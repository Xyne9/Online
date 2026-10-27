package com.oj.judge.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.common.core.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 两阶段判题执行器：把「一次完整判题」拆成<b>编译</b>与<b>跑测试点</b>两次 {@code /run}。
 *
 * <p>协议细节全部在 {@link JudgeAdapter}（纯函数、可被 main 直接验证）；
 * 本类只负责「按顺序调两次、组装中间产物、读磁盘期望输出」。</p>
 *
 * <h2>为什么必须两次请求</h2>
 * <p>实测（scripts/_probe_gojudge5.py 结论 1）：一次 {@code /run} 里的多个 {@code cmd}
 * <b>各自独立环境、文件系统不共享</b>。把「编译 cmd + 运行 cmd」放进同一个请求，
 * 运行 cmd 看不到编译产物。因此只能：
 * 编译请求取回 base64 产物 → 下一个请求的每个 cmd 各自 copyIn 同一份产物。</p>
 *
 * <h2>为什么 stdin 直挂 /data/testcases</h2>
 * <p>{@code /w} 是 128 MB tmpfs，而本机最大输入 205 MB。实测 {@code copyIn} 会
 * {@code write /w/big.in: no space left on device}；而 {@code files[0].src} 直接指向
 * go-judge 容器内可见的挂载文件（docker-compose 把 {@code ./data/testcases} 以 {@code :ro}
 * 挂到 {@code /data/testcases}），205 MB 耗时 0.0s。见 {@code oj.judge.use-container-src}。</p>
 *
 * @author oj-cloud
 */
@Component
public class JudgeExecutor {

    private static final Logger log = LoggerFactory.getLogger(JudgeExecutor.class);

    private final GoJudgeClient goJudgeClient;
    private final ObjectMapper objectMapper;

    /** 宿主机测试数据根目录（Java 读 .out 比对） */
    private final Path testcaseRoot;

    /** go-judge 容器内测试数据根目录（作为 stdin 的 src） */
    private final String testcaseContainerRoot;

    /** stdin 是否直挂容器内挂载文件 */
    private final boolean useContainerSrc;

    /** stdout 上限（字节） */
    private final int outputLimitBytes;

    /** 编译产物 base64 超过该值则退化为「每 cmd 独立编译」 */
    private final int maxArtifactBytes;

    /** 单次 /run 请求最多带几个测试点 cmd */
    private final int maxParallelCases;

    public JudgeExecutor(GoJudgeClient goJudgeClient,
                         ObjectMapper objectMapper,
                         @Value("${oj.judge.testcase-root:D:/毕设/agent oj/data/testcases}") String testcaseRoot,
                         @Value("${oj.judge.testcase-container-root:/data/testcases}") String testcaseContainerRoot,
                         @Value("${oj.judge.use-container-src:true}") boolean useContainerSrc,
                         @Value("${oj.judge.output-limit-bytes:10485760}") int outputLimitBytes,
                         @Value("${oj.judge.max-artifact-bytes:8388608}") int maxArtifactBytes,
                         @Value("${oj.judge.max-parallel-cases:4}") int maxParallelCases) {
        this.goJudgeClient = goJudgeClient;
        this.objectMapper = objectMapper;
        this.testcaseRoot = Paths.get(testcaseRoot);
        this.testcaseContainerRoot = testcaseContainerRoot;
        this.useContainerSrc = useContainerSrc;
        this.outputLimitBytes = outputLimitBytes;
        this.maxArtifactBytes = maxArtifactBytes;
        this.maxParallelCases = Math.max(1, maxParallelCases);
    }

    /**
     * 执行一次完整判题（编译 + N 个测试点）。
     *
     * @param problemId  题目 ID（定位测试数据目录）
     * @param language   语言
     * @param sourceCode 源码
     * @param cases      测试点清单（有序，seqNo 升序）
     * @param timeLimitMs      题目原始时限（毫秒，<b>未</b>乘倍率）
     * @param memoryLimitMb    题目原始内存（MB，<b>未</b>乘倍率）
     * @return 判题结果
     */
    public ExecutionResult execute(Long problemId, String language, String sourceCode,
                                   List<CaseSpec> cases, int timeLimitMs, int memoryLimitMb) {
        return executeWithCaseSpecs(problemId, language, sourceCode, cases, timeLimitMs, memoryLimitMb);
    }

    /**
     * 按测试点清单执行（{@link #execute} 的显式别名，供 service 层语义化调用）。
     *
     * @param problemId        题目 ID
     * @param language         语言
     * @param sourceCode       源码
     * @param cases            测试点清单
     * @param timeLimitMs      题目原始时限（毫秒）
     * @param memoryLimitMb    题目原始内存（MB）
     * @return 判题结果
     */
    public ExecutionResult executeWithCaseSpecs(Long problemId, String language, String sourceCode,
                                                List<CaseSpec> cases, int timeLimitMs, int memoryLimitMb) {
        ExecutionResult result = new ExecutionResult();
        result.setLanguage(JudgeAdapter.normalizeLanguage(language));

        // ── 阶段 0：输入校验（不浪费一次沙箱往返）──
        if (cases == null || cases.isEmpty()) {
            throw BusinessException.badRequest("该题目没有可用的测试点，无法判题");
        }
        List<String> missing = new ArrayList<>();
        for (CaseSpec spec : cases) {
            Path in = JudgeAdapter.hostCasePath(testcaseRoot, problemId, spec.getSeqNo(), "in");
            Path out = JudgeAdapter.hostCasePath(testcaseRoot, problemId, spec.getSeqNo(), "out");
            if (!Files.isReadable(in)) {
                missing.add(in.toString());
            }
            if (!Files.isReadable(out)) {
                missing.add(out.toString());
            }
        }
        if (!missing.isEmpty()) {
            // 这是**数据缺失**，不是用户代码错。必须 502 上报，绝不能伪造 AC/WA。
            log.error("题目 {} 测试数据缺失：{}", problemId, missing);
            throw BusinessException.badGateway("测试数据缺失，无法判题：" + String.join("、", missing));
        }

        // ── 阶段 1：编译 ──
        String artifactBase64 = compileOrFail(result, language, sourceCode);
        if (artifactBase64 == null) {
            // 编译失败：result 已被填成 CE
            return result;
        }

        // ── 阶段 2：跑测试点 ──
        List<JudgeAdapter.CaseInput> inputs = new ArrayList<>(cases.size());
        for (CaseSpec spec : cases) {
            if (useContainerSrc) {
                inputs.add(JudgeAdapter.CaseInput.ofContainerPath(spec.getSeqNo(),
                        JudgeAdapter.containerCasePath(testcaseContainerRoot, problemId, spec.getSeqNo())));
            } else {
                Path in = JudgeAdapter.hostCasePath(testcaseRoot, problemId, spec.getSeqNo(), "in");
                inputs.add(JudgeAdapter.CaseInput.ofContent(spec.getSeqNo(),
                        JudgeAdapter.containerCasePath(testcaseContainerRoot, problemId, spec.getSeqNo()),
                        JudgeAdapter.readCaseFile(in)));
            }
        }
        List<JudgeAdapter.RunResult> runResults = runAll(result, language, sourceCode, artifactBase64,
                inputs, timeLimitMs, memoryLimitMb);

        // ── 阶段 3：逐点比对（读磁盘 .out，绝不读 input_text 摘要）──
        List<CaseOutcome> outcomes = new ArrayList<>(cases.size());
        for (int i = 0; i < cases.size(); i++) {
            CaseSpec spec = cases.get(i);
            JudgeAdapter.RunResult run = runResults.get(i);
            if (run.isSandboxFault()) {
                throw BusinessException.badGateway("判题沙箱运行阶段故障（seqNo=" + spec.getSeqNo() + "）："
                        + safe(run.getError()));
            }
            String expected = JudgeAdapter.readCaseFile(
                    JudgeAdapter.hostCasePath(testcaseRoot, problemId, spec.getSeqNo(), "out"));
            outcomes.add(buildOutcome(spec, run, expected));
        }
        result.setCases(outcomes);
        summarize(result, outcomes, cases);
        return result;
    }

    /**
     * 用<b>内联输入</b>执行一次（self-test / run-sample 的 {@code mode=custom}）。
     *
     * <p>与 {@link #executeWithCaseSpecs} 的区别：不读磁盘、不比对期望输出、
     * 不参与 OI 计分。只有一个「虚拟测试点」（seqNo=1，满分 0），
     * 状态只区分 AC / TLE / MLE / RE / CE。</p>
     *
     * @param problemId      题目 ID（仅日志用）
     * @param language       语言
     * @param sourceCode     源码
     * @param stdin          自定义输入
     * @param timeLimitMs    题目原始时限（毫秒）
     * @param memoryLimitMb  题目原始内存（MB）
     * @return 判题结果（cases 里只有一项，expected 为 null）
     */
    public ExecutionResult executeWithInlineInput(Long problemId, String language, String sourceCode,
                                                  String stdin, int timeLimitMs, int memoryLimitMb) {
        ExecutionResult result = new ExecutionResult();
        result.setLanguage(JudgeAdapter.normalizeLanguage(language));

        String artifactBase64 = compileOrFail(result, language, sourceCode);
        if (artifactBase64 == null) {
            return result;
        }

        List<JudgeAdapter.CaseInput> inputs = List.of(
                JudgeAdapter.CaseInput.ofContent(1, "", stdin == null ? "" : stdin));
        List<JudgeAdapter.RunResult> runResults = runAll(result, language, sourceCode, artifactBase64,
                inputs, timeLimitMs, memoryLimitMb);

        JudgeAdapter.RunResult run = runResults.get(0);
        if (run.isSandboxFault()) {
            throw BusinessException.badGateway("判题沙箱运行故障：" + safe(run.getError()));
        }
        CaseOutcome outcome = new CaseOutcome();
        outcome.setSeqNo(1);
        outcome.setCaseId(null);
        outcome.setKind("custom");
        outcome.setScore(0);
        // 内联输入没有期望输出：沙箱判什么就是什么（AC 表示正常跑完，不代表答案正确）
        outcome.setStatus(JudgeAdapter.mapRunStatus(run.getStatus()));
        outcome.setTimeMs(run.timeMs());
        outcome.setMemKb(run.memoryKb());
        outcome.setExpectedOutput(null);
        outcome.setActualOutput(run.getStdout());
        outcome.setStderr(run.getStderr());
        outcome.setGotScore(0);

        result.setCases(List.of(outcome));
        result.setStatus(outcome.getStatus());
        result.setOiScore(0);
        result.setPassCaseCount(outcome.getStatus() == JudgeStatusEnum.AC ? 1 : 0);
        result.setTotalCaseCount(1);
        result.setTimeUsedMs(outcome.getTimeMs());
        result.setMemoryUsedKb(outcome.getMemKb());
        log.debug("内联输入执行完成：problem={} lang={} status={}", problemId, language, outcome.getStatus());
        return result;
    }

    /**
     * 阶段 1：编译。成功返回 base64 产物；失败把 {@code result} 填成 CE 并返回 {@code null}。
     *
     * @param result     结果对象（失败时被就地填充）
     * @param language   语言
     * @param sourceCode 源码
     * @return base64 产物或 null
     */
    private String compileOrFail(ExecutionResult result, String language, String sourceCode) {
        long compileStart = System.currentTimeMillis();
        String compileRequest = JudgeAdapter.buildCompileRequest(objectMapper, language, sourceCode);
        String compileResponse = goJudgeClient.run(compileRequest);
        List<JudgeAdapter.RunResult> compileResults =
                JudgeAdapter.parseRunResults(objectMapper, compileResponse);
        result.setCompileCostMs(System.currentTimeMillis() - compileStart);

        if (compileResults.isEmpty()) {
            throw BusinessException.badGateway("判题沙箱未返回编译结果");
        }
        JudgeAdapter.RunResult compileResult = compileResults.get(0);
        if (compileResult.isSandboxFault()) {
            // 沙箱自身故障（File Error / Internal Error）：502，不要记成 CE
            throw BusinessException.badGateway("判题沙箱编译阶段故障：" + safe(compileResult.getError()));
        }
        boolean compileFailed = !JudgeAdapter.GJ_ACCEPTED.equals(compileResult.getStatus())
                || compileResult.getExitStatus() == null
                || compileResult.getExitStatus() != 0;
        if (compileFailed) {
            result.setCompileError(true);
            result.setErrorMsg(trim(safe(compileResult.getStderr()).isBlank()
                    ? safe(compileResult.getError())
                    : compileResult.getStderr(), 8000));
            result.setStatus(JudgeStatusEnum.CE);
            log.info("编译失败：lang={} exit={} err={}", language, compileResult.getExitStatus(),
                    abbreviate(result.getErrorMsg(), 200));
            return null;
        }
        String artifactBase64 = compileResult.getArtifactBase64();
        if (artifactBase64.isBlank()) {
            throw BusinessException.badGateway("判题沙箱未返回编译产物（art.b64 为空）");
        }
        return artifactBase64;
    }

    /**
     * 阶段 2：分批跑全部测试点。
     *
     * @param result         结果对象（记录运行耗时）
     * @param language       语言
     * @param sourceCode     源码（降级路径用）
     * @param artifactBase64 编译产物
     * @param inputs         测试点输入
     * @param timeLimitMs    题目原始时限
     * @param memoryLimitMb  题目原始内存
     * @return 每个测试点的沙箱结果（与 inputs 同序）
     */
    private List<JudgeAdapter.RunResult> runAll(ExecutionResult result, String language, String sourceCode,
                                                String artifactBase64, List<JudgeAdapter.CaseInput> inputs,
                                                int timeLimitMs, int memoryLimitMb) {
        int effectiveTimeMs = JudgeAdapter.effectiveTimeMs(timeLimitMs, language);
        int effectiveMemMb = JudgeAdapter.effectiveMemoryMb(memoryLimitMb, language);
        long cpuLimit = JudgeAdapter.toCpuLimitNanos(effectiveTimeMs);
        long clockLimit = JudgeAdapter.toClockLimitNanos(cpuLimit);
        long memoryLimit = JudgeAdapter.mbToBytes(effectiveMemMb);

        boolean degrade = artifactBase64.length() > maxArtifactBytes;
        if (degrade) {
            log.warn("编译产物 base64 长度 {} 超过阈值 {}，退化为每个 cmd 独立编译",
                    artifactBase64.length(), maxArtifactBytes);
        }

        long runStart = System.currentTimeMillis();
        List<JudgeAdapter.RunResult> runResults = new ArrayList<>(inputs.size());
        // 分批：go-judge 会并行执行同一请求里的所有 cmd，而每个 cmd 的 memoryLimit 都是真实占用，
        // 一次塞 10 个 512MB 的 cmd 会把宿主机打爆（16G 内存还要跑 5 个容器）。
        int batch = Math.max(1, batchSize());
        for (int from = 0; from < inputs.size(); from += batch) {
            int to = Math.min(from + batch, inputs.size());
            List<JudgeAdapter.CaseInput> slice = inputs.subList(from, to);
            String request = degrade
                    ? JudgeAdapter.buildInlineCompileRunRequest(objectMapper, language, sourceCode, slice,
                            cpuLimit, clockLimit, memoryLimit, outputLimitBytes, useContainerSrc)
                    : JudgeAdapter.buildRunRequest(objectMapper, language, artifactBase64, slice,
                            cpuLimit, clockLimit, memoryLimit, outputLimitBytes, useContainerSrc);
            String response = goJudgeClient.run(request);
            List<JudgeAdapter.RunResult> sliceResults = JudgeAdapter.parseRunResults(objectMapper, response);
            if (sliceResults.size() != slice.size()) {
                throw BusinessException.badGateway("判题沙箱返回的测试点结果数量不符：期望 "
                        + slice.size() + "，实际 " + sliceResults.size());
            }
            runResults.addAll(sliceResults);
        }
        result.setRunCostMs(System.currentTimeMillis() - runStart);
        return runResults;
    }

    /** 单个测试点的判定结果组装 */
    private CaseOutcome buildOutcome(CaseSpec spec, JudgeAdapter.RunResult run, String expected) {
        String actual = run.getStdout();
        JudgeStatusEnum caseStatus = JudgeAdapter.judgeCase(run.getStatus(), expected, actual);
        CaseOutcome outcome = new CaseOutcome();
        outcome.setSeqNo(spec.getSeqNo());
        outcome.setCaseId(spec.getCaseId());
        outcome.setKind(spec.getKind());
        outcome.setScore(spec.getScore());
        outcome.setStatus(caseStatus);
        outcome.setTimeMs(run.timeMs());
        outcome.setMemKb(run.memoryKb());
        outcome.setExpectedOutput(expected);
        outcome.setActualOutput(actual);
        outcome.setStderr(run.getStderr());
        outcome.setGotScore(caseStatus == JudgeStatusEnum.AC ? spec.getScore() : 0);
        return outcome;
    }

    /** 阶段 4：OI 计分与整体状态汇总 */
    private void summarize(ExecutionResult result, List<CaseOutcome> outcomes, List<CaseSpec> cases) {
        List<JudgeStatusEnum> statuses = new ArrayList<>(outcomes.size());
        int oiScore = 0;
        int passCount = 0;
        int maxTimeMs = 0;
        int maxMemKb = 0;
        Integer firstFailedSeq = null;
        for (CaseOutcome outcome : outcomes) {
            statuses.add(outcome.getStatus());
            oiScore += outcome.getGotScore();
            if (outcome.getStatus() == JudgeStatusEnum.AC) {
                passCount++;
            } else if (firstFailedSeq == null) {
                firstFailedSeq = outcome.getSeqNo();
            }
            maxTimeMs = Math.max(maxTimeMs, outcome.getTimeMs());
            maxMemKb = Math.max(maxMemKb, outcome.getMemKb());
        }
        JudgeStatusEnum overall = JudgeAdapter.aggregateStatus(statuses);
        // OI 计分：有分但没满分 → PAC（部分通过），比整体状态更贴近契约 §2.1(1)
        if (overall == JudgeStatusEnum.AC && oiScore < totalScore(cases)) {
            overall = JudgeStatusEnum.PAC;
        } else if (overall != JudgeStatusEnum.AC && oiScore > 0) {
            overall = JudgeStatusEnum.PAC;
        }
        result.setStatus(overall);
        result.setOiScore(oiScore);
        result.setPassCaseCount(passCount);
        result.setTotalCaseCount(cases.size());
        result.setTimeUsedMs(maxTimeMs);
        result.setMemoryUsedKb(maxMemKb);
        result.setFirstFailedSeqNo(firstFailedSeq);
        if (firstFailedSeq != null) {
            for (CaseOutcome outcome : outcomes) {
                if (outcome.getSeqNo() == firstFailedSeq) {
                    result.setFirstFailedSummary(buildFailedSummary(outcome));
                    break;
                }
            }
        }
    }

    /** 单次 /run 请求最多带几个测试点 cmd */
    private int batchSize() {
        return maxParallelCases;
    }

    /** 测试点总满分（用于区分 AC 与 PAC） */
    private static int totalScore(List<CaseSpec> cases) {
        int total = 0;
        for (CaseSpec spec : cases) {
            total += spec.getScore() == null ? 0 : spec.getScore();
        }
        return total;
    }

    /**
     * 首个失败点的脱敏摘要（落 {@code judge_submission.first_failed_summary}）。
     *
     * <p>只带状态与截断后的输出片段，<b>不带完整隐藏用例</b>——它是给 AI 阶段③
     * 做错因诊断用的，完整输入输出会构成泄题。</p>
     */
    private static String buildFailedSummary(CaseOutcome outcome) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"status\":\"").append(outcome.getStatus().getLabel()).append('"');
        sb.append(",\"seqNo\":").append(outcome.getSeqNo());
        sb.append(",\"kind\":\"").append(safe(outcome.getKind())).append('"');
        sb.append(",\"expectedOut\":").append(jsonString(abbreviate(outcome.getExpectedOutput(), 200)));
        sb.append(",\"actualOut\":").append(jsonString(abbreviate(outcome.getActualOutput(), 200)));
        sb.append('}');
        return sb.toString();
    }

    /** 极简 JSON 字符串转义（只处理摘要里的必要字符，避免为它引入依赖） */
    private static String jsonString(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(text.length() + 8);
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private static String trim(String text, int max) {
        String value = safe(text);
        return value.length() <= max ? value : value.substring(0, max) + "\n...(已截断)";
    }

    private static String abbreviate(String text, int max) {
        String value = safe(text);
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    /** 宿主机测试数据根目录（诊断接口拼路径用） */
    public Path getTestcaseRoot() {
        return testcaseRoot;
    }

    // ==================================================================
    // 内部数据结构
    // ==================================================================

    /**
     * 一个测试点的元信息（来自 {@code prob_test_case} 摘要：不含正文）。
     */
    public static final class CaseSpec {

        /** 测试点主键 */
        private final Long caseId;
        /** 序号（从 1 开始，决定执行顺序） */
        private final int seqNo;
        /** 类型：sample / normal / edge / stress */
        private final String kind;
        /** 分值 */
        private final Integer score;

        public CaseSpec(Long caseId, int seqNo, String kind, Integer score) {
            this.caseId = caseId;
            this.seqNo = seqNo;
            this.kind = kind;
            this.score = score;
        }

        public Long getCaseId() {
            return caseId;
        }

        public int getSeqNo() {
            return seqNo;
        }

        public String getKind() {
            return kind;
        }

        public Integer getScore() {
            return score == null ? 0 : score;
        }
    }

    /** 单个测试点的判题结果（含期望/实际输出，供落库摘要与详情预览） */
    public static final class CaseOutcome {

        private Long caseId;
        private int seqNo;
        private String kind;
        private Integer score;
        private JudgeStatusEnum status;
        private int timeMs;
        private int memKb;
        private String expectedOutput;
        private String actualOutput;
        private String stderr;
        private int gotScore;

        public Long getCaseId() {
            return caseId;
        }

        public void setCaseId(Long caseId) {
            this.caseId = caseId;
        }

        public int getSeqNo() {
            return seqNo;
        }

        public void setSeqNo(int seqNo) {
            this.seqNo = seqNo;
        }

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public Integer getScore() {
            return score == null ? 0 : score;
        }

        public void setScore(Integer score) {
            this.score = score;
        }

        public JudgeStatusEnum getStatus() {
            return status;
        }

        public void setStatus(JudgeStatusEnum status) {
            this.status = status;
        }

        public int getTimeMs() {
            return timeMs;
        }

        public void setTimeMs(int timeMs) {
            this.timeMs = timeMs;
        }

        public int getMemKb() {
            return memKb;
        }

        public void setMemKb(int memKb) {
            this.memKb = memKb;
        }

        public String getExpectedOutput() {
            return expectedOutput;
        }

        public void setExpectedOutput(String expectedOutput) {
            this.expectedOutput = expectedOutput;
        }

        public String getActualOutput() {
            return actualOutput;
        }

        public void setActualOutput(String actualOutput) {
            this.actualOutput = actualOutput;
        }

        public String getStderr() {
            return stderr;
        }

        public void setStderr(String stderr) {
            this.stderr = stderr;
        }

        public int getGotScore() {
            return gotScore;
        }

        public void setGotScore(int gotScore) {
            this.gotScore = gotScore;
        }
    }

    /** 一次完整判题的结果 */
    public static final class ExecutionResult {

        private JudgeStatusEnum status = JudgeStatusEnum.QUEUED;
        private String language;
        private boolean compileError;
        private String errorMsg;
        private int oiScore;
        private int passCaseCount;
        private int totalCaseCount;
        private int timeUsedMs;
        private int memoryUsedKb;
        private Integer firstFailedSeqNo;
        private String firstFailedSummary;
        private List<CaseOutcome> cases = new ArrayList<>();
        private long compileCostMs;
        private long runCostMs;

        public JudgeStatusEnum getStatus() {
            return status;
        }

        public void setStatus(JudgeStatusEnum status) {
            this.status = status;
        }

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }

        public boolean isCompileError() {
            return compileError;
        }

        public void setCompileError(boolean compileError) {
            this.compileError = compileError;
        }

        public String getErrorMsg() {
            return errorMsg;
        }

        public void setErrorMsg(String errorMsg) {
            this.errorMsg = errorMsg;
        }

        public int getOiScore() {
            return oiScore;
        }

        public void setOiScore(int oiScore) {
            this.oiScore = oiScore;
        }

        public int getPassCaseCount() {
            return passCaseCount;
        }

        public void setPassCaseCount(int passCaseCount) {
            this.passCaseCount = passCaseCount;
        }

        public int getTotalCaseCount() {
            return totalCaseCount;
        }

        public void setTotalCaseCount(int totalCaseCount) {
            this.totalCaseCount = totalCaseCount;
        }

        public int getTimeUsedMs() {
            return timeUsedMs;
        }

        public void setTimeUsedMs(int timeUsedMs) {
            this.timeUsedMs = timeUsedMs;
        }

        public int getMemoryUsedKb() {
            return memoryUsedKb;
        }

        public void setMemoryUsedKb(int memoryUsedKb) {
            this.memoryUsedKb = memoryUsedKb;
        }

        public Integer getFirstFailedSeqNo() {
            return firstFailedSeqNo;
        }

        public void setFirstFailedSeqNo(Integer firstFailedSeqNo) {
            this.firstFailedSeqNo = firstFailedSeqNo;
        }

        public String getFirstFailedSummary() {
            return firstFailedSummary;
        }

        public void setFirstFailedSummary(String firstFailedSummary) {
            this.firstFailedSummary = firstFailedSummary;
        }

        public List<CaseOutcome> getCases() {
            return cases;
        }

        public void setCases(List<CaseOutcome> cases) {
            this.cases = cases;
        }

        public long getCompileCostMs() {
            return compileCostMs;
        }

        public void setCompileCostMs(long compileCostMs) {
            this.compileCostMs = compileCostMs;
        }

        public long getRunCostMs() {
            return runCostMs;
        }

        public void setRunCostMs(long runCostMs) {
            this.runCostMs = runCostMs;
        }

        /** 首个失败点的序号（无失败返回 null） */
        public Integer firstFailedSeqNo() {
            return firstFailedSeqNo;
        }
    }
}
