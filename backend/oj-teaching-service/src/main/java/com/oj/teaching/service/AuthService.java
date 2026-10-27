package com.oj.teaching.service;

import com.oj.teaching.dto.CurrentUserVO;
import com.oj.teaching.dto.LoginRequest;
import com.oj.teaching.dto.LoginResultVO;
import com.oj.teaching.dto.MenuVO;
import com.oj.teaching.dto.RefreshResultVO;

/**
 * 认证服务（契约 §2 的 5 个端点背后的业务逻辑）。
 *
 * <p>接口与实现分开，是为了让 {@code AuthController} 只做「HTTP → 领域」的翻译：
 * 取 IP、解 body、包 {@code Result}，一行业务判断都不写。这样后续若要给
 * {@code /inner/**} 或定时任务复用登录校验，直接注入本接口即可，不必依赖 Servlet 上下文。</p>
 *
 * @author oj-cloud
 */
public interface AuthService {

    /**
     * 账号密码登录（契约 §2.2）。
     *
     * @param request  登录请求（username/account + password + rememberMe）
     * @param clientIp 调用方 IP，用于每分钟 10 次的登录限流；可为 null（此时跳过限流）
     * @return access/refresh token、用户卡与角色落地页
     * @throws com.oj.common.core.exception.BusinessException 400 / 401 / 403 / 429
     */
    LoginResultVO login(LoginRequest request, String clientIp);

    /**
     * 注销（契约 §2.3），幂等。
     *
     * @param allDevices true 时额外删除 refresh token（清空该用户全部会话）
     * @throws com.oj.common.core.exception.BusinessException 401 未登录
     */
    void logout(boolean allDevices);

    /**
     * 用 refresh token 换新 access token，并轮换 refresh（契约 §2.4）。
     *
     * @param refreshToken 登录时下发的刷新令牌
     * @return 新 access + 新 refresh
     * @throws com.oj.common.core.exception.BusinessException 401 失效 / 403 用户已禁用
     */
    RefreshResultVO refresh(String refreshToken);

    /**
     * 当前用户（契约 §2.5）。
     *
     * @return 用户卡（含班级名与薄弱标签）
     * @throws com.oj.common.core.exception.BusinessException 401 未登录
     */
    CurrentUserVO currentUser();

    /**
     * 按角色下发侧边导航与实时角标（契约 §2.6）。
     *
     * @return 导航树 + roleHint
     * @throws com.oj.common.core.exception.BusinessException 401 未登录
     */
    MenuVO menus();
}
