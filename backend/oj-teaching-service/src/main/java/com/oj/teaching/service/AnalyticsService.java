package com.oj.teaching.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.common.core.exception.BusinessException;
import com.oj.teaching.entity.SysUser;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.oj.teaching.service.ActivitySupport.*;

/**
 * 学情分析服务：契约 02 §6.5（#51~#53）与 §6.6（#54~#62）的字段组装与归属校验。
 *
 * <h2>口径（全文件只有这一处定义，改口径只改这里）</h2>
 *
 * <table border="1">
 *   <caption>指标定义</caption>
 *   <tr><th>字段</th><th>口径</th></tr>
 *   <tr><td>{@code score}</td>
 *       <td><b>得分率（0–100）</b>= Σ(每道应做题的最佳单题分) / (应做题数 × 100) × 100。
 *           用「得分率」而不是「原始总分」，是为了让「5 题 300 分」与「10 题 500 分」
 *           两个活动可比——原型 {@code t-analytics.html} 的 {@code score} 就是 0–100 的分数。</td></tr>
 *   <tr><td>{@code acRate}</td>
 *       <td><b>人级 AC 率</b>= 已 AC 题目数 / 应做题目数。{@code #56} 的班级 AC 率是
 *           <b>逐生 AC 率的算术平均</b>（与原型 {@code get acRate()} 一致）；
 *           {@code #53} 的 {@code weekPassRate} 才是<b>按提交次数加权</b>——两者语义不同，
 *           原型 {@code t-dashboard.html:581-586} 专门写明了不能混用。</td></tr>
 *   <tr><td>应做题</td>
 *       <td>班级窗口内所有<b>非草稿</b>活动的 {@code problem_ids} 并集（去重）。
 *           草稿学生看不见，算进来会让完成度凭空变低。</td></tr>
 *   <tr><td>{@code aiUsed}</td>
 *       <td><b>以 {@code ai_chat_record} 审计流水计数</b>，不用
 *           {@code teach_student_score.ai_used_count}——后者是「作业内单题提示配额计数器」，
 *           当前库里恒为 0，拿它当用量会让看板永远显示 0 次。</td></tr>
 *   <tr><td>{@code range}</td>
 *       <td>{@code week}=近 7 天、{@code month}=近 30 天、{@code term}=近 126 天（18 周）。
 *           一律按<b>滚动窗口</b>（{@code now - N 天}）而非自然周/月：自然周在周一早上会
 *           让「本周」几乎为空，看板会莫名其妙全零。</td></tr>
 * </table>
 *
 * <h2>越权矩阵</h2>
 * <ul>
 *   <li>{@code ADMIN}：可看任意班级；</li>
 *   <li>{@code TEACHER}：只能看 {@code teach_class.teacher_id = 自己} 的班，否则 403；</li>
 *   <li>{@code STUDENT}：只能看自己所在班（{@code class_id} 相同），且
 *       <b>逐生列表 / 风险名单 / AI 用量 TOP 这类含他人个体数据的端点一律 403</b>；
 *       {@code #62} 学生看他人一律 403（契约 6.6.9 的错误码）。</li>
 * </ul>
 *
 * @author oj-cloud
 */
@Service
public class AnalyticsService {

    // ==================================================================
    // 口径常量
    // ==================================================================

    /** {@code range=week} 的窗口天数 */
    static final int DAYS_WEEK = 7;
    /** {@code range=month} 的窗口天数 */
    static final int DAYS_MONTH = 30;
    /** {@code range=term} 的窗口天数（18 周） */
    static final int DAYS_TERM = 126;

    /** 单题得分达到该分数视为「已 AC」（与 {@code teach_student_score.max_score} 的 0~100 刻度一致） */
    static final int FULL_SCORE = 100;

    /** 掌握度分档：>=70 强（原型 {@code t-analytics.html:305} 的「达标」线） */
    static final int MASTERY_STRONG = 70;
    /** 掌握度分档：<50 弱（原型 {@code s-analytics.html} 里唯一允许用警示色的阈值） */
    static final int MASTERY_WEAK = 50;

    /** AI 依赖判定阈值：单窗口内 AI 调用 >= 12 次（沿用原型 {@code t-analytics.html:751}） */
    static final int RISK_AI_THRESHOLD = 12;
    /** 长期未交判定：窗口内最近 {@code RISK_IDLE_DAYS} 天没有任何提交 */
    static final int RISK_IDLE_DAYS = 14;
    /** 低分判定：得分率 < 60（原型 {@code t-analytics.html:752} 的阈值 63 与之同量级，取整为 60） */
    static final double RISK_LOW_SCORE = 60;

    /**
     * AI 阶段名（1~4）。
     *
     * <p>与 {@code oj-ai-agent-service} 的 {@code AiHistoryService.STAGES} 同源同值。
     * 刻意各写一份而不是跨服务取：那是个 {@code public static final String[]}，跨服务只能靠
     * Feign 每次请求换 4 个字符串，得不偿失；而口径一旦漂移，前端胶囊文案会立刻对不上，
     * 比性能问题更容易被发现。</p>
     */
    static final String[] AI_STAGES = {"", "读题拆解", "编码引导", "排错诊断", "AC 复盘"};

    /** {@code days[].date} / {@code peakDay} 的格式 */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 每周第一天是周一 */
    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private final AnalyticsRepository repo;
    private final AssignmentService assignments;
    private final ActivitySupport support;
    private final ObjectMapper json;

    public AnalyticsService(AnalyticsRepository repo, AssignmentService assignments,
                            ActivitySupport support, ObjectMapper json) {
        this.repo = repo;
        this.assignments = assignments;
        this.support = support;
        this.json = json;
    }

    // ==================================================================
    // #51 GET /api/teaching/classes?withStats=true
    // ==================================================================

    /**
     * 班级列表 + 真实统计（{@code #51 withStats=true} 的唯一实现）。
     *
     * <p><b>{@code avgScore} / {@code acRate} 绝不为 null：</b>这两个字段是契约 §6.5.1 明写的
     * 统计值，{@code null} 会让前端 {@code x-text} 渲染成空串、看起来像加载失败。
     * 空数据一律给 {@code 0}（{@link #averageScore} / {@link #classAcRate} 内部已按
     * 「无学生 → 0」处理，不是在调用点补的默认值）。</p>
     *
     * <p>身份字段（classId/className/teacherId/teacherName/studentCount）与列表顺序
     * 完全来自 {@link AssignmentService#classes(boolean)}：第 5 步验收脚本依赖它，
     * 这里<b>只补三个统计字段</b>，不动任何既有字段。</p>
     */
    public Map<String, Object> classes(boolean withStats) {
        Map<String, Object> base = assignments.classes(false);
        if (!withStats) {
            return base;
        }
        Object raw = base.get("list");
        if (!(raw instanceof List<?> list)) {
            return base;
        }
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> entry)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) entry;
            Object id = row.get("classId");
            if (!(id instanceof Number)) {
                // 没有 classId 的行不可能是真实班级；补 0 而不是留 null，前端才不会有空串
                row.put("activeCount", 0);
                row.put("avgScore", 0);
                row.put("acRate", 0);
                continue;
            }
            Map<String, Object> clazz = repo.classRow(num(id));
            if (clazz == null) {
                row.put("activeCount", 0);
                row.put("avgScore", 0);
                row.put("acRate", 0);
                continue;
            }
            ClassData data = load(num(id), clazz, null);
            row.put("activeCount", activeCount(data));
            row.put("avgScore", averageScore(data));
            row.put("acRate", classAcRate(data));
        }
        return base;
    }

    // ==================================================================
    // #52 GET /api/teaching/todos
    // ==================================================================

    /**
     * 待办聚合（发布 / 查重 / 通道 / 催交四类）。
     *
     * <p><b>四类待办的口径都必须能落到具体行数</b>，不能写「看起来有」的假条：
     * 发布=草稿活动数、查重={@code audit_status=0} 的疑似对数、通道=非 ON 的通道数、
     * 催交=已过截止时间但该生没有 {@code SUBMITTED} 记录的（活动 × 学生）组合数。</p>
     *
     * <p>{@code list} 恒定返回四条（含 {@code count=0} 的「状态正常」项），
     * 让前端可以直接按 {@code count>0} 分流到主区与页脚；{@code healthyCount} 就是
     * 其中 {@code count=0} 的条数。</p>
     */
    public Map<String, Object> todos(Integer limit) {
        SysUser user = requireTeacher();
        Long teacherId = "ADMIN".equals(user.getRole()) ? null : user.getId();

        List<Map<String, Object>> list = new ArrayList<>();

        long drafts = repo.draftAssignments(teacherId);
        list.add(map("key", "publish", "label", "待发布作业",
                "subtitle", drafts == 0 ? "没有草稿活动，全部已发布" : drafts + " 项草稿待发布，学生当前看不见",
                // 发布是例行操作，不是「要人判断」的事——保持中性色，避免一屏三处警示（原型注释同口径）
                "count", drafts, "severity", "info",
                "href", "t-assignments.html?status=0", "actionText", "去发布", "warn", false));

        long pending = repo.pendingPlagiarism(teacherId);
        list.add(map("key", "plag", "label", "查重待复核",
                "subtitle", pending == 0 ? "没有待复核的查重疑似对" : pending + " 组疑似对等待人工判定",
                "count", pending, "severity", pending == 0 ? "info" : "warn",
                "href", "t-plagiarism.html", "actionText", "去复核", "warn", pending > 0));

        List<Map<String, Object>> channels = repo.channelStates();
        long unhealthy = channels.stream()
                .filter(c -> !"ON".equals(text(c.get("health_status"))) || num(c.get("is_enabled")) != 1)
                .count();
        list.add(map("key", "model", "label", "模型通道",
                "subtitle", channels.isEmpty() ? "尚未配置任何模型通道"
                        : unhealthy == 0 ? channels.size() + " 条通道全部在线"
                        : unhealthy + " / " + channels.size() + " 条通道不可用，AI 助教会降级",
                "count", unhealthy, "severity", unhealthy == 0 ? "info" : "danger",
                "href", "a-models.html", "actionText", "去检查", "warn", unhealthy > 0));

        long overdue = repo.overdueUnsubmitted(teacherId, now());
        list.add(map("key", "urge", "label", "逾期未交",
                "subtitle", overdue == 0 ? "没有逾期未交的作业" : overdue + " 人次已过截止时间仍未交",
                "count", overdue, "severity", overdue == 0 ? "info" : "danger",
                "href", "t-assignments.html", "actionText", "去催交", "warn", overdue > 0));

        long healthy = list.stream().filter(t -> num(t.get("count")) == 0).count();
        List<Map<String, Object>> rows = list;
        if (limit != null) {
            if (limit < 1) {
                throw BusinessException.badRequest("limit 必须大于 0");
            }
            // 待办优先：先 count>0 的，再状态正常的，最后截断
            rows = new ArrayList<>(list);
            rows.sort(Comparator.comparingLong(t -> num(t.get("count")) == 0 ? 1L : 0L));
            rows = rows.subList(0, Math.min(limit, rows.size()));
        }
        return map("list", rows, "healthyCount", healthy, "totalCount", list.size());
    }

    // ==================================================================
    // #53 GET /api/teaching/week-trend
    // ==================================================================

    /**
     * 逐日提交/AC 趋势。
     *
     * <p>窗口由 {@code weeks}（契约）或 {@code range}（{@code 7d}/{@code 30d}/{@code term}/{@code all}）
     * 决定，两者同时给出时 <b>{@code range} 优先</b>——前端两个入口共用一个端点，
     * 显式的时间范围比「第几周」更不容易误读。</p>
     *
     * <p>{@code weekSubmit}/{@code weekAc}/{@code weekPassRate} 恒为<b>最近 7 天</b>的口径
     * （即「本周」），与 {@code days} 的总窗口解耦：{@code weeks=4} 时 {@code days} 是 28 天，
     * 但「本周通过率」仍然是最近 7 天，这样标题与数字才对得上。</p>
     */
    public Map<String, Object> weekTrend(Long classId, Integer weeks, String range) {
        SysUser user = current();
        long resolvedClass = requireClassId(resolveClassId(classId, user, true));
        int windowDays = trendWindowDays(weeks, range);
        LocalDateTime since = windowDays <= 0 ? null : now().minusDays(windowDays);

        ClassData data = loadScope(resolvedClass, null, since, user);
        // 逐日补零到连续日历：只在「有真实数据的日期区间」内补，不凭空造到未来
        List<Map<String, Object>> days = new ArrayList<>();
        Map<String, long[]> byDate = new LinkedHashMap<>();
        LocalDate today = now().toLocalDate();
        LocalDate start;
        if (windowDays > 0) {
            start = today.minusDays(windowDays - 1L);
        } else {
            LocalDate earliest = earliestDate(data.submissions);
            start = earliest == null ? today : earliest;
        }
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            byDate.put(d.format(DATE), new long[]{0, 0});
        }
        for (Map<String, Object> submission : data.submissions) {
            LocalDateTime at = time(submission.get("create_time"));
            if (at == null) {
                continue;
            }
            long[] counter = byDate.get(at.toLocalDate().format(DATE));
            if (counter == null) {
                continue;
            }
            counter[0]++;
            if (num(submission.get("status")) == 3) {
                counter[1]++;
            }
        }
        long weekSubmit = 0;
        long weekAc = 0;
        String peakDate = null;
        long peakCount = -1;
        LocalDate weekStart = today.minusDays(DAYS_WEEK - 1L);
        for (Map.Entry<String, long[]> entry : byDate.entrySet()) {
            long submit = entry.getValue()[0];
            long ac = entry.getValue()[1];
            LocalDate d = LocalDate.parse(entry.getKey(), DATE);
            days.add(map("date", entry.getKey(), "weekday", WEEKDAYS[d.getDayOfWeek().getValue() - 1],
                    "submitCount", submit, "acCount", ac, "passRate", percent(ac, submit)));
            if (submit > peakCount) {
                peakCount = submit;
                peakDate = entry.getKey();
            }
            if (!d.isBefore(weekStart)) {
                weekSubmit += submit;
                weekAc += ac;
            }
        }
        return map("days", days, "weekPassRate", percent(weekAc, weekSubmit), "weekSubmit", weekSubmit,
                "weekAc", weekAc, "peakDate", peakDate);
    }

    // ==================================================================
    // #54 GET /api/teaching/analytics/hard-problems
    // ==================================================================

    /**
     * 易错题排行。
     *
     * <p>{@code acRate} 是<b>按提交次数</b>算的（{@code AC 提交数 / 总提交数}），
     * 与原型 {@code t-dashboard.html} 的 {@code ranks}（{@code tried}/{@code ac}/{@code pass} 三个提交级字段）
     * 同口径；{@code triedCount} 也是提交数而不是人数——「尝试人数」这一列在原型里直接写的就是
     * 提交条数（{@code tried:508} 不可能是 508 个人）。</p>
     */
    public Map<String, Object> hardProblems(Long classId, String range, String mode, Integer limit) {
        SysUser user = current();
        long resolvedClass = requireClassId(resolveClassId(classId, user, true));
        String sortMode = requireEnum(mode, "mode", "ac", Set.of("ac", "tried"));
        int size = requireLimit(limit, 10, 1, 50, "limit");
        LocalDateTime since = now().minusDays(rangeDays(range));

        ClassData data = loadScope(resolvedClass, null, since, user);
        Map<Long, List<Map<String, Object>>> grouped = groupBy(data.submissions, "problem_id");

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Long, List<Map<String, Object>>> entry : grouped.entrySet()) {
            long problemId = entry.getKey();
            List<Map<String, Object>> subs = entry.getValue();
            long tried = subs.size();
            long ac = subs.stream().filter(s -> num(s.get("status")) == 3).count();
            double avgScore = subs.stream().mapToLong(s -> num(s.get("oi_score"))).average().orElse(0);
            Map<String, Object> meta = data.problemMeta.get(problemId);
            rows.add(map("problemId", problemId,
                    "displayNo", "#" + problemId,
                    "title", meta == null ? "题目 " + problemId : text(meta.get("title")),
                    "difficulty", meta == null ? null : intOrNull(meta.get("difficulty")),
                    "triedCount", tried, "acRate", percent(ac, tried),
                    "avgScore", round2(avgScore),
                    "firstFailSeq", firstFailSeq(subs),
                    "topStudents", topFailStudents(subs, data.students)));
        }
        if ("tried".equals(sortMode)) {
            rows.sort(Comparator.comparingLong(r -> -num(r.get("triedCount"))));
        } else {
            rows.sort(Comparator.comparingDouble(r -> ((Number) r.get("acRate")).doubleValue()));
        }
        List<Map<String, Object>> page = rows.size() > size ? new ArrayList<>(rows.subList(0, size)) : rows;
        return map("list", page, "total", rows.size());
    }

    // ==================================================================
    // #55 GET /api/teaching/analytics/problem/{problemId}/errors
    // ==================================================================

    /**
     * 单题错误分析：逐测试点失败人数 + 典型错因分布。
     *
     * <p><b>隐私口径：</b>{@code topWrongOutput} 只取 {@code case_details[].actualPreview}
     * （学生自己的输出），<b>绝不</b>取 {@code expectedPreview}——期望输出是隐藏用例答案，
     * 一旦从错误分析端点漏出去，等于把题库送人。</p>
     *
     * <p><b>failCount 是人数不是提交数：</b>契约写的是「逐测试点失败人数」。
     * 取法 = 该测试点上「最好状态仍不是 AC」的学生数；一个学生多次提交，只要有一次通过就不计失败。</p>
     */
    public Map<String, Object> problemErrors(Long problemId, Long classId, Long assignmentId) {
        SysUser user = current();
        long id = requireId(problemId, "problemId");
        if (assignmentId != null) {
            requireId(assignmentId, "assignmentId");
        }
        long resolvedClass = requireClassId(resolveClassId(classId, user, true));
        List<Map<String, Object>> metaRows = repo.problems(List.of(id));
        if (metaRows.isEmpty()) {
            throw BusinessException.notFound("题目不存在");
        }
        Map<String, Object> meta = metaRows.get(0);
        assertClassAccess(classRowOrThrow(resolvedClass), user);
        problemId = id;
        List<Map<String, Object>> subs = repo.submissionsWithCases(resolvedClass, problemId, assignmentId, null);
        long total = subs.size();
        long ac = subs.stream().filter(s -> num(s.get("status")) == 3).count();

        // seqNo -> {reachedUsers, failedUsers, wrongOutput -> count}
        Map<Integer, Map<String, Object>> bySeq = new TreeMap<>();
        for (Map<String, Object> skeleton : repo.testCases(List.of(problemId))) {
            int seq = (int) num(skeleton.get("seq_no"));
            bySeq.computeIfAbsent(seq, k -> new LinkedHashMap<>(map(
                    "seqNo", seq, "kind", text(skeleton.get("kind")), "score", num(skeleton.get("score")),
                    "reached", new HashSet<Long>(), "failed", new HashSet<Long>(),
                    "wrong", new LinkedHashMap<String, Long>())));
        }
        Map<String, Long> statusCount = new LinkedHashMap<>();
        for (Map<String, Object> submission : subs) {
            long userId = num(submission.get("user_id"));
            String statusCode = judgeCode(num(submission.get("status")));
            statusCount.merge(statusCode, 1L, Long::sum);
            for (Map<String, Object> detail : cases(submission.get("case_details"))) {
                int seq = (int) num(detail.get("seqNo"));
                Map<String, Object> bucket = bySeq.computeIfAbsent(seq, k -> new LinkedHashMap<>(map(
                        "seqNo", seq, "kind", text(detail.get("kind")), "score", num(detail.get("score")),
                        "reached", new HashSet<Long>(), "failed", new HashSet<Long>(),
                        "wrong", new LinkedHashMap<String, Long>())));
                @SuppressWarnings("unchecked")
                Set<Long> reached = (Set<Long>) bucket.get("reached");
                @SuppressWarnings("unchecked")
                Set<Long> failed = (Set<Long>) bucket.get("failed");
                reached.add(userId);
                if (!"AC".equals(text(detail.get("status")))) {
                    failed.add(userId);
                    @SuppressWarnings("unchecked")
                    Map<String, Long> wrong = (Map<String, Long>) bucket.get("wrong");
                    String preview = preview(text(detail.get("actualPreview")));
                    if (preview != null) {
                        wrong.merge(preview, 1L, Long::sum);
                    }
                }
            }
        }
        List<Map<String, Object>> cases = new ArrayList<>();
        for (Map<String, Object> bucket : bySeq.values()) {
            @SuppressWarnings("unchecked")
            Set<Long> reached = (Set<Long>) bucket.get("reached");
            @SuppressWarnings("unchecked")
            Set<Long> failed = (Set<Long>) bucket.get("failed");
            @SuppressWarnings("unchecked")
            Map<String, Long> wrong = (Map<String, Long>) bucket.get("wrong");
            String topWrong = wrong.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            cases.add(map("seqNo", bucket.get("seqNo"), "kind", bucket.get("kind"), "score", bucket.get("score"),
                    "failCount", failed.size(), "failRate", percent(failed.size(), reached.size()),
                    "topWrongOutput", topWrong));
        }
        List<Map<String, Object>> errorTypes = new ArrayList<>();
        for (Map.Entry<String, Long> entry : statusCount.entrySet()) {
            if ("AC".equals(entry.getKey())) {
                continue;
            }
            errorTypes.add(map("code", entry.getKey(), "count", entry.getValue(),
                    "percent", percent(entry.getValue(), total)));
        }
        errorTypes.sort(Comparator.comparingLong(e -> -num(e.get("count"))));
        return map("problemId", problemId, "title", text(meta.get("title")), "totalSubmit", total,
                "acCount", ac, "acRate", percent(ac, total), "cases", cases, "errorTypes", errorTypes);
    }

    // ==================================================================
    // #56 GET /api/teaching/analytics/class/{classId}
    // ==================================================================

    /** 班级学情总览：均分、AC 率、成绩分布、活跃、AI 风险、峰值日、最弱知识点 */
    public Map<String, Object> classOverview(Long classId, String range) {
        SysUser user = current();
        long id = requireClassId(classId);
        Map<String, Object> clazz = classRowOrThrow(id);
        assertClassAccess(clazz, user);
        LocalDateTime since = now().minusDays(rangeDays(range));

        ClassData data = load(id, clazz, since);
        List<Double> scores = new ArrayList<>();
        int aiRisk = 0;
        for (Map<String, Object> student : data.students) {
            Metric metric = metric(data, num(student.get("id")));
            scores.add(metric.score);
            if ("ai".equals(metric.riskLevel)) {
                aiRisk++;
            }
        }
        Map<String, Long> perDay = new TreeMap<>();
        for (Map<String, Object> submission : data.submissions) {
            LocalDateTime at = time(submission.get("create_time"));
            if (at != null) {
                perDay.merge(at.toLocalDate().format(DATE), 1L, Long::sum);
            }
        }
        String peakDay = null;
        long peakCount = 0;
        for (Map.Entry<String, Long> entry : perDay.entrySet()) {
            if (entry.getValue() > peakCount) {
                peakCount = entry.getValue();
                peakDay = entry.getKey();
            }
        }
        List<Map<String, Object>> kpRows = knowledgeRows(data);
        Map<String, Object> weakest = kpRows.stream()
                .min(Comparator.comparingDouble(k -> toDouble(k.get("masteryPercent")))).orElse(null);

        return map("classId", id, "className", text(clazz.get("class_name")),
                "studentCount", data.students.size(), "avgScore", averageScore(data),
                "acRate", classAcRate(data), "activeCount", activeCount(data),
                "aiRiskCount", aiRisk, "dist", distribution(scores),
                "peakDay", peakDay, "peakCount", peakCount,
                "weakestKp", weakest == null ? null : weakest.get("kpName"),
                "avgKpMastery", averageMastery(kpRows));
    }

    // ==================================================================
    // #57 GET /api/teaching/analytics/class/{classId}/knowledge
    // ==================================================================

    /**
     * 知识点掌握度。<b>方向：越高越强</b>（契约 §6.6 的语义陷阱）。
     *
     * <p>与 {@code sys_user.weak_tags} 的「薄弱名单」方向相反，两者不可互借字段名：
     * 本端点只下发 {@code masteryPercent}，永不出现 {@code weakTags}。</p>
     *
     * <p>{@code symptom} / {@code cause} / {@code advice} 三句话<b>全部由本班的真实计数拼出</b>
     * （人数、提交数、占比最高的错误类型），不含任何固定示例文案——那是 agent.md §3.4 的红线。</p>
     */
    public Map<String, Object> classKnowledge(Long classId, String range) {
        SysUser user = current();
        long id = requireClassId(classId);
        Map<String, Object> clazz = classRowOrThrow(id);
        assertClassAccess(clazz, user);
        LocalDateTime since = now().minusDays(rangeDays(range));
        ClassData data = load(id, clazz, since);

        List<Map<String, Object>> rows = knowledgeRows(data);
        rows.sort(Comparator.comparingDouble(k -> toDouble(k.get("masteryPercent"))));
        return map("list", rows, "avgMastery", averageMastery(rows), "weakCount",
                rows.stream().filter(k -> toDouble(k.get("masteryPercent")) < MASTERY_STRONG).count());
    }

    // ==================================================================
    // #58 GET /api/teaching/analytics/class/{classId}/students
    // ==================================================================

    /**
     * 逐生学情列表（分页 + 排序 + 风险筛选）。
     *
     * <p>含他人的分数与 AI 用量，因此<b>仅教师/管理员</b>可访问（学生访问 403）。
     * 学生的「我自己的学情」走 {@code #62}。</p>
     */
    public Map<String, Object> classStudents(Long classId, String range, Integer page, Integer pageSize,
                                             Boolean riskOnly, String sortBy, String sortOrder, String kw) {
        SysUser user = current();
        if ("STUDENT".equals(user.getRole())) {
            throw BusinessException.forbidden("学生无权查看全班逐生成绩，请使用个人学情端点");
        }
        long id = requireClassId(classId);
        Map<String, Object> clazz = classRowOrThrow(id);
        assertClassAccess(clazz, user);
        // 白名单校验必须在 Comparator.comparingDouble 之前：garbage 值一旦流到比较器
        // 会抛 ClassCastException/IllegalArgumentException，变成 500 而不是 400
        String key = requireEnum(sortBy, "sortBy", "score", Set.of("score", "rate", "ai", "name"));
        String order = requireEnum(sortOrder, "sortOrder", "desc", Set.of("asc", "desc"));
        int safePage = requirePage(page);
        int safeSize = requirePageSize(pageSize);
        LocalDateTime since = now().minusDays(rangeDays(range));
        ClassData data = load(id, clazz, since);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> student : data.students) {
            Metric metric = metric(data, num(student.get("id")));
            rows.add(studentRow(data, student, metric));
        }
        if (Boolean.TRUE.equals(riskOnly)) {
            rows.removeIf(r -> "ok".equals(r.get("riskLevel")));
        }
        if (kw != null && !kw.isBlank()) {
            String needle = kw.trim().toLowerCase(Locale.ROOT);
            rows.removeIf(r -> !((text(r.get("name")) + " " + text(r.get("stuNo"))).toLowerCase(Locale.ROOT)
                    .contains(needle)));
        }
        Comparator<Map<String, Object>> comparator = switch (key) {
            case "name" -> Comparator.comparing(r -> text(r.get("name")));
            case "rate" -> Comparator.comparingDouble(r -> toDouble(r.get("acRate")));
            case "ai" -> Comparator.comparingLong(r -> num(r.get("aiUsed")));
            default -> Comparator.comparingDouble(r -> toDouble(r.get("score")));
        };
        if ("desc".equals(order)) {
            comparator = comparator.reversed();
        }
        rows.sort(comparator.thenComparingLong(r -> num(r.get("userId"))));
        return page(rows, safePage, safeSize);
    }

    // ==================================================================
    // #59 GET /api/teaching/analytics/class/{classId}/risk
    // ==================================================================

    /**
     * 风险学生名单。
     *
     * <p>每人只归一个 {@code riskLevel}（契约是单值），优先级 <b>idle &gt; ai &gt; low</b>：
     * 「一直没交」比「用 AI 多」更需要教师先介入；而 {@code byLevel} 三档相加恰好等于
     * {@code total}，不会出现一个人被数两次。</p>
     */
    public Map<String, Object> classRisk(Long classId, String level) {
        SysUser user = current();
        if ("STUDENT".equals(user.getRole())) {
            throw BusinessException.forbidden("学生无权查看班级风险名单");
        }
        String filter = requireEnum(level, "level", null, Set.of("ai", "low", "idle"));
        long id = requireClassId(classId);
        Map<String, Object> clazz = classRowOrThrow(id);
        assertClassAccess(clazz, user);
        ClassData data = load(id, clazz, now().minusDays(DAYS_TERM));

        List<Map<String, Object>> rows = new ArrayList<>();
        long ai = 0;
        long low = 0;
        long idle = 0;
        for (Map<String, Object> student : data.students) {
            Metric metric = metric(data, num(student.get("id")));
            switch (metric.riskLevel) {
                case "ai" -> ai++;
                case "low" -> low++;
                case "idle" -> idle++;
                default -> {
                    // ok：不入风险名单
                }
            }
            if ("ok".equals(metric.riskLevel) || filter != null && !filter.equals(metric.riskLevel)) {
                continue;
            }
            rows.add(map("userId", metric.userId, "name", metric.name, "stuNo", metric.stuNo,
                    "riskLevel", metric.riskLevel, "riskReason", metric.riskReason,
                    "score", round1(metric.score), "aiUsed", metric.aiUsed,
                    "lastActiveAt", metric.lastActiveAt, "suggestion", suggestion(metric)));
        }
        rows.sort(Comparator.comparingInt(r -> riskOrder(text(r.get("riskLevel")))));
        return map("list", rows, "total", rows.size(),
                "byLevel", map("ai", ai, "low", low, "idle", idle));
    }

    // ==================================================================
    // #60 GET /api/teaching/analytics/class/{classId}/heatmap
    // ==================================================================

    /**
     * 测试点热力图：前 N 题 × 测试点通过率。
     *
     * <p>{@code status} 三值：{@code ac} = 该格通过率 >= 60%；{@code wa} = 有提交跑到这一格
     * 但多数没通过；{@code none} = 全班还没有任何提交产生这一格的数据（不是「失败」，
     * 所以前端会渲染成「多数没跑到」而不是红色）。</p>
     *
     * <p>题目按「提交数降序」取前 N——教师最关心的举证样本自然是练得最多的题。</p>
     */
    public Map<String, Object> classHeatmap(Long classId, Integer limit) {
        SysUser user = current();
        long id = requireClassId(classId);
        Map<String, Object> clazz = classRowOrThrow(id);
        assertClassAccess(clazz, user);
        int size = requireLimit(limit, 6, 1, 20, "limit");
        ClassData data = load(id, clazz, now().minusDays(DAYS_TERM));
        List<Map<String, Object>> subs = repo.submissionsWithCases(id, null, null, now().minusDays(DAYS_TERM));

        Map<Long, List<Map<String, Object>>> grouped = groupBy(subs, "problem_id");
        List<Long> ordered = new ArrayList<>(grouped.keySet());
        ordered.sort(Comparator.comparingLong(problemId -> -grouped.get(problemId).size()));

        List<Map<String, Object>> rows = new ArrayList<>();
        long worstRate = Long.MAX_VALUE;
        String worstNote = null;
        for (Long problemId : ordered) {
            if (rows.size() >= size) {
                break;
            }
            Map<Integer, Set<Long>> reached = new TreeMap<>();
            Map<Integer, Set<Long>> passed = new TreeMap<>();
            for (Map<String, Object> submission : grouped.get(problemId)) {
                long userId = num(submission.get("user_id"));
                for (Map<String, Object> detail : cases(submission.get("case_details"))) {
                    int seq = (int) num(detail.get("seqNo"));
                    reached.computeIfAbsent(seq, k -> new HashSet<>()).add(userId);
                    if ("AC".equals(text(detail.get("status")))) {
                        passed.computeIfAbsent(seq, k -> new HashSet<>()).add(userId);
                    }
                }
            }
            List<Map<String, Object>> cells = new ArrayList<>();
            Map<String, Object> meta = data.problemMeta.get(problemId);
            String title = meta == null ? "题目 " + problemId : text(meta.get("title"));
            for (Map.Entry<Integer, Set<Long>> entry : reached.entrySet()) {
                long hit = passed.getOrDefault(entry.getKey(), Set.of()).size();
                long total = entry.getValue().size();
                double rate = percent(hit, total);
                String status = rate >= 60 ? "ac" : "wa";
                cells.add(map("seqNo", entry.getKey(), "passRate", rate, "status", status));
                if (rate < worstRate) {
                    worstRate = (long) rate;
                    worstNote = "#" + problemId + " " + title + " 第 " + entry.getKey() + " 个测试点";
                }
            }
            rows.add(map("problemId", problemId, "title", title, "cells", cells));
        }
        String footNote = subs.isEmpty()
                ? "本班在统计周期内还没有判题提交，热力图暂无可信数据。"
                : "共 " + grouped.size() + " 道题 · " + subs.size() + " 次提交；"
                + (worstNote == null ? "所有测试点通过率均正常。" : "全班通过率最低的一格是 " + worstNote + "。");
        return map("rows", rows, "footNote", footNote);
    }

    // ==================================================================
    // #61 GET /api/teaching/analytics/ai-usage
    // ==================================================================

    /**
     * 教师视角 AI 用量：总量 / 阶段分布 / 学生 TOP / 逐日趋势。
     *
     * <p>{@code groupBy} 是前端的分组偏好（{@code stage}/{@code student}/{@code day}），
     * 契约的响应体<b>三个维度都要给</b>，因此这里只做取值校验，不按它裁剪数据。</p>
     */
    public Map<String, Object> aiUsage(Long classId, String range, String groupBy) {
        SysUser user = current();
        if ("STUDENT".equals(user.getRole())) {
            throw BusinessException.forbidden("AI 用量看板仅教师可用");
        }
        String group = requireEnum(groupBy, "groupBy", null, Set.of("stage", "student", "day"));
        if (group == null && groupBy != null && !groupBy.isBlank()) {
            // 理论上不可达：非空白却在白名单外，requireEnum 已抛 400
            throw BusinessException.badRequest("groupBy 只能为 stage / student / day");
        }
        Long resolved = resolveClassId(classId, user, false);
        LocalDateTime since = now().minusDays(rangeDays(range));
        ClassData data = loadScope(resolved, null, since, user);
        List<Map<String, Object>> records = data.aiRecords;

        Map<Integer, Long> byStage = new TreeMap<>();
        Map<Long, Long> byStudent = new LinkedHashMap<>();
        Map<String, Long> byDay = new TreeMap<>();
        for (Map<String, Object> record : records) {
            byStage.merge((int) num(record.get("stage_type")), 1L, Long::sum);
            byStudent.merge(num(record.get("user_id")), 1L, Long::sum);
            LocalDateTime at = time(record.get("create_time"));
            if (at != null) {
                byDay.merge(at.toLocalDate().format(DATE), 1L, Long::sum);
            }
        }
        List<Map<String, Object>> stageDist = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : byStage.entrySet()) {
            int stage = entry.getKey();
            stageDist.add(map("stage", stage, "stageName", stageName(stage), "count", entry.getValue(),
                    "percent", percent(entry.getValue(), records.size())));
        }
        Map<Long, Map<String, Object>> studentIndex = new HashMap<>();
        for (Map<String, Object> student : data.students) {
            studentIndex.put(num(student.get("id")), student);
        }
        List<Map<String, Object>> topStudents = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : byStudent.entrySet()) {
            Map<String, Object> student = studentIndex.get(entry.getKey());
            if (student == null) {
                continue;
            }
            topStudents.add(map("userId", entry.getKey(), "name", text(student.get("real_name")),
                    "count", entry.getValue()));
        }
        topStudents.sort(Comparator.comparingLong(s -> -num(s.get("count"))));
        if (topStudents.size() > 5) {
            topStudents = new ArrayList<>(topStudents.subList(0, 5));
        }
        List<Map<String, Object>> dailyTrend = new ArrayList<>();
        for (Map.Entry<String, Long> entry : byDay.entrySet()) {
            dailyTrend.add(map("date", entry.getKey(), "count", entry.getValue()));
        }
        int students = data.students.size();
        return map("totalCalls", records.size(), "stageDist", stageDist, "topStudents", topStudents,
                "dailyTrend", dailyTrend, "avgPerStudent", students == 0 ? 0 : round1(records.size() * 1d / students));
    }

    // ==================================================================
    // #62 GET /api/teaching/analytics/student/{userId}
    // ==================================================================

    /**
     * 逐生学情明细：教师看学生 / 学生看自己。
     *
     * <p><b>越权：</b>学生看他人一律 403（契约 6.6.9 错误码）；教师只能看自己班的学生。</p>
     */
    public Map<String, Object> student(Long userId, String range) {
        SysUser user = current();
        long targetId = requireId(userId, "userId");
        Map<String, Object> target = repo.user(targetId);
        if (target == null) {
            throw BusinessException.notFound("学生不存在");
        }
        if (!"STUDENT".equals(text(target.get("role")))) {
            throw BusinessException.notFound("该用户不是学生");
        }
        Long targetClass = target.get("class_id") == null ? null : num(target.get("class_id"));
        if ("STUDENT".equals(user.getRole())) {
            if (user.getId() == null || user.getId() != targetId) {
                throw BusinessException.forbidden("学生只能查看自己的学情");
            }
        } else if (!"ADMIN".equals(user.getRole())) {
            if (targetClass == null) {
                throw BusinessException.forbidden("该学生未归属任何班级");
            }
            assertClassAccess(classRowOrThrow(targetClass), user);
        }
        LocalDateTime since = now().minusDays(rangeDays(range));
        ClassData data = targetClass == null
                ? loadScope(null, null, since, user)
                : load(targetClass, classRowOrThrow(targetClass), since);

        Metric metric = metric(data, targetId);
        List<Map<String, Object>> kpRows = knowledgeRows(data);
        List<Map<String, Object>> weakKps = new ArrayList<>();
        for (Map<String, Object> kp : kpRows) {
            double percent = studentKpPercent(data, targetId, num(kp.get("kpId")));
            weakKps.add(map("name", kp.get("kpName"), "masteryPercent", percent));
        }
        weakKps.sort(Comparator.comparingDouble(k -> toDouble(k.get("masteryPercent"))));
        if (weakKps.size() > 5) {
            weakKps = new ArrayList<>(weakKps.subList(0, 5));
        }
        List<Map<String, Object>> aiStages = new ArrayList<>();
        Map<Integer, Long> byStage = new TreeMap<>();
        for (Map<String, Object> record : data.aiRecords) {
            if (num(record.get("user_id")) == targetId) {
                byStage.merge((int) num(record.get("stage_type")), 1L, Long::sum);
            }
        }
        for (Map.Entry<Integer, Long> entry : byStage.entrySet()) {
            aiStages.add(map("stage", entry.getKey(), "count", entry.getValue()));
        }
        return map("userId", targetId, "name", text(target.get("real_name")),
                "stuNo", text(target.get("username")), "classId", targetClass,
                "solved", metric.solved, "total", metric.total, "score", round1(metric.score),
                "acRate", round2(metric.acRate), "aiUsed", metric.aiUsed,
                "rank", rank(data, targetId), "weakKps", weakKps,
                "recentSubmissions", recentSubmissions(data, targetId, 4), "aiStages", aiStages);
    }

    // ==================================================================
    // 数据装载
    // ==================================================================

    /** 班级上下文：一次请求只查一遍基数表，后续所有指标都从这份快照派生 */
    private static final class ClassData {
        Long classId;
        Map<String, Object> clazz;
        LocalDateTime since;
        List<Map<String, Object>> students = List.of();
        Set<Long> problems = new LinkedHashSet<>();
        Map<Long, Map<Long, Integer>> bestScore = new HashMap<>();
        List<Map<String, Object>> submissions = List.of();
        List<Map<String, Object>> aiRecords = List.of();
        Map<Long, Map<String, Object>> problemMeta = new HashMap<>();
        List<Map<String, Object>> kps = List.of();
        Map<Long, List<Long>> problemKps = new HashMap<>();
    }

    /** 单个学生的派生指标（{@code score} 是 0–100 的得分率，见类注释的口径表） */
    private static final class Metric {
        long userId;
        String name;
        String stuNo;
        int total;
        int solved;
        double score;
        double acRate;
        long aiUsed;
        Long lastActiveAt;
        String riskLevel = "ok";
        String riskReason = "进度、得分与 AI 使用都在班级合理区间";
        int[] spark = new int[DAYS_WEEK];
    }

    /**
     * 装载班级快照：一次请求内把班级、学生、活动、题目元数据、知识点关联、
     * 成绩汇总、判题提交、AI 流水各查一遍，后续指标全部从这份快照派生，
     * 避免「每个学生查一次库」的 N+1。
     *
     * <p><b>为什么活动要按 {@code create_time} 过滤：</b>{@code range} 决定统计窗口。
     * 若只按窗口过滤提交、却把历史上的所有活动都算进「应做题数」，分母会异常大、
     * 完成度凭空变低。两者必须用同一时间口径。</p>
     *
     * @param since 统计窗口下界；null = 全部历史（{@code #51 withStats} 用）
     */
    private ClassData load(long classId, Map<String, Object> clazz, LocalDateTime since) {
        ClassData data = new ClassData();
        data.classId = classId;
        data.clazz = clazz;
        data.since = since;
        data.students = repo.students(classId);
        data.submissions = repo.submissions(classId, since);
        data.aiRecords = repo.aiRecords(classId, since);

        List<Long> assignmentIds = new ArrayList<>();
        for (Map<String, Object> assignment : repo.assignments(classId, false)) {
            LocalDateTime created = time(assignment.get("create_time"));
            if (since != null && created != null && created.isBefore(since)) {
                continue;
            }
            assignmentIds.add(num(assignment.get("id")));
            data.problems.addAll(problemIds(assignment.get("problem_ids")));
        }
        /* ------------------------------------------------------------------
           题目元数据必须覆盖「本次统计里出现过的全部题目」，不能只取活动题单。
           ------------------------------------------------------------------
           第 6 步实测缺陷：原实现只把 teach_assignment.problem_ids 灌进 data.problems，
           于是**自由练习提交**（judge_submission.assignment_id = 0 的那些）拿不到标题与难度，
           #54 会下发 `title:"题目 1024" / difficulty:null` —— 而库里 #1024 明明是
           「最大异或和 / 难度 3」。前端 `difficulty` 为 null 时难度徽标直接空掉，
           评审一眼就能看出这一行是假的。

           真实数据里这类提交占了绝大多数（本库 119 条提交里 103 条是 assignment_id=0），
           所以不修的话「易错题排行」的主体会全是 `题目 N / 难度空`。

           注意：这里只补 problemMeta（纯展示用的标题/难度），**不往 data.problems 里塞**
           ——data.problems 的语义是「教学活动应做的题」，塞进自由练习的题会让
           #58 的 solved/total 分母虚高。
           ------------------------------------------------------------------ */
        Set<Long> metaIds = new LinkedHashSet<>(data.problems);
        for (Map<String, Object> submission : data.submissions) {
            long problemId = num(submission.get("problem_id"));
            if (problemId > 0) {
                metaIds.add(problemId);
            }
        }
        if (!metaIds.isEmpty()) {
            for (Map<String, Object> problem : repo.problems(metaIds)) {
                data.problemMeta.put(num(problem.get("id")), problem);
            }
            for (Map<String, Object> link : repo.problemKps(metaIds)) {
                data.problemKps.computeIfAbsent(num(link.get("problem_id")), k -> new ArrayList<>())
                        .add(num(link.get("kp_id")));
            }
        }
        data.kps = repo.knowledgePoints();
        for (Map<String, Object> row : repo.scoreRows(assignmentIds)) {
            long userId = num(row.get("user_id"));
            long problemId = num(row.get("problem_id"));
            int score = (int) num(row.get("max_score"));
            data.bestScore.computeIfAbsent(userId, k -> new HashMap<>())
                    .merge(problemId, score, Math::max);
        }
        return data;
    }

    /**
     * 跨班装载（{@code classId == null}，仅 ADMIN 使用）。
     *
     * <p>此时「应做题」无法按班界定，故只统计与班级无强耦合的两类数据：
     * 提交流水与 AI 调用流水（外加学生名册用于显示人名），
     * 用于 {@code #53 week-trend} 与 {@code #61 ai-usage} 的全站视图。</p>
     */
    private ClassData loadScope(Long classId, Map<String, Object> clazz, LocalDateTime since, SysUser user) {
        if (classId != null) {
            return load(classId, clazz == null ? classRowOrThrow(classId) : clazz, since);
        }
        ClassData data = new ClassData();
        data.classId = null;
        data.clazz = null;
        data.since = since;
        data.students = repo.allStudents();
        data.submissions = repo.submissions(null, since);
        data.aiRecords = repo.aiRecords(null, since);
        return data;
    }

    // ==================================================================
    // 指标计算
    // ==================================================================

    private Metric metric(ClassData data, long userId) {
        Metric metric = new Metric();
        metric.userId = userId;
        for (Map<String, Object> student : data.students) {
            if (num(student.get("id")) == userId) {
                metric.name = text(student.get("real_name"));
                metric.stuNo = text(student.get("username"));
                LocalDateTime login = time(student.get("last_login_at"));
                metric.lastActiveAt = epoch(login);
                break;
            }
        }
        Map<Long, Integer> best = data.bestScore.getOrDefault(userId, Map.of());
        metric.total = data.problems.size();
        int earned = 0;
        int solved = 0;
        for (Long problemId : data.problems) {
            Integer score = best.get(problemId);
            if (score == null) {
                continue;
            }
            earned += score;
            if (score >= FULL_SCORE) {
                solved++;
            }
        }
        metric.solved = solved;
        metric.score = metric.total == 0 ? 0 : round2(earned * 100d / (metric.total * (double) FULL_SCORE));
        metric.acRate = percent(solved, metric.total);

        long aiUsed = 0;
        for (Map<String, Object> record : data.aiRecords) {
            if (num(record.get("user_id")) == userId) {
                aiUsed++;
            }
        }
        metric.aiUsed = aiUsed;

        LocalDateTime lastSubmit = null;
        int[] daily = new int[DAYS_WEEK];
        LocalDate today = now().toLocalDate();
        for (Map<String, Object> submission : data.submissions) {
            if (num(submission.get("user_id")) != userId) {
                continue;
            }
            LocalDateTime at = time(submission.get("create_time"));
            if (at == null) {
                continue;
            }
            if (lastSubmit == null || at.isAfter(lastSubmit)) {
                lastSubmit = at;
            }
            long ago = java.time.temporal.ChronoUnit.DAYS.between(at.toLocalDate(), today);
            if (ago >= 0 && ago < DAYS_WEEK) {
                daily[DAYS_WEEK - 1 - (int) ago]++;
            }
        }
        if (lastSubmit != null) {
            metric.lastActiveAt = epoch(lastSubmit);
        }
        int max = 0;
        for (int value : daily) {
            max = Math.max(max, value);
        }
        for (int i = 0; i < daily.length; i++) {
            metric.spark[i] = max == 0 ? 0 : Math.round(daily[i] * 100f / max);
        }

        applyRisk(metric, lastSubmit);
        return metric;
    }

    /**
     * 风险分档（单值，优先级 idle &gt; ai &gt; low）。
     *
     * <p>三档的判据全部来自真实计数，不掺任何「示例值」：
     * idle = 窗口内最近 14 天没有提交；ai = AI 调用 >= 12 次；
     * low = 得分率 < 60 且确实交过东西（从没交过的已归 idle）。</p>
     */
    private void applyRisk(Metric metric, LocalDateTime lastSubmit) {
        LocalDateTime idleLine = now().minusDays(RISK_IDLE_DAYS);
        if (lastSubmit == null || lastSubmit.isBefore(idleLine)) {
            metric.riskLevel = "idle";
            metric.riskReason = lastSubmit == null
                    ? "统计周期内没有任何判题提交"
                    : "最近一次提交在 " + RISK_IDLE_DAYS + " 天前，已长期未练习";
            return;
        }
        if (metric.aiUsed >= RISK_AI_THRESHOLD) {
            metric.riskLevel = "ai";
            metric.riskReason = "AI 调用 " + metric.aiUsed + " 次，达到依赖阈值 " + RISK_AI_THRESHOLD + " 次";
            return;
        }
        if (metric.score < RISK_LOW_SCORE) {
            metric.riskLevel = "low";
            metric.riskReason = "得分率 " + round1(metric.score) + "%，低于及格线 " + (int) RISK_LOW_SCORE + "%";
        }
    }

    /** 知识点掌握度行（按班聚合，方向：越高越强） */
    private List<Map<String, Object>> knowledgeRows(ClassData data) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (data.kps.isEmpty()) {
            return rows;
        }
        Map<Long, Set<Long>> acUsers = new HashMap<>();
        Map<Long, Set<Long>> triedUsers = new HashMap<>();
        Map<Long, Long> acSlots = new HashMap<>();
        Map<Long, Long> triedSlots = new HashMap<>();
        for (Map<String, Object> submission : data.submissions) {
            long problemId = num(submission.get("problem_id"));
            List<Long> kpIds = data.problemKps.getOrDefault(problemId, List.of());
            if (kpIds.isEmpty()) {
                continue;
            }
            long userId = num(submission.get("user_id"));
            boolean accepted = num(submission.get("status")) == 3;
            for (Long kpId : kpIds) {
                triedUsers.computeIfAbsent(kpId, k -> new HashSet<>()).add(userId);
                triedSlots.merge(kpId, 1L, Long::sum);
                if (accepted) {
                    acUsers.computeIfAbsent(kpId, k -> new HashSet<>()).add(userId);
                    acSlots.merge(kpId, 1L, Long::sum);
                }
            }
        }
        for (Map<String, Object> kp : data.kps) {
            long kpId = num(kp.get("id"));
            String kpName = text(kp.get("name"));
            int acCount = acUsers.getOrDefault(kpId, Set.of()).size();
            int triedCount = triedUsers.getOrDefault(kpId, Set.of()).size();
            double mastery = percent(acCount, triedCount);
            List<Map<String, Object>> recommend = recommend(data, kpId, 3);
            rows.add(map("kpId", kpId, "kpName", kpName, "masteryPercent", mastery,
                    "acCount", acCount, "triedCount", triedCount, "level", masteryLevel(mastery),
                    "symptom", symptom(kpName, acCount, triedCount, acSlots.getOrDefault(kpId, 0L),
                            triedSlots.getOrDefault(kpId, 0L)),
                    "cause", cause(mastery), "advice", advice(mastery), "recommendProblems", recommend));
        }
        return rows;
    }

    /** 某个学生在某知识点上的个人掌握度（个人口径，与班的聚合口径分开算） */
    private double studentKpPercent(ClassData data, long userId, long kpId) {
        Set<Long> acProblems = new HashSet<>();
        Set<Long> triedProblems = new HashSet<>();
        for (Map<String, Object> submission : data.submissions) {
            if (num(submission.get("user_id")) != userId) {
                continue;
            }
            long problemId = num(submission.get("problem_id"));
            if (!data.problemKps.getOrDefault(problemId, List.of()).contains(kpId)) {
                continue;
            }
            triedProblems.add(problemId);
            if (num(submission.get("status")) == 3) {
                acProblems.add(problemId);
            }
        }
        return percent(acProblems.size(), triedProblems.size());
    }

    /** 推荐练习：优先「本班还没做过」的同知识点已发布题目 */
    private List<Map<String, Object>> recommend(ClassData data, long kpId, int limit) {
        List<Map<String, Object>> pool = repo.publishedProblemsOfKp(kpId, limit * 4);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> problem : pool) {
            long problemId = num(problem.get("id"));
            if (data.problems.contains(problemId)) {
                continue;
            }
            out.add(map("problemId", problemId, "title", text(problem.get("title")),
                    "difficulty", intOrNull(problem.get("difficulty"))));
            if (out.size() >= limit) {
                return out;
            }
        }
        return out;
    }

    private Map<String, Object> studentRow(ClassData data, Map<String, Object> student, Metric metric) {
        List<Map<String, Object>> tags = new ArrayList<>();
        for (Map<String, Object> kp : data.kps) {
            long kpId = num(kp.get("id"));
            double percent = studentKpPercent(data, metric.userId, kpId);
            if (percent > 0 || touched(data, metric.userId, kpId)) {
                tags.add(map("name", text(kp.get("name")), "percent", percent));
            }
        }
        return map("userId", metric.userId, "name", metric.name, "stuNo", metric.stuNo,
                "solved", metric.solved, "total", metric.total, "score", round1(metric.score),
                "aiUsed", metric.aiUsed, "acRate", round2(metric.acRate), "spark", toList(metric.spark),
                "riskLevel", metric.riskLevel, "riskReason", metric.riskReason,
                "knowledgeTags", tags, "recent", recentSubmissions(data, metric.userId, 4));
    }

    private boolean touched(ClassData data, long userId, long kpId) {
        for (Map<String, Object> submission : data.submissions) {
            if (num(submission.get("user_id")) != userId) {
                continue;
            }
            long problemId = num(submission.get("problem_id"));
            if (data.problemKps.getOrDefault(problemId, List.of()).contains(kpId)) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> recentSubmissions(ClassData data, long userId, int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> submission : data.submissions) {
            if (num(submission.get("user_id")) != userId) {
                continue;
            }
            long problemId = num(submission.get("problem_id"));
            Map<String, Object> meta = data.problemMeta.get(problemId);
            rows.add(map("submissionId", num(submission.get("id")),
                    "problemTitle", meta == null ? "题目 " + problemId : text(meta.get("title")),
                    "status", (int) num(submission.get("status")),
                    "score", num(submission.get("oi_score")),
                    "at", epoch(time(submission.get("create_time")))));
        }
        rows.sort(Comparator.comparingLong(r -> -num(r.get("submissionId"))));
        return rows.size() > limit ? new ArrayList<>(rows.subList(0, limit)) : rows;
    }

    /** 班级名次（按得分率降序，1 起） */
    private Integer rank(ClassData data, long userId) {
        List<Double> scores = new ArrayList<>();
        boolean present = false;
        for (Map<String, Object> student : data.students) {
            long id = num(student.get("id"));
            double score = metric(data, id).score;
            scores.add(score);
            if (id == userId) {
                present = true;
            }
        }
        if (!present) {
            return null;
        }
        Double mine = metric(data, userId).score;
        scores.sort(Comparator.reverseOrder());
        return scores.indexOf(mine) + 1;
    }

    private double averageScore(ClassData data) {
        if (data.students.isEmpty()) {
            return 0;
        }
        double sum = 0;
        for (Map<String, Object> student : data.students) {
            sum += metric(data, num(student.get("id"))).score;
        }
        return round2(sum / data.students.size());
    }

    /** 班级 AC 率 = 逐生 AC 率的<b>算术平均</b>（与原型一致；按提交加权的口径见 {@code #53}） */
    private double classAcRate(ClassData data) {
        if (data.students.isEmpty()) {
            return 0;
        }
        double sum = 0;
        for (Map<String, Object> student : data.students) {
            sum += metric(data, num(student.get("id"))).acRate;
        }
        return round2(sum / data.students.size());
    }

    private int activeCount(ClassData data) {
        LocalDateTime line = now().minusDays(DAYS_WEEK);
        int active = 0;
        for (Map<String, Object> student : data.students) {
            LocalDateTime login = time(student.get("last_login_at"));
            if (login != null && login.isAfter(line)) {
                active++;
            }
        }
        return active;
    }

    /**
     * 成绩分布。{@code ac}/{@code partial}/{@code fail} 是<b>人数占比</b>（三段相加为 100），
     * {@code max}/{@code min}/{@code median} 是<b>得分率</b>，{@code sd} 是得分率的标准差。
     * 全部按 0–100 刻度输出，与原型 {@code get dist()} 的形态一致。
     */
    private Map<String, Object> distribution(List<Double> scores) {
        if (scores.isEmpty()) {
            return map("ac", 0, "partial", 0, "fail", 0, "max", 0, "min", 0, "median", 0, "sd", 0);
        }
        long ac = scores.stream().filter(v -> v >= 85).count();
        long partial = scores.stream().filter(v -> v >= 60 && v < 85).count();
        long fail = scores.stream().filter(v -> v < 60).count();
        List<Double> sorted = new ArrayList<>(scores);
        sorted.sort(Comparator.naturalOrder());
        double median;
        int size = sorted.size();
        if (size % 2 == 1) {
            median = sorted.get(size / 2);
        } else {
            median = (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
        }
        double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = sorted.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
        return map("ac", percent(ac, size), "partial", percent(partial, size), "fail", percent(fail, size),
                "max", round1(sorted.get(size - 1)), "min", round1(sorted.get(0)),
                "median", round1(median), "sd", round1(Math.sqrt(variance)));
    }

    private double averageMastery(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        return round2(rows.stream().mapToDouble(k -> toDouble(k.get("masteryPercent"))).average().orElse(0));
    }

    // ==================================================================
    // 文案（全部由真实计数拼出，不含固定示例）
    // ==================================================================

    private String symptom(String kpName, int acCount, int triedCount, long acSlots, long triedSlots) {
        if (triedCount == 0) {
            return "统计周期内本班没有「" + kpName + "」的判题记录，暂时无法评估。";
        }
        return "「" + kpName + "」下 " + triedCount + " 名学生共提交 " + triedSlots + " 次，其中仅 "
                + acCount + " 人拿到 AC（" + acSlots + " 次通过）。";
    }

    private String cause(double mastery) {
        if (mastery >= MASTERY_STRONG) {
            return "该知识点的通过率已达标，错误主要来自个别边界数据。";
        }
        if (mastery >= MASTERY_WEAK) {
            return "通过率处于中间区间，说明思路能想到但细节不稳，典型是边界与规模处理。";
        }
        return "通过率偏低，通常是该知识点的建模或复杂度选择没建立起来，而不是单点笔误。";
    }

    private String advice(double mastery) {
        if (mastery >= MASTERY_STRONG) {
            return "保持现有练习强度，可加入跨知识点综合题做迁移训练。";
        }
        if (mastery >= MASTERY_WEAK) {
            return "布置一组该知识点的边界专项（小规模 + 极值），先把通过率推到 70% 以上。";
        }
        return "先补该知识点的基础模型课，再配一组从易到难的阶梯练习，暂不进入综合题。";
    }

    private String suggestion(Metric metric) {
        return switch (metric.riskLevel) {
            case "ai" -> "把 AI 提示配额收紧，要求先手写思路再求助（当前 " + metric.aiUsed + " 次）。";
            case "low" -> "约谈并安排一次该生薄弱知识点的专项练习（当前得分率 " + round1(metric.score) + "%）。";
            case "idle" -> "先确认是否掉队：发一条催交提醒，并检查账号能否正常登录。";
            default -> "无需干预。";
        };
    }

    // ==================================================================
    // 入参与权限
    // ==================================================================

    /**
     * 当前用户实体（含角色与 class_id）。
     *
     * <p><b>为什么复用 {@link ActivitySupport#current()} 而不是自己查库：</b>那个方法已经做了
     * 三件事——「用户存在」「{@code status=1}」「库里的 role 与网关头 {@code X-User-Role} 一致」。
     * 自己再查一遍等于复制一份鉴权口径，一旦有一处漏了 status 校验，
     * 被禁用的账号就能靠旧 token 继续看学情。</p>
     */
    private SysUser current() {
        return support.current();
    }

    private SysUser requireTeacher() {
        SysUser user = current();
        if (!Set.of("TEACHER", "ADMIN").contains(text(user.getRole()))) {
            throw BusinessException.forbidden("仅教师可查看该看板");
        }
        return user;
    }

    // ------------------------------------------------------------------
    // 入参校验：所有枚举型 / 数值型入参在「进入业务逻辑之前」过一遍白名单与范围，
    // 一律抛 BusinessException.badRequest（→ 400）。绝不让非法值流到
    // Comparator.comparingDouble / HikariCP / 拆箱点上去变成 500。
    // 范式与 AssignmentService.list() 一致（`Set.of(...).contains(...)`）。
    // ------------------------------------------------------------------

    /** 班级 ID：必须是正整数。为负/为 0 时直接 400，不去查库（查不到会退化成 404 或 NPE） */
    private static long requireClassId(Long classId) {
        if (classId == null) {
            throw BusinessException.badRequest("缺少 classId");
        }
        if (classId <= 0) {
            throw BusinessException.badRequest("classId 必须为正整数");
        }
        return classId;
    }

    /** 主键类 ID（用户 / 题目 / 活动）：必须是正整数 */
    private static long requireId(Long value, String name) {
        if (value == null || value <= 0) {
            throw BusinessException.badRequest(name + " 必须为正整数");
        }
        return value;
    }

    /** 枚举型参数白名单：null/空白取默认值，其余必须命中集合，否则 400 */
    private static String requireEnum(String value, String name, String fallback, Set<String> allowed) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        if (!allowed.contains(trimmed)) {
            throw BusinessException.badRequest(name + " 只能为 " + String.join(" / ", new TreeSet<>(allowed)));
        }
        return trimmed;
    }

    /** 页码：必须 >= 1（{@code ActivitySupport.page} 也会校验，但这里先给更明确的字段名） */
    private static int requirePage(Integer page) {
        int value = page == null ? 1 : page;
        if (value < 1) {
            throw BusinessException.badRequest("page 必须大于 0");
        }
        return value;
    }

    /**
     * 每页条数：契约 §1 规定「超过 100 按 100 收敛」。
     *
     * <p>因此 {@code pageSize=9999} 是<b>合法输入</b>，收敛到 100 后返回 200——
     * 不能报 400，更不能 500。只有 {@code < 1} 才是非法值。</p>
     */
    private static int requirePageSize(Integer pageSize) {
        int value = pageSize == null ? 20 : pageSize;
        if (value < 1) {
            throw BusinessException.badRequest("pageSize 必须大于 0");
        }
        return Math.min(value, 100);
    }

    /** 条数上限：给了就必须落在 [min, max]，否则 400；没给用 fallback */
    private static int requireLimit(Integer limit, int fallback, int min, int max, String name) {
        if (limit == null) {
            return fallback;
        }
        if (limit < min || limit > max) {
            throw BusinessException.badRequest(name + " 应在 " + min + " 至 " + max + " 之间");
        }
        return limit;
    }

    private Map<String, Object> classRowOrThrow(Long classId) {
        if (classId == null || classId <= 0) {
            throw BusinessException.badRequest("班级ID不合法");
        }
        Map<String, Object> row = repo.classRow(classId);
        if (row == null) {
            throw BusinessException.notFound("班级不存在");
        }
        return row;
    }

    /** 归属校验：ADMIN 放行；TEACHER 必须是负责教师；STUDENT 必须是本班 */
    private void assertClassAccess(Map<String, Object> clazz, SysUser user) {
        if ("ADMIN".equals(text(user.getRole()))) {
            return;
        }
        Long teacherId = clazz.get("teacher_id") == null ? null : num(clazz.get("teacher_id"));
        if ("TEACHER".equals(text(user.getRole()))) {
            if (!Objects.equals(teacherId, user.getId())) {
                throw BusinessException.forbidden("仅可查看自己负责的班级学情");
            }
            return;
        }
        if (!Objects.equals(clazz.get("id") == null ? null : num(clazz.get("id")), user.getClassId())) {
            throw BusinessException.forbidden("仅可查看本班学情");
        }
    }

    /**
     * 解析「未显式给 classId」时的默认班级。
     *
     * @param required true 时必须能解析出班级，否则 400（{@code #54} 这类按题聚合的端点没班级就没口径）；
     *                 false 时允许返回 null 表示全站（仅 ADMIN 到达这里）
     */
    private Long resolveClassId(Long classId, SysUser user, boolean required) {
        if (classId != null) {
            return classId;
        }
        if ("TEACHER".equals(text(user.getRole()))) {
            for (Map<String, Object> row : repo.classes()) {
                if (Objects.equals(row.get("teacher_id") == null ? null : num(row.get("teacher_id")), user.getId())) {
                    return num(row.get("id"));
                }
            }
            throw BusinessException.notFound("当前账号还没有负责的班级");
        }
        if ("STUDENT".equals(text(user.getRole()))) {
            return user.getClassId();
        }
        if (required) {
            throw BusinessException.badRequest("ADMIN 请显式指定 classId");
        }
        return null;
    }

    private int rangeDays(String range) {
        String value = requireEnum(range, "range", "week", Set.of("week", "month", "term"));
        return switch (value) {
            case "month" -> DAYS_MONTH;
            case "term" -> DAYS_TERM;
            default -> DAYS_WEEK;
        };
    }

    private int trendWindowDays(Integer weeks, String range) {
        // range 与 weeks 二选一，同时给出时 range 优先
        String value = requireEnum(range, "range", null, Set.of("7d", "30d", "term", "all"));
        if (value != null) {
            return switch (value) {
                case "30d" -> DAYS_MONTH;
                case "term" -> DAYS_TERM;
                case "all" -> 0;
                default -> DAYS_WEEK;
            };
        }
        int count = weeks == null ? 1 : weeks;
        if (count < 1 || count > 26) {
            throw BusinessException.badRequest("weeks 应在 1 至 26 之间");
        }
        return count * DAYS_WEEK;
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private List<Map<String, Object>> cases(Object raw) {
        String value = text(raw);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(value, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            // 坏 JSON 只让这一条提交的逐点明细降级，不让整个看板 500
            return List.of();
        }
    }

    private List<Long> problemIds(Object raw) {
        String value = text(raw);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(value, new TypeReference<List<Long>>() {
            });
        } catch (Exception e) {
            // 活动题目 JSON 坏掉时跳过该活动，其余活动照常统计
            return List.of();
        }
    }

    private static String judgeCode(long status) {
        return switch ((int) status) {
            case 3 -> "AC";
            case 4 -> "PAC";
            case 5 -> "WA";
            case 6 -> "TLE";
            case 7 -> "MLE";
            case 8 -> "RE";
            case 9 -> "CE";
            default -> "PENDING";
        };
    }

    private static String masteryLevel(double mastery) {
        if (mastery >= MASTERY_STRONG) {
            return "strong";
        }
        return mastery >= MASTERY_WEAK ? "normal" : "weak";
    }

    private static int riskOrder(String level) {
        return switch (level) {
            case "idle" -> 0;
            case "ai" -> 1;
            case "low" -> 2;
            default -> 3;
        };
    }

    private static String stageName(int stage) {
        return stage >= 1 && stage < AI_STAGES.length ? AI_STAGES[stage] : "阶段 " + stage;
    }

    /** 首个失败测试点：非 AC 提交里出现最多的 {@code first_failed_case_id} */
    private static Integer firstFailSeq(List<Map<String, Object>> submissions) {
        Map<Long, Long> counter = new LinkedHashMap<>();
        for (Map<String, Object> submission : submissions) {
            if (num(submission.get("status")) == 3) {
                continue;
            }
            Object value = submission.get("first_failed_case_id");
            if (value == null) {
                continue;
            }
            counter.merge(num(value), 1L, Long::sum);
        }
        return counter.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(e -> e.getKey().intValue()).orElse(null);
    }

    /** 该题失败次数最多的前 3 名学生（含最后一次失败时刻） */
    private static List<Map<String, Object>> topFailStudents(List<Map<String, Object>> submissions,
                                                             List<Map<String, Object>> students) {
        Map<Long, Map<String, Object>> index = new HashMap<>();
        for (Map<String, Object> student : students) {
            index.put(num(student.get("id")), student);
        }
        Map<Long, long[]> counter = new LinkedHashMap<>();
        Map<Long, LocalDateTime> last = new HashMap<>();
        for (Map<String, Object> submission : submissions) {
            if (num(submission.get("status")) == 3) {
                continue;
            }
            long userId = num(submission.get("user_id"));
            counter.computeIfAbsent(userId, k -> new long[]{0})[0]++;
            LocalDateTime at = time(submission.get("create_time"));
            if (at != null && (last.get(userId) == null || at.isAfter(last.get(userId)))) {
                last.put(userId, at);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Long, long[]> entry : counter.entrySet()) {
            Map<String, Object> student = index.get(entry.getKey());
            if (student == null) {
                continue;
            }
            rows.add(map("name", text(student.get("real_name")), "stuNo", text(student.get("username")),
                    "failCount", entry.getValue()[0], "lastAt", epoch(last.get(entry.getKey()))));
        }
        rows.sort(Comparator.comparingLong(r -> -num(r.get("failCount"))));
        return rows.size() > 3 ? new ArrayList<>(rows.subList(0, 3)) : rows;
    }

    private static Map<Long, List<Map<String, Object>>> groupBy(List<Map<String, Object>> rows, String key) {
        Map<Long, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            grouped.computeIfAbsent(num(row.get(key)), k -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private static LocalDate earliestDate(List<Map<String, Object>> submissions) {
        LocalDate earliest = null;
        for (Map<String, Object> submission : submissions) {
            LocalDateTime at = time(submission.get("create_time"));
            if (at == null) {
                continue;
            }
            if (earliest == null || at.toLocalDate().isBefore(earliest)) {
                earliest = at.toLocalDate();
            }
        }
        return earliest;
    }

    private static List<Integer> toList(int[] values) {
        List<Integer> out = new ArrayList<>(values.length);
        for (int value : values) {
            out.add(value);
        }
        return out;
    }

    private static String preview(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= 50 ? trimmed : trimmed.substring(0, 50);
    }

    private static double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }

    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private static double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static long num(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static Integer intOrNull(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static LocalDateTime time(Object value) {
        if (value instanceof LocalDateTime date) {
            return date;
        }
        if (value instanceof Timestamp stamp) {
            return stamp.toLocalDateTime();
        }
        if (value instanceof LocalDate date) {
            return date.atStartOfDay();
        }
        return null;
    }

    /** 判题状态 3 = AC（与 {@code judge_submission.status} 的 0–9 字典一致） */
    private static final int STATUS_AC = 3;
}
