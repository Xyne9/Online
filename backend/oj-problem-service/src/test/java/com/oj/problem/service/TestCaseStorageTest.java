package com.oj.problem.service;

import com.oj.problem.entity.ProbTestCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TestCaseStorageTest {
    @TempDir Path root;

    @Test
    void fullContentAndUtf8SummaryRemainSeparate() throws Exception {
        try (StorageClose storage = open()) {
            ProbTestCase tc = testCase(81L, 1);
            String text = "输入".repeat(5000);
            tc.setInputText(TestCaseStorage.summary(text));
            tc.setInputSize(TestCaseStorage.bytes(text));
            assertTrue(tc.getInputText().getBytes(StandardCharsets.UTF_8).length <= 4096);
            assertFalse(tc.getInputText().contains("�"));
            storage.value.schedule(73L, List.of(tc), Map.of(), Map.of(81L, text), Map.of(81L, "结果\n"));
            storage.value.awaitReady(73L);
            assertEquals(text, Files.readString(root.resolve("73/1.in")));
            assertEquals(text, storage.value.read(tc, true).text());
        }
    }

    @Test
    void reorderingAndCopyingPreserveLargeImportedFilesBeyondSummaryAndEditorLimit() throws Exception {
        Files.createDirectories(root.resolve("73"));
        String large = "1234567890\n".repeat(110000);
        Files.writeString(root.resolve("73/1.in"), large);
        Files.writeString(root.resolve("73/1.out"), "first\n");
        Files.writeString(root.resolve("73/2.in"), "second\n");
        Files.writeString(root.resolve("73/2.out"), "second result\n");
        try (StorageClose storage = open()) {
            ProbTestCase oldFirst = testCase(81L, 1);
            oldFirst.setInputText(TestCaseStorage.summary(large));
            oldFirst.setInputSize(TestCaseStorage.bytes(large));
            ProbTestCase oldSecond = testCase(82L, 2);
            ProbTestCase first = testCase(81L, 2);
            ProbTestCase second = testCase(82L, 1);
            assertTrue(storage.value.read(oldFirst, true).truncated());
            storage.value.schedule(73L, List.of(first, second), Map.of(81L, oldFirst, 82L, oldSecond), Map.of(), Map.of());
            storage.value.awaitReady(73L);
            assertEquals("second\n", Files.readString(root.resolve("73/1.in")));
            assertEquals(large, Files.readString(root.resolve("73/2.in")));
            ProbTestCase duplicate = testCase(91L, 1);
            duplicate.setProblemId(74L);
            storage.value.schedule(74L, List.of(duplicate), Map.of(91L, first), Map.of(), Map.of());
            storage.value.awaitReady(74L);
            assertEquals(large, Files.readString(root.resolve("74/1.in")));
            assertEquals(large, Files.readString(root.resolve("73/2.in")));
        }
    }

    @Test
    void rollbackDoesNotReplaceImportedFilesOrLeaveStagedData() throws Exception {
        Files.createDirectories(root.resolve("73"));
        Files.writeString(root.resolve("73/1.in"), "original");
        Files.writeString(root.resolve("73/1.out"), "result");
        try (StorageClose storage = open()) {
            TransactionSynchronizationManager.initSynchronization();
            try {
                storage.value.schedule(73L, List.of(testCase(81L, 1)), Map.of(),
                        Map.of(81L, "replacement"), Map.of(81L, "new result"));
                assertEquals("original", Files.readString(root.resolve("73/1.in")));
                for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations())
                    sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            } finally { TransactionSynchronizationManager.clearSynchronization(); }
            assertEquals("original", Files.readString(root.resolve("73/1.in")));
            try (var directories = Files.list(root.resolve(".pending"))) { assertEquals(0, directories.count()); }
        }
    }

    private ProbTestCase testCase(Long id, int seq) {
        ProbTestCase tc = new ProbTestCase();
        tc.setId(id); tc.setProblemId(73L); tc.setSeqNo(seq);
        tc.setInputText(""); tc.setOutputText(""); tc.setInputSize(0); tc.setOutputSize(0);
        return tc;
    }
    private StorageClose open() { return new StorageClose(new TestCaseStorage(root.toString())); }
    private record StorageClose(TestCaseStorage value) implements AutoCloseable {
        public void close() { value.close(); }
    }
}
