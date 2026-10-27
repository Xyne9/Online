package com.oj.common.api.dto.admin;

import lombok.Data;

import java.io.Serializable;

/**
 * 单个服务的健康快照（`AdminAggregateFeignClient#reportHealth` 的响应体）。
 *
 * <p>admin 服务的「健康聚合」大盘用它拼出 6 个服务（网关 + 5 业务）的统一视图，
 * 前端 a-audit.html 的「服务状态」卡片严格按 {@link #status} 上色。</p>
 *
 * @author oj-cloud
 */
@Data
public class ServiceHealthDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 服务名，如 oj-teaching-service */
    private String serviceName;

    /** 展示名，如「教务服务」 */
    private String displayName;

    /** 状态：UP / DOWN / DEGRADED / UNKNOWN */
    private String status;

    /** 实例地址（host:port） */
    private String instance;

    /** Nacos 中的实例数 */
    private Integer instanceCount;

    /** 版本号（取自 build-info 或配置） */
    private String version;

    /** 启动时间（毫秒时间戳） */
    private Long startedAt;

    /** 已运行秒数 */
    private Long uptimeSeconds;

    /** 探活耗时（毫秒） */
    private Long probeCostMs;

    /** 最近一次探活时间（毫秒时间戳） */
    private Long lastProbeAt;

    /** 不健康时的原因 */
    private String message;
}
