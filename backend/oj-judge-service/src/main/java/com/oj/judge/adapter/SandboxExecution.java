package com.oj.judge.adapter;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One MQ submission's cancellation scope. Cancels HTTP exchanges, never the Rabbit consumer thread. */
public final class SandboxExecution implements AutoCloseable {
    private static final ThreadLocal<SandboxExecution> CURRENT = new ThreadLocal<>();
    private final Consumer<String> log;
    private final Consumer<String> phase;
    private CompletableFuture<?> request;
    private volatile boolean cancelled;

    public SandboxExecution(Consumer<String> log, Consumer<String> phase) {
        this.log = log;
        this.phase = phase;
    }

    public static SandboxExecution current() { return CURRENT.get(); }

    public SandboxExecution bind() {
        if (CURRENT.get() != null) throw new IllegalStateException("Sandbox scope already bound");
        CURRENT.set(this);
        return this;
    }

    public void checkCancelled() {
        if (cancelled) throw new CancellationException("Sandbox terminated by administrator");
    }

    /** Starting the exchange and publishing its handle share the same lock as cancel(). */
    public synchronized <T> CompletableFuture<T> start(Supplier<CompletableFuture<T>> sender) {
        checkCancelled();
        CompletableFuture<T> future = sender.get();
        request = future;
        return future;
    }

    public synchronized void clear(CompletableFuture<?> future) {
        if (request == future) request = null;
    }

    public synchronized void cancel() {
        cancelled = true;
        // JDK 17 HttpClient's original sendAsync future propagates cancellation to the HTTP exchange.
        if (request != null) request.cancel(true);
    }

    public void phase(String value) {
        checkCancelled();
        phase.accept(value);
        checkCancelled();
    }

    public void log(String value) { log.accept(value); }

    public static void phaseCurrent(String value) {
        SandboxExecution scope = current();
        if (scope != null) scope.phase(value);
    }

    public static void logCurrent(String value) {
        SandboxExecution scope = current();
        if (scope != null) scope.log(value);
    }

    @Override public void close() { CURRENT.remove(); }
}
