package com.oj.teaching.controller;

import com.oj.common.api.constant.InnerConstants;
import com.oj.common.api.dto.teaching.*;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.result.Result;
import com.oj.teaching.mapper.ActivityMapper;
import com.oj.teaching.service.ExamService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/inner/teaching")
public class TeachingInnerController {
    private final ExamService service;
    private final ActivityMapper mapper;
    private final String innerToken;
    public TeachingInnerController(ExamService service,ActivityMapper mapper,@Value("${oj.inner-token:${OJ_INNER_TOKEN:}}") String innerToken) {
        this.service=service; this.mapper=mapper; this.innerToken=innerToken;
    }
    @GetMapping("/assignment/{assignmentId}")
    public Result<AssignmentBriefDTO> assignment(@PathVariable Long assignmentId,@RequestHeader(value=InnerConstants.INNER_TOKEN_HEADER,required=false) String token) {
        authorize(token); return Result.ok(service.assignmentBrief(assignmentId));
    }
    @GetMapping("/exam/{examId}/runtime")
    public Result<ExamRuntimeDTO> runtime(@PathVariable Long examId,@RequestHeader(value=InnerConstants.INNER_TOKEN_HEADER,required=false) String token) {
        authorize(token); return Result.ok(service.runtime(examId));
    }
    @GetMapping("/submission/verify")
    public Result<Void> verify(@RequestParam Long userId,@RequestParam Long assignmentId,@RequestParam Long problemId,
            @RequestHeader(value=InnerConstants.INNER_TOKEN_HEADER,required=false) String token) {
        authorize(token); service.verifyJudgeSubmission(userId,assignmentId,problemId); return Result.ok();
    }
    @PostMapping("/score/sync")
    public Result<Void> sync(@RequestBody ScoreSyncRequestDTO request,@RequestHeader(value=InnerConstants.INNER_TOKEN_HEADER,required=false) String token) {
        authorize(token); service.syncScore(request); return Result.ok();
    }
    @GetMapping("/class/{classId}/students/ids")
    public Result<List<Long>> students(@PathVariable Long classId,@RequestHeader(value=InnerConstants.INNER_TOKEN_HEADER,required=false) String token) {
        authorize(token); return Result.ok(mapper.students(classId).stream().map(com.oj.teaching.entity.SysUser::getId).toList());
    }
    private void authorize(String token) {
        if(innerToken!=null && !innerToken.isBlank() && !innerToken.equals(token)) throw BusinessException.forbidden("内部调用令牌无效");
    }
}
