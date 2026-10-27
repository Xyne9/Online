package com.oj.problem.controller;

import com.oj.common.api.dto.problem.ProblemEditDTO;
import com.oj.common.api.constant.InnerConstants;
import com.oj.common.api.dto.problem.ProblemAiRagDTO;
import com.oj.common.api.dto.problem.ProblemJudgeConfigDTO;
import com.oj.common.api.dto.problem.ProblemSimpleDTO;
import com.oj.common.api.dto.problem.TestCaseDTO;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.result.Result;
import com.oj.problem.service.ProblemQueryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 题库服务间内部接口（契约 02 §10.2 第 1/2/3 条）。
 *
 * <p><b>调用方与用途：</b></p>
 * <ul>
 *   <li>{@code GET /judge-config/{problemId}} → {@code oj-judge-service}
 *       取时限/内存/测试点摘要（<b>只有 seqNo/kind/score</b>，无正文）；</li>
 *   <li>{@code GET /ai-rag/{problemId}} → {@code oj-ai-agent-service}
 *       取题面、AI 策略、保密官方题解与公开样例；</li>
 *   <li>{@code GET /edit/{problemId}} → 编辑回显，含全部测试点正文，不作 AI 上下文；</li>
 *   <li>{@code GET /test-cases/{problemId}} → {@code oj-judge-service}
 *       拉全部测试点（<b>含隐藏用例输入输出</b>）。</li>
 * </ul>
 *
 * <p><b>安全边界：</b>本 Controller 的路径前缀是 {@code /inner/problem}，
 * 不在网关的 6 条路由前缀（{@code /api/**}）之内，外部无法经网关触达；
 * 只能由服务间 Feign 直连 {@code lb://oj-problem-service} 访问。
 * 另外 {@code ProblemTestCaseFeignClient} 的类注释也明确写了
 * 「{@code /test-cases} 返回含隐藏用例正文，绝不能暴露成对外 HTTP 端点」。</p>
 *
 * <p><b>关于 {@code X-Inner-Token}：</b>AI RAG 与下一题推荐接口在配置 {@code oj.inner-token}
 * 或 {@code OJ_INNER_TOKEN} 后校验该头；空配置开发放行。既有判题内部接口保留原有行为。</p>
 *
 * @author oj-cloud
 */
@RestController
@RequestMapping("/inner/problem")
public class ProblemInnerController {

    private final ProblemQueryService problemQueryService;
    private final String innerToken;

    public ProblemInnerController(ProblemQueryService problemQueryService,
            @Value("${oj.inner-token:${OJ_INNER_TOKEN:}}") String innerToken) {
        this.problemQueryService = problemQueryService;
        this.innerToken = innerToken;
    }

    /**
     * 判题配置：时限 / 内存 / 语言 / 判题模式 / 测试点摘要。
     *
     * @param problemId 题目 ID
     * @return 判题配置（{@code published=false} 表示该题当前不可受理判题）
     */
    @GetMapping("/judge-config/{problemId}")
    public Result<ProblemJudgeConfigDTO> getJudgeConfig(@PathVariable("problemId") Long problemId) {
        return Result.ok(problemQueryService.getJudgeConfig(problemId));
    }

    /** RAG 专用视图，包含保密题解和公开样例，不读取隐藏测试点正文。 */
    @GetMapping("/ai-rag/{problemId}")
    public Result<ProblemAiRagDTO> getProblemRagForAi(@PathVariable("problemId") Long problemId,
            @RequestHeader(value = InnerConstants.INNER_TOKEN_HEADER, required = false) String token) {
        verifyInnerToken(token);
        return Result.ok(problemQueryService.getProblemRagForAi(problemId));
    }

    /** 阶段④推荐工具：候选缺失是正常结果，返回 data:null。 */
    @GetMapping("/recommend-next")
    public Result<ProblemSimpleDTO> recommendProblemByWeakTag(
            @RequestParam("userId") Long userId,
            @RequestParam("currentProblemId") Long currentProblemId,
            @RequestParam("tag") String tag,
            @RequestHeader(value = InnerConstants.INNER_TOKEN_HEADER, required = false) String token) {
        verifyInnerToken(token);
        return Result.ok(problemQueryService.recommendProblemByWeakTag(userId, currentProblemId, tag));
    }

    private void verifyInnerToken(String token) {
        if (innerToken != null && !innerToken.isBlank() && !innerToken.equals(token)) {
            throw BusinessException.forbidden("内部调用令牌无效");
        }
    }

    /**
     * 编辑态视图：题面 + AI 策略 + 官方题解 + 全部测试点正文。
     *
     * <p>供教师编辑页回显。AI 服务使用专用 {@code /ai-rag}，避免读取隐藏测试点。</p>
     *
     * @param problemId 题目 ID
     * @return 编辑态视图
     */
    @GetMapping("/edit/{problemId}")
    public Result<ProblemEditDTO> getForEdit(@PathVariable("problemId") Long problemId) {
        return Result.ok(problemQueryService.getForEdit(problemId));
    }

    /**
     * 全部测试点（含隐藏用例输入输出）。
     *
     * <p><b>⚠️ 返回体含题解级敏感数据</b>，只能服务间调用。</p>
     *
     * @param problemId 题目 ID
     * @return 测试点列表（按 seqNo 升序）
     */
    @GetMapping("/test-cases/{problemId}")
    public Result<List<TestCaseDTO>> listTestCases(@PathVariable("problemId") Long problemId) {
        return Result.ok(problemQueryService.listTestCases(problemId));
    }
}
