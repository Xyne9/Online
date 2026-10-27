package com.oj.admin.controller;

import com.oj.admin.service.AdminSandboxService;
import com.oj.admin.support.AdminGuard;
import com.oj.common.api.dto.admin.SandboxInstancePageDTO;
import com.oj.common.api.dto.admin.SandboxKillDTO;
import com.oj.common.api.dto.admin.SandboxLogDTO;
import com.oj.common.core.result.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 沙箱运维（契约 §7.5 #84~#86）。
 *
 * <p><b>终止是唯一有真实副作用的端点</b>：{@code DELETE} 会把
 * {@code judge_sandbox_instance} 从 QUEUED/RUNNING 置为 KILLED 并写 {@code exit_code=137}，
 * 已处于终态返回 409。它不是「改个前端状态」——判题链路读的就是这张表。</p>
 *
 * <p>reason 走查询参数而不是请求体：{@code a-audit.html} 的 {@code kill(b)} 是按钮直点，
 * 且 {@code DELETE} 带 body 在部分代理下会被丢弃；{@code api/admin.js:85-88} 也是按
 * query 传的。两者一致。</p>
 *
 * @author oj-cloud
 */
@RestController
@RequestMapping("/api/admin/sandbox/instances")
public class AdminSandboxController {

    private final AdminSandboxService service;
    private final AdminGuard guard;

    public AdminSandboxController(AdminSandboxService service, AdminGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    /**
     * #84 沙箱实例列表 + 池统计。
     *
     * @param page        页码
     * @param pageSize    每页条数
     * @param state       running / queued / exited / killed
     * @param lang        cpp / java / python
     * @param kw          实例号 / 用户名 / 学号
     * @param autoRefresh 前端自动刷新开关（仅回显语义，不参与查询）
     * @return 分页结果
     */
    @GetMapping
    public Result<SandboxInstancePageDTO> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String lang,
            @RequestParam(required = false) String kw,
            @RequestParam(required = false) Boolean autoRefresh) {
        guard.requireAdmin("查看沙箱实例");
        return Result.ok(service.list(page, pageSize, state, lang, kw, autoRefresh));
    }

    /**
     * #85 容器日志（最多 500 行）。
     *
     * @param sandboxInstanceId 对外句柄
     * @param lines             期望行数（默认 200，上限 500）
     * @param since             起始时刻（毫秒）
     * @return 日志（无日志时为空数组 + note，不 404）
     */
    @GetMapping("/{sandboxInstanceId}/logs")
    public Result<SandboxLogDTO> logs(@PathVariable String sandboxInstanceId,
                                      @RequestParam(required = false) Integer lines,
                                      @RequestParam(required = false) Long since) {
        guard.requireAdmin("查看沙箱日志");
        return Result.ok(service.logs(sandboxInstanceId, lines, since));
    }

    /**
     * #86 终止实例并回收资源。
     *
     * @param sandboxInstanceId 对外句柄
     * @param reason            终止原因（仅日志记录）
     * @return 终止结果
     */
    @DeleteMapping("/{sandboxInstanceId}")
    public Result<SandboxKillDTO> kill(@PathVariable String sandboxInstanceId,
                                       @RequestParam(required = false) String reason) {
        guard.requireAdmin("终止沙箱实例");
        return Result.ok(service.kill(sandboxInstanceId, reason));
    }
}
