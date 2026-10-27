package com.oj.problem.controller;

import com.oj.common.core.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 题库服务健康检查（{@code GET /api/problem/health}）。
 *
 * <p>经网关 {@code /api/problem/**} 路由访问，验证「注册中心 + 负载均衡 + 网关路由」链路。</p>
 *
 * @author oj-cloud
 */
@RestController
@RequestMapping("/api/problem")
public class HealthController {

    /**
     * 健康检查。
     *
     * @return 统一响应体，data 为 {@code "oj-problem-service UP"}
     */
    @GetMapping("/health")
    public Result<String> health() {
        return Result.ok("oj-problem-service UP");
    }

    /**
     * 扩展健康信息。
     *
     * @return 服务元信息
     */
    @GetMapping("/health/detail")
    public Result<Map<String, Object>> detail() {
        Map<String, Object> info = new LinkedHashMap<>(8);
        info.put("service", "oj-problem-service");
        info.put("port", 8082);
        info.put("status", "UP");
        info.put("modules", new String[]{"problem", "test-case"});
        return Result.ok(info);
    }
}
