package com.oj.teaching.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 登录请求体（契约 §2.2 请求参数表）。
 *
 * <p><b>字段兼容：</b>契约写的是 {@code username}，而前端
 * {@code prototype/app/api/auth.js:49} 发的是 {@code account}。两者必须同时接受，
 * 否则真实登录会 400。取值逻辑见 {@link #resolveUsername()}。</p>
 *
 * <p><b>为什么不在本类加 {@code @NotBlank}：</b>两个字段是「二选一」关系，
 * 各加一个 {@code @NotBlank} 会变成「两个都必须填」，反而把合法请求挡掉。
 * 长度与空值校验统一放在 {@code AuthServiceImpl#login} 里做，
 * 这样错误文案是完整中文（「账号不能为空」），而不是
 * {@code @Valid} 产出的「username 不能为空」这种带英文字段名的拼接。</p>
 *
 * @author oj-cloud
 */
@Data
public class LoginRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 学号 / 工号 / 登录账号（契约字段名，2–32 字符） */
    private String username;

    /** 前端实际发送的账号字段名（与 {@link #username} 等价，二者取其一） */
    private String account;

    /** 明文密码（6–64 字符）；服务端只做 BCrypt 校验，绝不落库、绝不写日志 */
    private String password;

    /** true 时 refresh token 有效期 7 天，否则 12 小时（契约 §2.2） */
    private Boolean rememberMe;

    /**
     * 取真实账号：优先 {@code username}，回退 {@code account}，都为空返回 {@code null}。
     *
     * @return 去首尾空白的账号，或 null
     */
    public String resolveUsername() {
        String raw = (username != null && !username.isBlank()) ? username : account;
        return (raw == null || raw.isBlank()) ? null : raw.trim();
    }

    /** rememberMe 的空安全取值：null 视为 false（契约：默认 12 小时窗口） */
    public boolean rememberMeFlag() {
        return Boolean.TRUE.equals(rememberMe);
    }
}
