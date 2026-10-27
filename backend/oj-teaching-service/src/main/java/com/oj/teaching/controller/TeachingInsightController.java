package com.oj.teaching.controller;

import com.oj.common.core.result.Result;
import com.oj.teaching.service.AnalyticsService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 教师端首页（{@code t-dashboard.html}）需要的两个聚合端点：#52 待办、#53 周趋势。
 *
 * <p><b>为什么单开一个类而不并进 {@code analytics/**}：</b>契约 02 §6.5 把这两个端点
 * 定在 {@code /api/teaching/todos} 与 {@code /api/teaching/week-trend}，<b>不带 {@code analytics}
 * 段落</b>，它们属于「班级与待办簇」而不是「学情分析簇」。路径是契约的一部分，
 * 不能为了少写一个类就挪位置——前端 {@code api/analytics.js:62-63} 已经按现在的路径发了请求。</p>
 *
 * <p><b>为什么不加进 {@link AssignmentController}：</b>那个类承载第 5 步的作业主链路，
 * 被 {@code verify-day12.py}（110 项）与 {@code _probe_step5_acceptance.mjs}（18 项）逐条守住。
 * 往里面加方法虽然不改既有行为，但每次改都要重跑那两套验收；独立成类可以把影响面锁在
 * 「新增的路径」上。两个类同用 {@code /api/teaching} 前缀不会冲突——Spring 只按
 * 「方法 + 完整路径」判定歧义，而这里的两条路径与作业端点毫无重叠。</p>
 *
 * @author oj-cloud
 */
@RestController
@RequestMapping("/api/teaching")
public class TeachingInsightController {

    private final AnalyticsService service;

    public TeachingInsightController(AnalyticsService service) {
        this.service = service;
    }

    /**
     * #52 待办聚合（发布 / 查重 / 通道 / 催交四类）。
     *
     * @param limit 只返回前 N 条（按「有实质待办」优先排序）；省略返回全部四条
     */
    @GetMapping("/todos")
    public Result<Map<String, Object>> todos(@RequestParam(required = false) Integer limit) {
        return Result.ok(service.todos(limit));
    }

    /**
     * #53 周日历趋势（近 N 天逐日提交 / AC / 通过率）。
     *
     * @param classId 班级；教师/学生可省略（服务端按身份解析），ADMIN 省略表示全站
     * @param weeks   周数（默认 1，即 7 天）
     * @param range   与 {@code weeks} 二选一，取值 {@code 7d} / {@code 30d} / {@code term} / {@code all}；
     *                同时给出时 {@code range} 优先（显式时间范围比「第几周」更不容易误读）
     */
    @GetMapping("/week-trend")
    public Result<Map<String, Object>> weekTrend(@RequestParam(required = false) Long classId,
                                                 @RequestParam(required = false) Integer weeks,
                                                 @RequestParam(required = false) String range) {
        return Result.ok(service.weekTrend(classId, weeks, range));
    }
}
