package com.oj.teaching.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.common.core.redis.RedisKeys;
import jakarta.annotation.PreDestroy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static com.oj.teaching.service.PlagiarismAccess.number;
import static com.oj.teaching.service.PlagiarismQueryService.*;

/** Polling and SSE both read the exact same persisted progress snapshot. */
@Service
public class PlagiarismProgressService {
    private final PlagiarismRepository repository;
    private final PlagiarismAccess access;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "plagiarism-sse"); t.setDaemon(true); return t; });
    private final ConcurrentMap<Long, CopyOnWriteArrayList<SseEmitter>> listeners = new ConcurrentHashMap<>();
    public PlagiarismProgressService(PlagiarismRepository repository, PlagiarismAccess access, StringRedisTemplate redis, ObjectMapper json) {
        this.repository = repository; this.access = access; this.redis = redis; this.json = json;
    }
    public Map<String, Object> progress(long id) { var task = access.task(id); return cached(task); }
    Map<String, Object> cached(Map<String, Object> task) {
        long id = number(task, "id");
        try {
            Object value = redis.opsForHash().get(RedisKeys.plagiarismTask(Long.toString(id)), "snapshot");
            if (value != null) {
                Map<String, Object> snapshot = json.readValue(value.toString(), new TypeReference<>() { });
                // A terminal DB state always wins over an older cache after an interrupted write.
                var persisted = snapshot(task);
                boolean current = List.of("state","progressPercent","comparedPairs","suspiciousCount","highRiskCount").stream()
                        .allMatch(key -> Objects.toString(persisted.get(key), "").equals(Objects.toString(snapshot.get(key), "")));
                if (current) return snapshot;
            }
        } catch (Exception ignored) { }
        return snapshot(task);
    }
    public void publish(long id) {
        var task = repository.task(id); if (task == null) return;
        var snapshot = snapshot(task);
        try {
            String key = RedisKeys.plagiarismTask(Long.toString(id));
            Map<String, String> values = new HashMap<>();
            snapshot.forEach((field, value) -> { if (value != null) values.put(field, value.toString()); });
            values.put("snapshot", json.writeValueAsString(snapshot)); redis.opsForHash().putAll(key, values);
            redis.expire(key, Duration.ofSeconds(RedisKeys.TTL_PLAGIARISM_TASK));
        } catch (Exception ignored) { /* DB remains authoritative if cache is unavailable. */ }
        broadcast(id, terminal(snapshot) ? snapshot.get("state").equals("DONE") ? "done" : "failed" : "progress", eventPayload(snapshot));
    }
    public void pair(long id, Map<String, Object> pair) { broadcast(id, "pair", pair); }
    public SseEmitter events(long id) {
        var task = access.task(id); var emitter = new SseEmitter(1_800_000L);
        var bucket = listeners.computeIfAbsent(id, key -> new CopyOnWriteArrayList<>()); bucket.add(emitter);
        AtomicReference<ScheduledFuture<?>> timer = new AtomicReference<>();
        Runnable cleanup = () -> { bucket.remove(emitter); if (bucket.isEmpty()) listeners.remove(id, bucket); var future = timer.get(); if (future != null) future.cancel(false); };
        emitter.onCompletion(cleanup); emitter.onTimeout(cleanup); emitter.onError(e -> cleanup.run());
        var snapshot = cached(task);
        send(emitter, "progress", snapshot);
        if (terminal(snapshot)) { send(emitter, snapshot.get("state").equals("DONE") ? "done" : "failed", eventPayload(snapshot)); emitter.complete(); cleanup.run(); }
        else {
            java.util.concurrent.atomic.AtomicInteger beats = new java.util.concurrent.atomic.AtomicInteger();
            timer.set(scheduler.scheduleAtFixedRate(() -> {
            if (!bucket.contains(emitter)) return;
            try {
                if (beats.incrementAndGet() % 7 == 0) emitter.send(SseEmitter.event().comment("ping"));
                var current = repository.task(id);
                if (current != null && terminal(snapshot(current))) {
                    var value = cached(current); send(emitter, value.get("state").equals("DONE") ? "done" : "failed", eventPayload(value)); emitter.complete(); cleanup.run();
                } else if (current != null) send(emitter, "progress", cached(current));
            } catch (IOException | IllegalStateException e) { cleanup.run(); }
        }, 2, 2, TimeUnit.SECONDS));
            if (!bucket.contains(emitter)) cleanup.run();
        }
        return emitter;
    }
    private void broadcast(long id, String name, Object data) {
        var bucket = listeners.get(id); if (bucket == null) return;
        for (var emitter : bucket) {
            if (!send(emitter, name, data)) bucket.remove(emitter);
            else if (name.equals("done") || name.equals("failed")) { emitter.complete(); bucket.remove(emitter); }
        }
        if (bucket.isEmpty()) listeners.remove(id, bucket);
    }
    private static boolean send(SseEmitter emitter, String event, Object data) {
        try { emitter.send(SseEmitter.event().name(event).data(data)); return true; }
        catch (IOException | IllegalStateException e) { return false; }
    }
    private static boolean terminal(Map<String, Object> task) { return Set.of("DONE", "FAILED").contains(task.get("state")); }
    private static Object eventPayload(Map<String, Object> snapshot) {
        if (snapshot.get("state").equals("DONE")) return view("taskId", snapshot.get("taskId"), "state", "DONE", "totalPairs", snapshot.get("totalPairs"), "highRiskCount", snapshot.get("highRiskCount"),
                "elapsedMs", snapshot.get("startedAt") == null || snapshot.get("finishedAt") == null ? 0 : ((Number)snapshot.get("finishedAt")).longValue() - ((Number)snapshot.get("startedAt")).longValue());
        if (snapshot.get("state").equals("FAILED")) return view("taskId", snapshot.get("taskId"), "state", "FAILED", "errorMsg", snapshot.get("errorMsg"));
        return snapshot;
    }
    private static Map<String, Object> snapshot(Map<String, Object> task) {
        return view("taskId", task.get("id"), "displayNo", task.get("display_no"), "state", task.get("state"), "progressPercent", task.get("progress_percent"),
                "totalPairs", task.get("total_pairs"), "comparedPairs", task.get("compared_pairs"), "suspiciousCount", task.get("suspicious_count"), "highRiskCount", task.get("high_risk_count"),
                "startedAt", epoch(task.get("started_at")), "finishedAt", epoch(task.get("finished_at")), "errorMsg", task.get("error_msg"));
    }
    @PreDestroy void stop() { scheduler.shutdownNow(); listeners.values().forEach(bucket -> bucket.forEach(SseEmitter::complete)); listeners.clear(); }
}
