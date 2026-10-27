package com.oj.admin.controller;

import com.oj.common.core.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理服务健康检查（{@code GET /api/admin/health}）。
 *
 * @author oj-cloud
 */
@RestController
@RequestMapping("/api/admin")
public class HealthController {

    /**
     * 健康检查。
     *
     * @return 统一响应体，data 为 {@code "oj-admin-service UP"}
     */
    @GetMapping("/health")
    public Result<String> health() {
        return Result.ok("oj-admin-service UP");
    }

    /**
     * 扩展健康信息。
     *
     * @return 服务元信息
     */
    @GetMapping("/health/detail")
    public Result<Map<String, Object>> detail() {
        Map<String, Object> info = new LinkedHashMap<>(8);
        info.put("service", "oj-admin-service");
        info.put("port", 8085);
        info.put("status", "UP");
        info.put("modules", new String[]{"channel-probe", "audit", "sandbox-monitor"});
        return Result.ok(info);
    }
}
