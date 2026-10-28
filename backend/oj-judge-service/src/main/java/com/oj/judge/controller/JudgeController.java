package com.oj.judge.controller;

import com.oj.common.core.result.Result;
import com.oj.common.web.context.UserContext;
import com.oj.judge.dto.JudgeRequests;
import com.oj.judge.service.JudgeService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 判题对外接口（契约 §4 的 7 个端点，{@code /api/judge/**}）。
 *
 * <h2>端点清单</h2>
 * <table border="1">
 *   <caption>与契约 §4 逐字对齐</caption>
 *   <tr><th>#</th><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>19</td><td>POST</td><td>/self-test</td><td>自测（v1.0 冻结）</td></tr>
 *   <tr><td>20</td><td>POST</td><td>/submit</td><td>提交判题（v1.0 冻结）</td></tr>
 *   <tr><td>21</td><td>GET</td><td>/status/{submissionId}</td><td>轮询状态（v1.0 冻结）</td></tr>
 *   <tr><td>22</td><td>GET</td><td>/submissions</td><td>提交记录列表（含内嵌统计）</td></tr>
 *   <tr><td>23</td><td>GET</td><td>/submissions/{id}</td><td>提交详情</td></tr>
 *   <tr><td>24</td><td>GET</td><td>/submissions/trace</td><td>同题轨迹</td></tr>
 *   <tr><td>25</td><td>POST</td><td>/run-sample</td><td>运行样例 / 自定义输入</td></tr>
 * </table>
 *
 * <p><b>路由陷阱（务必注意）：</b>{@code GET /submissions/trace} 与
 * {@code GET /submissions/{id}} 路径模式同形。本类用两道保险：
 * ①{@code /trace} 声明在 {@code /{id}} 之前；②{@code {id}} 加了 {@code \\d+} 数字约束。
 * 只靠框架的「字面量优先」规则是脆弱的 —— 新增一个 {@code /submissions/export}
 * 就会重演一次「被当成 ID」的事故。</p>
 *
 * <p><b>用户身份：</b>网关校验 JWT 后注入 {@code X-User-Id} / {@code X-User-Role}，
 * 本类通过 {@link UserContext} 读取。<b>直接 curl 8083 时没有这些头</b>，
 * 此时 {@code userIdOrNull()} 为 null —— 学生视角接口会因此 401，
 * 这是正确行为（未登录不能看他人提交）。</p>
 *
 * @author oj-cloud
 */
@RestController
@RequestMapping("/api/judge")
public class JudgeController {

    private static final Logger log = LoggerFactory.getLogger(JudgeController.class);

    private final JudgeService judgeService;

    public JudgeController(JudgeService judgeService) {
        this.judgeService = judgeService;
    }

    /**
     * #19 · 自测（v1.0 冻结端点，只增 {@code statusCode} 字段）。
     *
     * <p>用自定义输入跑一次代码，<b>不比对期望输出、不写提交记录</b>。</p>
     *
     * @param request {@code {problemId, language, code, customInput}}
     * @return {@code {status:"FINISHED", statusCode, compileError, actualOutput, timeMs, memoryKb}}
     */
    @PostMapping("/self-test")
    public Result<Map<String, Object>> selfTest(@Valid @RequestBody JudgeRequests.SelfTestRequest request) {
        return Result.ok(judgeService.selfTest(request, UserContext.userIdOrNull()));
    }

    /**
     * #20 · 提交判题（v1.0 冻结端点）。
     *
     * <p>同步只做「落库 + 入队」，立刻返回排队中的提交号；判题由 MQ 消费者异步执行。</p>
     *
     * @param request {@code {problemId, assignmentId, language, code}}
     * @return {@code {submissionId, displayNo, status:0, isOverdue:0}}
     */
    @PostMapping("/submit")
    public Result<Map<String, Object>> submit(@Valid @RequestBody JudgeRequests.SubmitRequest request) {
        return Result.ok(judgeService.submit(request, UserContext.userId()));
    }

    /**
     * #21 · 轮询判题状态（v1.0 冻结端点，只增 statusCode / seqNo / kind / score / gotScore）。
     *
     * @param submissionId 提交 ID
     * @return 状态详情（含 {@code caseDetails} 与 {@code aiStateUpdate}）
     */
    @GetMapping("/status/{submissionId}")
    public Result<Map<String, Object>> status(@PathVariable("submissionId") Long submissionId) {
        return Result.ok(judgeService.status(submissionId, UserContext.userIdOrNull(),
                UserContext.isTeacherOrAbove()));
    }

    /**
     * #24 · 同题轨迹。
     *
     * <p><b>必须声明在 {@code /submissions/{id}} 之前</b>：虽然 Spring 会优先匹配字面量，
     * 但显式顺序能让后来维护的人一眼看清意图，不依赖框架的隐式排序。</p>
     *
     * @param problemId 题目 ID
     * @param userId    目标用户（缺省为当前用户）
     * @param limit     条数（默认 20，上限 50）
     * @return {@code {problemId, list, bestSubmissionId, bestScore}}
     */
    @GetMapping("/submissions/trace")
    public Result<Map<String, Object>> trace(@RequestParam("problemId") Long problemId,
                                             @RequestParam(value = "userId", required = false) Long userId,
                                             @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.ok(judgeService.trace(problemId, userId, limit,
                UserContext.userIdOrNull(), UserContext.isTeacherOrAbove()));
    }

    /**
     * #22 · 提交记录列表（含内嵌统计，不单开 /stats 端点）。
     *
     * @param page      页码（默认 1）
     * @param pageSize  每页条数（默认 20，上限 100）
     * @param status    状态筛选 {@code all/ac/wa/tle/mle/re/ce}
     * @param lang      语言筛选 {@code all/cpp/python/java}
     * @param range     近 N 天（3/7/30/126，默认 3；126 为本学期）
     * @param kw        关键词（题目名或展示号模糊）
     * @param sortBy    排序字段 {@code id/timeMs/score}，默认 id
     * @param sortOrder 排序方向 {@code asc/desc}，默认 desc
     * @return {@code {list, total, page, pageSize, hasNext, stats}}
     */
    @GetMapping("/submissions")
    public Result<Map<String, Object>> submissions(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "lang", required = false) String lang,
            @RequestParam(value = "range", required = false) Integer range,
            @RequestParam(value = "kw", required = false) String kw,
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "sortOrder", required = false) String sortOrder) {
        // 学生只能看本人（userId 由服务端注入，前端传什么都不影响）；
        // 教师/管理员看全部（userId=null）
        Long userId = UserContext.isTeacherOrAbove() ? null : UserContext.userId();
        return Result.ok(judgeService.submissions(page, pageSize, status, lang, range, kw, sortBy, sortOrder, userId));
    }

    /**
     * #23 · 提交详情（含代码 + 逐测试点预览 + 同题轨迹）。
     *
     * <p><b>{@code {id:\\d+}} 的正则约束是必需的</b>：{@code /submissions/trace} 与
     * {@code /submissions/{id}} 在路径模式上完全同形。虽然 Spring 的
     * {@code AntPathMatcher} 会让字面量优先于变量，但一旦有人把 {@code trace}
     * 改名或新增 {@code /submissions/export} 之类的端点，就会立刻踩到
     * 「字面量被当成 ID → 类型转换失败 → 400」的坑。加上数字约束后，
     * 任何非数字段都<b>永远不会</b>落到这个映射上，语义自解释。</p>
     *
     * @param id           提交 ID（必须为数字）
     * @param previewLimit 每个测试点预览字符数（默认 120，上限 500）
     * @return 详情
     */
    @GetMapping("/submissions/{id:\\d+}")
    public Result<Map<String, Object>> submissionDetail(
            @PathVariable("id") Long id,
            @RequestParam(value = "previewLimit", required = false) Integer previewLimit) {
        return Result.ok(judgeService.submissionDetail(id, previewLimit,
                UserContext.userIdOrNull(), UserContext.isTeacherOrAbove()));
    }

    /**
     * #25 · 运行样例 / 自定义输入。
     *
     * <p>与 self-test 的区别：支持 {@code mode}，且返回<b>多样例结构</b>
     * （每个样例的输入/期望/实际都下发，供工作台并排对比）。</p>
     *
     * @param request {@code {problemId, language, code, mode, customInput?, sampleSeq?}}
     * @return {@code {mode, status, compileError, cases, actualOutput, timeMs, memoryKb, truncated}}
     */
    @PostMapping("/run-sample")
    public Result<Map<String, Object>> runSample(@Valid @RequestBody JudgeRequests.RunSampleRequest request) {
        return Result.ok(judgeService.runSample(request, UserContext.userIdOrNull()));
    }
}
