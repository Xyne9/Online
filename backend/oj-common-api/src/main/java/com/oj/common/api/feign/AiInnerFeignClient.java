package com.oj.common.api.feign;

import com.oj.common.api.dto.ai.AiPlagiarismCheckDTO;
import com.oj.common.api.dto.ai.AiPlagiarismResultDTO;
import com.oj.common.api.dto.ai.ChannelHealthDTO;
import com.oj.common.api.dto.ai.GuardrailCheckDTO;
import com.oj.common.api.dto.ai.GuardrailVerdictDTO;
import com.oj.common.core.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * ⑥ AI 智能体服务内部接口（v1.0 1 个 + v2.0 新增 2 个 = 3 个方法）。
 *
 * <p>契约来源：设计文档 §6 第 6 条。</p>
 *
 * <p>调用方：教务服务（语义查重）、admin（通道健康）、各业务（护栏判定）。</p>
 *
 * @author oj-cloud
 */
@FeignClient(name = "oj-ai-agent-service", contextId = "aiInner", path = "/inner/ai")
public interface AiInnerFeignClient {

    /**
     * 代码语义相似度查重（异步查重任务的单次对比）。
     *
     * @param dto 查重请求（基准代码 + 候选代码列表）
     * @return 查重结果
     */
    @PostMapping("/plagiarism/semantic-check")
    Result<AiPlagiarismResultDTO> checkCodeSemanticSimilarity(@RequestBody AiPlagiarismCheckDTO dto);

    /**
     * 内容安全护栏三分类判定（pass / rewrite / block）。
     *
     * @param dto 护栏检查请求
     * @return 判定结果
     */
    @PostMapping("/guardrail/classify")
    Result<GuardrailVerdictDTO> classifyGuardrail(@RequestBody GuardrailCheckDTO dto);

    /**
     * 列出全部模型通道健康状态（admin 通道探活页）。
     *
     * @return 通道健康列表
     */
    @GetMapping("/channels/health")
    Result<List<ChannelHealthDTO>> listChannelHealth();
}
