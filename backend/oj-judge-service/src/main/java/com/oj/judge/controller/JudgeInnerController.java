package com.oj.judge.controller;

import com.oj.common.api.constant.InnerConstants;
import com.oj.common.api.dto.judge.FailedCaseDiagnosisDTO;
import com.oj.common.api.dto.judge.SubmissionCodeDTO;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.result.Result;
import com.oj.judge.service.JudgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 判题内部接口（{@code /inner/judge/**}，契约 §6 第 3 条）。
 *
 * <p><b>网关不路由 {@code /inner/**}</b>（{@code oj-gateway} 只配了 6 个 {@code /api/*} 前缀），
 * 因此这些端点只能在服务所在内网被访问。除此之外还要求
 * {@code X-Inner-Token} 头（{@link InnerConstants#INNER_TOKEN_HEADER}），
 * 这样即使有人绕过网关直连 8083，也调不到内部接口。</p>
 *
 * <h2>两个端点</h2>
 * <ul>
 *   <li>{@code GET  /inner/judge/failed-diagnosis} —— AI 服务取「上次挂在哪」，
 *       用于阶段③错因诊断。只下发<b>第一个</b>失败测试点，且隐藏用例的输出一律置空（防泄题）。</li>
 *   <li>{@code POST /inner/judge/batch-best-codes} —— 教务服务查重时批量取最佳代码，
 *       避免 50 人 × 5 题放大成 250 次 RPC。</li>
 * </ul>
 *
 * @author oj-cloud
 */
@RestController
@RequestMapping("/inner/judge")
public class JudgeInnerController {

    private static final Logger log = LoggerFactory.getLogger(JudgeInnerController.class);

    private final JudgeService judgeService;
    private final com.oj.judge.mapper.JudgeSubmissionMapper submissions;

    /**
     * 内部调用令牌。
     *
     * <p>为空时<b>不校验</b>（本地开发态）。生产必须通过
     * {@code OJ_INNER_TOKEN} 环境变量注入，否则内网直连即可调用内部接口。</p>
     */
    private final String innerToken;

    public JudgeInnerController(JudgeService judgeService,
                                com.oj.judge.mapper.JudgeSubmissionMapper submissions,
                                @Value("${oj.inner-token:}") String innerToken) {
        this.judgeService = judgeService;
        this.submissions = submissions;
        this.innerToken = innerToken;
    }

    @PostMapping("/exam-submit")
    public Result<java.util.Map<String, Object>> submitExam(
            @RequestBody com.oj.common.api.dto.judge.ExamJudgeRequestDTO request,
            @RequestHeader(value = InnerConstants.INNER_TOKEN_HEADER, required = false) String token) {
        verifyInnerToken(token);
        return Result.ok(judgeService.submitExamCode(request));
    }

    @GetMapping("/ai-state")
    public Result<com.oj.common.api.dto.judge.AiSubmissionStateDTO> aiState(
            @RequestParam Long userId, @RequestParam Long problemId,
            @RequestParam Long assignmentId,
            @RequestHeader(value = InnerConstants.INNER_TOKEN_HEADER, required = false) String token) {
        verifyInnerToken(token);
        if (userId <= 0 || problemId <= 0 || assignmentId < 0) {
            throw BusinessException.badRequest("用户、题目或教学活动参数无效");
        }
        var base = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.oj.judge.entity.JudgeSubmission>()
                .eq(com.oj.judge.entity.JudgeSubmission::getUserId, userId)
                .eq(com.oj.judge.entity.JudgeSubmission::getProblemId, problemId)
                .eq(com.oj.judge.entity.JudgeSubmission::getAssignmentId, assignmentId);
        boolean failed = submissions.selectCount(base.clone().between(com.oj.judge.entity.JudgeSubmission::getStatus, 4, 9)) > 0;
        boolean ac = submissions.selectCount(base.clone().eq(com.oj.judge.entity.JudgeSubmission::getStatus, 3)
                .eq(com.oj.judge.entity.JudgeSubmission::getOiScore, 100)) > 0;
        return Result.ok(new com.oj.common.api.dto.judge.AiSubmissionStateDTO(failed, ac));
    }

    /**
     * 取某用户在某题（某作业）下最近一次失败用例诊断。
     *
     * <p><b>无失败记录时返回 {@code data: null} 而不是 404</b>：
     * AI 服务每次对话都会问一次「上次挂哪了」，没挂过是常态而非错误，
     * 让它处理 404 会让正常路径充满异常分支。</p>
     *
     * @param userId       用户 ID
     * @param problemId    题目 ID
     * @param assignmentId 作业 ID（自由练习传 0）
     * @param token        内部令牌
     * @return 失败诊断；无记录时 data 为 null
     */
    @GetMapping("/failed-diagnosis")
    public Result<FailedCaseDiagnosisDTO> failedDiagnosis(
            @RequestParam("userId") Long userId,
            @RequestParam("problemId") Long problemId,
            @RequestParam(value = "assignmentId", required = false) Long assignmentId,
            @RequestHeader(value = InnerConstants.INNER_TOKEN_HEADER, required = false) String token) {
        verifyInnerToken(token);
        FailedCaseDiagnosisDTO diagnosis = judgeService.failedDiagnosis(userId, problemId, assignmentId);
        if (diagnosis == null) {
            log.debug("无失败记录：userId={} problemId={} assignmentId={}", userId, problemId, assignmentId);
        }
        return Result.ok(diagnosis);
    }

    /**
     * 批量取最佳提交的源代码（查重任务用）。
     *
     * @param submissionIds 提交 ID 列表（契约：直接收 {@code List<Long>}）
     * @param token         内部令牌
     * @return 代码列表（顺序不保证与入参一致，调用方按 submissionId 索引）
     */
    @PostMapping("/batch-best-codes")
    public Result<List<SubmissionCodeDTO>> batchBestCodes(
            @RequestBody(required = false) List<Long> submissionIds,
            @RequestHeader(value = InnerConstants.INNER_TOKEN_HEADER, required = false) String token) {
        verifyInnerToken(token);
        if (submissionIds == null || submissionIds.isEmpty()) {
            return Result.ok(new ArrayList<>());
        }
        return Result.ok(judgeService.batchBestCodes(submissionIds));
    }

    /**
     * 校验内部调用令牌。
     *
     * <p>配置为空 = 开发态放行（打 WARN 便于发现「生产忘了配」）。</p>
     *
     * @param token 请求头里的令牌
     * @throws BusinessException 403
     */
    private void verifyInnerToken(String token) {
        if (innerToken == null || innerToken.isBlank()) {
            return;
        }
        if (token == null || !innerToken.equals(token)) {
            throw BusinessException.forbidden("内部调用令牌无效");
        }
    }
}
