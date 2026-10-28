package com.oj.judge.service.support;

import com.oj.common.core.exception.BusinessException;
import com.oj.judge.adapter.SandboxExecution;
import com.oj.judge.entity.JudgeSubmission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SandboxLifecycleTest {
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final SandboxLifecycle lifecycle = new SandboxLifecycle(db, manager);
    final JudgeSubmission submission = new JudgeSubmission();

    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        submission.setId(10L);
        submission.setSandboxInstanceId("sbx-0010");
    }

    private void row(String state) {
        when(db.queryForList(anyString(), eq("sbx-0010"))).thenReturn(List.of(
                Map.of("state", state, "cpu_limit", 1, "mem_limit_kb", 65536)));
    }

    @Test void queuedKillPersistsAdministratorReasonAndSubmissionRE() {
        row("QUEUED");
        var result = lifecycle.kill("sbx-0010", "stop runaway submission");
        assertEquals("KILLED", result.getState());
        assertEquals(137, result.getExitCode());
        verify(db).update(contains("SET status=8"), eq("管理员终止判题：stop runaway submission"), eq("sbx-0010"));
        verify(db).update(contains("SET state='KILLED'"), contains("stop runaway submission"), eq("sbx-0010"));
    }

    @Test void activeRunningKillCancelsRegisteredExchange() {
        row("QUEUED");
        SandboxExecution execution = lifecycle.start(submission);
        CompletableFuture<String> pending = execution.start(CompletableFuture::new);
        row("RUNNING");
        lifecycle.kill("sbx-0010", null);
        assertTrue(pending.isCancelled());
        assertThrows(java.util.concurrent.CancellationException.class, execution::checkCancelled);
        lifecycle.release(submission, execution);
    }

    @Test void runningWithoutLocalHandleCannotClaimResourceReclamation() {
        row("RUNNING");
        assertEquals(409, assertThrows(BusinessException.class,
                () -> lifecycle.kill("sbx-0010", null)).getCode());
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void terminalInstancesCannotBeStartedAgainOnMqReplay() {
        for (String state : List.of("KILLED", "EXITED")) {
            row(state);
            assertNull(lifecycle.start(submission));
        }
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void abandonedRunningInstanceRestartsCompilationOnMqReplay() {
        row("RUNNING");
        SandboxExecution recovered = lifecycle.start(submission);
        assertNotNull(recovered, "Redelivery after a JVM exit must execute instead of ACKing a stuck RUNNING row");
        verify(db).update(contains("SET state='RUNNING'"), contains("RECOVERED: MQ replay"), eq("sbx-0010"));
        verify(db).update(contains("SET status=1"), eq(10L));
        CompletableFuture<String> exchange = recovered.start(CompletableFuture::new);
        lifecycle.kill("sbx-0010", "cancel recovered attempt");
        assertTrue(exchange.isCancelled(), "The recovered attempt must own a usable cancellation handle");
        lifecycle.release(submission, recovered);
    }

    @Test void sameProcessDuplicateCannotRestartAnOwnedRunningInstance() {
        row("RUNNING");
        SandboxExecution recovered = lifecycle.start(submission);
        assertNotNull(recovered);
        assertNull(lifecycle.start(submission));
        verify(db, times(1)).queryForList(anyString(), eq("sbx-0010"));
        verify(db, times(1)).update(contains("SET state='RUNNING'"), anyString(), eq("sbx-0010"));
        lifecycle.release(submission, recovered);
    }

    @Test void killedCompletionNeverWritesResultOrChangesTerminalState() {
        row("KILLED");
        Runnable write = mock(Runnable.class);
        assertFalse(lifecycle.finish(submission, 0, 1024, "AC", write));
        verifyNoInteractions(write);
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void finishedAndKilledRejectDuplicateKill() {
        for (String state : List.of("EXITED", "KILLED")) {
            row(state);
            assertEquals(409, assertThrows(BusinessException.class,
                    () -> lifecycle.kill("sbx-0010", null)).getCode());
        }
    }

    @Test void missingInstanceReturns404() {
        when(db.queryForList(anyString(), eq("missing"))).thenReturn(List.of());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> lifecycle.kill("missing", null)).getCode());
    }
}
