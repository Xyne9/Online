package com.oj.problem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 测试点表 {@code prob_test_case}（题库服务归属）。
 *
 * <p><b>⚠️ 最重要的字段语义（docs/day3 §7 第 1 条硬约束）：</b>
 * {@link #inputText} / {@link #outputText} 是<b>摘要</b>（mediumtext 里只存前若干 KB），
 * <b>不是</b>全文；判题比对必须读磁盘 {@code data/testcases/{problem_id}/{seq_no}.in|.out}。
 * 因此 {@code /inner/problem/judge-config} 只回 seqNo/kind/score，绝不带这两列；
 * 只有 {@code /inner/problem/test-cases}（服务间）才带正文，且仅供 AI 取题面/教师编辑回显。</p>
 *
 * <p><b>软删约定（docs/day3 §7 第 4 条）：</b>删测试点要写 {@code seq_no = -seq_no}，
 * 否则唯一键 {@code uk_prob_seq(problem_id, seq_no)} 会挡住「删了再建同号点」。
 * 所以本表里 {@code seq_no < 0} 的行是已软删行，正常查询要带 {@code deleted_at IS NULL}。</p>
 *
 * @author oj-cloud
 */
@Data
@TableName("prob_test_case")
public class ProbTestCase implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属题目 ID（prob_question.id） */
    private Long problemId;

    /** 序号，从 1 开始；决定判题执行顺序与展示顺序。软删时取负值 */
    private Integer seqNo;

    /** 类型四档：sample 样例 / normal 普通 / edge 边界 / stress 构造（契约 §3.9） */
    private String kind;

    /** 输入摘要（非全文，判题不可依赖它做比对） */
    private String inputText;

    /** 期望输出摘要（非全文） */
    private String outputText;

    /** 该测试点分值 0~100，5 的倍数；全部测试点之和必须 = 100 才允许发布 */
    private Integer score;

    /** 是否对用户公开（1 = 样例点可见） */
    private Boolean isPublic;

    /** 输入字节数（与磁盘 stat().size 比对用，不是 input_text 的长度） */
    private Integer inputSize;

    /** 输出字节数 */
    private Integer outputSize;

    /** 软删时间，NULL = 未删 */
    private LocalDateTime deletedAt;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
