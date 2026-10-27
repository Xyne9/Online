package com.oj.common.api.feign;

import com.oj.common.api.dto.problem.ProblemEditDTO;
import com.oj.common.api.dto.problem.TestCaseDTO;
import com.oj.common.core.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;

/**
 * ② 题库测试点扩展内部接口（v2.0 新增，3 个方法）。
 *
 * <p>契约来源：设计文档 §6 第 2 条。</p>
 *
 * <p>调用方：判题服务（拉测试点跑用例）、AI 服务（取题面 + AI 策略）、admin（物化刷新）。</p>
 *
 * <p><b>安全提醒：</b>本客户端的 {@code /test-cases} 返回含隐藏用例的 input/output，
 * 只能由服务间调用，绝不能暴露成对外 HTTP 端点。</p>
 *
 * @author oj-cloud
 */
@FeignClient(name = "oj-problem-service", contextId = "problemTestCase", path = "/inner/problem")
public interface ProblemTestCaseFeignClient {

    /**
     * 取某题全部测试点（含 kind/score/isPublic）。
     *
     * @param problemId 题目 ID
     * @return 测试点列表
     */
    @GetMapping("/test-cases/{problemId}")
    Result<List<TestCaseDTO>> listByProblem(@PathVariable("problemId") Long problemId);

    /**
     * 取题目编辑态视图（AI 服务取题面 + AI 策略）。
     *
     * @param problemId 题目 ID
     * @return 编辑态视图
     */
    @GetMapping("/edit/{problemId}")
    Result<ProblemEditDTO> getForEdit(@PathVariable("problemId") Long problemId);

    /**
     * 刷新题目的物化视图（测试点变更后由题库服务重建判题缓存/用例目录）。
     *
     * @param problemId 题目 ID
     * @return 无数据体的成功响应
     */
    @PostMapping("/materialize/{problemId}")
    Result<Void> refreshMaterialized(@PathVariable("problemId") Long problemId);
}
