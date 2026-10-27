package com.oj.ai.model;

import java.util.List;

/** 缓存内的私有通道配置，禁止作为对外 DTO 序列化。 */
public record AiChannel(String channelCode, String channelName, String providerType, String baseUrl,
                        String apiKey, String modelName, String roleBinding, int timeoutSeconds,
                        int maxTokens, double temperature, int maxConcurrency, String healthStatus,
                        boolean enabled, List<String> roles) {
    public boolean local() { return "OLLAMA".equals(providerType); }
    public boolean available() { return enabled && !"OFF".equals(healthStatus) && !"PROBING".equals(healthStatus); }
}
