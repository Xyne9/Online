package com.oj.common.api.feign;

import com.oj.common.api.dto.teaching.AiPermissionVerifyDTO;
import com.oj.common.api.dto.teaching.ScoreSyncRequestDTO;
import com.oj.common.core.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * ④ 教务服务核心内部接口（v1.0 保留，2 个方法）。
 *
 * <p>契约来源：设计文档 §6 第 4 条。</p>
 *
 * <p>调用方：判题服务（回写分数）、AI 服务（校验 AI 调用权限）。</p>
 *
 * @author oj-cloud
 */
@FeignClient(name = "oj-teaching-service", contextId = "teachingInner", path = "/inner/teaching")
public interface TeachingInnerFeignClient {
    @GetMapping("/submission/verify")
    Result<Void> verifyJudgeSubmission(@RequestParam("userId") Long userId,
                                      @RequestParam("assignmentId") Long assignmentId,
                                      @RequestParam("problemId") Long problemId);


    /**
     * 判题结果回写作业分数（幂等，以 submissionId 为幂等键）。
     *
     * @param dto 分数同步请求
     * @return 无数据体的成功响应
     */
    @PostMapping("/score/sync")
    Result<Void> syncSubmissionScore(@RequestBody ScoreSyncRequestDTO dto);

    /**
     * 校验 AI 调用权限（作业时间窗 / 考试模式 / 配额 / 阶段）。
     *
     * @param userId       用户 ID
     * @param assignmentId 作业 ID
     * @param problemId    题目 ID
     * @param targetStage  目标阶段（1/2/3）
     * @return 权限判定结果
     */
    @GetMapping("/ai-permission/verify")
    Result<AiPermissionVerifyDTO> verifyAiPermission(@RequestParam("userId") Long userId,
                                                     @RequestParam("assignmentId") Long assignmentId,
                                                     @RequestParam("problemId") Long problemId,
                                                     @RequestParam("targetStage") Integer targetStage);
}
