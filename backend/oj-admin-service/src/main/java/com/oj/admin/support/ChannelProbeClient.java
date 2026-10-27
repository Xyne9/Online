package com.oj.admin.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.admin.config.AdminProperties;
import com.oj.common.core.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通道探活的真实网络实现（契约 §7.2.7「连通性探测，真实发一次最小请求」）。
 *
 * <p><b>为什么必须真发请求</b>：这是本端点唯一的存在意义——a-models 页面上的
 * 「测试」按钮要回答的是「这条通道现在到底能不能用」，而不是「库里 health_status 是什么」。
 * 任何「读一下配置就回 ON」的实现在答辩现场一拔网线就穿帮。</p>
 *
 * <p><b>两条协议</b>：</p>
 * <ul>
 *   <li><b>Ollama</b>（{@code provider_type='OLLAMA'}）：打 OpenAI 兼容端点
 *       {@code POST {baseUrl}/chat/completions}，body 带 {@code options.num_ctx=8192}
 *       （agent.md §2.2 铁律：本地模型一律 8192，否则吃满 8 GB 显存）；</li>
 *   <li><b>云端</b>：OpenAI 兼容 {@code POST {baseUrl}/chat/completions} +
 *       {@code Authorization: Bearer {key}}，{@code max_tokens=8} 控成本。</li>
 * </ul>
 *
 * <p><b>baseUrl 归一化</b>：本地通道存的是 {@code http://host.docker.internal:11434/v1}——
 * 那是「容器内访问宿主机」的地址；本服务跑在宿主机 JVM 上，必须换成 {@code 127.0.0.1}
 * （照抄 {@code DynamicAiModelFactory.localUrl()} 的处理，两处口径必须一致）。</p>
 *
 * <p><b>错误码语义</b>（契约）：鉴权失败 / 网络不可达 → 502；超时 → 504。
 * 上游返回 4xx/5xx（如<b>曾经实测到的</b>智谱 429 余额不足）**不抛异常**，如实把
 * {@code httpStatus} 回给前端并把通道标 DEGRADE ——「探活发现上游拒绝」是探测成功、
 * 通道不健康，两者不能混为一谈。</p>
 *
 * <p><b>2026-10-27 二次实测补充</b>：智谱通道当时返回的是 <b>HTTP 200</b>（不是 429），
 * 但 {@code content} 为空、8 个 token 全被 {@code reasoning_content} 吃掉。
 * 因此判活标准是「HTTP 2xx + {@code choices} 非空」，<b>不看正文是否为空</b>——
 * 否则推理型模型会被永久误判为降级。详见 {@link #extractHasChoice}。</p>
 *
 * @author oj-cloud
 */
@Component
public class ChannelProbeClient {

    private static final Logger log = LoggerFactory.getLogger(ChannelProbeClient.class);

    /** 探活用的最小提示词：一个 token 就够，目的是拿首字延迟 */
    private static final String PROBE_PROMPT = "ok";

    /** 本地模型的上下文窗口，agent.md §2.2 铁律 */
    private static final int LOCAL_NUM_CTX = 8192;

    /** 云端 max_tokens：探活只要有 choices 就证明通道通；推理模型会把 8 个 token 全用在
     *  reasoning 上导致 content 为空，这是预期行为，不作为判据（见 extractHasChoice 注释） */
    private static final int CLOUD_MAX_TOKENS = 8;

    private final AdminProperties properties;
    private final SecretResolver secrets;
    private final ObjectMapper json;
    private final HttpClient http;

    public ChannelProbeClient(AdminProperties properties, SecretResolver secrets, ObjectMapper json) {
        this.properties = properties;
        this.secrets = secrets;
        this.json = json;
        // 不跟随重定向：探活要的是「这个地址本身通不通」
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, properties.getProbeTimeoutMs() / 3)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 一次探活的结果（内部值对象；Service 再包成契约 DTO）。
     *
     * @param httpStatus 上游 HTTP 状态码；网络不可达时为 null
     * @param modelEcho  上游响应体里的 model 字段
     * @param ok         是否可用（HTTP 2xx 且解析出 choices）
     * @param latencyMs  端到端耗时
     * @param note       不健康时的人类可读原因（写进 health_note）
     */
    public record ProbeOutcome(Integer httpStatus, String modelEcho, boolean ok,
                               long latencyMs, String note) {
    }

    /**
     * 对一条通道发一次最小请求。
     *
     * @param channelCode  通道标识（仅用于日志与文案，绝不打印密钥）
     * @param providerType OLLAMA / OPENAI_COMPATIBLE
     * @param baseUrl      API 端点
     * @param modelName    模型名
     * @param rawApiKey    DB 里的 api_key 原值（占位符或明文）
     * @param timeoutMs    超时（毫秒）
     * @return 探活结果
     * @throws BusinessException 502 网络不可达 / 鉴权失败；504 超时
     */
    public ProbeOutcome probe(String channelCode, String providerType, String baseUrl,
                              String modelName, String rawApiKey, long timeoutMs) {
        boolean local = "OLLAMA".equalsIgnoreCase(providerType);
        String url = chatCompletionsUrl(baseUrl, local);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("messages", List.of(Map.of("role", "user", "content", PROBE_PROMPT)));
        body.put("stream", false);
        if (local) {
            // Ollama 原生参数走 options；OpenAI 兼容端点会忽略未知字段，不会报错
            body.put("options", Map.of("num_ctx", LOCAL_NUM_CTX));
        } else {
            body.put("max_tokens", CLOUD_MAX_TOKENS);
        }

        byte[] payload;
        try {
            payload = json.writeValueAsBytes(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw BusinessException.internalError("探活请求体序列化失败");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));

        if (!local) {
            // 云端必须带密钥；解析不出就 502（与 AI 服务「未配置密钥」同语义）
            String key = secrets.require(rawApiKey, channelCode);
            builder.header("Authorization", "Bearer " + key);
        }

        long start = System.currentTimeMillis();
        try {
            HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;
            int status = resp.statusCode();
            String text = resp.body() == null ? "" : resp.body();

            if (status == 401 || status == 403) {
                // 鉴权失败：密钥本身有问题，如实 502（agent.md §2.6：绝不静默降级）
                throw BusinessException.badGateway("通道 " + channelCode + " 鉴权失败（HTTP " + status + "）");
            }
            String modelEcho = extractModel(text);
            if (status >= 200 && status < 300) {
                boolean ok = extractHasChoice(text);
                return new ProbeOutcome(status, modelEcho, ok, latency,
                        ok ? null : truncate("上游返回 2xx 但响应体没有 choices：" + text));
            }
            // 4xx/5xx：探测成功但通道不健康（如智谱 429 余额不足），如实回传
            return new ProbeOutcome(status, modelEcho, false, latency,
                    truncate("上游返回 HTTP " + status + "：" + text));
        } catch (HttpTimeoutException e) {
            throw BusinessException.gatewayTimeout(
                    "通道 " + channelCode + " 探活超时（>" + timeoutMs + "ms）");
        } catch (java.io.IOException e) {
            // ConnectException / UnknownHostException / SSLHandshakeException 都落这里
            throw BusinessException.badGateway("通道 " + channelCode + " 网络不可达："
                    + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BusinessException.badGateway("通道 " + channelCode + " 探活被中断");
        }
    }

    /**
     * 拼 {@code /chat/completions} 地址，并按「本机运行」把容器地址归一化。
     *
     * @param baseUrl 端点（可能以 /v1 结尾，也可能已含 /chat/completions）
     * @param local   是否本地 Ollama 通道
     * @return 完整 URL
     */
    static String chatCompletionsUrl(String baseUrl, boolean local) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.isEmpty()) {
            throw BusinessException.badRequest("通道 baseUrl 为空，无法探活");
        }
        if (local) {
            // 容器内地址 → 宿主地址（本服务跑在宿主机 JVM 上）
            base = base.replace("host.docker.internal", "127.0.0.1");
        }
        base = base.replaceAll("/+$", "");
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    /** 从响应体取 model 字段（回显模型名，证明「打到的确实是对应的模型」） */
    private String extractModel(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(text);
            JsonNode m = node.get("model");
            return m == null || m.isNull() ? null : m.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /** 是否真的拿到了 completion（而不是一个形状不对的 200）
     *
     *  <p><b>只判 {@code choices} 非空，不判 {@code content} 非空</b>——这是刻意为之：
     *  云端推理型模型（智谱 GLM-5.3-Flash）会先吐 {@code reasoning_content}，
     *  {@code max_tokens=8} 会被推理全部吃掉，正文 {@code content} 就是空串
     *  （agent.md §2.7 实测记录过同一现象，当时是 max_tokens=1024 被 reasoning 吃光）。
     *  探活只回答「通道通不通、模型在不在」，正文有没有内容不是连通性问题。
     *  若按 content 判，云端通道会永远显示 DEGRADE——那是误报。 */
    private boolean extractHasChoice(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        try {
            JsonNode node = json.readTree(text);
            JsonNode choices = node.get("choices");
            return choices != null && choices.isArray() && !choices.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        String oneLine = s.replaceAll("\\s+", " ").trim();
        // health_note 列是 VARCHAR(255)，留 12 字符余量
        return oneLine.length() <= 240 ? oneLine : oneLine.substring(0, 240) + "…";
    }

    /**
     * 探活默认超时。
     *
     * @return 毫秒
     */
    public long defaultTimeoutMs() {
        return properties.getProbeTimeoutMs();
    }
}
