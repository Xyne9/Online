package com.oj.problem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.common.core.exception.BusinessException;
import com.oj.problem.dto.*;
import com.oj.problem.entity.*;
import com.oj.problem.mapper.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.request.*;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProblemEditServiceTest {
    private final ProbQuestionMapper questions = mock(ProbQuestionMapper.class);
    private final ProbTestCaseMapper cases = mock(ProbTestCaseMapper.class);
    private final ProblemWriteMapper writes = mock(ProblemWriteMapper.class);
    private final TestCaseStorage storage = mock(TestCaseStorage.class);
    private final ProblemEditService service = new ProblemEditService(questions, cases,
            mock(ProbKnowledgePointMapper.class), writes, storage,
            mock(PlatformTransactionManager.class), new ObjectMapper());
    private ProbQuestion question;

    @BeforeEach void setUp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "31"); request.addHeader("X-User-Role", "TEACHER");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        question = new ProbQuestion();
        question.setId(73L); question.setCreatorId(31L); question.setTitle("编辑测试");
        question.setDifficulty(3); question.setState(0); question.setVersion(4);
        question.setUpdateTime(LocalDateTime.now());
        when(questions.selectByIdNotDeleted(73L)).thenReturn(question);
        when(writes.lockOwned(73L, 31L, false)).thenReturn(question);
        when(writes.selectOwned(73L, 31L, false)).thenReturn(question);
        when(writes.bumpVersion(73L, 4, 31L, false)).thenReturn(1);
    }
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }

    @Test void ownershipAndVersionRejectBeforeAnyWrite() {
        when(writes.selectOwned(73L, 31L, false)).thenReturn(null);
        assertEquals(403, assertThrows(BusinessException.class, () -> service.edit(73L, null)).getCode());
        ProblemWriteRequest request = new ProblemWriteRequest(); request.setVersion(3);
        assertEquals(409, assertThrows(BusinessException.class, () -> service.update(73L, request)).getCode());
        verify(questions, never()).updateById(any(ProbQuestion.class));
        verifyNoInteractions(storage);
    }

    @Test void publicationRequiresExactlyOneHundredAndArchiveCannotBeEdited() {
        when(cases.selectLockedByProblemId(73L)).thenReturn(List.of(testCase(81L, 1, 95)));
        ProblemStateRequest request = new ProblemStateRequest(); request.setAction("publish");
        assertEquals(400, assertThrows(BusinessException.class, () -> service.state(73L, request)).getCode());
        when(cases.selectLockedByProblemId(73L)).thenReturn(List.of(testCase(81L, 1, 100)));
        assertEquals(1, service.state(73L, request).get("toState"));
        assertEquals(1, question.getState());
        question.setState(2);
        ProblemWriteRequest edit = new ProblemWriteRequest(); edit.setVersion(5);
        assertEquals(409, assertThrows(BusinessException.class, () -> service.update(73L, edit)).getCode());
    }

    @Test void batchRejectsForeignIdsAndNonContinuousOrderWithoutChangingRows() {
        when(cases.selectLockedByProblemId(73L)).thenReturn(List.of(testCase(81L, 1, 100)));
        TestCaseWriteRequest foreign = new TestCaseWriteRequest(); foreign.setId(82L); foreign.setSeqNo(1);
        TestCaseBatchRequest request = new TestCaseBatchRequest(); request.setVersion(4); request.setItems(List.of(foreign));
        assertEquals(400, assertThrows(BusinessException.class, () -> service.batch(73L, request)).getCode());
        foreign.setId(81L); foreign.setSeqNo(2);
        assertEquals(400, assertThrows(BusinessException.class, () -> service.batch(73L, request)).getCode());
        verify(writes, never()).setCaseSeq(anyLong(), anyLong(), anyInt());
    }

    @Test void batchRetainsOldLargeContentWhenEditorEchoesItsSummary() {
        ProbTestCase original = testCase(81L, 1, 100);
        original.setInputText("summary"); original.setInputSize(2000000);
        original.setOutputText("out"); original.setOutputSize(3);
        when(cases.selectLockedByProblemId(73L)).thenReturn(List.of(original));
        TestCaseWriteRequest item = new TestCaseWriteRequest(); item.setId(81L); item.setSeqNo(1);
        item.setInput("summary");
        TestCaseBatchRequest request = new TestCaseBatchRequest(); request.setVersion(4); request.setItems(List.of(item));
        Map<String, Object> result = service.batch(73L, request);
        assertEquals(5, result.get("version"));
        verify(storage).schedule(eq(73L), anyList(), anyMap(), eq(Map.of()), eq(Map.of()));
        verify(cases).updateById(org.mockito.ArgumentMatchers.<ProbTestCase>argThat(tc -> tc.getInputSize() == 2000000));
    }

    @Test void invalidFieldsDoNotWriteAndSubmittedDraftCannotBeDeleted() {
        ProblemWriteRequest request = new ProblemWriteRequest(); request.setTitle("新题"); request.setDifficulty(6);
        assertEquals(400, assertThrows(BusinessException.class, () -> service.create(request)).getCode());
        when(writes.countSubmissions(73L)).thenReturn(1L);
        assertEquals(409, assertThrows(BusinessException.class, () -> service.delete(73L)).getCode());
        verify(questions, never()).insert(any(ProbQuestion.class));
    }

    @Test void dryRunValidatesEveryItemWithoutWritingOrMaterializing() {
        ProblemWriteRequest first = new ProblemWriteRequest(); first.setTitle("合法题"); first.setDifficulty(1);
        TestCaseWriteRequest tc = new TestCaseWriteRequest(); tc.setKind("normal"); tc.setInput("1"); tc.setOutput("2"); tc.setScore(100);
        first.setTestCases(List.of(tc));
        ProblemWriteRequest invalid = new ProblemWriteRequest(); invalid.setTitle("非法题"); invalid.setDifficulty(6);
        ProblemImportRequest request = new ProblemImportRequest(); request.setFormat("json"); request.setDryRun(true);
        request.setItems(List.of(first, invalid));
        Map<String, Object> result = service.importProblems(request);
        assertEquals(1, result.get("succeeded")); assertEquals(1, result.get("failed"));
        verify(questions, never()).insert(any(ProbQuestion.class)); verifyNoInteractions(storage);
    }

    private ProbTestCase testCase(Long id, int seq, int score) {
        ProbTestCase tc = new ProbTestCase(); tc.setId(id); tc.setProblemId(73L); tc.setSeqNo(seq);
        tc.setScore(score); tc.setKind("normal"); tc.setIsPublic(false); return tc;
    }
}
