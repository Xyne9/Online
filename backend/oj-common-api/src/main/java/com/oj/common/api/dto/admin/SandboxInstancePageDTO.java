package com.oj.common.api.dto.admin;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 沙箱实例分页结果（契约 02 §7.5.1 {@code GET /api/admin/sandbox/instances} 的 {@code data}）。
 *
 * <p><b>与既有 {@link SandboxPageDTO} 的关系</b>：那个类服务于「judge 服务 → admin」的
 * Feign 内部聚合（字段是 {@code sandboxId/status/durationMs}），与契约 §7.5.1 的对外
 * 字段（{@code sandboxInstanceId/state/ageMs/cpuPercent}）**不是同一套**。
 * 对外契约必须逐字对齐，故另建本类，不改 {@link SandboxPageDTO}（它在别处已被引用）。</p>
 *
 * <p><b>state 四值</b>：{@code QUEUED / RUNNING / EXITED / KILLED}（基线 §5.6），
 * 不是 {@code shell.js:436} 的 {@code run/idle}。</p>
 *
 * <p><b>ageMs 由服务端算</b>（契约原则 P2：前端不自己算）：
 * 运行中 = now − startedAt；已结束 = finishedAt − startedAt。</p>
 *
 * @author oj-cloud
 */
@Data
public class SandboxInstancePageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<Item> list = new ArrayList<>();

    private Long total = 0L;

    private Integer page = 1;

    private Integer pageSize = 20;

    private Boolean hasNext = false;

    /** 池统计（契约要求内嵌，不单开端点） */
    private Stats stats = new Stats();

    /** 单条实例 */
    @Data
    public static class Item implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 对外句柄，如 sbx-0001 */
        private String sandboxInstanceId;

        /** QUEUED / RUNNING / EXITED / KILLED */
        private String state;

        /** cpp / java / python */
        private String language;

        /** C++ 17 / Java 17 / Python 3 */
        private String languageLabel;

        private Long userId;

        private String userName;

        private Long problemId;

        private Long submissionId;

        /** 实时 CPU 占用百分比 */
        private Integer cpuPercent;

        /** 实时内存（KB） */
        private Long memKb;

        /** 启动时刻（毫秒），未启动为 null */
        private Long startedAt;

        /** 存活时长（毫秒），服务端计算 */
        private Long ageMs;

        /** 退出码，124 超时 / 137 被 kill / 0 正常；未结束为 null */
        private Integer exitCode;
    }

    /** 池统计 */
    @Data
    public static class Stats implements Serializable {

        private static final long serialVersionUID = 1L;

        private Integer running = 0;

        private Integer queued = 0;

        private Integer exited = 0;

        /** 被强杀数（exit_code=137），供「回收率」分母用 */
        private Integer killed = 0;

        /** 并发容量：由 ai_model_config 的 max_concurrency 同源配置读取 */
        private Integer capacity = 16;

        /** 平均排队时长（毫秒）：startedAt − createTime */
        private Long avgWaitMs;

        /** 平均运行时长（毫秒）：finishedAt − startedAt */
        private Long avgRunMs;

        /** 回收率（0~100）：已结束实例 / 总实例，保留一位小数 */
        private Double recycleRate;
    }
}
