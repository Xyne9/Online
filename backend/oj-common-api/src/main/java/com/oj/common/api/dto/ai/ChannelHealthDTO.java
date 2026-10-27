package com.oj.common.api.dto.ai;

import lombok.Data;

import java.io.Serializable;

/**
 * 模型通道健康状态（`AiInnerFeignClient#listChannelHealth` 的响应体元素）。
 *
 * <p>admin 服务的「通道探活」页直接消费这个结构：一行一个通道，展示延迟、成功率与当前角色绑定。</p>
 *
 * @author oj-cloud
 */
@Data
public class ChannelHealthDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 通道编码，如 deepseek / qwen / glm / local-llama */
    private String channelCode;

    /** 通道展示名 */
    private String channelName;

    /** 供应商：cloud / local */
    private String providerType;

    /** 模型名 */
    private String modelName;

    /** 健康状态：UP / DOWN / DEGRADED / UNKNOWN */
    private String status;

    /** 是否启用 */
    private Boolean enabled;

    /** 最近一次探活延迟（毫秒） */
    private Long latencyMs;

    /** 最近 1 小时成功率（0~100） */
    private Double successRate;

    /** 最近一次探活时间（毫秒时间戳） */
    private Long lastProbeAt;

    /** 绑定的角色：primary（主）/ fallback（降级）/ guardrail（护栏）/ embedding */
    private String boundRole;

    /** 降级链路中的顺位（0 为主通道） */
    private Integer priority;

    /** 失败原因（status != UP 时有值） */
    private String lastError;
}
