package com.oj.ai.service;

import com.oj.common.api.dto.judge.AiSubmissionStateDTO;
import com.oj.common.api.dto.problem.ProblemAiRagDTO;
import com.oj.common.api.dto.teaching.AiPermissionVerifyDTO;
import com.oj.common.api.feign.*;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.result.Result;
import feign.FeignException;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Supplier;

@Service
public class AiAccessService {
    private final TeachingInnerFeignClient teaching;
    private final ProblemInnerFeignClient problem;
    private final JudgeInnerFeignClient judge;
    public record Context(Long userId, Long assignmentId, Long problemId, ProblemAiRagDTO problem,
                          AiPermissionVerifyDTO permission, AiSubmissionStateDTO state) {
        public String sessionId() { return userId + "_" + assignmentId + "_" + problemId; }
        public boolean enabled() {
            return Boolean.TRUE.equals(problem.getAiEnabled()) && !Boolean.TRUE.equals(permission.getExamMode())
                    && (Boolean.TRUE.equals(permission.getAllowed()) || "QUOTA_EXHAUSTED".equals(permission.getDenyCode())
                    || "STAGE_NOT_ALLOWED".equals(permission.getDenyCode()));
        }
        public int maxQuota() { return permission.getMaxQuota() == null ? -1 : permission.getMaxQuota(); }
        public String lockReason(int stage) {
            if (!enabled()) return permission.getDenyReason() == null ? "本题已禁用 AI" : permission.getDenyReason();
            if (permission.getAllowedStages() == null || !permission.getAllowedStages().contains(stage)
                    || (problem.getAiStages() != null && !problem.getAiStages().contains(stage))) return "当前教学活动或题目已禁用此阶段";
            if (stage == 3 && !state.hasFailed()) return "需本题存在非 AC 的评测结果后解锁";
            if (stage == 4 && !state.hasAc()) return "需本题获得满分 AC 后解锁";
            return null;
        }
    }

    public AiAccessService(TeachingInnerFeignClient teaching, ProblemInnerFeignClient problem, JudgeInnerFeignClient judge) {
        this.teaching = teaching; this.problem = problem; this.judge = judge;
    }

    public Context load(Long uid, Long aid, Long pid, int stage) {
        if (aid == null || aid < 0 || pid == null || pid < 1) throw BusinessException.badRequest("题目或教学活动参数无效");
        var permission = call(() -> teaching.verifyAiPermission(uid, aid, pid, stage));
        if (permission == null) throw BusinessException.badGateway("教务权限数据为空");
        if (Set.of("NOT_IN_ASSIGNMENT", "FORBIDDEN", "USER_DISABLED").contains(String.valueOf(permission.getDenyCode())))
            throw BusinessException.forbidden(permission.getDenyReason());
        var rag = call(() -> problem.getProblemRagForAi(pid));
        if (rag == null) throw BusinessException.notFound("题目不存在");
        var state = call(() -> judge.getAiSubmissionState(uid, pid, aid));
        return new Context(uid, aid, pid, rag, permission, state);
    }

    public void requireStage(Context c, int stage) {
        String reason = c.lockReason(stage);
        if (reason != null) throw BusinessException.forbidden(reason);
    }

    public static <T> T call(Supplier<Result<T>> supplier) {
        try {
            var result = supplier.get();
            if (result == null) throw BusinessException.badGateway("内部服务响应为空");
            if (!result.isSuccess()) throw new BusinessException(result.getCode(), result.getMessage());
            return result.getData();
        } catch (FeignException e) {
            if (e.status() == 404) throw BusinessException.notFound("题目或教学活动不存在");
            if (e.status() == 403) throw BusinessException.forbidden("无权访问当前教学活动");
            if (e.status() == 400) throw BusinessException.badRequest("教学活动参数无效");
            throw BusinessException.badGateway("依赖服务暂不可用");
        }
    }
}
