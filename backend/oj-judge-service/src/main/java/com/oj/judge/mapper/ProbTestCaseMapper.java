package com.oj.judge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oj.judge.entity.ProbTestCaseMeta;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 测试点元信息的只读访问（{@code prob_test_case}）。
 *
 * <p><b>只查元信息，不查正文</b>：{@code input_text/output_text} 是 ≤4 KB 摘要，
 * 判题比对必须读磁盘 {@code data/testcases/{problemId}/{seqNo}.in|.out}（day3 §7 硬约束 1）。</p>
 *
 * @author oj-cloud
 */
@Mapper
public interface ProbTestCaseMapper extends BaseMapper<ProbTestCaseMeta> {

    /**
     * 取某题全部有效测试点的元信息（按 seqNo 升序 = 判题执行顺序）。
     *
     * <p>带 {@code deleted_at IS NULL}：软删测试点的 {@code seq_no} 会被置负
     * （day3 §7 硬约束 4），两者一起挡住才能保证不会跑到已删的点上。</p>
     *
     * @param problemId 题目 ID
     * @return 元信息列表（不含正文）
     */
    @Select("""
            SELECT t.id, t.problem_id AS problemId, t.seq_no AS seqNo, t.kind, t.score,
                   t.is_public AS isPublic, t.input_size AS inputSize, t.output_size AS outputSize
            FROM prob_test_case t
            WHERE t.problem_id = #{problemId} AND t.deleted_at IS NULL AND t.seq_no > 0
            ORDER BY t.seq_no ASC
            """)
    List<ProbTestCaseMeta> selectMetaByProblem(@Param("problemId") Long problemId);

    /**
     * 取某题的公开样例（{@code is_public = 1}），按 seqNo 升序。
     *
     * <p>「运行样例」用。注意样例的<b>输入输出正文</b>仍然要从磁盘读，
     * 这里只用来确定「哪些 seqNo 是公开的」。</p>
     *
     * @param problemId 题目 ID
     * @return 公开测试点元信息
     */
    @Select("""
            SELECT t.id, t.problem_id AS problemId, t.seq_no AS seqNo, t.kind, t.score,
                   t.is_public AS isPublic, t.input_size AS inputSize, t.output_size AS outputSize
            FROM prob_test_case t
            WHERE t.problem_id = #{problemId} AND t.deleted_at IS NULL AND t.seq_no > 0
              AND t.is_public = 1
            ORDER BY t.seq_no ASC
            """)
    List<ProbTestCaseMeta> selectPublicCases(@Param("problemId") Long problemId);
}
