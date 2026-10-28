package com.oj.judge.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SandboxCancellationTest {
    @TempDir Path root;

    @Test void cancelledBeforeSendNeverStartsAnExchange() {
        SandboxExecution scope = new SandboxExecution(x -> {}, x -> {});
        scope.cancel();
        AtomicInteger calls = new AtomicInteger();
        assertThrows(CancellationException.class, () -> scope.start(() -> {
            calls.incrementAndGet();
            return new CompletableFuture<>();
        }));
        assertEquals(0, calls.get());
    }

    @Test void cancellingHttpFutureLeavesTheConsumerThreadUsableForAnotherRequest() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService serverThreads = Executors.newCachedThreadPool();
        ExecutorService consumer = Executors.newSingleThreadExecutor();
        server.setExecutor(serverThreads);
        server.createContext("/run", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (calls.incrementAndGet() == 1) {
                received.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        GoJudgeClient client = new GoJudgeClient("http://127.0.0.1:" + server.getAddress().getPort(), 1000, 10000);
        SandboxExecution scope = new SandboxExecution(x -> {}, x -> {});
        try {
            Future<Boolean> first = consumer.submit(() -> {
                try (SandboxExecution ignored = scope.bind()) {
                    assertThrows(CancellationException.class, () -> client.run("{}"));
                    return Thread.currentThread().isInterrupted();
                }
            });
            assertTrue(received.await(2, TimeUnit.SECONDS));
            scope.cancel();
            assertFalse(first.get(2, TimeUnit.SECONDS), "HTTP cancellation must not interrupt Rabbit's consumer thread");
            release.countDown();
            assertEquals("{}", consumer.submit(() -> client.run("{}")).get(2, TimeUnit.SECONDS));
        } finally {
            scope.cancel();
            release.countDown();
            consumer.shutdownNow();
            server.stop(0);
            serverThreads.shutdownNow();
        }
    }

    @Test void cancellationBetweenBatchesPreventsAllLaterRunRequests() throws Exception {
        Path directory = Files.createDirectories(root.resolve("123"));
        for (int seq = 1; seq <= 2; seq++) {
            Files.writeString(directory.resolve(seq + ".in"), "");
            Files.writeString(directory.resolve(seq + ".out"), "0");
        }
        GoJudgeClient client = mock(GoJudgeClient.class);
        when(client.run(anyString())).thenReturn(
                "{\"status\":\"Accepted\",\"exitStatus\":0,\"files\":{\"art.b64\":\"YWJj\"}}",
                "{\"status\":\"Accepted\",\"exitStatus\":0,\"files\":{\"stdout\":\"0\"}}");
        JudgeExecutor executor = new JudgeExecutor(client, new ObjectMapper(), root.toString(),
                "/data/testcases", false, 1024, 1024, 1);
        AtomicReference<SandboxExecution> reference = new AtomicReference<>();
        SandboxExecution scope = new SandboxExecution(log -> {
            if (log.startsWith("case 1 status=")) reference.get().cancel();
        }, phase -> {});
        reference.set(scope);
        try (SandboxExecution ignored = scope.bind()) {
            assertThrows(CancellationException.class, () -> executor.executeWithCaseSpecs(
                    123L, "cpp", "int main(){}", List.of(
                            new JudgeExecutor.CaseSpec(1L, 1, "sample", 50),
                            new JudgeExecutor.CaseSpec(2L, 2, "sample", 50)), 1000, 64));
        }
        verify(client, times(2)).run(anyString()); // compile + first batch only
        assertNull(SandboxExecution.current());
    }
}
