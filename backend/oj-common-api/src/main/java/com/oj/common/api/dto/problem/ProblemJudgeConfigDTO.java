package com.oj.common.api.dto.problem;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 判题所需的最小题目配置（`ProblemInnerFeignClient#getJudgeConfig` 的响应体）。
 *
 * <p>判题服务拿到它就能独立完成「编译 + 跑测试点 + 算分」，不必再回查题库服务的全部题面。</p>
 *
 * @author oj-cloud
 */
@Data
public class ProblemJudgeConfigDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 题目 ID */
    private Long problemId;

    /** 展示号，如 #1024（§6.3 ID 策略：主键不出现在 UI） */
    private String displayNo;

    /** 题目标题 */
    private String title;

    /** 单测试点时间限制（毫秒） */
    private Integer timeLimitMs;

    /** 内存限制（MB） */
    private Integer memoryLimitMb;

    /** 支持的编程语言，如 cpp / java / python */
    private List<String> languages;

    /** 判题模式：OI（按测试点累计得分） / ACM（全对才得分） */
    private String scoringMode;

    /** 是否已发布（state=1 才可被判题服务受理） */
    private Boolean published;

    /** 测试点摘要（判题只需 seqNo/kind/score，不含输入输出内容） */
    private List<TestCaseBriefDTO> testCaseBriefs;

    /**
     * 测试点摘要：判题服务算分用，<b>不包含</b> input/output 正文
     * （正文走 {@code ProblemTestCaseFeignClient#listByProblem} 单独拉取，避免单次响应过大）。
     */
    @Data
    public static class TestCaseBriefDTO implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 测试点 ID */
        private Long id;

        /** 序号，从 1 开始，决定判题执行顺序 */
        private Integer seqNo;

        /** 类型：sample（样例）/ hidden（隐藏） */
        private String kind;

        /** 该测试点分值 */
        private Integer score;
    }
}
