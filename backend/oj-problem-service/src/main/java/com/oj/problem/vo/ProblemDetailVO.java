package com.oj.problem.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 题目详情（作答视角，契约 02 §3.3 的 {@code data}）。
 *
 * <p><b>安全红线（三条，改这个类之前必须确认）：</b></p>
 * <ol>
 *   <li><b>绝不包含 {@code officialSolution}</b>——作答视角下发官方题解等于直接给答案。
 *       本类刻意<b>没有</b>这个字段，编辑态走 {@code /inner/problem/edit} 的
 *       {@code ProblemEditDTO}，两条链路物理隔离。</li>
 *   <li>{@link #chainPreview} 只回 {@code seqNo/kind/score} 三元组，
 *       <b>不回输入输出</b>。隐藏测试点的正文一旦下发，题目就废了。</li>
 *   <li>{@link #samples} 只来自 {@code sample_cases} 或 {@code is_public=1} 的测试点，
 *       不能把 normal/edge/stress 的正文当样例带出来。</li>
 * </ol>
 *
 * @author oj-cloud
 */
@Data
public class ProblemDetailVO {

    /** 题目 ID */
    private Long id;

    /** 展示号，如 "#1024" */
    private String displayNo;

    /** 标题 */
    private String title;

    /** 难度 1~5 */
    private Integer difficulty;

    /** 难度中文标签 */
    private String difficultyLabel;

    /** 标签数组 */
    private List<String> tags;

    /**
     * 题目状态 0 草稿 / 1 已发布 / 2 已归档。
     *
     * <p>契约 §3.3 的示例没列这个字段，但教师预览草稿/归档题时（学生根本到不了这里，
     * 会先被 403 拦掉）前端需要一个信号来渲染「草稿」角标，否则教师看到的就是一篇
     * 和已发布题一模一样的详情，容易误判。与列表项的 {@code state} 同源同义。</p>
     */
    private Integer state;

    /** 题面 Markdown（原文，含全部小节） */
    private String statement;

    /** 输入格式说明（从题面「## 输入格式」小节抽出的纯文本，抽不到给空串） */
    private String inputSpec;

    /** 输出格式说明（从题面「## 输出格式」小节抽出，抽不到给空串） */
    private String outputSpec;

    /** 公开样例 */
    private List<SampleVO> samples;

    /** 代码模板：key = cpp/java/python */
    private Map<String, String> codeTemplates;

    /** 时间限制（毫秒，C/C++ 基准） */
    private Integer timeLimitMs;

    /** 内存限制（MB，C/C++ 基准） */
    private Integer memoryLimitMb;

    /** 语言字典与时空倍率（前端语言下拉直接用，禁止自己写死） */
    private List<LanguageOptionVO> languageMultipliers;

    /** 测试点总数 */
    private Integer testCaseCount;

    /** 公开测试点个数 */
    private Integer publicCaseCount;

    /** 判分模式，固定 "OI" */
    private String scoringMode;

    /** 计分说明文案 */
    private String scoreRule;

    /** 我的记录（未登录或未提交时 submitted=false，其余字段为 null） */
    private MyRecordVO myRecord;

    /** 判分链骨架（只含 seqNo/kind/score，按 seq_no 升序） */
    private List<ChainPreviewVO> chainPreview;

    /** 公开样例：输入 → 输出 */
    @Data
    public static class SampleVO {

        /** 样例输入 */
        private String input;

        /** 样例输出 */
        private String output;
    }

    /** 语言选项：存储键 / 展示标签 / 时空限制倍率 */
    @Data
    public static class LanguageOptionVO {

        /** 语言键：cpp / java / python */
        private String language;

        /** 展示标签，如 "C++ 17"（注意有空格，基线 §5.3 的硬约定） */
        private String label;

        /** 时限与内存倍率：cpp=1，java/python=2 */
        private Integer multiplier;
    }

    /** 我的记录（来自 judge_submission 聚合） */
    @Data
    public static class MyRecordVO {

        /** 是否提交过 */
        private Boolean submitted;

        /** 历史最高分 0~100；未提交为 null */
        private Integer bestScore;

        /** 最高分那条提交的 ID；未提交为 null */
        private Long bestSubmissionId;

        /** 提交次数；未提交为 0 */
        private Integer attempts;

        /** 最近一次判题状态 0~9；未提交为 null */
        private Integer lastStatus;

        /** 最近一次判题状态 CSS class；未提交为 null */
        private String lastStatusCode;
    }

    /** 判分链骨架元素（<b>只有</b>这三列，绝不加 input/output） */
    @Data
    public static class ChainPreviewVO {

        /** 测试点序号，从 1 开始 */
        private Integer seqNo;

        /** 类型：sample/normal/edge/stress */
        private String kind;

        /** 该点分值 */
        private Integer score;
    }
}
