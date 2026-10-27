package com.oj.common.api.dto.teaching;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * AI 调用权限校验结果（`TeachingInnerFeignClient#verifyAiPermission` 的响应体）。
 *
 * <p>AI 服务在每次对话前都必须问教务服务三件事：这题准不准用 AI、现在还准不准用（考试中禁止）、
 * 还剩多少次配额。把判定权放在教务服务，是因为只有它同时掌握作业时间窗、考试状态与班级归属。</p>
 *
 * @author oj-cloud
 */
@Data
public class AiPermissionVerifyDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否允许调用 AI */
    private Boolean allowed;

    /** 拒绝原因码：NOT_IN_ASSIGNMENT / EXAM_FORBIDDEN / QUOTA_EXHAUSTED / STAGE_NOT_ALLOWED */
    private String denyCode;

    /** 拒绝原因中文文案（可直接展示） */
    private String denyReason;

    /** 当前剩余可用次数；不限量时为 null */
    private Integer remainingQuota;

    /** 本作业允许的 AI 阶段，如 [1,2] */
    private List<Integer> allowedStages;

    /** 本次请求目标阶段是否在允许列表内 */
    private Boolean stageAllowed;

    /** 作业 ID（回显） */
    private Long assignmentId;

    /** 题目 ID（回显） */
    private Long problemId;

    /** 作业截止时间（毫秒时间戳）；无截止时为 null */
    private Long deadlineAt;

    /** 是否处于考试模式（考试中 AI 一律禁止，§1.4） */
    private Boolean examMode;
}
