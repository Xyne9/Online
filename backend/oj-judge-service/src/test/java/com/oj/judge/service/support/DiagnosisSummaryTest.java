package com.oj.judge.service.support;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosisSummaryTest {
    @Test void legacyHiddenAnswersAreReducedToCategories() {
        var raw = Map.<String,Object>of("status", "WA", "seqNo", 3, "kind", "hidden", "expectedOut", "-12345", "actualOut", "0");
        var safe = DiagnosisSummary.sanitize(raw, false);
        assertFalse(safe.containsKey("expectedOut")); assertFalse(safe.containsKey("actualOut"));
        assertEquals("NEGATIVE_NUMBER", safe.get("expectedType")); assertEquals("ZERO", safe.get("actualType"));
        assertEquals(3, safe.get("seqNo")); assertFalse(safe.toString().contains("-12345"));
    }
    @Test void publiclyVisibleSamplesKeepTheirOutput() {
        var sample = Map.<String,Object>of("expectedOut", "3");
        assertEquals(sample, DiagnosisSummary.sanitize(sample, true));
    }
}
