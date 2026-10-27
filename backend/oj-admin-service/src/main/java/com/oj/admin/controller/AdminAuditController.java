package com.oj.admin.controller;

import com.oj.admin.config.AdminProperties;
import com.oj.admin.service.AdminAuditService;
import com.oj.admin.support.AdminGuard;
import com.oj.common.api.dto.admin.AuditCallDetailDTO;
import com.oj.common.api.dto.admin.AuditCallPageDTO;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.redis.RedisKeys;
import com.oj.common.core.result.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * AI 调用审计（契约 §7.4 #81~#83）。
 *
 * <p><b>#83 CSV 导出为什么不用 {@code Result} 包</b>：契约要求
 * {@code text/csv} 流 + {@code Content-Disposition: attachment}，响应体就是文件本身。
 * 包成 JSON 信封浏览器会下载到一个 {@code {"code":200...}} 的假 CSV
 * （{@code api/admin.js:61-75} 用 {@code raw:true} 直接吃 Response，正好按这个约定写）。</p>
 *
 * <p><b>流式写出、不落临时文件</b>：边查边写 {@link HttpServletResponse#getOutputStream()}，
 * 全程不建 {@code File}。行数上限由 {@code limit}（默认 5000 / 上限 20000）控制，
 * 超上限直接 400，避免「导出把服务打挂」。</p>
 *
 * <p><b>限流 1 次/分钟</b>：走 Redis {@link RedisKeys#adminAuditExport()} SETNX，
 * 抢不到 429。该 key 原先是业务代码用 {@link RedisKeys#PREFIX} 自己拼的私货，
 * 已按 agent.md §4.4「禁止手写 Redis Key 字符串」收敛进 {@link RedisKeys} 第 13 类。</p>
 *
 * @author oj-cloud
 */
@RestController
@RequestMapping("/api/admin/audit/calls")
public class AdminAuditController {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditController.class);

    /** 导出限流 key（RedisKeys 第 13 类，TTL 见 {@link RedisKeys#TTL_ADMIN_AUDIT_EXPORT}） */
    private static final String EXPORT_RATE_KEY = RedisKeys.adminAuditExport();

    /** 文件名时间戳：ai-calls-202610272230.csv */
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final AdminAuditService service;
    private final AdminGuard guard;
    private final AdminProperties properties;
    private final StringRedisTemplate redis;

    public AdminAuditController(AdminAuditService service, AdminGuard guard,
                                AdminProperties properties, StringRedisTemplate redis) {
        this.service = service;
        this.guard = guard;
        this.properties = properties;
        this.redis = redis;
    }

    /**
     * #81 审计列表。
     *
     * @param page        页码
     * @param pageSize    每页条数
     * @param stage       阶段 1~4
     * @param channelCode 通道编码
     * @param guard       护栏：pass / rewrite / block / hit
     * @param kw          关键词
     * @param range       today / 7d / 30d
     * @param sortBy      at / ttft / tokens
     * @param sortOrder   asc / desc
     * @return 分页结果
     */
    @GetMapping
    public Result<AuditCallPageDTO> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Integer stage,
            @RequestParam(required = false) String channelCode,
            @RequestParam(required = false) String guard,
            @RequestParam(required = false) String kw,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder) {
        this.guard.requireAdmin("查看 AI 调用审计");
        return Result.ok(service.list(page, pageSize, stage, channelCode, guard, kw, range,
                sortBy, sortOrder));
    }

    /**
     * #82 调用详情。
     *
     * <p><b>路由顺序很重要</b>：{@code /export} 必须写在 {@code /{recordId}} 之前，
     * 否则 Spring 会把 {@code export} 当 {@code recordId} 解析成 Long 而抛 400。
     * 这里把 export 映射成显式字面量路径并且声明在前，两者不会冲突。</p>
     *
     * @param recordId 记录 ID
     * @return 详情
     */
    @GetMapping("/{recordId}")
    public Result<AuditCallDetailDTO> detail(@PathVariable Long recordId) {
        this.guard.requireAdmin("查看 AI 调用详情");
        return Result.ok(service.detail(recordId));
    }

    /**
     * #83 导出 CSV（受筛选条件约束）。
     *
     * @param stage       阶段
     * @param channelCode 通道编码
     * @param guard       护栏过滤
     * @param kw          关键词
     * @param range       区间
     * @param sortBy      排序字段
     * @param sortOrder   方向
     * @param limit       行数上限（默认 5000，上限 20000）
     * @param response    Servlet 响应（直接写流）
     * @throws IOException 写流失败
     */
    @GetMapping("/export")
    public void export(
            @RequestParam(required = false) Integer stage,
            @RequestParam(required = false) String channelCode,
            @RequestParam(required = false) String guard,
            @RequestParam(required = false) String kw,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder,
            @RequestParam(required = false) Integer limit,
            HttpServletResponse response) throws IOException {
        this.guard.requireAdmin("导出 AI 调用审计");

        int rows = limit == null || limit <= 0 ? properties.getExportDefaultLimit() : limit;
        if (rows > properties.getExportMaxLimit()) {
            throw BusinessException.badRequest(
                    "limit 超过上限 " + properties.getExportMaxLimit() + "（请求 " + rows + "）");
        }

        // 1 次/分钟限流：抢不到锁直接 429
        Boolean acquired = redis.opsForValue().setIfAbsent(EXPORT_RATE_KEY, "1",
                Duration.ofSeconds(Math.max(1, properties.getExportRateLimitSeconds())));
        if (!Boolean.TRUE.equals(acquired)) {
            throw BusinessException.tooManyRequests("导出过于频繁，每分钟最多 1 次");
        }

        String fileName = "ai-calls-" + LocalDateTime.now().format(FILE_STAMP) + ".csv";
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/csv;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // RFC 5987 双写：老浏览器读 filename，新浏览器读 filename*（中文名不乱码）
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + fileName + "\"; filename*=UTF-8''"
                        + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        response.setHeader("Cache-Control", "no-store");

        int[] written = {0};
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8), 8192)) {
            // UTF-8 BOM：Excel 打开中文不乱码
            writer.write('\uFEFF');
            writer.write(String.join(",", AdminAuditService.CSV_HEADERS));
            writer.write("\r\n");
            // 逐行写流：DAO 回调 → 这里立刻落 socket，全程不缓存整表、不落临时文件
            service.exportRows(stage, channelCode, guard, kw, range, sortBy, sortOrder, rows,
                    cells -> {
                        try {
                            for (int i = 0; i < cells.length; i++) {
                                if (i > 0) {
                                    writer.write(',');
                                }
                                writer.write(csvCell(cells[i]));
                            }
                            writer.write("\r\n");
                        } catch (IOException e) {
                            // 回调里不能抛受检异常：包成非受检，由外层统一处理
                            throw new java.io.UncheckedIOException(e);
                        }
                        written[0]++;
                    });
            writer.flush();
        } catch (java.io.UncheckedIOException e) {
            // 客户端中途断开（取消下载）也走这里：记日志后按未预期异常上抛，
            // 由 GlobalExceptionHandler 收敛成 500 + traceId（此时响应已提交，浏览器侧看到的是中断）。
            log.warn("导出审计 CSV 过程中写流失败（可能客户端已断开）：{}",
                    e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            throw e;
        }
        log.info("导出审计 CSV 完成：{} 行，文件 {}", written[0], fileName);
    }

    /**
     * CSV 单元格转义（RFC 4180）：含逗号/引号/换行时整体加引号，内部引号翻倍。
     *
     * <p>不转义的话，{@code note} 里一句带逗号的话就会把 12 列撑成 13 列，
     * Excel 打开整张表错位。</p>
     *
     * @param raw 原值
     * @return 转义后的单元格
     */
    private static String csvCell(String raw) {
        if (raw == null || "null".equals(raw)) {
            return "";
        }
        // 顺手清掉换行：CSV 里跨行会让行数对不上
        String value = raw.replace("\r", " ").replace("\n", " ");
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
