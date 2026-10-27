package com.oj.ai.controller;

import com.oj.common.core.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 服务健康检查（{@code GET /api/ai/health}）。
 *
 * @author oj-cloud
 */
@RestController
@RequestMapping("/api/ai")
public class HealthController {

    /**
     * 健康检查。
     *
     * @return 统一响应体，data 为 {@code "oj-ai-agent-service UP"}
     */
    @GetMapping("/health")
    public Result<String> health() {
        return Result.ok("oj-ai-agent-service UP");
    }

    /**
     * 扩展健康信息。
     *
     * @return 服务元信息
     */
    @GetMapping("/health/detail")
    public Result<Map<String, Object>> detail() {
        Map<String, Object> info = new LinkedHashMap<>(8);
        info.put("service", "oj-ai-agent-service");
        info.put("port", 8084);
        info.put("status", "UP");
        info.put("modules", new String[]{"model-channel-factory", "four-stage-sse", "gradual-hint"});
        return Result.ok(info);
    }
}
