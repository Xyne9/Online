package com.oj.common.api.dto.admin;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 全站健康聚合（契约 02 §7.3.1 {@code GET /api/admin/health} 的 {@code data}）。
 *
 * <p><b>唯一走真实 HTTP 探活的端点</b>：{@code services[]} 逐个打
 * {@code http://127.0.0.1:{port}/actuator/health}，{@code latencyMs} 是实测往返耗时。
 * 与「通道 CRUD / 审计 / 沙箱直连 MySQL」的取舍理由写在
 * {@code AdminHealthService} 的类注释里。</p>
 *
 * <p>{@code middleware[]} 是**真实探测**：MySQL 走 {@code SELECT 1}，Redis 走 {@code PING}，
 * RabbitMQ 走 AMQP 端口 TCP 连通性。任何一个不可达就是 {@code DOWN}，不做「配了就当成 UP」的假设。</p>
 *
 * @author oj-cloud
 */
@Data
public class PlatformHealthDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务服务健康（含网关） */
    private List<Service> services = new ArrayList<>();

    /** 中间件健康 */
    private List<Middleware> middleware = new ArrayList<>();

    /** 通道健康四档计数 */
    private Channels channels = new Channels();

    /** 沙箱池概况 */
    private Sandbox sandbox = new Sandbox();

    /** 至少一个通道非 ON（供 a-models 顶部黄条） */
    private Boolean degraded = false;

    /** 自动降级开关；本项目未建 sys_config，固定读「通道健康态由探活驱动」这一事实 */
    private Boolean autoDegrade = true;

    /** 聚合时刻（毫秒） */
    private Long checkedAt;

    /** 单个服务 */
    @Data
    public static class Service implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 服务名，如 oj-judge-service */
        private String name;

        /** UP / DOWN */
        private String status;

        /** 探活往返耗时（毫秒）；DOWN 时为 null */
        private Long latencyMs;
    }

    /** 单个中间件 */
    @Data
    public static class Middleware implements Serializable {

        private static final long serialVersionUID = 1L;

        /** mysql / redis / rabbitmq */
        private String name;

        /** UP / DOWN */
        private String status;
    }

    /** 通道四档计数 */
    @Data
    public static class Channels implements Serializable {

        private static final long serialVersionUID = 1L;

        private Integer on = 0;

        private Integer degrade = 0;

        private Integer off = 0;

        private Integer total = 0;
    }

    /** 沙箱池 */
    @Data
    public static class Sandbox implements Serializable {

        private static final long serialVersionUID = 1L;

        private Integer running = 0;

        private Integer queued = 0;

        /** 并发容量（Go-Judge 侧并发上限，读配置） */
        private Integer capacity = 16;
    }
}
