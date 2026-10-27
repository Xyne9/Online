package com.oj.common.api.dto.admin;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 沙箱容器日志（契约 02 §7.5.2 {@code GET /api/admin/sandbox/instances/{id}/logs} 的 {@code data}）。
 *
 * <p><b>为什么可能为空而不 404</b>：{@code judge_sandbox_instance.log_ref} 允许为 NULL
 * （实例还没跑完 / 日志采样未落盘）。此时返回 {@code lines: []} + {@code truncated: false}
 * 并带上 {@link #note} 说明原因，前端走空态即可；返回 404 会让管理端把「没日志」
 * 误读成「实例不存在」（agent.md §3.4：禁止编造日志，也禁止把正常空态当错误）。</p>
 *
 * @author oj-cloud
 */
@Data
public class SandboxLogDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String sandboxInstanceId;

    /** 实例当前状态 */
    private String state;

    /** 日志行（按时间正序） */
    private List<Line> lines = new ArrayList<>();

    /** 是否因超出 lines 上限被截断 */
    private Boolean truncated = false;

    /** 采集时刻（毫秒） */
    private Long collectedAt;

    /** 无日志时的人类可读说明；有日志且未截断时为 null */
    private String note;

    /** 单行日志 */
    @Data
    public static class Line implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 行时间戳：日志原文前缀可解析时用原文，否则用实例创建时刻（毫秒） */
        private Long ts;

        private String msg;

        /** 是否高亮（stderr / error / 关键字命中） */
        private Boolean highlight = false;
    }
}
