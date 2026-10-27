package com.oj.problem.vo;

import lombok.Data;

/**
 * 题库四格统计（契约 02 §3.2 的 {@code data.stats}）。
 *
 * <p>对应 s-problems.html:185-195 的「本学期进度」：
 * 「<b>solved</b>/total 题已通过 · 尝试过 <b>tried</b> · 我的 AC 率 <b>acRate</b>% · 本周新增 <b>weekNew</b>」。</p>
 *
 * <p><b>口径：</b>solved / tried 按<b>当前登录学生</b>的真实提交算（未登录或教师视角恒为 0，
 * 因为"我的进度"对教师没有意义）；total / weekNew 是题库维度。
 * 全部为全量口径，不受列表的「我的状态」筛选影响。</p>
 *
 * @author oj-cloud
 */
@Data
public class ProblemStatsVO {

    /** 可见题目总数 */
    private Long total;

    /** 我已通过的题数（存在 status=AC 的提交） */
    private Long solved;

    /** 我尝试过的题数（至少提交过一次） */
    private Long tried;

    /** 我的 AC 率 0~100，保留一位小数；tried=0 时为 0.0（不返回 NaN） */
    private Double acRate;

    /** 本周（最近 7 天）新增题目数 */
    private Long weekNew;
}
