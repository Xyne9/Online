package com.oj.judge.adapter;

import com.oj.common.core.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * go-judge v1.13 的 HTTP 客户端（<b>只做传输，不做任何业务换算</b>）。
 *
 * <p>用 JDK 自带的 {@link java.net.http.HttpClient}，不引入 RestTemplate 之外的新依赖，
 * 也不引 okhttp —— 契约里写死了「不引入新第三方依赖」。</p>
 *
 * <p><b>为什么单列一个类：</b>判题服务的所有换算与协议封装都集中在
 * {@link JudgeAdapter}；本类只负责「把 JSON 发到 {@code /run}，把响应体原样拿回来」。
 * 这样适配层可以脱离 Spring 被 {@code main} 方法直接驱动做验证。</p>
 *
 * <p><b>实测要点（v1.13，与官方文档不一致，已由 scripts/_probe_gojudge5.py 确认）：</b></p>
 * <ul>
 *   <li>{@code POST /run}，请求体 {@code {"cmd":[...]}}，响应<b>单对象</b>（1 个 cmd）或
 *       <b>数组</b>（≥2 个 cmd），两种都要兼容；</li>
 *   <li>非 200 时响应体是一段 JSON 字符串（如 {@code "source file does not exist ..."}），
 *       必须当作 502 上游错误抛出，<b>绝不能吞掉当成 AC</b>；</li>
 *   <li>go-judge 没有鉴权头，直连内网即可。</li>
 * </ul>
 *
 * @author oj-cloud
 */
@Component
public class GoJudgeClient {

    private static final Logger log = LoggerFactory.getLogger(GoJudgeClient.class);

    /** {@code /run} 路径 */
    private static final String RUN_PATH = "/run";

    /** 沙箱基地址，如 {@code http://127.0.0.1:5050} */
    private final String endpoint;

    /** 底层 HTTP 客户端（线程安全，全局一个即可） */
    private final HttpClient httpClient;

    /** 单次请求超时（毫秒）：编译 + N 个测试点都在这一个请求里 */
    private final long requestTimeoutMs;

    public GoJudgeClient(@Value("${oj.judge.go-judge-endpoint:http://127.0.0.1:5050}") String endpoint,
                         @Value("${oj.judge.connect-timeout-ms:3000}") long connectTimeoutMs,
                         @Value("${oj.judge.request-timeout-ms:180000}") long requestTimeoutMs) {
        this.endpoint = trimTrailingSlash(endpoint);
        this.requestTimeoutMs = requestTimeoutMs;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                // 判题结果永远只看响应体，不跟随重定向（沙箱不会重定向，跟随反而掩盖配置错误）
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 调用 {@code POST /run}。
     *
     * @param requestJson 已序列化好的请求 JSON
     * @return 响应体原文（JSON）
     * @throws BusinessException 502 沙箱返回非 200 / 连不上；504 超时
     */
    public String run(String requestJson) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + RUN_PATH))
                .timeout(Duration.ofMillis(requestTimeoutMs))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status != 200) {
                // go-judge 的 4xx/5xx 响应体是一段 JSON 字符串，例如
                //   "source file does not exist \"/data/testcases/1006/99.in\": file not found"
                // 这是**上游失败**，不是「用户答案错误」，必须 502 上报而不是伪造判题结果。
                String body = abbreviate(response.body(), 500);
                log.error("go-judge 返回非 200：status={} body={}", status, body);
                throw BusinessException.badGateway("判题沙箱返回 " + status + "：" + body);
            }
            return response.body();
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("go-judge 请求超时（{}ms）", requestTimeoutMs, e);
            throw BusinessException.gatewayTimeout("判题沙箱响应超时（" + requestTimeoutMs + "ms）");
        } catch (IOException e) {
            log.error("go-judge 调用失败：{}", endpoint, e);
            throw BusinessException.badGateway("判题沙箱不可用：" + endpoint);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BusinessException.badGateway("判题沙箱调用被中断");
        }
    }

    /**
     * 沙箱是否可用（健康检查用；不参与判题主链路）。
     *
     * @return true = 至少能建立 TCP 连接
     */
    public boolean isReachable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/healthz"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    /** 当前生效的沙箱地址（日志与排障用） */
    public String getEndpoint() {
        return endpoint;
    }

    /** 去掉末尾斜杠，避免拼出 {@code //run} */
    private static String trimTrailingSlash(String url) {
        String value = (url == null || url.isBlank()) ? "http://127.0.0.1:5050" : url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** 日志截断，避免把几 MB 的错误体打进日志 */
    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...(truncated)";
    }
}
