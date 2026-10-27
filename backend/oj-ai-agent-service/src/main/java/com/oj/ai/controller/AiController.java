package com.oj.ai.controller;

import com.oj.ai.dto.ChatRequest;
import com.oj.ai.model.DynamicAiModelFactory;
import com.oj.ai.service.*;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.result.Result;
import com.oj.common.web.context.UserContext;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final AiWorkspaceService workspace;
    private final AiHistoryService history;
    private final DynamicAiModelFactory models;
    private final HeuristicAgentOrchestrator orchestrator;
    private final AiQuotaService quota;
    private final ThreadPoolTaskExecutor executor;

    public AiController(AiWorkspaceService workspace, AiHistoryService history, DynamicAiModelFactory models,
                        HeuristicAgentOrchestrator orchestrator, AiQuotaService quota, ThreadPoolTaskExecutor executor) {
        this.workspace = workspace; this.history = history; this.models = models;
        this.orchestrator = orchestrator; this.quota = quota; this.executor = executor;
    }
    private Long user() {
        Long uid = UserContext.userId();
        if (!"STUDENT".equals(UserContext.role()) && !"TEACHER".equals(UserContext.role())) throw BusinessException.forbidden("当前角色不可使用 AI 助教");
        return uid;
    }
    @GetMapping("/workspace-init")
    public Result<?> workspace(@RequestParam Long problemId, @RequestParam(defaultValue = "0") Long assignmentId) {
        return Result.ok(workspace.workspace(user(), assignmentId, problemId));
    }
    @GetMapping("/quota")
    public Result<?> quota(@RequestParam Long problemId, @RequestParam Long assignmentId) {
        return Result.ok(workspace.quota(user(), assignmentId, problemId));
    }
    @GetMapping("/channels")
    public Result<?> channels() {
        user(); return Result.ok(java.util.Map.of("list", models.publicChannels(), "defaultChannelCode", models.local("TUTOR").channelCode()));
    }
    @GetMapping("/sessions")
    public Result<?> sessions(@RequestParam(required = false) Long problemId, @RequestParam(required = false) Long assignmentId,
                              @RequestParam(required = false) Integer stage, @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(history.sessions(user(), assignmentId, problemId, stage, page, pageSize));
    }
    public record FeedbackRequest(Long recordId, Boolean helpful, String reasonCode, String comment) { }
    @PostMapping("/feedback")
    public Result<?> feedback(@RequestBody FeedbackRequest request) {
        return Result.ok(history.feedback(user(), request.recordId(), request.helpful(), request.reasonCode(), request.comment()));
    }
    @PostMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream(@Valid @RequestBody ChatRequest request, HttpServletResponse response) {
        var prepared = orchestrator.prepare(user(), request);
        var lease = quota.reserve(prepared.context());
        var emitter = new SseEmitter(58000L);
        emitter.onCompletion(lease::close);
        emitter.onTimeout(lease::close);
        emitter.onError(error -> lease.close());
        response.setHeader("Cache-Control", "no-cache"); response.setHeader("X-Accel-Buffering", "no");
        try { executor.execute(() -> orchestrator.run(prepared, lease, emitter)); }
        catch (org.springframework.core.task.TaskRejectedException e) { lease.close(); throw BusinessException.tooManyRequests("AI 服务繁忙，请稍后重试"); }
        return emitter;
    }
}
