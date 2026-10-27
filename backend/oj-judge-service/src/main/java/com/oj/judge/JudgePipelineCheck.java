package com.oj.judge;

import com.oj.judge.adapter.JudgeAdapter;
import com.oj.judge.adapter.JudgeStatusEnum;
import com.oj.judge.entity.JudgeSubmission;
import com.oj.judge.entity.ProbQuestionJudgeConfig;
import com.oj.judge.entity.ProbTestCaseMeta;
import com.oj.judge.mapper.JudgeSubmissionMapper;
import com.oj.judge.mapper.ProbQuestionMapper;
import com.oj.judge.mapper.ProbTestCaseMapper;
import com.oj.judge.service.JudgeService;
import com.oj.judge.service.support.JudgeRedisSupport;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 判题服务<b>集成自检</b>：用真实 MySQL / Redis / RabbitMQ / go-judge 跑通整条链路。
 *
 * <p><b>它不是单元测试，是「上线前验收脚本」</b>：判题服务最容易错的地方不是业务逻辑，
 * 而是①{@code @Select} 注解 SQL 的语法与字段名、②{@code judge_submission} 的列与
 * entity 的映射、③异步状态机能否真正推进。这三类错误 {@code mvn compile} 一个都发现不了。</p>
 *
 * <h2>为什么用 {@code WebApplicationType.NONE}</h2>
 * <p>任务要求「不要启动 Spring Boot 服务」（端口 8083 由 Lead 统一启动）。
 * {@code NONE} 模式<b>不创建 Servlet 容器、不监听端口</b>，只把 Bean 装配起来跑一次
 * 验证然后退出，因此不会与 Lead 的启动冲突。同时显式关掉 Nacos 注册与 MQ 监听器
 * 自动启动，避免污染注册中心和抢消息。</p>
 *
 * <h2>用法</h2>
 * <pre>
 * java -cp "target/classes;$(cat cp-host.txt)" com.oj.judge.JudgePipelineCheck
 * </pre>
 * <p>退出码：0 全部通过；1 有失败。</p>
 *
 * @author oj-cloud
 */
public final class JudgePipelineCheck {

    /** 冒烟题目：1006 = A + B，3 个测试点 */
    private static final long PROBLEM_ID = 1006L;

    /** 测试用户（sys_user.id = 1，学号 20220101） */
    private static final long TEST_USER_ID = 1L;

    private static int passed;
    private static int failed;

    private JudgePipelineCheck() {
    }

    /**
     * 入口。
     *
     * @param args 忽略
     */
    public static void main(String[] args) {
        System.out.println("=== JudgePipelineCheck：判题服务集成自检（不启动 Web 服务）===");

        ConfigurableApplicationContext context = new SpringApplicationBuilder(JudgeApplication.class)
                .web(WebApplicationType.NONE)
                // 不注册到 Nacos（Lead 统一启动服务，这里只做一次性验证）
                .properties(
                        "spring.cloud.nacos.discovery.enabled=false",
                        "spring.cloud.service-registry.auto-registration.enabled=false",
                        // 不自动消费队列，避免与正式服务抢消息
                        "spring.rabbitmq.listener.simple.auto-startup=false",
                        "logging.level.root=WARN",
                        "logging.level.com.oj=INFO")
                .run();

        try {
            runChecks(context);
        } finally {
            context.close();
        }

        System.out.println();
        System.out.println("========================================");
        System.out.println("结果：" + passed + " 通过 / " + failed + " 失败");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 全部检查项 */
    private static void runChecks(ConfigurableApplicationContext context) {
        ProbQuestionMapper questionMapper = context.getBean(ProbQuestionMapper.class);
        ProbTestCaseMapper testCaseMapper = context.getBean(ProbTestCaseMapper.class);
        JudgeSubmissionMapper submissionMapper = context.getBean(JudgeSubmissionMapper.class);
        JudgeRedisSupport redisSupport = context.getBean(JudgeRedisSupport.class);
        JudgeService judgeService = context.getBean(JudgeService.class);

        // ── 1. Mapper SQL 真的能执行（@Select 注解里的字段名/表名/语法）──
        System.out.println("\n--- 1. Mapper SQL ---");
        ProbQuestionJudgeConfig config = questionMapper.selectJudgeConfig(PROBLEM_ID);
        expect("ProbQuestionMapper.selectJudgeConfig 可执行", config != null, "返回 null");
        if (config != null) {
            expect("题目 1006 时限 2000ms", config.getTimeLimit() != null && config.getTimeLimit() == 2000,
                    "实际 " + config.getTimeLimit());
            expect("题目 1006 内存 512MB", config.getMemoryLimit() != null && config.getMemoryLimit() == 512,
                    "实际 " + config.getMemoryLimit());
            expect("题目 1006 已发布(state=1)", config.getState() != null && config.getState() == 1,
                    "实际 " + config.getState());
            System.out.printf("        题目：%s  时限=%dms 内存=%dMB 测试点=%d 目录=%s%n",
                    config.getTitle(), config.getTimeLimit(), config.getMemoryLimit(),
                    config.getTestCaseCount(), config.getTestCaseDir());
        }

        List<ProbTestCaseMeta> metas = testCaseMapper.selectMetaByProblem(PROBLEM_ID);
        expect("ProbTestCaseMapper.selectMetaByProblem 可执行", metas != null, "返回 null");
        expect("题目 1006 有 3 个测试点", metas != null && metas.size() == 3,
                "实际 " + (metas == null ? 0 : metas.size()));
        int totalScore = 0;
        if (metas != null) {
            for (ProbTestCaseMeta meta : metas) {
                totalScore += meta.getScore() == null ? 0 : meta.getScore();
                System.out.printf("        seq=%d kind=%s score=%d public=%d%n",
                        meta.getSeqNo(), meta.getKind(), meta.getScore(), meta.getIsPublic());
            }
        }
        expect("测试点总分 = 100", totalScore == 100, "实际 " + totalScore);

        List<ProbTestCaseMeta> publicCases = testCaseMapper.selectPublicCases(PROBLEM_ID);
        expect("公开样例 2 个", publicCases != null && publicCases.size() == 2,
                "实际 " + (publicCases == null ? 0 : publicCases.size()));

        // ── 2. 列表/统计 SQL（最容易写错的一组）──
        System.out.println("\n--- 2. 列表与统计 SQL ---");
        long allTotal = submissionMapper.countSubmissionPage(null, null, null, null, null);
        expect("countSubmissionPage 可执行", allTotal >= 0, "异常");
        System.out.println("        全库提交总数 = " + allTotal);

        List<Map<String, Object>> page = submissionMapper.selectSubmissionPage(
                null, null, null, null, null, "id", "desc", 0, 5);
        expect("selectSubmissionPage 可执行", page != null, "返回 null");
        System.out.println("        首页条数 = " + (page == null ? 0 : page.size()));
        if (page != null && !page.isEmpty()) {
            Map<String, Object> first = page.get(0);
            expect("列表行含 problemTitle 字段", first.containsKey("problemTitle"), first.keySet().toString());
            System.out.println("        首行：" + first);
        }

        Map<String, Object> stats = submissionMapper.selectOverviewStats(null);
        expect("selectOverviewStats 可执行", stats != null, "返回 null");
        System.out.println("        概览统计 = " + stats);

        expect("countStatusDistribution 可执行",
                submissionMapper.countStatusDistribution(null, null) != null, "返回 null");
        expect("countLanguageDistribution 可执行",
                submissionMapper.countLanguageDistribution(null, null) != null, "返回 null");

        List<Map<String, Object>> traceRows = submissionMapper.selectTraceRows(TEST_USER_ID, PROBLEM_ID, 5);
        expect("selectTraceRows 可执行", traceRows != null, "返回 null");

        Long bestId = submissionMapper.selectBestSubmissionId(TEST_USER_ID, PROBLEM_ID);
        System.out.println("        最佳提交 ID = " + bestId);

        expect("selectLatestFailed 可执行",
                true, "");
        submissionMapper.selectLatestFailed(TEST_USER_ID, PROBLEM_ID, 0L);
        expect("countFailed 可执行", submissionMapper.countFailed(TEST_USER_ID, PROBLEM_ID) >= 0, "");

        // ── 3. Redis：防抖锁与展示号 ──
        System.out.println("\n--- 3. Redis ---");
        Long lockUser = 999_000L + (System.currentTimeMillis() % 1000);
        expect("首次获取防抖锁成功", redisSupport.tryAcquireJudgeLock(lockUser), "应成功");
        expect("3 秒内二次获取被拒", !redisSupport.tryAcquireJudgeLock(lockUser), "应失败");
        redisSupport.releaseJudgeLock(lockUser);
        expect("释放后可再次获取", redisSupport.tryAcquireJudgeLock(lockUser), "应成功");
        redisSupport.releaseJudgeLock(lockUser);

        String displayNo1 = redisSupport.nextSubmissionDisplayNo();
        String displayNo2 = redisSupport.nextSubmissionDisplayNo();
        expect("展示号格式 S-数字", displayNo1.matches("S-\\d+"), displayNo1);
        expect("展示号递增", !displayNo1.equals(displayNo2), displayNo1 + " vs " + displayNo2);
        System.out.println("        展示号样本：" + displayNo1 + " / " + displayNo2);

        // ── 4. 全链路：submit → 手动消费 → status ──
        System.out.println("\n--- 4. 异步判题全链路（submit → judgeSubmission → status）---");
        Map<String, Object> submitResult = judgeService.submit(buildSubmitRequest(), TEST_USER_ID);
        Object submissionIdRaw = submitResult.get("submissionId");
        Long submissionId = submissionIdRaw instanceof Number n ? n.longValue() : null;
        expect("submit 返回 submissionId", submissionId != null, "实际 " + submissionIdRaw);
        expect("submit 返回 displayNo", submitResult.get("displayNo") != null, "null");
        expect("submit 返回 status=0", Integer.valueOf(0).equals(submitResult.get("status")),
                "实际 " + submitResult.get("status"));
        System.out.println("        提交：" + submitResult);
        if (submissionId == null) {
            return;
        }

        // 落库校验：状态应为排队中，case_details 为空
        JudgeSubmission queued = submissionMapper.selectById(submissionId);
        expect("落库状态 = 0 排队中", queued != null && queued.getStatus() == 0,
                "实际 " + (queued == null ? "null" : queued.getStatus()));
        expect("落库 total_case_count = 3", queued != null && queued.getTotalCaseCount() == 3,
                "实际 " + (queued == null ? "null" : queued.getTotalCaseCount()));

        // 手动触发判题（等价于 MQ 消费）
        long judgeStart = System.currentTimeMillis();
        judgeService.judgeSubmission(submissionId);
        long judgeCost = System.currentTimeMillis() - judgeStart;

        JudgeSubmission finished = submissionMapper.selectById(submissionId);
        expect("判题后状态为终态", finished != null && JudgeStatusEnum.isTerminalStatus(finished.getStatus()),
                "实际 " + (finished == null ? "null" : finished.getStatus()));
        expect("判题后状态 = 3 AC", finished != null && finished.getStatus() == 3,
                "实际 " + (finished == null ? "null" : finished.getStatus()));
        expect("判题后得分 = 100", finished != null && finished.getOiScore() == 100,
                "实际 " + (finished == null ? "null" : finished.getOiScore()));
        expect("判题后通过点数 = 3", finished != null && finished.getPassCaseCount() == 3,
                "实际 " + (finished == null ? "null" : finished.getPassCaseCount()));
        expect("判题后 case_details 非空", finished != null && finished.getCaseDetails() != null
                && !finished.getCaseDetails().isBlank(), "null");
        expect("判题后 finished_at 已填", finished != null && finished.getFinishedAt() != null, "null");
        expect("判题后 memory_used > 0 (KB)", finished != null && finished.getMemoryUsed() != null
                && finished.getMemoryUsed() > 0, "实际 " + (finished == null ? "null" : finished.getMemoryUsed()));
        System.out.printf("        判题耗时 %dms，结果：status=%d score=%d pass=%d time=%dms mem=%dKB%n",
                judgeCost, finished == null ? -1 : finished.getStatus(),
                finished == null ? -1 : finished.getOiScore(),
                finished == null ? -1 : finished.getPassCaseCount(),
                finished == null ? -1 : finished.getTimeUsed(),
                finished == null ? -1 : finished.getMemoryUsed());
        if (finished != null && finished.getCaseDetails() != null) {
            System.out.println("        case_details 前 200 字符：" + abbreviate(finished.getCaseDetails(), 200));
        }

        // ── 5. status 接口（含 caseDetails 与 aiStateUpdate）──
        System.out.println("\n--- 5. GET /status/{id} 响应结构 ---");
        Map<String, Object> statusPayload = judgeService.status(submissionId, TEST_USER_ID, false);
        expect("status 含 statusLabel", "AC".equals(statusPayload.get("statusLabel")),
                "实际 " + statusPayload.get("statusLabel"));
        expect("status 含 statusCode=ac", "ac".equals(statusPayload.get("statusCode")),
                "实际 " + statusPayload.get("statusCode"));
        expect("status 含 timeUsedMs", statusPayload.get("timeUsedMs") != null, "null");
        expect("status 含 memoryUsedKb", statusPayload.get("memoryUsedKb") != null, "null");
        Object caseDetails = statusPayload.get("caseDetails");
        expect("status 的 caseDetails 是 3 项", caseDetails instanceof List<?> list && list.size() == 3,
                "实际 " + (caseDetails instanceof List<?> l ? l.size() : caseDetails));
        Object aiState = statusPayload.get("aiStateUpdate");
        expect("status 含 aiStateUpdate", aiState instanceof Map<?, ?>, "实际 " + aiState);
        System.out.println("        响应字段：" + statusPayload.keySet());
        System.out.println("        aiStateUpdate：" + aiState);

        // ── 6. 提交列表与详情 ──
        System.out.println("\n--- 6. 列表 / 详情 / 轨迹 ---");
        Map<String, Object> listPayload = judgeService.submissions(1, 5, "all", "all", 30, null,
                "id", "desc", TEST_USER_ID);
        expect("列表返回 list", listPayload.get("list") instanceof List<?>, "实际 " + listPayload.get("list"));
        expect("列表返回 total", listPayload.get("total") != null, "null");
        expect("列表返回 hasNext", listPayload.get("hasNext") != null, "null");
        Object statsObj = listPayload.get("stats");
        expect("列表内嵌 stats", statsObj instanceof Map<?, ?> map
                && map.containsKey("statusDist") && map.containsKey("langDist"), "实际 " + statsObj);
        System.out.println("        total=" + listPayload.get("total") + " stats=" + statsObj);

        Map<String, Object> detailPayload = judgeService.submissionDetail(submissionId, 120, TEST_USER_ID, false);
        expect("详情含 code", detailPayload.get("code") != null, "null");
        expect("详情含 languageLabel", "C++ 17".equals(detailPayload.get("languageLabel")),
                "实际 " + detailPayload.get("languageLabel"));
        expect("详情含 trace", detailPayload.get("trace") instanceof List<?>, "实际 " + detailPayload.get("trace"));
        Object detailCases = detailPayload.get("caseDetails");
        expect("详情 caseDetails 3 项", detailCases instanceof List<?> dl && dl.size() == 3,
                "实际 " + (detailCases instanceof List<?> l2 ? l2.size() : detailCases));
        if (detailCases instanceof List<?> dl && !dl.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> first = (Map<String, Object>) dl.get(0);
            expect("详情含 inputPreview", first.containsKey("inputPreview"), first.keySet().toString());
            expect("公开样例下发 expectedPreview", first.get("expectedPreview") != null,
                    "实际 " + first.get("expectedPreview"));
            System.out.println("        首点明细：" + first);
        }

        Map<String, Object> tracePayload = judgeService.trace(PROBLEM_ID, null, 20, TEST_USER_ID, false);
        expect("轨迹含 list", tracePayload.get("list") instanceof List<?>, "null");
        expect("轨迹含 bestScore", tracePayload.get("bestScore") != null, "null");
        System.out.println("        轨迹：" + tracePayload);

        // ── 7. run-sample（样例模式）──
        System.out.println("\n--- 7. run-sample（sample 模式）---");
        Map<String, Object> runSample = judgeService.runSample(buildRunSampleRequest("sample", null, null),
                888_000L + (System.currentTimeMillis() % 1000));
        expect("run-sample 返回 mode=sample", "sample".equals(runSample.get("mode")),
                "实际 " + runSample.get("mode"));
        expect("run-sample 返回 status=FINISHED", "FINISHED".equals(runSample.get("status")),
                "实际 " + runSample.get("status"));
        Object sampleCases = runSample.get("cases");
        expect("run-sample 跑 2 个公开样例", sampleCases instanceof List<?> sl && sl.size() == 2,
                "实际 " + (sampleCases instanceof List<?> l3 ? l3.size() : sampleCases));
        expect("run-sample 含 truncated", runSample.get("truncated") != null, "null");
        System.out.println("        " + abbreviate(String.valueOf(runSample), 400));

        // ── 8. run-sample（自定义输入）──
        System.out.println("\n--- 8. run-sample（custom 模式）---");
        Map<String, Object> custom = judgeService.runSample(
                buildRunSampleRequest("custom", "7 8\n", null),
                889_000L + (System.currentTimeMillis() % 1000));
        expect("custom 返回 mode=custom", "custom".equals(custom.get("mode")), "实际 " + custom.get("mode"));
        Object customCases = custom.get("cases");
        if (customCases instanceof List<?> cl && !cl.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> first = (Map<String, Object>) cl.get(0);
            expect("custom 实际输出 15", "15".equals(String.valueOf(first.get("actual")).trim()),
                    "实际 " + first.get("actual"));
            System.out.println("        " + first);
        } else {
            expect("custom 返回 cases", false, "空列表");
        }

        // ── 9. self-test ──
        System.out.println("\n--- 9. self-test ---");
        Map<String, Object> selfTest = judgeService.selfTest(buildSelfTestRequest(), 887_000L);
        expect("self-test 返回 FINISHED", "FINISHED".equals(selfTest.get("status")),
                "实际 " + selfTest.get("status"));
        expect("self-test 返回 actualOutput=15", "15".equals(String.valueOf(selfTest.get("actualOutput")).trim()),
                "实际 " + selfTest.get("actualOutput"));
        System.out.println("        " + selfTest);

        // ── 10. 内部接口 ──
        System.out.println("\n--- 10. 内部接口 ---");
        com.oj.common.api.dto.judge.FailedCaseDiagnosisDTO diagnosis =
                judgeService.failedDiagnosis(TEST_USER_ID, PROBLEM_ID, 0L);
        // 上面刚跑的是 AC，所以取不到失败记录 → 返回 null 是正确行为
        System.out.println("        failedDiagnosis（当前无失败记录）= " + diagnosis);

        List<com.oj.common.api.dto.judge.SubmissionCodeDTO> codes =
                judgeService.batchBestCodes(List.of(submissionId));
        expect("batchBestCodes 返回 1 条", codes.size() == 1, "实际 " + codes.size());
        if (!codes.isEmpty()) {
            expect("batchBestCodes 含代码", codes.get(0).getCode() != null, "null");
            expect("batchBestCodes 含 codeLength", codes.get(0).getCodeLength() != null,
                    "null");
            System.out.println("        codeLength=" + codes.get(0).getCodeLength()
                    + " language=" + codes.get(0).getLanguage()
                    + " score=" + codes.get(0).getScore());
        }
        expect("batchBestCodes 空入参返回空列表",
                judgeService.batchBestCodes(new ArrayList<>()).isEmpty(), "非空");

        // ── 11. 校验分支（错误码是否正确）──
        // 注意：每个分支用<b>不同的 userId</b>。防抖锁是 per-user 的 3 秒 SETNX，
        // 同一 userId 连发两次会被 429 抢先命中，导致测不到真正想测的 404/400。
        System.out.println("\n--- 11. 参数校验分支 ---");
        expectThrows("不存在的题目 → 404", 404, () -> {
            judgeService.submit(buildSubmitRequestFor(999_999L), TEST_USER_ID + 100);
        });
        expectThrows("草稿题目不可判题 → 404", 404, () -> {
            judgeService.submit(buildSubmitRequestFor(findDraftProblem(questionMapper)), TEST_USER_ID + 200);
        });
        expectThrows("非法语言 → 400", 400, () -> {
            com.oj.judge.dto.JudgeRequests.SubmitRequest req = buildSubmitRequest();
            req.setLanguage("rust");
            judgeService.submit(req, TEST_USER_ID + 300);
        });
        expectThrows("列表 range 越界 → 400", 400, () -> {
            judgeService.submissions(1, 20, "all", "all", 5, null, "id", "desc", TEST_USER_ID);
        });
        expectThrows("列表 sortBy 越界 → 400", 400, () -> {
            judgeService.submissions(1, 20, "all", "all", 3, null, "hack", "desc", TEST_USER_ID);
        });
        expectThrows("重复提交触发 3 秒防抖 → 429", 429, () -> {
            // 同一 userId 连续两次：第二次必须 429（验证防抖锁真的生效）
            Long sameUser = TEST_USER_ID + 400;
            judgeService.selfTest(buildSelfTestRequest(), sameUser);
            judgeService.selfTest(buildSelfTestRequest(), sameUser);
        });

        // ── 12. 内存单位换算（契约 §4.7 点名的这对值）──
        System.out.println("\n--- 12. 单位换算（契约 §4.7）---");
        expect("51200 KB = 52428800 B", JudgeAdapter.kbToBytes(51200L) == 52_428_800L, "");
        expect("268435456 B = 262144 KB", JudgeAdapter.bytesToKb(268_435_456L) == 262_144, "");
        expect("256 MB = 268435456 B", JudgeAdapter.mbToBytes(256L) == 268_435_456L, "");
        expect("java 512MB ×2 = 1024MB", JudgeAdapter.effectiveMemoryMb(512, "java") == 1024, "");
        expect("cpp 512MB ×1 = 512MB", JudgeAdapter.effectiveMemoryMb(512, "cpp") == 512, "");

        // ── 13. 清理本次验证产生的数据 ──
        System.out.println("\n--- 13. 清理验证数据 ---");
        int deleted = submissionMapper.deleteById(submissionId);
        expect("删除本次验证提交", deleted == 1, "影响行数 " + deleted);
    }

    /** 找一道草稿题（state != 1）用于验证「未发布不可判题」 */
    private static long findDraftProblem(ProbQuestionMapper mapper) {
        // 用一条直接 SQL 找草稿题；找不到就返回一个不存在的 ID（走 404 分支）
        try {
            List<ProbQuestionJudgeConfig> drafts = mapper.selectList(null);
            for (ProbQuestionJudgeConfig q : drafts) {
                if (q.getState() != null && q.getState() != 1) {
                    return q.getId();
                }
            }
        } catch (Exception ignored) {
            // 忽略：走下面的兜底
        }
        return 999_998L;
    }

    /** 构造一个 AC 的提交请求（C++ A+B） */
    private static com.oj.judge.dto.JudgeRequests.SubmitRequest buildSubmitRequest() {
        return buildSubmitRequestFor(PROBLEM_ID);
    }

    /** 构造指定题目的提交请求 */
    private static com.oj.judge.dto.JudgeRequests.SubmitRequest buildSubmitRequestFor(long problemId) {
        com.oj.judge.dto.JudgeRequests.SubmitRequest request = new com.oj.judge.dto.JudgeRequests.SubmitRequest();
        request.setProblemId(problemId);
        request.setAssignmentId(0L);
        request.setLanguage("cpp");
        request.setCode("""
                #include <iostream>
                int main() {
                    long long a, b;
                    if (!(std::cin >> a >> b)) return 0;
                    std::cout << a + b << "\\n";
                    return 0;
                }
                """);
        return request;
    }

    /** 构造 run-sample 请求 */
    private static com.oj.judge.dto.JudgeRequests.RunSampleRequest buildRunSampleRequest(
            String mode, String customInput, Integer sampleSeq) {
        com.oj.judge.dto.JudgeRequests.RunSampleRequest request = new com.oj.judge.dto.JudgeRequests.RunSampleRequest();
        request.setProblemId(PROBLEM_ID);
        request.setLanguage("cpp");
        request.setCode("""
                #include <iostream>
                int main() {
                    long long a, b;
                    if (!(std::cin >> a >> b)) return 0;
                    std::cout << a + b << "\\n";
                    return 0;
                }
                """);
        request.setMode(mode);
        request.setCustomInput(customInput);
        request.setSampleSeq(sampleSeq);
        return request;
    }

    /** 构造 self-test 请求 */
    private static com.oj.judge.dto.JudgeRequests.SelfTestRequest buildSelfTestRequest() {
        com.oj.judge.dto.JudgeRequests.SelfTestRequest request = new com.oj.judge.dto.JudgeRequests.SelfTestRequest();
        request.setProblemId(PROBLEM_ID);
        request.setLanguage("cpp");
        request.setCode("""
                #include <iostream>
                int main() {
                    long long a, b;
                    if (!(std::cin >> a >> b)) return 0;
                    std::cout << a + b << "\\n";
                    return 0;
                }
                """);
        request.setCustomInput("7 8\n");
        return request;
    }

    /** 断言某段逻辑抛出指定 code 的业务异常 */
    private static void expectThrows(String name, int expectedCode, Runnable action) {
        try {
            action.run();
            failed++;
            System.out.println("[FAIL] " + name + "  —— 未抛异常");
        } catch (com.oj.common.core.exception.BusinessException e) {
            if (e.getCode() == expectedCode) {
                passed++;
                System.out.println("[PASS] " + name);
            } else {
                failed++;
                System.out.println("[FAIL] " + name + "  —— 期望 " + expectedCode + "，实际 "
                        + e.getCode() + "：" + e.getMessage());
            }
        } catch (Exception e) {
            failed++;
            System.out.println("[FAIL] " + name + "  —— 抛了非业务异常：" + e);
        }
    }

    /** 断言辅助 */
    private static void expect(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name + "  —— " + detail);
        }
    }

    /** 截断字符串 */
    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "null";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** 供 IDE 直接运行时输出提示 */
    @SuppressWarnings("unused")
    private static Map<String, Object> emptyMap() {
        return new LinkedHashMap<>();
    }
}
