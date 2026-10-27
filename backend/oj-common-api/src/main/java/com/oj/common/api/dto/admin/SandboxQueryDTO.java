package com.oj.common.api.dto.admin;

import lombok.Data;

import java.io.Serializable;

/**
 * 沙箱实例查询条件（`AdminAggregateFeignClient#listSandboxes` 的请求体）。
 *
 * <p>判题服务侧的沙箱实例列表由 admin 聚合展示；查询条件沿用全站通用分页/排序入参约定
 * （§1.3：page 默认 1、pageSize 默认 20 上限 100、排序字段必须白名单校验）。</p>
 *
 * @author oj-cloud
 */
@Data
public class SandboxQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 页码，从 1 开始 */
    private Integer page = 1;

    /** 每页条数，默认 20，上限 100 */
    private Integer pageSize = 20;

    /** 排序字段白名单：id / createdAt / durationMs / memoryKb */
    private String sortBy = "createdAt";

    /** 排序方向：asc / desc */
    private String sortOrder = "desc";

    /** 状态筛选：running / finished / killed / timeout / all */
    private String status = "all";

    /** 语言筛选：cpp / java / python / all */
    private String language = "all";

    /** 提交 ID 精确筛选 */
    private Long submissionId;

    /** 用户 ID 精确筛选 */
    private Long userId;

    /** 起始时间（毫秒时间戳） */
    private Long fromAt;

    /** 结束时间（毫秒时间戳） */
    private Long toAt;

    /** 是否只看超时实例 */
    private Boolean onlyTimeout;

    /** 是否只看被强杀的实例（OOM / 无限循环） */
    private Boolean onlyKilled;
}
