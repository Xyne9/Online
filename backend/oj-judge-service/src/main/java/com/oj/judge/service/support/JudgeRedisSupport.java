package com.oj.judge.service.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.redis.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 判题服务的 Redis 辅助组件：防抖锁 + 展示号序列。
 *
 * <p><b>为什么不直接写在 service 里：</b>防抖锁与序列号在 self-test / submit /
 * run-sample 三个入口都要用，抽出来才能保证「三个入口用同一把锁、同一个序列」。
 * 契约明确要求 run-sample 与 submit 共用 {@code oj:judge:lock:{userId}}，
 * 复制粘贴迟早出现两把不同的锁。</p>
 *
 * <p><b>Key 一律走 {@link RedisKeys}</b>（项目铁律：禁止手写 {@code "oj:xxx:"} 字符串）。</p>
 *
 * @author oj-cloud
 */
@Component
public class JudgeRedisSupport {

    private static final Logger log = LoggerFactory.getLogger(JudgeRedisSupport.class);

    /** 提交展示号前缀（契约：{@code S-20481}，不补零） */
    private static final String SUBMISSION_PREFIX = "S-";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public JudgeRedisSupport(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 尝试获取 3 秒判题防抖锁（SETNX）。
     *
     * <p><b>Redis 不可用时的策略：放行。</b>判题是核心功能，防抖只是「防连点」的
     * 体验优化；Redis 挂掉时若直接 429，整个判题就不可用了，代价远大于收益。
     * 因此这里 catch 住连接异常并打 WARN，让请求继续走。</p>
     *
     * @param userId 用户 ID（null = 未登录，直接放行，不做锁）
     * @return true = 拿到锁 / 无需加锁；false = 3 秒内已提交过
     */
    public boolean tryAcquireJudgeLock(Long userId) {
        if (userId == null) {
            return true;
        }
        String key = RedisKeys.judgeLock(userId);
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", Duration.ofSeconds(RedisKeys.TTL_JUDGE_LOCK));
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("判题防抖锁不可用（Redis 异常），本次放行：{}", e.getMessage());
            return true;
        }
    }

    /**
     * 释放判题防抖锁（判题失败需要立刻重试时用；正常流程让它自然过期）。
     *
     * @param userId 用户 ID
     */
    public void releaseJudgeLock(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            redisTemplate.delete(RedisKeys.judgeLock(userId));
        } catch (Exception e) {
            log.warn("释放判题防抖锁失败：{}", e.getMessage());
        }
    }

    /**
     * 生成下一个提交展示号：{@code INCR oj:seq:submission} → {@code S-{n}}。
     *
     * <p><b>Redis 不可用时退化为「时间戳后 8 位」</b>：展示号有唯一索引，
     * 时间戳方案在单机演示场景下几乎不会撞；真撞了会抛唯一键冲突，
     * 比「整个提交接口不可用」好得多。</p>
     *
     * @return 展示号，如 {@code S-20481}
     */
    public String nextSubmissionDisplayNo() {
        try {
            Long seq = redisTemplate.opsForValue().increment(RedisKeys.seq(RedisKeys.SEQ_SUBMISSION));
            if (seq != null && seq > 0) {
                return SUBMISSION_PREFIX + seq;
            }
        } catch (Exception e) {
            log.warn("提交展示号序列不可用（Redis 异常），退化为时间戳：{}", e.getMessage());
        }
        return SUBMISSION_PREFIX + (System.currentTimeMillis() % 100_000_000L);
    }

    /**
     * 安全地把 JSON 字符串解析成 {@code List<Map<String,Object>>}。
     *
     * <p>{@code judge_submission.case_details} 是 TEXT/JSON 列，历史数据可能为空、
     * 也可能是非法 JSON（人工改库导致）。解析失败一律返回空列表 ——
     * 详情接口不该因为一条脏数据 500。</p>
     *
     * @param json JSON 字符串
     * @return 列表（绝不为 null）
     */
    public List<Map<String, Object>> parseCaseDetails(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> parsed = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            return parsed == null ? new ArrayList<>() : parsed;
        } catch (Exception e) {
            log.warn("case_details 解析失败，按空列表处理：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 安全地把 JSON 字符串解析成 {@code Map<String,Object>}。
     *
     * @param json JSON 字符串
     * @return map（绝不为 null）
     */
    public Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {
                    });
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception e) {
            log.warn("JSON 解析失败，按空对象处理：{}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * 序列化为 JSON 字符串（失败返回 null，由调用方决定兜底）。
     *
     * @param value 任意对象
     * @return JSON 或 null
     */
    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("序列化失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 断言拿到防抖锁，否则抛 429（契约 §4.6 错误码）。
     *
     * @param userId 用户 ID
     * @throws BusinessException 429
     */
    public void requireJudgeLock(Long userId) {
        if (!tryAcquireJudgeLock(userId)) {
            throw BusinessException.tooManyRequests("操作过于频繁，请 3 秒后再试");
        }
    }
}
