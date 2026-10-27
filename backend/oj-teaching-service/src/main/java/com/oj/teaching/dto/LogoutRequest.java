package com.oj.teaching.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 注销请求体（契约 §2.3）。
 *
 * @author oj-cloud
 */
@Data
public class LogoutRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** true 时清空该用户全部会话（access + refresh 全删）；默认 false 只删当前 access */
    private Boolean allDevices;

    /** 空安全取值：null 视为 false */
    public boolean allDevicesFlag() {
        return Boolean.TRUE.equals(allDevices);
    }
}
