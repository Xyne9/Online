package com.oj.judge.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.judge.config.RabbitConfig;
import com.oj.judge.service.JudgeService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 判题队列消费者（{@code oj.judge.submit.queue}）。
 *
 * <h2>消息体</h2>
 * <p>{@code {"submissionId": 20481, "problemId": 1006, "userId": 1}}。
 * 消费方<b>只信 {@code submissionId}</b>，其余字段仅用于日志——
 * 判题所需的题目配置、源码、测试点全部从库里重新查，
 * 避免「消息里的题目配置已过期」这类幽灵 bug。</p>
 *
 * <h2>为什么手动解析 JSON 而不用 Map 参数</h2>
 * <p>用 {@code Map<String,Object> payload} 参数依赖 {@code Jackson2JsonMessageConverter}
 * 正确装配；一旦有人改了 {@code spring.rabbitmq.*} 或换了容器工厂，
 * 消息会在<b>进入方法之前</b>就抛 {@code MessageConversionException}，
 * 表现为「消费者一直报错但看不到业务日志」。这里直接拿 {@link Message} 的原始
 * body 自己解析，任何形态的脏消息都能落到日志里，排障成本最低。</p>
 *
 * <h2>手动 ack 的三条规则</h2>
 * <ol>
 *   <li>判题成功（含 CE/WA/TLE 等用户侧结果）→ {@code basicAck}；</li>
 *   <li>判题抛异常 → {@code basicNack(requeue=false)} 且<b>不重试</b>：
 *       判题是幂等的（会覆盖写 status），但沙箱故障时立刻重投只会把队列打爆；
 *       由 {@code JudgeServiceImpl} 把失败落成 RE + errorMsg，用户可手动重判；</li>
 *   <li>消息体解析不出来（脏消息）→ ack 丢弃并打 ERROR，否则它会永远卡在队列头。</li>
 * </ol>
 *
 * <p><b>为什么 concurrency=1：</b>见 {@code application.yml}。判题是重 CPU 任务，
 * 一个消费者占满编译/运行资源，并发 2 以上只会让每个请求都变慢。</p>
 *
 * @author oj-cloud
 */
@Component
public class JudgeMqConsumer {

    private static final Logger log = LoggerFactory.getLogger(JudgeMqConsumer.class);

    private final JudgeService judgeService;
    private final ObjectMapper objectMapper;

    public JudgeMqConsumer(JudgeService judgeService, ObjectMapper objectMapper) {
        this.judgeService = judgeService;
        this.objectMapper = objectMapper;
    }

    /**
     * 消费判题消息。
     *
     * @param message     原始消息（自己解析 body，不依赖消息转换器）
     * @param channel     AMQP 通道（手动 ack 用）
     * @param deliveryTag 投递标签
     */
    @RabbitListener(queues = RabbitConfig.JUDGE_QUEUE)
    public void onJudgeMessage(Message message,
                               Channel channel,
                               @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        Long submissionId = extractSubmissionId(message);
        if (submissionId == null) {
            log.error("判题消息缺少 submissionId，丢弃：body={}", rawBody(message));
            ack(channel, deliveryTag);
            return;
        }
        long start = System.currentTimeMillis();
        try {
            log.info("收到判题消息：submissionId={} body={}", submissionId, rawBody(message));
            judgeService.judgeSubmission(submissionId);
            ack(channel, deliveryTag);
            log.info("判题消息处理完成：submissionId={} 耗时={}ms", submissionId,
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 判题服务内部已把「基础设施异常」落成 RE，这里只负责不让消息无限重投
            log.error("判题消息处理异常（不重投）：submissionId={}", submissionId, e);
            nack(channel, deliveryTag);
        }
    }

    /** 从消息体里取 submissionId（兼容 {"submissionId":123} 与裸数字两种形态） */
    private Long extractSubmissionId(Message message) {
        String body = rawBody(message);
        if (body.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node == null || node.isNull()) {
                return null;
            }
            JsonNode idNode = node.isObject() ? node.get("submissionId") : node;
            if (idNode == null || idNode.isNull()) {
                return null;
            }
            if (idNode.isNumber()) {
                return idNode.asLong();
            }
            String text = idNode.asText("").trim();
            return text.isEmpty() ? null : Long.parseLong(text);
        } catch (Exception e) {
            log.warn("判题消息体解析失败：{}", body, e);
            return null;
        }
    }

    /** 消息体原文（截断，避免日志被大消息打爆） */
    private String rawBody(Message message) {
        if (message == null || message.getBody() == null) {
            return "";
        }
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        return body.length() <= 500 ? body : body.substring(0, 500) + "...";
    }

    /** 安全 ack（通道已关闭时不抛异常打断消费循环） */
    private void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            log.warn("ack 失败（通道可能已关闭）：tag={} {}", deliveryTag, e.getMessage());
        }
    }

    /** 安全 nack（requeue=false，避免死循环重投） */
    private void nack(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (IOException e) {
            log.warn("nack 失败（通道可能已关闭）：tag={} {}", deliveryTag, e.getMessage());
        }
    }
}
