package com.oj.common.api.feign;

import com.oj.common.api.dto.judge.FailedCaseDiagnosisDTO;
import com.oj.common.api.dto.judge.SubmissionCodeDTO;
import com.oj.common.core.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * ③ 判题服务内部接口（v1.0 保留，2 个方法）。
 *
 * <p>契约来源：设计文档 §6 第 3 条。</p>
 *
 * <p>调用方：AI 服务（取失败诊断做启发式提示）、教务服务（查重时批量取最佳代码）。</p>
 *
 * @author oj-cloud
 */
@FeignClient(name = "oj-judge-service", contextId = "judgeInner", path = "/inner/judge")
public interface JudgeInnerFeignClient {
    /** 由判题节点终止真实执行，再返回实例终态；管理服务不直接改运行状态。 */
    @PostMapping("/sandbox/instances/{sandboxInstanceId}/kill")
    Result<com.oj.common.api.dto.admin.SandboxKillDTO> killSandbox(
            @PathVariable("sandboxInstanceId") String sandboxInstanceId,
            @RequestBody java.util.Map<String, String> request);

    @PostMapping("/exam-submit")
    Result<java.util.Map<String, Object>> submitExamCode(
            @RequestBody com.oj.common.api.dto.judge.ExamJudgeRequestDTO request);

    @GetMapping("/ai-state")
    Result<com.oj.common.api.dto.judge.AiSubmissionStateDTO> getAiSubmissionState(
            @RequestParam("userId") Long userId, @RequestParam("problemId") Long problemId,
            @RequestParam("assignmentId") Long assignmentId);

    /**
     * 取某用户在某题（某作业）下最近一次失败用例诊断。
     *
     * @param userId       用户 ID
     * @param problemId    题目 ID
     * @param assignmentId 作业 ID（自由练习时传 0 或 null）
     * @return 失败诊断
     */
    @GetMapping("/failed-diagnosis")
    Result<FailedCaseDiagnosisDTO> getLatestFailedDiagnosis(@RequestParam("userId") Long userId,
                                                            @RequestParam("problemId") Long problemId,
                                                            @RequestParam("assignmentId") Long assignmentId);

    /**
     * 批量取最佳提交的源代码（查重任务用）。
     *
     * @param bestSubmissionIds 提交 ID 列表
     * @return 代码列表
     */
    @PostMapping("/batch-best-codes")
    Result<List<SubmissionCodeDTO>> getBatchBestCodes(@RequestBody List<Long> bestSubmissionIds);
}
