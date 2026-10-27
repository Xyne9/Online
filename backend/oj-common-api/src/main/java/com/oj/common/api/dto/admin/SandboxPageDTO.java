package com.oj.common.api.dto.admin;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 沙箱实例分页结果（`AdminAggregateFeignClient#listSandboxes` 的响应体）。
 *
 * <p>字段与全站分页契约一致（§1.3：list/total/page/pageSize/hasNext），
 * 这样 admin 服务可以直接把它塞进 {@code Result<SandboxPageDTO>} 而不必二次包装。</p>
 *
 * @author oj-cloud
 */
@Data
public class SandboxPageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前页实例列表 */
    private List<SandboxItemDTO> list;

    /** 总记录数 */
    private Long total;

    /** 当前页码 */
    private Integer page;

    /** 每页条数 */
    private Integer pageSize;

    /** 是否存在下一页 */
    private Boolean hasNext;

    /** 全局汇总：运行中实例数 */
    private Integer runningCount;

    /** 全局汇总：峰值并发数 */
    private Integer peakConcurrent;

    /** 全局汇总：平均耗时（毫秒） */
    private Double avgDurationMs;

    /** 单个沙箱实例 */
    @Data
    public static class SandboxItemDTO implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 沙箱实例 ID */
        private String sandboxId;

        /** 提交 ID */
        private Long submissionId;

        /** 用户 ID */
        private Long userId;

        /** 语言 */
        private String language;

        /** 状态：running / finished / killed / timeout */
        private String status;

        /** 实际耗时（毫秒） */
        private Long durationMs;

        /** 峰值内存（KB） */
        private Long memoryKb;

        /** 退出码（124=TLE，137=OOM Killed） */
        private Integer exitCode;

        /** 容器名 */
        private String containerName;

        /** 开始时间（毫秒时间戳） */
        private Long startedAt;

        /** 结束时间（毫秒时间戳） */
        private Long finishedAt;
    }
}
