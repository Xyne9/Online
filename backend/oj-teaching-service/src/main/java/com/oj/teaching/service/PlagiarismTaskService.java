package com.oj.teaching.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.common.api.dto.ai.AiPlagiarismCheckDTO;
import com.oj.common.api.dto.ai.AiPlagiarismResultDTO;
import com.oj.common.api.dto.judge.SubmissionCodeDTO;
import com.oj.common.api.feign.AiInnerFeignClient;
import com.oj.common.api.feign.JudgeInnerFeignClient;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.redis.RedisKeys;
import com.oj.common.web.context.UserContext;
import com.oj.teaching.dto.PlagiarismRequests;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import static com.oj.teaching.service.PlagiarismAccess.*;
import static com.oj.teaching.service.PlagiarismQueryService.*;

@Service
public class PlagiarismTaskService {
    private final PlagiarismRepository repository;
    private final PlagiarismAccess access;
    private final PlagiarismReviewService reviews;
    private final PlagiarismQueryService queries;
    private final PlagiarismProgressService progress;
    private final CodeSimilarityAnalyzer analyzer;
    private final CodeSimilarityAlignment alignment;
    private final JudgeInnerFeignClient judge;
    private final AiInnerFeignClient ai;
    private final ObjectMapper json;
    private final StringRedisTemplate redis;
    private final ThreadPoolTaskExecutor executor;
    private final TransactionTemplate transactions;

    public PlagiarismTaskService(PlagiarismRepository repository, PlagiarismAccess access, PlagiarismReviewService reviews,
            PlagiarismQueryService queries, PlagiarismProgressService progress, CodeSimilarityAnalyzer analyzer, CodeSimilarityAlignment alignment,
            JudgeInnerFeignClient judge, AiInnerFeignClient ai, ObjectMapper json, StringRedisTemplate redis,
            @Qualifier("plagiarismExecutor") ThreadPoolTaskExecutor executor, PlatformTransactionManager manager) {
        this.repository = repository; this.access = access; this.reviews = reviews; this.queries = queries; this.progress = progress;
        this.analyzer = analyzer; this.alignment = alignment; this.judge = judge; this.ai = ai; this.json = json; this.redis = redis; this.executor = executor;
        this.transactions = new TransactionTemplate(manager);
    }
    private record Prepared(long id, long assignment, List<Long> problems, String language, double threshold, String channel,
                            long createdAt, boolean keepAudit) { }
    private record Sample(SubmissionCodeDTO code, CodeSimilarityAnalyzer.Fingerprint fingerprint) { }
    private static class Pair {
        final Sample a, b; final double struct; AiPlagiarismResultDTO.PairResultDTO semantic; String model;
        Pair(Sample a, Sample b, double struct) { this.a = a; this.b = b; this.struct = struct; }
    }
    private record RunResult(int samples, int totalPairs, long stage1Ms, int suspicious, int high, List<Long> reports) { }

    public Map<String, Object> create(PlagiarismRequests.Task request) {
        var task = prepare(request, false); enqueue(task); return created(task);
    }
    public Map<String, Object> check(PlagiarismRequests.Check request) {
        if (request == null || request.problemId() == null) throw BusinessException.badRequest("单题查重须指定problemId");
        var task = prepare(new PlagiarismRequests.Task(request.assignmentId(), List.of(request.problemId()), request.language(), request.stage1SimThreshold(), request.stage2ChannelCode(), "problem"), false);
        RunResult result = run(task, true);
        var records = repository.reports(task.assignment, request.problemId()).stream().filter(r -> r.get("task_id") != null && number(r, "task_id") == task.id)
                .map(r -> queries.summary(r, Map.of())).toList();
        return view("taskId", task.id, "totalValidSamples", result.samples, "totalPairsCompared", result.totalPairs, "stage1CostMs", result.stage1Ms,
                "stage1SuspiciousCount", result.suspicious, "stage2HighRiskCount", result.high, "records", records);
    }
    public Map<String, Object> rerun(long id, boolean keepAudit) {
        var previous = access.task(id);
        if (!Set.of("DONE", "FAILED").contains(previous.get("state"))) throw BusinessException.conflict("任务未结束，不能重跑");
        var request = new PlagiarismRequests.Task(number(previous, "assignment_id"), problemIds(previous.get("problem_ids")), (String)previous.get("language"),
                decimal(previous, "stage1_threshold"), (String)previous.get("stage2_channel"), "assignment");
        var task = prepare(request, keepAudit); enqueue(task);
        return view("taskId", id, "newTaskId", task.id, "state", "PENDING", "estimatedMs", 40000);
    }
    private Prepared prepare(PlagiarismRequests.Task request, boolean keepAudit) {
        if (request == null || request.assignmentId() == null || request.assignmentId() < 1) throw BusinessException.badRequest("须指定教学活动");
        if (request.language() != null && !request.language().isBlank() && !Set.of("cpp", "java", "python").contains(request.language())) throw BusinessException.badRequest("查重语言无效");
        double threshold = request.stage1SimThreshold() == null ? 75 : request.stage1SimThreshold();
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 100) throw BusinessException.badRequest("结构阈值须在0至100之间");
        if (request.scope() != null && !Set.of("problem", "assignment").contains(request.scope())) throw BusinessException.badRequest("查重范围无效");
        String language = request.language() == null || request.language().isBlank() ? null : request.language();
        String channel = request.stage2ChannelCode() == null || request.stage2ChannelCode().isBlank() ? null : request.stage2ChannelCode();
        if (channel != null && channel.length() > 32) throw BusinessException.badRequest("模型通道标识过长");
        long uid = UserContext.userId();
        return transactions.execute(status -> {
            var assignment = access.assignment(request.assignmentId(), true); List<Long> allowed = problemIds(assignment.get("problem_ids"));
            List<Long> problems = request.problemIds() == null || request.problemIds().isEmpty() ? allowed : request.problemIds();
            if (problems.isEmpty() || problems.stream().anyMatch(p -> p == null || !allowed.contains(p))) throw BusinessException.badRequest("查重题目不属于该教学活动");
            problems = problems.stream().distinct().sorted().toList();
            if ("problem".equals(request.scope()) && problems.size() != 1) throw BusinessException.badRequest("单题范围须只选择一道题");
            for (var active : repository.activeTasks(request.assignmentId())) {
                // Overlapping scopes must not race to replace reports or their score-review ownership.
                boolean overlaps = problemIds(active.get("problem_ids")).stream().anyMatch(problems::contains);
                if (overlaps && (language == null || active.get("language") == null || language.equals(active.get("language")))) throw BusinessException.conflict("该题目已有进行中的查重任务");
            }
            long now = System.currentTimeMillis();
            long id = repository.insertTask(nextNo("plagiarism_task", "PL-"), request.assignmentId(), encode(problems), language, threshold, channel, uid, now);
            return new Prepared(id, request.assignmentId(), problems, language, threshold, channel, now, keepAudit);
        });
    }
    private void enqueue(Prepared task) {
        progress.publish(task.id);
        try { executor.execute(() -> { try { run(task, false); } catch (Exception ignored) { /* run persists FAILED before returning. */ } }); }
        catch (org.springframework.core.task.TaskRejectedException e) {
            repository.taskProgress(task.id, "FAILED", 0, 0, 0, 0, 0, null, System.currentTimeMillis(), "查重队列已满，请稍后重试");
            progress.publish(task.id); throw BusinessException.tooManyRequests("查重队列已满，请稍后重试");
        }
    }
    private RunResult run(Prepared task, boolean synchronous) {
        long started = System.currentTimeMillis(); int total = 0, complete = 0, suspicious = 0, high = 0, percent = 0;
        try {
            update(task, "STAGE1", 0, 0, 0, 0, 0, started, null, null);
            List<Pair> pairs = new ArrayList<>(); int sampleCount = 0;
            Map<Long, List<Sample>> samplesByProblem = new LinkedHashMap<>();
            long fingerprintNanos = 0;
            for (Long problem : task.problems) {
                List<Long> ids = repository.sampleIds(task.assignment, problem);
                var response = ids.isEmpty() ? null : judge.getBatchBestCodes(ids);
                if (response != null && (response.getCode() != 200 || response.getData() == null)) throw BusinessException.badGateway("无法取得查重样本");
                if (!ids.isEmpty() && response == null) throw BusinessException.badGateway("判题服务未返回查重样本");
                List<Sample> samples = new ArrayList<>(); Set<Long> users = new HashSet<>();
                if (response != null) for (SubmissionCodeDTO code : response.getData()) {
                    if (code.getSubmissionId() == null || !ids.contains(code.getSubmissionId()) || code.getUserId() == null || !Objects.equals(code.getProblemId(), problem)
                            || code.getScore() == null || code.getScore() < 60 || code.getCode() == null || code.getCode().isBlank()
                            || (task.language != null && !task.language.equals(code.getLanguage())) || !users.add(code.getUserId())) continue;
                    long beforeFingerprint = System.nanoTime();
                    samples.add(new Sample(code, analyzer.fingerprint(code.getCode(), code.getLanguage())));
                    fingerprintNanos += System.nanoTime() - beforeFingerprint;
                }
                samples.sort(Comparator.comparing((Sample s) -> s.code.getSubmittedAt(), Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(s -> s.code.getSubmissionId()));
                samplesByProblem.put(problem, samples); sampleCount += samples.size();
                for (int i = 0; i < samples.size(); i++) for (int j = i + 1; j < samples.size(); j++) if (Objects.equals(samples.get(i).code.getLanguage(), samples.get(j).code.getLanguage())) total++;
            }
            if (total == 0) throw BusinessException.badRequest("有效样本不足：同题同语言至少需要两名得分60以上学生");
            if (synchronous && total > 6) throw BusinessException.badRequest("同步查重仅支持6对以内，请使用异步查重任务");
            int evaluated = 0; long lastFlush = System.currentTimeMillis(); int lastPercent = 0;
            long comparisonNanos = 0;
            for (var samples : samplesByProblem.values()) for (int i = 0; i < samples.size(); i++) for (int j = i + 1; j < samples.size(); j++) {
                Sample a = samples.get(i), b = samples.get(j); if (!Objects.equals(a.code.getLanguage(), b.code.getLanguage())) continue;
                long beforeComparison = System.nanoTime();
                double struct = analyzer.compare(a.fingerprint, b.fingerprint).overall(); Pair pair = new Pair(a, b, struct); pairs.add(pair);
                comparisonNanos += System.nanoTime() - beforeComparison;
                if (struct >= task.threshold) suspicious++; else complete++;
                percent = ++evaluated * 50 / total;
                if (percent - lastPercent >= 5 || System.currentTimeMillis() - lastFlush >= 2000) {
                    update(task, "STAGE1", percent, total, complete, suspicious, 0, started, null, null); lastPercent = percent; lastFlush = System.currentTimeMillis();
                }
            }
            long stage1Ms = (fingerprintNanos + comparisonNanos) / 1_000_000;
            update(task, "STAGE2", 50, total, complete, suspicious, 0, started, null, null);
            int semanticDone = 0;
            for (Pair pair : pairs) {
                if (pair.struct < task.threshold) continue;
                var request = semanticRequest(task, pair); var response = ai.checkCodeSemanticSimilarity(request);
                if (response == null || response.getCode() != 200 || response.getData() == null || response.getData().getPairs() == null || response.getData().getPairs().size() != 1)
                    throw BusinessException.badGateway("语义模型未返回对应配对结论");
                var data = response.getData(); var result = data.getPairs().get(0);
                if (!Objects.equals(result.getSubmissionId(), pair.b.code.getSubmissionId()) || result.getSimilarity() == null || !Double.isFinite(result.getSimilarity())
                        || result.getSimilarity() < 0 || result.getSimilarity() > 1 || result.getReason() == null || result.getReason().isBlank() || data.getModelName() == null || data.getModelName().isBlank())
                    throw BusinessException.badGateway("语义模型配对结论格式无效");
                validateSegments(pair, result);
                pair.semantic = result; pair.model = data.getModelName(); complete++; semanticDone++;
                if (queries.level(pair.struct, result.getSimilarity() * 100).equals("high")) high++;
                percent = 50 + (suspicious == 0 ? 45 : semanticDone * 45 / suspicious);
                update(task, "STAGE2", percent, total, complete, suspicious, high, started, null, null);
            }
            final int countHigh = high;
            List<Long> reports = transactions.execute(status -> persist(task, pairs));
            for (long id : reports) {
                var report = repository.report(id);
                if (report != null && queries.level(decimal(report,"stage1_algo_sim"), decimal(report,"stage2_ai_sim")).equals("high")) {
                    var summary = queries.summary(report, Map.of());
                    progress.pair(task.id, view("reportId", id, "displayNo", report.get("display_no"), "overallSim", summary.get("overallSim"), "level", "high", "userA", summary.get("userA"), "userB", summary.get("userB")));
                }
            }
            update(task, "DONE", 100, total, total, suspicious, high, started, System.currentTimeMillis(), null);
            return new RunResult(sampleCount, total, stage1Ms, suspicious, countHigh, reports);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(PlagiarismTaskService.class).error("查重任务失败：taskId={}", task.id, e);
            String error = e instanceof BusinessException ? e.getMessage() : "查重处理失败，请查看服务日志";
            update(task, "FAILED", percent, total, complete, suspicious, high, started, System.currentTimeMillis(), error.length() > 512 ? error.substring(0, 512) : error);
            if (e instanceof BusinessException business) throw business;
            throw BusinessException.badGateway(error);
        }
    }
    private List<Long> persist(Prepared task, List<Pair> pairs) {
        repository.assignment(task.assignment, true);
        // Replace selected scopes only after every required model call succeeded.
        var previous = repository.reports(task.assignment, null).stream().filter(r -> task.problems.contains(number(r, "problem_id")) && (task.language == null || task.language.equals(r.get("language")))).toList();
        Map<String, Map<String, Object>> preserved = new HashMap<>();
        for (var old : previous) {
            if (task.keepAudit && number(old, "audit_status") != 0) preserved.put(pairKey(number(old,"sub_a_id"), number(old,"sub_b_id")), old);
            else { reviews.restoreBeforeReset(old); repository.audit(number(old,"id"), 0, null, null, null); repository.deleteReport(number(old,"id")); }
        }
        List<Long> ids = new ArrayList<>();
        for (Pair pair : pairs) {
            var old = preserved.remove(pairKey(pair.a.code.getSubmissionId(), pair.b.code.getSubmissionId()));
            Double sem = pair.semantic == null ? null : Math.round(pair.semantic.getSimilarity() * 10000) / 100.0;
            long id;
            if (old != null) { id = number(old,"id"); repository.refreshReport(id, task.id, pair.struct, sem, pair.model, pair.semantic == null ? null : pair.semantic.getReason()); }
            else id = repository.insertReport(nextNo(RedisKeys.SEQ_PLAGIARISM, "P-"), task.id, task.assignment, pair.a.code.getProblemId(), pair.a.code.getLanguage(), pair.a.code.getUserId(), pair.a.code.getSubmissionId(),
                    pair.b.code.getUserId(), pair.b.code.getSubmissionId(), pair.struct, sem, pair.model, pair.semantic == null ? null : pair.semantic.getReason());
            ids.add(id);
            if (pair.semantic != null && pair.semantic.getSegments() != null) {
                int seq = 0;
                var rows = alignment.align(pair.a.code.getCode(), pair.b.code.getCode(), pair.a.code.getLanguage());
                for (var segment : pair.semantic.getSegments()) {
                    int count = (int)rows.stream().filter(r -> (r.aLineNo() != null && r.aLineNo() >= segment.getAFrom() && r.aLineNo() <= segment.getATo())
                            || (r.bLineNo() != null && r.bLineNo() >= segment.getBFrom() && r.bLineNo() <= segment.getBTo())).count();
                    repository.insertSegment(id, ++seq, segment.getSegName(), segment.getAFrom(), segment.getATo(), segment.getBFrom(), segment.getBTo(), count, sem, pair.semantic.getReason());
                }
            }
        }
        // Audited pairs with a now-cleared score no longer qualify as samples; keep their review visible.
        for (var old : preserved.values()) { repository.moveReport(number(old,"id"), task.id); ids.add(number(old,"id")); }
        return ids;
    }
    private AiPlagiarismCheckDTO semanticRequest(Prepared task, Pair pair) {
        var request = new AiPlagiarismCheckDTO(); request.setTaskId(Long.toString(task.id)); request.setAssignmentId(task.assignment);
        request.setProblemId(pair.a.code.getProblemId()); request.setBaseSubmissionId(pair.a.code.getSubmissionId()); request.setBaseUserId(pair.a.code.getUserId());
        request.setBaseCode(pair.a.code.getCode()); request.setLanguage(pair.a.code.getLanguage()); request.setChannelCode(task.channel); request.setThreshold(.85);
        var candidate = new AiPlagiarismCheckDTO.CandidateDTO(); candidate.setSubmissionId(pair.b.code.getSubmissionId()); candidate.setUserId(pair.b.code.getUserId());
        candidate.setUserName(pair.b.code.getUserName()); candidate.setCode(pair.b.code.getCode()); request.setCandidates(List.of(candidate)); return request;
    }
    private void validateSegments(Pair pair, AiPlagiarismResultDTO.PairResultDTO result) {
        if (result.getSegments() == null || result.getSegments().size() > 6 || (result.getSimilarity() >= .85 && result.getSegments().isEmpty()))
            throw BusinessException.badGateway("语义鉴定缺少真实对应行段");
        int aLines = pair.a.code.getCode().split("\\R", -1).length, bLines = pair.b.code.getCode().split("\\R", -1).length;
        for (var segment : result.getSegments()) if (segment == null || segment.getSegName() == null || segment.getSegName().isBlank() || segment.getSegName().length() > 64
                || segment.getAFrom() == null || segment.getATo() == null || segment.getBFrom() == null || segment.getBTo() == null
                || segment.getAFrom() < 1 || segment.getATo() < segment.getAFrom() || segment.getATo() > aLines
                || segment.getBFrom() < 1 || segment.getBTo() < segment.getBFrom() || segment.getBTo() > bLines)
            throw BusinessException.badGateway("语义鉴定代码行段超出原始源码");
    }
    private void update(Prepared task, String state, int percent, int total, int compared, int suspicious, int high, Long started, Long finished, String error) {
        repository.taskProgress(task.id, state, percent, total, compared, suspicious, high, started, finished, error); progress.publish(task.id);
    }
    private String nextNo(String business, String prefix) {
        Long value = redis.opsForValue().increment(RedisKeys.seq(business));
        if (value == null) throw BusinessException.badGateway("展示号序列不可用");
        return prefix + String.format(Locale.ROOT, "%04d", value);
    }
    private Map<String, Object> created(Prepared task) { var stored = repository.task(task.id); return view("taskId", task.id, "displayNo", stored.get("display_no"), "state", "PENDING", "estimatedMs", 40000, "createdAt", task.createdAt); }
    private List<Long> problemIds(Object raw) {
        try { return json.readValue(raw.toString(), new TypeReference<List<Long>>() { }); }
        catch (Exception e) { throw BusinessException.badRequest("教学活动题目列表格式无效"); }
    }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String pairKey(long a, long b) { return Math.min(a,b) + ":" + Math.max(a,b); }
}
