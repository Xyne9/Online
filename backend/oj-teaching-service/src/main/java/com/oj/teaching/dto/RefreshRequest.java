package com.oj.teaching.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 续期请求体（契约 §2.4）。
 *
 * @author oj-cloud
 */
@Data
public class RefreshRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 登录时下发的刷新令牌 */
    private String refreshToken;
}
