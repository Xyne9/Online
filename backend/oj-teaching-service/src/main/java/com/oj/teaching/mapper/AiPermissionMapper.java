package com.oj.teaching.mapper;

import com.oj.teaching.entity.TeachAssignment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/** 教务域拥有活动、班级和时间窗的判定权，AI 服务不直接跨库读取这些表。 */
@Mapper
public interface AiPermissionMapper {
    String POLICY_COLUMNS = "id, class_id, teacher_id, mode_type, status, allowed_ai_stages, "
            + "ai_quota_per_prob, problem_ids, start_time, end_time";

    @Select("SELECT " + POLICY_COLUMNS + " FROM teach_assignment WHERE id = #{assignmentId}")
    TeachAssignment selectPolicy(@Param("assignmentId") Long assignmentId);

    /** 防止考试中将客户端 assignmentId 改成 0，再从自由练习入口取同题 AI 提示。 */
    @Select("SELECT " + POLICY_COLUMNS + " FROM teach_assignment "
            + "WHERE class_id = #{classId} AND mode_type = 3 AND status IN (1, 2) "
            + "AND start_time <= #{now} AND end_time > #{now} "
            + "AND JSON_CONTAINS(problem_ids, JSON_ARRAY(#{problemId})) "
            + "ORDER BY end_time DESC LIMIT 1")
    TeachAssignment selectActiveExam(@Param("classId") Long classId,
                                     @Param("problemId") Long problemId,
                                     @Param("now") LocalDateTime now);
}
