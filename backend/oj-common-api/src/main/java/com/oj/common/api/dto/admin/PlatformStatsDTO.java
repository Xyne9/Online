package com.oj.common.api.dto.admin;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 全站大盘统计（契约 02 §7.6.1 {@code GET /api/admin/stats} 的 {@code data}）。
 *
 * <p><b>口径说明</b>（每一项都能在 DB 里找到出处，不造数）：</p>
 * <ul>
 *   <li>{@code users}：{@code sys_user}（total / students / teachers / admins），
 *       {@code activeToday} 取 {@code last_login_at >= 今日 00:00}；</li>
 *   <li>{@code problems}：{@code prob_question}（state 0 草稿 / 1 已发布 / 2 归档）；</li>
 *   <li>{@code submissions}：{@code judge_submission}，{@code acRate} = status=3(AC) 占比 ×100 保留一位；</li>
 *   <li>{@code ai}：{@code ai_chat_record}，{@code fallbackRate} = is_fallback=1 占比 ×100 保留一位；</li>
 *   <li>{@code teaching}：{@code teach_class} / {@code teach_assignment}（mode_type=3 即考试）/
 *       {@code teach_plagiarism_report.audit_status=0}（待复核）；</li>
 *   <li>{@code storage}：{@code prob_test_case.input_size + output_size} 之和（测试数据字节），
 *       {@code codeBytes} = {@code judge_submission.code} 字节数之和。</li>
 * </ul>
 *
 * <p>{@code range} 默认 7 天，只影响 {@code trend[]} 的区间，不影响 today 系列。</p>
 *
 * @author oj-cloud
 */
@Data
public class PlatformStatsDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Users users = new Users();

    private Problems problems = new Problems();

    private Submissions submissions = new Submissions();

    private Ai ai = new Ai();

    private Teaching teaching = new Teaching();

    private Storage storage = new Storage();

    /** 近 N 天趋势（按日期升序） */
    private List<TrendPoint> trend = new ArrayList<>();

    /** 用户维度 */
    @Data
    public static class Users implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long total = 0L;

        private Long students = 0L;

        private Long teachers = 0L;

        private Long admins = 0L;

        private Long activeToday = 0L;
    }

    /** 题目维度 */
    @Data
    public static class Problems implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long total = 0L;

        private Long published = 0L;

        private Long draft = 0L;

        private Long archived = 0L;
    }

    /** 提交维度 */
    @Data
    public static class Submissions implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long total = 0L;

        private Long today = 0L;

        /** AC 率（0~100，一位小数） */
        private Double acRate = 0.0;
    }

    /** AI 维度 */
    @Data
    public static class Ai implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long callsToday = 0L;

        /** 今日 tokens_in + tokens_out */
        private Long tokensToday = 0L;

        private Long costCentsToday = 0L;

        /** 降级率（0~100，一位小数） */
        private Double fallbackRate = 0.0;
    }

    /** 教学维度 */
    @Data
    public static class Teaching implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long classes = 0L;

        private Long assignments = 0L;

        /** mode_type=3 的作业即考试 */
        private Long exams = 0L;

        /** 待复核查重报告数（audit_status=0） */
        private Long pendingPlagiarism = 0L;
    }

    /** 存储维度 */
    @Data
    public static class Storage implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 测试数据字节数（input_size + output_size 之和） */
        private Long testCaseBytes = 0L;

        /** 提交代码字节数 */
        private Long codeBytes = 0L;
    }

    /** 趋势点 */
    @Data
    public static class TrendPoint implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 日期 yyyy-MM-dd */
        private String date;

        /** 当日提交数 */
        private Long submissions = 0L;

        /** 当日 AI 调用数 */
        private Long aiCalls = 0L;
    }
}
