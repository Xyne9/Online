package com.oj.common.api.dto.judge;

import lombok.Data;

import java.io.Serializable;

/**
 * 学生某题的最佳代码（`JudgeInnerFeignClient#getBatchBestCodes` 的响应体元素）。
 *
 * <p>查重服务需要批量拉取「每个学生每道题的最高分提交代码」，用于 AST / 语义相似度对比。
 * 一次批量拉取而非 N 次单查，是因为一个班 50 人 × 5 题的查重任务会放大成 250 次 RPC。</p>
 *
 * @author oj-cloud
 */
@Data
public class SubmissionCodeDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 提交 ID */
    private Long submissionId;

    /** 提交者用户 ID */
    private Long userId;

    /** 提交者显示名（脱敏后的姓名，查重报告展示用） */
    private String userName;

    /** 题目 ID */
    private Long problemId;

    /** 编程语言：cpp / java / python */
    private String language;

    /** 得分（该题最高分） */
    private Integer score;

    /** 判题状态码（4=WA，8=AC 等，基线 §5.1） */
    private Integer status;

    /** 源代码全文（查重对比的核心数据） */
    private String code;

    /** 代码字符数（用于预筛：过短代码跳过对比） */
    private Integer codeLength;

    /** 提交时间（毫秒时间戳） */
    private Long submittedAt;

    /** AST 指纹（可选，题库侧预计算后缓存，查重直接比对指纹可大幅提速） */
    private String astHash;
}
