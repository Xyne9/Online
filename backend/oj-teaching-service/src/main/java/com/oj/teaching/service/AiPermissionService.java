package com.oj.teaching.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.common.api.dto.teaching.AiPermissionVerifyDTO;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.redis.RedisKeys;
import com.oj.teaching.constant.AuthConstants;
import com.oj.teaching.entity.SysUser;
import com.oj.teaching.entity.TeachAssignment;
import com.oj.teaching.mapper.AiPermissionMapper;
import com.oj.teaching.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** AI 调用前的权威活动权限。仅查询配额，扣减统一留给 AI 流结束时的原子操作。 */
@Service
public class AiPermissionService {
    private static final List<Integer> PRACTICE_STAGES = List.of(1, 2, 3, 4);
    private static final List<Integer> ASSIGNMENT_STAGES = List.of(1, 3);

    private final SysUserMapper userMapper;
    private final AiPermissionMapper permissionMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public AiPermissionService(SysUserMapper userMapper, AiPermissionMapper permissionMapper,
                               StringRedisTemplate redis, ObjectMapper objectMapper) {
        this(userMapper, permissionMapper, redis, objectMapper, Clock.system(AuthConstants.DB_ZONE));
    }

    AiPermissionService(SysUserMapper userMapper, AiPermissionMapper permissionMapper,
                        StringRedisTemplate redis, ObjectMapper objectMapper, Clock clock) {
        this.userMapper = userMapper;
        this.permissionMapper = permissionMapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public AiPermissionVerifyDTO verify(Long userId, Long assignmentId, Long problemId, Integer targetStage) {
        if (userId == null || userId <= 0 || problemId == null || problemId <= 0
                || assignmentId != null && assignmentId < 0
                || targetStage == null || targetStage < 1 || targetStage > 4) {
            throw BusinessException.badRequest("AI 权限参数不合法");
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null || !user.isEnabled()
                || !("STUDENT".equals(user.getRole()) || "TEACHER".equals(user.getRole()))) {
            throw BusinessException.forbidden("仅正常学生或教师账号可使用作答助教");
        }
        boolean teacher = "TEACHER".equals(user.getRole());
        long activityId = assignmentId == null ? 0 : assignmentId;
        AiPermissionVerifyDTO result = new AiPermissionVerifyDTO();
        result.setAssignmentId(activityId);
        result.setProblemId(problemId);
        result.setModeType(1);
        result.setMaxQuota(-1);
        result.setRemainingQuota(null);
        result.setAllowedStages(PRACTICE_STAGES);
        result.setStageAllowed(true);
        result.setExamMode(false);
        result.setAllowed(true);

        LocalDateTime now = LocalDateTime.now(clock);
        TeachAssignment policy = activityId == 0 ? null : permissionMapper.selectPolicy(activityId);
        if (activityId != 0 && policy == null) {
            throw BusinessException.notFound("教学活动不存在");
        }
        if (policy != null) {
            result.setModeType(policy.getModeType());
            result.setDeadlineAt(toEpochMs(policy.getEndTime()));
            boolean ownsActivity = teacher ? Objects.equals(userId, policy.getTeacherId())
                    : Objects.equals(user.getClassId(), policy.getClassId());
            if (!ownsActivity
                    || !readIds(policy.getProblemIds()).contains(problemId)) {
                return deny(result, "NOT_IN_ASSIGNMENT", "该题目不属于你有权访问的教学活动");
            }
        }

        TeachAssignment activeExam = teacher || user.getClassId() == null ? null
                : permissionMapper.selectActiveExam(user.getClassId(), problemId, now);
        if (activeExam != null || policy != null && Integer.valueOf(3).equals(policy.getModeType())) {
            result.setModeType(3);
            result.setExamMode(true);
            result.setMaxQuota(0);
            result.setRemainingQuota(0);
            result.setAllowedStages(List.of());
            result.setStageAllowed(false);
            if (activeExam != null) result.setDeadlineAt(toEpochMs(activeExam.getEndTime()));
            return deny(result, "EXAM_FORBIDDEN", "考试模式禁止使用 AI 助教");
        }

        if (policy == null) return result;
        if (!Integer.valueOf(1).equals(policy.getModeType()) && !Integer.valueOf(2).equals(policy.getModeType())) {
            return deny(result, "STAGE_NOT_ALLOWED", "教学活动模式无效");
        }
        List<Integer> configured = readStages(policy.getAllowedAiStages());
        List<Integer> modeStages = Integer.valueOf(2).equals(policy.getModeType())
                ? ASSIGNMENT_STAGES : PRACTICE_STAGES;
        result.setAllowedStages(modeStages.stream().filter(configured::contains).toList());
        result.setStageAllowed(result.getAllowedStages().contains(targetStage));

        if (Integer.valueOf(2).equals(policy.getModeType())) {
            int limit = policy.getAiQuotaPerProb() == null || policy.getAiQuotaPerProb() < 0
                    ? AuthConstants.AI_QUOTA_DEFAULT : policy.getAiQuotaPerProb();
            result.setMaxQuota(limit);
            String rawUsed = redis.opsForValue().get(RedisKeys.aiQuota(activityId, problemId, userId));
            int used;
            try {
                used = rawUsed == null ? 0 : Math.max(0, Integer.parseInt(rawUsed));
            } catch (NumberFormatException e) {
                throw BusinessException.internalError("AI 配额计数异常");
            }
            result.setRemainingQuota(Math.max(0, limit - used));
        }
        if (!Integer.valueOf(2).equals(policy.getStatus()) || policy.getStartTime() == null
                || policy.getEndTime() == null || now.isBefore(policy.getStartTime())
                || !now.isBefore(policy.getEndTime())) {
            return deny(result, "ACTIVITY_CLOSED", "教学活动不在开放时间内");
        }
        if (!Boolean.TRUE.equals(result.getStageAllowed())) {
            return deny(result, "STAGE_NOT_ALLOWED", "当前活动未开放此 AI 阶段");
        }
        if (result.getRemainingQuota() != null && result.getRemainingQuota() == 0) {
            return deny(result, "QUOTA_EXHAUSTED", "本题 AI 提示次数已用完");
        }
        return result;
    }

    private AiPermissionVerifyDTO deny(AiPermissionVerifyDTO result, String code, String reason) {
        result.setAllowed(false);
        result.setDenyCode(code);
        result.setDenyReason(reason);
        return result;
    }

    private List<Long> readIds(String raw) {
        try {
            List<Long> values = objectMapper.readValue(raw, new TypeReference<List<Long>>() { });
            return values == null ? List.of() : values;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Integer> readStages(String raw) {
        try {
            List<Integer> values = objectMapper.readValue(raw, new TypeReference<List<Integer>>() { });
            return values == null ? List.of() : values;
        } catch (Exception e) {
            return List.of();
        }
    }

    private Long toEpochMs(LocalDateTime value) {
        return value == null ? null : value.atZone(AuthConstants.DB_ZONE).toInstant().toEpochMilli();
    }
}
