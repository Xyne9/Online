package com.oj.judge.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑声明（设计文档 §3.6 的三个名字，<b>逐字不变</b>）。
 *
 * <pre>
 * 交换机 oj.judge.direct.exchange (direct)
 *    └─ 路由键 oj.judge.submit.key
 *          └─ 队列 oj.judge.submit.queue
 * </pre>
 *
 * <h2>为什么用 direct 而不是 topic / fanout</h2>
 * <p>判题只有一种消息（提交 ID），没有「按模式分发」的需求。
 * direct 的路由规则最简单、最不容易配错；topic 会让人误以为支持
 * {@code judge.submit.cpp} 这类通配，实际没有。</p>
 *
 * <h2>为什么队列要 durable + 不自动删除</h2>
 * <p>判题消息丢了就是「用户提交了但永远停在排队中」——这是最糟的体验，
 * 比重判一次糟得多。所以队列必须持久化，配合
 * {@code spring.rabbitmq.listener.simple.acknowledge-mode=manual}，
 * 只有判题结果<b>落库成功后</b>才 ack。</p>
 *
 * <h2>为什么单独配 JSON 消息转换器</h2>
 * <p>默认的 {@code SimpleMessageConverter} 用 Java 序列化，生产方/消费方
 * 类版本不一致就反序列化失败（而且消息体在管理台里是一堆乱码，排障极难）。
 * 改成 JSON 后，管理台能直接看到 {@code {"submissionId":123}}。</p>
 *
 * @author oj-cloud
 */
@Configuration
public class RabbitConfig {

    /** 交换机名（设计文档 §3.6，逐字） */
    public static final String JUDGE_EXCHANGE = "oj.judge.direct.exchange";

    /** 队列名（设计文档 §3.6，逐字） */
    public static final String JUDGE_QUEUE = "oj.judge.submit.queue";

    /** 路由键（设计文档 §3.6，逐字） */
    public static final String JUDGE_ROUTING_KEY = "oj.judge.submit.key";

    /**
     * 判题交换机（direct，持久化）。
     *
     * @return 交换机
     */
    @Bean
    public DirectExchange judgeExchange() {
        return new DirectExchange(JUDGE_EXCHANGE, true, false);
    }

    /**
     * 判题队列（持久化，不自动删除）。
     *
     * @return 队列
     */
    @Bean
    public Queue judgeQueue() {
        return QueueBuilder.durable(JUDGE_QUEUE).build();
    }

    /**
     * 绑定：{@code oj.judge.submit.key} → {@code oj.judge.submit.queue}。
     *
     * @param judgeQueue    队列
     * @param judgeExchange 交换机
     * @return 绑定关系
     */
    @Bean
    public Binding judgeBinding(Queue judgeQueue, DirectExchange judgeExchange) {
        return BindingBuilder.bind(judgeQueue).to(judgeExchange).with(JUDGE_ROUTING_KEY);
    }

    /**
     * JSON 消息转换器（生产端与消费端共用）。
     *
     * @return 转换器
     */
    @Bean
    public MessageConverter judgeMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
