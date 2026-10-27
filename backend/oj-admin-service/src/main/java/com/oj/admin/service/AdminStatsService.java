package com.oj.admin.service;

import com.oj.admin.dao.AdminChannelDao;
import com.oj.admin.dao.AdminStatsDao;
import com.oj.common.api.dto.admin.PlatformStatsDTO;
import com.oj.common.core.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 全站大盘统计（契约 §7.6.1 #87）。
 *
 * <p>六组数字全部来自真实表计数/求和，口径写在 {@link PlatformStatsDTO} 的类注释里。
 * 这里额外把「通道健康四档」与「今日调用数」也接进来，让 a-models 的概览区
 * 与 a-audit 的主带共用同一份聚合，避免两页算出两个数（原型里 1840 就是被两页
 * 各自硬编码的，注释还写着「口径必须一致」）。</p>
 *
 * @author oj-cloud
 */
@Service
public class AdminStatsService {

    /** range 白名单 → 天数 */
    private static final Map<String, Integer> RANGES = Map.of(
            "7d", 7,
            "30d", 30,
            "today", 1);

    private final AdminStatsDao statsDao;
    private final AdminChannelDao channelDao;

    public AdminStatsService(AdminStatsDao statsDao, AdminChannelDao channelDao) {
        this.statsDao = statsDao;
        this.channelDao = channelDao;
    }

    /**
     * 全站大盘。
     *
     * @param range 区间（默认 7 天），只影响 trend[]
     * @return 大盘数据
     */
    public PlatformStatsDTO stats(String range) {
        int days = resolveDays(range);
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        PlatformStatsDTO out = new PlatformStatsDTO();

        // ① 用户
        Map<String, Long> users = statsDao.userStats(todayStart);
        PlatformStatsDTO.Users u = new PlatformStatsDTO.Users();
        u.setTotal(users.getOrDefault("total", 0L));
        u.setStudents(users.getOrDefault("students", 0L));
        u.setTeachers(users.getOrDefault("teachers", 0L));
        u.setAdmins(users.getOrDefault("admins", 0L));
        u.setActiveToday(users.getOrDefault("activeToday", 0L));
        out.setUsers(u);

        // ② 题目
        Map<String, Long> problems = statsDao.problemStats();
        PlatformStatsDTO.Problems p = new PlatformStatsDTO.Problems();
        p.setTotal(problems.getOrDefault("total", 0L));
        p.setPublished(problems.getOrDefault("published", 0L));
        p.setDraft(problems.getOrDefault("draft", 0L));
        p.setArchived(problems.getOrDefault("archived", 0L));
        out.setProblems(p);

        // ③ 提交
        Map<String, Object> subs = statsDao.submissionStats(todayStart);
        PlatformStatsDTO.Submissions s = new PlatformStatsDTO.Submissions();
        s.setTotal(lng(subs.get("total")));
        s.setToday(lng(subs.get("today")));
        s.setAcRate(dbl(subs.get("acRate")));
        out.setSubmissions(s);

        // ④ AI
        Map<String, Object> ai = statsDao.aiStats(todayStart);
        PlatformStatsDTO.Ai a = new PlatformStatsDTO.Ai();
        a.setCallsToday(lng(ai.get("callsToday")));
        a.setTokensToday(lng(ai.get("tokensToday")));
        a.setCostCentsToday(lng(ai.get("costCentsToday")));
        a.setFallbackRate(dbl(ai.get("fallbackRate")));
        out.setAi(a);

        // ⑤ 教学
        Map<String, Long> teaching = statsDao.teachingStats();
        PlatformStatsDTO.Teaching t = new PlatformStatsDTO.Teaching();
        t.setClasses(teaching.getOrDefault("classes", 0L));
        t.setAssignments(teaching.getOrDefault("assignments", 0L));
        t.setExams(teaching.getOrDefault("exams", 0L));
        t.setPendingPlagiarism(teaching.getOrDefault("pendingPlagiarism", 0L));
        out.setTeaching(t);

        // ⑥ 存储
        Map<String, Long> storage = statsDao.storageStats();
        PlatformStatsDTO.Storage st = new PlatformStatsDTO.Storage();
        st.setTestCaseBytes(storage.getOrDefault("testCaseBytes", 0L));
        st.setCodeBytes(storage.getOrDefault("codeBytes", 0L));
        out.setStorage(st);

        // ⑦ 趋势：补齐区间内每一天（无数据补 0），前端画折线不能有断点
        out.setTrend(buildTrend(days));
        return out;
    }

    private List<PlatformStatsDTO.TrendPoint> buildTrend(int days) {
        Map<String, Map<String, Long>> raw = statsDao.trend(days);
        List<PlatformStatsDTO.TrendPoint> trend = new ArrayList<>(days);
        LocalDate from = AdminStatsDao.trendFrom(days);
        for (int i = 0; i < days; i++) {
            LocalDate date = from.plusDays(i);
            String key = date.toString();
            Map<String, Long> bucket = raw.get(key);
            PlatformStatsDTO.TrendPoint point = new PlatformStatsDTO.TrendPoint();
            point.setDate(key);
            point.setSubmissions(bucket == null ? 0L : bucket.getOrDefault("submissions", 0L));
            point.setAiCalls(bucket == null ? 0L : bucket.getOrDefault("aiCalls", 0L));
            trend.add(point);
        }
        return trend;
    }

    private static int resolveDays(String range) {
        if (range == null || range.isBlank()) {
            return 7;
        }
        Integer days = RANGES.get(range.trim().toLowerCase(java.util.Locale.ROOT));
        if (days == null) {
            throw BusinessException.badRequest("range 只支持 today / 7d / 30d");
        }
        return days;
    }

    private static long lng(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static double dbl(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0d;
    }

    /**
     * 今日全站 AI 调用数（供健康聚合旁的健康位复用）。
     *
     * @return 调用数
     */
    public long todayCalls() {
        return channelDao.countCallsSince(LocalDate.now().atStartOfDay());
    }
}
