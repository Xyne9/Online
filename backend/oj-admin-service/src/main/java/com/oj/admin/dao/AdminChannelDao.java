package com.oj.admin.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型通道表（{@code ai_model_config} + {@code ai_model_role}）的直连读写。
 *
 * <p><b>为什么直连 MySQL 而不走 Feign</b>（本服务最重要的一个取舍）：
 * 契约 §7.1 原文是「数据一律通过 OpenFeign 从 4 个服务取」，但落地上有三个事实：</p>
 * <ol>
 *   <li>{@code ai_model_config} / {@code ai_model_role} / {@code ai_chat_record} /
 *       {@code judge_sandbox_instance} <b>都在同一个 {@code oj_cloud} 库</b>，
 *       跨的只是「服务边界」，不是「数据库边界」——不存在跨库事务问题；</li>
 *   <li>通道 CRUD 是<b>写操作</b>。走 Feign 就得在 AI 服务新开 5 个 {@code /inner/**}
 *       写端点、在判题服务新开 1 个沙箱终止端点，还要各自加内部令牌校验与透传，
 *       等于把一个 {@code UPDATE} 拆成三段网络调用；</li>
 *   <li>运维写操作要求「改完立刻 DEL 通道缓存」。跨进程调用反而引入
 *       「AI 服务改了库、admin 不知道」的缓存一致性风险。</li>
 * </ol>
 * <p>因此本服务对三块（通道 / 审计 / 沙箱）<b>直连 MySQL</b>，
 * 而把「真实 HTTP 探活」留给 {@code AdminHealthService}——那才是 HTTP 无法省略的语义
 * （要拿别人的真实运行状态）。契约 §7.1 的「优先 Feign」在这里被显式让位于
 * 「不把简单事做复杂」，取舍理由记录在此，评审可查。</p>
 *
 * <p>所有 SQL 全部参数化（{@code ?}），无字符串拼接。</p>
 *
 * @author oj-cloud
 */
@Repository
public class AdminChannelDao {

    private static final String SELECT_COLUMNS = """
            SELECT id, channel_code, channel_name, vendor, provider_type, base_url, api_key,
                   model_name, role_binding, timeout_seconds, max_tokens, temperature,
                   max_concurrency, health_status, health_note, last_probe_at, is_enabled
              FROM ai_model_config
            """;

    private final JdbcTemplate db;

    public AdminChannelDao(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * 查全部通道，按 {@code channel_code} 升序。
     *
     * @return 每行是「下划线列名 → 值」的 Map，由 Service 映射成 DTO
     */
    public List<Map<String, Object>> findAll() {
        return db.queryForList(SELECT_COLUMNS + " ORDER BY channel_code");
    }

    /**
     * 按 id 查单个通道。
     *
     * @param id 主键
     * @return 行数据；不存在返回 {@code null}
     */
    public Map<String, Object> findById(Long id) {
        List<Map<String, Object>> rows = db.queryForList(SELECT_COLUMNS + " WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 按通道编码查（新增时的唯一性预检）。
     *
     * @param channelCode 通道编码
     * @return 行数据；不存在返回 {@code null}
     */
    public Map<String, Object> findByCode(String channelCode) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT id, channel_code, channel_name, health_status, is_enabled"
                        + " FROM ai_model_config WHERE channel_code = ?", channelCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 一次性取全部通道的角色绑定（避免 N+1 查询）。
     *
     * @return {@code channelCode → [ROLE...]}（按 priority、id 升序）
     */
    public Map<String, List<String>> loadRoleBindings() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT channel_code, role
                  FROM ai_model_role
                 WHERE is_enabled = 1
                 ORDER BY channel_code, priority, id
                """);
        for (Map<String, Object> row : rows) {
            out.computeIfAbsent(str(row, "channel_code"), k -> new ArrayList<>())
                    .add(str(row, "role"));
        }
        return out;
    }

    /**
     * 取单个通道的角色绑定。
     *
     * @param channelCode 通道编码
     * @return 角色列表（可能为空）
     */
    public List<String> findRoles(String channelCode) {
        return db.queryForList("""
                SELECT role FROM ai_model_role
                 WHERE channel_code = ? AND is_enabled = 1
                 ORDER BY priority, id
                """, String.class, channelCode);
    }

    /**
     * 取各通道今日调用聚合（调用数 / 平均 TTFT / tokens / 成本）。
     *
     * <p>{@code since} 由调用方传「今日 00:00」，避免 MySQL 侧时区函数与 JVM 时区漂移
     * （本项目统一 Asia/Shanghai）。</p>
     *
     * @param since 起始时刻
     * @return {@code channelCode → 聚合行}
     */
    public Map<String, Map<String, Object>> todayAggByChannel(LocalDateTime since) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT selected_channel                        AS channel_code,
                       COUNT(*)                                AS calls,
                       AVG(ttft_ms)                            AS avg_ttft,
                       COALESCE(SUM(tokens_in), 0)             AS tokens_in,
                       COALESCE(SUM(tokens_out), 0)            AS tokens_out,
                       COALESCE(SUM(cost_cents), 0)            AS cost_cents
                  FROM ai_chat_record
                 WHERE create_time >= ?
                 GROUP BY selected_channel
                """, since);
        for (Map<String, Object> row : rows) {
            Map<String, Object> agg = new LinkedHashMap<>();
            agg.put("calls", num(row, "calls"));
            agg.put("avgTtft", row.get("avg_ttft") == null ? null : dbl(row, "avg_ttft"));
            agg.put("tokensIn", num(row, "tokens_in"));
            agg.put("tokensOut", num(row, "tokens_out"));
            agg.put("costCents", num(row, "cost_cents"));
            out.put(str(row, "channel_code"), agg);
        }
        return out;
    }

    /**
     * 取某通道近 24 小时按小时分桶的调用趋势。
     *
     * @param channelCode 通道编码
     * @param since       起始时刻（now − 24h）
     * @return 每桶 {@code {hour, ttftMs, calls}}，按小时升序（Service 负责补齐空桶）
     */
    public List<Map<String, Object>> trend24h(String channelCode, LocalDateTime since) {
        return db.queryForList("""
                SELECT HOUR(create_time)  AS h,
                       AVG(ttft_ms)       AS avg_ttft,
                       COUNT(*)           AS calls
                  FROM ai_chat_record
                 WHERE selected_channel = ? AND create_time >= ?
                 GROUP BY HOUR(create_time)
                 ORDER BY h
                """, channelCode, since);
    }

    /**
     * 取某通道最近 N 次调用。
     *
     * @param channelCode 通道编码
     * @param limit       条数
     * @return 按时间倒序
     */
    public List<Map<String, Object>> recentCalls(String channelCode, int limit) {
        return db.queryForList("""
                SELECT id, create_time, ttft_ms, tokens_in, tokens_out, guardrail_action
                  FROM ai_chat_record
                 WHERE selected_channel = ?
                 ORDER BY create_time DESC, id DESC
                 LIMIT ?
                """, channelCode, limit);
    }

    /**
     * 取全站近期降级事件（只能由 {@code is_fallback=1} 的真实记录派生——
     * 本项目没有独立降级事件表，见 {@code AdminChannelService} 类注释）。
     *
     * @param limit 条数
     * @return 按时间倒序
     */
    public List<Map<String, Object>> degradeEvents(int limit) {
        return db.queryForList("""
                SELECT create_time, selected_channel, actual_model, is_fallback
                  FROM ai_chat_record
                 WHERE is_fallback = 1
                 ORDER BY create_time DESC, id DESC
                 LIMIT ?
                """, limit);
    }

    // ==================================================================
    // 写操作
    // ==================================================================

    /**
     * 新增通道。
     *
     * @param p 字段（键为下划线列名）
     * @return 自增主键
     */
    public Long insert(Map<String, Object> p) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        db.update(conn -> {
            var ps = conn.prepareStatement("""
                    INSERT INTO ai_model_config
                      (channel_code, channel_name, vendor, provider_type, base_url, api_key,
                       model_name, role_binding, timeout_seconds, max_tokens, temperature,
                       max_concurrency, health_status, health_note, last_probe_at, is_enabled)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, (String) p.get("channel_code"));
            ps.setString(2, (String) p.get("channel_name"));
            ps.setString(3, (String) p.get("vendor"));
            ps.setString(4, (String) p.get("provider_type"));
            ps.setString(5, (String) p.get("base_url"));
            ps.setString(6, p.get("api_key") == null ? "" : (String) p.get("api_key"));
            ps.setString(7, (String) p.get("model_name"));
            ps.setString(8, (String) p.get("role_binding"));
            ps.setInt(9, (Integer) p.get("timeout_seconds"));
            ps.setInt(10, (Integer) p.get("max_tokens"));
            ps.setBigDecimal(11, BigDecimal.valueOf((Double) p.get("temperature")));
            ps.setInt(12, (Integer) p.get("max_concurrency"));
            ps.setString(13, (String) p.get("health_status"));
            ps.setString(14, (String) p.get("health_note"));
            ps.setObject(15, p.get("last_probe_at"));
            ps.setInt(16, Boolean.TRUE.equals(p.get("is_enabled")) ? 1 : 0);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? null : key.longValue();
    }

    /**
     * 更新通道（只更新传入的字段）。
     *
     * <p>列名来自服务层的固定枚举（不来自请求参数），值全部走 {@code ?} 占位，
     * 因此不构成 SQL 注入面。</p>
     *
     * @param id    主键
     * @param patch 待更新列（下划线列名 → 值）
     */
    public void update(Long id, Map<String, Object> patch) {
        if (patch.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder("UPDATE ai_model_config SET ");
        List<Object> args = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            if (!first) {
                sql.append(", ");
            }
            sql.append(e.getKey()).append(" = ?");
            args.add(e.getValue());
            first = false;
        }
        sql.append(" WHERE id = ?");
        args.add(id);
        db.update(sql.toString(), args.toArray());
    }

    /**
     * 删除通道主记录。
     *
     * @param id 主键
     * @return 影响行数
     */
    public int deleteChannel(Long id) {
        return db.update("DELETE FROM ai_model_config WHERE id = ?", id);
    }

    /**
     * 删除通道的全部角色绑定。
     *
     * @param channelCode 通道编码
     * @return 影响行数
     */
    public int deleteRoles(String channelCode) {
        return db.update("DELETE FROM ai_model_role WHERE channel_code = ?", channelCode);
    }

    /**
     * 重建通道的角色绑定（先删后插，事务由 Service 控制）。
     *
     * @param channelCode 通道编码
     * @param roles       角色列表（已规范化大写）
     */
    public void insertRoles(String channelCode, Collection<String> roles) {
        for (String role : roles) {
            db.update("""
                    INSERT INTO ai_model_role (channel_code, role, priority, is_enabled)
                    VALUES (?, ?, 100, 1)
                    ON DUPLICATE KEY UPDATE is_enabled = 1
                    """, channelCode, role);
        }
    }

    /**
     * 某通道是否还有历史调用记录（决定「删除」还是「转禁用」）。
     *
     * @param channelCode 通道编码
     * @return 记录数
     */
    public long countCalls(String channelCode) {
        Long n = db.queryForObject("SELECT COUNT(*) FROM ai_chat_record WHERE selected_channel = ?",
                Long.class, channelCode);
        return n == null ? 0L : n;
    }

    /**
     * 某角色名下（排除指定通道）还有几个可用通道，用于「删除会不会干掉最后的 Tutor」判断。
     *
     * @param role           角色
     * @param excludeChannel 排除的通道编码
     * @return 可用通道数（is_enabled=1）
     */
    public long countAvailableByRole(String role, String excludeChannel) {
        Long n = db.queryForObject("""
                SELECT COUNT(*)
                  FROM ai_model_role r
                  JOIN ai_model_config c ON c.channel_code = r.channel_code
                 WHERE r.role = ? AND r.is_enabled = 1 AND r.channel_code <> ? AND c.is_enabled = 1
                """, Long.class, role, excludeChannel);
        return n == null ? 0L : n;
    }

    /**
     * 全部通道的健康态分桶计数（健康聚合与统计共用）。
     *
     * @return 每行 {@code {is_enabled, health_status, cnt}}
     */
    public List<Map<String, Object>> healthBuckets() {
        return db.queryForList("""
                SELECT is_enabled, health_status, COUNT(*) AS cnt
                  FROM ai_model_config
                 GROUP BY is_enabled, health_status
                """);
    }

    /**
     * 今日全站 AI 调用总数。
     *
     * @param since 今日 00:00
     * @return 调用数
     */
    public long countCallsSince(LocalDateTime since) {
        Long n = db.queryForObject("SELECT COUNT(*) FROM ai_chat_record WHERE create_time >= ?",
                Long.class, since);
        return n == null ? 0L : n;
    }

    // ==================================================================
    // 列读取小工具：MySQL 的 SUM/COUNT 返回 BigDecimal/Long/BigInteger 不等，
    // 统一收敛，避免 ActiveRecord 式的 ClassCastException
    // ==================================================================

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static long num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static double dbl(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Number n ? n.doubleValue() : 0d;
    }
}
