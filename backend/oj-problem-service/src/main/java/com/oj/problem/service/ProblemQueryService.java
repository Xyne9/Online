package com.oj.problem.service;

import com.oj.common.api.dto.problem.ProblemEditDTO;
import com.oj.common.api.dto.problem.ProblemAiRagDTO;
import com.oj.common.api.dto.problem.ProblemJudgeConfigDTO;
import com.oj.common.api.dto.problem.ProblemSimpleDTO;
import com.oj.common.api.dto.problem.TestCaseDTO;
import com.oj.problem.dto.ProblemQuery;
import com.oj.problem.vo.ProblemDetailVO;
import com.oj.problem.vo.ProblemListVO;

import java.util.List;

/**
 * 题库只读查询服务（契约 02 §3.2 / §3.3 + §10.2 的三个内部接口）。
 *
 * <p>本期只做「读」，写链路（建题/改题/测试点增删/状态流转）在后续任务里补齐。
 * 读链路的权限口径全部收敛在实现类里，Controller 只做参数接收与包装，
 * 这样「学生强制 state=1」这类规则不可能被某个新加的 Controller 漏掉。</p>
 *
 * @author oj-cloud
 */
public interface ProblemQueryService {

    /**
     * 题库列表（契约 #6 / 02 §3.2）。
     *
     * @param query 原始查询参数（未校验）
     * @return 分页列表 + 四格统计
     */
    ProblemListVO listQuestions(ProblemQuery query);

    /**
     * 题目详情·作答视角（契约 #7 / 02 §3.3）。
     *
     * @param id 题目 ID
     * @return 详情（<b>不含</b> officialSolution 与隐藏用例正文）
     */
    ProblemDetailVO getQuestionDetail(Long id);

    /**
     * 判题配置（{@code GET /inner/problem/judge-config/{problemId}}）。
     *
     * <p>判题服务靠它拿时限/内存/测试点摘要（只有 seqNo/kind/score，不含正文）。</p>
     *
     * @param problemId 题目 ID
     * @return 判题配置
     */
    ProblemJudgeConfigDTO getJudgeConfig(Long problemId);

    /** AI 专用 RAG：官方题解可作保密上下文，只允许公开样例进入 DTO。 */
    ProblemAiRagDTO getProblemRagForAi(Long problemId);

    /** 按弱项标签推荐未 AC 的下一题；没有候选时返回 null。 */
    ProblemSimpleDTO recommendProblemByWeakTag(Long userId, Long currentProblemId, String tag);

    /**
     * 编辑态视图（{@code GET /inner/problem/edit/{problemId}}）。
     *
     * <p>含官方题解与全部测试点正文，<b>只能</b>给 AI 服务与教师编辑页用。</p>
     *
     * @param problemId 题目 ID
     * @return 编辑态视图
     */
    ProblemEditDTO getForEdit(Long problemId);

    /**
     * 全部测试点（{@code GET /inner/problem/test-cases/{problemId}}）。
     *
     * <p><b>含隐藏用例的输入输出</b>，仅服务间可用。</p>
     *
     * @param problemId 题目 ID
     * @return 测试点列表
     */
    List<TestCaseDTO> listTestCases(Long problemId);
}
