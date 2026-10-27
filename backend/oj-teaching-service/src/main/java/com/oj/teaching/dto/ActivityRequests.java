package com.oj.teaching.dto;

import lombok.Data;
import java.util.List;

/** 仅此服务使用的请求字段，公共 Feign DTO 仍归 oj-common-api。 */
public final class ActivityRequests {
    private ActivityRequests() { }
    @Data
    public static class AssignmentWrite {
        private String title;
        private Integer modeType;
        private Long classId;
        private List<Long> problemIds;
        private Long startAt;
        private Long dueAt;
        private List<Integer> allowedAiStages;
        private Integer aiQuotaPerProb;
        private Integer tabSwitchLimit;
        private Boolean publishNow;
    }
    public record StateChange(String action) { }
    public record Submit(String remark) { }
    public record Answer(Long problemId, String language, String code, Long clientSavedAt) { }
    public record HandIn(List<Long> problemIds, Boolean confirm) { }
    public record TabSwitch(Long assignmentId, Long problemId, Double awaySeconds) { }
    public record Extend(Integer minutes, String reason) { }
    public record Finish(Boolean autoSubmitUnsubmitted) { }
}
