package com.oj.common.core.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JWT 工具类（设计文档 §2.7 载荷 schema / 《02-契约补全规范》§2.7）。
 *
 * <p><b>算法固定 HS256</b>，Header 恒为 {@code {"alg":"HS256","typ":"JWT"}}。</p>
 *
 * <p>载荷字段（与 §2.7 逐字对齐）：</p>
 * <table border="1">
 *   <caption>JWT Payload</caption>
 *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
 *   <tr><td>sub</td><td>string</td><td>用户名 sys_user.username</td></tr>
 *   <tr><td>uid</td><td>long</td><td>用户 ID，服务层越权校验用</td></tr>
 *   <tr><td>role</td><td>string</td><td>STUDENT / TEACHER / ADMIN</td></tr>
 *   <tr><td>cid</td><td>long|null</td><td>班级 ID；教师为所带班，管理员为 null</td></tr>
 *   <tr><td>jti</td><td>string</td><td>令牌唯一 ID（UUID），与 Redis value 内 jti 比对</td></tr>
 *   <tr><td>iat / exp</td><td>long</td><td>秒级时间戳，exp - iat = 86400（24h）</td></tr>
 * </table>
 *
 * <p><b>密钥纪律：</b>生产必须通过环境变量注入 {@code oj.auth.jwt-secret}（≥32 字节），
 * 绝不允许把真实密钥提交进 Git；本类的 {@link #DEFAULT_DEV_SECRET} 仅供第 2 步骨架联调与本地开发，
 * 上线前必须被配置覆盖（{@code oj.auth.jwt-secret} 一配就生效）。</p>
 *
 * <p><b>为什么是静态工具类：</b>网关（WebFlux）与 5 个 Servlet 服务都要用，
 * 做成 Spring Bean 会逼着每个服务都扫到本包；静态方法 + 静态可配密钥，
 * 既能在 {@code @ConfigurationProperties} 里初始化，也能在过滤器里直接用。</p>
 *
 * @author oj-cloud
 */
public final class JwtUtil {

    /** Access Token 默认有效期（秒）：24 小时，与 Redis oj:auth:token TTL 一致 */
    public static final long DEFAULT_EXPIRE_SECONDS = 24 * 60 * 60L;

    /**
     * 开发用默认密钥（≥32 字节，满足 HS256 要求）。
     * <p><b>仅供本地/答辩演示</b>，生产环境必须由 {@code oj.auth.jwt-secret} 覆盖。</p>
     */
    public static final String DEFAULT_DEV_SECRET =
            "oj-cloud-dev-secret-key-please-override-in-production-2026";

    /** 当前生效的密钥（可通过 {@link #configure} 覆盖） */
    private static volatile String secret = DEFAULT_DEV_SECRET;

    /** 当前生效的默认有效期（秒） */
    private static volatile long defaultExpireSeconds = DEFAULT_EXPIRE_SECONDS;

    private JwtUtil() {
    }

    /**
     * 配置密钥与有效期（由各服务的 {@code JwtConfig} 在启动时调用）。
     *
     * @param newSecret        密钥明文，长度必须 ≥ 32 字节
     * @param expireSeconds    默认有效期（秒），≤0 时保持 24 小时
     */
    public static void configure(String newSecret, long expireSeconds) {
        if (newSecret != null && newSecret.getBytes(StandardCharsets.UTF_8).length >= 32) {
            secret = newSecret;
        }
        if (expireSeconds > 0) {
            defaultExpireSeconds = expireSeconds;
        }
    }

    /** 取当前密钥的 HMAC-SHA 签名 Key */
    private static SecretKey signKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    // ==================================================================
    // 签发
    // ==================================================================

    /**
     * 签发 Access Token（最常用）。
     *
     * @param userId   用户 ID → 载荷 uid
     * @param username 用户名 → 载荷 sub
     * @param role     STUDENT / TEACHER / ADMIN
     * @param classId  班级 ID，可为 null（管理员）
     * @return 完整 JWT 字符串
     */
    public static String createToken(Long userId, String username, String role, Long classId) {
        return createToken(userId, username, role, classId, defaultExpireSeconds);
    }

    /**
     * 签发 Access Token（自定义有效期，用于 SSE 短期票据等场景）。
     *
     * @param userId        用户 ID
     * @param username      用户名
     * @param role          角色
     * @param classId       班级 ID，可为 null
     * @param expireSeconds 有效期（秒）
     * @return 完整 JWT 字符串
     */
    public static String createToken(Long userId, String username, String role,
                                     Long classId, long expireSeconds) {
        long now = System.currentTimeMillis();
        Map<String, Object> claims = new HashMap<>(8);
        claims.put("uid", userId);
        claims.put("role", role);
        if (classId != null) {
            claims.put("cid", classId);
        }
        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date(now))
                .expiration(new Date(now + expireSeconds * 1000L))
                .signWith(signKey(), Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 用自定义 claims 签发（培训/测试场景，或需要额外字段时使用）。
     *
     * @param claims        自定义载荷（会与标准字段合并）
     * @param subject       主体（用户名）
     * @param expireSeconds 有效期（秒）
     * @return 完整 JWT 字符串
     */
    public static String createToken(Map<String, Object> claims, String subject, long expireSeconds) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .claims(claims == null ? new HashMap<>() : claims)
                .subject(subject)
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date(now))
                .expiration(new Date(now + expireSeconds * 1000L))
                .signWith(signKey(), Jwts.SIG.HS256)
                .compact();
    }

    // ==================================================================
    // 解析 / 校验
    // ==================================================================

    /**
     * 解析并验签。签名错、格式错、已过期都会抛 {@link JwtException}。
     *
     * @param token JWT 字符串
     * @return 载荷
     * @throws JwtException 令牌非法或已过期
     */
    public static Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 校验令牌是否有效（不抛异常版本，供网关过滤器使用）。
     *
     * @param token JWT 字符串
     * @return true 表示签名有效且未过期
     */
    public static boolean validate(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 去除 {@code Bearer } 前缀（大小写不敏感），得到裸 token。
     *
     * @param authorization Authorization 头原值
     * @return 裸 token；入参为空或不是 Bearer 格式时返回 null
     */
    public static String resolveBearer(String authorization) {
        if (authorization == null) {
            return null;
        }
        String value = authorization.trim();
        if (value.length() > 7 && value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = value.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    // ==================================================================
    // 载荷读取
    // ==================================================================

    /** 取用户 ID（uid），缺省返回 null */
    public static Long getUserId(Claims claims) {
        Object uid = claims.get("uid");
        if (uid == null) {
            return null;
        }
        return Long.valueOf(String.valueOf(uid));
    }

    /** 取角色（role），缺省返回 null */
    public static String getRole(Claims claims) {
        Object role = claims.get("role");
        return role == null ? null : String.valueOf(role);
    }

    /** 取班级 ID（cid），管理员为 null */
    public static Long getClassId(Claims claims) {
        Object cid = claims.get("cid");
        if (cid == null) {
            return null;
        }
        return Long.valueOf(String.valueOf(cid));
    }

    /** 取令牌唯一 ID（jti），网关与 Redis 中的 jti 逐字比对 */
    public static String getJti(Claims claims) {
        return claims.getId();
    }

    /** 取过期时间（秒级时间戳） */
    public static long getExpireAtSeconds(Claims claims) {
        Date exp = claims.getExpiration();
        return exp == null ? 0L : exp.getTime() / 1000L;
    }
}
