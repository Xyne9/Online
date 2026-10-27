package com.oj.admin.support;

import com.oj.admin.config.AdminProperties;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.jwt.RoleConstants;
import com.oj.common.web.context.UserContext;
import org.springframework.stereotype.Component;

/**
 * {@code /api/admin/**} 的统一准入闸门。
 *
 * <p><b>为什么服务层还要再校验一次</b>（agent.md §4.4）：网关只能证明「你是谁」
 * （校验 JWT 并注入 {@code X-User-Role}），**不能证明「你有没有这个权限」**——
 * 而且本机允许直连 8085 调试，绕开网关时根本没有任何角色头。
 * 所以每个端点进来第一件事就是过这道闸。</p>
 *
 * <p>用 {@link UserContext#role()}：未登录（无 {@code X-User-Role}）抛 401，
 * 有角色但不是 ADMIN 抛 403，两者语义不能混。</p>
 *
 * @author oj-cloud
 */
@Component
public class AdminGuard {

    private final AdminProperties properties;

    public AdminGuard(AdminProperties properties) {
        this.properties = properties;
    }

    /**
     * 断言当前调用者是管理员。
     *
     * <p><b>401 与 403 必须严格分开</b>（契约 §2.8 / 基线 §7）：</p>
     * <ul>
     *   <li>没有任何身份头 → <b>401</b>「未登录或 Token 失效」：
     *       调用方连「你是谁」都没证明，让他重登才有意义；</li>
     *   <li>有身份但不是 ADMIN → <b>403</b>「没有权限」：
     *       身份已确认，是这个身份不该碰这个资源。</li>
     * </ul>
     *
     * <p>历史上这里一律抛 403，看起来只是语义不精确，实际后果不小：
     * 前端 `api/http.js` 收到 401 会清 token 并跳登录页（正确的 UX），
     * 收到 403 只弹「没有权限」——<b>token 真过期时用户会卡在页面上反复点</b>，
     * 永远等不到自动跳登录。所以这两个码不能图省事合并。</p>
     *
     * @param action 动作描述，拼进 403 文案（如「查看模型通道」）
     * @throws BusinessException 401 未登录 / 403 非管理员
     */
    public void requireAdmin(String action) {
        String role = UserContext.roleOrNull();
        if (role == null || role.isBlank()) {
            throw BusinessException.unauthorized("未登录或 Token 失效");
        }
        if (!RoleConstants.isAdmin(role)) {
            throw BusinessException.forbidden("没有权限" + (action == null ? "" : action));
        }
    }

    /**
     * 断言当前调用者是管理员，但允许本地调试开关放行匿名请求。
     *
     * <p>放行仅在 {@code oj.admin.allow-anonymous-local=true} 时生效，**默认关闭**：
     * 这个开关只为「服务刚起、还没有网关 / 还没有 admin token 时用 curl 自检」存在，
     * 一旦默认放行就等于线上越权。</p>
     *
     * @param action 动作描述
     */
    public void requireAdminAllowingLocal(String action) {
        if (properties.isAllowAnonymousLocal() && UserContext.roleOrNull() == null) {
            return;
        }
        requireAdmin(action);
    }
}
