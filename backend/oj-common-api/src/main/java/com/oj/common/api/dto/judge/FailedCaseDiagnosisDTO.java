package com.oj.common.api.dto.judge;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 最近一次失败用例诊断（`JudgeInnerFeignClient#getLatestFailedDiagnosis` 的响应体）。
 *
 * <p>AI 四阶段启发需要知道「上次挂在哪」，否则只能给泛泛的鼓励式提示。
 * 该 DTO 刻意只带<b>第一个失败测试点</b>的诊断，避免把全部隐藏用例灌给模型造成泄题风险。</p>
 *
 * @author oj-cloud
 */
@Data
public class FailedCaseDiagnosisDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 提交 ID */
    private Long submissionId;

    /** 提交展示号，如 #S1024 */
    private String displayNo;

    /** 判题状态码（基线 §5.1：4=WA，5=RE，6=TLE，7=MLE 等） */
    private Integer status;

    /** 状态中文名，如「答案错误」 */
    private String statusLabel;

    /** 失败测试点序号（从 1 开始） */
    private Integer failedSeqNo;

    /** 失败测试点类型：sample / hidden */
    private String failedKind;

    /** 期望输出（仅当 failedKind=sample 时下发，hidden 一律置空） */
    private String expectedOutput;

    /** 实际输出（仅当 failedKind=sample 时下发） */
    private String actualOutput;

    /** 编译器/运行器原始报错摘要（已截断，防止超长） */
    private String errorSnippet;

    /** 耗时（毫秒） */
    private Long timeUsedMs;

    /** 内存占用（KB） */
    private Long memoryUsedKb;

    /** 首个失败点的常见错因标签（如 off_by_one / 未初始化 / 递归无出口） */
    private List<String> causeTags;

    /** 该题历史失败次数（用于 AI 判断是否「反复踩同一个坑」） */
    private Integer historyFailCount;

    /** 判题落库的脱敏特征 JSON；不含完整隐藏输入输出。 */
    private String firstFailedSummary;
}
