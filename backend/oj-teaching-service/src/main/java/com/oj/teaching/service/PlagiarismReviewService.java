package com.oj.teaching.service;

import com.oj.common.core.exception.BusinessException;
import com.oj.common.web.context.UserContext;
import com.oj.teaching.dto.PlagiarismRequests;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.oj.teaching.service.PlagiarismAccess.number;

/** Lock the activity and score together, so report status and score restoration commit atomically. */
@Service
public class PlagiarismReviewService {
    private final PlagiarismRepository repository;
    private final PlagiarismAccess access;
    private final TransactionTemplate transactions;
    public PlagiarismReviewService(PlagiarismRepository repository, PlagiarismAccess access, PlatformTransactionManager manager) {
        this.repository = repository; this.access = access; this.transactions = new TransactionTemplate(manager);
    }
    public Map<String, Object> audit(PlagiarismRequests.Audit request) {
        if (request == null || request.reportId() == null || request.auditStatus() == null || (request.auditStatus() != 1 && request.auditStatus() != 2)
                || (request.note() != null && request.note().length() > 255)) throw BusinessException.badRequest("复核状态须为1/2且备注不能超过255字");
        var initial = access.report(request.reportId());
        return transactions.execute(status -> {
            access.assignment(number(initial, "assignment_id"), true);
            var report = repository.report(request.reportId());
            if (report == null) throw BusinessException.notFound("查重报告不存在");
            if (number(report, "audit_status") != 0) throw BusinessException.conflict("已复核，请先撤销后修改结论");
            if (request.auditStatus() == 1 && report.get("stage2_ai_sim") == null) throw BusinessException.conflict("尚未完成AI语义鉴定，不能确认抄袭");
            long assignment = number(report, "assignment_id"), problem = number(report, "problem_id");
            long now = System.currentTimeMillis(); List<Map<String, Object>> affected = new ArrayList<>();
            if (request.auditStatus() == 1) {
                List<Long> users = participants(report);
                // Validate and lock both score rows before changing either participant.
                for (long user : users) if (repository.score(assignment, problem, user) == null)
                    throw BusinessException.conflict("待清零的双方成绩不完整");
                for (long user : users) {
                    repository.clearScore(assignment, problem, user);
                    affected.add(Map.of("userId", user, "maxScore", 0));
                }
            }
            repository.audit(request.reportId(), request.auditStatus(), UserContext.userId(), now, request.note());
            var result = new LinkedHashMap<String, Object>(); result.put("reportId", request.reportId()); result.put("auditStatus", request.auditStatus());
            result.put("auditedAt", now); result.put("affectedScore", forUser(affected, number(report, "user_b_id")));
            result.put("affectedScores", affected); return result;
        });
    }
    public Map<String, Object> undo(long reportId) {
        var initial = access.report(reportId);
        return transactions.execute(status -> {
            access.assignment(number(initial, "assignment_id"), true);
            var report = repository.report(reportId);
            if (report == null) throw BusinessException.notFound("查重报告不存在");
            if (number(report, "audit_status") == 0) throw BusinessException.conflict("该报告尚未复核");
            List<Map<String, Object>> restored = restoreScoresBeforeReset(report);
            repository.audit(reportId, 0, null, null, null);
            var result = new LinkedHashMap<String, Object>(); result.put("reportId", reportId); result.put("auditStatus", 0);
            result.put("restoredScore", forUser(restored, number(report, "user_b_id"))); result.put("restoredScores", restored);
            return result;
        });
    }
    /** Caller already holds the activity row lock and transaction (also used for reruns). */
    Map<String, Object> restoreBeforeReset(Map<String, Object> report) {
        return forUser(restoreScoresBeforeReset(report), number(report, "user_b_id"));
    }
    private record Restoration(long user, int score, boolean restore) { }
    private List<Map<String, Object>> restoreScoresBeforeReset(Map<String, Object> report) {
        if (number(report, "audit_status") != 1) return List.of();
        long assignment = number(report, "assignment_id"), problem = number(report, "problem_id");
        List<Restoration> plan = new ArrayList<>();
        for (long user : participants(report)) {
            var score = repository.score(assignment, problem, user);
            if (score == null) throw BusinessException.conflict("被清零的双方成绩不完整，无法恢复");
            if (repository.otherConfirmed(assignment, problem, user, number(report, "id")) > 0) {
                plan.add(new Restoration(user, 0, false)); continue;
            }
            if (score.get("prev_max_score") == null) throw BusinessException.conflict("未保存原始成绩，无法撤销清零");
            plan.add(new Restoration(user, ((Number)score.get("prev_max_score")).intValue(), true));
        }
        List<Map<String, Object>> restored = new ArrayList<>();
        for (var item : plan) {
            if (item.restore) repository.restoreScore(assignment, problem, item.user);
            restored.add(Map.of("userId", item.user, "maxScore", item.score));
        }
        return restored;
    }
    private static List<Long> participants(Map<String, Object> report) {
        return List.of(number(report, "user_a_id"), number(report, "user_b_id")).stream().distinct().toList();
    }
    private static Map<String, Object> forUser(List<Map<String, Object>> scores, long user) {
        return scores.stream().filter(score -> ((Number)score.get("userId")).longValue() == user).findFirst().orElse(null);
    }
}
