package com.oj.common.api.dto.ai;

import lombok.Data;

import java.io.Serializable;

/**
 * 代码语义查重请求（`AiInnerFeignClient#checkCodeSemanticSimilarity` 的请求体）。
 *
 * <p>教务服务的查重任务是「两两对比」，为了减少模型调用次数，本 DTO 支持把
 * <b>目标代码</b> 与 <b>候选代码批量</b> 一起送进 AI，由模型一次性给出相似度矩阵的一行。</p>
 *
 * @author oj-cloud
 */
@Data
public class AiPlagiarismCheckDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 查重任务 ID（异步任务进度写 Redis oj:plagiarism:task:{taskId}） */
    private String taskId;

    /** 作业 ID */
    private Long assignmentId;

    /** 题目 ID */
    private Long problemId;

    /** 基准代码（被比对的这一份） */
    private String baseCode;

    /** 基准代码的提交 ID */
    private Long baseSubmissionId;

    /** 基准代码的作者 ID */
    private Long baseUserId;

    /** 编程语言：cpp / java / python */
    private String language;

    /** 候选代码列表（与基准代码逐一对比） */
    private java.util.List<CandidateDTO> candidates;

    /** 判定阈值（0~1），超过即视为疑似抄袭；缺省 0.85 */
    private Double threshold;

    /** 本次语义鉴定选择的模型通道，缺省使用 Tutor 默认通道。 */
    private String channelCode;

    /** 候选代码：一份待比对的学生提交 */
    @Data
    public static class CandidateDTO implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 候选提交 ID */
        private Long submissionId;

        /** 候选作者 ID */
        private Long userId;

        /** 候选作者显示名 */
        private String userName;

        /** 候选代码全文 */
        private String code;
    }
}
