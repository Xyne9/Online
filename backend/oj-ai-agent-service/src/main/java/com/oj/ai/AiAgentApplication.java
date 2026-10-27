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
 * <p><b>第 2 步（当前）</b>：仅空壳 + 健康检查。按父 POM 的约定，
 * <b>不引入 langchain4j 任何实际依赖</b>，等第 3 阶段开工时再补。</p>
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
