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
 *       → 路由键 {@code oj.judge.submit.key}（拓扑在 {@code config/RabbitConfig} 声明）；</li>
 *   <li>Go-Judge 沙箱适配：{@code adapter/} 包（两阶段调用链，见 {@code JudgeAdapter} 类注释）；</li>
 *   <li>提交状态机与 3 秒防抖锁（Redis {@code oj:judge:lock:{userId}}）。</li>
 * </ul>
 *
 * <p><b>公共基座装配：</b>{@code com.oj.common.web} 不在本类的组件扫描范围内
 * （默认只扫 {@code com.oj.judge} 及其子包），由 {@code config/CommonWebConfig}
 * 显式 {@code @ComponentScan} 引入 —— 否则 {@code GlobalExceptionHandler} 不生效，
 * 所有业务异常都会退化成 500。</p>
 *
 * <p><b>自检入口（不启动 Web 服务）：</b></p>
 * <ul>
 *   <li>{@code adapter.JudgeAdapter#main} —— 单位换算与状态映射；</li>
 *   <li>{@code adapter.JudgeExecutorSelfTest#main} —— 真实 go-judge 的三语言端到端判题；</li>
 *   <li>{@code JudgePipelineCheck#main} —— MySQL / Redis / go-judge 全链路；</li>
 *   <li>{@code JudgeMqCheck#main} —— RabbitMQ 真实收发。</li>
 * </ul>
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
