package com.oj.admin.service;

import com.oj.admin.config.AdminProperties;
import com.oj.admin.dao.AdminSandboxDao;
import com.oj.common.api.dto.admin.PlatformHealthDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 全站健康聚合（契约 §7.3.1 #80）。
 *
 * <p><b>这是本服务里唯一「必须走真实网络」的端点</b>，也是 §7.1「数据走 Feign」
 * 这条原则真正适用的地方：</p>
 * <ul>
 *   <li>通道 CRUD / 审计 / 沙箱是<b>同一库的运维读写</b>——直连 MySQL 更短、更一致
 *       （详见 {@code AdminChannelDao} 类注释）；</li>
 *   <li>而「{@code oj-judge-service} 现在到底活不活」这件事，<b>只有它自己的进程知道</b>。
 *       读库读不出来，读 Nacos 注册表也只能得到「15 秒前的心跳」，所以这里逐个打
 *       {@code /actuator/health} 并实测往返耗时。</li>
 * </ul>
 *
 * <p><b>为什么用写死的端口而不是 Nacos 服务名</b>：本机 6 个服务端口固定
 * （agent.md §2.4：8080~8085）。走 Nacos 会在「注册中心正常、实例已挂」的窗口期误报 UP，
 * 也会在 Nacos 自己在抖动时把全部服务误报 DOWN。写死端口 + 短超时是最诚实的探法。</p>
 *
 * <p><b>中间件也是真探</b>：MySQL 走 {@code SELECT 1}，Redis 走 {@code PING}，
 * RabbitMQ 走 AMQP 端口的 TCP 连通性——不是「配置里写了就当 UP」。</p>
 *
 * @author oj-cloud
 */
@Service
public class AdminHealthService {

    private static final Logger log = LoggerFactory.getLogger(AdminHealthService.class);

    /** 单个服务的探活超时：健康聚合是首屏接口，不能因为一个服务慢就整体卡住 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    /** 中间件 TCP 连通性超时 */
    private static final int SOCKET_TIMEOUT_MS = 1500;

    private final AdminProperties properties;
    private final AdminChannelService channelService;
    private final AdminSandboxDao sandboxDao;
    private final JdbcTemplate db;
    private final StringRedisTemplate redis;
    private final HttpClient http;

    public AdminHealthService(AdminProperties properties, AdminChannelService channelService,
                              AdminSandboxDao sandboxDao, JdbcTemplate db, StringRedisTemplate redis) {
        this.properties = properties;
        this.channelService = channelService;
        this.sandboxDao = sandboxDao;
        this.db = db;
        this.redis = redis;
        this.http = HttpClient.newBuilder()
                .connectTimeout(PROBE_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 聚合全站健康。
     *
     * @param deep true 时对非 ON 通道逐个真实探活（1 次/10 秒由探活锁保证）；
     *             false（默认）只读库里的健康态，避免管理页每次刷新都打上游 API
     * @return 健康快照
     */
    public PlatformHealthDTO health(boolean deep) {
        PlatformHealthDTO out = new PlatformHealthDTO();

        // ① 业务服务（真实 HTTP）
        List<PlatformHealthDTO.Service> services = new ArrayList<>();
        for (String name : properties.getMonitoredServices()) {
            services.add(probeService(name));
        }
        out.setServices(services);

        // ② 中间件（真实连接）
        List<PlatformHealthDTO.Middleware> middleware = new ArrayList<>();
        middleware.add(middleware("mysql", this::probeMysql));
        middleware.add(middleware("redis", this::probeRedis));
        String mqHost = properties.getRabbitmqHost();
        int mqPort = properties.getRabbitmqPort();
        middleware.add(middleware("rabbitmq", () -> probeTcp(mqHost, mqPort)));
        out.setMiddleware(middleware);

        // ③ 通道四档
        Map<String, Integer> buckets = channelService.healthBuckets();
        PlatformHealthDTO.Channels channels = new PlatformHealthDTO.Channels();
        channels.setOn(buckets.getOrDefault("on", 0));
        channels.setDegrade(buckets.getOrDefault("degrade", 0));
        channels.setOff(buckets.getOrDefault("off", 0));
        channels.setTotal(buckets.getOrDefault("total", 0));
        out.setChannels(channels);
        // 「至少一个通道非 ON」——契约原文，直接决定 a-models 顶部黄条
        out.setDegraded(channels.getDegrade() > 0 || channels.getOff() > 0);

        // ④ 沙箱池
        Map<String, Long> states = sandboxDao.stateBuckets();
        PlatformHealthDTO.Sandbox sandbox = new PlatformHealthDTO.Sandbox();
        sandbox.setRunning(intOf(states.get("RUNNING")));
        sandbox.setQueued(intOf(states.get("QUEUED")));
        sandbox.setCapacity(properties.getSandboxCapacity());
        out.setSandbox(sandbox);

        // ⑤ deep 模式：逐通道真探（探活锁天然把频率压到 1 次/10 秒）
        if (deep) {
            deepProbeChannels();
        }
        out.setCheckedAt(System.currentTimeMillis());
        return out;
    }

    /**
     * 对全部「非 ON 或已降级」的通道补一次真实探活。
     *
     * <p>只探非 ON 的通道：ON 的通道刚被 AI 服务真实用过，再探一次纯属浪费上游配额。
     * 单条失败不影响整体——健康聚合要回答「现在什么状态」，某条探不通正是它要报告的事实。</p>
     */
    private void deepProbeChannels() {
        channelService.allChannels().forEach(item -> {
            if ("ON".equalsIgnoreCase(item.getHealthStatus())) {
                return;
            }
            try {
                channelService.probe(item.getId(), null);
            } catch (RuntimeException e) {
                log.warn("deep 健康聚合探活通道 {} 失败：{}", item.getChannelCode(), e.getMessage());
            }
        });
    }

    /**
     * 探一个业务服务的 {@code /actuator/health}。
     *
     * @param name 服务名
     * @return 健康条目（DOWN 时 latencyMs 为 null）
     */
    private PlatformHealthDTO.Service probeService(String name) {
        PlatformHealthDTO.Service item = new PlatformHealthDTO.Service();
        item.setName(name);
        String url = properties.getServiceHealthUrls().get(name);
        if (url == null || url.isBlank()) {
            item.setStatus("DOWN");
            return item;
        }
        long start = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(PROBE_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            long cost = System.currentTimeMillis() - start;
            // actuator 返回 {"status":"UP",...}；只认 2xx 且 body 里出现 "UP"
            boolean up = resp.statusCode() >= 200 && resp.statusCode() < 300
                    && resp.body() != null && resp.body().contains("\"UP\"");
            item.setStatus(up ? "UP" : "DOWN");
            item.setLatencyMs(up ? cost : null);
        } catch (IOException e) {
            item.setStatus("DOWN");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            item.setStatus("DOWN");
        }
        return item;
    }

    /** MySQL 真探：{@code SELECT 1} */
    private boolean probeMysql() {
        try {
            Integer one = db.queryForObject("SELECT 1", Integer.class);
            return one != null && one == 1;
        } catch (RuntimeException e) {
            log.warn("MySQL 健康探测失败：{}", e.getMessage());
            return false;
        }
    }

    /** Redis 真探：PING 必须回 PONG */
    private boolean probeRedis() {
        try {
            String pong = redis.execute((org.springframework.data.redis.core.RedisCallback<String>) conn ->
                    conn.ping());
            return "PONG".equalsIgnoreCase(pong);
        } catch (RuntimeException e) {
            log.warn("Redis 健康探测失败：{}", e.getMessage());
            return false;
        }
    }

    /** RabbitMQ 真探：AMQP 端口 TCP 可连即认为 UP（不做 AMQP 握手，避免引入 amqp 客户端依赖） */
    private boolean probeTcp(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), SOCKET_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private PlatformHealthDTO.Middleware middleware(String name, java.util.function.BooleanSupplier probe) {
        PlatformHealthDTO.Middleware m = new PlatformHealthDTO.Middleware();
        m.setName(name);
        boolean up;
        try {
            up = probe.getAsBoolean();
        } catch (RuntimeException e) {
            up = false;
        }
        m.setStatus(up ? "UP" : "DOWN");
        return m;
    }

    private static int intOf(Long v) {
        return v == null ? 0 : v.intValue();
    }
}
