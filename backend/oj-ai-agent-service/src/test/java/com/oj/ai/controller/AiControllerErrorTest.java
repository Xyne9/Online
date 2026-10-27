package com.oj.ai.controller;

import com.oj.ai.model.DynamicAiModelFactory;
import com.oj.ai.service.*;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiControllerErrorTest {
    @Test void sseAcceptHeaderStillGetsJson403BeforeStreamStarts() throws Exception {
        var orchestration = mock(HeuristicAgentOrchestrator.class);
        when(orchestration.prepare(anyLong(), any())).thenThrow(BusinessException.forbidden("输入护栏拦截"));
        var controller = new AiController(mock(AiWorkspaceService.class), mock(AiHistoryService.class),
                mock(DynamicAiModelFactory.class), orchestration, mock(AiQuotaService.class), mock(ThreadPoolTaskExecutor.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/api/ai/chat/stream").header("X-User-Id", "1").header("X-User-Role", "STUDENT")
                .accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON)
                .content("{\"problemId\":1024,\"assignmentId\":0,\"capsuleStage\":1,\"userMessage\":\"hello\"}"))
                .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(403));
    }
    @Test void invalidSseBodyReturnsJson400() throws Exception {
        var controller = new AiController(mock(AiWorkspaceService.class), mock(AiHistoryService.class),
                mock(DynamicAiModelFactory.class), mock(HeuristicAgentOrchestrator.class), mock(AiQuotaService.class), mock(ThreadPoolTaskExecutor.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/api/ai/chat/stream").accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON)
                .content("{\"problemId\":1024,\"assignmentId\":0,\"capsuleStage\":8,\"userMessage\":\"hello\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }
}
