package com.oj.common.api.dto.admin;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 调用审计分页结果（契约 02 §7.4.1 {@code GET /api/admin/audit/calls} 的 {@code data}）。
 *
 * <p><b>guardCode 是派生值，不改原列</b>（契约 §7.4 备注）：DB 里只有 {@code guardrail_action}
 * 整数（0 通过 / 1 拦截 / 2 改写），接口按
 * {@code 0→pass、1→block、2→rewrite} 派生为三分类字符串。{@code guardAction} 原值一并下发，
 * 保证「原列可溯源」。</p>
 *
 * <p><b>route</b>：由 {@code ai_model_config.provider_type} 判定，
 * {@code OLLAMA → local}，其余 {@code cloud}。这样「本地 / 云端」口径与 AI 服务的降级链同源。</p>
 *
 * <p><b>summary.hitDist</b>：契约示例的 code 是 {@code leak / jailbreak / sensitive}
 * 三分类，而 DB 的 {@code guard_reason} 字典是
 * {@code NONE / LEAK_RATE / JAILBREAK / SENSITIVE_WORD / OVER_LENGTH_CODE}（§8.6 DDL）。
 * 映射关系写在 {@code AdminAuditService.HIT_DIST_MAPPING}，两边都是真实字段，不做虚构。</p>
 *
 * @author oj-cloud
 */
@Data
public class AuditCallPageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<Item> list = new ArrayList<>();

    private Long total = 0L;

    private Integer page = 1;

    private Integer pageSize = 20;

    private Boolean hasNext = false;

    private Summary summary = new Summary();

    /** 单条调用记录 */
    @Data
    public static class Item implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long recordId;

        /** 调用时刻（毫秒） */
        private Long at;

        private Long userId;

        /** 学生姓名（sys_user.real_name） */
        private String userName;

        /** 阶段 1~4 */
        private Integer stage;

        /** 读题拆解 / 编码引导 / 排错诊断 / AC 复盘 */
        private String stageName;

        private String channelCode;

        private String modelName;

        /** local / cloud */
        private String route;

        /** 首字耗时（毫秒） */
        private Long ttftMs;

        private Long tokensIn;

        private Long tokensOut;

        private Long costCents;

        /** 原值：0 pass / 1 block / 2 rewrite */
        private Integer guardAction;

        /** 派生值：pass / block / rewrite */
        private String guardCode;

        /** 命中原因原值：NONE / LEAK_RATE / JAILBREAK / SENSITIVE_WORD / OVER_LENGTH_CODE */
        private String guardReason;

        /** 备注：会话标识（session_id），供审计页「备注」列展示 */
        private String note;

        private Long submissionId;

        private String traceId;
    }

    /** 全站汇总（与筛选无关，供顶部主带） */
    @Data
    public static class Summary implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 今日调用总数 */
        private Long todayTotal = 0L;

        /** 今日护栏命中三分类分布 */
        private List<HitDist> hitDist = new ArrayList<>();
    }

    /** 命中分布一项 */
    @Data
    public static class HitDist implements Serializable {

        private static final long serialVersionUID = 1L;

        /** leak / jailbreak / sensitive */
        private String code;

        private Long count;
    }
}
