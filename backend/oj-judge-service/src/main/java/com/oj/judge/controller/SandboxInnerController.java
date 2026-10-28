package com.oj.judge.controller;

import com.oj.common.api.constant.InnerConstants;
import com.oj.common.api.dto.admin.SandboxKillDTO;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.result.Result;
import com.oj.judge.service.support.SandboxLifecycle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Resource cancellation is owned by the judge node that owns the live HTTP exchange. */
@RestController
@RequestMapping("/inner/judge/sandbox/instances")
public class SandboxInnerController {
    private final SandboxLifecycle sandboxes;
    private final String token;

    public SandboxInnerController(SandboxLifecycle sandboxes, @Value("${oj.inner-token:}") String token) {
        this.sandboxes = sandboxes;
        this.token = token;
    }

    @PostMapping("/{sandboxInstanceId}/kill")
    public Result<SandboxKillDTO> kill(@PathVariable String sandboxInstanceId,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = InnerConstants.INNER_TOKEN_HEADER, required = false) String provided) {
        if (token == null || token.isBlank() || !token.equals(provided)) {
            throw BusinessException.forbidden("内部调用令牌无效或未配置");
        }
        return Result.ok(sandboxes.kill(sandboxInstanceId, body == null ? null : body.get("reason")));
    }
}
