package com.oj.common.web.context;

import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.jwt.RoleConstants;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 当前登录用户上下文（从网关注入的请求头读取）。
 *
 * <p><b>数据来源：</b>网关 {@code AuthGlobalFilter} 校验 JWT 后，把
 * {@code X-User-Id} / {@code X-User-Role} / {@code X-Class-Id} 三个头注入下游请求。
 * 服务层**只认这三个头**，不再自己解析 JWT——令牌校验只发生在网关一处，
 * 避免「网关校验一次、服务再校验一次、两处口径不一致」的经典事故。</p>
 *
 * <p><b>为什么不用 ThreadLocal 存：</b>Servlet 容器线程池复用，ThreadLocal 必须在
 * finally 里清理，漏一处就串号。这里改成每次从 {@link RequestContextHolder} 现取，
 * 零清理负担，且天然支持异步 Servlet。</p>
 *
 * <p><b>安全底线：</b>服务层拿到 userId 后，**所有涉及归属的 SQL 必须带
 * {@code WHERE user_id = currentUserId}**。网关只能证明「你是谁」，不能证明
 * 「这条数据是不是你的」——越权校验必须落在服务层（基线 §7）。</p>
 *
 * @author oj-cloud
 */
public final class UserContext {

    /** 网关注入的用户 ID 头 */
    public static final String HEADER_USER_ID = "X-User-Id";

    /** 网关注入的角色头，取值 STUDENT / TEACHER / ADMIN */
    public static final String HEADER_USER_ROLE = "X-User-Role";

    /** 网关注入的班级 ID 头，管理员为空 */
    public static final String HEADER_CLASS_ID = "X-Class-Id";

    private UserContext() {
    }

    /** 取当前请求，非 Web 线程（定时任务/MQ 消费者）返回 null */
    private static HttpServletRequest request() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }

    /**
     * 取当前用户 ID。
     *
     * @return 用户 ID；未登录（无网关头）返回 {@code null}
     */
    public static Long userIdOrNull() {
        HttpServletRequest req = request();
        if (req == null) {
            return null;
        }
        String raw = req.getHeader(HEADER_USER_ID);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 取当前用户 ID，未登录直接抛 401。
     *
     * <p>业务接口用它而不是 {@link #userIdOrNull()}：这样「忘了判空导致
     * {@code WHERE user_id = null} 查出空结果」这类静默 bug 会变成显式 401。</p>
     *
     * @return 用户 ID（绝不为 null）
     * @throws BusinessException 401 未登录
     */
    public static Long userId() {
        Long id = userIdOrNull();
        if (id == null) {
            throw BusinessException.unauthorized("未登录或 Token 失效");
        }
        return id;
    }

    /**
     * 取当前角色。
     *
     * @return STUDENT / TEACHER / ADMIN；未登录返回 {@code null}
     */
    public static String roleOrNull() {
        HttpServletRequest req = request();
        if (req == null) {
            return null;
        }
        String raw = req.getHeader(HEADER_USER_ROLE);
        return (raw == null || raw.isBlank()) ? null : raw.trim().toUpperCase();
    }

    /**
     * 取当前角色，未登录抛 401。
     *
     * @return 角色（绝不为 null）
     */
    public static String role() {
        String r = roleOrNull();
        if (r == null) {
            throw BusinessException.unauthorized("未登录或 Token 失效");
        }
        return r;
    }

    /**
     * 取当前班级 ID（管理员 / 教师可能为空）。
     *
     * @return 班级 ID 或 {@code null}
     */
    public static Long classIdOrNull() {
        HttpServletRequest req = request();
        if (req == null) {
            return null;
        }
        String raw = req.getHeader(HEADER_CLASS_ID);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 是否学生 */
    public static boolean isStudent() {
        return RoleConstants.STUDENT.equals(roleOrNull());
    }

    /** 是否教师（含管理员） */
    public static boolean isTeacherOrAbove() {
        return RoleConstants.isTeacherOrAbove(roleOrNull());
    }

    /** 是否管理员 */
    public static boolean isAdmin() {
        return RoleConstants.isAdmin(roleOrNull());
    }

    /**
     * 断言当前用户是教师或管理员，否则抛 403。
     *
     * @param action 动作描述，拼进错误文案（如「发布题目」）
     */
    public static void requireTeacherOrAbove(String action) {
        if (!isTeacherOrAbove()) {
            throw BusinessException.forbidden("没有权限" + (action == null ? "" : action));
        }
    }

    /**
     * 断言当前用户是管理员，否则抛 403。
     *
     * @param action 动作描述
     */
    public static void requireAdmin(String action) {
        if (!isAdmin()) {
            throw BusinessException.forbidden("没有权限" + (action == null ? "" : action));
        }
    }
}
