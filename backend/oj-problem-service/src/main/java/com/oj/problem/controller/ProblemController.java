package com.oj.problem.controller;

import com.oj.common.core.result.Result;
import com.oj.problem.dto.ProblemQuery;
import com.oj.problem.service.ProblemQueryService;
import com.oj.problem.vo.ProblemDetailVO;
import com.oj.problem.vo.ProblemListVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 题库对外接口（契约 02 §3.2 / §3.3，编号 #6 / #7）。
 *
 * <p><b>Controller 只做三件事</b>：接收参数 → 调 Service → 包 {@link Result}。
 * 权限口径、参数白名单、分页水位修正全在 Service 里，避免「新加一个 Controller
 * 就漏掉学生强制 state=1」这类只在越权时才暴露的缺陷。</p>
 *
 * <p><b>为什么用 {@code @RequestParam} 而不是 {@code @ModelAttribute} 绑 DTO：</b>
 * 契约里这些参数全是可选的，缺省值有明确语义（page=1/pageSize=20/sortBy=id/sortOrder=desc），
 * 显式列出参数名能让 {@code curl} 与接口文档逐字对上；同时 DTO 里的字段仍需可空，
 * 便于 Service 区分「没传」与「传了空串」。</p>
 *
 * <p><b>路径前缀</b>：网关不做 StripPrefix（agent.md §4.4），所以这里必须写全
 * {@code /api/problem}，与网关路由 {@code Path=/api/problem/**} 对齐。</p>
 *
 * @author oj-cloud
 */
@RestController
@RequestMapping("/api/problem")
public class ProblemController {

    private final ProblemQueryService problemQueryService;

    public ProblemController(ProblemQueryService problemQueryService) {
        this.problemQueryService = problemQueryService;
    }

    /**
     * 题库列表（#6）。
     *
     * <p>学生视角强制 {@code state=1}，教师/管理员可见全部三态。
     * {@code stats} 内嵌在 {@code data} 里，不单开端点。</p>
     *
     * @param page      页码，默认 1
     * @param pageSize  每页条数，默认 20，上限 100
     * @param status    我的状态筛选：all（默认）/ todo / done / fail
     * @param state     题目状态 0/1/2；仅教师与管理员生效
     * @param lvl       难度多选，逗号分隔如 "1,2,3"
     * @param kw        标题/标签模糊词，≤50 字符
     * @param tag       单标签精确匹配
     * @param sortBy    排序字段：id/lvl/rate/score/updatedAt，默认 id
     * @param sortOrder 排序方向：asc/desc，默认 desc
     * @return 分页列表 + 四格统计
     */
    @GetMapping("/questions")
    public Result<ProblemListVO> listQuestions(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "state", required = false) Integer state,
            @RequestParam(value = "lvl", required = false) String lvl,
            @RequestParam(value = "kw", required = false) String kw,
            @RequestParam(value = "tag", required = false) String tag,
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "sortOrder", required = false) String sortOrder) {

        ProblemQuery query = new ProblemQuery();
        query.setPage(page);
        query.setPageSize(pageSize);
        query.setStatus(status);
        query.setState(state);
        query.setLvl(lvl);
        query.setKw(kw);
        query.setTag(tag);
        query.setSortBy(sortBy);
        query.setSortOrder(sortOrder);

        return Result.ok(problemQueryService.listQuestions(query));
    }

    /**
     * 题目详情·作答视角（#7）。
     *
     * <p><b>不返回</b> {@code officialSolution}；{@code chainPreview} 只有
     * {@code seqNo/kind/score}，隐藏测试点的输入输出永不下发。</p>
     *
     * @param id 题目 ID
     * @return 详情
     */
    @GetMapping("/questions/{id}")
    public Result<ProblemDetailVO> getQuestionDetail(@PathVariable("id") Long id) {
        return Result.ok(problemQueryService.getQuestionDetail(id));
    }
}
