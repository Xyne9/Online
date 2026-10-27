package com.oj.admin.support;

import com.oj.admin.config.AdminProperties;
import com.oj.common.core.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /api/admin/**} 准入闸门的权限回归测试。
 *
 * <p><b>为什么必须锁住这条</b>：验收实测发现学生 / 教师 token 访问
 * {@code /api/admin/health} 曾返回 <b>200</b>（旧空壳没有做任何校验）。
 * 而该路径命中了网关白名单 {@code /api/*&#47;health}，请求会绕过 JWT 直达服务、
 * 且网关注入的 {@code X-User-Role} 根本不存在——所以权限只能由服务层判定。
 * 本测试把三种身份（无头 / 学生 / 管理员）的期望码钉死，防止再次回退。</p>
 *
 * <p>不需要启动 Web 容器：直接构造 {@link MockHttpServletRequest} 塞进
 * {@link RequestContextHolder}，与 {@code UserContext} 的读取路径完全一致。</p>
 *
 * @author oj-cloud
 */
class AdminGuardTest {

    private final AdminProperties properties = new AdminProperties();
    private final AdminGuard guard = new AdminGuard(properties);

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 模拟网关注入的身份头 */
    private void givenIdentity(Long userId, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (userId != null) {
            request.addHeader("X-User-Id", String.valueOf(userId));
        }
        if (role != null) {
            request.addHeader("X-User-Role", role);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    @DisplayName("无身份头（网关白名单放行的场景）→ 401，绝不 200")
    void noIdentityIsUnauthorized() {
        givenIdentity(null, null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.requireAdmin("查看全站健康"));
        assertEquals(401, ex.getCode(), "没有 X-User-Role 时应是 401（未登录），而不是 403 或 200");
    }

    @Test
    @DisplayName("学生 token → 403")
    void studentIsForbidden() {
        givenIdentity(1L, "STUDENT");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.requireAdmin("查看全站健康"));
        assertEquals(403, ex.getCode(), "学生访问管理端点必须是 403");
        assertTrue(ex.getMessage().contains("查看全站健康"), "403 文案应带动作描述，便于前端提示");
    }

    @Test
    @DisplayName("教师 token → 403（教师不是管理员）")
    void teacherIsForbidden() {
        givenIdentity(2L, "TEACHER");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.requireAdmin("查看全站健康"));
        assertEquals(403, ex.getCode(), "教师访问管理端点必须是 403");
    }

    @Test
    @DisplayName("管理员 token → 放行")
    void adminPasses() {
        givenIdentity(3L, "ADMIN");
        assertDoesNotThrow(() -> guard.requireAdmin("查看全站健康"));
    }

    @Test
    @DisplayName("角色大小写不敏感（网关透传的可能是小写）")
    void roleIsCaseInsensitive() {
        givenIdentity(3L, "admin");
        assertDoesNotThrow(() -> guard.requireAdmin("查看全站健康"));
    }

    @Test
    @DisplayName("本地调试开关打开时，匿名请求放行；关闭时仍 401")
    void localBypassOnlyWhenEnabled() {
        givenIdentity(null, null);
        // 默认关闭：匿名必须 401
        assertEquals(401, assertThrows(BusinessException.class,
                () -> guard.requireAdminAllowingLocal("自检")).getCode());

        // 打开后可放行（仅供本机 curl 自检）
        properties.setAllowAnonymousLocal(true);
        assertDoesNotThrow(() -> guard.requireAdminAllowingLocal("自检"));

        // 但「有身份但不是管理员」时，开关不能成为越权后门
        givenIdentity(1L, "STUDENT");
        assertEquals(403, assertThrows(BusinessException.class,
                () -> guard.requireAdminAllowingLocal("自检")).getCode());
    }
}
