package com.oj.ai.service;

import com.oj.common.api.dto.judge.AiSubmissionStateDTO;
import com.oj.common.api.dto.problem.ProblemAiRagDTO;
import com.oj.common.api.dto.teaching.AiPermissionVerifyDTO;
import com.oj.common.core.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiQuotaServiceTest {
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final AiHistoryService history = mock(AiHistoryService.class);
    final ValueOperations<String,String> values = mock(ValueOperations.class);
    AiQuotaService service;
    AiAccessService.Context context;
    @BeforeEach void setup() {
        when(redis.opsForValue()).thenReturn(values); when(redis.hasKey(anyString())).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        var permission = new AiPermissionVerifyDTO(); permission.setMaxQuota(3);
        context = new AiAccessService.Context(1L, 2L, 1024L, new ProblemAiRagDTO(), permission, new AiSubmissionStateDTO(true, false));
        service = new AiQuotaService(redis, history);
    }
    @Test void failedOrCancelledStreamRefundsExactlyOnce() {
        var lease = service.reserve(context); lease.close(); lease.close();
        verify(redis, times(1)).execute(any(RedisScript.class), eq(List.of("oj:rate:ai:stream:1", "oj:ai:quota:2:1024:1")), eq("1"));
    }
    @Test void successfullySavedResponseConsumesQuotaAndCannotBeRefundedOnCompletion() {
        var saved = new java.util.concurrent.atomic.AtomicBoolean();
        var lease = service.reserve(context); lease.commit(() -> saved.set(true)); lease.close();
        assertTrue(saved.get());
        verify(redis).execute(any(RedisScript.class), anyList(), eq("0"));
    }
    @Test void cancelledLeaseCannotPersistAResponse() {
        var lease = service.reserve(context); lease.close();
        assertThrows(BusinessException.class, () -> lease.commit(() -> fail("取消后不应落库")));
    }
    @Test void storageFailureReleasesReservedQuota() {
        var lease = service.reserve(context);
        assertThrows(IllegalStateException.class, () -> lease.commit(() -> { throw new IllegalStateException(); }));
        lease.close(); verify(redis).execute(any(RedisScript.class), anyList(), eq("1"));
    }
    @Test void concurrentAndQuotaDenialsAre429() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(-1L, -2L);
        assertEquals(429, assertThrows(BusinessException.class, () -> service.reserve(context)).getCode());
        assertEquals(429, assertThrows(BusinessException.class, () -> service.reserve(context)).getCode());
    }
}
