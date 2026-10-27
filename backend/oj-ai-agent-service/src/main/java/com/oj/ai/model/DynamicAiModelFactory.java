package com.oj.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.redis.RedisKeys;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/** Redis 快照热读，首次从 MySQL 装载；所有本地调用显式限定 8192 上下文。 */
@Component
public class DynamicAiModelFactory {
    private static final Logger log = LoggerFactory.getLogger(DynamicAiModelFactory.class);
    private final JdbcTemplate db;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final String ollamaBaseUrl;
    /** 统一从 Spring Environment 取密钥：同时覆盖系统环境变量与 .env.local 导入。 */
    private final org.springframework.core.env.Environment env;

    public DynamicAiModelFactory(JdbcTemplate db, StringRedisTemplate redis, ObjectMapper json,
                                @Value("${oj.ai.ollama-base-url:}") String ollamaBaseUrl,
                                org.springframework.core.env.Environment env) {
        this.db = db; this.redis = redis; this.json = json; this.ollamaBaseUrl = ollamaBaseUrl; this.env = env;
    }

    public List<AiChannel> channels() {
        var values = redis.opsForHash().values(RedisKeys.aiChannelConfig());
        if (values.isEmpty()) {
            var loaded = db.query("SELECT * FROM ai_model_config ORDER BY id", (r, n) -> {
                String code = r.getString("channel_code");
                var roles = db.queryForList("SELECT role FROM ai_model_role WHERE channel_code=? AND is_enabled=1 ORDER BY priority,id",
                        String.class, code);
                return new AiChannel(code, r.getString("channel_name"), r.getString("provider_type"), r.getString("base_url"),
                        r.getString("api_key"), r.getString("model_name"), r.getString("role_binding"),
                        r.getInt("timeout_seconds"), r.getInt("max_tokens"), r.getDouble("temperature"),
                        r.getInt("max_concurrency"), r.getString("health_status"), r.getBoolean("is_enabled"), roles);
            });
            for (var c : loaded) {
                try { redis.opsForHash().putIfAbsent(RedisKeys.aiChannelConfig(), c.channelCode(), json.writeValueAsString(c)); }
                catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
            }
            return loaded;
        }
        return values.stream().map(value -> {
            try { return json.readValue(value.toString(), AiChannel.class); }
            catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw BusinessException.badGateway("模型通道缓存格式无效，请刷新配置"); }
        }).sorted(Comparator.comparing(AiChannel::channelCode)).toList();
    }

    public AiChannel select(String code) {
        if (code == null || code.isBlank()) return local("TUTOR");
        return channels().stream().filter(c -> c.channelCode().equals(code) && c.available()
                        && (c.local() || c.roles().contains("TUTOR")))
                .findFirst().orElseThrow(() -> BusinessException.badRequest("所选 Tutor 通道不可用"));
    }

    public AiChannel local(String role) {
        return channels().stream().filter(c -> c.available() && c.local()
                        && ("TUTOR".equals(role) || c.roles().contains(role)))
                .findFirst().orElseThrow(() -> BusinessException.badGateway("本地 " + role + " 模型通道不可用"));
    }

    public List<Map<String, Object>> publicChannels() {
        return channels().stream().filter(c -> c.available() && (c.local() || c.roles().contains("TUTOR")))
                .map(c -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("channelCode", c.channelCode()); item.put("channelName", c.channelName());
                    item.put("modelName", c.modelName()); item.put("roleBinding", c.roleBinding());
                    item.put("isDefault", c.local()); item.put("healthStatus", c.healthStatus());
                    item.put("avgTtftMs", null);
                    return item;
                }).toList();
    }

    private String localUrl(AiChannel c) {
        String base = ollamaBaseUrl.isBlank() ? c.baseUrl().replace("host.docker.internal", "127.0.0.1") : ollamaBaseUrl;
        return base.replaceAll("/v1/?$", "").replaceAll("/+$", "");
    }

    public ChatLanguageModel localModel(String role) {
        var c = local(role);
        return OllamaChatModel.builder().baseUrl(localUrl(c)).modelName(c.modelName()).numCtx(8192)
                .numPredict(120).temperature(0.0).maxRetries(0).timeout(Duration.ofSeconds(12)).build();
    }

    public StreamingChatLanguageModel streaming(AiChannel c) {
        if (c.local()) {
            return OllamaStreamingChatModel.builder().baseUrl(localUrl(c)).modelName(c.modelName()).numCtx(8192)
                    .numPredict(Math.min(c.maxTokens(), 1024)).temperature(c.temperature()).timeout(Duration.ofSeconds(35)).build();
        }
        String key = resolveSecret(c.apiKey());
        if (key == null || key.isBlank()) {
            log.warn("云端通道 {} 密钥解析为空：apiKey 原始值={}", c.channelCode(), c.apiKey());
            throw BusinessException.badGateway("云端通道尚未配置密钥");
        }
        /* max_tokens 尊重通道配置，不再焊死 1024。
           推理型模型（如 GLM-5.3-Flash）会先消耗几十到几千个 reasoning token
           再出正文；上限压到 1024 时会被推理吃光，正文直接为空（实测复现）。
           本地 Ollama 仍限制 1024，因为它不是推理模型且要控显存/时长。 */
        int maxOut = Math.max(256, c.maxTokens());
        return OpenAiStreamingChatModel.builder().baseUrl(c.baseUrl()).apiKey(key).modelName(c.modelName())
                .temperature(c.temperature()).maxTokens(maxOut)
                .timeout(Duration.ofSeconds(Math.max(1, c.timeoutSeconds()))).build();
    }

    /**
     * 解析 api_key 字段。支持三种形态（按优先级）：
     *   ① 已是明文 key —— 原样返回（兼容老数据 / 管理员后台直接填）
     *   ② 环境变量占位符 —— `${OJ_ZHIPU_API_KEY}` 或 `${OJ_ZHIPU_API_KEY:默认值}`
     *   ③ 环境变量名裸写 —— 以 `env:` 开头，如 `env:OJ_ZHIPU_API_KEY`
     *
     * 取值统一走 Spring Environment，不用 System.getenv：
     *   · 系统环境变量 → Environment 也能读到
     *   · .env.local（spring.config.import）→ **只进 Environment，不进 System.getenv**
     * 历史上这里用 System.getenv 导致 .env.local 明明配了却始终解析为空。
     */
    String resolveSecret(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (value.startsWith("env:")) return lookup(value.substring(4).trim(), null);
        if (value.startsWith("${") && value.endsWith("}")) {
            String body = value.substring(2, value.length() - 1);
            int colon = body.indexOf(':');
            String name = (colon < 0 ? body : body.substring(0, colon)).trim();
            String fallback = colon < 0 ? null : body.substring(colon + 1);
            return lookup(name, fallback);
        }
        return value;
    }

    private String lookup(String name, String fallback) {
        String resolved = env == null ? null : env.getProperty(name);
        if (resolved == null || resolved.isBlank()) resolved = System.getenv(name);
        return (resolved == null || resolved.isBlank()) ? fallback : resolved;
    }
}
