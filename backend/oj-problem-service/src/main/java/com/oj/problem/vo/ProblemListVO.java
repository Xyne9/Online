package com.oj.problem.vo;

import com.oj.common.core.result.PageResult;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 题库列表响应（契约 02 §3.2 的 {@code data}）。
 *
 * <p><b>为什么继承 {@link PageResult}：</b>契约要求 {@code data} 是
 * {@code {list, total, page, pageSize, hasNext, stats}}——前五个字段与全站统一分页结构
 * 完全一致，只有 {@code stats} 是题库特有的。继承既有分页类既复用了
 * {@code normalizePageSize}（上限 100）与 {@code hasNext = page*pageSize < total} 的算法，
 * 也保证 {@code OJ.pager} 那套前端分页组件零改动就能吃。</p>
 *
 * <p>注意：{@code stats} 是<b>全量口径</b>（不受「我的状态」筛选影响），
 * 因为它渲染的是页面顶部的「本学期进度」四格，不是"当前筛选结果的统计"。</p>
 *
 * @author oj-cloud
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProblemListVO extends PageResult<ProblemListItemVO> {

    private static final long serialVersionUID = 1L;

    /** 题库四格统计：{total, solved, tried, acRate, weekNew} */
    private ProblemStatsVO stats;

    /**
     * 构造列表响应。
     *
     * @param list     当前页数据
     * @param total    总条数
     * @param page     页码
     * @param pageSize 每页条数
     * @param stats    全量四格统计
     */
    public ProblemListVO(List<ProblemListItemVO> list, long total, int page, int pageSize,
                         ProblemStatsVO stats) {
        super(list, total, page, pageSize);
        this.stats = stats;
    }
}
