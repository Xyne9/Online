package com.oj.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 统一网关启动类（端口 8080）。
 *
 * <p>职责（设计文档 §1.2）：</p>
 * <ol>
 *   <li><b>路由转发</b>：按 {@code /api/{module}/**} 前缀分流到 5 个业务服务（见 application.yml）；</li>
 *   <li><b>统一鉴权</b>：{@link com.oj.gateway.filter.AuthGlobalFilter} 校验 JWT 签名 + Redis 在线态；</li>
 *   <li><b>跨域</b>：{@link com.oj.gateway.config.CorsConfig} 允许原型页面（file:// 与 localhost）直连；</li>
 *   <li><b>链路追踪</b>：生成 {@code X-Trace-Id} 透传给下游。</li>
 * </ol>
 *
 * <p><b>注意：</b>本模块基于 WebFlux，classpath 上绝不能出现 spring-webmvc，
 * 否则 Spring Cloud Gateway 会直接启动失败。因此 {@code oj-common-core} 刻意不引 web starter。</p>
 *
 * @author oj-cloud
 */
@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
