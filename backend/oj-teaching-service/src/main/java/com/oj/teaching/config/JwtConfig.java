package com.oj.teaching.config;

import com.oj.common.core.jwt.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * 教务服务 JWT 初始化（与 {@code oj-gateway/config/JwtConfig} 同一套口径）。
 *
 * <p>{@link JwtUtil} 是静态工具类，网关与 5 个 Servlet 服务共用同一份签名密钥，
 * 因此每个进程启动时都必须把配置「喂」进去一次，否则会出现
 * 「登录服务用默认密钥签发、网关用环境变量密钥验签 → 全站 401」这种最难查的事故。</p>
 *
 * <p>密钥取值优先级：环境变量 {@code OJ_JWT_SECRET} &gt; 配置项 {@code oj.auth.jwt-secret}
 * &gt; {@link JwtUtil#DEFAULT_DEV_SECRET}（仅本地联调，启动时打 WARN）。</p>
 *
 * @author oj-cloud
 */
@Configuration
public class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    /** JWT 密钥：生产必须由环境变量注入（≥32 字节） */
    @Value("${oj.auth.jwt-secret:}")
    private String jwtSecret;

    /** Access Token 有效期（秒），默认 24h，与 Redis oj:auth:token TTL 对齐 */
    @Value("${oj.auth.jwt-expire-seconds:86400}")
    private long expireSeconds;

    /**
     * 启动时把密钥与有效期写入静态工具类。
     */
    @PostConstruct
    public void init() {
        String envSecret = System.getenv("OJ_JWT_SECRET");
        String realSecret = (envSecret != null && !envSecret.isBlank()) ? envSecret : jwtSecret;
        if (realSecret == null || realSecret.isBlank()) {
            log.warn("[oj-teaching-service] 警告：未配置 oj.auth.jwt-secret / OJ_JWT_SECRET，"
                    + "已回退到开发默认密钥，仅限本地联调使用！");
            JwtUtil.configure(JwtUtil.DEFAULT_DEV_SECRET, expireSeconds);
        } else {
            JwtUtil.configure(realSecret, expireSeconds);
            log.info("[oj-teaching-service] JWT 密钥已从配置加载，有效期 {} 秒", expireSeconds);
        }
    }

    /**
     * 当前生效的 Access Token 有效期（秒）。
     *
     * <p>登录/续期响应里的 {@code expiresIn} 必须与真正签进 {@code exp} 的值一致，
     * 所以只能读这里，不能在业务代码里写死 86400——否则配置一改，
     * 前端按 {@code expiresIn} 算的续期时机就与真实过期时间错位。</p>
     *
     * @return 有效期秒数（&gt;0）
     */
    public long getExpireSeconds() {
        return expireSeconds > 0 ? expireSeconds : JwtUtil.DEFAULT_EXPIRE_SECONDS;
    }
}
