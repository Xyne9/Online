package com.oj.problem.controller;

import com.oj.common.api.dto.problem.ProblemEditDTO;
import com.oj.common.api.dto.problem.ProblemJudgeConfigDTO;
import com.oj.common.api.dto.problem.TestCaseDTO;
import com.oj.common.core.result.Result;
import com.oj.problem.service.ProblemQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 题库服务间内部接口（契约 02 §10.2 第 1/2/3 条）。
 *
 * <p><b>调用方与用途：</b></p>
 * <ul>
 *   <li>{@code GET /judge-config/{problemId}} → {@code oj-judge-service}
 *       取时限/内存/测试点摘要（<b>只有 seqNo/kind/score</b>，无正文）；</li>
 *   <li>{@code GET /edit/{problemId}} → {@code oj-ai-agent-service}
 *       取题面 + AI 策略（含官方题解，AI 侧作参考答案注入）；</li>
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
 * <p><b>关于 {@code X-Inner-Token}：</b>契约 §10 约定内部端点校验该头。本期服务间
 * 调用尚未接入网关的统一内部令牌（各 Track 并行开发，令牌密钥还没定），
 * 所以这里不做强制校验，仅靠「路径不经网关暴露」这一层边界；
 * 待令牌方案定稿后在此加一个 {@code OncePerRequestFilter} 即可，端点签名不变。</p>
 *
 * @author oj-cloud
 */
@RestController
@RequestMapping("/inner/problem")
public class ProblemInnerController {

    private final ProblemQueryService problemQueryService;

    public ProblemInnerController(ProblemQueryService problemQueryService) {
        this.problemQueryService = problemQueryService;
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

    /**
     * 编辑态视图：题面 + AI 策略 + 官方题解 + 全部测试点正文。
     *
     * <p>供 AI 服务取「题面 + AI 策略」；教师编辑页回显也用它。</p>
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
