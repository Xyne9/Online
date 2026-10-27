package com.oj.admin.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 沙箱实例表（{@code judge_sandbox_instance}）的查询与终止。
 *
 * <p><b>终止必须真实生效</b>（契约 §7.5.3）：{@link #kill} 是一条带状态前置条件的
 * {@code UPDATE}，只有从 {@code QUEUED}/{@code RUNNING} 才允许迁移到 {@code KILLED}。
 * 用「影响行数 = 0」判定「已被他人结束 / 本来就 EXITED」，由 Service 转 409。
 * 这样并发双击「终止」也只有一次生效，不会出现「两次终止都回 200」。</p>
 *
 * @author oj-cloud
 */
@Repository
public class AdminSandboxDao {

    private static final String SELECT_COLUMNS = """
            SELECT s.id, s.sandbox_instance_id, s.submission_id, s.user_id, u.real_name,
                   s.problem_id, s.language, s.state, s.judge_node, s.container_id,
                   s.cpu_limit, s.mem_limit_kb, s.cpu_percent, s.mem_used_kb, s.exit_code,
                   s.started_at, s.finished_at, s.last_heartbeat_at, s.log_ref, s.create_time
              FROM judge_sandbox_instance s
              LEFT JOIN sys_user u ON u.id = s.user_id
            """;

    private final JdbcTemplate db;

    public AdminSandboxDao(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * 分页查沙箱实例。
     *
     * @param state  四值状态（QUEUED/RUNNING/EXITED/KILLED），null 或 all 不过滤
     * @param lang   语言，null 或 all 不过滤
     * @param kw     关键词（实例号 / 用户名 / 学号），null 不过滤
     * @param offset 偏移
     * @param limit  条数
     * @return 行数据
     */
    public List<Map<String, Object>> page(String state, String lang, String kw, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(state, lang, kw, args);
        args.add(limit);
        args.add(offset);
        return db.queryForList(SELECT_COLUMNS + where
                + " ORDER BY FIELD(s.state,'RUNNING','QUEUED','KILLED','EXITED'), s.create_time DESC LIMIT ? OFFSET ?",
                args.toArray());
    }

    /**
     * 条件命中数。
     *
     * @param state 状态过滤
     * @param lang  语言过滤
     * @param kw    关键词
     * @return 总条数
     */
    public long count(String state, String lang, String kw) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(state, lang, kw, args);
        Long n = db.queryForObject("""
                SELECT COUNT(*) FROM judge_sandbox_instance s
                  LEFT JOIN sys_user u ON u.id = s.user_id
                """ + where, Long.class, args.toArray());
        return n == null ? 0L : n;
    }

    private String buildWhere(String state, String lang, String kw, List<Object> args) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1");
        if (state != null && !state.isBlank() && !"all".equalsIgnoreCase(state)) {
            sb.append(" AND s.state = ?");
            args.add(state.trim().toUpperCase());
        }
        if (lang != null && !lang.isBlank() && !"all".equalsIgnoreCase(lang)) {
            sb.append(" AND s.language = ?");
            args.add(lang.trim().toLowerCase());
        }
        if (kw != null && !kw.isBlank()) {
            sb.append(" AND (s.sandbox_instance_id LIKE ? OR u.real_name LIKE ? OR u.username LIKE ?)");
            String like = "%" + kw.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        return sb.toString();
    }

    /**
     * 按对外句柄查单个实例。
     *
     * @param sandboxInstanceId 对外句柄，如 sbx-0001
     * @return 行数据；不存在返回 {@code null}
     */
    public Map<String, Object> findByNo(String sandboxInstanceId) {
        List<Map<String, Object>> rows = db.queryForList(SELECT_COLUMNS + " WHERE s.sandbox_instance_id = ?",
                sandboxInstanceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 池状态分桶计数。
     *
     * @return {@code state → 数量}
     */
    public Map<String, Long> stateBuckets() {
        Map<String, Long> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT state, COUNT(*) AS cnt FROM judge_sandbox_instance GROUP BY state
                """);
        for (Map<String, Object> row : rows) {
            Object st = row.get("state");
            Object cnt = row.get("cnt");
            out.put(st == null ? "UNKNOWN" : String.valueOf(st),
                    cnt instanceof Number n ? n.longValue() : 0L);
        }
        return out;
    }

    /**
     * 平均排队时长（毫秒）：{@code started_at − create_time}。
     *
     * <p><b>为什么要加 {@code started_at >= create_time} 的前置条件</b>：实测库里存在
     * {@code create_time} 晚于 {@code started_at} 的行（种子数据先写了 started_at，
     * 之后 INSERT 时 create_time 取 CURRENT_TIMESTAMP）。不排除这类行，
     * 平均值会被算成负数（实测 −520692000ms），前端会显示「排队 -6 天」。
     * 这是数据质量问题，不是接口口径问题，故只统计时序合理的行。</p>
     *
     * @return 平均值；无有效数据返回 {@code null}
     */
    public Long avgWaitMs() {
        Double v = db.queryForObject("""
                SELECT AVG(TIMESTAMPDIFF(SECOND, create_time, started_at)) * 1000.0
                  FROM judge_sandbox_instance
                 WHERE started_at IS NOT NULL AND started_at >= create_time
                """, Double.class);
        return v == null ? null : Math.round(v);
    }

    /**
     * 平均运行时长（毫秒）：{@code finished_at − started_at}。
     *
     * @return 平均值；无数据返回 {@code null}
     */
    public Long avgRunMs() {
        Double v = db.queryForObject("""
                SELECT AVG(TIMESTAMPDIFF(SECOND, started_at, finished_at)) * 1000.0
                  FROM judge_sandbox_instance
                 WHERE started_at IS NOT NULL AND finished_at IS NOT NULL
                """, Double.class);
        return v == null ? null : Math.round(v);
    }

    /**
     * 终止实例（真实写库）。
     *
     * <p>{@code WHERE state IN ('QUEUED','RUNNING')} 是关键：它不是「查一次再改」，
     * 而是让数据库原子地做「状态前置条件」校验。</p>
     *
     * @param sandboxInstanceId 对外句柄
     * @param exitCode          退出码（契约要求 137）
     * @return 影响行数（0 表示实例已处于终态或不存在）
     */
    public int kill(String sandboxInstanceId, int exitCode) {
        return db.update("""
                UPDATE judge_sandbox_instance
                   SET state = 'KILLED',
                       exit_code = ?,
                       finished_at = NOW(),
                       cpu_percent = 0,
                       mem_used_kb = 0,
                       last_heartbeat_at = NOW()
                 WHERE sandbox_instance_id = ?
                   AND state IN ('QUEUED', 'RUNNING')
                """, exitCode, sandboxInstanceId);
    }

    /**
     * 统计今日执行的沙箱实例数（供大盘趋势/统计复用）。
     *
     * @return 实例数
     */
    public long countAll() {
        Long n = db.queryForObject("SELECT COUNT(*) FROM judge_sandbox_instance", Long.class);
        return n == null ? 0L : n;
    }
}
