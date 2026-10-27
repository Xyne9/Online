package com.oj.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * admin 服务的运维参数（{@code oj.admin.*}）。
 *
 * <p><b>为什么放配置不放硬编码</b>：探活超时、探测地址、并发容量这些值
 * 会随机器与网络变化（答辩现场的代理可能让云端探测变慢），
 * 写死在代码里每次都要重新编译打包。</p>
 *
 * @author oj-cloud
 */
@Component
@ConfigurationProperties(prefix = "oj.admin")
public class AdminProperties {

    /** 探活防抖锁 TTL（秒），对应 {@code oj:admin:probe:{channelCode}} */
    private long probeDebounceSeconds = 10;

    /** 探活默认超时（毫秒）；单次请求可用 body.timeoutMs 覆盖 */
    private long probeTimeoutMs = 30000;

    /** 健康聚合要探测的业务服务名（顺序即返回顺序） */
    private List<String> monitoredServices = new ArrayList<>();

    /**
     * 服务名 → actuator 地址。**必须写死端口而不是走服务发现**：
     * 本机 6 个服务端口固定（agent.md §2.4），走 Nacos 反而在注册中心抖动时误报 DOWN。
     */
    private Map<String, String> serviceHealthUrls = new LinkedHashMap<>();

    /** MySQL 健康探测地址（仅用于判定 UP/DOWN，不做连接池） */
    private String mysqlHost = "127.0.0.1";

    private int mysqlPort = 3306;

    /** Redis 健康探测 */
    private String redisHost = "127.0.0.1";

    private int redisPort = 6379;

    /** RabbitMQ AMQP 端口探测（5672） */
    private String rabbitmqHost = "127.0.0.1";

    private int rabbitmqPort = 5672;

    /** 沙箱并发容量（Go-Judge 侧同时可跑的实例数上限） */
    private int sandboxCapacity = 16;

    /**
     * 本地调试放行开关：true 时「没有 X-User-Role 头」的请求视为可信，
     * 便于服务刚起、网关还没起时用 curl 自检。**默认 false**，生产绝不能开。
     */
    private boolean allowAnonymousLocal = false;

    /**
     * CSV 导出限流窗口（秒），契约 §7.4.3 要求 1 次/分钟。
     *
     * <p>默认值直接引用 {@link com.oj.common.core.redis.RedisKeys#TTL_ADMIN_AUDIT_EXPORT}，
     * 与 Redis Key 的 TTL 保持同一个真值来源——避免「常量写 60、配置写 30」
     * 这种 key 提前过期导致限流失效的漂移。</p>
     */
    private long exportRateLimitSeconds = com.oj.common.core.redis.RedisKeys.TTL_ADMIN_AUDIT_EXPORT;

    /** CSV 导出默认行数 */
    private int exportDefaultLimit = 5000;

    /** CSV 导出硬上限，超出直接 400 */
    private int exportMaxLimit = 20000;

    public long getProbeDebounceSeconds() {
        return probeDebounceSeconds;
    }

    public void setProbeDebounceSeconds(long probeDebounceSeconds) {
        this.probeDebounceSeconds = probeDebounceSeconds;
    }

    public long getProbeTimeoutMs() {
        return probeTimeoutMs;
    }

    public void setProbeTimeoutMs(long probeTimeoutMs) {
        this.probeTimeoutMs = probeTimeoutMs;
    }

    public List<String> getMonitoredServices() {
        return monitoredServices;
    }

    public void setMonitoredServices(List<String> monitoredServices) {
        this.monitoredServices = monitoredServices;
    }

    public Map<String, String> getServiceHealthUrls() {
        return serviceHealthUrls;
    }

    public void setServiceHealthUrls(Map<String, String> serviceHealthUrls) {
        this.serviceHealthUrls = serviceHealthUrls;
    }

    public String getMysqlHost() {
        return mysqlHost;
    }

    public void setMysqlHost(String mysqlHost) {
        this.mysqlHost = mysqlHost;
    }

    public int getMysqlPort() {
        return mysqlPort;
    }

    public void setMysqlPort(int mysqlPort) {
        this.mysqlPort = mysqlPort;
    }

    public String getRedisHost() {
        return redisHost;
    }

    public void setRedisHost(String redisHost) {
        this.redisHost = redisHost;
    }

    public int getRedisPort() {
        return redisPort;
    }

    public void setRedisPort(int redisPort) {
        this.redisPort = redisPort;
    }

    public String getRabbitmqHost() {
        return rabbitmqHost;
    }

    public void setRabbitmqHost(String rabbitmqHost) {
        this.rabbitmqHost = rabbitmqHost;
    }

    public int getRabbitmqPort() {
        return rabbitmqPort;
    }

    public void setRabbitmqPort(int rabbitmqPort) {
        this.rabbitmqPort = rabbitmqPort;
    }

    public int getSandboxCapacity() {
        return sandboxCapacity;
    }

    public void setSandboxCapacity(int sandboxCapacity) {
        this.sandboxCapacity = sandboxCapacity;
    }

    public boolean isAllowAnonymousLocal() {
        return allowAnonymousLocal;
    }

    public void setAllowAnonymousLocal(boolean allowAnonymousLocal) {
        this.allowAnonymousLocal = allowAnonymousLocal;
    }

    public long getExportRateLimitSeconds() {
        return exportRateLimitSeconds;
    }

    public void setExportRateLimitSeconds(long exportRateLimitSeconds) {
        this.exportRateLimitSeconds = exportRateLimitSeconds;
    }

    public int getExportDefaultLimit() {
        return exportDefaultLimit;
    }

    public void setExportDefaultLimit(int exportDefaultLimit) {
        this.exportDefaultLimit = exportDefaultLimit;
    }

    public int getExportMaxLimit() {
        return exportMaxLimit;
    }

    public void setExportMaxLimit(int exportMaxLimit) {
        this.exportMaxLimit = exportMaxLimit;
    }
}
