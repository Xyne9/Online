package com.oj.teaching.service;

import com.oj.common.core.exception.BusinessException;
import com.oj.teaching.dto.PlagiarismRequests;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlagiarismReviewServiceTest {
    private final PlagiarismRepository repository = mock(PlagiarismRepository.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final PlagiarismAccess access = new PlagiarismAccess(repository);
    private PlagiarismReviewService reviews;
    private Map<String,Object> report;
    @BeforeEach void setUp() {
        var request=new MockHttpServletRequest(); request.addHeader("X-User-Id","9"); request.addHeader("X-User-Role","TEACHER");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(repository.assignment(eq(12L),anyBoolean())).thenReturn(Map.of("teacher_id",9L,"class_teacher_id",9L));
        report=new HashMap<>(Map.of("id",80L,"assignment_id",12L,"problem_id",1006L,"user_a_id",4L,"user_b_id",7L,"audit_status",0,"stage2_ai_sim",96.0));
        when(repository.report(80L)).thenAnswer(call -> new HashMap<>(report));
        when(repository.score(12,1006,4)).thenReturn(new HashMap<>(Map.of("max_score",96)));
        when(repository.score(12,1006,7)).thenReturn(new HashMap<>(Map.of("max_score",87)));
        doAnswer(call->{report.put("audit_status",call.getArgument(1)); return null;}).when(repository).audit(eq(80L),anyInt(),any(),any(),any());
        reviews=new PlagiarismReviewService(repository,access,manager);
    }
    @AfterEach void clean() { RequestContextHolder.resetRequestAttributes(); }
    @Test void confirmationValidatesAndClearsBothParticipantsAndCommitsWithTheReport() {
        var result=reviews.audit(new PlagiarismRequests.Audit(80L,1,"已核实"));
        assertEquals(Map.of("userId",7L,"maxScore",0),result.get("affectedScore"));
        assertEquals(List.of(Map.of("userId",4L,"maxScore",0),Map.of("userId",7L,"maxScore",0)),result.get("affectedScores"));
        var order=inOrder(repository,manager);
        order.verify(repository).assignment(12,true);
        order.verify(repository).score(12,1006,4);
        order.verify(repository).score(12,1006,7);
        order.verify(repository).clearScore(12,1006,4);
        order.verify(repository).clearScore(12,1006,7);
        order.verify(repository).audit(eq(80L),eq(1),eq(9L),anyLong(),eq("已核实"));
        order.verify(manager).commit(any());
    }
    @Test void repeatedConfirmationCannotOverwriteTheOriginalScore() {
        report.put("audit_status",1);
        when(repository.score(12,1006,4)).thenReturn(Map.of("max_score",0,"prev_max_score",96));
        var error=assertThrows(BusinessException.class,()->reviews.audit(new PlagiarismRequests.Audit(80L,1,null)));
        assertEquals(409,error.getCode()); verify(repository,never()).clearScore(anyLong(),anyLong(),anyLong());
        verify(manager).rollback(any());
    }
    @Test void undoRestoresTheSavedScoreAndClearsTheReviewAtomically() {
        report.put("audit_status",1);
        when(repository.score(12,1006,4)).thenReturn(Map.of("max_score",0,"prev_max_score",96));
        when(repository.score(12,1006,7)).thenReturn(Map.of("max_score",0,"prev_max_score",87));
        var result=reviews.undo(80);
        assertEquals(Map.of("userId",7L,"maxScore",87),result.get("restoredScore"));
        assertEquals(List.of(Map.of("userId",4L,"maxScore",96),Map.of("userId",7L,"maxScore",87)),result.get("restoredScores"));
        var order=inOrder(repository,manager);
        order.verify(repository).restoreScore(12,1006,4);
        order.verify(repository).restoreScore(12,1006,7); order.verify(repository).audit(80,0,null,null,null); order.verify(manager).commit(any());
    }
    @Test void anotherConfirmedReportKeepsScoreClearedUntilItsOwnUndo() {
        report.put("audit_status",1);
        when(repository.score(12,1006,4)).thenReturn(Map.of("max_score",0,"prev_max_score",96));
        when(repository.score(12,1006,7)).thenReturn(Map.of("max_score",0,"prev_max_score",87));
        when(repository.otherConfirmed(12,1006,7,80)).thenReturn(1L);
        assertEquals(Map.of("userId",7L,"maxScore",0),reviews.undo(80).get("restoredScore"));
        verify(repository).restoreScore(12,1006,4);
        verify(repository,never()).restoreScore(12,1006,7);
    }
    @Test void missingOriginalScoreRollsBackInsteadOfRestoringZero() {
        report.put("audit_status",1);
        when(repository.score(12,1006,4)).thenReturn(Map.of("max_score",0,"prev_max_score",96));
        when(repository.score(12,1006,7)).thenReturn(Map.of("max_score",0));
        assertEquals(409,assertThrows(BusinessException.class,()->reviews.undo(80)).getCode());
        verify(repository,never()).restoreScore(anyLong(),anyLong(),anyLong());
        verify(repository,never()).audit(anyLong(),anyInt(),any(),any(),any()); verify(manager).rollback(any());
    }
    @Test void exclusionDoesNotTouchScores() {
        var result=reviews.audit(new PlagiarismRequests.Audit(80L,2,"模板雷同"));
        assertNull(result.get("affectedScore")); assertEquals(List.of(),result.get("affectedScores"));
        verify(repository,never()).score(anyLong(),anyLong(),anyLong());
    }
    @Test void unrelatedTeacherAndStudentAreForbidden() {
        when(repository.assignment(eq(12L),anyBoolean())).thenReturn(Map.of("teacher_id",8L,"class_teacher_id",8L));
        assertEquals(403,assertThrows(BusinessException.class,()->reviews.undo(80)).getCode());
        var request=new MockHttpServletRequest(); request.addHeader("X-User-Id","9"); request.addHeader("X-User-Role","STUDENT");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertEquals(403,assertThrows(BusinessException.class,()->reviews.audit(new PlagiarismRequests.Audit(80L,1,null))).getCode());
        verify(repository,never()).clearScore(anyLong(),anyLong(),anyLong());
    }
    @Test void noAiConclusionCannotBeConfirmed() {
        report.put("stage2_ai_sim",null);
        assertEquals(409,assertThrows(BusinessException.class,()->reviews.audit(new PlagiarismRequests.Audit(80L,1,null))).getCode());
    }
    @Test void missingSecondParticipantScoreDoesNotClearTheFirst() {
        when(repository.score(12,1006,7)).thenReturn(null);
        assertEquals(409,assertThrows(BusinessException.class,()->reviews.audit(new PlagiarismRequests.Audit(80L,1,null))).getCode());
        verify(repository,never()).clearScore(anyLong(),anyLong(),anyLong());
        verify(repository,never()).audit(anyLong(),anyInt(),any(),any(),any()); verify(manager).rollback(any());
    }
    @Test void crossSideConfirmationKeepsSharedParticipantZeroAndRestoresEachUnrelatedParticipant() {
        // Report 80: A=4/B=7. Report 81: A=7/B=8. User 7 occurs on opposite sides.
        report.put("audit_status",1);
        var second=new HashMap<String,Object>(Map.of("id",81L,"assignment_id",12L,"problem_id",1006L,"user_a_id",7L,"user_b_id",8L,"audit_status",1));
        when(repository.report(81L)).thenAnswer(call -> new HashMap<>(second));
        doAnswer(call->{second.put("audit_status",call.getArgument(1));return null;}).when(repository).audit(eq(81L),anyInt(),any(),any(),any());
        when(repository.score(12,1006,4)).thenReturn(Map.of("max_score",0,"prev_max_score",96));
        when(repository.score(12,1006,7)).thenReturn(Map.of("max_score",0,"prev_max_score",87));
        when(repository.score(12,1006,8)).thenReturn(Map.of("max_score",0,"prev_max_score",74));
        when(repository.otherConfirmed(12,1006,7,80)).thenAnswer(call -> ((Number)second.get("audit_status")).intValue()==1 ? 1L : 0L);
        when(repository.otherConfirmed(12,1006,7,81)).thenAnswer(call -> ((Number)report.get("audit_status")).intValue()==1 ? 1L : 0L);
        var firstUndo=reviews.undo(80);
        assertEquals(List.of(Map.of("userId",4L,"maxScore",96),Map.of("userId",7L,"maxScore",0)),firstUndo.get("restoredScores"));
        verify(repository,never()).restoreScore(12,1006,7);
        var secondUndo=reviews.undo(81);
        assertEquals(List.of(Map.of("userId",7L,"maxScore",87),Map.of("userId",8L,"maxScore",74)),secondUndo.get("restoredScores"));
        assertEquals(Map.of("userId",8L,"maxScore",74),secondUndo.get("restoredScore"));
        verify(repository).restoreScore(12,1006,7); verify(repository).restoreScore(12,1006,8);
    }
    @Test void rerunRestorationAlsoRestoresBothParticipants() {
        report.put("audit_status",1);
        when(repository.score(12,1006,4)).thenReturn(Map.of("max_score",0,"prev_max_score",96));
        when(repository.score(12,1006,7)).thenReturn(Map.of("max_score",0,"prev_max_score",87));
        assertEquals(Map.of("userId",7L,"maxScore",87),reviews.restoreBeforeReset(report));
        verify(repository).restoreScore(12,1006,4); verify(repository).restoreScore(12,1006,7);
    }
    @Test void otherConfirmedSqlChecksMembershipOnEitherSide() {
        var db=mock(JdbcTemplate.class);
        when(db.queryForObject(anyString(),eq(Long.class),eq(12L),eq(1006L),eq(7L),eq(7L),eq(80L))).thenReturn(1L);
        assertEquals(1L,new PlagiarismRepository(db).otherConfirmed(12,1006,7,80));
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).queryForObject(sql.capture(),eq(Long.class),eq(12L),eq(1006L),eq(7L),eq(7L),eq(80L));
        assertTrue(sql.getValue().contains("(user_a_id=? OR user_b_id=?)"));
    }
}
