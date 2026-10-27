package com.oj.teaching.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 导航角标计数 Mapper（契约 §2.6 备注：「角标数实时查库（待办数 / 待复核数），不写死」）。
 *
 * <p><b>为什么不并进 {@code SysUserMapper}：</b>这些查询统计的是
 * {@code teach_assignment} / {@code assignment_submission} / {@code teach_plagiarism_report}
 * 三张表，与 {@code sys_user} 毫无关系。塞进用户 Mapper 会让「谁在读用户表」变得模糊，
 * 后续做数据权限审计时容易漏判。单独一个只读计数 Mapper，职责单一、一眼可查。</p>
 *
 * <p><b>为什么全部返回 {@code int} 而不是包装类型：</b>{@code COUNT(*)} 永远有值，
 * 不会返回 null；用原生 int 省掉调用处的判空。</p>
 *
 * @author oj-cloud
 */
@Mapper
public interface AuthBadgeMapper {

    /**
     * 学生「我的作业」待办数：本班已开始且未结束、且本人尚未整份提交的作业数。
     *
     * <p>{@code status IN (1,2)} 对应状态机「1 未开始 / 2 进行中」——
     * 草稿（0）学生看不见，已结束（3）不再是待办。用 {@code NOT EXISTS} 而不是
     * {@code LEFT JOIN ... IS NULL}：前者在 {@code assignment_submission}
     * 命中唯一键 {@code uk_assign_user} 后能直接短路，后者会先物化再过滤。</p>
     *
     * @param classId 学生所在班级
     * @param userId  学生用户 ID
     * @return 待办作业数
     */
    @Select("SELECT COUNT(*) FROM teach_assignment a "
            + "WHERE a.class_id = #{classId} AND a.status IN (1, 2) "
            + "AND NOT EXISTS (SELECT 1 FROM assignment_submission s "
            + "                WHERE s.assignment_id = a.id AND s.user_id = #{userId} "
            + "                  AND s.status = 'SUBMITTED')")
    int countStudentTodoAssignments(@Param("classId") Long classId, @Param("userId") Long userId);

    /**
     * 教师「教学活动」待办数：本人创建的、尚未结束（草稿/未开始/进行中）的活动数。
     *
     * @param teacherId 教师用户 ID
     * @return 待办活动数
     */
    @Select("SELECT COUNT(*) FROM teach_assignment "
            + "WHERE teacher_id = #{teacherId} AND status IN (0, 1, 2)")
    int countTeacherTodoAssignments(@Param("teacherId") Long teacherId);

    /**
     * 教师「查重报告」待复核数：本人所带班级的作业里、审核状态仍为 0（待复核）的报告数。
     *
     * <p>报告表本身没有 {@code teacher_id}，只能经 {@code teach_assignment.teacher_id}
     * 回溯归属；不加这个 join 会把别的老师班的待复核数也算进来（越权可见的信息泄露）。</p>
     *
     * @param teacherId 教师用户 ID
     * @return 待复核报告数
     */
    @Select("SELECT COUNT(*) FROM teach_plagiarism_report r "
            + "JOIN teach_assignment a ON a.id = r.assignment_id "
            + "WHERE a.teacher_id = #{teacherId} AND r.audit_status = 0")
    int countTeacherPendingPlagiarism(@Param("teacherId") Long teacherId);
}
