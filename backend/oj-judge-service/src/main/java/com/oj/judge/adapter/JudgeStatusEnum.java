package com.oj.judge.adapter;

/**
 * 判题状态字典（设计文档 §2.1(1) / 基线决策 §5.1，<b>一张表派生三者</b>）。
 *
 * <p><b>为什么必须集中在这里：</b>契约要求同一个 {@code status} 同时下发
 * {@code statusLabel}（大写缩写，直接展示）与 {@code statusCode}（小写，前端拼
 * {@code class="tag {statusCode}"}）。如果 service 里写一套映射、controller 里再写一套，
 * 早晚出现「列表页显示 WA、详情页显示 wa、工作台显示 pending」的分裂。
 * 因此全站唯一映射表就在本枚举，任何地方都调 {@link #of(int)} 取。</p>
 *
 * <p><b>int 是真值：</b>DB 存 {@code judge_submission.status}（0~9），接口层下发 int，
 * label 与 code 都是派生物，不落库、不可反推。</p>
 *
 * <p><b>与 go-judge 的关系：</b>go-judge 的 {@code status} 是字符串
 * （{@code Accepted} / {@code Time Limit Exceeded} / {@code Memory Limit Exceeded} /
 * {@code Nonzero Exit Status} / {@code Signalled} / {@code Output Limit Exceeded} / ...），
 * 到本枚举的映射只发生在 {@link JudgeAdapter#mapRunStatus(String)}，不在别处。</p>
 *
 * @author oj-cloud
 */
public enum JudgeStatusEnum {

    /** 0 · 排队中（已落库，等待 MQ 消费） */
    QUEUED(0, "排队中", "pending", false),

    /** 1 · 编译中 */
    COMPILING(1, "编译中", "pending", false),

    /** 2 · 评测中（已拿到编译产物，正在跑测试点） */
    JUDGING(2, "评测中", "pending", false),

    /** 3 · Accepted，全部测试点通过 */
    AC(3, "AC", "ac", true),

    /** 4 · Partially Accepted，部分通过（OI 计分下非满分） */
    PAC(4, "PAC", "pac", true),

    /** 5 · Wrong Answer，输出与期望不一致 */
    WA(5, "WA", "wa", true),

    /** 6 · Time Limit Exceeded */
    TLE(6, "TLE", "tle", true),

    /** 7 · Memory Limit Exceeded */
    MLE(7, "MLE", "mle", true),

    /** 8 · Runtime Error（非零退出 / 被信号杀死） */
    RE(8, "RE", "re", true),

    /** 9 · Compile Error */
    CE(9, "CE", "ce", true);

    /** DB 真值，0~9 */
    private final int code;

    /** 展示文案（契约 {@code statusLabel}） */
    private final String label;

    /** 前端 CSS class（契约 {@code statusCode}） */
    private final String statusCode;

    /** 是否终态（终态后不允许再回退到 0/1/2） */
    private final boolean terminal;

    JudgeStatusEnum(int code, String label, String statusCode, boolean terminal) {
        this.code = code;
        this.label = label;
        this.statusCode = statusCode;
        this.terminal = terminal;
    }

    /** DB 真值 0~9 */
    public int getCode() {
        return code;
    }

    /** 展示文案，如 {@code AC} / {@code WA} / {@code 排队中} */
    public String getLabel() {
        return label;
    }

    /** 前端 CSS class，如 {@code ac} / {@code wa} / {@code pending} */
    public String getStatusCode() {
        return statusCode;
    }

    /** 是否终态 */
    public boolean isTerminal() {
        return terminal;
    }

    /**
     * 按 int 真值反查枚举。
     *
     * <p>越界值一律兜底为 {@link #QUEUED}（而不是抛异常）：
     * 状态列是 {@code TINYINT NOT NULL DEFAULT 0}，理论上不会出现越界值；
     * 真出现了也应该「显示成排队中」而不是让整个列表接口 500。</p>
     *
     * @param code 0~9
     * @return 命中的枚举，绝不返回 null
     */
    public static JudgeStatusEnum of(Integer code) {
        if (code == null) {
            return QUEUED;
        }
        for (JudgeStatusEnum value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return QUEUED;
    }

    /**
     * 是否终态（null 视为非终态）。
     *
     * @param code 0~9
     * @return true = 已出结果，轮询可以停
     */
    public static boolean isTerminalStatus(Integer code) {
        return of(code).terminal;
    }
}
