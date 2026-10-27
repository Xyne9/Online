package com.oj.teaching.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 登录 / 续期响应里的「用户卡」（契约 §2.2 {@code data.user}）。
 *
 * <p>字段逐字对齐契约示例：{@code id, username, realName, role, roleLabel, classId,
 * className, avatarText}。多出的 {@code subtitle} 是给 {@code shell.js:152}
 * 的身份第三行兜底（{@code u.subtitle || u.className || u.roleLabel}）——前端
 * {@code buildSide()} 会读它，缺了会退化成演示身份。</p>
 *
 * <p><b>绝不包含 {@code password}：</b>本类是唯一会随登录响应下发的用户结构，
 * 实体 {@code SysUser} 直接序列化会把 BCrypt 哈希带出去。</p>
 *
 * @author oj-cloud
 */
@Data
public class LoginUserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    private Long id;

    /** 学号 / 工号 / 登录账号 */
    private String username;

    /** 真实姓名 */
    private String realName;

    /** STUDENT / TEACHER / ADMIN（大写，前端 api/auth.js:52 自己转小写） */
    private String role;

    /** 角色中文名，来自 {@code RoleConstants.labelOf} */
    private String roleLabel;

    /** 班级 ID（管理员为 null） */
    private Long classId;

    /** 班级名称（管理员为 null；教师为其所带班） */
    private String className;

    /** 身份副标题：学生=班级名，教师=「班级 · 主讲教师」，管理员=「系统与模型运维」 */
    private String subtitle;

    /** 头像字（真实姓名首字，前端 .avatar 直接渲染） */
    private String avatarText;
}
