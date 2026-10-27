package com.oj.gateway.config;

import com.oj.common.core.jwt.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * 网关 JWT 初始化配置。
 *
 * <p>{@link JwtUtil} 是静态工具类（网关与 5 个 Servlet 服务共用），因此需要在启动时
 * 用配置里的密钥「喂」它一次。密钥优先取环境变量 {@code OJ_JWT_SECRET}，
 * 其次取 {@code oj.auth.jwt-secret}，都没配则退回 {@link JwtUtil#DEFAULT_DEV_SECRET}。</p>
 *
 * @author oj-cloud
 */
@Configuration
public class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    /** JWT 密钥：生产必须由环境变量注入（≥32 字节） */
    @Value("${oj.auth.jwt-secret:}")
    private String jwtSecret;

    /** Access Token 有效期（秒），默认 24h，与 Redis TTL 对齐 */
    @Value("${oj.auth.jwt-expire-seconds:86400}")
    private long expireSeconds;

    /**
     * 启动时把配置写入静态工具类。
     *
     * <p>用 SLF4J 而非 {@code System.err}：一是能进统一日志文件，二是 Windows 控制台
     * 默认 GBK，直接用 {@code System.err} 打中文会乱码。</p>
     */
    @PostConstruct
    public void init() {
        String envSecret = System.getenv("OJ_JWT_SECRET");
        String realSecret = (envSecret != null && !envSecret.isBlank()) ? envSecret : jwtSecret;
        if (realSecret == null || realSecret.isBlank()) {
            // 未配置：使用开发默认密钥，并在启动日志里明确提示（生产环境必须覆盖）
            log.warn("[oj-gateway] 警告：未配置 oj.auth.jwt-secret / OJ_JWT_SECRET，"
                    + "已回退到开发默认密钥，仅限本地联调使用！");
            JwtUtil.configure(JwtUtil.DEFAULT_DEV_SECRET, expireSeconds);
        } else {
            JwtUtil.configure(realSecret, expireSeconds);
            log.info("[oj-gateway] JWT 密钥已从配置加载，有效期 {} 秒", expireSeconds);
        }
    }
}
