package com.oj.common.api.dto.problem;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 题目的轻量视图（`ProblemInnerFeignClient#recommendProblemByWeakTag` 的响应体）。
 *
 * <p>AI 推荐下一题时只需要「能渲染一张卡片」的最小字段集，避免把整份题面搬运过 Feign。</p>
 *
 * @author oj-cloud
 */
@Data
public class ProblemSimpleDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 题目 ID */
    private Long problemId;

    /** 展示号，如 #1024 */
    private String displayNo;

    /** 标题 */
    private String title;

    /** 难度 1~5 */
    private Integer difficulty;

    /** 难度中文标签（入门/简单/中等/困难/挑战） */
    private String difficultyLabel;

    /** 标签，逗号分隔 */
    private String tags;

    /** 通过率（0~100，保留一位小数） */
    private Double acRate;

    /** 我的作答状态：todo / done / fail */
    private String myStatus;

    /** 推荐理由（AI 依据弱项标签给出的可展示文案） */
    private String recommendReason;

    /** 关联的弱项标签（触发本次推荐的标签） */
    private String matchedTag;

    /** 先修/相关知识点的题号列表（可选，用于学习路径展示） */
    private List<Long> relatedProblemIds;
}
