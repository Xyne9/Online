package com.oj.ai.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class AiConfig {
    @Bean
    RequestInterceptor innerToken(@Value("${oj.inner-token:}") String token) {
        return template -> { if (!token.isBlank()) template.header("X-Inner-Token", token); };
    }

    @Bean
    ThreadPoolTaskExecutor aiExecutor() {
        var pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(2);
        pool.setMaxPoolSize(4);
        pool.setQueueCapacity(0);
        pool.setThreadNamePrefix("ai-stream-");
        return pool;
    }
}
