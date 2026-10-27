package com.oj.judge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oj.judge.entity.ProbQuestionJudgeConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 题目判题配置的只读访问（{@code prob_question}）。
 *
 * <p><b>只读</b>：题目数据的写权限归题库服务，判题服务绝不写题面/测试点，
 * 只有 {@link JudgeSubmissionMapper#increaseSubmitCount} 那两条计数是例外
 * （已在那边注明「跨服务直连是本期妥协」）。</p>
 *
 * @author oj-cloud
 */
@Mapper
public interface ProbQuestionMapper extends BaseMapper<ProbQuestionJudgeConfig> {

    /**
     * 取判题所需的最小题目配置。
     *
     * <p>带 {@code deleted_at IS NULL}：软删题不能被判题（否则学生会提交到已删题目上，
     * 测试数据目录可能已不存在 → 变成 502）。</p>
     *
     * @param problemId 题目 ID
     * @return 配置；不存在/已软删返回 null
     */
    @Select("""
            SELECT q.id, q.title, q.time_limit AS timeLimit, q.memory_limit AS memoryLimit,
                   q.test_case_count AS testCaseCount, q.test_case_dir AS testCaseDir, q.state
            FROM prob_question q
            WHERE q.id = #{problemId} AND q.deleted_at IS NULL
            """)
    ProbQuestionJudgeConfig selectJudgeConfig(@Param("problemId") Long problemId);

    /**
     * 取题目标题（列表/详情展示用；题目被删时返回 null，展示侧兜底成「题目已删除」）。
     *
     * @param problemId 题目 ID
     * @return 标题
     */
    @Select("SELECT q.title FROM prob_question q WHERE q.id = #{problemId}")
    String selectTitle(@Param("problemId") Long problemId);
}
