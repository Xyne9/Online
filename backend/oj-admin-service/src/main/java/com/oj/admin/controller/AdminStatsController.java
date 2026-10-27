package com.oj.admin.controller;

import com.oj.admin.service.AdminStatsService;
import com.oj.admin.support.AdminGuard;
import com.oj.common.api.dto.admin.PlatformStatsDTO;
import com.oj.common.core.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全站大盘统计（契约 §7.6.1 #87）。
 *
 * <p>供 {@code index.html:189-222} 的门户数字位与 {@code a-models.html:410-471}
 * 的运行概览共用一份聚合。<b>没有 mock 分支</b>：所有数字都是 SQL 计数出来的，
 * 查不到就是 0（空态），不编数字（agent.md §3.4）。</p>
 *
 * @author oj-cloud
 */
@RestController
@RequestMapping("/api/admin")
public class AdminStatsController {

    private final AdminStatsService service;
    private final AdminGuard guard;

    public AdminStatsController(AdminStatsService service, AdminGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    /**
     * 全站大盘。
     *
     * @param range 区间（today / 7d / 30d，默认 7 天），只影响 trend[]
     * @return 大盘数据
     */
    @GetMapping("/stats")
    public Result<PlatformStatsDTO> stats(@RequestParam(required = false) String range) {
        guard.requireAdmin("查看全站统计");
        return Result.ok(service.stats(range));
    }
}
