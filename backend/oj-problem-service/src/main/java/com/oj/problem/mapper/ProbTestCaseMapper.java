package com.oj.problem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oj.problem.entity.ProbTestCase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 测试点 Mapper。
 *
 * <p><b>安全红线：</b>本 Mapper 查出来的 {@code input_text/output_text} 是隐藏用例正文，
 * 只能出现在 {@code /inner/problem/test-cases}（服务间）与编辑态；
 * 作答视角的 {@code chainPreview} 只允许取 seqNo/kind/score 三列，
 * 因此这里另开一个 {@link #selectBriefByProblemId} 只选这三列——
 * 从 SQL 层就不把正文捞进内存，比"查出来再脱敏"更难写错。</p>
 *
 * <p><b>软删：</b>删测试点写的是 {@code seq_no = -seq_no}，所以本 Mapper 统一带
 * {@code deleted_at IS NULL AND seq_no > 0} 双条件，防止负数行混进判题顺序。</p>
 *
 * @author oj-cloud
 */
@Mapper
public interface ProbTestCaseMapper extends BaseMapper<ProbTestCase> {

    /** AI RAG 的公开样例兜底；隐藏点输入输出不进入此查询结果。 */
    @Select("SELECT id, problem_id, seq_no, input_text, output_text FROM prob_test_case "
            + "WHERE problem_id = #{problemId} AND deleted_at IS NULL AND seq_no > 0 "
            + "AND is_public = 1 ORDER BY seq_no ASC")
    List<ProbTestCase> selectPublicByProblemId(@Param("problemId") Long problemId);

    /**
     * 取某题全部有效测试点（含输入输出正文，按 seq_no 升序）。
     *
     * <p>仅供 {@code /inner/problem/test-cases} 与 {@code /inner/problem/edit} 使用。</p>
     *
     * @param problemId 题目 ID
     * @return 测试点列表，无数据时为空列表
     */
    @Select("SELECT id, problem_id, seq_no, kind, input_text, output_text, score, is_public, "
            + "input_size, output_size, deleted_at, create_time, update_time "
            + "FROM prob_test_case "
            + "WHERE problem_id = #{problemId} AND deleted_at IS NULL AND seq_no > 0 "
            + "ORDER BY seq_no ASC")
    List<ProbTestCase> selectByProblemId(@Param("problemId") Long problemId);

    /**
     * 取某题的测试点摘要（<b>只有</b> id/seqNo/kind/score，不含输入输出）。
     *
     * <p>判题配置与作答视角的判分链都走它，天然不泄题。</p>
     *
     * @param problemId 题目 ID
     * @return 摘要列表（按 seq_no 升序）
     */
    @Select("SELECT id, problem_id, seq_no, kind, score "
            + "FROM prob_test_case "
            + "WHERE problem_id = #{problemId} AND deleted_at IS NULL AND seq_no > 0 "
            + "ORDER BY seq_no ASC")
    List<ProbTestCase> selectBriefByProblemId(@Param("problemId") Long problemId);

    /**
     * 统计公开测试点个数（is_public = 1）。
     *
     * @param problemId 题目 ID
     * @return 个数，无数据返回 0
     */
    @Select("SELECT COUNT(*) FROM prob_test_case "
            + "WHERE problem_id = #{problemId} AND deleted_at IS NULL AND seq_no > 0 AND is_public = 1")
    int countPublicByProblemId(@Param("problemId") Long problemId);
}
