package com.oj.teaching.controller;

import com.oj.common.core.result.Result;
import com.oj.teaching.service.AnalyticsService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 学情分析端点（契约 02 §6.6 学情分析簇，#54~#62）。
 *
 * <p><b>为什么单独开一个 Controller：</b>{@link AssignmentController} 是第 5 步的作业/考试主链路，
 * 已被 {@code verify-day12.py}（110 项）与 {@code _probe_step5_acceptance.mjs}（18 项）逐条守住。
 * 学情是另一件事：它跨判题域与 AI 域做聚合、口径随时可能因为答辩追问而调整。
 * 两者混在一个类里，改学情就得重跑作业验收——这是没必要的风险。</p>
 *
 * <p><b>路径前缀：</b>沿用 {@code /api/teaching}（网关不做 StripPrefix，服务自带完整前缀，
 * 见 agent.md §4.4）。{@code analytics/**} 子路径由本类独占，与作业端点无交集。</p>
 *
 * <p><b>所有方法只有一行：</b>鉴权、归属、口径、空数据兜底全部在
 * {@link AnalyticsService}。Controller 里出现任何业务判断，都意味着有一份口径漂到了
 * 「HTTP 层」，而那一层没有单元测试守着。</p>
 *
 * @author oj-cloud
 */
@RestController
@RequestMapping("/api/teaching/analytics")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    /**
     * #54 易错题排行。
     *
     * @param classId 班级；教师/学生可省略（服务端按身份解析）
     * @param range   {@code week} / {@code month} / {@code term}
     * @param mode    {@code ac}（按 AC 率升序）或 {@code tried}（按尝试数降序）
     * @param limit   条数上限（1–50，默认 10）
     */
    @GetMapping("/hard-problems")
    public Result<Map<String, Object>> hardProblems(@RequestParam(required = false) Long classId,
                                                    @RequestParam(required = false) String range,
                                                    @RequestParam(required = false) String mode,
                                                    @RequestParam(required = false) Integer limit) {
        return Result.ok(service.hardProblems(classId, range, mode, limit));
    }

    /**
     * #55 单题错误分析。
     *
     * @param problemId    题目 ID（必填，路径）
     * @param classId      班级；省略时按身份解析
     * @param assignmentId 只统计某个活动；省略表示不限
     */
    @GetMapping("/problem/{problemId}/errors")
    public Result<Map<String, Object>> problemErrors(@PathVariable Long problemId,
                                                     @RequestParam(required = false) Long classId,
                                                     @RequestParam(required = false) Long assignmentId) {
        return Result.ok(service.problemErrors(problemId, classId, assignmentId));
    }

    /**
     * #56 班级学情总览。
     *
     * @param range {@code week} / {@code month} / {@code term}
     */
    @GetMapping("/class/{classId}")
    public Result<Map<String, Object>> classOverview(@PathVariable Long classId,
                                                     @RequestParam(required = false) String range) {
        return Result.ok(service.classOverview(classId, range));
    }

    /**
     * #57 知识点掌握度（{@code masteryPercent} 越高越强）。
     */
    @GetMapping("/class/{classId}/knowledge")
    public Result<Map<String, Object>> classKnowledge(@PathVariable Long classId,
                                                      @RequestParam(required = false) String range) {
        return Result.ok(service.classKnowledge(classId, range));
    }

    /**
     * #58 逐生学情列表（分页 + 排序 + 风险筛选）。
     */
    @GetMapping("/class/{classId}/students")
    public Result<Map<String, Object>> classStudents(@PathVariable Long classId,
                                                     @RequestParam(required = false) String range,
                                                     @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer pageSize,
                                                     @RequestParam(required = false) Boolean riskOnly,
                                                     @RequestParam(required = false) String sortBy,
                                                     @RequestParam(required = false) String sortOrder,
                                                     @RequestParam(required = false) String kw) {
        return Result.ok(service.classStudents(classId, range, page, pageSize, riskOnly, sortBy, sortOrder, kw));
    }

    /**
     * #59 风险学生名单。
     *
     * @param level {@code ai} / {@code low} / {@code idle}；省略返回全部三档
     */
    @GetMapping("/class/{classId}/risk")
    public Result<Map<String, Object>> classRisk(@PathVariable Long classId,
                                                 @RequestParam(required = false) String level) {
        return Result.ok(service.classRisk(classId, level));
    }

    /**
     * #60 测试点通过热力图。
     *
     * @param limit 题目条数（1–20，默认 6）
     */
    @GetMapping("/class/{classId}/heatmap")
    public Result<Map<String, Object>> classHeatmap(@PathVariable Long classId,
                                                    @RequestParam(required = false) Integer limit) {
        return Result.ok(service.classHeatmap(classId, limit));
    }

    /**
     * #61 教师视角 AI 用量。
     *
     * @param groupBy 前端分组偏好 {@code stage} / {@code student} / {@code day}
     *                （响应体三个维度都会给，此参数只做取值校验）
     */
    @GetMapping("/ai-usage")
    public Result<Map<String, Object>> aiUsage(@RequestParam(required = false) Long classId,
                                               @RequestParam(required = false) String range,
                                               @RequestParam(required = false) String groupBy) {
        return Result.ok(service.aiUsage(classId, range, groupBy));
    }

    /**
     * #62 逐生学情明细（教师看学生 / 学生看自己；学生看他人 403）。
     */
    @GetMapping("/student/{userId}")
    public Result<Map<String, Object>> student(@PathVariable Long userId,
                                               @RequestParam(required = false) String range) {
        return Result.ok(service.student(userId, range));
    }
}
