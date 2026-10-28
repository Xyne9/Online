package com.oj.teaching.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.common.core.exception.BusinessException;
import com.oj.teaching.entity.SysUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.oj.teaching.service.ActivitySupport.map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalyticsServiceTest {
    private AnalyticsRepository repo;
    private ActivitySupport support;
    private AnalyticsService service;

    @BeforeEach
    void setUp() {
        repo = mock(AnalyticsRepository.class);
        support = mock(ActivitySupport.class);
        service = new AnalyticsService(repo, mock(AssignmentService.class), support, new ObjectMapper());
        when(repo.classRow(10L)).thenReturn(map("id", 10L, "teacher_id", 1L, "class_name", "本班"));
        when(repo.classRow(20L)).thenReturn(map("id", 20L, "teacher_id", 2L, "class_name", "其他班"));
        when(repo.problems(anyCollection())).thenReturn(List.of(map("id", 100L, "title", "测试题", "difficulty", 1)));
        when(repo.testCases(anyCollection())).thenReturn(List.of(map("problem_id", 100L, "seq_no", 1,
                "kind", "hidden", "score", 100)));
        current("TEACHER");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void errorsCountBestCaseResultPerStudentRegardlessOfSubmissionOrder(boolean passFirst) {
        Map<String, Object> fail = submission(1L, 7L, 5, "[{\"seqNo\":1,\"status\":\"WA\",\"actualPreview\":\"wrong\"}]");
        Map<String, Object> pass = submission(2L, 7L, 3, "[{\"seqNo\":1,\"status\":\"AC\"}]");
        Map<String, Object> stillFailing = submission(3L, 8L, 5, "[{\"seqNo\":1,\"status\":\"WA\",\"actualPreview\":\"wrong\"}]");
        when(repo.submissionsWithCases(eq(10L), eq(100L), isNull(), isNull()))
                .thenReturn(passFirst ? List.of(pass, fail, stillFailing) : List.of(fail, pass, stillFailing));

        Map<String, Object> result = service.problemErrors(100L, 10L, null);
        Map<String, Object> cell = rows(result, "cases").get(0);

        assertEquals(3L, result.get("totalSubmit"));
        assertEquals(1, cell.get("failCount"), "已通过该测试点的学生不再计失败");
        assertEquals(50d, ((Number) cell.get("failRate")).doubleValue());
        assertEquals("wrong", cell.get("topWrongOutput"));
    }

    @Test
    void heatmapKeepsRealCasesThatNoSubmissionReached() {
        Map<String, Object> sub = submission(1L, 7L, 4, "[{\"seqNo\":1,\"status\":\"AC\"}]");
        when(repo.submissions(eq(10L), any())).thenReturn(List.of(sub));
        when(repo.submissionsWithCases(eq(10L), isNull(), isNull(), any())).thenReturn(List.of(sub));
        when(repo.testCases(anyCollection())).thenReturn(List.of(
                map("problem_id", 100L, "seq_no", 1, "kind", "public", "score", 30),
                map("problem_id", 100L, "seq_no", 2, "kind", "hidden", "score", 30),
                map("problem_id", 100L, "seq_no", 3, "kind", "hidden", "score", 40)));

        Map<String, Object> result = service.classHeatmap(10L, 6);
        List<Map<String, Object>> cells = rows(rows(result, "rows").get(0), "cells");

        assertEquals(List.of(1, 2, 3), cells.stream().map(c -> c.get("seqNo")).toList());
        assertEquals(List.of("ac", "none", "none"), cells.stream().map(c -> c.get("status")).toList());
        assertEquals(100d, ((Number) cells.get(0).get("passRate")).doubleValue());
        assertEquals(0d, ((Number) cells.get(1).get("passRate")).doubleValue());
    }

    @Test
    void heatmapLowestCaseRetainsFractionalPrecision() {
        List<Map<String, Object>> submissions = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            String first = "{\"seqNo\":1,\"status\":\"" + (i < 4 ? "AC" : "WA") + "\"}";
            String second = i < 10 ? ",{\"seqNo\":2,\"status\":\"" + (i < 3 ? "AC" : "WA") + "\"}" : "";
            submissions.add(submission(i + 1L, i + 7L, 4, "[" + first + second + "]"));
        }
        when(repo.submissions(eq(10L), any())).thenReturn(submissions);
        when(repo.submissionsWithCases(eq(10L), isNull(), isNull(), any())).thenReturn(submissions);

        Map<String, Object> result = service.classHeatmap(10L, 6);

        // 第一点 4/13=30.77%，第二点 3/10=30%，最低点必须选第二点。
        assertTrue(((String) result.get("footNote")).contains("第 2 个测试点"));
    }

    @Test
    void compileErrorsDoNotMakeUnrunCasesAppearHealthy() {
        Map<String, Object> sub = submission(1L, 7L, 9, "[]");
        when(repo.submissions(eq(10L), any())).thenReturn(List.of(sub));
        when(repo.submissionsWithCases(eq(10L), isNull(), isNull(), any())).thenReturn(List.of(sub));

        Map<String, Object> result = service.classHeatmap(10L, 6);

        assertEquals("none", rows(rows(result, "rows").get(0), "cells").get(0).get("status"));
        assertTrue(((String) result.get("footNote")).contains("无法评估"));
    }

    @Test
    void studentTrendCountsOnlyTheirOwnSubmissions() {
        current("STUDENT");
        when(repo.submissions(eq(10L), any())).thenReturn(List.of(
                submission(1L, 7L, 3, "[]"), submission(2L, 8L, 5, "[]")));

        Map<String, Object> result = service.weekTrend(10L, 1, null);

        assertEquals(1L, result.get("weekSubmit"));
        assertEquals(1L, result.get("weekAc"));
        assertEquals(100d, ((Number) result.get("weekPassRate")).doubleValue());
        assertEquals(1L, rows(result, "days").stream()
                .mapToLong(d -> ((Number) d.get("submitCount")).longValue()).sum());
    }

    @Test
    void teacherTrendStillCountsTheWholeClass() {
        when(repo.submissions(eq(10L), any())).thenReturn(List.of(
                submission(1L, 7L, 3, "[]"), submission(2L, 8L, 5, "[]")));

        Map<String, Object> result = service.weekTrend(10L, 1, null);

        assertEquals(2L, result.get("weekSubmit"));
        assertEquals(1L, result.get("weekAc"));
        assertEquals(50d, ((Number) result.get("weekPassRate")).doubleValue());
    }

    @ParameterizedTest
    @CsvSource({"STUDENT,trend", "TEACHER,trend", "STUDENT,hard", "TEACHER,hard", "TEACHER,ai"})
    void explicitClassCannotBypassOwnership(String role, String endpoint) {
        current(role);

        BusinessException error = assertThrows(BusinessException.class, () -> endpoint(endpoint, 20L));

        assertEquals(403, error.getCode());
        verify(repo, never()).submissions(eq(20L), any());
        verify(repo, never()).aiRecords(eq(20L), any());
    }

    @ParameterizedTest
    @CsvSource({"STUDENT,trend,10", "TEACHER,trend,10", "STUDENT,hard,10", "TEACHER,hard,10",
            "TEACHER,ai,10", "ADMIN,trend,20", "ADMIN,hard,20", "ADMIN,ai,20"})
    void authorizedClassReadsStillWork(String role, String endpoint, long classId) {
        current(role);

        assertDoesNotThrow(() -> endpoint(endpoint, classId));
        verify(repo).submissions(eq(classId), any());
    }

    private Map<String, Object> endpoint(String endpoint, long classId) {
        return switch (endpoint) {
            case "trend" -> service.weekTrend(classId, 1, null);
            case "hard" -> service.hardProblems(classId, "week", "ac", 10);
            default -> service.aiUsage(classId, "week", "stage");
        };
    }

    private void current(String role) {
        SysUser user = new SysUser();
        user.setId("STUDENT".equals(role) ? 7L : 1L);
        user.setClassId(10L);
        user.setRole(role);
        when(support.current()).thenReturn(user);
    }

    private Map<String, Object> submission(long id, long userId, int status, String cases) {
        return map("id", id, "user_id", userId, "problem_id", 100L, "assignment_id", 0L,
                "status", status, "oi_score", status == 3 ? 100 : 0,
                "create_time", ActivitySupport.now(), "case_details", cases);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }
}
