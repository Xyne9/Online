package com.oj.teaching.service;

import com.oj.common.core.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.sql.Timestamp;
import java.util.*;

/** SQL is kept in the plagiarism slice; shared teaching mappers are deliberately untouched. */
@Repository
public class PlagiarismRepository {
    private final JdbcTemplate db;
    public PlagiarismRepository(JdbcTemplate db) { this.db = db; }
    public Map<String, Object> assignment(long id, boolean lock) {
        return one("SELECT a.*,c.teacher_id class_teacher_id FROM teach_assignment a JOIN teach_class c ON c.id=a.class_id WHERE a.id=?" + (lock ? " FOR UPDATE" : ""), id);
    }
    public Map<String, Object> task(long id) { return one("SELECT * FROM teach_plagiarism_task WHERE id=?", id); }
    public List<Map<String, Object>> activeTasks(long assignmentId) {
        return db.queryForList("SELECT * FROM teach_plagiarism_task WHERE assignment_id=? AND state IN ('PENDING','STAGE1','STAGE2')", assignmentId);
    }
    public long insertTask(String display, long assignmentId, String problems, String language, double threshold,
                           String channel, long uid, long now) {
        return insert("INSERT INTO teach_plagiarism_task(display_no,assignment_id,problem_ids,language,stage1_threshold,stage2_channel,created_by,create_time) VALUES (?,?,?,?,?,?,?,?)",
                display, assignmentId, problems, language, threshold, channel, uid, new Timestamp(now));
    }
    public void taskProgress(long id, String state, int percent, int total, int compared, int suspicious,
                             int high, Long started, Long finished, String error) {
        db.update("UPDATE teach_plagiarism_task SET state=?,progress_percent=?,total_pairs=?,compared_pairs=?,suspicious_count=?,high_risk_count=?,started_at=?,finished_at=?,error_msg=? WHERE id=?",
                state, percent, total, compared, suspicious, high, stamp(started), stamp(finished), error, id);
    }
    public List<Long> sampleIds(long assignmentId, long problemId) {
        return db.queryForList("SELECT s.best_submission_id FROM teach_student_score s JOIN sys_user u ON u.id=s.user_id JOIN teach_assignment a ON a.id=s.assignment_id WHERE s.assignment_id=? AND s.problem_id=? AND u.class_id=a.class_id AND u.role='STUDENT' AND u.status=1 AND COALESCE(s.prev_max_score,s.max_score)>=60 AND s.best_submission_id IS NOT NULL ORDER BY s.user_id", Long.class, assignmentId, problemId);
    }
    public List<Map<String, Object>> reports(long assignmentId, Long problemId) {
        return db.queryForList("SELECT r.*,q.title problem_title,ua.real_name a_name,ua.username a_stu_no,ub.real_name b_name,ub.username b_stu_no FROM teach_plagiarism_report r LEFT JOIN prob_question q ON q.id=r.problem_id JOIN sys_user ua ON ua.id=r.user_a_id JOIN sys_user ub ON ub.id=r.user_b_id WHERE r.assignment_id=?" + (problemId == null ? "" : " AND r.problem_id=?") + " ORDER BY r.id", problemId == null ? new Object[]{assignmentId} : new Object[]{assignmentId, problemId});
    }
    public Map<String, Object> report(long id) {
        return one("SELECT r.*,q.title problem_title,ua.real_name a_name,ua.username a_stu_no,ub.real_name b_name,ub.username b_stu_no FROM teach_plagiarism_report r LEFT JOIN prob_question q ON q.id=r.problem_id JOIN sys_user ua ON ua.id=r.user_a_id JOIN sys_user ub ON ub.id=r.user_b_id WHERE r.id=?", id);
    }
    public long insertReport(String display, long taskId, long assignmentId, long problemId, String language,
                             long aUser, long aSub, long bUser, long bSub, double struct, Double sem,
                             String model, String diagnosis) {
        return insert("INSERT INTO teach_plagiarism_report(display_no,task_id,assignment_id,problem_id,language,user_a_id,sub_a_id,user_b_id,sub_b_id,stage1_algo_sim,stage2_ai_sim,ai_model_used,ai_diagnosis) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                display, taskId, assignmentId, problemId, language, aUser, aSub, bUser, bSub, struct, sem, model, diagnosis);
    }
    public void insertSegment(long reportId, int seq, String name, int af, int at, int bf, int bt, int rowCount, double sim, String annotation) {
        db.update("INSERT INTO teach_plagiarism_segment(report_id,seg_id,seg_name,a_from_line,a_to_line,b_from_line,b_to_line,row_count,similarity,annotation) VALUES (?,?,?,?,?,?,?,?,?,?)",
                reportId, seq, name, af, at, bf, bt, rowCount, sim, annotation == null || annotation.length() <= 255 ? annotation : annotation.substring(0,255));
    }
    public List<Map<String, Object>> segments(long reportId) { return db.queryForList("SELECT * FROM teach_plagiarism_segment WHERE report_id=? ORDER BY seg_id", reportId); }
    public Map<String, Object> score(long assignmentId, long problemId, long userId) {
        return one("SELECT s.*,a.tab_switch_limit FROM teach_student_score s JOIN teach_assignment a ON a.id=s.assignment_id WHERE s.assignment_id=? AND s.problem_id=? AND s.user_id=? FOR UPDATE", assignmentId, problemId, userId);
    }
    public long otherConfirmed(long assignment, long problem, long user, long excludingReport) {
        Long result = db.queryForObject("SELECT COUNT(*) FROM teach_plagiarism_report WHERE assignment_id=? AND problem_id=? AND (user_a_id=? OR user_b_id=?) AND audit_status=1 AND id<>?", Long.class, assignment, problem, user, user, excludingReport);
        return result == null ? 0 : result;
    }
    public void clearScore(long assignment, long problem, long user) {
        db.update("UPDATE teach_student_score SET prev_max_score=COALESCE(prev_max_score,max_score),max_score=0,red_flag_type=2 WHERE assignment_id=? AND problem_id=? AND user_id=?", assignment, problem, user);
    }
    public void restoreScore(long assignment, long problem, long user) {
        db.update("UPDATE teach_student_score s JOIN teach_assignment a ON a.id=s.assignment_id SET s.max_score=COALESCE(s.prev_max_score,s.max_score),s.prev_max_score=NULL,s.red_flag_type=IF(s.tab_switch_count>=a.tab_switch_limit,1,0) WHERE s.assignment_id=? AND s.problem_id=? AND s.user_id=?", assignment, problem, user);
    }
    public void audit(long id, int status, Long uid, Long at, String note) {
        db.update("UPDATE teach_plagiarism_report SET audit_status=?,audited_by=?,audited_at=?,audit_note=? WHERE id=?", status, uid, stamp(at), note, id);
    }
    public void moveReport(long id, long taskId) { db.update("UPDATE teach_plagiarism_report SET task_id=? WHERE id=?", taskId, id); }
    public void refreshReport(long id, long taskId, double struct, Double sem, String model, String diagnosis) {
        db.update("UPDATE teach_plagiarism_report SET task_id=?,stage1_algo_sim=?,stage2_ai_sim=?,ai_model_used=?,ai_diagnosis=? WHERE id=?", taskId, struct, sem, model, diagnosis, id);
        db.update("DELETE FROM teach_plagiarism_segment WHERE report_id=?", id);
    }
    public void deleteReport(long id) { db.update("DELETE FROM teach_plagiarism_segment WHERE report_id=?", id); db.update("DELETE FROM teach_plagiarism_report WHERE id=?", id); }
    private Map<String, Object> one(String sql, Object... params) {
        var rows = db.queryForList(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }
    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        if (key.getKey() == null) throw BusinessException.badGateway("查重记录未获得主键");
        return key.getKey().longValue();
    }
    private static Timestamp stamp(Long at) { return at == null ? null : new Timestamp(at); }
}
