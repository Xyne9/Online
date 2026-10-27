package com.oj.problem.vo;

import lombok.Data;

import java.util.List;

/**
 * 题库列表的单行视图（契约 02 §3.2 的 {@code data.list[]}）。
 *
 * <p><b>字段名逐字对齐契约</b>，不做任何"优化重命名"——前端适配器
 * {@code adapter.adaptProblem} 是按这些名字取值的，改一个名就白屏。</p>
 *
 * <p><b>关于 {@link #myStatus} 的类型：</b>契约 §3.2 的示例写的是字符串
 * {@code "myStatus":"fail"}，但 04 §2.1(1)「判题状态字典」与前端适配器
 * {@code statusCodeOf({status: p.myStatus})}（用 int 直接查表 0~9）都以
 * <b>int 为真值</b>。示例里的 "fail" 是「我的状态」这个筛选档位的口语写法，
 * 不是判题状态本身。此处按字典实现为 int（未提交 = null），
 * 并额外下发 {@link #myStatusCode}（"ac"/"wa"/…，可直接当 CSS class）
 * 与 {@link #myStatusLabel}，让前端无论按 int 还是按 code 取都不会落空。</p>
 *
 * @author oj-cloud
 */
@Data
public class ProblemListItemVO {

    /** 题目 ID（主键，展示时由前端或 displayNo 拼 #） */
    private Long id;

    /** 展示号，如 "#1024" */
    private String displayNo;

    /** 标题 */
    private String title;

    /** 难度 1~5（整数，前端用它画 5 格刻度） */
    private Integer difficulty;

    /** 难度中文标签：入门/简单/中等/较难/困难 */
    private String difficultyLabel;

    /** 标签数组（DB 里是 JSON，这里已解析） */
    private List<String> tags;

    /** 全站 AC 率 0~100，保留一位小数 */
    private Double acRate;

    /** 尝试次数（取 prob_question.submit_count，见 Service 注释的统计口径说明） */
    private Integer triedCount;

    /** 我的判题状态 0~9；null = 从未提交（契约 04 §2.1(1) 明确"未提交不入枚举"） */
    private Integer myStatus;

    /** 我的状态 CSS class：ac/wa/tle/mle/re/ce/pac/pending；null = 未提交 */
    private String myStatusCode;

    /** 我的状态中文标签：AC/WA/…；null = 未提交 */
    private String myStatusLabel;

    /**
     * 我的状态筛选档位：{@code todo}（未做）/ {@code done}（已通过）/ {@code fail}（做过但没通过）。
     *
     * <p>契约 02 §3.2 的响应示例里 {@code myStatus} 写的是 {@code "fail"}，而 04 §2.1(1)
     * 的判题状态字典与前端适配器 {@code statusCodeOf({status})} 都要求 int 真值。
     * 两者不冲突：{@code "fail"} 是<b>筛选档位</b>，{@code 5} 是<b>判题状态</b>。
     * 本类同时下发：{@code myStatus}（int 真值，对齐字典）、{@code myStatusCode}（CSS class）、
     * {@code myStatusLabel}（展示文案）、{@code myStatusFilter}（筛选档位，对齐 §3.2 示例）。
     * 前端按哪个取都不会落空。</p>
     */
    private String myStatusFilter;

    /** 我的历史最高分 0~100；null = 从未提交 */
    private Integer myScore;

    /** 题目状态 0 草稿 / 1 已发布 / 2 已归档 */
    private Integer state;

    /** 时间限制（毫秒） */
    private Integer timeLimitMs;

    /** 内存限制（MB） */
    private Integer memoryLimitMb;

    /** 测试点个数 */
    private Integer testCaseCount;

    /** 公开（样例）测试点个数 */
    private Integer publicCaseCount;

    /** 是否首页推荐题（本期由「提交数最高的已发布题」派生，见 Service） */
    private Boolean isFeatured;

    /** 最后更新时间，epoch 毫秒 */
    private Long updatedAt;
}
