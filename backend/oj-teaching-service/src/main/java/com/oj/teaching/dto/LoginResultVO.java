package com.oj.teaching.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 登录成功响应体（契约 §2.2 {@code data}）。
 *
 * <pre>
 * {"accessToken":"eyJhbGciOi...","refreshToken":"rt_9f2c...","expiresIn":86400,
 *  "user":{...},"homePath":"s-problems.html"}
 * </pre>
 *
 * @author oj-cloud
 */
@Data
public class LoginResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** JWT Access Token（HS256，载荷见契约 §2.7） */
    private String accessToken;

    /** 刷新令牌（不含分隔符的 Base64URL 串，形如 {@code rt_...}） */
    private String refreshToken;

    /** Access Token 有效期（秒），契约示例 86400 */
    private Long expiresIn;

    /** 用户卡 */
    private LoginUserVO user;

    /** 按角色下发的落地页（前端不该硬编码） */
    private String homePath;
}
