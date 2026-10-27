package com.oj.problem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oj.problem.entity.ProbQuestion;
import com.oj.problem.mapper.params.ProblemListParams;
import com.oj.problem.mapper.row.ProblemListRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 题目 Mapper。
 *
 * <p><b>SQL 放在 XML 而不是注解里：</b>列表查询带 8 个可选条件 + 2 个子查询 + 动态排序，
 * 注解里拼字符串可读性极差且容易漏 {@code <script>} 包裹。XML 便于逐条对照契约核对，
 * 也方便 DBA 直接复制去 explain。</p>
 *
 * <p><b>铁律：</b>本 Mapper 的每一条查询都必须显式带 {@code deleted_at IS NULL}。
 * 项目没开逻辑删除拦截器（{@code MybatisPlusConfig} 有说明），漏写就等于把软删的题
 * 重新暴露给学生。</p>
 *
 * @author oj-cloud
 */
@Mapper
public interface ProbQuestionMapper extends BaseMapper<ProbQuestion> {

    /**
     * 题库列表分页查询（含「我的最高分 / 最近状态」与公开测试点数）。
     *
     * @param params 已校验参数（含 userId、state、levels、排序白名单等）
     * @return 当前页行数据
     */
    List<ProblemListRow> selectProblemPage(@Param("p") ProblemListParams params);

    /**
     * 同条件下的总条数（用于 total / hasNext）。
     *
     * <p>与 {@link #selectProblemPage} 共享同一段 WHERE，保证「翻到最后一页却 total 对不上」
     * 这种分页错位不会发生。</p>
     *
     * @param params 已校验参数（offset/limit 不参与）
     * @return 总条数
     */
    long countProblems(@Param("p") ProblemListParams params);

    /**
     * 列表四格统计：总题数 / 已通过 / 尝试过 / 本周新增。
     *
     * <p>口径说明（对应 s-problems.html:185-195 的「本学期进度」）：
     * {@code total} 与列表的 total 同源；{@code solved}/{@code tried} 按<b>当前用户</b>在
     * {@code judge_submission} 里的真实提交算；{@code weekNew} 是题库本周新增的题目数，
     * 与用户无关。未登录时 solved/tried 恒为 0（没有 user_id 可 JOIN）。</p>
     *
     * @param params 已校验参数（只用 userId / state / levels / keyword / tag / weekStart）
     * @return 单行 Map，键为 total/solved/tried/weekNew
     */
    java.util.Map<String, Object> selectStats(@Param("p") ProblemListParams params);

    /**
     * 取「首页推荐题」：已发布、未软删的题里提交数最高的那道。
     *
     * <p><b>为什么单独查一次而不是在当前页里挑：</b>{@code isFeatured} 是给题库页
     * hero 卡（「继续做题」）用的，它必须是<b>全局唯一且翻页不变</b>的。
     * 若按"当前页提交数最高的那道"来算，同一道题会在第 1 页被标推荐、翻到第 2 页就不标，
     * 而且默认按 id 倒序时首页全是 0 提交的新题，推荐会落到一个随机题目上。
     * 单查一次走 {@code idx_state_diff}，代价可忽略。</p>
     *
     * @return 推荐题 ID；题库为空时返回 null
     */
    @Select("SELECT id FROM prob_question "
            + "WHERE deleted_at IS NULL AND state = 1 "
            + "ORDER BY submit_count DESC, id ASC LIMIT 1")
    Long selectFeaturedProblemId();

    /**
     * 取单个题目（未软删），不带任何 JOIN。
     *
     * @param id 题目 ID
     * @return 题目实体；不存在或已软删返回 null
     */
    @Select("SELECT * FROM prob_question WHERE id = #{id} AND deleted_at IS NULL")
    ProbQuestion selectByIdNotDeleted(@Param("id") Long id);
}
