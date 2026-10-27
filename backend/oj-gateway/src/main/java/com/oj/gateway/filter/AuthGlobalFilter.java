package com.oj.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.common.core.exception.ErrorCode;
import com.oj.common.core.jwt.JwtUtil;
import com.oj.common.core.result.Result;
import com.oj.gateway.config.GatewayAuthProperties;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 全局鉴权过滤器（设计文档 §1.2 / §2.8）。
 *
 * <p><b>执行顺序</b>：{@code Ordered.HIGHEST_PRECEDENCE + 10}，排在
 * {@code RouteToRequestUrlFilter(10000)} / {@code NettyRoutingFilter(Integer.MAX_VALUE)} 之前，
 * 确保未鉴权请求根本打不到下游服务。</p>
 *
 * <p><b>鉴权流程</b>：</p>
 * <ol>
 *   <li>为每个请求生成 {@code X-Trace-Id}（已有则透传），写入响应头；</li>
 *   <li>命中白名单（登录/刷新/actuator/健康检查）→ 直放；</li>
 *   <li>取 {@code Authorization: Bearer <token>}，缺失或格式错 → 401；</li>
 *   <li>校验 JWT 签名与 exp → 失败 401；</li>
 *   <li>（可配置）校验 Redis 在线态 {@code oj:auth:token:{uid}} → 不存在 401；</li>
 *   <li>（可配置）比对 Redis 里存的 token 与请求 token → 不一致 401
 *       （单点登录的关键：刷新后旧 token 立刻失效）；</li>
 *   <li>注入 {@code X-User-Id} / {@code X-User-Role} / {@code X-Class-Id} 头给下游，
 *       服务层据此做资源归属校验（§1.4 最后一列）。</li>
 * </ol>
 *
 * <p><b>Day2 降级开关</b>：{@code oj.gateway.redis-check.enabled=false}（默认）时跳过第 5、6 步，
 * 否则此刻 Redis 里还没有任何 token，验收 curl 会全部 401。</p>
 *
 * @author oj-cloud
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthGlobalFilter.class);

    /** 链路追踪头 */
    private static final String TRACE_HEADER = "X-Trace-Id";

    /** 下游用户上下文头 */
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLE_HEADER = "X-User-Role";
    private static final String CLASS_ID_HEADER = "X-Class-Id";

    private final GatewayAuthProperties properties;
    private final ObjectMapper objectMapper;

    /** 响应式 Redis 模板；未引入 starter 时为 null（此时自动跳过 Redis 校验） */
    private final ReactiveStringRedisTemplate redisTemplate;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public AuthGlobalFilter(GatewayAuthProperties properties,
                            ObjectMapper objectMapper,
                            ObjectProvider<ReactiveStringRedisTemplate> redisProvider) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisProvider.getIfAvailable();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // ① 链路追踪 ID：有则透传，无则生成（12 位短 ID，与 Result.traceId 同格式）
        String incomingTraceId = request.getHeaders().getFirst(TRACE_HEADER);
        final String traceId = (incomingTraceId == null || incomingTraceId.isBlank())
                ? UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                : incomingTraceId;
        exchange.getResponse().getHeaders().set(TRACE_HEADER, traceId);

        // ② 白名单直放（登录 / 刷新 / actuator / 各服务健康检查）
        if (isWhitelisted(path)) {
            return chain.filter(withHeaders(exchange, traceId, null, null, null));
        }

        // 网关 JWT 校验整体关闭时，仅透传 traceId 放行（本地排障用）
        if (!properties.isJwtCheckEnabled()) {
            return chain.filter(withHeaders(exchange, traceId, null, null, null));
        }

        // ③ 取 Bearer token
        String token = JwtUtil.resolveBearer(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (token == null) {
            return unauthorized(exchange, traceId, "未登录或 Token 失效", "缺少 Authorization: Bearer 请求头");
        }

        // ④ 校验 JWT 签名与有效期
        Claims claims;
        try {
            claims = JwtUtil.parse(token);
        } catch (Exception e) {
            log.debug("[{}] JWT 校验失败: {}", traceId, e.getMessage());
            return unauthorized(exchange, traceId, "未登录或 Token 失效", "Token 非法或已过期");
        }

        Long userId = JwtUtil.getUserId(claims);
        String role = JwtUtil.getRole(claims);
        Long classId = JwtUtil.getClassId(claims);
        if (userId == null) {
            return unauthorized(exchange, traceId, "未登录或 Token 失效", "Token 缺少 uid 声明");
        }

        // ⑤⑥ Redis 在线态校验（Day2 默认关闭，避免空 Redis 全 401）
        if (!properties.isRedisCheckEnabled() || redisTemplate == null) {
            return chain.filter(withHeaders(exchange, traceId, userId, role, classId));
        }

        String redisKey = properties.getTokenKeyPrefix() + userId;
        return redisTemplate.opsForValue().get(redisKey)
                .flatMap(stored -> {
                    if (stored == null || stored.isBlank()) {
                        return unauthorized(exchange, traceId, "未登录或 Token 失效",
                                "Redis 中不存在在线态：" + redisKey);
                    }
                    // Redis 里存的是 JSON {accessToken, jti}：用 contains 而非 equals，
                    // 既兼容 JSON 存储也兼容纯字符串存储。
                    if (!stored.contains(token)) {
                        return unauthorized(exchange, traceId, "未登录或 Token 失效",
                                "Token 与在线态不一致（可能已在其他设备登录或已刷新）");
                    }
                    return chain.filter(withHeaders(exchange, traceId, userId, role, classId));
                })
                // Redis key 不存在时 flatMap 不会触发（Mono 为空），用 switchIfEmpty 兜底
                .switchIfEmpty(Mono.defer(() -> unauthorized(exchange, traceId, "未登录或 Token 失效",
                        "Redis 中不存在在线态：" + redisKey)))
                .onErrorResume(e -> {
                    // Redis 故障不应导致全站不可用：记警告后放行（fail-open），
                    // 下游仍有 JWT 解析兜底。生产可按需改为 fail-close。
                    log.warn("[{}] Redis 在线态校验异常，已降级放行：{}", traceId, e.toString());
                    return chain.filter(withHeaders(exchange, traceId, userId, role, classId));
                });
    }

    /**
     * 给下游请求补上 traceId 与用户上下文头（值为 null 时不注入该头）。
     */
    private ServerWebExchange withHeaders(ServerWebExchange exchange, String traceId,
                                          Long userId, String role, Long classId) {
        ServerHttpRequest.Builder builder = exchange.getRequest().mutate().header(TRACE_HEADER, traceId);
        if (userId != null) {
            builder.header(USER_ID_HEADER, String.valueOf(userId));
        }
        if (role != null) {
            builder.header(USER_ROLE_HEADER, role);
        }
        if (classId != null) {
            builder.header(CLASS_ID_HEADER, String.valueOf(classId));
        }
        return exchange.mutate().request(builder.build()).build();
    }

    /** 白名单判断（Ant 风格，支持 {@code /api/&#42;/health} 这类单层通配） */
    private boolean isWhitelisted(String path) {
        List<String> whitelist = properties.getWhitelist();
        if (whitelist == null || whitelist.isEmpty()) {
            return false;
        }
        for (String pattern : whitelist) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /** 输出统一 Result JSON 的 401 响应（HTTP 状态码与 body.code 严格同值） */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String traceId,
                                    String message, String detail) {
        log.info("[{}] 401 拒绝请求 {} {}：{}", traceId,
                exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getPath(), detail);

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(TRACE_HEADER, traceId);

        Result<Void> body = Result.fail(ErrorCode.UNAUTHORIZED, message);
        body.setTraceId(traceId);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            bytes = ("{\"code\":401,\"message\":\"" + message
                    + "\",\"data\":null,\"traceId\":\"" + traceId + "\"}").getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 必须早于 RouteToRequestUrlFilter(10000) 与 NettyRoutingFilter(Integer.MAX_VALUE)
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
