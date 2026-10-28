package com.oj.admin.service;

import com.oj.admin.config.AdminProperties;
import com.oj.admin.dao.AdminSandboxDao;
import com.oj.common.api.dto.admin.SandboxKillDTO;
import com.oj.common.api.feign.JudgeInnerFeignClient;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.result.Result;
import feign.FeignException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminSandboxServiceTest {
    private final AdminSandboxDao dao = mock(AdminSandboxDao.class);
    private final JudgeInnerFeignClient judge = mock(JudgeInnerFeignClient.class);
    private final AdminSandboxService service = new AdminSandboxService(dao, new AdminProperties(), judge);

    @Test
    void returnsOnlyJudgeConfirmedCancellation() {
        when(dao.findByNo("sbx-test")).thenReturn(Map.of("state", "RUNNING"));
        SandboxKillDTO killed = new SandboxKillDTO();
        killed.setSandboxInstanceId("sbx-test");
        killed.setState("KILLED");
        killed.setExitCode(137);
        when(judge.killSandbox("sbx-test", Map.of("reason", "停止运行"))).thenReturn(Result.ok(killed));

        assertSame(killed, service.kill("sbx-test", "停止运行"));
        verify(judge).killSandbox("sbx-test", Map.of("reason", "停止运行"));
    }

    @Test
    void completedInstanceConflictsWithoutCallingJudge() {
        when(dao.findByNo("sbx-test")).thenReturn(Map.of("state", "EXITED"));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.kill("sbx-test", null)).getCode());
        verifyNoInteractions(judge);
    }

    @Test
    void missingInstanceIsNotFound() {
        when(dao.findByNo("missing")).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.kill("missing", null)).getCode());
        verifyNoInteractions(judge);
    }

    @Test
    void judgeUnavailableDoesNotReportKilled() {
        when(dao.findByNo("sbx-test")).thenReturn(Map.of("state", "RUNNING"));
        FeignException unavailable = mock(FeignException.class);
        when(unavailable.status()).thenReturn(503);
        when(judge.killSandbox("sbx-test", Map.of())).thenThrow(unavailable);
        assertEquals(502, assertThrows(BusinessException.class,
                () -> service.kill("sbx-test", null)).getCode());
    }

    @Test
    void completionRacePreservesConflict() {
        when(dao.findByNo("sbx-test")).thenReturn(Map.of("state", "RUNNING"));
        FeignException conflict = mock(FeignException.class);
        when(conflict.status()).thenReturn(409);
        when(judge.killSandbox("sbx-test", Map.of())).thenThrow(conflict);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.kill("sbx-test", null)).getCode());
    }

    @Test
    void invalidAcknowledgementIsNotSuccess() {
        when(dao.findByNo("sbx-test")).thenReturn(Map.of("state", "RUNNING"));
        when(judge.killSandbox("sbx-test", Map.of())).thenReturn(Result.ok());
        assertEquals(502, assertThrows(BusinessException.class,
                () -> service.kill("sbx-test", null)).getCode());
    }

    @Test
    void logsFilterByActualLineTimestampBeforeTakingTail() {
        when(dao.findByNo("sbx-test")).thenReturn(Map.of("state", "RUNNING", "log_ref",
                "2026-10-28T01:00:00Z COMPILING\n2026-10-28T01:00:01Z JUDGING\n2026-10-28T01:00:02Z case 1 status=Accepted\n"));
        long since = java.time.Instant.parse("2026-10-28T01:00:01Z").toEpochMilli();
        var logs = service.logs("sbx-test", 1, since);
        assertEquals(1, logs.getLines().size());
        assertEquals(since + 1000, logs.getLines().get(0).getTs());
        assertTrue(logs.getTruncated());
        assertTrue(logs.getLines().get(0).getMsg().contains("case 1"));
    }

    @Test
    void logsSinceAfterLastEntryReturnsEmpty() {
        when(dao.findByNo("sbx-test")).thenReturn(Map.of("state", "EXITED", "log_ref",
                "2026-10-28T01:00:00Z EXITED\n"));
        var logs = service.logs("sbx-test", 10, java.time.Instant.parse("2026-10-28T01:00:01Z").toEpochMilli());
        assertTrue(logs.getLines().isEmpty());
        assertFalse(logs.getTruncated());
    }
}
