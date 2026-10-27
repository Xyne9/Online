package com.oj.common.api.dto.problem;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * AI 服务取题面 + AI 策略（`ProblemInnerFeignClient#getProblemRagForAi` 的响应体）。
 *
 * <p>用于 RAG 检索增强：AI 需要题干、知识点、官方题解做上下文，但<b>绝不能</b>看到隐藏测试点，
 * 因此本 DTO 只带公开样例（{@link #publicSamples}），不含 hidden 用例的输入输出。</p>
 *
 * @author oj-cloud
 */
@Data
public class ProblemAiRagDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 题目 ID */
    private Long problemId;

    /** 展示号 */
    private String displayNo;

    /** 标题 */
    private String title;

    /** 难度 1~5（基线 §5.2 五档） */
    private Integer difficulty;

    /** 知识点 ID */
    private Long knowledgePointId;

    /** 知识点名称 */
    private String knowledgePoint;

    /** 标签，逗号分隔 */
    private String tags;

    /** 题面（Markdown） */
    private String statement;

    /** 输入格式说明 */
    private String inputSpec;

    /** 输出格式说明 */
    private String outputSpec;

    /** 官方题解（仅教师/管理员可见；AI 侧可作为参考答案注入上下文） */
    private String officialSolution;

    /** 公开样例（用户可见的部分） */
    private List<SampleDTO> publicSamples;

    /** 该题是否允许 AI 提示 */
    private Boolean aiEnabled;

    /** AI 可用阶段，如 [1,2]（1=思路启发，2=具体指路） */
    private List<Integer> aiStages;

    /** 每个学生的 AI 提示配额上限（作业模式生效） */
    private Integer aiQuotaLimit;

    /** 公开样例：输入 → 输出 */
    @Data
    public static class SampleDTO implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 样例输入 */
        private String input;

        /** 样例输出 */
        private String output;
    }
}
