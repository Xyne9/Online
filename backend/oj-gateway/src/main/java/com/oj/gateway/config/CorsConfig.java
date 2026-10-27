package com.oj.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * 网关跨域配置（设计文档 §1.2：前端原型以 file:// 或 localhost 静态服务器打开）。
 *
 * <p>为什么在网关统一处理：5 个业务服务各自配 CORS 必然漂移（漏配 Content-Type、漏配 PUT/DELETE），
 * 而浏览器只认最终响应头。网关是唯一入口，在这里一次性放行最省心。</p>
 *
 * <p><b>安全提示：</b>使用 {@link CorsConfiguration#setAllowedOriginPatterns} 而非
 * {@code setAllowedOrigins("*")}，因为前者可以与 {@code allowCredentials(true)} 共存
 * （后者在带 Cookie 时会被浏览器拒绝）。</p>
 *
 * @author oj-cloud
 */
@Configuration
public class CorsConfig {

    /**
     * 全局 CORS 过滤器。
     *
     * @return CorsWebFilter 实例
     */
    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // 允许任意来源（含 file:// 打开的 null origin）：原型页面可能直接从磁盘双击打开
        config.setAllowedOriginPatterns(List.of("*"));

        // 网关只做转发，方法全放行
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"));

        // 全部请求头放行：Authorization / X-Trace-Id / Content-Type / X-Inner-Token 等
        config.setAllowedHeaders(List.of("*"));

        // 让前端能读到链路追踪 ID（排障用）
        config.setExposedHeaders(Arrays.asList("X-Trace-Id", "Content-Disposition"));

        // 允许携带凭据（后续若用 Cookie 存 refreshToken 需要）
        config.setAllowCredentials(true);

        // 预检结果缓存 1 小时，减少 OPTIONS 请求
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
