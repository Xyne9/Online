package com.oj.problem.mapper;

import com.oj.problem.entity.ProbQuestion;
import org.apache.ibatis.annotations.*;

/** 写操作均先锁题目行；所有子资源变动共享此锁，发布校验与版本变更不能交错。 */
@Mapper
public interface ProblemWriteMapper {
    @Select("SELECT * FROM prob_question WHERE id=#{id} AND deleted_at IS NULL "
            + "AND (creator_id=#{userId} OR #{admin}=TRUE) FOR UPDATE")
    ProbQuestion lockOwned(@Param("id") Long id, @Param("userId") Long userId, @Param("admin") boolean admin);

    @Select("SELECT * FROM prob_question WHERE id=#{id} AND deleted_at IS NULL "
            + "AND (creator_id=#{userId} OR #{admin}=TRUE)")
    ProbQuestion selectOwned(@Param("id") Long id, @Param("userId") Long userId, @Param("admin") boolean admin);

    @Select("SELECT COUNT(*) FROM judge_submission WHERE problem_id=#{problemId}")
    long countSubmissions(@Param("problemId") Long problemId);

    @Update("UPDATE prob_question SET version=version+1, update_time=NOW() WHERE id=#{id} "
            + "AND version=#{version} AND deleted_at IS NULL AND (creator_id=#{userId} OR #{admin}=TRUE)")
    int bumpVersion(@Param("id") Long id, @Param("version") Integer version,
                    @Param("userId") Long userId, @Param("admin") boolean admin);

    // 临时移到 INT 最小值附近，避免重新排序或多次软删时撞 uk_prob_seq(problem_id,seq_no)。
    @Update("UPDATE prob_test_case SET seq_no=#{seq} WHERE id=#{id} AND problem_id=#{problemId}")
    int setCaseSeq(@Param("id") Long id, @Param("problemId") Long problemId, @Param("seq") int seq);

    @Select("SELECT COALESCE(MIN(seq_no),0) FROM prob_test_case WHERE problem_id=#{problemId}")
    int minimumSeq(@Param("problemId") Long problemId);

    @Delete("DELETE FROM prob_question_kp WHERE problem_id=#{problemId}")
    int deleteKnowledgeLinks(@Param("problemId") Long problemId);

    @Insert("INSERT INTO prob_question_kp(problem_id,kp_id,weight,is_primary) VALUES(#{problemId},#{kpId},1.0,1)")
    int addKnowledgeLink(@Param("problemId") Long problemId, @Param("kpId") Long kpId);

    @Select("SELECT * FROM prob_question WHERE id=#{id} AND deleted_at IS NULL FOR UPDATE")
    ProbQuestion lockById(@Param("id") Long id);
}
