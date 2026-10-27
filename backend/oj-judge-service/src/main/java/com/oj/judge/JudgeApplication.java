package com.oj.judge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 判题服务启动类（端口 8083）。
 *
 * <p>承载（设计文档 §1.2 / §3.6）：</p>
 * <ul>
 *   <li>RabbitMQ 异步判题：交换机 {@code oj.judge.direct.exchange} → 队列 {@code oj.judge.submit.queue}
 *       → 路由键 {@code oj.judge.submit.key}；</li>
 *   <li>Go-Judge 沙箱适配（HTTP 提交 + 1s 轮询）；</li>
 *   <li>提交状态机与 3 秒防抖锁（Redis {@code oj:judge:lock:{userId}}）。</li>
 * </ul>
 *
 * <p><b>第 2 步（当前）</b>：仅空壳 + 健康检查，判题逻辑在第 5 步实现。
 * 此时队列/交换机尚未声明，RabbitMQ 连接只在首次使用时建立，不影响启动。</p>
 *
 * @author oj-cloud
 */
@SpringBootApplication
@EnableDiscoveryClient
public class JudgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(JudgeApplication.class, args);
    }
}
