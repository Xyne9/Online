package com.oj.judge.controller;

import com.oj.common.api.dto.admin.SandboxKillDTO;
import com.oj.common.core.exception.BusinessException;
import com.oj.judge.service.support.SandboxLifecycle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SandboxInnerControllerTest {
    @Test void configuredTokenIsRequiredBeforeLookingUpAnInstance() {
        SandboxLifecycle lifecycle = mock(SandboxLifecycle.class);
        SandboxInnerController controller = new SandboxInnerController(lifecycle, "test-only-token");
        assertEquals(403, assertThrows(BusinessException.class,
                () -> controller.kill("sbx-0010", null, null)).getCode());
        assertEquals(403, assertThrows(BusinessException.class,
                () -> controller.kill("sbx-0010", null, "wrong")).getCode());
        verifyNoInteractions(lifecycle);
        when(lifecycle.kill("sbx-0010", null)).thenReturn(new SandboxKillDTO());
        assertTrue(controller.kill("sbx-0010", null, "test-only-token").isSuccess());
    }

    @Test void emptyConfiguredTokenDoesNotEnableAnonymousCancellation() {
        SandboxLifecycle lifecycle = mock(SandboxLifecycle.class);
        SandboxInnerController controller = new SandboxInnerController(lifecycle, "");
        assertEquals(403, assertThrows(BusinessException.class,
                () -> controller.kill("sbx-0010", null, "")).getCode());
        verifyNoInteractions(lifecycle);
    }
}
