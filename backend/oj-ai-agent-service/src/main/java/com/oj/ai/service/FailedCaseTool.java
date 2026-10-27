package com.oj.ai.service;

import com.oj.common.api.dto.judge.FailedCaseDiagnosisDTO;
import com.oj.common.api.feign.JudgeInnerFeignClient;
import dev.langchain4j.agent.tool.Tool;

/** 身份绑定在请求级工具实例上，模型无法自选其他用户或作业。 */
public final class FailedCaseTool {
    private final JudgeInnerFeignClient judge;
    private final AiAccessService.Context context;
    public FailedCaseTool(JudgeInnerFeignClient judge, AiAccessService.Context context) { this.judge = judge; this.context = context; }

    @Tool("读取当前学生在当前教学活动、本题的首个失败测试点脱敏诊断，不返回隐藏用例正文")
    public FailedCaseDiagnosisDTO fetchFailedTestCaseTool() {
        return AiAccessService.call(() -> judge.getLatestFailedDiagnosis(context.userId(), context.problemId(), context.assignmentId()));
    }
}
