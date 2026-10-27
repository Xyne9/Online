package com.oj.teaching.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** AI 权限判定所需的教学活动只读字段，列名以 teach_assignment 的实际 DDL 为准。 */
@Data
public class TeachAssignment {
    private Long id;
    private Long classId;
    private Long teacherId;
    /** 1 练习 / 2 作业 / 3 考试。 */
    private Integer modeType;
    /** 0 草稿 / 1 未开始 / 2 进行中 / 3 已结束。 */
    private Integer status;
    private String allowedAiStages;
    private Integer aiQuotaPerProb;
    private String problemIds;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
