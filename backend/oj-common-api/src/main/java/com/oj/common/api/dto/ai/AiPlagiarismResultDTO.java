package com.oj.common.api.dto.ai;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 代码语义查重结果（`AiInnerFeignClient#checkCodeSemanticSimilarity` 的响应体）。
 *
 * <p>与 {@link AiPlagiarismCheckDTO} 一一对应：请求里有 N 个候选，这里就有 N 个配对结果。</p>
 *
 * @author oj-cloud
 */
@Data
public class AiPlagiarismResultDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 查重任务 ID */
    private String taskId;

    /** 题目 ID */
    private Long problemId;

    /** 基准提交 ID */
    private Long baseSubmissionId;

    /** 配对结果列表 */
    private List<PairResultDTO> pairs;

    /** 最高相似度（0~1） */
    private Double maxSimilarity;

    /** 是否存在超阈值的疑似抄袭 */
    private Boolean hit;

    /** 调用模型名称（审计用） */
    private String modelName;

    /** 本次耗时（毫秒） */
    private Long costMs;

    /** 单个配对结果 */
    @Data
    public static class PairResultDTO implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 候选提交 ID */
        private Long submissionId;

        /** 候选作者 ID */
        private Long userId;

        /** 候选作者显示名 */
        private String userName;

        /** 语义相似度（0~1，保留 4 位小数） */
        private Double similarity;

        /** AST 结构相似度（0~1） */
        private Double astSimilarity;

        /** 是否超阈值 */
        private Boolean suspected;

        /** 判定理由（中文，展示在查重报告里） */
        private String reason;

        /** 相似片段（可选，最多 3 段） */
        private List<String> matchedSnippets;

        /** 相似片段的真实行号范围，用于双栏对齐。 */
        private List<SegmentDTO> segments;
    }

    @Data
    public static class SegmentDTO implements Serializable {
        private static final long serialVersionUID = 1L;
        private String segName;
        private Integer aFrom;
        private Integer aTo;
        private Integer bFrom;
        private Integer bTo;
    }
}
