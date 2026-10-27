package com.oj.teaching.service;

import com.oj.common.api.dto.judge.SubmissionCodeDTO;
import com.oj.common.api.feign.JudgeInnerFeignClient;
import com.oj.common.core.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.oj.teaching.service.PlagiarismAccess.*;

@Service
public class PlagiarismQueryService {
    private final PlagiarismRepository repository;
    private final PlagiarismAccess access;
    private final JudgeInnerFeignClient judge;
    private final CodeSimilarityAlignment alignment;
    private final double highStruct, highSem;
    public PlagiarismQueryService(PlagiarismRepository repository, PlagiarismAccess access, JudgeInnerFeignClient judge,
                                 CodeSimilarityAlignment alignment, @Value("${oj.plagiarism.high-struct:75}") double highStruct,
                                 @Value("${oj.plagiarism.high-sem:85}") double highSem) {
        this.repository = repository; this.access = access; this.judge = judge; this.alignment = alignment;
        this.highStruct = highStruct; this.highSem = highSem;
    }
    public String level(double struct, Double sem) {
        boolean a = struct >= highStruct, b = sem != null && sem >= highSem;
        return a && b ? "high" : a || b ? "suspect" : "normal";
    }
    public Map<String, Object> pairs(long assignmentId, Long problemId, String filter, int page, int pageSize, String sortBy, String sortOrder) {
        access.assignment(assignmentId, false);
        if (!Set.of("all", "high", "suspect", "normal", "todo").contains(filter) || page < 1 || pageSize < 1
                || !Set.of("overall", "struct", "sem").contains(sortBy) || !Set.of("asc", "desc").contains(sortOrder)) throw BusinessException.badRequest("查重筛选、分页或排序参数无效");
        int size = Math.min(100, pageSize); var reports = repository.reports(assignmentId, problemId);
        List<Map<String, Object>> records = reports.stream().map(r -> summary(r, Map.of())).toList();
        long high = count(records, "high"), suspect = count(records, "suspect"), normal = count(records, "normal");
        long pending = records.stream().filter(r -> ((Number)r.get("auditStatus")).intValue() == 0 && !r.get("level").equals("normal")).count();
        String key = switch (sortBy) { case "struct" -> "structSim"; case "sem" -> "semSim"; default -> "overallSim"; };
        Comparator<Double> direction = sortOrder.equals("asc") ? Comparator.naturalOrder() : Comparator.reverseOrder();
        Comparator<Map<String, Object>> comparator = Comparator.comparing(r -> (Double)r.get(key), Comparator.nullsLast(direction));
        var selected = records.stream().filter(r -> filter.equals("all") || (filter.equals("todo") ? ((Number)r.get("auditStatus")).intValue() == 0 && !r.get("level").equals("normal") : filter.equals(r.get("level"))))
                .sorted(comparator.thenComparingLong(r -> ((Number)r.get("reportId")).longValue())).toList();
        long start = (long)(page - 1) * size; var slice = selected.subList((int)Math.min(start, selected.size()), (int)Math.min(start + size, selected.size()));
        List<Long> ids = new ArrayList<>();
        for (var r : slice) { var original = reports.stream().filter(v -> number(v, "id") == ((Number)r.get("reportId")).longValue()).findFirst().orElseThrow(); ids.add(number(original, "sub_a_id")); ids.add(number(original, "sub_b_id")); }
        Map<Long, SubmissionCodeDTO> codes = codes(ids);
        var list = slice.stream().map(r -> reports.stream().filter(v -> number(v, "id") == ((Number)r.get("reportId")).longValue()).findFirst().orElseThrow()).map(r -> summary(r, codes)).toList();
        return view("list", list, "total", selected.size(), "page", page, "pageSize", size, "hasNext", start + size < selected.size(),
                "stats", Map.of("flagged", high + suspect, "pending", pending, "high", high, "suspect", suspect, "normal", normal));
    }
    public Map<String, Object> detail(long reportId, Integer onlySeg) {
        var report = access.report(reportId); long aId = number(report, "sub_a_id"), bId = number(report, "sub_b_id");
        var codes = codes(List.of(aId, bId));
        if (!codes.containsKey(aId) || !codes.containsKey(bId)) throw BusinessException.badGateway("查重原始提交代码缺失");
        var segmentRows = repository.segments(reportId);
        if (onlySeg != null && segmentRows.stream().noneMatch(s -> number(s, "seg_id") == onlySeg)) throw BusinessException.notFound("相似片段不存在");
        String codeA = codes.get(aId).getCode(), codeB = codes.get(bId).getCode();
        var aligned = alignment.align(codeA, codeB, report.get("language").toString());
        List<Map<String, Object>> rows = new ArrayList<>(); int seq = 0;
        for (var line : aligned) {
            Map<String, Object> segment = segmentRows.stream().filter(s -> inside(line.aLineNo(), s, "a") || inside(line.bLineNo(), s, "b")).findFirst().orElse(null);
            Integer segmentId = segment == null ? null : ((Number)segment.get("seg_id")).intValue();
            if (onlySeg == null || onlySeg.equals(segmentId)) rows.add(view("key", ++seq, "aLineNo", line.aLineNo(), "aText", line.aText(), "bLineNo", line.bLineNo(), "bText", line.bText(), "segId", segmentId, "annotation", segment == null ? null : segment.get("annotation")));
        }
        var segments = segmentRows.stream().map(s -> view("segId", s.get("seg_id"), "segName", s.get("seg_name"), "aFrom", s.get("a_from_line"), "aTo", s.get("a_to_line"), "bFrom", s.get("b_from_line"), "bTo", s.get("b_to_line"),
                "rowCount", aligned.stream().filter(line -> inside(line.aLineNo(), s, "a") || inside(line.bLineNo(), s, "b")).count())).toList();
        var result = new LinkedHashMap<>(summary(report, codes));
        result.putAll(view("codeA", codeA, "codeB", codeB, "stage1AlgoSim", decimal(report, "stage1_algo_sim"), "stage2AiSim", decimal(report, "stage2_ai_sim"),
                "aiModelUsed", report.get("ai_model_used"), "aiDiagnosis", report.get("ai_diagnosis"), "segments", segments, "rows", rows,
                "review", view("auditStatus", report.get("audit_status"), "auditedBy", report.get("audited_by"), "auditedAt", epoch(report.get("audited_at")), "note", report.get("audit_note"))));
        return result;
    }
    public Map<String, Object> summary(Map<String, Object> report, Map<Long, SubmissionCodeDTO> codes) {
        Double struct = decimal(report, "stage1_algo_sim"), sem = decimal(report, "stage2_ai_sim"); String level = level(struct, sem);
        int audit = ((Number)report.get("audit_status")).intValue();
        return view("reportId", report.get("id"), "displayNo", report.get("display_no"), "problemId", report.get("problem_id"), "problemTitle", report.get("problem_title"),
                "structSim", struct, "semSim", sem, "overallSim", sem == null ? null : Math.round((struct + sem) * 5) / 10.0,
                "stage1AlgoSim", struct, "stage2AiSim", sem, "aiModelUsed", report.get("ai_model_used"), "aiDiagnosis", report.get("ai_diagnosis"),
                "level", level, "levelName", switch(level) { case "high" -> "高度疑似"; case "suspect" -> "疑似"; default -> "正常"; },
                "auditStatus", audit, "statusText", audit == 1 ? "确认抄袭" : audit == 2 ? "已排除" : "待复核",
                "userA", user(report, "a", codes.get(number(report, "sub_a_id"))), "userB", user(report, "b", codes.get(number(report, "sub_b_id"))));
    }
    public Map<Long, SubmissionCodeDTO> codes(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        var response = judge.getBatchBestCodes(ids.stream().distinct().toList());
        if (response == null || response.getCode() != 200 || response.getData() == null) throw BusinessException.badGateway("判题服务未返回原始代码");
        return response.getData().stream().collect(Collectors.toMap(SubmissionCodeDTO::getSubmissionId, Function.identity(), (a, b) -> a));
    }
    private static Map<String, Object> user(Map<String, Object> r, String side, SubmissionCodeDTO code) {
        String language = code == null ? r.get("language").toString() : code.getLanguage();
        return view("id", r.get("user_" + side + "_id"), "name", r.get(side + "_name"), "stuNo", r.get(side + "_stu_no"), "language", language,
                "languageLabel", switch(language) { case "cpp" -> "C++ 17"; case "java" -> "Java 17"; default -> "Python 3"; }, "submittedAt", code == null ? null : code.getSubmittedAt());
    }
    private static boolean inside(Integer line, Map<String, Object> segment, String side) { return line != null && line >= number(segment, side + "_from_line") && line <= number(segment, side + "_to_line"); }
    private static long count(List<Map<String, Object>> records, String level) { return records.stream().filter(r -> level.equals(r.get("level"))).count(); }
    public static Long epoch(Object value) {
        if (value == null) return null;
        LocalDateTime date = value instanceof Timestamp t ? t.toLocalDateTime() : (LocalDateTime)value;
        return date.atZone(com.oj.teaching.constant.AuthConstants.DB_ZONE).toInstant().toEpochMilli();
    }
    public static Map<String, Object> view(Object... values) {
        var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < values.length; i += 2) result.put(values[i].toString(), values[i + 1]); return result;
    }
}
