package com.oj.teaching.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.common.api.constant.InnerConstants;
import com.oj.common.core.exception.BusinessException;
import feign.RequestInterceptor;
import feign.codec.ErrorDecoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class PlagiarismConfig {
    @Bean
    RequestInterceptor teachingInnerToken(@Value("${oj.inner-token:${OJ_INNER_TOKEN:}}") String token) {
        return request -> { if (!token.isBlank()) request.header(InnerConstants.INNER_TOKEN_HEADER, token); };
    }
    @Bean
    ErrorDecoder teachingUpstreamErrors(ObjectMapper json) {
        return (method, response) -> {
            int status = response.status();
            if (!java.util.Set.of(400,401,403,404,409,429,500,502,504).contains(status)) status = 502;
            String message = "内部依赖请求失败";
            if (response.body() != null) {
                try (var input = response.body().asInputStream()) { message = json.readTree(input).path("message").asText(message); }
                catch (java.io.IOException ignored) { }
            }
            return new BusinessException(status, message);
        };
    }
    @Bean("plagiarismExecutor")
    ThreadPoolTaskExecutor plagiarismExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("plagiarism-"); executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
