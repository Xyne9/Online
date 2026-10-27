package com.oj.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.ai.dto.ChatRequest;
import com.oj.ai.entity.AiChatRecord;
import com.oj.ai.guard.InputInjectionGuard;
import com.oj.ai.model.*;
import com.oj.common.api.dto.judge.AiSubmissionStateDTO;
import com.oj.common.api.dto.problem.ProblemAiRagDTO;
import com.oj.common.api.dto.teaching.AiPermissionVerifyDTO;
import com.oj.common.api.feign.JudgeInnerFeignClient;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HeuristicAgentOrchestratorTest {
    final DynamicAiModelFactory models = mock(DynamicAiModelFactory.class);
    final AiHistoryService history = mock(AiHistoryService.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final ListOperations<String, String> lists = mock(ListOperations.class);
    AiQuotaService quota;
    HeuristicAgentOrchestrator orchestrator;
    AiAccessService.Context context;
    AiChannel local = channel("local", true), cloud = channel("cloud", false);
    AiChatRecord saved;
    static AiChannel channel(String name, boolean local) {
        return new AiChannel(name, name, local ? "OLLAMA" : "OPENAI_COMPATIBLE", "http://unused", "", "test-model",
                "TUTOR_OPTIONAL", 8, 1000, 0.3, 4, "ON", true, List.of("TUTOR", "ROUTER", "GUARDRAIL"));
    }
    @BeforeEach void setup() {
        when(redis.opsForValue()).thenReturn(values); when(redis.opsForList()).thenReturn(lists);
        when(redis.hasKey(anyString())).thenReturn(true); when(values.increment(anyString())).thenReturn(1L);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        when(lists.range(anyString(), anyLong(), anyLong())).thenReturn(List.of());
        ChatLanguageModel router = mock(ChatLanguageModel.class);
        when(router.generate(anyList())).thenReturn(Response.from(AiMessage.from("请先手算边界，再分析变量含义。")));
        when(models.localModel(anyString())).thenReturn(router); when(models.local(anyString())).thenReturn(local);
        doAnswer(inv -> { saved = inv.getArgument(0); saved.setId(123L); return null; }).when(history).save(any());
        var permission = new AiPermissionVerifyDTO(); permission.setMaxQuota(3); permission.setAllowed(true);
        permission.setAllowedStages(List.of(1, 2, 3, 4));
        var problem = new ProblemAiRagDTO(); problem.setTitle("测试题"); problem.setAiEnabled(true);
        context = new AiAccessService.Context(1L, 2L, 1024L, problem, permission, new AiSubmissionStateDTO(true, true));
        quota = new AiQuotaService(redis, history);
        orchestrator = new HeuristicAgentOrchestrator(mock(AiAccessService.class), quota, history, models,
                mock(JudgeInnerFeignClient.class), mock(com.oj.common.api.feign.ProblemInnerFeignClient.class), redis, new ObjectMapper(), new InputInjectionGuard());
    }
    void answer(String text) {
        StreamingChatLanguageModel stream = mock(StreamingChatLanguageModel.class);
        doAnswer(inv -> {
            StreamingResponseHandler<AiMessage> handler = inv.getArgument(1);
            for (char c : text.toCharArray()) handler.onNext(String.valueOf(c));
            handler.onComplete(Response.from(AiMessage.from(text)));
            return null;
        }).when(stream).generate(anyList(), any(StreamingResponseHandler.class));
        when(models.streaming(local)).thenReturn(stream);
    }
    Capture run(AiChannel selected) {
        var request = new ChatRequest(1024L, 2L, selected.channelCode(), 1, "请解释初始值", "int ans=0;");
        var events = new Capture();
        orchestrator.run(new HeuristicAgentOrchestrator.Prepared(context, selected, request, "test-trace"), quota.reserve(context), events);
        return events;
    }
    @Test void normalTurnStreamsThenPersistsAndConsumesQuota() {
        answer("先手算最小输入，再检查初始值。\n"); var result = run(local);
        assertEquals(List.of("router_step", "tool_calling"), result.names.subList(0, 2));
        assertTrue(result.names.contains("token")); assertEquals("done", result.names.get(result.names.size() - 1));
        assertEquals("先手算最小输入，再检查初始值。\n", saved.getAiResponse());
        assertEquals("1: int ans=0;\n", saved.getNumberedCode());
        verify(redis).execute(any(RedisScript.class), anyList(), eq("0"));
    }
    @Test void cloudFailureBeforeFirstTokenFallsBackWithoutLosingQuestion() {
        answer("请观察当前变量的初始含义。\n");
        when(models.streaming(cloud)).thenThrow(new IllegalStateException("unavailable"));
        var result = run(cloud);
        assertTrue(result.names.contains("fallback_notice")); assertEquals("done", result.names.get(result.names.size() - 1));
        assertEquals(1, saved.getIsFallback()); assertEquals("请解释初始值", saved.getUserMessage());
    }
    @Test void emptyCloudDeltaDoesNotPreventFallbackWhenTheProviderThenFails() {
        answer("请先检查边界。\n");
        StreamingChatLanguageModel stream = mock(StreamingChatLanguageModel.class);
        doAnswer(inv -> {
            StreamingResponseHandler<AiMessage> handler = inv.getArgument(1);
            handler.onNext(""); handler.onError(new IllegalStateException()); return null;
        }).when(stream).generate(anyList(), any(StreamingResponseHandler.class));
        when(models.streaming(cloud)).thenReturn(stream);
        assertTrue(run(cloud).names.contains("fallback_notice")); assertEquals(1, saved.getIsFallback());
    }
    @Test void cloudWithoutActualTextFallsBackAfterEightSeconds() {
        answer("请先检查边界。\n");
        StreamingChatLanguageModel stream = mock(StreamingChatLanguageModel.class);
        doAnswer(inv -> {
            StreamingResponseHandler<AiMessage> handler = inv.getArgument(1);
            handler.onNext(""); return null;
        }).when(stream).generate(anyList(), any(StreamingResponseHandler.class));
        when(models.streaming(cloud)).thenReturn(stream);
        long started = System.nanoTime();
        var events = run(cloud);
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 7900);
        assertTrue(events.names.contains("fallback_notice")); assertEquals("done", events.names.get(events.names.size() - 1));
    }
    @Test void longCodeNeverStreamsAndLocalGuardRewritesAuditRecord() {
        answer("```cpp\n1\n2\n3\n4\n5\nSECRET_SIXTH\n```\n"); var result = run(local);
        assertTrue(result.names.contains("guardrail_rewrite")); assertFalse(result.names.contains("token"));
        assertEquals(2, saved.getGuardrailAction()); assertEquals("OVER_LENGTH_CODE", saved.getGuardReason());
        assertFalse(saved.getAiResponse().contains("SECRET_SIXTH"));
    }
    @Test void localFailureReturnsErrorAndRefundsWithoutSavingAnAnswer() {
        when(models.streaming(local)).thenThrow(new IllegalStateException()); var result = run(local);
        assertEquals("error", result.names.get(result.names.size() - 1)); assertNull(saved);
        verify(redis).execute(any(RedisScript.class), anyList(), eq("1"));
    }
    static class Capture extends SseEmitter {
        final List<String> names = new ArrayList<>();
        @Override public void send(SseEventBuilder builder) {
            for (var data : builder.build()) if (data.getData() instanceof String text) {
                var matcher = java.util.regex.Pattern.compile("event:([^\\n]+)").matcher(text);
                if (matcher.find()) names.add(matcher.group(1));
            }
        }
    }
}
