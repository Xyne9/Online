package com.oj.problem.constant;

import java.util.List;

/**
 * 题库域的三张字典（设计文档 04 §2.1、基线决策 §5）。
 *
 * <p><b>为什么集中在这里：</b>难度文案、语言标签与倍率、判题状态码这三张表
 * 在列表、详情、内部接口里都要用。散写成字面量的话，只要有一处写成
 * 「C++17」（少一个空格）或「较难」写成「困难」，前端就会出现
 * 同一道题在两个页面显示不同难度的诡异现象（基线 §5.3 记录过这个真实冲突）。</p>
 *
 * @author oj-cloud
 */
public final class ProblemDict {

    /** 题目状态：草稿（学生不可见） */
    public static final int STATE_DRAFT = 0;

    /** 题目状态：已发布（学生唯一可见态） */
    public static final int STATE_PUBLISHED = 1;

    /** 题目状态：已归档 */
    public static final int STATE_ARCHIVED = 2;

    /** 判题状态：AC（唯一算「已解决」的终态） */
    public static final int JUDGE_STATUS_AC = 3;

    /** 判分模式固定 OI（PRD L63：按测试点累计得分） */
    public static final String SCORING_MODE_OI = "OI";

    /** 计分说明文案（契约 §3.3 原文，前端直接展示） */
    public static final String SCORE_RULE = "按测试点计分，取历史最高";

    /** 难度 5 档文案，索引 0 占位（契约 §2.1(2)） */
    private static final String[] DIFFICULTY_LABELS = {"", "入门", "简单", "中等", "较难", "困难"};

    /** 判题状态 0~9 → 前端 CSS class（契约 §2.1(1)） */
    private static final String[] STATUS_CODES = {
            "pending", "pending", "pending", "ac", "pac", "wa", "tle", "mle", "re", "ce"
    };

    /** 支持的语言（顺序即前端展示顺序，契约 §2.1(3)） */
    public static final List<LanguageSpec> LANGUAGES = List.of(
            new LanguageSpec("cpp", "C++ 17", 1),
            new LanguageSpec("java", "Java 17", 2),
            new LanguageSpec("python", "Python 3", 2)
    );

    private ProblemDict() {
    }

    /**
     * 难度值 → 中文标签。
     *
     * @param difficulty 1~5；越界按 1（入门）兜底，绝不返回 null——
     *                   前端 {@code x-text} 渲染 null 会显示 "null"
     */
    public static String difficultyLabel(Integer difficulty) {
        if (difficulty == null || difficulty < 1 || difficulty > 5) {
            return DIFFICULTY_LABELS[1];
        }
        return DIFFICULTY_LABELS[difficulty];
    }

    /**
     * 判题状态 int → statusCode（小写 CSS class）。
     *
     * @param status 0~9；null 或越界返回 null（未提交不是状态，由调用方判空）
     */
    public static String statusCode(Integer status) {
        if (status == null || status < 0 || status > 9) {
            return null;
        }
        return STATUS_CODES[status];
    }

    /** 语言字典条目：存储键 / 展示标签 / 时空限制倍率 */
    public record LanguageSpec(String language, String label, int multiplier) {
    }
}
