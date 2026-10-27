package com.oj.teaching.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 学情分析（契约 02 §6.5 班级与待办簇 / §6.6 学情分析簇）的<b>只读</b>跨域查询仓库。
 *
 * <p><b>为什么用 JdbcTemplate 直查而不用 MyBatis-Plus：</b>学情要聚合的数据横跨
 * {@code judge_submission}（判题域）、{@code ai_chat_record}（AI 域）、
 * {@code prob_question} / {@code prob_test_case} / {@code prob_question_kp}（题库域）。
 * 这五张表虽然与教学表同库（{@code oj_cloud}），但写作用域不归本服务；
 * 给它们建 Entity + Mapper 等于在教学域里复制一份别人的表模型，列一改就两边漂移。
 * 所以这里只做 {@code SELECT ... FROM 别人的表}，<b>一个字都不写</b>
 * ——与 {@link PlagiarismRepository} 是同一先例。</p>
 *
 * <p><b>为什么聚合不在 SQL 里做：</b>「按学生 × 知识点 × 天」的多层 GROUP BY 一旦口径要变
 * 就得重写 SQL，而且无法与 {@code teach_assignment.problem_ids} 这种 JSON 列联动
 * （应做题数是 JSON 数组的并集，SQL 里没法优雅地求并集）。因此本类只负责
 * <b>带条件取数</b>，分组与百分比统一在 {@link AnalyticsService} 里做——口径只有一处。</p>
 *
 * <p><b>越权防线：</b>凡是能按班级收窄的查询都第一时间套
 * {@code sys_user.class_id=?} / {@code teach_assignment.class_id=?}；
 * {@code classId == null} 只对 ADMIN 的跨班视图开放，调用前由服务层校验角色。</p>
 *
 * @author oj-cloud
 */
@Repository
public class AnalyticsRepository {

    /**
     * 判题提交的公共列。
     *
     * <p>{@code case_details} 刻意<b>不</b>在这里——它是 JSON 列，单条可到几十 KB，
     * 只有 #55 / #60 需要，由 {@link #submissionsWithCases} 单独带出来。</p>
     */
    private static final String SUBMISSION_COLUMNS =
            "j.id,j.user_id,j.problem_id,j.assignment_id,j.status,j.oi_score,"
                    + "j.pass_case_count,j.total_case_count,j.first_failed_case_id,j.create_time";

    private final JdbcTemplate db;

    public AnalyticsRepository(JdbcTemplate db) {
        this.db = db;
    }

    // ==================================================================
    // 班级与用户
    // ==================================================================

    /** 全部班级（含负责教师姓名）；ADMIN 跨班视图与 #51 共用 */
    public List<Map<String, Object>> classes() {
        return db.queryForList("SELECT c.id,c.class_name,c.teacher_id,c.student_count,"
                + "u.real_name teacher_name FROM teach_class c "
                + "LEFT JOIN sys_user u ON u.id=c.teacher_id ORDER BY c.id");
    }

    /** 单个班级；不存在返回 null（由服务层转 404） */
    public Map<String, Object> classRow(long classId) {
        return first(db.queryForList("SELECT c.id,c.class_name,c.teacher_id,c.student_count,"
                + "u.real_name teacher_name FROM teach_class c "
                + "LEFT JOIN sys_user u ON u.id=c.teacher_id WHERE c.id=?", classId));
    }

    /** 班级在册学生（与 `ActivityMapper#students` 同口径：role=STUDENT 且 status=1） */
    public List<Map<String, Object>> students(long classId) {
        return db.queryForList("SELECT id,username,real_name,class_id,last_login_at FROM sys_user "
                + "WHERE class_id=? AND role='STUDENT' AND status=1 ORDER BY username,id", classId);
    }

    /** 全站在册学生（仅 ADMIN 的跨班视图使用；一次查完，避免逐个补姓名造成 N+1） */
    public List<Map<String, Object>> allStudents() {
        return db.queryForList("SELECT id,username,real_name,class_id,last_login_at FROM sys_user "
                + "WHERE role='STUDENT' AND status=1 ORDER BY username,id");
    }

    /** 单用户（含角色，供 #62 的归属判定与 404） */
    public Map<String, Object> user(long userId) {
        return first(db.queryForList("SELECT id,username,real_name,role,class_id,status,last_login_at "
                + "FROM sys_user WHERE id=?", userId));
    }

    // ==================================================================
    // 教学活动与题目元数据
    // ==================================================================

    /**
     * 班级的教学活动。
     *
     * @param includeDrafts 是否包含草稿（{@code status=0}）。学情统计一律传 {@code false}：
     *                      草稿学生根本看不到，算进「应做题数」会让完成度凭空变低。
     */
    public List<Map<String, Object>> assignments(long classId, boolean includeDrafts) {
        return db.queryForList("SELECT id,title,mode_type,status,problem_ids,start_time,end_time,create_time "
                + "FROM teach_assignment WHERE class_id=?" + (includeDrafts ? "" : " AND status<>0")
                + " ORDER BY id", classId);
    }

    /** 题目元数据（标题 / 难度 / 状态），只用于展示 */
    public List<Map<String, Object>> problems(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return db.queryForList("SELECT id,title,difficulty,state,knowledge_point_id FROM prob_question "
                + "WHERE deleted_at IS NULL AND id IN (" + placeholders(ids.size()) + ")", ids.toArray());
    }

    /** 测试点骨架（seqNo / kind / score）：全班零提交时 #55 仍需列出测试点表 */
    public List<Map<String, Object>> testCases(Collection<Long> problemIds) {
        if (problemIds == null || problemIds.isEmpty()) {
            return List.of();
        }
        return db.queryForList("SELECT problem_id,seq_no,kind,score FROM prob_test_case "
                + "WHERE deleted_at IS NULL AND problem_id IN (" + placeholders(problemIds.size()) + ")"
                + " ORDER BY problem_id,seq_no", problemIds.toArray());
    }

    /** 知识点字典全量（按 sort_order） */
    public List<Map<String, Object>> knowledgePoints() {
        return db.queryForList("SELECT id,name,sort_order FROM prob_knowledge_point ORDER BY sort_order,id");
    }

    /** 题目 ↔ 知识点多对多（Day3 补丁新增表，是学情聚合的唯一权威关联） */
    public List<Map<String, Object>> problemKps(Collection<Long> problemIds) {
        if (problemIds == null || problemIds.isEmpty()) {
            return List.of();
        }
        return db.queryForList("SELECT problem_id,kp_id,weight,is_primary FROM prob_question_kp "
                + "WHERE problem_id IN (" + placeholders(problemIds.size()) + ")", problemIds.toArray());
    }

    /**
     * 某个知识点下的已发布题目（#57 `recommendProblems` 用）。
     *
     * <p>上限刻意放宽，让服务层剔除「本班已 AC」的题后再截断，
     * 避免推荐位全耗在已经掌握的题目上。</p>
     */
    public List<Map<String, Object>> publishedProblemsOfKp(long kpId, int limit) {
        return db.queryForList("SELECT q.id,q.title,q.difficulty FROM prob_question q "
                + "JOIN prob_question_kp k ON k.problem_id=q.id "
                + "WHERE k.kp_id=? AND q.state=1 AND q.deleted_at IS NULL "
                + "ORDER BY q.difficulty,q.id LIMIT ?", kpId, Math.max(1, limit));
    }

    // ==================================================================
    // 成绩汇总（教学域自己的表）
    // ==================================================================

    /**
     * 指定活动集合内的单题最高分汇总。
     *
     * <p><b>为什么要传 assignmentIds 而不是 classId：</b>契约里的 {@code range} 决定
     * 「统计哪些活动」，而活动的归属时段只有服务层知道（{@code teach_assignment.create_time}）。
     * 把活动集合算好再传进来，可以保证「应做题数」与「已得分数」来自<b>同一批活动</b>，
     * 不会出现「分母含旧活动、分子只有新活动」的错位。</p>
     *
     * <p>{@code max_score} 是教务口径的有效分：查重确认抄袭后会清零
     * （{@code red_flag_type=2}）。这里<b>不</b>拿 {@code prev_max_score} 还原——
     * 还原是「撤销复核」专用动作，学情看板必须体现处罚后的真实分数。</p>
     */
    public List<Map<String, Object>> scoreRows(Collection<Long> assignmentIds) {
        if (assignmentIds == null || assignmentIds.isEmpty()) {
            return List.of();
        }
        return db.queryForList("SELECT assignment_id,user_id,problem_id,max_score,submit_count,ai_used_count "
                + "FROM teach_student_score WHERE assignment_id IN (" + placeholders(assignmentIds.size()) + ")"
                + " ORDER BY user_id,problem_id", assignmentIds.toArray());
    }

    // ==================================================================
    // 判题提交（判题域，只读）
    // ==================================================================

    /**
     * 学生判题提交（含自由练习 {@code assignment_id=0}）。
     *
     * @param classId 班级；<b>null 表示不按班级收窄</b>（仅 ADMIN 跨班视图使用）
     * @param since   时间窗下界；null 表示不限
     */
    public List<Map<String, Object>> submissions(Long classId, LocalDateTime since) {
        StringBuilder sql = new StringBuilder("SELECT ").append(SUBMISSION_COLUMNS)
                .append(" FROM judge_submission j JOIN sys_user u ON u.id=j.user_id WHERE u.role='STUDENT'");
        List<Object> args = new ArrayList<>();
        if (classId != null) {
            sql.append(" AND u.class_id=?");
            args.add(classId);
        }
        if (since != null) {
            sql.append(" AND j.create_time>=?");
            args.add(Timestamp.valueOf(since));
        }
        sql.append(" ORDER BY j.id");
        return db.queryForList(sql.toString(), args.toArray());
    }

    /** 带逐测试点明细的提交（#55 单题错误分析 / #60 热力图），按题目与作业可再收窄 */
    public List<Map<String, Object>> submissionsWithCases(Long classId, Long problemId, Long assignmentId, LocalDateTime since) {
        StringBuilder sql = new StringBuilder("SELECT ").append(SUBMISSION_COLUMNS).append(",j.case_details")
                .append(" FROM judge_submission j JOIN sys_user u ON u.id=j.user_id WHERE u.role='STUDENT'");
        List<Object> args = new ArrayList<>();
        if (classId != null) {
            sql.append(" AND u.class_id=?");
            args.add(classId);
        }
        if (problemId != null) {
            sql.append(" AND j.problem_id=?");
            args.add(problemId);
        }
        if (assignmentId != null) {
            sql.append(" AND j.assignment_id=?");
            args.add(assignmentId);
        }
        if (since != null) {
            sql.append(" AND j.create_time>=?");
            args.add(Timestamp.valueOf(since));
        }
        sql.append(" ORDER BY j.id");
        return db.queryForList(sql.toString(), args.toArray());
    }

    /** 单个学生的提交（#62 逐生明细） */
    public List<Map<String, Object>> submissionsOfUser(long userId, LocalDateTime since) {
        String sql = "SELECT " + SUBMISSION_COLUMNS + " FROM judge_submission j WHERE j.user_id=?"
                + (since == null ? "" : " AND j.create_time>=?") + " ORDER BY j.id DESC";
        return since == null
                ? db.queryForList(sql, userId)
                : db.queryForList(sql, userId, Timestamp.valueOf(since));
    }

    // ==================================================================
    // AI 调用审计（AI 域，只读）
    // ==================================================================

    /**
     * 学生 AI 调用流水。
     *
     * <p><b>为什么不用 {@code teach_student_score.ai_used_count}：</b>那一列是「作业内单题
     * 提示配额计数器」，只在特定链路回写，当前库里恒为 0；而 {@code ai_chat_record} 是每次
     * 真实调用的审计流水。<b>用量统计必须以流水为准</b>，否则看板永远显示 0 次。</p>
     *
     * @param classId 班级；null 表示不按班级收窄（仅 ADMIN 跨班视图使用）
     */
    public List<Map<String, Object>> aiRecords(Long classId, LocalDateTime since) {
        StringBuilder sql = new StringBuilder("SELECT r.id,r.user_id,r.stage_type,r.guardrail_action,"
                + "r.guard_reason,r.create_time FROM ai_chat_record r "
                + "JOIN sys_user u ON u.id=r.user_id WHERE u.role='STUDENT'");
        List<Object> args = new ArrayList<>();
        if (classId != null) {
            sql.append(" AND u.class_id=?");
            args.add(classId);
        }
        if (since != null) {
            sql.append(" AND r.create_time>=?");
            args.add(Timestamp.valueOf(since));
        }
        sql.append(" ORDER BY r.id");
        return db.queryForList(sql.toString(), args.toArray());
    }

    /** 单个学生的 AI 调用流水（#62） */
    public List<Map<String, Object>> aiRecordsOfUser(long userId, LocalDateTime since) {
        String sql = "SELECT id,user_id,stage_type,create_time FROM ai_chat_record WHERE user_id=?"
                + (since == null ? "" : " AND create_time>=?") + " ORDER BY id";
        return since == null
                ? db.queryForList(sql, userId)
                : db.queryForList(sql, userId, Timestamp.valueOf(since));
    }

    // ==================================================================
    // 待办聚合计数（#52）
    // ==================================================================

    /** 待发布（草稿）活动数；{@code teacherId=null} 表示管理员视角看全站 */
    public long draftAssignments(Long teacherId) {
        String sql = "SELECT COUNT(*) FROM teach_assignment WHERE status=0"
                + (teacherId == null ? "" : " AND teacher_id=?");
        return count(sql, teacherId == null ? new Object[]{} : new Object[]{teacherId});
    }

    /** 查重待复核对数（{@code audit_status=0} 即待审） */
    public long pendingPlagiarism(Long teacherId) {
        String sql = "SELECT COUNT(*) FROM teach_plagiarism_report r "
                + "JOIN teach_assignment a ON a.id=r.assignment_id WHERE r.audit_status=0"
                + (teacherId == null ? "" : " AND a.teacher_id=?");
        return count(sql, teacherId == null ? new Object[]{} : new Object[]{teacherId});
    }

    /**
     * 逾期未交的（活动 × 学生）组合数。
     *
     * <p>口径：活动已过截止时间且不是草稿，而该生在 {@code assignment_submission} 里
     * 没有 {@code SUBMITTED} 记录。这是「催交」这个待办唯一的真实定义。</p>
     */
    public long overdueUnsubmitted(Long teacherId, LocalDateTime now) {
        String sql = "SELECT COUNT(*) FROM teach_assignment a "
                + "JOIN sys_user u ON u.class_id=a.class_id AND u.role='STUDENT' AND u.status=1 "
                + "LEFT JOIN assignment_submission s "
                + "ON s.assignment_id=a.id AND s.user_id=u.id AND s.status='SUBMITTED' "
                + "WHERE a.status IN (1,2,3) AND a.end_time<? AND s.id IS NULL"
                + (teacherId == null ? "" : " AND a.teacher_id=?");
        return teacherId == null
                ? count(sql, new Object[]{Timestamp.valueOf(now)})
                : count(sql, new Object[]{Timestamp.valueOf(now), teacherId});
    }

    /** 模型通道健康态（#52 的「通道」待办数据源；只读 AI 域的配置表） */
    public List<Map<String, Object>> channelStates() {
        return db.queryForList("SELECT channel_code,channel_name,health_status,is_enabled "
                + "FROM ai_model_config ORDER BY id");
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }

    private static Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long count(String sql, Object[] args) {
        Long value = db.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }
}
