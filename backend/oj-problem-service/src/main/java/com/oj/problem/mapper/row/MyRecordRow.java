package com.oj.problem.mapper.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 「我的记录」聚合行（来自 {@code judge_submission}）。
 *
 * <p>契约 §3.3 的 {@code myRecord} 需要 5 个值：是否提交过、最高分、最高分那条提交的 ID、
 * 尝试次数、最近一次的状态。它们全在 {@code judge_submission} 里，用一条
 * {@code GROUP BY problem_id} 聚合出来，避免 N+1 查询。</p>
 *
 * @author oj-cloud
 */
@Data
public class MyRecordRow {

    /** 题目 ID */
    private Long problemId;

    /** 提交次数（attempts） */
    private Integer attempts;

    /** 历史最高分（bestScore），OI 口径 0~100 */
    private Integer bestScore;

    /** 最高分对应的提交 ID（bestSubmissionId）；同分取最早那条，保证结果稳定 */
    private Long bestSubmissionId;

    /** 最近一次提交的判题状态 0~9（lastStatus） */
    private Integer lastStatus;

    /** 最近一次提交时间（DB 墙钟），用于「最近一次」排序与展示 */
    private LocalDateTime lastSubmittedAt;
}
