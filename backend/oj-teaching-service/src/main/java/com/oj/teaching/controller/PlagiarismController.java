package com.oj.teaching.controller;

import com.oj.common.core.result.Result;
import com.oj.teaching.dto.PlagiarismRequests;
import com.oj.teaching.service.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/teaching/plagiarism")
public class PlagiarismController {
    private final PlagiarismTaskService tasks;
    private final PlagiarismQueryService queries;
    private final PlagiarismReviewService reviews;
    private final PlagiarismProgressService progress;
    public PlagiarismController(PlagiarismTaskService tasks, PlagiarismQueryService queries, PlagiarismReviewService reviews, PlagiarismProgressService progress) {
        this.tasks = tasks; this.queries = queries; this.reviews = reviews; this.progress = progress;
    }
    @PostMapping("/check") public Result<?> check(@RequestBody PlagiarismRequests.Check request) { return Result.ok(tasks.check(request)); }
    @PostMapping("/tasks") public Result<?> create(@RequestBody PlagiarismRequests.Task request) { return Result.ok(tasks.create(request)); }
    @GetMapping("/tasks/{id}") public Result<?> task(@PathVariable long id) { return Result.ok(progress.progress(id)); }
    @GetMapping(value="/tasks/{id}/events", produces="text/event-stream;charset=UTF-8")
    public SseEmitter events(@PathVariable long id, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache"); response.setHeader("X-Accel-Buffering", "no"); return progress.events(id);
    }
    @PostMapping("/tasks/{id}/rerun") public Result<?> rerun(@PathVariable long id, @RequestBody(required=false) PlagiarismRequests.Rerun request) { return Result.ok(tasks.rerun(id, request != null && Boolean.TRUE.equals(request.keepAudit()))); }
    @GetMapping("/pairs") public Result<?> pairs(@RequestParam long assignmentId, @RequestParam(required=false) Long problemId,
            @RequestParam(defaultValue="all") String filter, @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int pageSize,
            @RequestParam(defaultValue="overall") String sortBy, @RequestParam(defaultValue="desc") String sortOrder) {
        return Result.ok(queries.pairs(assignmentId, problemId, filter, page, pageSize, sortBy, sortOrder));
    }
    @GetMapping("/pairs/{id}") public Result<?> detail(@PathVariable long id, @RequestParam(required=false) Integer onlySeg) { return Result.ok(queries.detail(id, onlySeg)); }
    @GetMapping("/diff/{id}") public Result<?> diff(@PathVariable long id) { return Result.ok(queries.detail(id, null)); }
    @PutMapping("/audit") public Result<?> audit(@RequestBody PlagiarismRequests.Audit request) { return Result.ok(reviews.audit(request)); }
    @DeleteMapping("/audit/{id}") public Result<?> undo(@PathVariable long id) { return Result.ok(reviews.undo(id)); }
}
