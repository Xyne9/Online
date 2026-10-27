package com.oj.judge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 题目判题配置的只读视图（{@code prob_question} 的判题相关列）。
 *
 * <p><b>为什么不走 Feign 取 {@code ProblemJudgeConfigDTO}：</b>
 * 契约 §4.7 与 day3 §7 只要求「判题读磁盘文件、不读 input_text 摘要」，
 * 并没要求必须走 RPC。判题服务与题库服务连的是<b>同一个库</b>，
 * 判题又在热路径上（每次提交都要拿时限/内存/测试点摘要），
 * 多一跳 Feign 就多一个 504 风险点。因此本期用只读 SQL 直连，
 * 在 {@code JudgeServiceImpl} 类注释里明确记为「跨服务直连是本期妥协」。
 * 将来题库服务独立分库时，把 {@code ProbQuestionMapper#selectJudgeConfig} 换成
 * {@code ProblemInnerFeignClient#getJudgeConfig} 即可，上层无感。</p>
 *
 * <p>字段只取判题真正需要的 6 列，<b>不取</b> {@code content} / {@code official_solution}
 * 这类大字段（题面正文有几十 KB，每次判题都拉一遍毫无意义）。</p>
 *
 * @author oj-cloud
 */
@Data
@TableName("prob_question")
public class ProbQuestionJudgeConfig {

    /** 题目 ID */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 标题（提交记录列表要展示题目名） */
    private String title;

    /** 单测试点时间限制（毫秒，<b>原始值，不含语言倍率</b>） */
    private Integer timeLimit;

    /** 内存限制（MB，<b>原始值，不含语言倍率</b>） */
    private Integer memoryLimit;

    /** 测试点数量（物化列，与 prob_test_case 行数应一致） */
    private Integer testCaseCount;

    /** 测试点物化目录（容器内路径，如 {@code /data/testcases/1006/}） */
    private String testCaseDir;

    /** 题目状态：0 草稿 / 1 已发布 / 2 已归档 */
    private Integer state;
}
