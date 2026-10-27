package com.oj.problem.mapper.params;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 列表查询的「已校验」参数（由 Service 构造，Mapper 只负责拼 SQL）。
 *
 * <p><b>为什么单独一层：</b>契约要求「学生强制 state=1」「sortBy 白名单」「lvl 只能是 1~5」，
 * 这些规则必须在 Java 里判定完再进 SQL。把原始查询串直接透到 Mapper，
 * 就只剩两种选择：用 {@code ${}} 拼串（注入风险）或在 XML 里写一堆 if（规则散落）。
 * 这里全部收敛成强类型字段，XML 只做取值。</p>
 *
 * @author oj-cloud
 */
@Data
public class ProblemListParams {

    /**
     * 当前用户 ID；<b>未登录时由 Service 置为 -1</b>（不是 null）。
     * <p>原因：列表 SQL 里 {@code judge_submission} 的子查询是常驻 LEFT JOIN，
     * 传 null 会让 {@code user_id = NULL} 永远不成立、且部分驱动会直接抛类型异常。
     * 用一个绝不存在的 ID 代替，语义等价且 SQL 稳定。</p>
     */
    private Long userId;

    /** 题目状态过滤；null = 不限制（教师视角的「全部」） */
    private Integer state;

    /** 难度多选；空列表 = 不限制 */
    private List<Integer> levels;

    /** 标题/标签模糊词；null/空 = 不限制 */
    private String keyword;

    /** 单标签精确匹配；null/空 = 不限制 */
    private String tag;

    /**
     * 我的状态筛选：all / todo / done / fail（已校验）。
     *
     * <p>只用于日志与排障；<b>SQL 判定请用下面三个布尔位</b>。</p>
     */
    private String myStatus;

    /**
     * 只看「未开始」（该题没有任何提交）。
     *
     * <p><b>为什么不在 XML 里直接比较字符串：</b>MyBatis 的 {@code <if test>} 走 OGNL，
     * 单引号字面量在 OGNL 里对单字符会被当成 {@code Character}，与 {@code String} 比较恒为 false，
     * 这类 bug 不报错、只是"筛选没生效"，极难发现。改成 Java 侧算好的布尔位，
     * XML 里只写 {@code <if test="p.onlyTodo">}，从根上消除该风险。</p>
     */
    private boolean onlyTodo;

    /** 只看「已通过」（该题存在 AC 提交） */
    private boolean onlyDone;

    /** 只看「未通过」（提交过但从未 AC） */
    private boolean onlyFail;

    /** 已解析的排序列表达式（来自白名单映射，绝不含用户原始输入） */
    private String orderBy;

    /** 排序方向：ASC / DESC（已大写，来自白名单） */
    private String orderDirection;

    /** 分页偏移 */
    private long offset;

    /** 每页条数（1~100） */
    private int limit;

    /** 「本周新增」的时间下界（DB 墙钟） */
    private LocalDateTime weekStart;
}
