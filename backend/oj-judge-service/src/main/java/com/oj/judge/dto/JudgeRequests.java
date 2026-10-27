package com.oj.judge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 判题相关请求体（集中在一个文件里，避免为 4 个短小的 record 各建一个类）。
 *
 * <p>契约 §4.1 / §4.6 的请求体逐字对应。校验注解只是第一道闸，
 * 真正的业务校验（语言白名单、代码长度、mode 与 customInput 的互斥）
 * 在 {@code JudgeServiceImpl} 里做 —— 因为错误码要求区分 400 的具体原因。</p>
 *
 * @author oj-cloud
 */
public final class JudgeRequests {

    private JudgeRequests() {
    }

    /**
     * {@code POST /api/judge/self-test} 请求体。
     *
     * <p>v1.0 冻结端点，字段不增不减。</p>
     */
    @Data
    public static class SelfTestRequest {

        /** 题目 ID（取时限/内存/语言倍率） */
        @NotNull(message = "不能为空")
        private Long problemId;

        /** 语言：cpp / java / python */
        @NotBlank(message = "不能为空")
        private String language;

        /** 源码 */
        @NotBlank(message = "不能为空")
        private String code;

        /** 自定义输入（v1.0 语义：直接跑这一段输入，不比对期望输出） */
        private String customInput;
    }

    /**
     * {@code POST /api/judge/submit} 请求体。
     */
    @Data
    public static class SubmitRequest {

        /** 题目 ID */
        @NotNull(message = "不能为空")
        private Long problemId;

        /** 所属教学活动 ID（自由练习传 0 或省略） */
        private Long assignmentId;

        /** 语言：cpp / java / python */
        @NotBlank(message = "不能为空")
        private String language;

        /** 源码（≤64 KB） */
        @NotBlank(message = "不能为空")
        private String code;
    }

    /**
     * {@code POST /api/judge/run-sample} 请求体。
     */
    @Data
    public static class RunSampleRequest {

        /** 题目 ID */
        @NotNull(message = "不能为空")
        private Long problemId;

        /** 语言 */
        @NotBlank(message = "不能为空")
        private String language;

        /** 源码（≤64 KB） */
        @NotBlank(message = "不能为空")
        private String code;

        /** {@code sample} = 跑公开样例；{@code custom} = 跑自定义输入 */
        @NotBlank(message = "不能为空")
        private String mode;

        /** 自定义输入（{@code mode=custom} 必填，≤64 KB） */
        @Size(max = 65536, message = "不能超过 64 KB")
        private String customInput;

        /** {@code mode=sample} 时只跑指定序号的公开样例 */
        private Integer sampleSeq;
    }

    /**
     * 内部接口 {@code POST /inner/judge/batch-best-codes} 的请求体兼容形态。
     *
     * <p>契约里该方法直接收 {@code List<Long>}，这里不额外定义 —— 见
     * {@code JudgeInnerController}。</p>
     */
    @Data
    public static class BatchBestCodesRequest {
    }
}
