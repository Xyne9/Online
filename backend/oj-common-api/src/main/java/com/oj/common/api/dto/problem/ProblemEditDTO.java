package com.oj.common.api.dto.problem;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 题目编辑态视图（`ProblemTestCaseFeignClient#getForEdit` 的响应体）。
 *
 * <p>字段严格对齐设计文档 §4.1 「{@code GET /api/problem/questions/{id}/edit}」的 {@code data} 结构，
 * AI 服务用它取「题面 + AI 策略」，题库服务自身也用它回显编辑表单。</p>
 *
 * @author oj-cloud
 */
@Data
public class ProblemEditDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 题目 ID */
    private Long id;

    /** 展示号 */
    private String displayNo;

    /** 标题 */
    private String title;

    /** 难度 1~5 */
    private Integer difficulty;

    /** 知识点名称 */
    private String knowledgePoint;

    /** 知识点 ID */
    private Long knowledgePointId;

    /** 标签，逗号分隔 */
    private String tags;

    /** 时间限制（毫秒） */
    private Integer timeLimitMs;

    /** 内存限制（MB） */
    private Integer memoryLimitMb;

    /** 是否启用 AI 提示 */
    private Boolean aiEnabled;

    /** 单学生 AI 提示配额 */
    private Integer aiQuotaLimit;

    /** 允许的 AI 阶段，如 [1,2] */
    private List<Integer> aiStages;

    /** 题面（Markdown） */
    private String statement;

    /** 官方题解 */
    private String officialSolution;

    /** 状态：0=草稿，1=已发布，2=已归档 */
    private Integer state;

    /** 乐观锁版本号（编辑提交时必须带回，冲突返 409） */
    private Integer version;

    /** 最后保存时间（毫秒时间戳） */
    private Long savedAt;

    /** 测试点列表 */
    private List<TestCaseDTO> testCases;

    /** 测试点总分（用于校验「发布时总分必须 = 100」） */
    private Integer totalScore;

    /** 是否满足发布条件 */
    private Boolean canPublish;

    /** 不满足发布条件时的提示文案 */
    private String publishHint;
}
