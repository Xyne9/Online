package com.oj.admin.controller;

import com.oj.admin.service.AdminHealthService;
import com.oj.admin.support.AdminGuard;
import com.oj.common.api.dto.admin.PlatformHealthDTO;
import com.oj.common.core.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全站健康聚合（契约 §7.3.1 #80）。
 *
 * <p><b>⚠️ 本类此前是空壳实现（返回字符串 {@code "oj-admin-service UP"}）</b>，
 * 已按契约 §7.3.1 改成完整对象。踩过的两个坑记录在此，防止回退：</p>
 *
 * <ol>
 *   <li><b>返回体必须是契约对象</b>，不是字符串。字符串过不了
 *       {@code data.services[].status} 这类前端取值，页面会渲染成空白。</li>
 *   <li><b>权限必须由服务层自己判</b>——网关 {@code whitelist} 里有
 *       {@code /api/&#42;/health}（gateway {@code application.yml}），
 *       这个请求会<b>绕过 JWT 校验直达服务</b>，网关根本不会注入
 *       {@code X-User-Id} / {@code X-User-Role}。所以「学生 / 教师也能 200」这个
 *       漏洞只能在这里堵：无身份头 → 401，有但不是 ADMIN → 403。
 *       <b>不要删掉下面这行 guard 调用。</b></li>
 * </ol>
 *
 * <p>注意「/api/admin/health」与「保留给网关白名单的自检」的取舍：
 * 契约 §7.3.1 明确该端点错误码是 {@code 403,502}（**不是** 200 公开），
 * 因此它属于受保护的管理端点，白名单那条规则在本端点上的效果必须被服务层覆盖。</p>
 *
 * @author oj-cloud
 */
@RestController
@RequestMapping("/api/admin")
public class HealthController {

    private final AdminHealthService healthService;
    private final AdminGuard guard;

    public HealthController(AdminHealthService healthService, AdminGuard guard) {
        this.healthService = healthService;
        this.guard = guard;
    }

    /**
     * 全站健康：业务服务 + 中间件 + 通道 + 沙箱池。
     *
     * @param deep 是否逐个真探非 ON 通道（默认 false，只读库内健康态；
     *             true 会真发请求，探活锁保证 1 次/10 秒）
     * @return 健康快照
     */
    @GetMapping("/health")
    public Result<PlatformHealthDTO> health(@RequestParam(defaultValue = "false") boolean deep) {
        /*
          权限：/api/admin/health 落在网关白名单「单层通配 health」规则的匹配范围里，
          请求可能完全没有 X-User-Role 头。此时 UserContext.role() 抛 401，
          有角色但不是 ADMIN 抛 403——两者都在 GlobalExceptionHandler 里转成同值 HTTP 码。
        */
        guard.requireAdmin("查看全站健康");
        return Result.ok(healthService.health(deep));
    }
}
