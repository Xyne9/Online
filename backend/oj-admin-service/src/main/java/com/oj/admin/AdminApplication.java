package com.oj.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 管理服务启动类（端口 8085）。
 *
 * <p>承载（设计文档 §1.2 / §5）：</p>
 * <ul>
 *   <li>模型通道探活（Redis 防抖锁 {@code oj:admin:probe:{channelCode}}，TTL 10s）；</li>
 *   <li>AI 调用审计日志；</li>
 *   <li>沙箱容器监控；</li>
 *   <li>全站健康聚合（聚合另外 4 个业务服务的 actuator/health）。</li>
 * </ul>
 *
 * <p><b>第 2 步（当前）</b>：仅空壳 + 健康检查，管理接口在第 6 步实现。</p>
 *
 * @author oj-cloud
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.oj.common.api.feign")
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
