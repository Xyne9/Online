package com.oj.teaching.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ActivityScore {
    private Long id;
    private Long assignmentId;
    private Long userId;
    private Long problemId;
    private Long bestSubmissionId;
    private Integer maxScore;
    private Integer minTimeUsed;
    private String draftLanguage;
    private String draftCode;
    private Integer submitCount;
    private Integer aiUsedCount;
    private Integer tabSwitchCount;
    private Integer redFlagType;
    private Integer prevMaxScore;
    private LocalDateTime updateTime;
}
