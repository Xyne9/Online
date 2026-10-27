package com.oj.common.core.jwt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 全局角色权限矩阵（设计文档 §1.4）。
 *
 * <p>网关侧只做「粗粒度」判断（学生能不能碰 {@code /api/admin/**}），
 * 「细粒度」的资源归属校验（学生只能看自己的提交、教师只能改自己班）必须落在服务层 SQL 里，
 * 因为网关看不到资源 ID 与归属。两者缺一不可，这也是 §1.4 表格最后一列反复强调的要求。</p>
 *
 * @author oj-cloud
 */
public final class RoleConstants {

    /** 学生 */
    public static final String STUDENT = "STUDENT";

    /** 教师 */
    public static final String TEACHER = "TEACHER";

    /** 管理员 */
    public static final String ADMIN = "ADMIN";

    /** 全部合法角色 */
    public static final List<String> ALL = List.of(STUDENT, TEACHER, ADMIN);

    /**
     * 仅管理员可访问的 URL 前缀（§1.4：网关 + 服务双层强制 role == ADMIN）
     * <p>注意：{@code /inner/**} 是服务间内部接口，走的是 {@code X-Inner-Token} 校验，不走用户 JWT。</p>
     */
    public static final List<String> ADMIN_ONLY_PREFIXES = List.of("/api/admin/");

    /**
     * 考试模式下禁止调用的 URL 前缀（§1.4：{@code /api/ai/**} 在考试中禁止）
     * <p>Day2 骨架阶段不启用，由 teaching 服务判定考试态后通过请求头下发。</p>
     */
    public static final List<String> EXAM_FORBIDDEN_PREFIXES = List.of("/api/ai/");

    private RoleConstants() {
    }

    /** 角色是否合法（大小写敏感，与数据库 sys_user.role 一致） */
    public static boolean isValid(String role) {
        return role != null && ALL.contains(role);
    }

    /** 是否管理员 */
    public static boolean isAdmin(String role) {
        return ADMIN.equals(role);
    }

    /** 是否教师（含管理员，管理员拥有教师全部权限） */
    public static boolean isTeacherOrAbove(String role) {
        return TEACHER.equals(role) || ADMIN.equals(role);
    }

    /**
     * 判断某路径是否仅管理员可访问。
     *
     * @param path 请求路径
     * @return true 表示该路径属于 {@code /api/admin/**}
     */
    public static boolean isAdminOnlyPath(String path) {
        if (path == null) {
            return false;
        }
        for (String prefix : ADMIN_ONLY_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 角色中文名（前端 roleLabel 用，§1.4 的三种角色） */
    public static String labelOf(String role) {
        if (STUDENT.equals(role)) {
            return "学生";
        }
        if (TEACHER.equals(role)) {
            return "教师";
        }
        if (ADMIN.equals(role)) {
            return "管理员";
        }
        return "未知角色";
    }

    /** 便于调试：返回全部角色名的不可变副本 */
    public static List<String> all() {
        return new ArrayList<>(Arrays.asList(STUDENT, TEACHER, ADMIN));
    }
}
