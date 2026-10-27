package com.oj.common.api.feign;

import com.oj.common.api.dto.admin.SandboxPageDTO;
import com.oj.common.api.dto.admin.SandboxQueryDTO;
import com.oj.common.api.dto.admin.ServiceHealthDTO;
import com.oj.common.core.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * ⑦ 运维管理聚合接口（v2.0 新增，2 个方法）。
 *
 * <p>契约来源：设计文档 §6 第 7 条。</p>
 *
 * <p>调用方：admin 服务自身聚合各服务健康与沙箱实例。
 * 注意：本客户端的 {@code name} 指向 {@code oj-admin-service}，是<b>被</b> admin 调用
 * 其余服务时反向使用的聚合入口（各服务内部暴露 {@code /inner/admin/**} 供 admin 抓取）。</p>
 *
 * @author oj-cloud
 */
@FeignClient(name = "oj-admin-service", contextId = "adminAggregate", path = "/inner/admin")
public interface AdminAggregateFeignClient {

    /**
     * 上报/查询某服务的健康快照。
     *
     * @param service 服务名，如 oj-teaching-service
     * @return 健康快照
     */
    @GetMapping("/health/{service}")
    Result<ServiceHealthDTO> reportHealth(@PathVariable("service") String service);

    /**
     * 分页查询沙箱实例（admin 沙箱运维页）。
     *
     * @param q 查询条件
     * @return 沙箱分页结果
     */
    @PostMapping("/sandbox/query")
    Result<SandboxPageDTO> listSandboxes(@RequestBody SandboxQueryDTO q);
}
