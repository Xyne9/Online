package com.oj.ai.service;

import com.oj.ai.model.DynamicAiModelFactory;
import com.oj.common.core.redis.RedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AiWorkspaceService {
    private final AiAccessService access;
    private final AiQuotaService quota;
    private final AiHistoryService history;
    private final DynamicAiModelFactory models;
    private final StringRedisTemplate redis;

    public AiWorkspaceService(AiAccessService access, AiQuotaService quota, AiHistoryService history,
                              DynamicAiModelFactory models, StringRedisTemplate redis) {
        this.access = access; this.quota = quota; this.history = history; this.models = models; this.redis = redis;
    }

    public Map<String, Object> workspace(Long uid, Long aid, Long pid) {
        var c = access.load(uid, aid, pid, 1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("aiEnabled", c.enabled()); out.put("modeDesc", mode(c));
        out.put("remainingQuota", quota.remaining(c)); out.put("maxQuota", c.maxQuota());
        out.put("availableChannels", models.publicChannels()); out.put("stageCapsules", capsules(c));
        List<Map<String, Object>> chat = new ArrayList<>();
        for (var r : history.recent(uid, aid, pid)) {
            chat.add(Map.of("role", "user", "stage", r.getStageType(), "content", r.getUserMessage()));
            chat.add(Map.of("role", "assistant", "stage", r.getStageType(), "content", r.getAiResponse(),
                    "hintLevel", r.getHintLevel(), "model", r.getActualModel(), "recordId", r.getId()));
        }
        out.put("chatHistory", chat);
        return out;
    }

    public Map<String, Object> quota(Long uid, Long aid, Long pid) {
        var c = access.load(uid, aid, pid, 1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("aiEnabled", c.enabled()); out.put("remaining", quota.remaining(c)); out.put("max", c.maxQuota());
        out.put("unlimited", c.maxQuota() < 0); out.put("resetAt", c.permission().getDeadlineAt());
        out.put("stages", capsules(c));
        var local = models.local("TUTOR");
        out.put("channel", Map.of("channelCode", local.channelCode(), "channelName", local.channelName(), "healthStatus", local.healthStatus()));
        return out;
    }

    private List<Map<String, Object>> capsules(AiAccessService.Context c) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int stage = 1; stage <= 4; stage++) {
            String count = redis.opsForValue().get(RedisKeys.aiLevel(c.userId(), c.problemId(), stage));
            int next = Math.min(3, (count == null ? 0 : Integer.parseInt(count)) + 1);
            String reason = c.lockReason(stage);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stage", stage); item.put("stageName", AiHistoryService.STAGES[stage]); item.put("title", AiHistoryService.STAGES[stage]);
            item.put("unlocked", reason == null); item.put("nextHintLevel", next); item.put("hintLevel", next);
            item.put("usedCount", history.stageUsed(c.userId(), c.assignmentId(), c.problemId(), stage)); item.put("lockReason", reason);
            result.add(item);
        }
        return result;
    }

    private String mode(AiAccessService.Context c) {
        if (Boolean.TRUE.equals(c.permission().getExamMode())) return "考试模式：AI 已禁用";
        return Integer.valueOf(2).equals(c.permission().getModeType()) ? "课后作业模式（仅开放阶段①与阶段③）" : "练习模式（不限次数）";
    }
}
