package com.oj.teaching.dto;

import java.util.List;

public final class PlagiarismRequests {
    private PlagiarismRequests() { }
    public record Check(Long assignmentId, Long problemId, String language, Double stage1SimThreshold,
                        String stage2ChannelCode) { }
    public record Task(Long assignmentId, List<Long> problemIds, String language, Double stage1SimThreshold,
                       String stage2ChannelCode, String scope) { }
    public record Audit(Long reportId, Integer auditStatus, String note) { }
    public record Rerun(Boolean keepAudit) { }
}
