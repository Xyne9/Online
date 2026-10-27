package com.oj.teaching.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 教学班级实体（表 {@code teach_class}，DDL 见《04-终极完整版设计文档》§3.1 表 2）。
 *
 * <p>认证链路只用它做两件事：</p>
 * <ol>
 *   <li>{@code /api/auth/me} 把 {@code class_id} 翻成人类可读的 {@code className} / {@code subtitle}；</li>
 *   <li>登录时给教师补 {@code cid} 声明——契约 §2.7 要求「教师为所带班」，
 *       而 {@code sys_user.class_id} 对教师是 NULL，必须回查本表的 {@code teacher_id}。</li>
 * </ol>
 *
 * @author oj-cloud
 */
@Data
@TableName("teach_class")
public class TeachClass implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 班级 ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 班级名称，如「计算机2022级1班」 */
    private String className;

    /** 负责教师 ID（指向 sys_user.id，角色 TEACHER） */
    private Long teacherId;

    /** 班级人数（冗余统计列） */
    private Integer studentCount;

    /** 创建时间 */
    private LocalDateTime createTime;
}
