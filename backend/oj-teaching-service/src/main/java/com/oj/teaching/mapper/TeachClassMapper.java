package com.oj.teaching.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oj.teaching.entity.TeachClass;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 教学班级 Mapper（表 {@code teach_class}）。
 *
 * @author oj-cloud
 */
@Mapper
public interface TeachClassMapper extends BaseMapper<TeachClass> {

    /**
     * 查某个教师所带的第一个班（契约 §2.7 的 {@code cid}：教师取「所带班」）。
     *
     * <p>{@code teach_class.teacher_id} 没有唯一约束（一个教师可以带多个班），
     * 但 JWT 的 {@code cid} 只有一个值，故按 {@code id ASC} 取最早创建的那个班，
     * 保证同一教师每次登录拿到的 {@code cid} 稳定（否则每次登录 token 里的
     * cid 都不同，前端按班级缓存的数据会莫名失效）。</p>
     *
     * @param teacherId 教师用户 ID
     * @return 班级；该教师未带班返回 {@code null}
     */
    @Select("SELECT id, class_name, teacher_id, student_count, create_time "
            + "FROM teach_class WHERE teacher_id = #{teacherId} ORDER BY id ASC LIMIT 1")
    TeachClass selectFirstByTeacherId(@Param("teacherId") Long teacherId);
}
