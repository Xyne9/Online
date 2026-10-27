package com.oj.admin.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 调用审计表（{@code ai_chat_record} + {@code sys_user} + {@code judge_submission}）的只读查询。
 *
 * <p><b>为什么这里能直连</b>：见 {@link AdminChannelDao} 的类注释（同一 {@code oj_cloud} 库、
 * 纯运维读、不引入跨进程缓存一致性问题）。</p>
 *
 * <p><b>筛选条件全部下推 SQL</b>：契约 §7.4.1 有 stage / channelCode / guard / kw / range
 * 五个维度，前端 a-audit 的 pager 是真分页（`a-audit.html:412-418`），
 * 若在内存里过滤会出现「第 2 页条数与 total 对不上」。</p>
 *
 * @author oj-cloud
 */
@Repository
public class AdminAuditDao {

    private final JdbcTemplate db;

    public AdminAuditDao(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * 审计列表查询条件。用 record + 静态构造器，避免十几个同名 String 参数传错位置。
     *
     * @param stage       阶段 1~4，null 不过滤
     * @param channelCode 通道编码，null 不过滤
     * @param guardAction 护栏动作 0/1/2，null 不过滤（与 {@code onlyHits} 互斥）
     * @param onlyHits    true 表示「任意护栏命中」（{@code guardrail_action <> 0}），
     *                    对应契约 §7.4.1 的 {@code guard=hit}
     * @param kw          关键词（匹配 real_name / username / session_id），null 不过滤
     * @param from        起始时刻，null 不过滤
     * @param to          结束时刻，null 不过滤
     * @param sortColumn  已白名单化的排序列（a.create_time / a.ttft_ms / a.tokens_in）
     * @param sortDesc    是否倒序
     * @param offset      偏移
     * @param limit       条数
     */
    public record Query(Integer stage, String channelCode, Integer guardAction, boolean onlyHits,
                        String kw, LocalDateTime from, LocalDateTime to,
                        String sortColumn, boolean sortDesc, int offset, int limit) {
    }

    /** 与列表查询等价的 count 条件（同 where 子句，无 order/limit） */
    private record Where(String sql, List<Object> args) {
    }

    private Where buildWhere(Query q) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (q.stage() != null) {
            sb.append(" AND a.stage_type = ?");
            args.add(q.stage());
        }
        if (q.channelCode() != null && !q.channelCode().isBlank()) {
            sb.append(" AND a.selected_channel = ?");
            args.add(q.channelCode());
        }
        if (q.guardAction() != null) {
            sb.append(" AND a.guardrail_action = ?");
            args.add(q.guardAction());
        } else if (q.onlyHits()) {
            // 契约 guard=hit：任意拦截/改写（0 是 pass，不算命中）
            sb.append(" AND a.guardrail_action <> 0");
        }
        if (q.kw() != null && !q.kw().isBlank()) {
            sb.append(" AND (u.real_name LIKE ? OR u.username LIKE ? OR a.session_id LIKE ?)");
            String like = "%" + q.kw().trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (q.from() != null) {
            sb.append(" AND a.create_time >= ?");
            args.add(q.from());
        }
        if (q.to() != null) {
            sb.append(" AND a.create_time < ?");
            args.add(q.to());
        }
        return new Where(sb.toString(), args);
    }

    /**
     * 分页查审计记录（联表取用户名与模型通道类型，用于派生 route）。
     *
     * @param q 查询条件
     * @return 记录行（下划线列名）
     */
    public List<Map<String, Object>> page(Query q) {
        Where w = buildWhere(q);
        List<Object> args = new ArrayList<>(w.args());
        args.add(q.limit());
        args.add(q.offset());
        String order = q.sortColumn() + (q.sortDesc() ? " DESC" : " ASC") + ", a.id DESC";
        return db.queryForList("""
                SELECT a.id, a.session_id, a.user_id, u.real_name, u.username,
                       a.problem_id, a.submission_id, a.trace_id, a.stage_type, a.hint_level,
                       a.selected_channel, a.actual_model, a.is_fallback, a.guardrail_action,
                       a.guard_reason, a.ttft_ms, a.tokens_in, a.tokens_out, a.cost_cents,
                       a.create_time, c.provider_type
                  FROM ai_chat_record a
                  LEFT JOIN sys_user u ON u.id = a.user_id
                  LEFT JOIN ai_model_config c ON c.channel_code = a.selected_channel
                """ + w.sql() + " ORDER BY " + order + " LIMIT ? OFFSET ?", args.toArray());
    }

    /**
     * 命中条数。
     *
     * @param q 查询条件（忽略分页与排序）
     * @return 总条数
     */
    public long count(Query q) {
        Where w = buildWhere(q);
        Long n = db.queryForObject("""
                SELECT COUNT(*)
                  FROM ai_chat_record a
                  LEFT JOIN sys_user u ON u.id = a.user_id
                """ + w.sql(), Long.class, w.args().toArray());
        return n == null ? 0L : n;
    }

    /**
     * 取单条调用详情（含完整 prompt / 回复）。
     *
     * @param recordId 记录 ID
     * @return 行数据；不存在返回 {@code null}
     */
    public Map<String, Object> findById(Long recordId) {
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT a.id, a.session_id, a.user_id, u.real_name, u.username,
                       a.problem_id, a.assignment_id, a.submission_id, a.trace_id,
                       a.stage_type, a.hint_level, a.user_message, a.numbered_code,
                       a.selected_channel, a.actual_model, a.is_fallback, a.tool_called,
                       a.ai_response, a.guardrail_action, a.guard_reason, a.guard_replacement,
                       a.ttft_ms, a.tokens_in, a.tokens_out, a.cost_cents, a.create_time,
                       c.provider_type
                  FROM ai_chat_record a
                  LEFT JOIN sys_user u ON u.id = a.user_id
                  LEFT JOIN ai_model_config c ON c.channel_code = a.selected_channel
                 WHERE a.id = ?
                """, recordId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 取关联提交的逐测试点明细（解析 {@code case_details} JSON 用）。
     *
     * @param submissionId 提交 ID
     * @return 行数据；不存在返回 {@code null}
     */
    public Map<String, Object> findSubmission(Long submissionId) {
        if (submissionId == null) {
            return null;
        }
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT id, status, case_details, sandbox_instance_id
                  FROM judge_submission WHERE id = ?
                """, submissionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 今日调用总数（不受筛选影响，供 summary）。
     *
     * @param since 今日 00:00
     * @return 调用数
     */
    public long todayTotal(LocalDateTime since) {
        Long n = db.queryForObject("SELECT COUNT(*) FROM ai_chat_record WHERE create_time >= ?",
                Long.class, since);
        return n == null ? 0L : n;
    }

    /**
     * 今日护栏命中原因分布（供 {@code summary.hitDist}）。
     *
     * @param since 今日 00:00
     * @return {@code guard_reason → 次数}
     */
    public Map<String, Long> todayHitDist(LocalDateTime since) {
        Map<String, Long> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT guard_reason, COUNT(*) AS cnt
                  FROM ai_chat_record
                 WHERE create_time >= ? AND guardrail_action <> 0
                 GROUP BY guard_reason
                """, since);
        for (Map<String, Object> row : rows) {
            Object reason = row.get("guard_reason");
            Object cnt = row.get("cnt");
            out.put(reason == null ? "NONE" : String.valueOf(reason),
                    cnt instanceof Number n ? n.longValue() : 0L);
        }
        return out;
    }

    /**
     * CSV 导出用：按筛选条件**逐行回调**（真流式，不把结果集堆进内存）。
     *
     * <p>用 {@link org.springframework.jdbc.core.RowCallbackHandler} 而不是
     * {@code queryForList}：导出上限 20000 行，一次性 list 化在 8 GB 机器上
     * 会白占几 MB 堆，更重要的是「流式」是契约 §7.4.3 的明确要求。
     * 全程不落临时文件，回调里直接写 HTTP 响应流。</p>
     *
     * @param q        查询条件（offset 忽略，limit 为导出上限）
     * @param consumer 每行的处理回调
     */
    public void exportEach(Query q, java.util.function.Consumer<java.util.Map<String, Object>> consumer) {
        String where = buildWhere(q).sql();
        List<Object> args = new ArrayList<>(buildWhere(q).args());
        args.add(q.limit());
        String order = q.sortColumn() + (q.sortDesc() ? " DESC" : " ASC") + ", a.id DESC";
        String sql = """
                SELECT a.id, a.create_time, a.user_id, u.real_name, u.username,
                       a.stage_type, a.selected_channel, a.actual_model,
                       a.is_fallback, a.ttft_ms, a.tokens_in, a.tokens_out, a.cost_cents,
                       a.guardrail_action, a.guard_reason, a.submission_id, a.trace_id,
                       c.provider_type
                  FROM ai_chat_record a
                  LEFT JOIN sys_user u ON u.id = a.user_id
                  LEFT JOIN ai_model_config c ON c.channel_code = a.selected_channel
                """ + where + " ORDER BY " + order + " LIMIT ?";
        db.query(sql, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getObject("id"));
            row.put("create_time", rs.getObject("create_time"));
            row.put("user_id", rs.getObject("user_id"));
            row.put("real_name", rs.getString("real_name"));
            row.put("username", rs.getString("username"));
            row.put("stage_type", rs.getObject("stage_type"));
            row.put("selected_channel", rs.getString("selected_channel"));
            row.put("actual_model", rs.getString("actual_model"));
            row.put("guardrail_action", rs.getObject("guardrail_action"));
            row.put("ttft_ms", rs.getObject("ttft_ms"));
            row.put("tokens_in", rs.getObject("tokens_in"));
            row.put("tokens_out", rs.getObject("tokens_out"));
            row.put("provider_type", rs.getString("provider_type"));
            consumer.accept(row);
        }, args.toArray());
    }
}
