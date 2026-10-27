package com.oj.teaching.controller;

import com.oj.common.core.result.Result;
import com.oj.teaching.dto.ActivityRequests.*;
import com.oj.teaching.service.AssignmentService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/teaching")
public class AssignmentController {
    private final AssignmentService service;
    public AssignmentController(AssignmentService service) { this.service=service; }
    @GetMapping("/assignments")
    public Result<Map<String,Object>> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,
            @RequestParam(required=false) Integer type,@RequestParam(required=false) Integer status,@RequestParam(required=false) String kw,
            @RequestParam(defaultValue="status") String sortBy,@RequestParam(defaultValue="desc") String sortOrder) {
        return Result.ok(service.list(page,pageSize,type,status,kw,sortBy,sortOrder));
    }
    @PostMapping("/assignments")
    public Result<Map<String,Object>> create(@RequestBody AssignmentWrite request) { return Result.ok(service.create(request)); }
    @GetMapping("/assignments/{id}")
    public Result<Map<String,Object>> detail(@PathVariable Long id) { return Result.ok(service.detail(id)); }
    @PutMapping("/assignments/{id}")
    public Result<Map<String,Object>> update(@PathVariable Long id,@RequestBody AssignmentWrite request) { return Result.ok(service.update(id,request)); }
    @PutMapping("/assignments/{id}/state")
    public Result<Map<String,Object>> state(@PathVariable Long id,@RequestBody StateChange request) { return Result.ok(service.state(id,request.action())); }
    @GetMapping("/assignments/{id}/problems")
    public Result<Map<String,Object>> problems(@PathVariable Long id,@RequestParam(defaultValue="false") boolean withMine) { return Result.ok(service.problems(id,withMine)); }
    @GetMapping("/assignments/{id}/summary")
    public Result<Map<String,Object>> summary(@PathVariable Long id) { return Result.ok(service.summary(id)); }
    @PostMapping("/assignments/{id}/submit")
    public Result<Map<String,Object>> submit(@PathVariable Long id,@RequestBody(required=false) Submit request) { return Result.ok(service.submit(id,request==null?null:request.remark())); }
    @DeleteMapping("/assignments/{id}/submit")
    public Result<Map<String,Object>> revoke(@PathVariable Long id) { return Result.ok(service.revoke(id)); }
    @GetMapping("/assignments/{id}/submissions")
    public Result<Map<String,Object>> submissions(@PathVariable Long id,@RequestParam(defaultValue="all") String stuFilter,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String kw) {
        return Result.ok(service.submissions(id,stuFilter,page,pageSize,kw));
    }
    @GetMapping("/classes")
    public Result<Map<String,Object>> classes(@RequestParam(defaultValue="false") boolean withStats) { return Result.ok(service.classes(withStats)); }
}
