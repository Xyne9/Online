package com.oj.common.api.dto.admin;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型通道分页结果（契约 02 §7.2.1 {@code GET /api/admin/channels} 的 {@code data}）。
 *
 * <p><b>字段名逐字对齐契约表格</b>：{@code channelCode} / {@code healthStatus} / {@code avgTtftMs} /
 * {@code todayCalls} / {@code tokensIn} / {@code tokensOut} / {@code costCents} / {@code lastProbeAt}，
 * 一个都不改名、不加下划线缩写。</p>
 *
 * <p><b>单位口径</b>（agent.md §4.4）：{@code timeoutMs} 毫秒（DB 存 {@code timeout_seconds}，
 * 接口层 ×1000 下发——见 §8.2 的「超时单位 1000 倍误差」教训）；{@code lastProbeAt} 为 Long 毫秒时间戳。</p>
 *
 * <p><b>roleBindings 取值</b>：直接下发 {@code ai_model_role.role} 原值
 * （{@code ROUTER} / {@code TUTOR} / {@code GUARDRAIL}），与 §8.6 DDL 注释一致；
 * 契约示例里写的 {@code "Tutor"} 是展示大小写，服务端以枚举原值为准（入参大小写不敏感）。</p>
 *
 * @author oj-cloud
 */
@Data
public class ChannelPageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前页通道列表 */
    private List<Item> list = new ArrayList<>();

    /** 过滤后的总条数 */
    private Long total = 0L;

    /** 当前页码（从 1 起） */
    private Integer page = 1;

    /** 每页条数（1~100） */
    private Integer pageSize = 20;

    /** 是否存在下一页 */
    private Boolean hasNext = false;

    /** 全量汇总（不受筛选影响，供页面顶部黄条/四档计数） */
    private Summary summary = new Summary();

    /** 单个通道 */
    @Data
    public static class Item implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 主键 */
        private Long id;

        /** 稳定通道标识，如 local-qwen */
        private String channelCode;

        /** 展示名 */
        private String channelName;

        /** 厂商：Ollama / DeepSeek / Qwen / Zhipu / OpenAI-Compatible */
        private String vendor;

        /** 具体模型名 */
        private String modelName;

        /** 角色绑定（ROUTER / TUTOR / GUARDRAIL 的数组，按 priority 升序） */
        private List<String> roleBindings = new ArrayList<>();

        /** API 端点 */
        private String baseUrl;

        /** 超时（毫秒）= timeout_seconds × 1000 */
        private Long timeoutMs;

        /** 单次回复 Token 上限 */
        private Integer maxTokens;

        /** 采样温度 */
        private Double temperature;

        /** 最大并发 */
        private Integer maxConcurrency;

        /** 是否启用 */
        private Boolean isEnabled;

        /** 健康态：ON / DEGRADE / OFF / PROBING */
        private String healthStatus;

        /** 健康异常或降级原因 */
        private String healthNote;

        /** 今日平均首字耗时（毫秒），无调用为 null */
        private Long avgTtftMs;

        /** 今日调用次数 */
        private Long todayCalls;

        /** 今日输入 Token */
        private Long tokensIn;

        /** 今日输出 Token */
        private Long tokensOut;

        /** 今日成本（分） */
        private Long costCents;

        /** 最近一次探活时间（毫秒） */
        private Long lastProbeAt;
    }

    /** 全量汇总 */
    @Data
    public static class Summary implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 在线通道数（is_enabled=1 且 health_status=ON） */
        private Integer on = 0;

        /** 降级通道数（health_status=DEGRADE） */
        private Integer degrade = 0;

        /** 关闭通道数（is_enabled=0 或 health_status=OFF） */
        private Integer off = 0;

        /** 今日全站 AI 调用总数 */
        private Long todayTotal = 0L;
    }
}
