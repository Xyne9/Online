package com.oj.teaching.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统用户实体（表 {@code sys_user}，DDL 见《04-终极完整版设计文档》§3.1 表 1）。
 *
 * <p><b>字段与列名映射：</b>本类字段用驼峰，DB 用下划线，靠
 * {@code mybatis-plus.configuration.map-underscore-to-camel-case=true}（见 application.yml）
 * 自动转换，因此<b>不需要</b>逐个写 {@code @TableField}。</p>
 *
 * <p><b>关于 {@link #password}：</b>库里存的是 BCrypt 哈希（cost=10），
 * 本实体只用于「取出来做 {@code matches} 校验」，<b>任何响应 DTO 都不得携带该字段</b>，
 * 一旦泄漏等于把全站账号的离线爆破材料送出去。</p>
 *
 * <p><b>关于 {@link #weakTags}：</b>DB 类型是 {@code JSON}，JDBC 取出来就是一段 JSON 文本
 * （形如 {@code ["图论","数学"]}）。这里刻意用 {@code String} 接收而不是映射成
 * {@code List<String>}：JSON 列在 MySQL 里允许 {@code NULL}，也可能被人工写坏，
 * 用 String 接住再在服务层 {@code try/catch} 解析，坏数据只会退化成空数组，
 * 不会让整个 {@code /api/auth/me} 500。</p>
 *
 * @author oj-cloud
 */
@Data
@TableName("sys_user")
public class SysUser implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户主键 ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 学号 / 工号 / 登录账号（唯一） */
    private String username;

    /** BCrypt 哈希密码（cost=10）；绝不出现在任何响应里 */
    private String password;

    /** 真实姓名 */
    private String realName;

    /** 角色：STUDENT / TEACHER / ADMIN（基线 §5.6） */
    private String role;

    /** 所属班级 ID（仅学生有效；教师为 null，管理员为 null） */
    private Long classId;

    /** 薄弱知识点名称数组快照（JSON 列），如 {@code ["图论","数学"]} */
    private String weakTags;

    /** 状态：1-正常，0-禁用（禁用后登录返回 403） */
    private Integer status;

    /** 最近登录时刻（v2.0 新增列，登录成功后回写） */
    private LocalDateTime lastLoginAt;

    /** 创建时间 */
    private LocalDateTime createTime;

    /**
     * 账号是否处于「正常」状态。
     *
     * <p>把 {@code null} 也判为不可用：列定义是 {@code NOT NULL DEFAULT 1}，
     * 真出现 null 说明数据被人工改坏，此时拒绝登录比放行安全。</p>
     *
     * @return true 表示 status = 1
     */
    public boolean isEnabled() {
        return status != null && status == 1;
    }
}
