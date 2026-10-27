package com.oj.common.api.dto.teaching;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 作业摘要（`TeachingAggregateFeignClient#getAssignment` 的响应体）。
 *
 * <p>判题/AI 两个服务都需要它来校验「是否在时间窗内」「允许哪些 AI 阶段」「这题算不算数」。
 * 刻意保持精简——只带判定所需字段，不带作业描述正文。</p>
 *
 * @author oj-cloud
 */
@Data
public class AssignmentBriefDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 作业 ID */
    private Long assignmentId;

    /** 作业展示号，如 #A1024 */
    private String displayNo;

    /** 作业标题 */
    private String title;

    /** 班级 ID */
    private Long classId;

    /** 创建教师 ID */
    private Long teacherId;

    /** 活动状态：0=草稿，1=未开始，2=进行中，3=已结束。 */
    private Integer state;

    /** 开始时间（毫秒时间戳） */
    private Long startAt;

    /** 截止时间（毫秒时间戳） */
    private Long deadlineAt;

    /** 是否允许迟交 */
    private Boolean allowLateSubmit;

    /** 包含的题目 ID 列表 */
    private List<Long> problemIds;

    /** 允许的 AI 阶段，如 [1,2]；空列表表示禁用 AI */
    private List<Integer> allowedAiStages;

    /** 单学生单题 AI 提示配额 */
    private Integer aiQuotaPerProblem;

    /** 是否处于考试模式（考试模式下 AI 一律禁止） */
    private Boolean examMode;

    /** 考试 ID；非考试作业为 null */
    private Long examId;
}
