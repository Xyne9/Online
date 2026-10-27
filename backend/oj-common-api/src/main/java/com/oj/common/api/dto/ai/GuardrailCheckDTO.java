package com.oj.common.api.dto.ai;

import lombok.Data;

import java.io.Serializable;

/**
 * 内容安全护栏检查请求（`AiInnerFeignClient#classifyGuardrail` 的请求体）。
 *
 * <p>教学场景对 AI 输出有硬约束：不能直接给完整答案、不能包含攻击性内容、不能夹带外链。
 * 每一轮模型输出都要过这一层，判定为 rewrite 时会被要求重写，block 时直接拦下。</p>
 *
 * @author oj-cloud
 */
@Data
public class GuardrailCheckDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话 ID */
    private String sessionId;

    /** 用户 ID */
    private Long userId;

    /** 用户提问原文 */
    private String userInput;

    /** 模型生成内容（待审查的正文） */
    private String modelOutput;

    /** 当前启发阶段：1=思路启发，2=具体指路，3=代码审查 */
    private Integer stage;

    /** 题目 ID（上下文判定用） */
    private Long problemId;

    /** 是否考试模式（考试模式下任何答案泄漏都直接 block） */
    private Boolean examMode;

    /** 审查侧重：answer_leak / toxicity / external_link / all；缺省 all */
    private String focus;
}
