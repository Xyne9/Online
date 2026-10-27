package com.oj.common.api.dto.teaching;

import lombok.Data;

import java.io.Serializable;

/**
 * 判题结果回写教务（`TeachingInnerFeignClient#syncSubmissionScore` 的请求体）。
 *
 * <p>判题服务判完必须回调教务服务同步分数，否则「我的作业」页永远显示未提交。
 * 该调用是<b>幂等</b>的：以 {@code submissionId} 为幂等键，重复同步只覆盖不累加。</p>
 *
 * @author oj-cloud
 */
@Data
public class ScoreSyncRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 提交 ID（幂等键） */
    private Long submissionId;

    /** 提交者用户 ID */
    private Long userId;

    /** 题目 ID */
    private Long problemId;

    /** 作业 ID；非作业提交时为 null（自测/自由练习） */
    private Long assignmentId;

    /** 考试 ID；非考试提交时为 null */
    private Long examId;

    /** 本次得分（OI 模式按测试点累计） */
    private Integer score;

    /** 判题状态码（基线 §5.1） */
    private Integer status;

    /** 状态中文名 */
    private String statusLabel;

    /** 是否 AC（100 分或 ACM 全过） */
    private Boolean accepted;

    /** 用时（毫秒） */
    private Long timeUsedMs;

    /** 内存占用（KB） */
    private Long memoryUsedKb;

    /** 判题完成时间（毫秒时间戳） */
    private Long judgedAt;
}
