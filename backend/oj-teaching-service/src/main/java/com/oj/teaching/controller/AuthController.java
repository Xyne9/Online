package com.oj.teaching.controller;

import com.oj.common.core.result.Result;
import com.oj.teaching.dto.CurrentUserVO;
import com.oj.teaching.dto.LoginRequest;
import com.oj.teaching.dto.LoginResultVO;
import com.oj.teaching.dto.LogoutRequest;
import com.oj.teaching.dto.MenuVO;
import com.oj.teaching.dto.RefreshRequest;
import com.oj.teaching.dto.RefreshResultVO;
import com.oj.teaching.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证与用户控制器（契约 §2 的 5 个端点，服务端口 8081，网关前缀 {@code /api/auth}）。
 *
 * <p><b>本类只做三件事：</b>取请求信息（IP）、把 body 交给 {@link AuthService}、
 * 把结果包成 {@link Result}。所有业务判断（BCrypt、限流、Redis、角色落地页）
 * 都在服务层——这样换协议（比如给 {@code /inner/**} 或定时任务复用）时不必动 HTTP 层。</p>
 *
 * <p><b>为什么 {@code code} 与 HTTP 状态码能严格同值：</b>失败一律靠
 * {@code BusinessException} 抛出，由 {@code oj-common-web} 的
 * {@code GlobalExceptionHandler} 统一转 {@code ResponseEntity}，
 * 状态码取自 {@code ErrorCode}。本类因此<b>不写任何 try/catch</b>——
 * 一旦在 Controller 里 catch 掉异常再自己拼 {@code Result.fail}，
 * HTTP 状态码就会退化成 200，前端 {@code api/http.js:93} 的 401 拦截当场失效。</p>
 *
 * <p><b>网关白名单：</b>{@code /api/auth/login} 与 {@code /api/auth/refresh} 在
 * {@code oj-gateway/application.yml} 的白名单里，无需 Bearer 即可访问；
 * {@code /logout}、{@code /me}、{@code /menus} 需要网关注入的
 * {@code X-User-Id}（{@code UserContext} 只认这三个头，不再自己解 JWT）。</p>
 *
 * @author oj-cloud
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** 代理链头：网关（Spring Cloud Gateway）默认会把客户端 IP 追加进 X-Forwarded-For */
    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";

    /** 另一种常见的反代头（Nginx 默认） */
    private static final String HEADER_REAL_IP = "X-Real-IP";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * #1 登录（契约 §2.2）。
     *
     * <pre>
     * POST /api/auth/login
     * {"username":"20220101","password":"******","rememberMe":false}
     * </pre>
     *
     * @param request     登录请求；{@code username} 与前端实际发送的 {@code account} 都接受
     * @param httpRequest 用于取客户端 IP 做每分钟 10 次限流
     * @return access/refresh token、用户卡、角色落地页
     */
    @PostMapping("/login")
    public Result<LoginResultVO> login(@RequestBody LoginRequest request,
                                       HttpServletRequest httpRequest) {
        return Result.ok(authService.login(request, resolveClientIp(httpRequest)));
    }

    /**
     * #2 注销（契约 §2.3），幂等。
     *
     * <pre>POST /api/auth/logout  {"allDevices":false}</pre>
     *
     * <p>body 可省略：{@code @RequestBody(required = false)} 让「只发一个空 body 或干脆不发」
     * 也能正常注销——前端 {@code api/auth.js:73} 的 logout 目前就是清完 localStorage 直接跳转，
     * 若这里强制要 body，一个「退出登录」按钮就会 400。</p>
     *
     * @param request 注销请求，可为 null
     * @return {@code {"loggedOut":true}}
     */
    @PostMapping("/logout")
    public Result<Map<String, Object>> logout(@RequestBody(required = false) LogoutRequest request) {
        boolean allDevices = request != null && request.allDevicesFlag();
        authService.logout(allDevices);
        Map<String, Object> data = new LinkedHashMap<>(2);
        data.put("loggedOut", true);
        return Result.ok(data);
    }

    /**
     * #3 续期（契约 §2.4）。轮换策略：每次刷新同时签发新 refresh 并覆盖 Redis。
     *
     * <pre>POST /api/auth/refresh  {"refreshToken":"rt_..."}</pre>
     *
     * @param request 续期请求
     * @return 新 access + 新 refresh + expiresIn
     */
    @PostMapping("/refresh")
    public Result<RefreshResultVO> refresh(@RequestBody RefreshRequest request) {
        return Result.ok(authService.refresh(request == null ? null : request.getRefreshToken()));
    }

    /**
     * #4 当前用户（契约 §2.5）。
     *
     * @return 用户卡（含班级名、薄弱标签、最近登录时刻）
     */
    @GetMapping("/me")
    public Result<CurrentUserVO> me() {
        return Result.ok(authService.currentUser());
    }

    /**
     * #5 导航菜单（契约 §2.6）。
     *
     * @return 按角色下发的导航树与实时角标
     */
    @GetMapping("/menus")
    public Result<MenuVO> menus() {
        return Result.ok(authService.menus());
    }

    /**
     * 解析客户端真实 IP（用于登录限流）。
     *
     * <p><b>取值顺序：</b>{@code X-Forwarded-For} 首段 → {@code X-Real-IP} → {@code getRemoteAddr()}。
     * 经网关转发时 {@code getRemoteAddr()} 永远是网关自己的地址，若只用它，
     * 全站所有用户会共享同一个限流桶（10 次/分钟后所有人一起被锁），
     * 所以必须优先读代理头。</p>
     *
     * <p><b>安全边界：</b>这两个头可被客户端伪造，因此本方法只在「请求确实来自可信网关」的
     * 前提下才可信。本期服务只经网关暴露（8081 不对外），可接受；若将来直连暴露，
     * 需要在网关侧覆盖写（而不是追加）该头。</p>
     *
     * @param request 当前请求
     * @return 客户端 IP，永不为 null
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(HEADER_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            // X-Forwarded-For: client, proxy1, proxy2 —— 第一段才是真实客户端
            int comma = forwarded.indexOf(',');
            String first = (comma > 0) ? forwarded.substring(0, comma) : forwarded;
            if (!first.isBlank()) {
                return first.trim();
            }
        }
        String realIp = request.getHeader(HEADER_REAL_IP);
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String remote = request.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? "unknown" : remote;
    }
}
