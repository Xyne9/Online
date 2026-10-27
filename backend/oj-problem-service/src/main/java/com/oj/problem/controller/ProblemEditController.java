package com.oj.problem.controller;

import com.oj.common.core.result.Result;
import com.oj.problem.dto.*;
import com.oj.problem.entity.ProbKnowledgePoint;
import com.oj.problem.service.ProblemEditService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 契约 #8~#18。权限、状态与版本校验全部由服务层执行。 */
@RestController
@RequestMapping("/api/problem")
public class ProblemEditController {
    private final ProblemEditService service;

    public ProblemEditController(ProblemEditService service) { this.service = service; }

    @GetMapping("/questions/{id}/edit")
    public Result<Map<String, Object>> edit(@PathVariable("id") Long id,
            @RequestParam(value = "version", required = false) Integer version) {
        return Result.ok(service.edit(id, version));
    }

    @PostMapping("/questions")
    public Result<Map<String, Object>> create(@RequestBody ProblemWriteRequest request) {
        return Result.ok(service.create(request));
    }

    @PutMapping("/questions/{id}")
    public Result<Map<String, Object>> update(@PathVariable("id") Long id, @RequestBody ProblemWriteRequest request) {
        return Result.ok(service.update(id, request));
    }

    @DeleteMapping("/questions/{id}")
    public Result<Map<String, Object>> delete(@PathVariable("id") Long id) { return Result.ok(service.delete(id)); }

    @PutMapping("/questions/{id}/state")
    public Result<Map<String, Object>> state(@PathVariable("id") Long id, @RequestBody ProblemStateRequest request) {
        return Result.ok(service.state(id, request));
    }

    @GetMapping("/questions/{id}/test-cases")
    public Result<Map<String, Object>> testCases(@PathVariable("id") Long id,
            @RequestParam(value = "withContent", defaultValue = "true") boolean withContent) {
        return Result.ok(service.listCases(id, withContent));
    }

    @PostMapping("/questions/{id}/test-cases")
    public Result<Map<String, Object>> addCase(@PathVariable("id") Long id, @RequestBody TestCaseWriteRequest request) {
        return Result.ok(service.addCase(id, request));
    }

    @PutMapping("/test-cases/{caseId}")
    public Result<Map<String, Object>> updateCase(@PathVariable("caseId") Long id, @RequestBody TestCaseWriteRequest request) {
        return Result.ok(service.updateCase(id, request));
    }

    @DeleteMapping("/test-cases/{caseId}")
    public Result<Map<String, Object>> deleteCase(@PathVariable("caseId") Long id) { return Result.ok(service.deleteCase(id)); }

    @PutMapping("/questions/{id}/test-cases/batch")
    public Result<Map<String, Object>> batch(@PathVariable("id") Long id, @RequestBody TestCaseBatchRequest request) {
        return Result.ok(service.batch(id, request));
    }

    @PostMapping("/questions/import")
    public Result<Map<String, Object>> importProblems(@RequestBody ProblemImportRequest request) {
        return Result.ok(service.importProblems(request));
    }

    @PostMapping("/questions/{id}/copy")
    public Result<Map<String, Object>> copy(@PathVariable("id") Long id) { return Result.ok(service.copy(id)); }

    @GetMapping("/knowledge-points")
    public Result<List<ProbKnowledgePoint>> knowledgePoints() { return Result.ok(service.knowledgePoints()); }
}
