package com.oj.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.admin.dao.AdminAuditDao;
import com.oj.admin.support.SecretResolver;
import com.oj.common.api.dto.admin.AuditCallDetailDTO;
import com.oj.common.api.dto.admin.AuditCallPageDTO;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.result.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 调用审计（契约 §7.4 #81~#83）。
 *
 * <p><b>三分类派生的唯一实现处</b>：{@code guardrail_action}（0/1/2）→
 * {@code guardCode}（pass/block/rewrite）的映射只写在这里，列表、详情、CSV 三处共用，
 * 避免出现「列表说 block、导出说拦截」这种两套口径。</p>
 *
 * <p><b>hitDist 的 code 命名</b>：契约示例是 {@code leak / jailbreak / sensitive}，
 * DB 字典是 {@code LEAK_RATE / JAILBREAK / SENSITIVE_WORD}（§8.6 DDL）。
 * 映射见 {@link #HIT_DIST_CODES}；{@code OVER_LENGTH_CODE} 不在契约三分类里，
 * 归入 {@code leak} 桶（它同样是「把题解整段交出去」被拦的场景），这个归并关系写在这里，
 * 不隐藏也不发明第四个桶。</p>
 *
 * @author oj-cloud
 */
@Service
public class AdminAuditService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);

    /** 契约 §7.4.1 的 summary.hitDist 三分类 ↔ DB guard_reason 字典的映射 */
    private static final Map<String, List<String>> HIT_DIST_CODES = new LinkedHashMap<>();

    static {
        HIT_DIST_CODES.put("leak", List.of("LEAK_RATE", "OVER_LENGTH_CODE"));
        HIT_DIST_CODES.put("jailbreak", List.of("JAILBREAK"));
        HIT_DIST_CODES.put("sensitive", List.of("SENSITIVE_WORD"));
    }

    /** 契约 §7.4.1 允许的排序字段（白名单，防 SQL 注入） */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "at", "a.create_time",
            "ttft", "a.ttft_ms",
            "tokens", "a.tokens_in");

    /** 阶段中文名（与 AI 服务 AiHistoryService.STAGES 同源同值） */
    private static final String[] STAGES = {"", "读题拆解", "编码引导", "排错诊断", "AC 复盘"};

    /** 守卫过滤 → guardrail_action；hit 表示「任意非 0」 */
    private static final Map<String, Integer> GUARD_ACTIONS = Map.of(
            "pass", 0,
            "block", 1,
            "rewrite", 2);

    /** range 参数 → 天数 */
    private static final Map<String, Integer> RANGES = Map.of(
            "today", 1,
            "7d", 7,
            "30d", 30);

    private final AdminAuditDao dao;
    private final SecretResolver secrets;
    private final ObjectMapper json;

    public AdminAuditService(AdminAuditDao dao, SecretResolver secrets, ObjectMapper json) {
        this.dao = dao;
        this.secrets = secrets;
        this.json = json;
    }

    // ==================================================================
    // #81 列表
    // ==================================================================

    /**
     * 审计列表。
     *
     * @param page        页码
     * @param pageSize    每页条数
     * @param stage       阶段 1~4（null / 0 不过滤）
     * @param channelCode 通道编码
     * @param guard       pass / rewrite / block / hit
     * @param kw          关键词
     * @param range       today / 7d / 30d（null 不限）
     * @param sortBy      at / ttft / tokens
     * @param sortOrder   asc / desc
     * @return 分页结果
     */
    public AuditCallPageDTO list(int page, int pageSize, Integer stage, String channelCode,
                                 String guard, String kw, String range,
                                 String sortBy, String sortOrder) {
        AdminAuditDao.Query q = buildQuery(page, pageSize, stage, channelCode, guard, kw, range,
                sortBy, sortOrder);
        long total = dao.count(q);
        List<Map<String, Object>> rows = total == 0 ? List.of() : dao.page(q);

        AuditCallPageDTO out = new AuditCallPageDTO();
        List<AuditCallPageDTO.Item> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            items.add(toItem(row));
        }
        out.setList(items);
        out.setTotal(total);
        out.setPage(PageResult.normalizePage(page));
        out.setPageSize(PageResult.normalizePageSize(pageSize));
        out.setHasNext((long) PageResult.normalizePage(page) * PageResult.normalizePageSize(pageSize) < total);
        out.setSummary(buildSummary());
        return out;
    }

    // ==================================================================
    // #82 详情
    // ==================================================================

    /**
     * 调用详情（完整 prompt / 回复 / 护栏判定 / 关联判题链）。
     *
     * @param recordId 记录 ID
     * @return 详情
     */
    public AuditCallDetailDTO detail(Long recordId) {
        Map<String, Object> row = dao.findById(recordId);
        if (row == null) {
            throw BusinessException.notFound("调用记录不存在：" + recordId);
        }

        AuditCallDetailDTO dto = new AuditCallDetailDTO();
        dto.setRecordId(lng(row, "id"));
        dto.setAt(toEpochMs(row.get("create_time")));

        AuditCallDetailDTO.User user = new AuditCallDetailDTO.User();
        user.setId(row.get("user_id") == null ? null : lng(row, "user_id"));
        user.setName(str(row, "real_name"));
        user.setStuNo(str(row, "username"));
        dto.setUser(user);

        dto.setStage(row.get("stage_type") == null ? null : intOf(row, "stage_type"));
        dto.setStageName(stageName(dto.getStage()));
        dto.setChannelCode(str(row, "selected_channel"));
        dto.setModelName(str(row, "actual_model"));
        dto.setRoute(route(str(row, "provider_type")));
        dto.setIsFallback(intOf(row, "is_fallback") == 1);
        dto.setTtftMs(row.get("ttft_ms") == null ? null : lng(row, "ttft_ms"));
        dto.setTokensIn(lng(row, "tokens_in"));
        dto.setTokensOut(lng(row, "tokens_out"));
        dto.setCostCents(lng(row, "cost_cents"));
        dto.setPromptText(str(row, "user_message"));
        dto.setReplyText(str(row, "ai_response"));
        Integer action = row.get("guardrail_action") == null ? null : intOf(row, "guardrail_action");
        dto.setGuardAction(action);
        dto.setGuardCode(AdminChannelService.guardCode(action));
        dto.setGuardReason(str(row, "guard_reason"));
        dto.setGuardReplacementText(str(row, "guard_replacement"));
        dto.setTraceId(str(row, "trace_id"));

        // 关联判题链：由 judge_submission.case_details 真实展开，没有关联就是空数组
        Long submissionId = row.get("submission_id") == null ? null : lng(row, "submission_id");
        Map<String, Object> submission = dao.findSubmission(submissionId);
        dto.setChain(chainOf(submission));
        dto.setSandboxInstanceId(submission == null ? null : str(submission, "sandbox_instance_id"));
        return dto;
    }

    // ==================================================================
    // #83 CSV 导出
    // ==================================================================

    /** CSV 固定 12 列表头（契约 §7.4.3「列头固定 12 列」） */
    public static final String[] CSV_HEADERS = {
            "recordId", "at", "userId", "userName", "stage", "channelCode",
            "modelName", "route", "ttftMs", "tokensIn", "tokensOut", "guardCode"
    };

    /**
     * 导出数据（**真流式**：每行回调，不把 20000 行堆进 List）。
     *
     * <p>契约 §7.4.3 要求「流式写出，不落临时文件」。这里把 DAO 的逐行回调直接透给
     * Controller 的写流回调，三层之间不缓存整表。</p>
     *
     * @param stage       阶段
     * @param channelCode 通道编码
     * @param guard       护栏过滤
     * @param kw          关键词
     * @param range       区间
     * @param sortBy      排序字段
     * @param sortOrder   方向
     * @param limit       行数上限（已由 Controller 校验过上限）
     * @param consumer    每行的 12 列写出行
     * @return 实际导出行数
     */
    public int exportRows(Integer stage, String channelCode, String guard, String kw,
                          String range, String sortBy, String sortOrder, int limit,
                          java.util.function.Consumer<String[]> consumer) {
        AdminAuditDao.Query q = buildQuery(1, limit, stage, channelCode, guard, kw, range,
                sortBy, sortOrder);
        // 导出不分页：offset 归零、limit 即上限
        AdminAuditDao.Query unlimited = new AdminAuditDao.Query(q.stage(), q.channelCode(),
                q.guardAction(), q.onlyHits(), q.kw(), q.from(), q.to(),
                q.sortColumn(), q.sortDesc(), 0, limit);

        int[] count = {0};
        dao.exportEach(unlimited, row -> {
            Integer action = row.get("guardrail_action") == null ? null : intOf(row, "guardrail_action");
            consumer.accept(new String[]{
                    String.valueOf(lng(row, "id")),
                    formatDateTime(row.get("create_time")),
                    str(row, "user_id"),
                    str(row, "real_name"),
                    str(row, "stage_type"),
                    str(row, "selected_channel"),
                    str(row, "actual_model"),
                    route(str(row, "provider_type")),
                    str(row, "ttft_ms"),
                    str(row, "tokens_in"),
                    str(row, "tokens_out"),
                    AdminChannelService.guardCode(action)
            });
            count[0]++;
        });
        log.info("审计 CSV 导出 {} 行（stage={} channel={} guard={} kw={} range={}）",
                count[0], stage, channelCode, guard, kw, range);
        return count[0];
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private AdminAuditDao.Query buildQuery(int page, int pageSize, Integer stage, String channelCode,
                                           String guard, String kw, String range,
                                           String sortBy, String sortOrder) {
        int safePage = PageResult.normalizePage(page);
        int safeSize = PageResult.normalizePageSize(pageSize);

        Integer stageFilter = null;
        if (stage != null && stage != 0) {
            if (stage < 1 || stage > 4) {
                throw BusinessException.badRequest("stage 只支持 1~4");
            }
            stageFilter = stage;
        }

        Integer guardAction = null;
        boolean onlyHits = false;
        if (guard != null && !guard.isBlank()) {
            String g = guard.trim().toLowerCase(Locale.ROOT);
            if ("hit".equals(g)) {
                // hit = 任意非 0（block 或 rewrite 都算命中）
                onlyHits = true;
            } else if (GUARD_ACTIONS.containsKey(g)) {
                guardAction = GUARD_ACTIONS.get(g);
            } else {
                throw BusinessException.badRequest("guard 只支持 pass / rewrite / block / hit");
            }
        }

        return compose(safePage, safeSize, stageFilter, channelCode, guardAction, onlyHits,
                kw, range, sortBy, sortOrder);
    }

    private AdminAuditDao.Query compose(int page, int pageSize, Integer stage, String channelCode,
                                        Integer guardAction, boolean onlyHits,
                                        String kw, String range, String sortBy, String sortOrder) {
        String sortKey = (sortBy == null || sortBy.isBlank()) ? "at" : sortBy.trim().toLowerCase(Locale.ROOT);
        String column = SORT_COLUMNS.get(sortKey);
        if (column == null) {
            throw BusinessException.badRequest("sortBy 只支持 at / ttft / tokens");
        }
        boolean desc = !"asc".equalsIgnoreCase(sortOrder);

        LocalDateTime from = null;
        LocalDateTime to = null;
        if (range != null && !range.isBlank()) {
            String r = range.trim().toLowerCase(Locale.ROOT);
            Integer days = RANGES.get(r);
            if (days == null) {
                throw BusinessException.badRequest("range 只支持 today / 7d / 30d");
            }
            from = LocalDate.now().minusDays(days - 1L).atStartOfDay();
            to = LocalDate.now().plusDays(1).atStartOfDay();
        }
        // 见 DAO.buildWhere：onlyHits 会生成 guardrail_action <> 0，与 guardAction 互斥
        return new AdminAuditDao.Query(stage, blankToNull(channelCode), guardAction, onlyHits,
                blankToNull(kw), from, to, column, desc, (page - 1) * pageSize, pageSize);
    }

    private AuditCallPageDTO.Item toItem(Map<String, Object> row) {
        AuditCallPageDTO.Item item = new AuditCallPageDTO.Item();
        item.setRecordId(lng(row, "id"));
        item.setAt(toEpochMs(row.get("create_time")));
        item.setUserId(row.get("user_id") == null ? null : lng(row, "user_id"));
        item.setUserName(str(row, "real_name"));
        Integer stage = row.get("stage_type") == null ? null : intOf(row, "stage_type");
        item.setStage(stage);
        item.setStageName(stageName(stage));
        item.setChannelCode(str(row, "selected_channel"));
        item.setModelName(str(row, "actual_model"));
        item.setRoute(route(str(row, "provider_type")));
        item.setTtftMs(row.get("ttft_ms") == null ? null : lng(row, "ttft_ms"));
        item.setTokensIn(lng(row, "tokens_in"));
        item.setTokensOut(lng(row, "tokens_out"));
        item.setCostCents(lng(row, "cost_cents"));
        Integer action = row.get("guardrail_action") == null ? null : intOf(row, "guardrail_action");
        item.setGuardAction(action);
        item.setGuardCode(AdminChannelService.guardCode(action));
        item.setGuardReason(str(row, "guard_reason"));
        // note 用 session_id：a-audit 的「备注」列需要一句能定位会话的短文本
        item.setNote(str(row, "session_id"));
        item.setSubmissionId(row.get("submission_id") == null ? null : lng(row, "submission_id"));
        item.setTraceId(str(row, "trace_id"));
        return item;
    }

    private AuditCallPageDTO.Summary buildSummary() {
        LocalDateTime today = LocalDate.now().atStartOfDay();
        AuditCallPageDTO.Summary summary = new AuditCallPageDTO.Summary();
        summary.setTodayTotal(dao.todayTotal(today));

        Map<String, Long> raw = dao.todayHitDist(today);
        List<AuditCallPageDTO.HitDist> dist = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : HIT_DIST_CODES.entrySet()) {
            long count = 0L;
            for (String reason : e.getValue()) {
                count += raw.getOrDefault(reason, 0L);
            }
            AuditCallPageDTO.HitDist hit = new AuditCallPageDTO.HitDist();
            hit.setCode(e.getKey());
            hit.setCount(count);
            dist.add(hit);
        }
        summary.setHitDist(dist);
        return summary;
    }

    /**
     * 把 {@code judge_submission.case_details} 的 JSON 展开成判题链。
     *
     * @param submission 提交行（可为 null）
     * @return 链节点；无关联提交或 JSON 不可解析时返回空数组（不回填假数据）
     */
    private List<AuditCallDetailDTO.ChainNode> chainOf(Map<String, Object> submission) {
        if (submission == null) {
            return new ArrayList<>();
        }
        Object raw = submission.get("case_details");
        if (raw == null) {
            return new ArrayList<>();
        }
        List<AuditCallDetailDTO.ChainNode> chain = new ArrayList<>();
        try {
            JsonNode arr = json.readTree(String.valueOf(raw));
            if (arr != null && arr.isArray()) {
                for (JsonNode node : arr) {
                    AuditCallDetailDTO.ChainNode c = new AuditCallDetailDTO.ChainNode();
                    c.setSeqNo(node.hasNonNull("seqNo") ? node.get("seqNo").asInt() : null);
                    c.setKind(node.hasNonNull("kind") ? node.get("kind").asText() : null);
                    c.setStatus(node.hasNonNull("status") ? node.get("status").asText() : null);
                    chain.add(c);
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("解析 case_details 失败，返回空判题链：{}", e.getMessage());
        }
        return chain;
    }

    /** provider_type → 契约的 route（local / cloud） */
    static String route(String providerType) {
        return "OLLAMA".equalsIgnoreCase(providerType) ? "local" : "cloud";
    }

    static String stageName(Integer stage) {
        if (stage == null || stage < 1 || stage >= STAGES.length) {
            return null;
        }
        return STAGES[stage];
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private String formatDateTime(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        return String.valueOf(value);
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

    /** DATETIME / Timestamp / LocalDateTime → 毫秒时间戳 */
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

    /**
     * 密钥解析器可用性自检（供启动日志用，不参与业务）。
     *
     * @param raw 测试用占位符
     * @return 是否已配置
     */
    boolean secretProbe(String raw) {
        return secrets.configured(raw);
    }
}
