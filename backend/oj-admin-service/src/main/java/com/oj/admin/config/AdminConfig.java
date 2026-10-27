package com.oj.admin.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 服务间调用配置。
 *
 * <p>契约声明的 Feign 客户端（{@code AiInnerFeignClient} / {@code AdminAggregateFeignClient}）
 * 被 {@code @EnableFeignClients} 创建成 Bean，调用时要带 {@code X-Inner-Token}
 * （各服务的 {@code /inner/**} 用它校验，见 {@code InnerConstants}）。
 * 令牌从配置取，缺省空串 = 目标服务未开启内部校验。</p>
 *
 * @author oj-cloud
 */
@Configuration(proxyBeanMethods = false)
public class AdminConfig {

    /**
     * 给所有 Feign 请求补 {@code X-Inner-Token}。
     *
     * @param token 内部调用令牌，来自 {@code oj.inner-token}（默认取环境变量 OJ_INNER_TOKEN）
     * @return 请求拦截器
     */
    @Bean
    public RequestInterceptor innerTokenInterceptor(@Value("${oj.inner-token:}") String token) {
        return template -> {
            if (token != null && !token.isBlank()) {
                template.header("X-Inner-Token", token);
            }
        };
    }
}
