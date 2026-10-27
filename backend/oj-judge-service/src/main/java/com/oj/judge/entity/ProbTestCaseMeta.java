package com.oj.judge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 测试点元信息（{@code prob_test_case} 的判题相关列，<b>不含正文</b>）。
 *
 * <p><b>刻意不映射 {@code input_text} / {@code output_text}：</b>
 * 那两列只存 ≤4 KB 摘要（day3 §2.1），判题比对必须读
 * {@code data/testcases/{problemId}/{seqNo}.in|.out}。映射进来只会诱使别人误用。</p>
 *
 * @author oj-cloud
 */
@Data
@TableName("prob_test_case")
public class ProbTestCaseMeta {

    /** 测试点主键（下发 {@code caseId}） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属题目 ID */
    private Long problemId;

    /** 序号（从 1 开始，决定判题执行顺序） */
    private Integer seqNo;

    /** 类型：sample / normal / edge / stress */
    private String kind;

    /** 该测试点分值（全部测试点之和 = 100 才允许发布） */
    private Integer score;

    /** 是否对用户公开（1 = 样例，可展示输入输出） */
    private Integer isPublic;

    /** 输入字节数（完整性校验用，与磁盘 stat 比，不与 LENGTH(input_text) 比） */
    private Integer inputSize;

    /** 输出字节数 */
    private Integer outputSize;
}
