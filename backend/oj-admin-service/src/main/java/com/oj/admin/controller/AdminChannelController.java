package com.oj.admin.controller;

import com.oj.admin.service.AdminChannelService;
import com.oj.admin.support.AdminGuard;
import com.oj.common.api.dto.admin.ChannelActionDTO;
import com.oj.common.api.dto.admin.ChannelCommandDTO;
import com.oj.common.api.dto.admin.ChannelDetailDTO;
import com.oj.common.api.dto.admin.ChannelPageDTO;
import com.oj.common.core.result.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型通道簇（契约 §7.2 #72~#79）。
 *
 * <p><b>权限</b>：8 个端点无一例外先过 {@link AdminGuard#requireAdmin(String)}——
 * 网关只证明「你是谁」，越权判定必须落在服务层（agent.md §4.4）。
 * 本服务允许被直连 8085 调试，绕开网关时连角色头都没有，服务层校验是唯一防线。</p>
 *
 * <p><b>URL 前缀不做 StripPrefix</b>：Controller 自带完整 {@code /api/admin}，
 * 网关原样透传（agent.md §4.4）。</p>
 *
 * @author oj-cloud
 */
@RestController
@RequestMapping("/api/admin/channels")
public class AdminChannelController {

    private final AdminChannelService service;
    private final AdminGuard guard;

    public AdminChannelController(AdminChannelService service, AdminGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    /**
     * #72 通道列表。
     *
     * @param page      页码，默认 1
     * @param pageSize  每页条数，默认 20，上限 100
     * @param state     健康态筛选：all / on / degrade / off / probing
     * @param kw        关键词
     * @param sortBy    排序键：today / name / ttft
     * @param sortOrder 排序方向：asc / desc
     * @return 分页结果（含 summary 四档汇总）
     */
    @GetMapping
    public Result<ChannelPageDTO> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String kw,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder) {
        guard.requireAdmin("查看模型通道");
        return Result.ok(service.list(page, pageSize, state, kw, sortBy, sortOrder));
    }

    /**
     * #73 通道详情。
     *
     * @param id 主键
     * @return 详情（含 24h 趋势 / 近期调用 / 降级事件）
     */
    @GetMapping("/{id}")
    public Result<ChannelDetailDTO> detail(@PathVariable Long id) {
        guard.requireAdmin("查看模型通道详情");
        return Result.ok(service.detail(id));
    }

    /**
     * #74 新增通道（创建后自动探活一次）。
     *
     * @param dto 请求体
     * @return 创建结果（healthStatus 固定 PROBING）
     */
    @PostMapping
    public Result<ChannelActionDTO.Created> create(@RequestBody ChannelCommandDTO dto) {
        guard.requireAdmin("新增模型通道");
        return Result.ok(service.create(dto));
    }

    /**
     * #75 更新通道配置。
     *
     * @param id  主键
     * @param dto 请求体（null 字段表示不改）
     * @return 更新结果
     */
    @PutMapping("/{id}")
    public Result<ChannelActionDTO.Updated> update(@PathVariable Long id,
                                                   @RequestBody ChannelCommandDTO dto) {
        guard.requireAdmin("修改模型通道");
        return Result.ok(service.update(id, dto));
    }

    /**
     * #76 删除通道（有历史调用则转禁用）。
     *
     * @param id    主键
     * @param force 是否越过「唯一可用通道」保护
     * @return 删除结果
     */
    @DeleteMapping("/{id}")
    public Result<ChannelActionDTO.Deleted> delete(@PathVariable Long id,
                                                   @RequestParam(defaultValue = "false") boolean force) {
        guard.requireAdmin("删除模型通道");
        return Result.ok(service.delete(id, force));
    }

    /**
     * #77 启用 / 禁用 / 手动降级。
     *
     * @param id     主键
     * @param payload 请求体 {@code {action: enable|disable|degrade}}
     * @return 状态变更结果
     */
    @PutMapping("/{id}/state")
    public Result<ChannelActionDTO.StateChanged> changeState(@PathVariable Long id,
                                                             @RequestBody StatePayload payload) {
        guard.requireAdmin("变更模型通道状态");
        return Result.ok(service.changeState(id, payload == null ? null : payload.action()));
    }

    /**
     * #78 连通性探测（真实发一次最小请求）。
     *
     * @param id        主键
     * @param payload   可选请求体 {@code {timeoutMs}}
     * @param timeoutMs 也支持查询参数写法，便于 curl 自检
     * @return 探测结果
     */
    @PostMapping("/{id}/probe")
    public Result<ChannelActionDTO.Probe> probe(@PathVariable Long id,
                                                @RequestBody(required = false) ProbePayload payload,
                                                @RequestParam(required = false) Long timeoutMs) {
        guard.requireAdmin("探测模型通道");
        Long t = timeoutMs != null ? timeoutMs : (payload == null ? null : payload.timeoutMs());
        return Result.ok(service.probe(id, t));
    }

    /**
     * #79 从降级恢复（清零连续失败计数并重新探活）。
     *
     * @param id 主键
     * @return 恢复结果
     */
    @PostMapping("/{id}/recover")
    public Result<ChannelActionDTO.Recovered> recover(@PathVariable Long id) {
        guard.requireAdmin("恢复模型通道");
        return Result.ok(service.recover(id));
    }

    /**
     * #77 的请求体（单独定义而不是复用 CommandDTO：契约只允许一个 action 字段）。
     *
     * @param action enable / disable / degrade
     */
    public record StatePayload(String action) {
    }

    /**
     * #78 的请求体。
     *
     * @param timeoutMs 可选超时（毫秒）
     */
    public record ProbePayload(Long timeoutMs) {
    }
}
