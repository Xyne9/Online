package com.oj.judge.service.impl;

import com.oj.common.api.dto.judge.FailedCaseDiagnosisDTO;
import com.oj.common.api.dto.judge.SubmissionCodeDTO;
import com.oj.common.core.exception.BusinessException;
import com.oj.judge.adapter.JudgeAdapter;
import com.oj.judge.adapter.JudgeExecutor;
import com.oj.judge.adapter.JudgeStatusEnum;
import com.oj.judge.dto.JudgeRequests;
import com.oj.judge.entity.JudgeSubmission;
import com.oj.judge.entity.ProbQuestionJudgeConfig;
import com.oj.judge.entity.ProbTestCaseMeta;
import com.oj.judge.mapper.JudgeSubmissionMapper;
import com.oj.judge.mapper.ProbQuestionMapper;
import com.oj.judge.mapper.ProbTestCaseMapper;
import com.oj.judge.service.JudgeService;
import com.oj.judge.service.support.JudgeRedisSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 判题服务实现（契约 §4 的 7 个端点 + 2 个内部端点 + MQ 消费入口）。
 *
 * <h2>一、跨服务直连的说明（本期妥协）</h2>
 * <p>本类<b>直接读写 {@code oj_cloud} 库</b>的 {@code prob_question} / {@code prob_test_case}
 * （只读）与 {@code judge_submission}（读写），而没有走
 * {@code ProblemInnerFeignClient}。原因与边界：</p>
 * <ul>
 *   <li>判题在热路径上，每次提交都要拿「时限 / 内存 / 测试点摘要」，
 *       多一跳 Feign 就多一个 504 风险点；</li>
 *   <li>题库服务本期与判题服务<b>同库同实例</b>，直连不引入一致性风险；</li>
 *   <li>代价是耦合了题库的表结构。<b>题库服务一旦独立分库，必须改成 Feign</b>：
 *       把 {@link ProbQuestionMapper#selectJudgeConfig} 与
 *       {@link ProbTestCaseMapper#selectMetaByProblem} 换成
 *       {@code ProblemInnerFeignClient#getJudgeConfig} / {@code ProblemTestCaseFeignClient#listByProblem}，
 *       {@code prob_question.pass_count/submit_count} 的两条 UPDATE 也要改成 Feign 调用。
 *       本类所有直连点都已单独标 {@code 【跨服务直连·本期妥协】} 注释。</li>
 * </ul>
 *
 * <h2>二、异步判题的状态机</h2>
 * <pre>
 * submit():  INSERT status=0(QUEUED) → 发 MQ → 立刻返回 {submissionId, status:0}
 * consumer:  UPDATE status=1(COMPILING) → 编译 → UPDATE status=2(JUDGING) → 跑点 → 落终态
 * 轮询:      GET /status/{id} 直接读库，状态是终态时前端停止轮询
 * </pre>
 * <p><b>为什么先落库再发 MQ：</b>反过来的话，MQ 消费者可能比 INSERT 先执行
 * （本地 RabbitMQ 延迟只有几毫秒），查不到记录直接抛异常、消息被丢弃。</p>
 *
 * <h2>三、测试数据只从磁盘读</h2>
 * <p>{@code prob_test_case.input_text/output_text} 是 ≤4 KB 摘要（day3 §2.1），
 * 拿它比对 = 100% 误判。比对一律走
 * {@code {oj.judge.testcase-root}/{problemId}/{seqNo}.out}，由
 * {@link JudgeExecutor} 负责读盘，沙箱侧 stdin 直接挂载
 * {@code /data/testcases}（见该类注释）。</p>
 *
 * @author oj-cloud
 */
@Service
public class JudgeServiceImpl implements JudgeService {

    private static final Logger log = LoggerFactory.getLogger(JudgeServiceImpl.class);

    /** 提交状态：排队中 */
    private static final int STATUS_QUEUED = 0;

    /** 提交状态：编译中 */
    private static final int STATUS_COMPILING = 1;

    /** 提交状态：评测中 */
    private static final int STATUS_JUDGING = 2;

    /** 提交状态：AC（计数用） */
    private static final int STATUS_AC = 3;

    /** 详情预览默认长度（契约 §4.3） */
    private static final int DEFAULT_PREVIEW_LIMIT = 120;

    /** 详情预览上限（契约 §4.3） */
    private static final int MAX_PREVIEW_LIMIT = 500;

    /** 轨迹默认条数（契约 §4.5） */
    private static final int DEFAULT_TRACE_LIMIT = 20;

    /** 轨迹上限（契约 §4.5） */
    private static final int MAX_TRACE_LIMIT = 50;

    /** 时间范围白名单（契约 §4.2：3 / 7 / 30） */
    private static final List<Integer> RANGE_WHITELIST = List.of(3, 7, 30);

    /** 排序字段白名单（契约 §4.2） */
    private static final List<String> SORT_BY_WHITELIST = List.of("id", "timeMs", "score");

    /** 状态筛选：字符串 → int（契约 §4.2） */
    private static final Map<String, Integer> STATUS_FILTER_MAP = Map.of(
            "ac", 3, "pac", 4, "wa", 5, "tle", 6, "mle", 7, "re", 8, "ce", 9);

    private final JudgeSubmissionMapper submissionMapper;
    private final ProbQuestionMapper questionMapper;
    private final ProbTestCaseMapper testCaseMapper;
    private final JudgeExecutor judgeExecutor;
    private final JudgeRedisSupport redisSupport;
    private final com.oj.judge.mq.JudgeMessageProducer messageProducer;
    private final com.oj.judge.service.support.JudgeResultWriter resultWriter;

    /** 代码长度上限（字节，契约：≤64 KB） */
    private final int codeMaxBytes;

    /** 沙箱实例标识（落 judge_submission.sandbox_instance_id） */
    private final String sandboxInstanceId;

    /** 宿主机测试数据根目录（运行样例时读公开用例正文） */
    private final Path testcaseRoot;

    public JudgeServiceImpl(JudgeSubmissionMapper submissionMapper,
                            ProbQuestionMapper questionMapper,
                            ProbTestCaseMapper testCaseMapper,
                            JudgeExecutor judgeExecutor,
                            JudgeRedisSupport redisSupport,
                            com.oj.judge.mq.JudgeMessageProducer messageProducer,
                            com.oj.judge.service.support.JudgeResultWriter resultWriter,
                            @Value("${oj.judge.code-max-bytes:65536}") int codeMaxBytes,
                            @Value("${oj.judge.sandbox-instance-id:sbx-local}") String sandboxInstanceId,
                            @Value("${oj.judge.testcase-root:D:/毕设/agent oj/data/testcases}") String testcaseRoot) {
        this.submissionMapper = submissionMapper;
        this.questionMapper = questionMapper;
        this.testCaseMapper = testCaseMapper;
        this.judgeExecutor = judgeExecutor;
        this.redisSupport = redisSupport;
        this.messageProducer = messageProducer;
        this.resultWriter = resultWriter;
        this.codeMaxBytes = codeMaxBytes;
        this.sandboxInstanceId = sandboxInstanceId;
        this.testcaseRoot = Path.of(testcaseRoot);
    }

    // ==================================================================
    // 1. self-test（v1.0 冻结端点）
    // ==================================================================

    @Override
    public Map<String, Object> selfTest(JudgeRequests.SelfTestRequest request, Long userId) {
        redisSupport.requireJudgeLock(userId);
        ProbQuestionJudgeConfig config = requirePublishedProblem(request.getProblemId());
        validateLanguage(request.getLanguage(), config);
        validateCode(request.getCode());

        // self-test 语义：跑「一段输入」，不比对期望输出、不落库。
        // 为了复用两阶段执行器，这里构造一个「临时测试点」：把 customInput 写成
        // 一个临时 .in 文件放到测试数据目录下？——不行，测试目录是 6.95GB 只读资产。
        // 正确做法：customInput 直接内联进 files[0].content（沙箱不读磁盘）。
        // 因此这里单独走一条「单测试点、内联输入」的轻量链路。
        long start = System.currentTimeMillis();
        JudgeExecutor.ExecutionResult result = judgeExecutor.executeWithInlineInput(
                request.getProblemId(),
                request.getLanguage(),
                request.getCode(),
                request.getCustomInput() == null ? "" : request.getCustomInput(),
                config.getTimeLimit() == null ? 1000 : config.getTimeLimit(),
                config.getMemoryLimit() == null ? 256 : config.getMemoryLimit());

        Map<String, Object> data = new LinkedHashMap<>(8);
        data.put("status", "FINISHED");
        data.put("statusCode", result.getStatus().getStatusCode());
        data.put("compileError", result.isCompileError() ? result.getErrorMsg() : null);
        data.put("actualOutput", firstCaseOutput(result));
        data.put("timeMs", result.getTimeUsedMs());
        data.put("memoryKb", result.getMemoryUsedKb());
        log.debug("self-test 完成：problem={} lang={} status={} 耗时={}ms",
                request.getProblemId(), request.getLanguage(), result.getStatus(),
                System.currentTimeMillis() - start);
        return data;
    }

    // ==================================================================
    // 2. submit（异步）
    // ==================================================================

    /**
     * {@inheritDoc}
     *
     * <p><b>为什么这里<b>不加</b> {@code @Transactional}：</b>本方法要做「INSERT → 发 MQ」。
     * 若把 MQ 发送包在事务里，会出现「事务最终回滚但消息已投递」的经典不一致 ——
     * 消费者拿到的 submissionId 在库里根本不存在。反过来「先提交事务再发消息」也有
     * 极小窗口（提交成功、发消息失败），但那一种可以被显式降级处理（把状态落成 RE），
     * 而前一种无法补救。</p>
     *
     * <p>因此这里让每次 mapper 调用各自自动提交，保证「发消息时 INSERT 一定已可见」。
     * 代价是「INSERT 成功但计数失败」不再回滚，但计数本身就是可对账的统计口径
     * （day3 §7 硬约束 6），且已用 try/catch 单独兜底。</p>
     */
    @Override
    public Map<String, Object> submit(JudgeRequests.SubmitRequest request, Long userId) {
        redisSupport.requireJudgeLock(userId);
        ProbQuestionJudgeConfig config = requirePublishedProblem(request.getProblemId());
        validateLanguage(request.getLanguage(), config);
        validateCode(request.getCode());

        List<ProbTestCaseMeta> cases = testCaseMapper.selectMetaByProblem(request.getProblemId());
        if (cases.isEmpty()) {
            throw BusinessException.badRequest("该题目没有测试点，无法提交判题");
        }

        JudgeSubmission submission = new JudgeSubmission();
        submission.setDisplayNo(redisSupport.nextSubmissionDisplayNo());
        submission.setUserId(userId);
        submission.setProblemId(request.getProblemId());
        submission.setAssignmentId(request.getAssignmentId() == null ? 0L : request.getAssignmentId());
        submission.setLanguage(JudgeAdapter.normalizeLanguage(request.getLanguage()));
        submission.setCode(request.getCode());
        // 先落「排队中」再发 MQ：反过来的话消费者可能比 INSERT 先到，查不到记录
        submission.setStatus(STATUS_QUEUED);
        submission.setOiScore(0);
        submission.setPassCaseCount(0);
        submission.setTotalCaseCount(cases.size());
        submission.setTimeUsed(0);
        submission.setMemoryUsed(0);
        submission.setRejudgeCount(0);
        submission.setIsOverdue(0);
        submission.setSandboxInstanceId(sandboxInstanceId);
        submissionMapper.insert(submission);

        // 【跨服务直连·本期妥协】题目提交计数（题库服务独立分库后改 Feign）
        try {
            submissionMapper.increaseSubmitCount(request.getProblemId());
        } catch (Exception e) {
            // 计数失败不该让用户提交失败：判题记录已经落库了
            log.warn("更新题目 submit_count 失败（不影响判题）：problem={} {}", request.getProblemId(), e.getMessage());
        }

        Map<String, Object> data = new LinkedHashMap<>(8);
        data.put("submissionId", submission.getId());
        data.put("displayNo", submission.getDisplayNo());
        data.put("status", STATUS_QUEUED);
        data.put("isOverdue", 0);
        log.info("提交入队：id={} displayNo={} user={} problem={} lang={} cases={}",
                submission.getId(), submission.getDisplayNo(), userId, request.getProblemId(),
                submission.getLanguage(), cases.size());

        // 落库之后才发 MQ（顺序不能反：消费者可能比 INSERT 先到，查不到记录会丢消息）。
        // 投递失败时把该提交直接落成 RE + 明确原因，绝不让它永远卡在「排队中」。
        boolean sent = messageProducer.sendJudgeTask(submission.getId(), request.getProblemId(), userId);
        if (!sent) {
            finishWithFailure(submission, JudgeStatusEnum.RE, "判题队列不可用，本次提交未能进入判题（请稍后重试）");
            throw BusinessException.badGateway("判题队列不可用，请稍后重试");
        }
        return data;
    }

    // ==================================================================
    // 3. status（轮询）
    // ==================================================================

    @Override
    public Map<String, Object> status(Long submissionId, Long userId, boolean isTeacher) {
        JudgeSubmission submission = requireSubmission(submissionId);
        assertOwnership(submission, userId, isTeacher);
        return buildStatusPayload(submission);
    }

    // ==================================================================
    // 4. submissions（列表 + 内嵌统计）
    // ==================================================================

    @Override
    public Map<String, Object> submissions(int page, int pageSize, String status, String lang, Integer range,
                                           String keyword, String sortBy, String sortOrder, Long userId) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);
        Integer rangeDays = normalizeRange(range);
        List<Integer> statuses = parseStatusFilter(status);
        String language = normalizeLangFilter(lang);
        String safeSortBy = normalizeSortBy(sortBy);
        String safeSortOrder = "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
        String safeKeyword = (keyword == null || keyword.isBlank()) ? null : keyword.trim();

        long total = submissionMapper.countSubmissionPage(userId, statuses, language, safeKeyword, rangeDays);
        List<Map<String, Object>> rows = total == 0
                ? new ArrayList<>()
                : submissionMapper.selectSubmissionPage(userId, statuses, language, safeKeyword, rangeDays,
                safeSortBy, safeSortOrder, (safePage - 1) * safePageSize, safePageSize);

        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            list.add(toSubmissionListItem(row));
        }

        Map<String, Object> data = new LinkedHashMap<>(8);
        data.put("list", list);
        data.put("total", total);
        data.put("page", safePage);
        data.put("pageSize", safePageSize);
        data.put("hasNext", (long) safePage * safePageSize < total);
        data.put("stats", buildStats(userId, rangeDays, total, list));
        return data;
    }

    // ==================================================================
    // 5. submissionDetail（详情 + 逐点预览 + 轨迹）
    // ==================================================================

    @Override
    public Map<String, Object> submissionDetail(Long id, Integer previewLimit, Long userId, boolean isTeacher) {
        JudgeSubmission submission = requireSubmission(id);
        assertOwnership(submission, userId, isTeacher);
        int limit = clampPreviewLimit(previewLimit);

        ProbQuestionJudgeConfig config = questionMapper.selectJudgeConfig(submission.getProblemId());
        String problemTitle = config == null ? null : config.getTitle();

        List<Map<String, Object>> caseDetails = buildCaseDetails(submission, limit, true);

        Map<String, Object> data = new LinkedHashMap<>(24);
        data.put("id", submission.getId());
        data.put("displayNo", submission.getDisplayNo());
        data.put("problemId", submission.getProblemId());
        data.put("problemTitle", problemTitle);
        data.put("language", submission.getLanguage());
        data.put("languageLabel", JudgeAdapter.languageLabel(submission.getLanguage()));
        data.put("code", submission.getCode());
        data.put("status", submission.getStatus());
        data.put("statusLabel", JudgeStatusEnum.of(submission.getStatus()).getLabel());
        data.put("statusCode", JudgeStatusEnum.of(submission.getStatus()).getStatusCode());
        data.put("score", submission.getOiScore());
        data.put("oiScore", submission.getOiScore());
        data.put("passCaseCount", submission.getPassCaseCount());
        data.put("totalCaseCount", submission.getTotalCaseCount());
        data.put("timeUsedMs", submission.getTimeUsed());
        data.put("memoryUsedKb", submission.getMemoryUsed());
        data.put("errorMsg", submission.getErrorMsg());
        data.put("isOverdue", submission.getIsOverdue() != null && submission.getIsOverdue() == 1);
        data.put("submittedAt", toEpochMilli(submission.getCreateTime()));
        data.put("caseDetails", caseDetails);
        data.put("trace", buildTraceList(submission.getProblemId(), submission.getUserId(), DEFAULT_TRACE_LIMIT));
        return data;
    }

    // ==================================================================
    // 6. trace（同题轨迹）
    // ==================================================================

    @Override
    public Map<String, Object> trace(Long problemId, Long userId, Integer limit,
                                     Long currentUserId, boolean isTeacher) {
        if (problemId == null) {
            throw BusinessException.badRequest("problemId 不能为空");
        }
        Long targetUserId = userId;
        if (targetUserId == null) {
            if (currentUserId == null) {
                throw BusinessException.unauthorized("未登录或 Token 失效");
            }
            targetUserId = currentUserId;
        } else if (!Objects.equals(targetUserId, currentUserId) && !isTeacher) {
            throw BusinessException.forbidden("没有权限查看他人的提交轨迹");
        }
        int safeLimit = limit == null ? DEFAULT_TRACE_LIMIT : Math.min(Math.max(limit, 1), MAX_TRACE_LIMIT);

        List<Map<String, Object>> list = buildTraceList(problemId, targetUserId, safeLimit);
        Long bestId = submissionMapper.selectBestSubmissionId(targetUserId, problemId);
        Integer bestScore = 0;
        if (bestId != null) {
            JudgeSubmission best = submissionMapper.selectById(bestId);
            bestScore = best == null || best.getOiScore() == null ? 0 : best.getOiScore();
        }

        Map<String, Object> data = new LinkedHashMap<>(8);
        data.put("problemId", problemId);
        data.put("list", list);
        data.put("bestSubmissionId", bestId);
        data.put("bestScore", bestScore);
        return data;
    }

    // ==================================================================
    // 7. runSample（样例 / 自定义输入）
    // ==================================================================

    @Override
    public Map<String, Object> runSample(JudgeRequests.RunSampleRequest request, Long userId) {
        redisSupport.requireJudgeLock(userId);
        ProbQuestionJudgeConfig config = requirePublishedProblem(request.getProblemId());
        validateLanguage(request.getLanguage(), config);
        validateCode(request.getCode());

        String mode = request.getMode() == null ? "" : request.getMode().trim().toLowerCase();
        if (!"sample".equals(mode) && !"custom".equals(mode)) {
            throw BusinessException.badRequest("mode 只能是 sample 或 custom");
        }

        int timeLimit = config.getTimeLimit() == null ? 1000 : config.getTimeLimit();
        int memoryLimit = config.getMemoryLimit() == null ? 256 : config.getMemoryLimit();

        if ("custom".equals(mode)) {
            if (request.getCustomInput() == null) {
                throw BusinessException.badRequest("mode=custom 时必须提供 customInput");
            }
            if (request.getCustomInput().getBytes(StandardCharsets.UTF_8).length > codeMaxBytes) {
                throw BusinessException.badRequest("customInput 不能超过 64 KB");
            }
            JudgeExecutor.ExecutionResult result = judgeExecutor.executeWithInlineInput(
                    request.getProblemId(), request.getLanguage(), request.getCode(),
                    request.getCustomInput(), timeLimit, memoryLimit);
            return buildRunSamplePayload(mode, result, request.getCustomInput(), null);
        }

        // mode = sample：跑公开样例（is_public = 1），可指定 sampleSeq
        List<ProbTestCaseMeta> publicCases = testCaseMapper.selectPublicCases(request.getProblemId());
        if (publicCases.isEmpty()) {
            throw BusinessException.notFound("该题目没有公开样例");
        }
        List<ProbTestCaseMeta> selected;
        if (request.getSampleSeq() != null) {
            selected = publicCases.stream()
                    .filter(c -> Objects.equals(c.getSeqNo(), request.getSampleSeq()))
                    .toList();
            if (selected.isEmpty()) {
                throw BusinessException.notFound("指定的样例不存在：" + request.getSampleSeq());
            }
        } else {
            selected = publicCases;
        }

        // 公开样例同样要读磁盘正文（DB 里的 input_text 是摘要）
        List<JudgeExecutor.CaseSpec> specs = new ArrayList<>(selected.size());
        for (ProbTestCaseMeta meta : selected) {
            specs.add(new JudgeExecutor.CaseSpec(meta.getId(), meta.getSeqNo(), meta.getKind(), meta.getScore()));
        }
        // 样例运行不落库、不占提交记录：直接跑「只含公开样例」的子集
        JudgeExecutor.ExecutionResult result = judgeExecutor.executeWithCaseSpecs(
                request.getProblemId(), request.getLanguage(), request.getCode(), specs, timeLimit, memoryLimit);

        Map<String, Object> data = buildRunSamplePayload(mode, result, null, selected);
        return data;
    }

    // ==================================================================
    // 8. 内部接口：失败诊断
    // ==================================================================

    @Override
    public FailedCaseDiagnosisDTO failedDiagnosis(Long userId, Long problemId, Long assignmentId) {
        JudgeSubmission failed = submissionMapper.selectLatestFailed(userId, problemId, assignmentId);
        if (failed == null) {
            return null;
        }
        FailedCaseDiagnosisDTO dto = new FailedCaseDiagnosisDTO();
        dto.setSubmissionId(failed.getId());
        dto.setFirstFailedSummary(redisSupport.toJson(com.oj.judge.service.support.DiagnosisSummary.sanitize(
                redisSupport.parseObject(failed.getFirstFailedSummary()), false)));
        dto.setDisplayNo(failed.getDisplayNo());
        dto.setStatus(failed.getStatus());
        dto.setStatusLabel(JudgeStatusEnum.of(failed.getStatus()).getLabel());
        dto.setTimeUsedMs(failed.getTimeUsed() == null ? 0L : failed.getTimeUsed().longValue());
        dto.setMemoryUsedKb(failed.getMemoryUsed() == null ? 0L : failed.getMemoryUsed().longValue());

        // 编译错误没有「测试点」，错误信息就是 stderr
        if (JudgeStatusEnum.of(failed.getStatus()) == JudgeStatusEnum.CE) {
            dto.setErrorSnippet(abbreviate(failed.getErrorMsg(), 2000));
            dto.setHistoryFailCount(submissionMapper.countFailed(userId, problemId));
            return dto;
        }

        List<Map<String, Object>> details = redisSupport.parseCaseDetails(failed.getCaseDetails());
        Map<String, Object> firstFailed = null;
        for (Map<String, Object> detail : details) {
            String status = String.valueOf(detail.get("status"));
            if (!"AC".equalsIgnoreCase(status)) {
                firstFailed = detail;
                break;
            }
        }
        if (firstFailed == null) {
            // 状态是失败但没有失败明细（历史脏数据）：只回状态，不给假数据
            dto.setHistoryFailCount(submissionMapper.countFailed(userId, problemId));
            return dto;
        }

        int seqNo = toInt(firstFailed.get("seqNo"), failed.getFirstFailedCaseId() == null
                ? 1 : failed.getFirstFailedCaseId());
        String kind = firstFailed.get("kind") == null ? "hidden" : String.valueOf(firstFailed.get("kind"));
        dto.setFailedSeqNo(seqNo);
        dto.setFailedKind(kind);

        // 【防泄题】只有公开样例才下发期望/实际输出；隐藏用例一律置空
        boolean isPublic = isPublicCase(failed.getProblemId(), seqNo);
        if (isPublic) {
            dto.setFirstFailedSummary(failed.getFirstFailedSummary());
            dto.setExpectedOutput(abbreviate(stringOf(firstFailed.get("expectedPreview")), 2000));
            dto.setActualOutput(abbreviate(stringOf(firstFailed.get("actualPreview")), 2000));
        }
        dto.setErrorSnippet(abbreviate(failed.getErrorMsg(), 2000));
        dto.setHistoryFailCount(submissionMapper.countFailed(userId, problemId));

        // 常见错因标签：用状态推一个粗粒度集合（不做 AI 推断，本期只给确定性标签）
        dto.setCauseTags(causeTagsOf(failed.getStatus()));
        return dto;
    }

    // ==================================================================
    // 9. 内部接口：批量取最佳代码
    // ==================================================================

    @Override
    public List<SubmissionCodeDTO> batchBestCodes(List<Long> submissionIds) {
        if (submissionIds == null || submissionIds.isEmpty()) {
            return new ArrayList<>();
        }
        // 去重 + 上限保护（一次查重任务最多 500 份代码，再多说明调用方传错了）
        List<Long> ids = submissionIds.stream().filter(Objects::nonNull).distinct().limit(500).toList();
        if (ids.isEmpty()) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> rows = submissionMapper.selectBestCodeRows(ids);
        List<SubmissionCodeDTO> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            SubmissionCodeDTO dto = new SubmissionCodeDTO();
            dto.setSubmissionId(toLong(row.get("id")));
            dto.setUserId(toLong(row.get("userId")));
            dto.setProblemId(toLong(row.get("problemId")));
            dto.setLanguage(stringOf(row.get("language")));
            dto.setScore(toInt(row.get("oiScore"), 0));
            dto.setStatus(toInt(row.get("status"), 0));
            dto.setCode(stringOf(row.get("code")));
            dto.setCodeLength(toInt(row.get("codeLength"), 0));
            dto.setSubmittedAt(toEpochMilli(row.get("createTime")));
            // userName 属于用户域，判题服务不持有；置 null 由调用方（教务）补齐
            dto.setUserName(null);
            dto.setAstHash(null);
            result.add(dto);
        }
        return result;
    }

    // ==================================================================
    // 10. MQ 消费入口：真正判题
    // ==================================================================

    @Override
    public void judgeSubmission(Long submissionId) {
        JudgeSubmission submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            log.warn("判题消息对应的提交不存在，跳过：submissionId={}", submissionId);
            return;
        }
        if (JudgeStatusEnum.isTerminalStatus(submission.getStatus())) {
            log.warn("提交已是终态，跳过重复消费：submissionId={} status={}", submissionId, submission.getStatus());
            return;
        }

        // 0(QUEUED) → 1(COMPILING)
        updateStatus(submissionId, JudgeStatusEnum.COMPILING);

        ProbQuestionJudgeConfig config = questionMapper.selectJudgeConfig(submission.getProblemId());
        if (config == null) {
            // 题目被删：落 RE 并写明原因，而不是让状态永远停在「编译中」
            finishWithFailure(submission, JudgeStatusEnum.RE, "题目不存在或已被删除，无法判题");
            return;
        }
        List<ProbTestCaseMeta> caseMetas = testCaseMapper.selectMetaByProblem(submission.getProblemId());
        if (caseMetas.isEmpty()) {
            finishWithFailure(submission, JudgeStatusEnum.RE, "题目没有可用测试点，无法判题");
            return;
        }

        List<JudgeExecutor.CaseSpec> specs = new ArrayList<>(caseMetas.size());
        for (ProbTestCaseMeta meta : caseMetas) {
            specs.add(new JudgeExecutor.CaseSpec(meta.getId(), meta.getSeqNo(), meta.getKind(), meta.getScore()));
        }

        try {
            // 1(COMPILING) → 2(JUDGING)：编译在 execute 内部完成，这里先把状态推到评测中。
            // 严格说编译失败时应停在 1 直接跳 9，但为了让前端在长编译期间看到「评测中」
            // 而不是卡在「编译中」误以为挂死，这里统一推到 2；最终状态由结果覆盖。
            updateStatus(submissionId, JudgeStatusEnum.JUDGING);

            JudgeExecutor.ExecutionResult result = judgeExecutor.executeWithCaseSpecs(
                    submission.getProblemId(), submission.getLanguage(), submission.getCode(), specs,
                    config.getTimeLimit() == null ? 1000 : config.getTimeLimit(),
                    config.getMemoryLimit() == null ? 256 : config.getMemoryLimit());

            persistResult(submission, result);
        } catch (BusinessException e) {
            // 沙箱故障 / 测试数据缺失：这是**基础设施问题**，不能记成用户 WA。
            // 落 RE + 明确 errorMsg，让用户能重试，运维能定位。
            log.error("判题失败（基础设施）：submissionId={} code={} msg={}",
                    submissionId, e.getCode(), e.getMessage());
            finishWithFailure(submission, JudgeStatusEnum.RE,
                    "判题服务异常（" + e.getCode() + "）：" + e.getMessage());
        } catch (Exception e) {
            log.error("判题异常：submissionId={}", submissionId, e);
            finishWithFailure(submission, JudgeStatusEnum.RE, "判题服务内部错误：" + e.getMessage());
        }
    }

    // ==================================================================
    // 内部：状态与结果落库（统一委托给 JudgeResultWriter，保证 @Transactional 生效）
    // ==================================================================

    /**
     * 更新状态（轻量 UPDATE，只改 status）。
     *
     * <p>委托给 {@link JudgeResultWriter}：本类内部自调用不会经过 Spring 代理，
     * 写在类里的 {@code @Transactional} 会被静默忽略（经典事务失效坑）。</p>
     */
    private void updateStatus(Long submissionId, JudgeStatusEnum status) {
        resultWriter.updateStatus(submissionId, status);
    }

    /** 判题成功（含 CE/WA/TLE 等「用户侧结果」）落库 */
    private void persistResult(JudgeSubmission submission, JudgeExecutor.ExecutionResult result) {
        resultWriter.persistResult(submission, result);
    }

    /** 判题失败（基础设施问题）落库 */
    private void finishWithFailure(JudgeSubmission submission, JudgeStatusEnum status, String errorMsg) {
        resultWriter.finishWithFailure(submission.getId(), status, errorMsg);
    }

    // ==================================================================
    // 内部：响应组装
    // ==================================================================

    /**
     * 组装 {@code GET /status/{id}} 的 data。
     *
     * <p><b>为什么这里也要带 preview 三栏：</b>工作台（{@code s-workbench.js}）
     * 判分链的唯一数据源就是这个接口——它把 {@code caseDetails} 直接映射成
     * {@code this.cases}。若这里只回状态/耗时/内存，工作台点开失败测试点就只剩
     * 「— /（隐藏）」三个空位，而「第 3 步验收标志」要求的正是「点第 3 格能看到
     * 真实的输入输出对比」。所以状态接口与详情接口共用同一套脱敏规则
     * （{@link #buildCaseDetails}），不再各写一份。</p>
     *
     * <p>脱敏口径不变：隐藏点（{@code is_public=0}）只给输入前 100 字符，
     * 期望/实际输出为 {@code null}——避免把隐藏用例的答案直接摊给学生。</p>
     */
    private Map<String, Object> buildStatusPayload(JudgeSubmission submission) {
        JudgeStatusEnum status = JudgeStatusEnum.of(submission.getStatus());
        List<Map<String, Object>> caseDetails =
                buildCaseDetails(submission, DEFAULT_PREVIEW_LIMIT, false);

        Map<String, Object> data = new LinkedHashMap<>(24);
        data.put("submissionId", submission.getId());
        data.put("displayNo", submission.getDisplayNo());
        data.put("status", submission.getStatus());
        data.put("statusLabel", status.getLabel());
        data.put("statusCode", status.getStatusCode());
        data.put("oiScore", submission.getOiScore());
        data.put("passCaseCount", submission.getPassCaseCount());
        data.put("totalCaseCount", submission.getTotalCaseCount());
        data.put("timeUsed", submission.getTimeUsed());
        data.put("timeUsedMs", submission.getTimeUsed());
        data.put("memoryUsed", submission.getMemoryUsed());
        data.put("memoryUsedKb", submission.getMemoryUsed());
        data.put("errorMsg", submission.getErrorMsg());
        data.put("firstFailedCaseId", submission.getFirstFailedCaseId());
        data.put("caseDetails", caseDetails);
        data.put("aiStateUpdate", buildAiStateUpdate(status, submission));
        return data;
    }

    /**
     * AI 阶段解锁状态（契约 {@code aiStateUpdate}）。
     *
     * <p>本期只做确定性推导：<b>AC 才解锁阶段 3（错因诊断）与阶段 4（复杂度优化）</b>——
     * 没错可诊断、没错可优化。真正是否解锁还要看作业的 AI 策略与配额，
     * 那属于 AI 服务的职责，这里只给「判题维度」的建议值。</p>
     */
    private Map<String, Object> buildAiStateUpdate(JudgeStatusEnum status, JudgeSubmission submission) {
        boolean ac = status == JudgeStatusEnum.AC;
        Map<String, Object> ai = new LinkedHashMap<>(4);
        ai.put("canUnlockStage3", ac);
        ai.put("canUnlockStage4", ac);
        ai.put("guideBtnText", ac ? "看看更优解法" : "分析错在哪");
        return ai;
    }

    /** 组装列表项（契约 §4.2 响应字段逐字对齐） */
    private Map<String, Object> toSubmissionListItem(Map<String, Object> row) {
        JudgeStatusEnum status = JudgeStatusEnum.of(toInt(row.get("status"), 0));
        Map<String, Object> item = new LinkedHashMap<>(20);
        item.put("id", toLong(row.get("id")));
        item.put("displayNo", stringOf(row.get("displayNo")));
        item.put("problemId", toLong(row.get("problemId")));
        item.put("problemTitle", stringOf(row.get("problemTitle")));
        item.put("language", stringOf(row.get("language")));
        item.put("languageLabel", JudgeAdapter.languageLabel(stringOf(row.get("language"))));
        item.put("status", status.getCode());
        item.put("statusLabel", status.getLabel());
        item.put("statusCode", status.getStatusCode());
        item.put("score", toInt(row.get("oiScore"), 0));
        item.put("passCaseCount", toInt(row.get("passCaseCount"), 0));
        item.put("totalCaseCount", toInt(row.get("totalCaseCount"), 0));
        item.put("timeUsedMs", toInt(row.get("timeUsed"), 0));
        item.put("memoryUsedKb", toInt(row.get("memoryUsed"), 0));
        item.put("firstFailedCaseSeq", row.get("firstFailedCaseId"));
        item.put("submittedAt", toEpochMilli(row.get("createTime")));
        return item;
    }

    /**
     * 内嵌统计（契约 §4.2 的 {@code stats}）。
     *
     * <p>四格指标一次 SQL 查完；两张分布卡各一条 GROUP BY。
     * 百分比统一 0–100（不是 0–1），保留 1 位小数。</p>
     */
    private Map<String, Object> buildStats(Long userId, Integer rangeDays, long pageTotal,
                                          List<Map<String, Object>> pageList) {
        Map<String, Object> overview = submissionMapper.selectOverviewStats(userId);
        long totalAc = overview == null ? 0 : toLong(overview.get("totalAc"), 0);
        long avgTime = overview == null ? 0 : toLong(overview.get("avgTimeMs"), 0);
        long weekCount = overview == null ? 0 : toLong(overview.get("weekCount"), 0);
        long weekAc = overview == null ? 0 : toLong(overview.get("weekAc"), 0);
        long weekProblem = overview == null ? 0 : toLong(overview.get("weekProblemCount"), 0);

        // 提交总数（分母）：用与列表同条件的 total 不准（列表可能被 status 过滤），
        // 因此单独查「不限条件」的总数
        long allTotal = submissionMapper.countSubmissionPage(userId, null, null, null, null);
        double acRate = allTotal == 0 ? 0.0 : Math.round(totalAc * 1000.0 / allTotal) / 10.0;

        Map<String, Object> stats = new LinkedHashMap<>(8);
        stats.put("totalAc", totalAc);
        stats.put("acRate", acRate);
        stats.put("avgTimeMs", avgTime);
        stats.put("weekCount", weekCount);
        stats.put("weekAc", weekAc);
        stats.put("weekProblemCount", weekProblem);
        stats.put("statusDist", buildStatusDist(userId, rangeDays));
        stats.put("langDist", buildLangDist(userId, rangeDays));
        return stats;
    }

    /** 结果分布（百分比按「该范围内提交总数」算） */
    private List<Map<String, Object>> buildStatusDist(Long userId, Integer rangeDays) {
        List<Map<String, Object>> rows = submissionMapper.countStatusDistribution(userId, rangeDays);
        long total = 0;
        for (Map<String, Object> row : rows) {
            total += toLong(row.get("cnt"), 0);
        }
        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        // 按 status 升序输出，保证前端图表颜色稳定（不能依赖 SQL 的 GROUP BY 顺序）
        rows.sort(Comparator.comparingInt(row -> toInt(row.get("status"), 0)));
        for (Map<String, Object> row : rows) {
            JudgeStatusEnum status = JudgeStatusEnum.of(toInt(row.get("status"), 0));
            long count = toLong(row.get("cnt"), 0);
            Map<String, Object> item = new LinkedHashMap<>(6);
            item.put("status", status.getCode());
            item.put("statusCode", status.getStatusCode());
            item.put("count", count);
            item.put("percent", total == 0 ? 0.0 : Math.round(count * 1000.0 / total) / 10.0);
            list.add(item);
        }
        return list;
    }

    /** 语言分布 */
    private List<Map<String, Object>> buildLangDist(Long userId, Integer rangeDays) {
        List<Map<String, Object>> rows = submissionMapper.countLanguageDistribution(userId, rangeDays);
        long total = 0;
        for (Map<String, Object> row : rows) {
            total += toLong(row.get("cnt"), 0);
        }
        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        rows.sort(Comparator.comparing(row -> stringOf(row.get("language"))));
        for (Map<String, Object> row : rows) {
            String language = stringOf(row.get("language"));
            long count = toLong(row.get("cnt"), 0);
            Map<String, Object> item = new LinkedHashMap<>(6);
            item.put("language", language);
            item.put("label", JudgeAdapter.languageLabel(language));
            item.put("count", count);
            item.put("percent", total == 0 ? 0.0 : Math.round(count * 1000.0 / total) / 10.0);
            list.add(item);
        }
        return list;
    }

    /** 详情页逐点明细：按 is_public 决定是否下发期望/实际输出（防泄题） */
    /**
     * 逐点明细组装。
     *
     * <p><b>两个调用方的脱敏口径不同，所以必须显式传参，不能各写一份：</b></p>
     * <ul>
     *   <li>{@code #23 提交详情}（{@code s-submissions.html} 历史回看）→ {@code maskHiddenOutputs=true}：
     *       隐藏点只给输入前 100 字符，期望/实际输出为 {@code null}（契约 §4.3 的防泄题要求）。</li>
     *   <li>{@code #21 判题状态}（{@code s-workbench.html} 判分链）→ {@code maskHiddenOutputs=false}：
     *       这是<b>学生本人刚提交、正在调试</b>的那一次评测，设计文档 §「联动高亮」明确要求
     *       「点测试点看 input / expected / actual 三栏 diff」。《6 步手册》第 3 步的验收标志
     *       也写死要「点第 3 格能看到真实的输入输出对比」。这一路本来就有
     *       {@code assertOwnership} 兜住（只能看自己的提交），输出只在本次判题结果里出现一次。</li>
     * </ul>
     *
     * @param submission        提交记录
     * @param previewLimit      预览字符数
     * @param maskHiddenOutputs 是否对隐藏点屏蔽期望/实际输出
     */
    private List<Map<String, Object>> buildCaseDetails(JudgeSubmission submission, int previewLimit,
                                                       boolean maskHiddenOutputs) {
        List<Map<String, Object>> raw = redisSupport.parseCaseDetails(submission.getCaseDetails());
        List<Map<String, Object>> list = new ArrayList<>(raw.size());
        for (Map<String, Object> item : raw) {
            int seqNo = toInt(item.get("seqNo"), 0);
            String kind = stringOf(item.get("kind"));
            boolean isPublic = isPublicCase(submission.getProblemId(), seqNo);
            boolean showOutputs = showCaseOutputs(maskHiddenOutputs, isPublic);

            Map<String, Object> detail = new LinkedHashMap<>(16);
            detail.put("caseId", item.get("caseId"));
            detail.put("seqNo", seqNo);
            detail.put("kind", kind);
            detail.put("status", item.get("status"));
            detail.put("statusCode", item.get("statusCode"));
            detail.put("score", item.get("score"));
            detail.put("gotScore", item.get("gotScore"));
            detail.put("timeMs", item.get("timeMs"));
            detail.put("memKb", item.get("memKb"));
            // 输入预览：隐藏点也允许给前 100 字符（契约 §4.3 明确要求）
            detail.put("inputPreview", readInputPreview(submission.getProblemId(), seqNo,
                    isPublic ? previewLimit : Math.min(previewLimit, 100)));
            detail.put("expectedPreview", showOutputs
                    ? abbreviate(stringOf(item.get("expectedPreview")), previewLimit) : null);
            detail.put("actualPreview", showOutputs
                    ? abbreviate(stringOf(item.get("actualPreview")), previewLimit) : null);
            list.add(detail);
        }
        return list;
    }

    /**
     * 某个测试点的期望/实际输出是否可以下发给前端。
     *
     * <p>拆成独立方法是为了能被单元测试直接覆盖——这条规则一旦写错，
     * 要么把隐藏用例的答案泄给学生，要么让工作台点开测试点只看得到「（隐藏）」。</p>
     *
     * @param maskHiddenOutputs 调用方是否需要遮掉隐藏点（详情接口 true / 判题状态 false）
     * @param isPublic          该测试点是否公开（{@code prob_test_case.is_public = 1}）
     * @return true 表示可以下发
     */
    static boolean showCaseOutputs(boolean maskHiddenOutputs, boolean isPublic) {
        return !maskHiddenOutputs || isPublic;
    }

    /** 读测试点输入的前 N 字符（从磁盘读；读不到返回 null，不抛异常） */    private String readInputPreview(Long problemId, int seqNo, int limit) {
        Path path = JudgeAdapter.hostCasePath(testcaseRoot, problemId, seqNo, "in");
        if (!Files.isReadable(path)) {
            return null;
        }
        try {
            // 大文件（最大 205 MB）不能整个读进来做预览：只读前 4 KB 再截断
            byte[] buffer = new byte[4096];
            try (var in = Files.newInputStream(path)) {
                int read = in.read(buffer);
                if (read <= 0) {
                    return "";
                }
                return abbreviate(new String(buffer, 0, read, StandardCharsets.UTF_8), limit);
            }
        } catch (Exception e) {
            log.debug("读取输入预览失败：{}", path, e);
            return null;
        }
    }

    /** 同题轨迹列表（时间正序） */
    private List<Map<String, Object>> buildTraceList(Long problemId, Long userId, int limit) {
        List<Map<String, Object>> rows = submissionMapper.selectTraceRows(userId, problemId, limit);
        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        // SQL 取的是「最近 N 条（倒序）」，这里反转成正序给前端画时间线
        for (int i = rows.size() - 1; i >= 0; i--) {
            Map<String, Object> row = rows.get(i);
            JudgeStatusEnum status = JudgeStatusEnum.of(toInt(row.get("status"), 0));
            Map<String, Object> item = new LinkedHashMap<>(8);
            item.put("submissionId", toLong(row.get("id")));
            item.put("status", status.getCode());
            item.put("statusCode", status.getStatusCode());
            item.put("score", toInt(row.get("oiScore"), 0));
            item.put("timeUsedMs", toInt(row.get("timeUsed"), 0));
            item.put("submittedAt", toEpochMilli(row.get("createTime")));
            list.add(item);
        }
        return list;
    }

    /**
     * 组装 run-sample 响应（契约 §4.6）。
     *
     * @param mode         sample / custom
     * @param result       判题结果
     * @param customInput  custom 模式的输入原文（sample 模式传 null）
     * @param sampleCases  sample 模式的测试点元信息（custom 模式传 null）
     * @return 响应 data
     */
    private Map<String, Object> buildRunSamplePayload(String mode, JudgeExecutor.ExecutionResult result,
                                                      String customInput, List<ProbTestCaseMeta> sampleCases) {
        List<Map<String, Object>> cases = new ArrayList<>();
        boolean truncated = false;
        for (int i = 0; i < result.getCases().size(); i++) {
            JudgeExecutor.CaseOutcome outcome = result.getCases().get(i);
            Map<String, Object> item = new LinkedHashMap<>(12);
            item.put("seqNo", outcome.getSeqNo());
            item.put("status", outcome.getStatus().getLabel());
            item.put("statusCode", outcome.getStatus().getStatusCode());
            item.put("timeMs", outcome.getTimeMs());
            item.put("memKb", outcome.getMemKb());
            String input = customInput;
            String expected = null;
            if (sampleCases != null && i < sampleCases.size()) {
                // sample 模式：输入从磁盘读（DB 里只有 4KB 摘要），期望输出用执行器读到的 .out
                ProbTestCaseMeta meta = sampleCases.get(i);
                input = readInputPreview(meta.getProblemId(), outcome.getSeqNo(), 2000);
                expected = abbreviate(outcome.getExpectedOutput(), 2000);
            }
            item.put("input", abbreviate(input, 2000));
            item.put("expected", expected);
            item.put("actual", abbreviate(outcome.getActualOutput(), 2000));
            if (outcome.getActualOutput() != null && outcome.getActualOutput().length() > 2000) {
                truncated = true;
            }
            cases.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>(16);
        data.put("mode", mode);
        data.put("status", "FINISHED");
        data.put("compileError", result.isCompileError() ? result.getErrorMsg() : null);
        data.put("cases", cases);
        data.put("actualOutput", firstCaseOutput(result));
        data.put("timeMs", result.getTimeUsedMs());
        data.put("memoryKb", result.getMemoryUsedKb());
        data.put("truncated", truncated);
        return data;
    }

    /** 取第一个测试点的实际输出（self-test / run-sample 的 {@code actualOutput}） */
    private String firstCaseOutput(JudgeExecutor.ExecutionResult result) {
        if (result.getCases().isEmpty()) {
            return result.isCompileError() ? null : "";
        }
        return result.getCases().get(0).getActualOutput();
    }

    // ==================================================================
    // 内部：校验与工具
    // ==================================================================

    /** 取已发布的题目配置（草稿/归档/不存在一律 404，避免判到不可见题目） */
    private ProbQuestionJudgeConfig requirePublishedProblem(Long problemId) {
        if (problemId == null) {
            throw BusinessException.badRequest("problemId 不能为空");
        }
        ProbQuestionJudgeConfig config = questionMapper.selectJudgeConfig(problemId);
        if (config == null) {
            throw BusinessException.notFound("题目不存在或已删除");
        }
        if (config.getState() == null || config.getState() != 1) {
            throw BusinessException.notFound("题目未发布，暂不可判题");
        }
        return config;
    }

    /** 语言白名单校验（只允许 cpp / java / python） */
    private void validateLanguage(String language, ProbQuestionJudgeConfig config) {
        String lang = JudgeAdapter.normalizeLanguage(language);
        if (!List.of("cpp", "java", "python").contains(lang)) {
            throw BusinessException.badRequest("不支持的语言：" + language + "（仅支持 cpp / java / python）");
        }
        if (language == null || language.isBlank()) {
            throw BusinessException.badRequest("language 不能为空");
        }
    }

    /** 代码长度校验（契约：≤64 KB） */
    private void validateCode(String code) {
        if (code == null || code.isBlank()) {
            throw BusinessException.badRequest("code 不能为空");
        }
        int bytes = code.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > codeMaxBytes) {
            throw BusinessException.badRequest("代码不能超过 64 KB（当前 " + bytes + " 字节）");
        }
    }

    /** 取提交记录，不存在抛 404 */
    private JudgeSubmission requireSubmission(Long submissionId) {
        JudgeSubmission submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            throw BusinessException.notFound("提交记录不存在");
        }
        return submission;
    }

    /** 归属校验：非本人且非教师 → 403 */
    private void assertOwnership(JudgeSubmission submission, Long userId, boolean isTeacher) {
        if (isTeacher) {
            return;
        }
        if (userId == null) {
            throw BusinessException.unauthorized("未登录或 Token 失效");
        }
        if (!Objects.equals(submission.getUserId(), userId)) {
            throw BusinessException.forbidden("没有权限查看他人的提交");
        }
    }

    /** 状态筛选解析：{@code all} / 空 → null（不限） */
    private List<Integer> parseStatusFilter(String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) {
            return null;
        }
        List<Integer> statuses = new ArrayList<>();
        for (String part : status.split(",")) {
            String key = part.trim().toLowerCase();
            if (key.isEmpty()) {
                continue;
            }
            Integer value = STATUS_FILTER_MAP.get(key);
            if (value == null) {
                throw BusinessException.badRequest("status 取值非法：" + part + "（可选 all/ac/wa/tle/mle/re/ce）");
            }
            statuses.add(value);
        }
        return statuses.isEmpty() ? null : statuses;
    }

    /** 语言筛选：{@code all} / 空 → null */
    private String normalizeLangFilter(String lang) {
        if (lang == null || lang.isBlank() || "all".equalsIgnoreCase(lang)) {
            return null;
        }
        String normalized = JudgeAdapter.normalizeLanguage(lang);
        if (!List.of("cpp", "java", "python").contains(normalized)) {
            throw BusinessException.badRequest("lang 取值非法：" + lang + "（可选 all/cpp/python/java）");
        }
        return normalized;
    }

    /** 时间范围：只允许 3 / 7 / 30 */
    private Integer normalizeRange(Integer range) {
        if (range == null) {
            return 3;
        }
        if (!RANGE_WHITELIST.contains(range)) {
            throw BusinessException.badRequest("range 只能是 3 / 7 / 30");
        }
        return range;
    }

    /** 排序字段白名单 */
    private String normalizeSortBy(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) {
            return "id";
        }
        if (!SORT_BY_WHITELIST.contains(sortBy)) {
            throw BusinessException.badRequest("sortBy 只能是 id / timeMs / score");
        }
        return sortBy;
    }

    /** 预览长度水位 */
    private int clampPreviewLimit(Integer previewLimit) {
        if (previewLimit == null) {
            return DEFAULT_PREVIEW_LIMIT;
        }
        return Math.min(Math.max(previewLimit, 1), MAX_PREVIEW_LIMIT);
    }

    /** 该题该序号的测试点是否公开（详情防泄题用） */
    private boolean isPublicCase(Long problemId, int seqNo) {
        List<ProbTestCaseMeta> metas = testCaseMapper.selectMetaByProblem(problemId);
        for (ProbTestCaseMeta meta : metas) {
            if (Objects.equals(meta.getSeqNo(), seqNo)) {
                return meta.getIsPublic() != null && meta.getIsPublic() == 1;
            }
        }
        return false;
    }

    /** 由状态推常见错因标签（确定性映射，不做 AI 推断） */
    private List<String> causeTagsOf(Integer status) {
        JudgeStatusEnum value = JudgeStatusEnum.of(status);
        return switch (value) {
            case WA -> List.of("输出与期望不一致", "边界条件未处理");
            case TLE -> List.of("时间复杂度过高", "存在死循环");
            case MLE -> List.of("内存占用过大", "数组/容器开得过大");
            case RE -> List.of("数组越界", "除以零", "空指针");
            case CE -> List.of("语法错误", "缺少头文件/导入");
            default -> new ArrayList<>();
        };
    }

    /** 截断文本（null 安全） */
    private static String abbreviate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** 转字符串（null → null） */
    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 转 int（带兜底） */
    private static int toInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 转 long（带兜底） */
    private static long toLong(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 转 Long（null 安全） */
    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * {@code LocalDateTime} / {@code java.sql.Timestamp} / {@code Date} → epoch 毫秒。
     *
     * <p>契约要求所有时刻字段以 {@code At} 结尾且是 epoch 毫秒 long；
     * DB 里是 {@code DATETIME}，必须在这里转，不能让 Jackson 直接序列化出
     * {@code "2026-10-27T10:50:17"} 这种字符串。</p>
     */
    private static Long toEpochMilli(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant().toEpochMilli();
        }
        if (value instanceof java.util.Date date) {
            return date.getTime();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }
}
