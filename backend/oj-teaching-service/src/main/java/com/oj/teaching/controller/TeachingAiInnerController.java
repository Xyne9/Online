package com.oj.teaching.controller;

import com.oj.common.api.constant.InnerConstants;
import com.oj.common.api.dto.teaching.AiPermissionVerifyDTO;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.result.Result;
import com.oj.teaching.service.AiPermissionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 仅供 AI 服务调用的权限接口；对外请求仍统一经过网关。 */
@RestController
@RequestMapping("/inner/teaching")
public class TeachingAiInnerController {
    private final AiPermissionService permissionService;
    private final String innerToken;

    public TeachingAiInnerController(AiPermissionService permissionService,
            @Value("${oj.inner-token:${OJ_INNER_TOKEN:}}") String innerToken) {
        this.permissionService = permissionService;
        this.innerToken = innerToken;
    }

    @GetMapping("/ai-permission/verify")
    public Result<AiPermissionVerifyDTO> verify(
            @RequestParam("userId") Long userId,
            @RequestParam(value = "assignmentId", defaultValue = "0") Long assignmentId,
            @RequestParam("problemId") Long problemId,
            @RequestParam("targetStage") Integer targetStage,
            @RequestHeader(value = InnerConstants.INNER_TOKEN_HEADER, required = false) String token) {
        if (innerToken != null && !innerToken.isBlank() && !innerToken.equals(token)) {
            throw BusinessException.forbidden("内部调用令牌无效");
        }
        return Result.ok(permissionService.verify(userId, assignmentId, problemId, targetStage));
    }
}
