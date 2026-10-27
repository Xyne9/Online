package com.oj.common.api.dto.ai;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 内容安全护栏判定结果（`AiInnerFeignClient#classifyGuardrail` 的响应体）。
 *
 * @author oj-cloud
 */
@Data
public class GuardrailVerdictDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 三分类判定：pass（放行）/ rewrite（要求重写）/ block（拦截） */
    private String verdict;

    /** 命中的规则码列表，如 ANSWER_LEAK / TOXICITY / EXTERNAL_LINK */
    private List<String> ruleCodes;

    /** 判定说明（中文，写入审计日志） */
    private String reason;

    /** 置信度（0~1） */
    private Double confidence;

    /** 需要重写时给出的改写建议（直接追加到下一轮 prompt） */
    private String rewriteHint;

    /** 是否命中「直接给答案」红线 */
    private Boolean answerLeak;

    /** 审查耗时（毫秒） */
    private Long costMs;

    /** 审查所用模型名 */
    private String modelName;
}
