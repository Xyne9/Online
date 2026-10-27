package com.oj.common.api.dto.teaching;

import lombok.Data;

import java.io.Serializable;

/**
 * 考试运行时快照（`TeachingAggregateFeignClient#getExamRuntime` 的响应体）。
 *
 * <p><b>为什么要有它：</b>剩余时间必须由服务端权威计算并下发，前端本地倒计时只作显示。
 * 一旦前端被篡改时钟或切到后台，交卷仍以服务端的 {@link #endAt} 为准，避免「超时后仍能交卷」。</p>
 *
 * @author oj-cloud
 */
@Data
public class ExamRuntimeDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 考试 ID */
    private Long examId;

    /** 考试名称 */
    private String examName;

    /** 班级 ID */
    private Long classId;

    /** 活动状态：0=草稿，1=未开始，2=进行中，3=已结束。 */
    private Integer state;

    /** 开始时间（毫秒时间戳） */
    private Long startAt;

    /** 结束时间（毫秒时间戳，含延长后的最新值） */
    private Long endAt;

    /** 服务端计算的剩余秒数（≤0 表示已结束） */
    private Long remainingSeconds;

    /** 是否已延长过 */
    private Boolean extended;

    /** 累计延长分钟数 */
    private Integer extendedMinutes;

    /** 允许交卷的考生 ID 列表（空表示全班级） */
    private java.util.List<Long> candidateIds;

    /** 已交卷人数 */
    private Integer submittedCount;

    /** 应考人数 */
    private Integer candidateCount;

    /** 监考事件最新 seq（SSE Last-Event-ID 断线重放用） */
    private Long latestEventSeq;

    /** 服务端当前时间（毫秒时间戳，供前端校准时钟偏移） */
    private Long serverNow;
}
