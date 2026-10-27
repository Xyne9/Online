package com.oj.problem.dto;

import lombok.Data;

/**
 * 题库列表的原始请求参数（契约 §3.2）。
 *
 * <p>字段名与查询串一一对应，Spring 会自动按名绑定；<b>这里不做任何校验</b>——
 * 校验与「学生视角强制 state=1」等口径统一在 {@code ProblemQueryServiceImpl}
 * 里做，避免同一个规则在 Controller 和 Service 各写一份、改一处漏一处。</p>
 *
 * @author oj-cloud
 */
@Data
public class ProblemQuery {

    /** 页码，默认 1 */
    private Integer page;

    /** 每页条数，默认 20，上限 100 */
    private Integer pageSize;

    /** 我的状态筛选：all（默认）/ todo / done / fail，仅学生视角生效 */
    private String status;

    /** 题目状态 0/1/2；仅教师与管理员生效，学生传了也按 1 处理 */
    private Integer state;

    /** 难度多选，逗号分隔如 "1,2,3"；单值也接受 */
    private String lvl;

    /** 标题/标签模糊匹配，≤50 字符 */
    private String kw;

    /** 单标签精确匹配 */
    private String tag;

    /** 排序字段白名单：id/lvl/rate/score/updatedAt，默认 id */
    private String sortBy;

    /** 排序方向：asc/desc，默认 desc */
    private String sortOrder;
}
