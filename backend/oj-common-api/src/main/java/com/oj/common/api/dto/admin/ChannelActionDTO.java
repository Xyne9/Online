package com.oj.common.api.dto.admin;

import lombok.Data;

import java.io.Serializable;

/**
 * 通道写操作的响应体集合（契约 02 §7.2.3 ~ §7.2.8 的 {@code data}）。
 *
 * <p>六个端点的响应结构都很小、且只在 admin 内部使用，放一个文件里用静态内部类表达，
 * 比开 6 个单字段文件更容易对照契约表格逐条核验。</p>
 *
 * @author oj-cloud
 */
public final class ChannelActionDTO {

    private ChannelActionDTO() {
    }

    /** #74 新增通道（创建后自动探活一次，故先回 PROBING） */
    @Data
    public static class Created implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long id;

        private String channelCode;

        /** 固定回 PROBING：真实探活在后台线程跑，完成后回写 ON / DEGRADE */
        private String healthStatus;

        /** 首次探活任务标识，便于前端轮询/日志串联 */
        private String firstProbeTaskId;
    }

    /** #75 更新通道 */
    @Data
    public static class Updated implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long id;

        /** 更新时刻（毫秒） */
        private Long updatedAt;
    }

    /** #76 删除通道 */
    @Data
    public static class Deleted implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long id;

        /** 是否完成删除语义（软删也算 true） */
        private Boolean deleted;

        /** true 表示因存在历史调用而转为禁用，未物理删除 */
        private Boolean softDeleted;
    }

    /** #77 启停 / 手动降级 */
    @Data
    public static class StateChanged implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long id;

        private Boolean isEnabled;

        private String healthStatus;

        /** 生效时刻（毫秒） */
        private Long effectiveAt;
    }

    /** #78 连通性探测 */
    @Data
    public static class Probe implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long id;

        /** ON / DEGRADE */
        private String healthStatus;

        /** 端到端耗时（毫秒） */
        private Long latencyMs;

        /** 上游 HTTP 状态码；网络不可达时为 null */
        private Integer httpStatus;

        /** 上游回显的模型名（响应体 {@code model} 字段） */
        private String modelEcho;

        /** 探测时刻（毫秒） */
        private Long probedAt;
    }

    /** #79 降级恢复 */
    @Data
    public static class Recovered implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long id;

        private String healthStatus;

        /** 恢复时刻（毫秒） */
        private Long recoveredAt;

        /** 连续失败计数，恢复后固定为 0 */
        private Integer failStreak;
    }
}
