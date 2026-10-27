package com.oj.ai.service;

import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.redis.RedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 先原子预留并发与配额，成功落库后确认消费；异常、取消仅释放自己的预留。 */
@Service
public class AiQuotaService {
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            local active = tonumber(redis.call('GET', KEYS[1]) or '0')
            if active >= 2 then return -1 end
            if tonumber(ARGV[1]) >= 0 then
              local used = tonumber(redis.call('GET', KEYS[2]) or '0')
              if used >= tonumber(ARGV[1]) then return -2 end
              redis.call('INCR', KEYS[2]); redis.call('PEXPIRE', KEYS[2], ARGV[2])
            end
            redis.call('INCR', KEYS[1]); redis.call('EXPIRE', KEYS[1], 60)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if tonumber(redis.call('GET', KEYS[1]) or '0') > 0 then redis.call('DECR', KEYS[1]) end
            if ARGV[1] == '1' and tonumber(redis.call('GET', KEYS[2]) or '0') > 0 then redis.call('DECR', KEYS[2]) end
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final AiHistoryService history;

    public AiQuotaService(StringRedisTemplate redis, AiHistoryService history) { this.redis = redis; this.history = history; }

    private String key(AiAccessService.Context c) { return RedisKeys.aiQuota(c.assignmentId(), c.problemId(), c.userId()); }
    private long ttl(AiAccessService.Context c) {
        Long end = c.permission().getDeadlineAt();
        return end == null ? Duration.ofDays(1).toMillis() : Math.max(1, end - System.currentTimeMillis());
    }
    private void initialize(AiAccessService.Context c) {
        if (c.maxQuota() >= 0 && !Boolean.TRUE.equals(redis.hasKey(key(c)))) {
            redis.opsForValue().setIfAbsent(key(c), String.valueOf(history.used(c.userId(), c.assignmentId(), c.problemId())), Duration.ofMillis(ttl(c)));
        }
    }
    public int remaining(AiAccessService.Context c) {
        if (c.maxQuota() < 0) return -1;
        initialize(c);
        String used = redis.opsForValue().get(key(c));
        return Math.max(0, c.maxQuota() - (used == null ? 0 : Integer.parseInt(used)));
    }
    public Lease reserve(AiAccessService.Context c) {
        initialize(c);
        String rate = RedisKeys.rateAiStream(c.userId());
        Long result = redis.execute(RESERVE, List.of(rate, key(c)), String.valueOf(c.maxQuota()), String.valueOf(ttl(c)));
        if (result == null || result == -1) throw BusinessException.tooManyRequests("同时最多进行两条 AI 对话，请等待当前回答结束");
        if (result == -2) throw BusinessException.tooManyRequests("本题 AI 提示配额已耗尽");
        return new Lease(rate, key(c), c.maxQuota() >= 0);
    }

    public final class Lease implements AutoCloseable {
        private final String rate;
        private final String quota;
        private final boolean finite;
        private boolean success;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease(String rate, String quota, boolean finite) { this.rate = rate; this.quota = quota; this.finite = finite; }
        public boolean closed() { return closed.get(); }
        public synchronized void commit(Runnable save) {
            if (closed.get()) throw BusinessException.conflict("对话已取消");
            save.run();
            success = true;
        }
        @Override public synchronized void close() {
            if (closed.compareAndSet(false, true)) redis.execute(RELEASE, List.of(rate, quota), finite && !success ? "1" : "0");
        }
    }
}
