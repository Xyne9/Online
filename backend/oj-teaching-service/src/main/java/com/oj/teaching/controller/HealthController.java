package com.oj.teaching.controller;

import com.oj.common.core.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 教务服务健康检查（{@code GET /api/teaching/health}）。
 *
 * <p><b>为什么单独写一个 HealthController 而不是只用 actuator：</b>
 * 第 2 步的验收要求是「网关 → 服务」整条链路通，actuator 的
 * {@code /actuator/health} 走的是各服务自身端口，必须经由网关路由
 * {@code /api/teaching/**} 才能证明「注册中心 + 负载均衡 + 网关路由」三者都正常。
 * 同时它返回统一 {@link Result}，也顺带验证了 {@code oj-common-core} 的可用性。</p>
 *
 * <p>网关白名单里配置了 {@code /api/&#42;/health}，因此该接口无需 Token 即可访问，
 * 方便 Day2 验收与运维探活。</p>
 *
 * @author oj-cloud
 */
@RestController
@RequestMapping("/api/teaching")
public class HealthController {

    /**
     * 健康检查。
     *
     * @return 统一响应体，data 为中文状态串 {@code "oj-teaching-service UP"}
     */
    @GetMapping("/health")
    public Result<String> health() {
        return Result.ok("oj-teaching-service UP");
    }

    /**
     * 扩展健康信息：返回服务名、端口与已装配能力，便于答辩演示时说明本服务职责。
     *
     * @return 服务元信息
     */
    @GetMapping("/health/detail")
    public Result<Map<String, Object>> detail() {
        Map<String, Object> info = new LinkedHashMap<>(8);
        info.put("service", "oj-teaching-service");
        info.put("port", 8081);
        info.put("status", "UP");
        info.put("modules", new String[]{"auth", "teaching", "plagiarism"});
        return Result.ok(info);
    }
}
