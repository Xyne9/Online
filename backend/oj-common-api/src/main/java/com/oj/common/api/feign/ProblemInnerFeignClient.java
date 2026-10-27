package com.oj.common.api.feign;

import com.oj.common.api.dto.problem.ProblemAiRagDTO;
import com.oj.common.api.dto.problem.ProblemJudgeConfigDTO;
import com.oj.common.api.dto.problem.ProblemSimpleDTO;
import com.oj.common.core.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * ① 题库服务核心内部接口（v1.0 保留，3 个方法）。
 *
 * <p>契约来源：设计文档 §6「微服务内部通信契约」第 1 条，<b>方法签名与路径逐字对齐</b>。</p>
 *
 * <p>调用方：判题服务（取判题配置）、AI 服务（取 RAG 上下文 / 推荐下一题）。</p>
 *
 * @author oj-cloud
 */
@FeignClient(name = "oj-problem-service", contextId = "problemInner", path = "/inner/problem")
public interface ProblemInnerFeignClient {

    /**
     * 取判题所需的最小题目配置（时间/内存限制、测试点摘要、判题模式）。
     *
     * @param problemId 题目 ID
     * @return 判题配置
     */
    @GetMapping("/judge-config/{problemId}")
    Result<ProblemJudgeConfigDTO> getJudgeConfig(@PathVariable("problemId") Long problemId);

    /**
     * 取 AI 所需题干与 AI 策略（含公开样例，<b>不含</b>隐藏用例）。
     *
     * @param problemId 题目 ID
     * @return RAG 上下文
     */
    @GetMapping("/ai-rag/{problemId}")
    Result<ProblemAiRagDTO> getProblemRagForAi(@PathVariable("problemId") Long problemId);

    /**
     * 按弱项标签推荐下一题。
     *
     * @param userId           用户 ID
     * @param currentProblemId 当前题目 ID（用于排除已做/当前题）
     * @param tag              弱项标签
     * @return 推荐题目轻量视图
     */
    @GetMapping("/recommend-next")
    Result<ProblemSimpleDTO> recommendProblemByWeakTag(@RequestParam("userId") Long userId,
                                                       @RequestParam("currentProblemId") Long currentProblemId,
                                                       @RequestParam("tag") String tag);
}
