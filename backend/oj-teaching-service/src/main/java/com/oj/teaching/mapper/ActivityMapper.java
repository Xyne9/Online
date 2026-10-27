package com.oj.teaching.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oj.teaching.entity.*;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

/** 所有状态写入先锁 teach_assignment 单行，兼顾同考场计数和截止/交卷的原子性。 */
@Mapper
public interface ActivityMapper extends BaseMapper<TeachAssignment> {
    @Select("SELECT * FROM teach_assignment WHERE id=#{id} FOR UPDATE")
    TeachAssignment lock(@Param("id") Long id);

    @Select("SELECT * FROM teach_assignment ORDER BY id DESC")
    List<TeachAssignment> activities();

    @Update("UPDATE teach_assignment SET status=2 WHERE status=1 AND start_time <= #{now} AND end_time > #{now}")
    int activate(@Param("now") LocalDateTime now);

    @Select("SELECT id FROM teach_assignment WHERE status IN (1,2) AND end_time <= #{now}")
    List<Long> expired(@Param("now") LocalDateTime now);

    @Select("SELECT * FROM teach_class ORDER BY id")
    List<TeachClass> classes();

    @Select("SELECT * FROM sys_user WHERE class_id=#{classId} AND role='STUDENT' AND status=1 ORDER BY username,id")
    List<SysUser> students(@Param("classId") Long classId);

    @Select("SELECT * FROM teach_student_score WHERE assignment_id=#{id}")
    List<ActivityScore> scores(@Param("id") Long id);

    @Select("SELECT * FROM teach_student_score WHERE assignment_id=#{id} AND user_id=#{userId} AND problem_id=#{problemId}")
    ActivityScore score(@Param("id") Long id, @Param("userId") Long userId, @Param("problemId") Long problemId);

    @Insert("INSERT INTO teach_student_score(assignment_id,user_id,problem_id) VALUES(#{id},#{userId},#{problemId}) ON DUPLICATE KEY UPDATE id=id")
    int ensureScore(@Param("id") Long id, @Param("userId") Long userId, @Param("problemId") Long problemId);

    @Update("UPDATE teach_student_score SET draft_language=#{language},draft_code=#{code},update_time=#{now} WHERE assignment_id=#{id} AND user_id=#{userId} AND problem_id=#{problemId}")
    int draft(@Param("id") Long id, @Param("userId") Long userId, @Param("problemId") Long problemId,
              @Param("language") String language, @Param("code") String code, @Param("now") LocalDateTime now);

    @Update("UPDATE teach_student_score SET tab_switch_count=tab_switch_count+1 WHERE assignment_id=#{id} AND user_id=#{userId} AND problem_id=#{problemId}")
    int incrementSwitch(@Param("id") Long id, @Param("userId") Long userId, @Param("problemId") Long problemId);

    @Update("UPDATE teach_student_score SET red_flag_type=GREATEST(red_flag_type,1) WHERE assignment_id=#{id} AND user_id=#{userId}")
    int flag(@Param("id") Long id, @Param("userId") Long userId);

    @Update("UPDATE teach_student_score SET max_score=CASE WHEN red_flag_type=2 THEN 0 ELSE #{score} END,prev_max_score=CASE WHEN red_flag_type=2 THEN #{score} ELSE prev_max_score END,best_submission_id=#{submissionId},min_time_used=#{timeMs},submit_count=submit_count+1 WHERE assignment_id=#{id} AND user_id=#{userId} AND problem_id=#{problemId}")
    int improve(@Param("id") Long id, @Param("userId") Long userId, @Param("problemId") Long problemId,
                @Param("score") Integer score, @Param("submissionId") Long submissionId, @Param("timeMs") Integer timeMs);

    @Update("UPDATE teach_student_score SET submit_count=submit_count+1 WHERE assignment_id=#{id} AND user_id=#{userId} AND problem_id=#{problemId}")
    int countSubmission(@Param("id") Long id, @Param("userId") Long userId, @Param("problemId") Long problemId);

    @Select("SELECT * FROM assignment_submission WHERE assignment_id=#{id}")
    List<AssignmentSubmission> submissions(@Param("id") Long id);

    @Select("SELECT * FROM assignment_submission WHERE assignment_id=#{id} AND user_id=#{userId}")
    AssignmentSubmission submission(@Param("id") Long id, @Param("userId") Long userId);

    @Insert("INSERT INTO assignment_submission(display_no,assignment_id,user_id,class_id,status,submitted_at,undo_deadline_at,problem_count,answered_count,remark) VALUES(#{displayNo},#{assignmentId},#{userId},#{classId},#{status},#{submittedAt},#{undoDeadlineAt},#{problemCount},#{answeredCount},#{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSubmission(AssignmentSubmission value);

    @Update("UPDATE assignment_submission SET status='SUBMITTED',submitted_at=#{submittedAt},undo_deadline_at=#{undoDeadlineAt},problem_count=#{problemCount},answered_count=#{answeredCount},remark=#{remark} WHERE id=#{id} AND status='UNSUBMITTED'")
    int resubmit(AssignmentSubmission value);

    @Update("UPDATE assignment_submission SET status='UNSUBMITTED',revoked_at=#{now} WHERE assignment_id=#{id} AND user_id=#{userId} AND status='SUBMITTED' AND undo_deadline_at > #{now}")
    int revoke(@Param("id") Long id, @Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Select("SELECT * FROM exam_monitor_event WHERE exam_id=#{id} ORDER BY seq")
    List<ExamMonitorEvent> events(@Param("id") Long id);

    @Select("SELECT COALESCE(MAX(seq),0) FROM exam_monitor_event WHERE exam_id=#{id}")
    long latestSeq(@Param("id") Long id);

    @Insert("INSERT INTO exam_monitor_event(exam_id,seq,user_id,event_type,tone,message,payload,create_time) VALUES(#{examId},#{seq},#{userId},#{eventType},#{tone},#{message},#{payload},#{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertEvent(ExamMonitorEvent event);
}

