package com.oj.problem.service;

import com.oj.common.core.exception.BusinessException;
import com.oj.problem.entity.ProbTestCase;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * DB 只存4KB摘要。完整文件先暂存，提交后按 caseId 保存，再异步物化 seqNo 文件。
 * 快照用同卷硬链接保留已有的大文件；任何操作都不从摘要重建截断的测试数据。
 */
@Component
public class TestCaseStorage {
    public static final int MAX_CONTENT_BYTES = 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(TestCaseStorage.class);
    private final Path root;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "problem-testcase-materializer");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Long, Future<?>> pending = new ConcurrentHashMap<>();

    public TestCaseStorage(@Value("${oj.testcase.root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public record Content(String text, boolean truncated) { }
    public record FilesForCase(ProbTestCase testCase, Path input, Path output) { }

    public Path source(ProbTestCase tc, boolean input) {
        String suffix = input ? ".in" : ".out";
        Path managed = root.resolve(".managed").resolve(tc.getProblemId().toString())
                .resolve(tc.getId() + suffix);
        return Files.isRegularFile(managed) ? managed
                : root.resolve(tc.getProblemId().toString()).resolve(tc.getSeqNo() + suffix);
    }

    public Content read(ProbTestCase tc, boolean input) {
        Path file = source(tc, input);
        try {
            if (Files.isRegularFile(file)) {
                long size = Files.size(file);
                if (size <= MAX_CONTENT_BYTES) return new Content(Files.readString(file), false);
                try (var stream = Files.newInputStream(file)) {
                    return new Content(decodePrefix(stream.readNBytes(4096)), true);
                }
            }
            String summary = input ? tc.getInputText() : tc.getOutputText();
            int size = input ? value(tc.getInputSize()) : value(tc.getOutputSize());
            if (size <= bytes(summary)) return new Content(summary == null ? "" : summary, false);
            throw BusinessException.conflict("测试点完整文件缺失，请恢复磁盘数据后重试");
        } catch (IOException e) {
            throw BusinessException.internalError("读取测试点文件失败");
        }
    }

    /** 保存时把旧点映射传进来，重排后仍按旧序号定位首次导入的文件。 */
    public void schedule(Long problemId, List<ProbTestCase> cases, Map<Long, ProbTestCase> originals,
                         Map<Long, String> inputs, Map<Long, String> outputs) {
        Path staging = root.resolve(".pending").resolve(UUID.randomUUID().toString());
        List<FilesForCase> snapshot = new ArrayList<>();
        try {
            Files.createDirectories(staging);
            for (ProbTestCase tc : cases) {
                Path input = staging.resolve(tc.getId() + ".in");
                Path output = staging.resolve(tc.getId() + ".out");
                stage(input, tc, originals.get(tc.getId()), inputs.get(tc.getId()), true);
                stage(output, tc, originals.get(tc.getId()), outputs.get(tc.getId()), false);
                snapshot.add(new FilesForCase(tc, input, output));
            }
        } catch (IOException | RuntimeException e) {
            cleanup(staging);
            if (e instanceof BusinessException be) throw be;
            throw BusinessException.internalError("暂存完整测试数据失败，未保存变更");
        }
        Runnable committed = () -> {
            try {
                Path managed = root.resolve(".managed").resolve(problemId.toString());
                Files.createDirectories(managed);
                for (FilesForCase item : snapshot) {
                    replace(item.input(), managed.resolve(item.testCase().getId() + ".in"));
                    replace(item.output(), managed.resolve(item.testCase().getId() + ".out"));
                }
                pending.put(problemId, executor.submit(() -> materialize(problemId, snapshot, staging)));
            } catch (IOException e) {
                // 暂存目录保留完整快照，绝不删除可供恢复的唯一正文。
                log.error("测试点提交后保存失败，完整快照保留于 {}", staging, e);
                throw BusinessException.internalError("数据已保存，测试文件待恢复，请联系管理员");
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { committed.run(); }
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) cleanup(staging);
                }
            });
        } else committed.run();
    }

    private void stage(Path destination, ProbTestCase tc, ProbTestCase original, String replacement,
                       boolean input) throws IOException {
        if (replacement != null) {
            Files.writeString(destination, replacement, StandardCharsets.UTF_8);
        } else if (original != null && Files.isRegularFile(source(original, input))) {
            linkOrCopy(source(original, input), destination);
        } else {
            ProbTestCase from = original == null ? tc : original;
            Content content = read(from, input);
            if (content.truncated()) throw BusinessException.conflict("无法从摘要恢复完整测试数据");
            Files.writeString(destination, content.text(), StandardCharsets.UTF_8);
        }
    }

    private void materialize(Long problemId, List<FilesForCase> snapshot, Path staging) {
        try {
            Path directory = root.resolve(problemId.toString());
            Files.createDirectories(directory);
            for (FilesForCase item : snapshot) {
                replace(item.input(), directory.resolve(item.testCase().getSeqNo() + ".in"));
                replace(item.output(), directory.resolve(item.testCase().getSeqNo() + ".out"));
            }
            cleanup(staging);
        } catch (IOException e) {
            log.error("异步物化题目 {} 失败，保留快照 {}", problemId, staging, e);
            throw new CompletionException(e);
        }
    }

    /** 判题配置与发布在读目录前等待已排队的物化，避免新元数据配到旧内容。 */
    public void awaitReady(Long problemId) {
        Future<?> task = pending.get(problemId);
        if (task == null) return;
        try {
            task.get(30, TimeUnit.SECONDS);
            pending.remove(problemId, task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BusinessException.internalError("等待测试数据物化被中断");
        } catch (ExecutionException | TimeoutException e) {
            throw BusinessException.conflict("测试数据尚未就绪，请重试物化后再判题或发布");
        }
    }

    public void refresh(Long problemId, List<ProbTestCase> cases) {
        schedule(problemId, cases, cases.stream().collect(java.util.stream.Collectors.toMap(
                ProbTestCase::getId, tc -> tc)), Map.of(), Map.of());
    }

    private static void replace(Path source, Path target) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        linkOrCopy(source, temp);
        try {
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private static void linkOrCopy(Path source, Path target) throws IOException {
        try { Files.createLink(target, source); }
        catch (IOException | UnsupportedOperationException e) { Files.copy(source, target); }
    }

    private static void cleanup(Path staging) {
        if (!Files.isDirectory(staging)) return;
        try (var children = Files.list(staging)) {
            for (Path child : children.toList()) Files.deleteIfExists(child);
            Files.deleteIfExists(staging);
        } catch (IOException e) { log.warn("清理测试数据暂存目录失败 {}", staging, e); }
    }

    public static int bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    public static String summary(String text) {
        byte[] encoded = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        return decodePrefix(Arrays.copyOf(encoded, Math.min(encoded.length, 4096)));
    }

    private static String decodePrefix(byte[] encoded) {
        // UTF-8 的尾部如果被截在多字节字符中，丢掉残缺字符而非补一个 U+FFFD。
        var decoder = StandardCharsets.UTF_8.newDecoder();
        var buffer = java.nio.CharBuffer.allocate(encoded.length);
        decoder.decode(ByteBuffer.wrap(encoded), buffer, false);
        buffer.flip();
        return buffer.toString();
    }

    private static int value(Integer number) { return number == null ? 0 : number; }

    @PreDestroy public void close() { executor.shutdown(); }
}
