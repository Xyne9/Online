package com.oj.admin.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全站大盘统计的只读聚合（契约 §7.6）。
 *
 * <p><b>为什么一处都不过 Feign</b>：大盘要的是 6 张表的一次性计数/求和。
 * 走 Feign 需要各服务分别开统计端点、定义 6 个 DTO，再加一轮网络往返；
 * 而它们都在同一个库，直连一条 SQL 就完。理由同 {@link AdminChannelDao} 类注释。</p>
 *
 * @author oj-cloud
 */
@Repository
public class AdminStatsDao {

    private final JdbcTemplate db;

    public AdminStatsDao(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * 用户维度计数。
     *
     * @param todayStart 今日 00:00
     * @return {@code {total, students, teachers, admins, activeToday}}
     */
    public Map<String, Long> userStats(LocalDateTime todayStart) {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("total", scalar("SELECT COUNT(*) FROM sys_user"));
        out.put("students", scalar("SELECT COUNT(*) FROM sys_user WHERE role = 'STUDENT'"));
        out.put("teachers", scalar("SELECT COUNT(*) FROM sys_user WHERE role = 'TEACHER'"));
        out.put("admins", scalar("SELECT COUNT(*) FROM sys_user WHERE role = 'ADMIN'"));
        out.put("activeToday", scalar("SELECT COUNT(*) FROM sys_user WHERE last_login_at >= ?", todayStart));
        return out;
    }

    /**
     * 题目维度计数。
     *
     * @return {@code {total, published, draft, archived}}
     */
    public Map<String, Long> problemStats() {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("total", scalar("SELECT COUNT(*) FROM prob_question WHERE deleted_at IS NULL"));
        out.put("published", scalar("SELECT COUNT(*) FROM prob_question WHERE deleted_at IS NULL AND state = 1"));
        out.put("draft", scalar("SELECT COUNT(*) FROM prob_question WHERE deleted_at IS NULL AND state = 0"));
        out.put("archived", scalar("SELECT COUNT(*) FROM prob_question WHERE deleted_at IS NULL AND state = 2"));
        return out;
    }

    /**
     * 提交维度计数。
     *
     * @param todayStart 今日 00:00
     * @return {@code {total, today, acRate}}
     */
    public Map<String, Object> submissionStats(LocalDateTime todayStart) {
        Map<String, Object> out = new LinkedHashMap<>();
        long total = scalar("SELECT COUNT(*) FROM judge_submission");
        long ac = scalar("SELECT COUNT(*) FROM judge_submission WHERE status = 3");
        out.put("total", total);
        out.put("today", scalar("SELECT COUNT(*) FROM judge_submission WHERE create_time >= ?", todayStart));
        // 保留一位小数，与 prob 服务 acRate 口径一致（agent.md §5 / 契约 §3.2 备注）
        out.put("acRate", total == 0 ? 0.0 : Math.round(ac * 1000.0 / total) / 10.0);
        return out;
    }

    /**
     * AI 维度计数。
     *
     * @param todayStart 今日 00:00
     * @return {@code {callsToday, tokensToday, costCentsToday, fallbackRate}}
     */
    public Map<String, Object> aiStats(LocalDateTime todayStart) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT COUNT(*)                                          AS calls,
                       COALESCE(SUM(tokens_in + tokens_out), 0)          AS tokens,
                       COALESCE(SUM(cost_cents), 0)                      AS cost,
                       COALESCE(SUM(CASE WHEN is_fallback = 1 THEN 1 ELSE 0 END), 0) AS fallback
                  FROM ai_chat_record
                 WHERE create_time >= ?
                """, todayStart);
        Map<String, Object> row = rows.isEmpty() ? new HashMap<>() : rows.get(0);
        long calls = lng(row.get("calls"));
        long fallback = lng(row.get("fallback"));
        out.put("callsToday", calls);
        out.put("tokensToday", lng(row.get("tokens")));
        out.put("costCentsToday", lng(row.get("cost")));
        out.put("fallbackRate", calls == 0 ? 0.0 : Math.round(fallback * 1000.0 / calls) / 10.0);
        return out;
    }

    /**
     * 教学维度计数。
     *
     * @return {@code {classes, assignments, exams, pendingPlagiarism}}
     */
    public Map<String, Long> teachingStats() {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("classes", scalar("SELECT COUNT(*) FROM teach_class"));
        out.put("assignments", scalar("SELECT COUNT(*) FROM teach_assignment"));
        out.put("exams", scalar("SELECT COUNT(*) FROM teach_assignment WHERE mode_type = 3"));
        out.put("pendingPlagiarism", scalar("SELECT COUNT(*) FROM teach_plagiarism_report WHERE audit_status = 0"));
        return out;
    }

    /**
     * 存储维度计数。
     *
     * @return {@code {testCaseBytes, codeBytes}}
     */
    public Map<String, Long> storageStats() {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("testCaseBytes", scalar(
                "SELECT COALESCE(SUM(input_size + output_size), 0) FROM prob_test_case WHERE deleted_at IS NULL"));
        out.put("codeBytes", scalar("SELECT COALESCE(SUM(LENGTH(code)), 0) FROM judge_submission"));
        return out;
    }

    /**
     * 近 N 天趋势（提交数 + AI 调用数）。
     *
     * @param days 天数（含今天）
     * @return {@code date → {submissions, aiCalls}}，只含有数据的日期，Service 负责补零
     */
    public Map<String, Map<String, Long>> trend(int days) {
        LocalDate from = LocalDate.now().minusDays(Math.max(1, days) - 1L);
        LocalDateTime fromAt = from.atStartOfDay();
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();

        List<Map<String, Object>> subRows = db.queryForList("""
                SELECT DATE(create_time) AS d, COUNT(*) AS cnt
                  FROM judge_submission
                 WHERE create_time >= ?
                 GROUP BY DATE(create_time)
                """, fromAt);
        for (Map<String, Object> r : subRows) {
            String date = String.valueOf(r.get("d"));
            out.computeIfAbsent(date, k -> new LinkedHashMap<>(Map.of("submissions", 0L, "aiCalls", 0L)))
                    .put("submissions", lng(r.get("cnt")));
        }

        List<Map<String, Object>> aiRows = db.queryForList("""
                SELECT DATE(create_time) AS d, COUNT(*) AS cnt
                  FROM ai_chat_record
                 WHERE create_time >= ?
                 GROUP BY DATE(create_time)
                """, fromAt);
        for (Map<String, Object> r : aiRows) {
            String date = String.valueOf(r.get("d"));
            Map<String, Long> bucket = out.get(date);
            if (bucket == null) {
                bucket = new LinkedHashMap<>();
                bucket.put("submissions", 0L);
                bucket.put("aiCalls", 0L);
                out.put(date, bucket);
            }
            bucket.put("aiCalls", lng(r.get("cnt")));
        }
        return out;
    }

    /**
     * 趋势区间起始日期。
     *
     * @param days 天数
     * @return 起始日期（含）
     */
    public static LocalDate trendFrom(int days) {
        return LocalDate.now().minusDays(Math.max(1, days) - 1L);
    }

    // ------------------------------------------------------------------

    private long scalar(String sql, Object... args) {
        Long n = args.length == 0 ? db.queryForObject(sql, Long.class)
                : db.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    private static long lng(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
