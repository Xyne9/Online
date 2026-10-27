package com.oj.teaching.service;

import com.oj.common.core.exception.BusinessException;
import com.oj.common.web.context.UserContext;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class PlagiarismAccess {
    private final PlagiarismRepository repository;
    public PlagiarismAccess(PlagiarismRepository repository) { this.repository = repository; }
    public Map<String, Object> assignment(long id, boolean lock) {
        long uid = UserContext.userId(); UserContext.requireTeacherOrAbove("查重");
        var assignment = repository.assignment(id, lock);
        if (assignment == null) throw BusinessException.notFound("教学活动不存在");
        if (!UserContext.isAdmin() && (number(assignment, "teacher_id") != uid || number(assignment, "class_teacher_id") != uid))
            throw BusinessException.forbidden("仅负责教师可查看或复核该班查重报告");
        return assignment;
    }
    public Map<String, Object> task(long id) {
        UserContext.userId(); UserContext.requireTeacherOrAbove("查重");
        var task = repository.task(id);
        if (task == null) throw BusinessException.notFound("查重任务不存在");
        assignment(number(task, "assignment_id"), false); return task;
    }
    public Map<String, Object> report(long id) {
        UserContext.userId(); UserContext.requireTeacherOrAbove("查重");
        var report = repository.report(id);
        if (report == null) throw BusinessException.notFound("查重报告不存在");
        assignment(number(report, "assignment_id"), false); return report;
    }
    public static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    public static Double decimal(Map<String, Object> row, String key) { return row.get(key) == null ? null : ((Number)row.get(key)).doubleValue(); }
}
