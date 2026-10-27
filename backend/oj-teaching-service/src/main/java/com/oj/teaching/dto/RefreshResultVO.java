package com.oj.teaching.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 续期响应体（契约 §2.4 {@code data}）。
 *
 * <pre>{"accessToken":"eyJhbGciOi...","refreshToken":"rt_3a71...","expiresIn":86400}</pre>
 *
 * @author oj-cloud
 */
@Data
public class RefreshResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 新签发的 Access Token */
    private String accessToken;

    /** 轮换后的新 refresh token（旧值立即失效，防重放） */
    private String refreshToken;

    /** 新 Access Token 的有效期（秒） */
    private Long expiresIn;
}
