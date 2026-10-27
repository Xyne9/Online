package com.oj.teaching.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 当前用户响应体（契约 §2.5 {@code data}）。
 *
 * <pre>
 * {"id":1024,"username":"20220101","realName":"林昭","role":"STUDENT","roleLabel":"学生",
 *  "classId":1,"className":"计算机2022级1班","subtitle":"计算机2022级1班",
 *  "weakTags":["图论","数学"],"aiQuotaDefault":3,"lastLoginAt":1761302400000}
 * </pre>
 *
 * <p><b>语义陷阱（{@code 01} 报告 §3⑧）：</b>{@code weakTags} 是「薄弱知识点名单」，
 * 越高越弱；{@code s-analytics} 的掌握度百分比越高越强，两者方向相反，
 * <b>不可混用</b>，字段名也不得互借。</p>
 *
 * @author oj-cloud
 */
@Data
public class CurrentUserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    private Long id;

    /** 学号 / 工号 / 登录账号 */
    private String username;

    /** 真实姓名 */
    private String realName;

    /** STUDENT / TEACHER / ADMIN */
    private String role;

    /** 角色中文名 */
    private String roleLabel;

    /** 班级 ID（管理员为 null） */
    private Long classId;

    /** 班级名称（管理员为 null） */
    private String className;

    /** 副标题：学生=班级名，教师=「班级 · 主讲教师」，管理员=「系统与模型运维」 */
    private String subtitle;

    /** 薄弱知识点名称数组；解析失败给空数组，绝不给 null */
    private java.util.List<String> weakTags;

    /** 单题 AI 提示默认配额（平台默认值，见 AuthConstants.AI_QUOTA_DEFAULT） */
    private Integer aiQuotaDefault;

    /** 最近登录时刻（epoch 毫秒）；从未登录为 null */
    private Long lastLoginAt;
}
