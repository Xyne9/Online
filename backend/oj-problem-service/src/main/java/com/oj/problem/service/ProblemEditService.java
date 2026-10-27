package com.oj.problem.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.web.context.UserContext;
import com.oj.problem.dto.*;
import com.oj.problem.entity.*;
import com.oj.problem.mapper.*;
import com.oj.problem.support.JsonSupport;
import com.oj.problem.support.Times;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 教师题库写侧。题目行锁统一串行化版本、发布和测试点全量对账。 */
@Service
public class ProblemEditService {
    private static final Set<String> KINDS = Set.of("sample", "normal", "edge", "stress");
    private final ProbQuestionMapper questions;
    private final ProbTestCaseMapper cases;
    private final ProbKnowledgePointMapper knowledge;
    private final ProblemWriteMapper writes;
    private final TestCaseStorage storage;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public ProblemEditService(ProbQuestionMapper questions, ProbTestCaseMapper cases,
                              ProbKnowledgePointMapper knowledge, ProblemWriteMapper writes,
                              TestCaseStorage storage, PlatformTransactionManager manager,
                              ObjectMapper json) {
        this.questions = questions;
        this.cases = cases;
        this.knowledge = knowledge;
        this.writes = writes;
        this.storage = storage;
        this.transactions = new TransactionTemplate(manager);
        this.json = json;
    }

    public Map<String, Object> edit(Long id, Integer version) {
        ProbQuestion question = owned(id, false);
        checkVersion(question, version, false);
        List<Map<String, Object>> testCases = caseViews(cases.selectByProblemId(id), true);
        int total = testCases.stream().mapToInt(tc -> (Integer) tc.get("score")).sum();
        ProbKnowledgePoint kp = question.getKnowledgePointId() == null ? null
                : knowledge.selectById(question.getKnowledgePointId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("displayNo", "#" + id);
        result.put("title", question.getTitle());
        result.put("difficulty", question.getDifficulty());
        result.put("knowledgePointId", question.getKnowledgePointId());
        result.put("knowledgePoint", kp == null ? "未分类" : kp.getName());
        result.put("tags", JsonSupport.toStringList(question.getTags()));
        result.put("timeLimitMs", question.getTimeLimit());
        result.put("memoryLimitMb", question.getMemoryLimit());
        result.put("aiEnabled", question.getAiEnabled());
        result.put("aiQuotaLimit", question.getAiQuotaLimit());
        result.put("aiStages", JsonSupport.toIntList(question.getAiStages()));
        result.put("statement", question.getContent());
        result.put("officialSolution", question.getOfficialSolution());
        result.put("codeTemplates", JsonSupport.toStringMap(question.getCodeTemplates()));
        result.put("state", question.getState());
        result.put("version", question.getVersion());
        result.put("savedAt", Times.toEpochMs(question.getUpdateTime()));
        result.put("testCases", testCases);
        result.put("totalScore", total);
        String hint = publishHint(question, testCases.size(), total);
        result.put("canPublish", hint == null);
        result.put("publishHint", hint);
        return result;
    }

    @Transactional
    public Map<String, Object> create(ProblemWriteRequest request) {
        teacher();
        ProbQuestion question = newQuestion(request);
        questions.insert(question);
        question.setTestCaseDir("/data/testcases/" + question.getId() + "/");
        questions.updateById(question);
        syncKnowledge(question);
        // 创建仅生成草稿；测试点由其独立端点维护，导入在同一事务显式调用 batch。
        return created(question);
    }

    @Transactional
    public Map<String, Object> update(Long id, ProblemWriteRequest request) {
        ProbQuestion question = owned(id, true);
        editable(question);
        checkVersion(question, request == null ? null : request.getVersion(), true);
        validate(request, false);
        apply(question, request);
        bump(question);
        questions.updateById(question);
        if (request.isKnowledgePointProvided() && question.getKnowledgePointId() == null)
            questions.update(null, new UpdateWrapper<ProbQuestion>().eq("id", id)
                    .eq("creator_id", question.getCreatorId()).set("knowledge_point_id", null));
        if (request.isKnowledgePointProvided()) syncKnowledge(question);
        return Map.of("id", id, "version", question.getVersion(), "savedAt", System.currentTimeMillis(),
                "state", question.getState());
    }

    @Transactional
    public Map<String, Object> delete(Long id) {
        ProbQuestion question = owned(id, true);
        if (question.getState() != 0 || writes.countSubmissions(id) > 0) {
            throw BusinessException.conflict("已有提交或非草稿题目不可删除，请改用归档");
        }
        LocalDateTime now = Times.nowInDbZone();
        question.setDeletedAt(now);
        questions.updateById(question);
        int tombstone = Math.min(0, writes.minimumSeq(id)) - 1;
        for (ProbTestCase tc : cases.selectByProblemId(id)) {
            writes.setCaseSeq(tc.getId(), id, tombstone--);
            cases.update(null, new UpdateWrapper<ProbTestCase>().eq("id", tc.getId())
                    .eq("problem_id", id).set("deleted_at", now));
        }
        return Map.of("deleted", true, "id", id);
    }

    @Transactional
    public Map<String, Object> state(Long id, ProblemStateRequest request) {
        ProbQuestion question = owned(id, true);
        if (request == null || request.getAction() == null) throw BusinessException.badRequest("action 必填");
        if (request.getReason() != null && request.getReason().length() > 100)
            throw BusinessException.badRequest("归档原因最多100字符");
        int from = question.getState();
        int target;
        switch (request.getAction()) {
            case "publish" -> {
                if (from != 0) throw BusinessException.conflict("只有草稿题目可以发布");
                List<ProbTestCase> list = cases.selectLockedByProblemId(id);
                String hint = publishHint(question, list.size(), totalScore(list));
                if (hint != null) throw BusinessException.badRequest(hint);
                storage.awaitReady(id);
                target = 1;
            }
            case "archive" -> {
                if (from != 1) throw BusinessException.conflict("只有已发布题目可以归档");
                target = 2;
            }
            case "restore" -> {
                if (from != 2) throw BusinessException.conflict("只有归档题目可以恢复");
                storage.awaitReady(id);
                target = 1;
            }
            default -> throw BusinessException.badRequest("action 只支持 publish/archive/restore");
        }
        bump(question);
        question.setState(target);
        questions.updateById(question);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("fromState", from);
        result.put("toState", target);
        result.put("version", question.getVersion());
        result.put("publishedAt", target == 1 ? System.currentTimeMillis() : null);
        return result;
    }

    public Map<String, Object> listCases(Long id, boolean withContent) {
        teacherOrStudentRead(id, withContent);
        List<ProbTestCase> list = cases.selectByProblemId(id);
        List<Map<String, Object>> summary = new ArrayList<>();
        for (String kind : List.of("sample", "normal", "edge", "stress")) {
            List<ProbTestCase> group = list.stream().filter(tc -> kind.equals(normalizeKind(tc.getKind()))).toList();
            summary.add(Map.of("kind", kind, "count", group.size(), "score", totalScore(group)));
        }
        return Map.of("list", caseViews(list, withContent), "total", list.size(),
                "totalScore", totalScore(list), "kindSummary", summary);
    }

    @Transactional
    public Map<String, Object> addCase(Long id, TestCaseWriteRequest request) {
        ProbQuestion question = owned(id, true);
        editable(question);
        List<ProbTestCase> old = cases.selectByProblemId(id);
        List<TestCaseWriteRequest> items = retainedItems(old);
        if (request == null) throw BusinessException.badRequest("测试点内容必填");
        request.setId(null);
        request.setSeqNo(items.size() + 1);
        items.add(request);
        Map<String, Object> result = reconcile(question, items, null);
        List<ProbTestCase> after = cases.selectByProblemId(id);
        ProbTestCase inserted = after.get(after.size() - 1);
        return Map.of("id", inserted.getId(), "seqNo", inserted.getSeqNo(),
                "totalScore", result.get("totalScore"), "version", question.getVersion());
    }

    @Transactional
    public Map<String, Object> updateCase(Long caseId, TestCaseWriteRequest request) {
        ProbTestCase target = requireCase(caseId);
        ProbQuestion question = owned(target.getProblemId(), true);
        editable(question);
        if (request == null) throw BusinessException.badRequest("测试点更新内容必填");
        List<ProbTestCase> current = cases.selectLockedByProblemId(question.getId());
        if (current.stream().noneMatch(tc -> Objects.equals(tc.getId(), caseId)))
            throw BusinessException.notFound("测试点不存在");
        List<TestCaseWriteRequest> items = retainedItems(current);
        for (int i = 0; i < items.size(); i++) if (Objects.equals(items.get(i).getId(), caseId)) {
            request.setId(caseId);
            request.setSeqNo(i + 1);
            items.set(i, request);
        }
        Map<String, Object> result = reconcile(question, items, null);
        return Map.of("id", caseId, "seqNo", target.getSeqNo(), "totalScore", result.get("totalScore"),
                "version", question.getVersion());
    }

    @Transactional
    public Map<String, Object> deleteCase(Long caseId) {
        ProbTestCase target = requireCase(caseId);
        ProbQuestion question = owned(target.getProblemId(), true);
        editable(question);
        List<TestCaseWriteRequest> items = retainedItems(cases.selectLockedByProblemId(question.getId()));
        if (items.stream().noneMatch(item -> Objects.equals(item.getId(), caseId)))
            throw BusinessException.notFound("测试点不存在");
        if (items.size() <= 1) throw BusinessException.conflict("至少保留1个测试点");
        items.removeIf(item -> Objects.equals(item.getId(), caseId));
        for (int i = 0; i < items.size(); i++) items.get(i).setSeqNo(i + 1);
        Map<String, Object> result = reconcile(question, items, null);
        return Map.of("deleted", true, "remaining", items.size(), "totalScore", result.get("totalScore"),
                "version", question.getVersion());
    }

    @Transactional
    public Map<String, Object> batch(Long id, TestCaseBatchRequest request) {
        ProbQuestion question = owned(id, true);
        editable(question);
        checkVersion(question, request == null ? null : request.getVersion(), true);
        return reconcile(question, request.getItems(), request.getTotalScore());
    }

    private Map<String, Object> reconcile(ProbQuestion question, List<TestCaseWriteRequest> items,
                                          Integer assertedTotal) {
        if (items == null || items.isEmpty()) throw BusinessException.badRequest("至少保留1个测试点");
        List<ProbTestCase> originalList = cases.selectLockedByProblemId(question.getId());
        Map<Long, ProbTestCase> originals = originalList.stream().collect(Collectors.toMap(
                ProbTestCase::getId, Function.identity()));
        if (assertedTotal != null && assertedTotal != totalScore(originalList))
            throw BusinessException.conflict("totalScore 与当前数据不一致，请刷新后重试");
        Set<Long> ids = new HashSet<>();
        Set<Integer> sequences = new HashSet<>();
        List<ProbTestCase> next = new ArrayList<>();
        Map<Long, String> inputs = new HashMap<>();
        Map<Long, String> outputs = new HashMap<>();
        List<String[]> changes = new ArrayList<>();
        int samples = 0;
        for (TestCaseWriteRequest item : items) {
            if (item == null || item.getSeqNo() == null || item.getSeqNo() < 1
                    || item.getSeqNo() > items.size() || !sequences.add(item.getSeqNo()))
                throw BusinessException.badRequest("seqNo 必须从1起连续且不能重复");
            ProbTestCase old = item.getId() == null ? null : originals.get(item.getId());
            if (item.getId() != null && (old == null || !ids.add(item.getId())))
                throw BusinessException.badRequest("测试点ID重复或不属于当前题目");
            ProbTestCase tc = mergeCase(question.getId(), item, old);
            if ("sample".equals(tc.getKind())) samples++;
            next.add(tc);
            changes.add(new String[]{replacement(item.getInput(), old, true),
                    replacement(item.getOutput(), old, false)});
        }
        if (samples > 3) throw BusinessException.conflict("每道题最多3个样例测试点");
        // 先全部移到尚未使用的负数序号，消除重排的唯一键冲突；事务失败可整体回滚。
        int temporary = Math.min(0, writes.minimumSeq(question.getId())) - 1;
        for (ProbTestCase old : originalList) writes.setCaseSeq(old.getId(), question.getId(), temporary--);
        int inserted = 0;
        int updated = 0;
        for (int i = 0; i < next.size(); i++) {
            ProbTestCase tc = next.get(i);
            String[] content = changes.get(i);
            if (content[0] != null) {
                tc.setInputText(TestCaseStorage.summary(content[0]));
                tc.setInputSize(TestCaseStorage.bytes(content[0]));
            }
            if (content[1] != null) {
                tc.setOutputText(TestCaseStorage.summary(content[1]));
                tc.setOutputSize(TestCaseStorage.bytes(content[1]));
            }
            if (tc.getId() == null) { cases.insert(tc); inserted++; }
            else { cases.updateById(tc); updated++; }
            if (content[0] != null) inputs.put(tc.getId(), content[0]);
            if (content[1] != null) outputs.put(tc.getId(), content[1]);
        }
        int deleted = 0;
        for (ProbTestCase old : originalList) if (!ids.contains(old.getId())) {
            cases.update(null, new UpdateWrapper<ProbTestCase>().eq("id", old.getId())
                    .eq("problem_id", question.getId()).set("deleted_at", Times.nowInDbZone()));
            deleted++;
        }
        bump(question);
        question.setTestCaseCount(next.size());
        // sample_cases 只保存公开样例，避免残留旧样例；正文仍以完整磁盘文件为准。
        List<Map<String, String>> publicSamples = new ArrayList<>();
        for (ProbTestCase tc : next) if (Boolean.TRUE.equals(tc.getIsPublic())) {
            ProbTestCase original = originals.get(tc.getId());
            String input = inputs.containsKey(tc.getId()) ? inputs.get(tc.getId())
                    : storage.read(original == null ? tc : original, true).text();
            String output = outputs.containsKey(tc.getId()) ? outputs.get(tc.getId())
                    : storage.read(original == null ? tc : original, false).text();
            publicSamples.add(Map.of("input", input, "output", output));
        }
        question.setSampleCases(encode(publicSamples));
        questions.updateById(question);
        storage.schedule(question.getId(), next, originals, inputs, outputs);
        return Map.of("version", question.getVersion(), "total", next.size(), "totalScore", totalScore(next),
                "changed", Map.of("inserted", inserted, "updated", updated, "deleted", deleted));
    }

    public Map<String, Object> importProblems(ProblemImportRequest request) {
        teacher();
        if (request == null || !"json".equals(request.getFormat()) || request.getItems() == null
                || request.getItems().isEmpty() || request.getItems().size() > 200)
            throw BusinessException.badRequest("format 必须为 json，items 必须含1至200条题目");
        List<Map<String, Object>> results = new ArrayList<>();
        int succeeded = 0;
        for (int i = 0; i < request.getItems().size(); i++) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("index", i);
            try {
                ProblemWriteRequest item = request.getItems().get(i);
                validateImportItem(item);
                if (Boolean.TRUE.equals(request.getDryRun())) {
                    result.put("ok", true);
                    results.add(result);
                    succeeded++;
                    continue;
                }
                Map<String, Object> created = transactions.execute(status -> {
                    Map<String, Object> value = create(item);
                    if (item.getTestCases() != null && !item.getTestCases().isEmpty()) {
                        List<TestCaseWriteRequest> imports = item.getTestCases();
                        for (int j = 0; j < imports.size(); j++) {
                            if (imports.get(j) == null) throw BusinessException.badRequest("测试点不能为空");
                            imports.get(j).setId(null);
                            imports.get(j).setSeqNo(j + 1);
                        }
                        reconcile(owned((Long) value.get("id"), true), imports, null);
                    }
                    return value;
                });
                result.put("ok", true);
                if (!Boolean.TRUE.equals(request.getDryRun())) result.put("id", created.get("id"));
                succeeded++;
            } catch (BusinessException e) {
                result.put("ok", false);
                result.put("error", e.getMessage());
            } catch (RuntimeException e) {
                result.put("ok", false);
                result.put("error", "题目保存失败");
            }
            results.add(result);
        }
        return Map.of("total", results.size(), "succeeded", succeeded, "failed", results.size() - succeeded,
                "results", results);
    }

    @Transactional
    public Map<String, Object> copy(Long id) {
        ProbQuestion source = owned(id, true);
        ProbQuestion duplicate = new ProbQuestion();
        org.springframework.beans.BeanUtils.copyProperties(source, duplicate);
        duplicate.setId(null);
        duplicate.setCreatorId(UserContext.userId());
        duplicate.setState(0);
        duplicate.setVersion(1);
        duplicate.setPassCount(0);
        duplicate.setSubmitCount(0);
        duplicate.setCreateTime(Times.nowInDbZone());
        duplicate.setUpdateTime(duplicate.getCreateTime());
        String title = source.getTitle();
        duplicate.setTitle(title.substring(0, Math.min(title.length(), 35)) + "（副本）");
        questions.insert(duplicate);
        duplicate.setTestCaseDir("/data/testcases/" + duplicate.getId() + "/");
        questions.updateById(duplicate);
        syncKnowledge(duplicate);
        List<ProbTestCase> oldCases = cases.selectByProblemId(id);
        List<ProbTestCase> newCases = new ArrayList<>();
        Map<Long, ProbTestCase> sources = new HashMap<>();
        for (ProbTestCase original : oldCases) {
            ProbTestCase tc = new ProbTestCase();
            org.springframework.beans.BeanUtils.copyProperties(original, tc);
            tc.setId(null);
            tc.setProblemId(duplicate.getId());
            tc.setCreateTime(Times.nowInDbZone());
            tc.setUpdateTime(tc.getCreateTime());
            cases.insert(tc);
            sources.put(tc.getId(), original);
            newCases.add(tc);
        }
        storage.schedule(duplicate.getId(), newCases, sources, Map.of(), Map.of());
        return created(duplicate);
    }

    public List<ProbKnowledgePoint> knowledgePoints() {
        return knowledge.selectList(new QueryWrapper<ProbKnowledgePoint>().orderByAsc("sort_order", "id"));
    }

    @Transactional
    public void refresh(Long id) {
        ProbQuestion question = writes.lockById(id);
        if (question == null) throw BusinessException.notFound("题目不存在");
        storage.refresh(id, cases.selectByProblemId(id));
    }

    private ProbQuestion newQuestion(ProblemWriteRequest request) {
        validate(request, true);
        ProbQuestion question = new ProbQuestion();
        question.setTags("[]");
        question.setContent("");
        question.setSampleCases("[]");
        question.setCodeTemplates(encode(Map.of("cpp", "#include <bits/stdc++.h>\nusing namespace std;\nint main() {\n    return 0;\n}\n",
                "java", "public class Main {\n    public static void main(String[] args) {\n    }\n}\n",
                "python", "import sys\n\ndef main():\n    pass\n\nif __name__ == '__main__':\n    main()\n")));
        question.setTimeLimit(1000);
        question.setMemoryLimit(256);
        question.setTestCaseCount(0);
        question.setTestCaseDir("");
        question.setAiEnabled(true);
        question.setAiQuotaLimit(3);
        question.setAiStages("[1,2]");
        question.setState(0);
        question.setVersion(1);
        question.setPassCount(0);
        question.setSubmitCount(0);
        question.setCreatorId(UserContext.userId());
        question.setCreateTime(Times.nowInDbZone());
        question.setUpdateTime(question.getCreateTime());
        apply(question, request);
        return question;
    }

    private void validate(ProblemWriteRequest request, boolean creating) {
        if (request == null) throw BusinessException.badRequest("题目内容必填");
        if (creating && (request.getTitle() == null || request.getDifficulty() == null))
            throw BusinessException.badRequest("title 和 difficulty 必填");
        if (request.getTitle() != null && (request.getTitle().isBlank() || request.getTitle().length() > 40))
            throw BusinessException.badRequest("标题必须为1至40字符");
        if (request.getDifficulty() != null && (request.getDifficulty() < 1 || request.getDifficulty() > 5))
            throw BusinessException.badRequest("difficulty 必须为1至5");
        if (request.getTags() != null && (request.getTags().size() > 5 || request.getTags().stream()
                .anyMatch(tag -> tag == null || tag.isBlank() || tag.length() > 8)))
            throw BusinessException.badRequest("标签最多5个，每个为1至8字符");
        if (request.getTimeLimitMs() != null && (request.getTimeLimitMs() < 100 || request.getTimeLimitMs() % 100 != 0))
            throw BusinessException.badRequest("timeLimitMs 至少100且必须为100的倍数");
        if (request.getMemoryLimitMb() != null && (request.getMemoryLimitMb() < 16 || request.getMemoryLimitMb() % 16 != 0))
            throw BusinessException.badRequest("memoryLimitMb 至少16且必须为16的倍数");
        if (request.getAiQuotaLimit() != null && request.getAiQuotaLimit() < -1)
            throw BusinessException.badRequest("aiQuotaLimit 至少为-1");
        if (request.getAiStages() != null && request.getAiStages().stream().anyMatch(stage -> stage == null || stage < 1 || stage > 4))
            throw BusinessException.badRequest("aiStages 元素必须为1至4");
        if (request.getStatement() != null && request.getStatement().length() > 20000)
            throw BusinessException.badRequest("题面最多20000字符");
        if (request.getKnowledgePointId() != null && request.getKnowledgePointId() > 0
                && knowledge.selectById(request.getKnowledgePointId()) == null)
            throw BusinessException.badRequest("知识点不存在");
    }

    private void validateImportItem(ProblemWriteRequest item) {
        validate(item, true);
        if (item.getTestCases() == null) return;
        int samples = 0;
        for (int i = 0; i < item.getTestCases().size(); i++) {
            TestCaseWriteRequest tc = item.getTestCases().get(i);
            if (tc == null) throw BusinessException.badRequest("测试点不能为空");
            tc.setId(null);
            tc.setSeqNo(i + 1);
            if ("sample".equals(mergeCase(0L, tc, null).getKind())) samples++;
        }
        if (samples > 3) throw BusinessException.conflict("每道题最多3个样例测试点");
    }

    private void apply(ProbQuestion question, ProblemWriteRequest request) {
        if (request.getTitle() != null) question.setTitle(request.getTitle().trim());
        if (request.getDifficulty() != null) question.setDifficulty(request.getDifficulty());
        if (request.isKnowledgePointProvided()) question.setKnowledgePointId(request.getKnowledgePointId() == null
                || request.getKnowledgePointId() <= 0 ? null : request.getKnowledgePointId());
        if (request.getTags() != null) question.setTags(encode(new LinkedHashSet<>(request.getTags())));
        if (request.getTimeLimitMs() != null) question.setTimeLimit(request.getTimeLimitMs());
        if (request.getMemoryLimitMb() != null) question.setMemoryLimit(request.getMemoryLimitMb());
        if (request.getAiEnabled() != null) question.setAiEnabled(request.getAiEnabled());
        if (request.getAiQuotaLimit() != null) question.setAiQuotaLimit(request.getAiQuotaLimit());
        if (request.getAiStages() != null) question.setAiStages(encode(new LinkedHashSet<>(request.getAiStages())));
        if (request.getStatement() != null) question.setContent(request.getStatement());
        if (request.getOfficialSolution() != null) question.setOfficialSolution(request.getOfficialSolution());
        if (request.getCodeTemplates() != null) question.setCodeTemplates(encode(request.getCodeTemplates()));
    }

    private ProbTestCase mergeCase(Long problemId, TestCaseWriteRequest item, ProbTestCase old) {
        boolean inserting = old == null;
        if (inserting && (item.getKind() == null || item.getInput() == null || item.getOutput() == null || item.getScore() == null))
            throw BusinessException.badRequest("新增测试点必须提供 kind/input/output/score");
        if (item.getKind() != null && !KINDS.contains(item.getKind())) throw BusinessException.badRequest("kind 非法");
        if (item.getScore() != null && (item.getScore() < 0 || item.getScore() > 100 || item.getScore() % 5 != 0))
            throw BusinessException.badRequest("score 必须为0至100且是5的倍数");
        if (TestCaseStorage.bytes(item.getInput()) > TestCaseStorage.MAX_CONTENT_BYTES
                || TestCaseStorage.bytes(item.getOutput()) > TestCaseStorage.MAX_CONTENT_BYTES)
            throw BusinessException.badRequest("测试点输入输出分别不能超过1MB");
        ProbTestCase tc = new ProbTestCase();
        if (old != null) org.springframework.beans.BeanUtils.copyProperties(old, tc);
        tc.setProblemId(problemId);
        tc.setSeqNo(item.getSeqNo());
        if (item.getKind() != null) tc.setKind(item.getKind());
        tc.setKind(normalizeKind(tc.getKind()));
        if (item.getScore() != null) tc.setScore(item.getScore());
        if (item.getIsPublic() != null) tc.setIsPublic(item.getIsPublic());
        else if (inserting) tc.setIsPublic("sample".equals(tc.getKind()));
        tc.setUpdateTime(Times.nowInDbZone());
        if (inserting) tc.setCreateTime(tc.getUpdateTime());
        return tc;
    }

    private String replacement(String supplied, ProbTestCase old, boolean input) {
        if (old == null || supplied == null) return supplied;
        String summary = input ? old.getInputText() : old.getOutputText();
        Integer size = input ? old.getInputSize() : old.getOutputSize();
        // 旧大文件摘要未变时保留全文，编辑页整表回传也不能截断导入的测试数据。
        if (size != null && size > TestCaseStorage.bytes(summary) && Objects.equals(supplied, summary)) return null;
        return supplied;
    }

    private List<Map<String, Object>> caseViews(List<ProbTestCase> list, boolean content) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ProbTestCase tc : list) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", tc.getId());
            item.put("seqNo", tc.getSeqNo());
            item.put("kind", normalizeKind(tc.getKind()));
            item.put("score", tc.getScore());
            item.put("isPublic", tc.getIsPublic());
            if (content) {
                TestCaseStorage.Content input = storage.read(tc, true);
                TestCaseStorage.Content output = storage.read(tc, false);
                item.put("input", input.text());
                item.put("output", output.text());
                item.put("inputTruncated", input.truncated());
                item.put("outputTruncated", output.truncated());
                item.put("inputSize", tc.getInputSize());
                item.put("outputSize", tc.getOutputSize());
            }
            result.add(item);
        }
        return result;
    }

    private void teacherOrStudentRead(Long id, boolean content) {
        if (UserContext.isTeacherOrAbove()) { owned(id, false); return; }
        UserContext.userId();
        if (content) throw BusinessException.forbidden("学生只能读取测试点骨架");
        ProbQuestion question = questions.selectByIdNotDeleted(id);
        if (question == null) throw BusinessException.notFound("题目不存在");
        if (question.getState() != 1) throw BusinessException.forbidden("题目尚未发布");
    }

    private ProbQuestion owned(Long id, boolean lock) {
        teacher();
        if (id == null || id <= 0) throw BusinessException.badRequest("题目ID不合法");
        ProbQuestion question = lock ? writes.lockOwned(id, UserContext.userId(), UserContext.isAdmin())
                : writes.selectOwned(id, UserContext.userId(), UserContext.isAdmin());
        if (question == null) {
            if (questions.selectByIdNotDeleted(id) == null) throw BusinessException.notFound("题目不存在或已删除");
            throw BusinessException.forbidden("只能编辑本人创建的题目");
        }
        return question;
    }

    private void teacher() { UserContext.userId(); UserContext.requireTeacherOrAbove("管理题目"); }
    private void editable(ProbQuestion question) {
        if (question.getState() == 2) throw BusinessException.conflict("已归档题目不能编辑，请先恢复");
    }
    private void checkVersion(ProbQuestion question, Integer version, boolean required) {
        if (required && version == null) throw BusinessException.badRequest("version 必填");
        if (version != null && !Objects.equals(version, question.getVersion())) throw BusinessException.conflict("版本已变化，请刷新后重试");
    }
    private void bump(ProbQuestion question) {
        if (writes.bumpVersion(question.getId(), question.getVersion(), UserContext.userId(), UserContext.isAdmin()) != 1)
            throw BusinessException.conflict("版本已变化，请刷新后重试");
        question.setVersion(question.getVersion() + 1);
    }
    private ProbTestCase requireCase(Long id) {
        ProbTestCase tc = id == null ? null : cases.selectById(id);
        if (tc == null || tc.getDeletedAt() != null || tc.getSeqNo() <= 0) throw BusinessException.notFound("测试点不存在");
        return tc;
    }
    private List<TestCaseWriteRequest> retainedItems(List<ProbTestCase> current) {
        List<TestCaseWriteRequest> result = new ArrayList<>();
        for (ProbTestCase tc : current) {
            TestCaseWriteRequest item = new TestCaseWriteRequest();
            item.setId(tc.getId());
            item.setSeqNo(tc.getSeqNo());
            result.add(item);
        }
        return result;
    }
    private int totalScore(List<ProbTestCase> list) { return list.stream().mapToInt(tc -> tc.getScore() == null ? 0 : tc.getScore()).sum(); }
    private String normalizeKind(String kind) { return "hidden".equals(kind) ? "normal" : kind; }
    private String publishHint(ProbQuestion question, int count, int total) {
        if (question.getTitle() == null || question.getTitle().isBlank()) return "请填写题目标题";
        if (count == 0) return "至少1个测试点才能保存并发布";
        if (total < 100) return "还差" + (100 - total) + "分，凑满100分才能发布";
        if (total > 100) return "测试点总分为" + total + "，调整为100分才能发布";
        return null;
    }
    private Map<String, Object> created(ProbQuestion question) {
        return Map.of("id", question.getId(), "displayNo", "#" + question.getId(), "state", 0, "version", question.getVersion());
    }
    private void syncKnowledge(ProbQuestion question) {
        writes.deleteKnowledgeLinks(question.getId());
        if (question.getKnowledgePointId() != null) writes.addKnowledgeLink(question.getId(), question.getKnowledgePointId());
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw BusinessException.badRequest("JSON字段格式错误"); }
    }
}
