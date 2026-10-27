package com.oj.problem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 题目主表 {@code prob_question}（题库服务归属，agent.md §5.1）。
 *
 * <p><b>字段命名口径：</b>Java 字段用驼峰，DB 列是下划线，靠
 * {@code mybatis-plus.configuration.map-underscore-to-camel-case=true} 自动映射。
 * 只有「列名与驼峰不是标准下划线互转」时才写 {@code @TableField}——本表没有这种列，
 * 故一个都不写，避免两处口径打架。</p>
 *
 * <p><b>DB 列名与对外契约名不同，务必分清：</b></p>
 * <ul>
 *   <li>{@code time_limit} → 对外 {@code timeLimitMs}（契约 §2.1(4)：时长一律 Ms 结尾）</li>
 *   <li>{@code memory_limit} → 对外 {@code memoryLimitMb}（契约 §2.1(5)：题目内存单位 MB）</li>
 *   <li>{@code content} → 对外拆成 {@code statement / inputSpec / outputSpec}（契约 §3.3）</li>
 *   <li>{@code sample_cases} / {@code code_templates} / {@code tags} / {@code ai_stages} 都是 JSON 列，
 *       这里统一按 {@code String} 取原文，由 Service 用 Jackson 解析——不引 MyBatis 的 JSON 类型处理器，
 *       避免「库是 8.0 驱动是 8.3 类型处理器版本漂移」这类只在运行时炸的坑。</li>
 * </ul>
 *
 * <p><b>软删：</b>本表用 {@code deleted_at}（NULL = 未删）。项目刻意不开 MyBatis-Plus 的逻辑删除拦截器
 * （见 {@code MybatisPlusConfig} 注释），所以<b>所有</b>查询都要自己带 {@code deleted_at IS NULL}。
 * 本实体保留 {@link #deletedAt} 字段只用于读全量，不作为删除条件来源。</p>
 *
 * @author oj-cloud
 */
@Data
@TableName("prob_question")
public class ProbQuestion implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键，BIGINT AUTO_INCREMENT，展示号 = "#" + id（契约 §2.1(6)） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标题（≤40 字符，DB 列 varchar(128) 留了余量） */
    private String title;

    /** 难度 1~5：1 入门 / 2 简单 / 3 中等 / 4 较难 / 5 困难（契约 §2.1(2)） */
    private Integer difficulty;

    /** 主知识点 ID，可空（未分类）。关联表 prob_question_kp 是多对多，本列是「主」知识点 */
    private Long knowledgePointId;

    /** 标签，JSON 数组原文，如 {@code ["动态规划","前缀和"]} */
    private String tags;

    /** 题面 Markdown 全文（含「## 输入格式」「## 输出格式」等小节） */
    private String content;

    /** 样例，JSON 数组原文，如 {@code [{"input":"...","output":"..."}]} */
    private String sampleCases;

    /** 代码模板，JSON 对象原文，如 {@code {"cpp":"...","java":"...","python":"..."}} */
    private String codeTemplates;

    /** 时间限制（毫秒，C/C++ 基准；Java/Python 由前端按倍率展示）。对外名 timeLimitMs */
    private Integer timeLimit;

    /** 内存限制（MB，C/C++ 基准）。对外名 memoryLimitMb */
    private Integer memoryLimit;

    /** 测试数据目录（宿主机与容器双路径见 application.yml 的 oj.testcase.*） */
    private String testCaseDir;

    /** 测试点个数（手工维护的声明值；真实行数以 prob_test_case 为准，二者已核对一致） */
    private Integer testCaseCount;

    /** 官方题解。<b>作答视角绝不外泄</b>，只有 /inner/problem/edit 与编辑态可以读 */
    private String officialSolution;

    /** 是否启用 AI 提示 */
    private Boolean aiEnabled;

    /** 单学生 AI 提示配额，-1 表示不限 */
    private Integer aiQuotaLimit;

    /** 允许的 AI 阶段，JSON 数组原文，如 {@code [1,2,3,4]} */
    private String aiStages;

    /** 状态：0 草稿 / 1 已发布 / 2 已归档（契约 §2.1(7)） */
    private Integer state;

    /** 乐观锁版本号（编辑保存必须带 version 条件，冲突返 409） */
    private Integer version;

    /** AC 次数（手工计数，无触发器；判题落库时增量更新，另配每日对账） */
    private Integer passCount;

    /** 提交次数（同上） */
    private Integer submitCount;

    /** 创建者用户 ID */
    private Long creatorId;

    /** 软删时间，NULL = 未删 */
    private LocalDateTime deletedAt;

    /** 创建时间（DB datetime，无时区） */
    private LocalDateTime createTime;

    /** 最后更新时间（DB datetime，ON UPDATE CURRENT_TIMESTAMP） */
    private LocalDateTime updateTime;
}
