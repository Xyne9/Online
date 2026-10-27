package com.oj.common.api.dto.admin;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 调用详情（契约 02 §7.4.2 {@code GET /api/admin/audit/calls/{recordId}} 的 {@code data}）。
 *
 * <p><b>promptText / replyText 是审计端点存在的意义</b>：管理端要能在抽屉里看到
 * 「学生到底问了什么、模型到底回了什么」以及护栏为什么这么判。这两个字段
 * 与 {@code ai_chat_record.user_message} / {@code ai_response} 一一对应，不做截断
 * （契约未规定上限，前端抽屉自带滚动）。</p>
 *
 * <p><b>chain 的语义</b>：契约 §7.4.2 要求「关联判题链」，元素为
 * {@code {seqNo, kind, status}}。本项目没有独立的「链」表，因此由
 * {@code judge_submission.case_details}（逐测试点明细 JSON）真实展开：
 * {@code seqNo} = 测试点序号，{@code kind} = 测试点类型（sample / normal / stress），
 * {@code status} = 该点判定（AC / WA / TLE / MLE / RE）。没有关联提交时返回空数组，
 * **不回填假数据**（agent.md §3.4 红线）。</p>
 *
 * @author oj-cloud
 */
@Data
public class AuditCallDetailDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long recordId;

    /** 调用时刻（毫秒） */
    private Long at;

    /** 调用者 */
    private User user;

    private Integer stage;

    private String stageName;

    private String channelCode;

    private String modelName;

    /** local / cloud */
    private String route;

    /** 是否触发了端云降级 */
    private Boolean isFallback;

    private Long ttftMs;

    private Long tokensIn;

    private Long tokensOut;

    private Long costCents;

    /** 完整学生提问（含行号注入后的上下文由 numberedCode 承载，本字段是 user_message 原值） */
    private String promptText;

    /** 完整 AI 回复 */
    private String replyText;

    /** 原值：0 pass / 1 block / 2 rewrite */
    private Integer guardAction;

    /** 派生值：pass / block / rewrite */
    private String guardCode;

    private String guardReason;

    /** 护栏改写后的替代文本；未改写为 null */
    private String guardReplacementText;

    /** 关联判题链（逐测试点） */
    private List<ChainNode> chain = new ArrayList<>();

    /** 关联沙箱实例展示号，如 sbx-0001；无则 null */
    private String sandboxInstanceId;

    private String traceId;

    /** 调用者信息 */
    @Data
    public static class User implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long id;

        private String name;

        /** 学号/工号（sys_user.username） */
        private String stuNo;
    }

    /** 判题链节点 */
    @Data
    public static class ChainNode implements Serializable {

        private static final long serialVersionUID = 1L;

        private Integer seqNo;

        /** 测试点类型：sample / normal / stress */
        private String kind;

        /** 该测试点判定：AC / WA / TLE / MLE / RE */
        private String status;
    }
}
