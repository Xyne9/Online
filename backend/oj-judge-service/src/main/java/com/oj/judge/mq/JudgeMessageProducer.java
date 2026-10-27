package com.oj.judge.mq;

import com.oj.judge.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 判题消息生产者（{@code oj.judge.submit.key}）。
 *
 * <p><b>发送失败不能让提交接口失败</b>：提交记录已经落库（status=0 排队中），
 * 此时抛异常会让用户看到「提交失败」，但库里已经有一条记录 —— 用户重试就会产生
 * 两条记录，其中一条永远停在排队中。因此这里 catch 住异常、打 ERROR 日志，
 * 并把「消息发送失败」作为返回值交给调用方决定是否要把状态直接落成 RE。</p>
 *
 * <p>调用方 {@code JudgeServiceImpl#submit} 的处理：发送失败时把该提交直接落成
 * RE 并写明「判题队列不可用」，而不是让它永远卡在「排队中」。</p>
 *
 * @author oj-cloud
 */
@Component
public class JudgeMessageProducer {

    private static final Logger log = LoggerFactory.getLogger(JudgeMessageProducer.class);

    private final RabbitTemplate rabbitTemplate;

    public JudgeMessageProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 发送判题消息。
     *
     * @param submissionId 提交 ID
     * @param problemId    题目 ID（仅日志用，消费方以库为准）
     * @param userId       用户 ID（仅日志用）
     * @return true = 已投递；false = 投递失败（调用方需降级处理）
     */
    public boolean sendJudgeTask(Long submissionId, Long problemId, Long userId) {
        Map<String, Object> payload = new LinkedHashMap<>(4);
        payload.put("submissionId", submissionId);
        payload.put("problemId", problemId);
        payload.put("userId", userId);
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.JUDGE_EXCHANGE, RabbitConfig.JUDGE_ROUTING_KEY, payload);
            log.debug("判题消息已投递：submissionId={}", submissionId);
            return true;
        } catch (Exception e) {
            log.error("判题消息投递失败：submissionId={} problemId={} {}", submissionId, problemId, e.getMessage());
            return false;
        }
    }
}
