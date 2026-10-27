package com.oj.common.api.dto.admin;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 通道详情（契约 02 §7.2.2 {@code GET /api/admin/channels/{id}} 的 {@code data}）。
 *
 * <p><b>apiKeyMasked 红线</b>（agent.md §2.6）：本字段只允许出现 {@code sk-****3f2a} 这类
 * 「前缀 + 四个星号 + 后四位」的脱敏形态，**原文密钥绝不下发、绝不写日志**。
 * 密钥本身在 DB 里是 {@code ${OJ_ZHIPU_API_KEY}} 这类占位符，由 SecretResolver 运行时解析。</p>
 *
 * <p><b>trend24h / degradeEvents 的数据来源说明</b>：本项目没有「降级事件表」，
 * 也没有按小时的预聚合表，因此两者都由 {@code ai_chat_record} 真实聚合而来
 * （trend24h = 近 24 小时按小时分桶的调用与 TTFT；degradeEvents = {@code is_fallback=1}
 * 的真实降级记录），不是造数据。</p>
 *
 * @author oj-cloud
 */
@Data
public class ChannelDetailDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String channelCode;

    private String channelName;

    private String vendor;

    private String modelName;

    /** ROUTER / TUTOR / GUARDRAIL（DB 枚举原值） */
    private List<String> roleBindings = new ArrayList<>();

    private String baseUrl;

    /** 脱敏密钥，形态如 {@code sk-****3f2a}；未配置为 null */
    private String apiKeyMasked;

    /** 超时（毫秒） */
    private Long timeoutMs;

    private Integer maxTokens;

    private Double temperature;

    private Integer maxConcurrency;

    private Boolean isEnabled;

    private String healthStatus;

    private String healthNote;

    /** 今日平均首字耗时（毫秒） */
    private Long avgTtftMs;

    /** 近 24 小时按小时分桶趋势 */
    private List<TrendPoint> trend24h = new ArrayList<>();

    /** 最近 10 次调用 */
    private List<RecentCall> recentCalls = new ArrayList<>();

    /** 近期降级事件（由 is_fallback=1 的真实记录派生） */
    private List<DegradeEvent> degradeEvents = new ArrayList<>();

    /** 24 小时趋势点 */
    @Data
    public static class TrendPoint implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 小时（0~23，按时间先后排列，覆盖最近 24 小时） */
        private Integer hour;

        /** 该小时平均 TTFT（毫秒），无调用为 null */
        private Long ttftMs;

        /** 该小时调用次数 */
        private Long calls;
    }

    /** 近期调用 */
    @Data
    public static class RecentCall implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long recordId;

        /** 调用时刻（毫秒） */
        private Long at;

        private Long ttftMs;

        /** tokensIn + tokensOut */
        private Long tokens;

        /** pass / block / rewrite */
        private String guardCode;
    }

    /** 降级事件 */
    @Data
    public static class DegradeEvent implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 事件时刻（毫秒） */
        private Long at;

        /** 降级前通道 */
        private String from;

        /** 降级后通道（实际执行的模型通道） */
        private String to;

        private String reason;
    }
}
