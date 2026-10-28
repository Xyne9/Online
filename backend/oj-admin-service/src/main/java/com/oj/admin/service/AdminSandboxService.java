package com.oj.admin.service;

import com.oj.admin.config.AdminProperties;
import com.oj.admin.dao.AdminSandboxDao;
import com.oj.common.api.dto.admin.SandboxInstancePageDTO;
import com.oj.common.api.dto.admin.SandboxKillDTO;
import com.oj.common.api.dto.admin.SandboxLogDTO;
import com.oj.common.api.feign.JudgeInnerFeignClient;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.result.Result;
import com.oj.common.core.result.PageResult;
import feign.FeignException;
import feign.RetryableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 沙箱实例运维（契约 §7.5 #84~#86）。
 *
 * <p>终止委托给持有执行任务的判题节点，只有节点确认执行取消才回 200。
 * 列表与日志读取同一张实例表，管理服务不直接把运行中记录改成 KILLED。</p>
 *
 * <p><b>日志绝不编造</b>：{@code log_ref} 为空时返回空行数组 + 明确说明，
 * 不用 {@code [sandbox] no log available} 这类占位行冒充真实日志（agent.md §3.4）。</p>
 *
 * @author oj-cloud
 */
@Service
public class AdminSandboxService {

    private static final Logger log = LoggerFactory.getLogger(AdminSandboxService.class);

    /** 契约 §7.5.2：日志行数默认 200，上限 500 */
    private static final int LOG_DEFAULT_LINES = 200;

    private static final int LOG_MAX_LINES = 500;

    private final AdminSandboxDao dao;
    private final AdminProperties properties;
    private final JudgeInnerFeignClient judge;

    public AdminSandboxService(AdminSandboxDao dao, AdminProperties properties, JudgeInnerFeignClient judge) {
        this.dao = dao;
        this.properties = properties;
        this.judge = judge;
    }

    // ==================================================================
    // #84 列表 + 池统计
    // ==================================================================

    /**
     * 沙箱实例列表（池统计内嵌，不单开端点）。
     *
     * @param page     页码
     * @param pageSize 每页条数
     * @param state    running / queued / exited / killed（null 或 all 不过滤）
     * @param lang     cpp / java / python
     * @param kw       实例号 / 用户名 / 学号
     * @param autoRefresh 前端开关，服务端只回显不参与查询（保持契约入参形状）
     * @return 分页结果
     */
    public SandboxInstancePageDTO list(int page, int pageSize, String state, String lang,
                                       String kw, Boolean autoRefresh) {
        int safePage = PageResult.normalizePage(page);
        int safeSize = PageResult.normalizePageSize(pageSize);

        long total = dao.count(state, lang, kw);
        List<Map<String, Object>> rows = total == 0 ? List.of()
                : dao.page(state, lang, kw, (safePage - 1) * safeSize, safeSize);

        SandboxInstancePageDTO out = new SandboxInstancePageDTO();
        List<SandboxInstancePageDTO.Item> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            items.add(toItem(row));
        }
        out.setList(items);
        out.setTotal(total);
        out.setPage(safePage);
        out.setPageSize(safeSize);
        out.setHasNext((long) safePage * safeSize < total);
        out.setStats(buildStats());
        return out;
    }

    // ==================================================================
    // #85 容器日志
    // ==================================================================

    /**
     * 容器日志（stdout + stderr，最多 500 行）。
     *
     * @param sandboxInstanceId 对外句柄
     * @param lines             期望行数（默认 200，上限 500）
     * @param since             起始时刻（毫秒），null 表示不限
     * @return 日志；实例存在但无日志时返回空数组 + note
     */
    public SandboxLogDTO logs(String sandboxInstanceId, Integer lines, Long since) {
        Map<String, Object> row = dao.findByNo(sandboxInstanceId);
        if (row == null) {
            throw BusinessException.notFound("沙箱实例不存在：" + sandboxInstanceId);
        }
        int want = lines == null || lines <= 0 ? LOG_DEFAULT_LINES : Math.min(lines, LOG_MAX_LINES);

        SandboxLogDTO dto = new SandboxLogDTO();
        dto.setSandboxInstanceId(sandboxInstanceId);
        dto.setState(str(row, "state"));
        dto.setCollectedAt(System.currentTimeMillis());

        String logRef = str(row, "log_ref");
        if (logRef == null || logRef.isBlank()) {
            /*
              空日志是正常空态，不是 404：实例可能还在 QUEUED（还没产出任何 stdout），
              也可能判题链路尚未把日志落盘。这里明确说明原因，前端走空态即可。
            */
            dto.setLines(new ArrayList<>());
            dto.setTruncated(false);
            dto.setNote("该实例尚无日志采集（log_ref 为空）。实例状态：" + str(row, "state")
                    + "；日志由判题服务在实例结束时写入。");
            return dto;
        }

        // 本项目 log_ref 存的是「stdout/stderr 采样文本」本身（DDL 注释：容器stdout/stderr采样日志缓存），
        // 不是文件路径。整段按行切分，取最后 want 行（运维关心最近输出）。
        String[] all = logRef.split("\\R", -1);
        Long baseTs = toEpochMs(row.get("create_time"));
        List<SandboxLogDTO.Line> collected = new ArrayList<>();
        for (String text : all) {
            if (text.isEmpty()) {
                continue;
            }
            Long timestamp = baseTs;
            int separator = text.indexOf(' ');
            if (separator > 0) {
                try {
                    timestamp = java.time.Instant.parse(text.substring(0, separator)).toEpochMilli();
                } catch (java.time.format.DateTimeParseException ignored) {
                    // 历史日志没有逐行时间，沿用实例创建时间。
                }
            }
            if (since != null && timestamp != null && timestamp < since) continue;
            SandboxLogDTO.Line line = new SandboxLogDTO.Line();
            line.setTs(timestamp);
            line.setMsg(text);
            line.setHighlight(isHighlight(text));
            collected.add(line);
        }
        boolean truncated = collected.size() > want;
        int from = Math.max(0, collected.size() - want);

        dto.setLines(new ArrayList<>(collected.subList(from, collected.size())));
        dto.setTruncated(truncated);
        if (truncated) {
            dto.setNote("日志共 " + collected.size() + " 行，已按 lines=" + want + " 截取最近部分");
        }
        return dto;
    }

    // ==================================================================
    // #86 终止
    // ==================================================================

    /**
     * 终止实例并回收资源。
     *
     * @param sandboxInstanceId 对外句柄
     * @param reason            终止原因（由判题节点记录到实例日志与提交错误信息）
     * @return 终止结果
     */
    public SandboxKillDTO kill(String sandboxInstanceId, String reason) {
        Map<String, Object> row = dao.findByNo(sandboxInstanceId);
        if (row == null) {
            throw BusinessException.notFound("沙箱实例不存在：" + sandboxInstanceId);
        }
        String state = str(row, "state");
        if ("EXITED".equalsIgnoreCase(state) || "KILLED".equalsIgnoreCase(state)) {
            throw BusinessException.conflict("沙箱实例 " + sandboxInstanceId + " 已处于 "
                    + state + " 终态，无需终止");
        }

        Result<SandboxKillDTO> response;
        try {
            response = judge.killSandbox(sandboxInstanceId,
                    reason == null ? Map.of() : Map.of("reason", reason));
        } catch (RetryableException e) {
            if (e.getCause() instanceof java.net.SocketTimeoutException) {
                throw BusinessException.gatewayTimeout("判题节点终止请求超时，请查询实例状态");
            }
            throw BusinessException.badGateway("判题节点不可用，未确认实例终止");
        } catch (FeignException e) {
            if (e.status() == 404) throw BusinessException.notFound("沙箱实例不存在：" + sandboxInstanceId);
            if (e.status() == 409) throw BusinessException.conflict("实例已结束或没有可终止的执行任务");
            throw BusinessException.badGateway("判题节点未确认实例终止");
        }
        if (response == null || response.getCode() != 200 || response.getData() == null) {
            throw BusinessException.badGateway("判题节点未返回终止结果");
        }
        SandboxKillDTO out = response.getData();
        if (!sandboxInstanceId.equals(out.getSandboxInstanceId()) || !"KILLED".equals(out.getState())) {
            throw BusinessException.badGateway("判题节点返回的实例终态无效");
        }
        log.info("终止沙箱实例 {}（原状态 {}，原因 {}），回收 CPU={} mem={}KB",
                sandboxInstanceId, state, reason == null ? "-" : reason,
                out.getReleasedCpu(), out.getReleasedMemKb());
        return out;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private SandboxInstancePageDTO.Item toItem(Map<String, Object> row) {
        SandboxInstancePageDTO.Item item = new SandboxInstancePageDTO.Item();
        item.setSandboxInstanceId(str(row, "sandbox_instance_id"));
        item.setState(str(row, "state"));
        String lang = str(row, "language");
        item.setLanguage(lang);
        item.setLanguageLabel(languageLabel(lang));
        item.setUserId(row.get("user_id") == null ? null : lng(row, "user_id"));
        item.setUserName(str(row, "real_name"));
        item.setProblemId(row.get("problem_id") == null ? null : lng(row, "problem_id"));
        item.setSubmissionId(row.get("submission_id") == null ? null : lng(row, "submission_id"));
        item.setCpuPercent(intOf(row, "cpu_percent"));
        item.setMemKb(lng(row, "mem_used_kb"));
        item.setStartedAt(toEpochMs(row.get("started_at")));
        item.setAgeMs(ageMs(row));
        item.setExitCode(row.get("exit_code") == null ? null : intOf(row, "exit_code"));
        return item;
    }

    /**
     * 存活时长：运行中 = now − startedAt；已结束 = finishedAt − startedAt。
     *
     * @param row 实例行
     * @return 毫秒；未启动返回 null
     */
    private Long ageMs(Map<String, Object> row) {
        Long started = toEpochMs(row.get("started_at"));
        if (started == null) {
            return null;
        }
        Long finished = toEpochMs(row.get("finished_at"));
        long end = finished == null ? System.currentTimeMillis() : finished;
        return Math.max(0L, end - started);
    }

    private SandboxInstancePageDTO.Stats buildStats() {
        Map<String, Long> states = dao.stateBuckets();
        long running = states.getOrDefault("RUNNING", 0L);
        long queued = states.getOrDefault("QUEUED", 0L);
        long exited = states.getOrDefault("EXITED", 0L);
        long killed = states.getOrDefault("KILLED", 0L);
        long total = running + queued + exited + killed;

        SandboxInstancePageDTO.Stats stats = new SandboxInstancePageDTO.Stats();
        stats.setRunning((int) running);
        stats.setQueued((int) queued);
        stats.setExited((int) exited);
        stats.setKilled((int) killed);
        stats.setCapacity(properties.getSandboxCapacity());
        stats.setAvgWaitMs(dao.avgWaitMs());
        stats.setAvgRunMs(dao.avgRunMs());
        // 回收率 = 已进入终态的占比（EXITED + KILLED）/ 总数，保留一位小数
        if (total == 0) {
            stats.setRecycleRate(0.0);
        } else {
            stats.setRecycleRate(Math.round((exited + killed) * 1000.0 / total) / 10.0);
        }
        return stats;
    }

    /** 语言标签，与判题服务 JudgeAdapter.languageLabel 同源同值 */
    static String languageLabel(String language) {
        if (language == null) {
            return null;
        }
        return switch (language.trim().toLowerCase(Locale.ROOT)) {
            case "c" -> "C 11";
            case "cpp" -> "C++ 17";
            case "java" -> "Java 17";
            case "python", "py" -> "Python 3";
            default -> language;
        };
    }

    /** stderr / 错误关键字高亮，供前端日志抽屉上色 */
    private static boolean isHighlight(String line) {
        String s = line.toLowerCase(Locale.ROOT);
        return s.contains("error") || s.contains("fail") || s.contains("panic")
                || s.contains("stderr") || s.contains("timeout") || s.contains("killed");
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static long lng(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static int intOf(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static Long toEpochMs(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (value instanceof java.util.Date d) {
            return d.getTime();
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return null;
    }
}
