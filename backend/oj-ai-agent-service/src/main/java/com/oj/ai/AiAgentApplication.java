package com.oj.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * AI 智能体服务启动类（端口 8084）。
 *
 * <p>承载（设计文档 §1.2 / §4）：</p>
 * <ul>
 *   <li>端云多模型工厂：DeepSeek / 通义千问 / 本地 Ollama 的降级链；</li>
 *   <li>四阶段启发式对话 SSE：审题 → 定位 → 拆解 → 伪代码；</li>
 *   <li>渐进式提示阶梯（{@code oj:ai:level}）与作业配额（{@code oj:ai:quota}）。</li>
 * </ul>
 *
 * <p>第 4 步：LangChain4j 本地 Router/Guardrail、端云 Tutor、六事件 SSE 与服务端配额。</p>
 *
 * @author oj-cloud
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.oj.common.api.feign")
public class AiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiAgentApplication.class, args);
    }
}
