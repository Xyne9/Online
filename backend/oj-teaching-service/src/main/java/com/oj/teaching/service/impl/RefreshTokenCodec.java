package com.oj.teaching.service.impl;

import com.oj.teaching.constant.AuthConstants;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Refresh Token 编解码器（无状态工具类，便于单独验证）。
 *
 * <p><b>令牌格式：</b>{@code rt_} + Base64URL({@code userId:jti:随机串})，去 padding。</p>
 *
 * <p><b>为什么把 userId 编进令牌：</b>Redis 里的 key 是
 * {@code oj:auth:refresh:{userId}}，而续期请求里只有令牌本身。
 * 不把 userId 编进去，就得为「令牌 → 用户」再维护一张反向索引，
 * 等于凭空多一个可能与 Redis 不一致的数据源。</p>
 *
 * <p><b>为什么外层看不出结构：</b>整体 Base64URL 后形如 {@code rt_eyJ1c2Vy...}，
 * 与契约示例 {@code rt_9f2c...} 同族，且不含可直接读出的用户信息。</p>
 *
 * <p><b>它不是密码学令牌：</b>真正的防伪靠「与 Redis 逐字比对」——令牌是否被伪造
 * 根本不影响判定，因为伪造出来的值不可能等于 Redis 里的那一份。因此这里不签名、
 * 不做 HMAC，也不引入额外依赖。</p>
 *
 * @author oj-cloud
 */
final class RefreshTokenCodec {

    private RefreshTokenCodec() {
    }

    /**
     * 令牌本体与其 jti。
     *
     * @param token 完整令牌串（含 {@code rt_} 前缀）
     * @param jti   令牌唯一 ID，同时写进 Redis，续期时逐字比对
     */
    record Issued(String token, String jti) {
    }

    /**
     * 签发一枚新的 refresh token。
     *
     * @param userId 用户 ID
     * @return 令牌串与 jti
     */
    static Issued issue(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        String jti = UUID.randomUUID().toString().replace("-", "");
        String random = UUID.randomUUID().toString().replace("-", "");
        String raw = userId + AuthConstants.REFRESH_TOKEN_SEPARATOR
                + jti + AuthConstants.REFRESH_TOKEN_SEPARATOR + random;
        String token = AuthConstants.REFRESH_TOKEN_PREFIX
                + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return new Issued(token, jti);
    }

    /**
     * 解析一枚 refresh token。
     *
     * @param token 完整令牌串
     * @return 令牌内容（userId 与 jti）
     * @throws IllegalArgumentException 前缀缺失 / Base64 非法 / 段数不足 / userId 非数字
     */
    static Parsed parse(String token) {
        if (token == null || !token.startsWith(AuthConstants.REFRESH_TOKEN_PREFIX)) {
            throw new IllegalArgumentException("refresh token 前缀非法");
        }
        String body = token.substring(AuthConstants.REFRESH_TOKEN_PREFIX.length());
        String decoded = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8);
        String[] parts = decoded.split(AuthConstants.REFRESH_TOKEN_SEPARATOR, 3);
        if (parts.length < 3) {
            throw new IllegalArgumentException("refresh token 结构不完整");
        }
        return new Parsed(Long.valueOf(parts[0]), parts[1]);
    }

    /**
     * 解析结果。
     *
     * @param userId 用户 ID
     * @param jti    令牌唯一 ID
     */
    record Parsed(Long userId, String jti) {
    }
}
