package com.oj.judge.controller;

import com.oj.common.core.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 判题服务健康检查（{@code GET /api/judge/health}）。
 *
 * @author oj-cloud
 */
@RestController
@RequestMapping("/api/judge")
public class HealthController {

    /**
     * 健康检查。
     *
     * @return 统一响应体，data 为 {@code "oj-judge-service UP"}
     */
    @GetMapping("/health")
    public Result<String> health() {
        return Result.ok("oj-judge-service UP");
    }

    /**
     * 扩展健康信息。
     *
     * @return 服务元信息
     */
    @GetMapping("/health/detail")
    public Result<Map<String, Object>> detail() {
        Map<String, Object> info = new LinkedHashMap<>(8);
        info.put("service", "oj-judge-service");
        info.put("port", 8083);
        info.put("status", "UP");
        info.put("modules", new String[]{"rabbitmq-consumer", "go-judge-adapter"});
        return Result.ok(info);
    }
}
