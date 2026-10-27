package com.oj.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关鉴权配置（{@code oj.gateway.*}）。
 *
 * <p><b>为什么要有 {@link #redisCheckEnabled} 这个开关：</b>
 * 设计文档要求「JWT 校验 + Redis 在线态逐字比对」，但 B/C 阶段（第 2/3 步）
 * 5 个服务还没有任何登录实现，Redis 里不存在 {@code oj:auth:token:{uid}}。
 * 若此刻强制查 Redis，<b>所有</b>请求都会被判 401，Day2 的验收 curl 会全红。
 * 于是默认关闭（{@code false}），等第 4 步认证接口落地后再置为 {@code true}。</p>
 *
 * @author oj-cloud
 */
@Component
@ConfigurationProperties(prefix = "oj.gateway")
public class GatewayAuthProperties {

    /** 是否启用 Redis 在线态校验；Day2 骨架默认 false，登录功能上线后置 true */
    private boolean redisCheckEnabled = false;

    /** 是否启用 JWT 校验（关闭则网关退化为纯转发，仅供本地排障） */
    private boolean jwtCheckEnabled = true;

    /** 白名单路径（Ant 风格），命中即直放，不校验 token */
    private List<String> whitelist = new ArrayList<>(List.of(
            "/api/auth/login",
            "/api/auth/refresh",
            "/actuator/**",
            "/api/*/health"
    ));

    /** Redis 中校验的在线态 key 前缀，必须与 {@code RedisKeys.authToken()} 保持一致 */
    private String tokenKeyPrefix = "oj:auth:token:";

    public boolean isRedisCheckEnabled() {
        return redisCheckEnabled;
    }

    public void setRedisCheckEnabled(boolean redisCheckEnabled) {
        this.redisCheckEnabled = redisCheckEnabled;
    }

    public boolean isJwtCheckEnabled() {
        return jwtCheckEnabled;
    }

    public void setJwtCheckEnabled(boolean jwtCheckEnabled) {
        this.jwtCheckEnabled = jwtCheckEnabled;
    }

    public List<String> getWhitelist() {
        return whitelist;
    }

    public void setWhitelist(List<String> whitelist) {
        this.whitelist = whitelist;
    }

    public String getTokenKeyPrefix() {
        return tokenKeyPrefix;
    }

    public void setTokenKeyPrefix(String tokenKeyPrefix) {
        this.tokenKeyPrefix = tokenKeyPrefix;
    }
}
