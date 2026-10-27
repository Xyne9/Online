package com.oj.problem.mapper.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 列表查询的行结果（不是实体，因为带 JOIN 出来的聚合列）。
 *
 * <p>字段名与 {@code ProbQuestionMapper.xml} 里的别名<b>逐字对应</b>：
 * MyBatis 的 {@code map-underscore-to-camel-case} 只对下划线列名生效，
 * 这里全部用驼峰别名（{@code acRate} / {@code triedCount} / {@code myBestScore} …），
 * 一一映射到同名字段，不做二次转换。</p>
 *
 * @author oj-cloud
 */
@Data
public class ProblemListRow {

    /** 题目 ID */
    private Long id;

    /** 标题 */
    private String title;

    /** 难度 1~5 */
    private Integer difficulty;

    /** 标签 JSON 原文，Service 再解析成数组 */
    private String tags;

    /** 推荐下一题时使用的主知识点。 */
    private Long knowledgePointId;

    /** 推荐候选的软删标记。 */
    private LocalDateTime deletedAt;

    /** 题目状态 0/1/2 */
    private Integer state;

    /** 时间限制（毫秒） */
    private Integer timeLimit;

    /** 内存限制（MB） */
    private Integer memoryLimit;

    /** 测试点个数（prob_question 的声明值） */
    private Integer testCaseCount;

    /** 公开测试点个数（子查询统计 is_public = 1） */
    private Integer publicCaseCount;

    /** AC 次数 */
    private Integer passCount;

    /** 提交次数 */
    private Integer submitCount;

    /** 最近更新时间（DB 墙钟） */
    private LocalDateTime updateTime;

    /** 当前用户的最高分；未登录或无提交为 null（LEFT JOIN judge_submission 得到） */
    private Integer myBestScore;

    /** 当前用户的最近一次判题状态 0~9；未提交为 null */
    private Integer myLastStatus;

    /** 推荐候选的用户提交聚合；按当前 userId 分组，不能使用全站 passCount 判断。 */
    private Integer myAcCount;
    private Integer myAttempts;
}
