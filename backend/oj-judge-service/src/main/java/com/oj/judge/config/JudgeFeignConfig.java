package com.oj.judge.config;

import com.oj.common.core.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import feign.codec.ErrorDecoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class JudgeFeignConfig {
    @Bean
    RequestInterceptor judgeInnerToken(@Value("${oj.inner-token:}") String token) {
        return request -> { if (!token.isBlank()) request.header("X-Inner-Token", token); };
    }

    @Bean
    ErrorDecoder teachingErrors(ObjectMapper json) {
        return (method, response) -> {
            int status = response.status();
            if (!java.util.Set.of(400, 401, 403, 404, 409, 429, 500, 502, 504).contains(status)) status = 502;
            String message = "教务服务请求失败";
            if (response.body() != null) {
                try (var input = response.body().asInputStream()) {
                    message = json.readTree(input).path("message").asText(message);
                } catch (java.io.IOException ignored) { }
            }
            return new BusinessException(status, message);
        };
    }
}
