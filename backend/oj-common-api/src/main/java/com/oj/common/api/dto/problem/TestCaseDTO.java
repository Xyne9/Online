package com.oj.common.api.dto.problem;

import lombok.Data;

import java.io.Serializable;

/**
 * 测试点（`ProblemTestCaseFeignClient#listByProblem` 的响应体元素）。
 *
 * <p><b>安全红线：</b>本 DTO 含隐藏用例的 input/output，只能走 {@code /inner/problem/**}
 * 这类服务间内部接口；面向学生的 HTTP 接口必须脱敏成「只有 seqNo/kind/score」的视图。</p>
 *
 * @author oj-cloud
 */
@Data
public class TestCaseDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 测试点 ID */
    private Long id;

    /** 所属题目 ID */
    private Long problemId;

    /** 序号，从 1 开始，决定判题执行顺序与展示顺序 */
    private Integer seqNo;

    /** 类型：sample（样例，对用户可见）/ hidden（隐藏） */
    private String kind;

    /** 输入数据 */
    private String input;

    /** 期望输出 */
    private String output;

    /** 该测试点分值（全部测试点之和必须 = 100，否则不允许发布） */
    private Integer score;

    /** 是否对用户公开 */
    private Boolean isPublic;

    /** 校验提示（可选，判题失败时的补充说明） */
    private String tip;
}
