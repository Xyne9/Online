package com.oj.ai.controller;

import com.oj.ai.service.PlagiarismSemanticService;
import com.oj.common.api.constant.InnerConstants;
import com.oj.common.api.dto.ai.AiPlagiarismCheckDTO;
import com.oj.common.api.dto.ai.AiPlagiarismResultDTO;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.result.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/inner/ai/plagiarism")
public class PlagiarismInnerController {
    private final PlagiarismSemanticService service;
    private final String innerToken;
    public PlagiarismInnerController(PlagiarismSemanticService service, @Value("${oj.inner-token:${OJ_INNER_TOKEN:}}") String innerToken) {
        this.service = service; this.innerToken = innerToken;
    }
    @PostMapping("/semantic-check")
    public Result<AiPlagiarismResultDTO> check(@RequestBody AiPlagiarismCheckDTO request,
            @RequestHeader(value = InnerConstants.INNER_TOKEN_HEADER, required = false) String token) {
        if (innerToken != null && !innerToken.isBlank() && !innerToken.equals(token)) throw BusinessException.forbidden("内部调用令牌无效");
        return Result.ok(service.check(request));
    }
}
