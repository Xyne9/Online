package com.oj.problem.mapper;

import com.oj.problem.mapper.row.MyRecordRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 判题提交的<b>只读</b>Mapper（题库服务视角）。
 *
 * <p><b>⚠️ 表归属说明：</b>{@code judge_submission} 归 {@code oj-judge-service}（agent.md §5.1），
 * 本服务只读、不写。之所以直连而不是走 Feign：契约 §3.2 的列表要在<b>分页 SQL 里</b>
 * LEFT JOIN 出「我的状态/我的最高分」，走 Feign 就得先把上千道题全拉回来再内存拼，
 * 既违反分页语义，又把每页请求放大成 N 次 RPC。本期按「同库只读」实现，
 * 代价是判题服务的表结构变更会波及本服务的 SQL——已在类注释里记为本期妥协，
 * 后续拆库时把这两条查询改成读判题服务的宽表/物化视图即可。</p>
 *
 * <p>本 Mapper 只有 SELECT，不提供任何写方法，避免"顺手改一行"越界。</p>
 *
 * @author oj-cloud
 */
@Mapper
public interface JudgeSubmissionReadMapper {

    /**
     * 取某用户在某题上的「我的记录」聚合（契约 §3.3 的 {@code myRecord}）。
     *
     * <p>取最高分那条提交的 ID 用「按分数降序、id 升序排第一」的子查询，
     * 而不是 {@code MAX(id)}——同分时 {@code MAX(id)} 会给最后一条，
     * 而这里要的是"首次拿到这个分数的提交"，两者语义不同。</p>
     *
     * @param userId    用户 ID（调用方保证非 null）
     * @param problemId 题目 ID
     * @return 聚合行；该用户在这道题上无提交时返回 null
     */
    @Select("SELECT s.problem_id AS problemId, "
            + "       COUNT(*) AS attempts, "
            + "       MAX(s.oi_score) AS bestScore, "
            + "       (SELECT s2.id FROM judge_submission s2 "
            + "         WHERE s2.user_id = #{userId} AND s2.problem_id = #{problemId} "
            + "         ORDER BY s2.oi_score DESC, s2.id ASC LIMIT 1) AS bestSubmissionId, "
            + "       (SELECT s3.status FROM judge_submission s3 "
            + "         WHERE s3.user_id = #{userId} AND s3.problem_id = #{problemId} "
            + "         ORDER BY s3.id DESC LIMIT 1) AS lastStatus, "
            + "       MAX(s.create_time) AS lastSubmittedAt "
            + "FROM judge_submission s "
            + "WHERE s.user_id = #{userId} AND s.problem_id = #{problemId} "
            + "GROUP BY s.problem_id")
    MyRecordRow selectMyRecord(@Param("userId") Long userId, @Param("problemId") Long problemId);
}
