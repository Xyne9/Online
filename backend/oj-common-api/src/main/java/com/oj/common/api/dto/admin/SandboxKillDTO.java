package com.oj.common.api.dto.admin;

import lombok.Data;

import java.io.Serializable;

/**
 * 沙箱终止结果（契约 02 §7.5.3 {@code DELETE /api/admin/sandbox/instances/{id}} 的 {@code data}）。
 *
 * <p><b>真实生效</b>：本响应只在上面的 UPDATE 语句确实把
 * {@code state} 从 {@code QUEUED}/{@code RUNNING} 改成 {@code KILLED}、
 * {@code exit_code=137}、写 {@code finished_at} 之后才会返回；
 * 已 {@code EXITED}/{@code KILLED} 的实例抛 409，不会走到这里。</p>
 *
 * <p>{@code releasedCpu} / {@code releasedMemKb} 取实例自身的
 * {@code cpu_limit} / {@code mem_limit_kb}（即「回收了多少配额」），不是常量。</p>
 *
 * @author oj-cloud
 */
@Data
public class SandboxKillDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String sandboxInstanceId;

    /** 固定 KILLED */
    private String state;

    /** 终止时刻（毫秒） */
    private Long killedAt;

    /** 回收 CPU 核数（cpu_limit） */
    private Integer releasedCpu;

    /** 回收内存（KB，mem_limit_kb） */
    private Long releasedMemKb;

    /** 固定 137（SIGKILL） */
    private Integer exitCode;
}
