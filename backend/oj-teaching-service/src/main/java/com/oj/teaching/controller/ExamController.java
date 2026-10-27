package com.oj.teaching.controller;

import com.oj.common.core.result.Result;
import com.oj.teaching.dto.ActivityRequests.*;
import com.oj.teaching.service.ExamService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.Map;

@RestController
@RequestMapping("/api/teaching/exam")
public class ExamController {
    private final ExamService service;
    public ExamController(ExamService service) { this.service=service; }
    @GetMapping("/{examId}/paper")
    public Result<Map<String,Object>> paper(@PathVariable Long examId) { return Result.ok(service.paper(examId)); }
    @PostMapping("/{examId}/answer")
    public Result<Map<String,Object>> answer(@PathVariable Long examId,@RequestBody Answer request) { return Result.ok(service.answer(examId,request)); }
    @PostMapping("/{examId}/hand-in")
    public Result<Map<String,Object>> handIn(@PathVariable Long examId,@RequestBody HandIn request) { return Result.ok(service.handIn(examId,request)); }
    @GetMapping("/{examId}/remaining")
    public Result<Map<String,Object>> remaining(@PathVariable Long examId) { return Result.ok(service.remaining(examId)); }
    @PostMapping("/tab-switch")
    public Result<Map<String,Object>> tabSwitch(@RequestBody TabSwitch request) { return Result.ok(service.tabSwitch(request)); }
    @GetMapping("/{examId}/monitor")
    public Result<Map<String,Object>> monitor(@PathVariable Long examId,@RequestParam(defaultValue="all") String filter,@RequestParam(required=false) String kw) { return Result.ok(service.monitor(examId,filter,kw)); }
    @PostMapping("/{examId}/extend")
    public Result<Map<String,Object>> extend(@PathVariable Long examId,@RequestBody Extend request) { return Result.ok(service.extend(examId,request)); }
    @PostMapping("/{examId}/finish")
    public Result<Map<String,Object>> finish(@PathVariable Long examId,@RequestBody Finish request) { return Result.ok(service.finish(examId,Boolean.TRUE.equals(request.autoSubmitUnsubmitted()))); }
    @GetMapping(value="/{examId}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long examId,@RequestHeader(value="Last-Event-ID",required=false) String lastEventId,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-cache"); response.setHeader("X-Accel-Buffering","no"); response.setCharacterEncoding("UTF-8");
        return service.events(examId,lastEventId);
    }
}
