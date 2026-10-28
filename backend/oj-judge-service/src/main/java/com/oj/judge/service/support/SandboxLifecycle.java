package com.oj.judge.service.support;

import com.oj.common.api.dto.admin.SandboxKillDTO;
import com.oj.common.core.exception.BusinessException;
import com.oj.judge.adapter.JudgeAdapter;
import com.oj.judge.adapter.SandboxExecution;
import com.oj.judge.entity.JudgeSubmission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Owns the persisted instance and the live HTTP cancellation handle for each MQ submission. */
@Component
public class SandboxLifecycle {
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final Map<String, SandboxExecution> running = new ConcurrentHashMap<>();

    public SandboxLifecycle(JdbcTemplate db, PlatformTransactionManager transactionManager) {
        this.db = db;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void enqueue(JudgeSubmission submission, int memoryLimitMb, Runnable insertSubmission) {
        transactions.executeWithoutResult(tx -> {
            insertSubmission.run();
            db.update("""
                    INSERT INTO judge_sandbox_instance
                      (sandbox_instance_id, submission_id, user_id, problem_id, language, state,
                       judge_node, cpu_limit, mem_limit_kb, last_heartbeat_at, log_ref)
                    VALUES (?, ?, ?, ?, ?, 'QUEUED', 'local', 1, ?, NOW(), ?)
                    """, submission.getSandboxInstanceId(), submission.getId(), submission.getUserId(),
                    submission.getProblemId(), submission.getLanguage(),
                    JudgeAdapter.effectiveMemoryMb(memoryLimitMb, submission.getLanguage()) * 1024,
                    line("QUEUED submission=" + submission.getDisplayNo()));
        });
    }

    /**
     * Registry is installed before taking the instance lock, closing the start/kill race.
     * On this project's single judge node, a persisted RUNNING instance with no local handle is an
     * interrupted attempt: the old JVM's HTTP connection has closed and Rabbit redelivers its unacked
     * message. Restart compilation instead of acknowledging the message and leaving it stuck.
     * Multiple judge JVMs would require persisted node ownership; this recovery assumes one JVM.
     */
    public SandboxExecution start(JudgeSubmission submission) {
        String id = submission.getSandboxInstanceId();
        SandboxExecution execution = new SandboxExecution(
                message -> appendLog(id, message), phase -> progress(submission, phase));
        if (running.putIfAbsent(id, execution) != null) return null;
        boolean started = false;
        try {
            started = Boolean.TRUE.equals(transactions.execute(tx -> {
                Map<String, Object> row = locked(id);
                String previousState = String.valueOf(row.get("state"));
                if (!List.of("QUEUED", "RUNNING").contains(previousState)) return false;
                String message = "RUNNING".equals(previousState)
                        ? "RECOVERED: MQ replay restarts compiler after interrupted attempt (single judge node)"
                        : "RUNNING: compiler started";
                db.update("""
                        UPDATE judge_sandbox_instance SET state='RUNNING', started_at=NOW(),
                          last_heartbeat_at=NOW(), log_ref=RIGHT(CONCAT(COALESCE(log_ref,''), ?),12000)
                        WHERE sandbox_instance_id=? AND state IN ('QUEUED','RUNNING')
                        """, line(message), id);
                db.update("UPDATE judge_submission SET status=1 WHERE id=? AND status < 3", submission.getId());
                return true;
            }));
            return started ? execution : null;
        } finally {
            if (!started) running.remove(id, execution);
        }
    }

    public void release(JudgeSubmission submission, SandboxExecution execution) {
        running.remove(submission.getSandboxInstanceId(), execution);
    }

    /** The same row lock arbitrates completion and kill, so KILLED and its submission RE cannot be overwritten. */
    public boolean finish(JudgeSubmission submission, int exitCode, int memKb, String message, Runnable writeResult) {
        return Boolean.TRUE.equals(transactions.execute(tx -> {
            Map<String, Object> row = locked(submission.getSandboxInstanceId());
            if (!List.of("QUEUED", "RUNNING").contains(row.get("state"))) return false;
            writeResult.run();
            db.update("""
                    UPDATE judge_sandbox_instance SET state='EXITED', exit_code=?, mem_used_kb=?,
                      cpu_percent=0, finished_at=NOW(), last_heartbeat_at=NOW(),
                      log_ref=RIGHT(CONCAT(COALESCE(log_ref,''), ?),12000)
                    WHERE sandbox_instance_id=? AND state IN ('QUEUED','RUNNING')
                    """, exitCode, memKb, line(message), submission.getSandboxInstanceId());
            return true;
        }));
    }

    public SandboxKillDTO kill(String id, String reason) {
        String explanation = reason == null || reason.isBlank() ? "管理员主动终止" : reason.trim();
        if (explanation.length() > 1000) throw BusinessException.badRequest("终止原因不能超过1000字符");
        return transactions.execute(tx -> {
            Map<String, Object> row = locked(id);
            String state = String.valueOf(row.get("state"));
            if (!List.of("QUEUED", "RUNNING").contains(state)) {
                throw BusinessException.conflict("沙箱实例已处于 " + state + "，不能再次终止");
            }
            SandboxExecution execution = running.get(id);
            if ("RUNNING".equals(state) && execution == null) {
                throw BusinessException.conflict("该实例没有本机执行句柄，无法确认终止，请检查判题节点");
            }
            // Mark both records while holding the instance lock; the worker's finish() waits on this lock.
            db.update("""
                    UPDATE judge_sandbox_instance SET state='KILLED', exit_code=137, cpu_percent=0,
                      mem_used_kb=0, finished_at=NOW(), last_heartbeat_at=NOW(),
                      log_ref=RIGHT(CONCAT(COALESCE(log_ref,''), ?),12000)
                    WHERE sandbox_instance_id=? AND state IN ('QUEUED','RUNNING')
                    """, line("KILLED: " + explanation), id);
            db.update("""
                    UPDATE judge_submission SET status=8, oi_score=0, pass_case_count=0,
                      error_msg=?, finished_at=NOW()
                    WHERE sandbox_instance_id=? AND status < 3
                    """, "管理员终止判题：" + explanation, id);
            if (execution != null) execution.cancel();
            SandboxKillDTO result = new SandboxKillDTO();
            result.setSandboxInstanceId(id);
            result.setState("KILLED");
            result.setKilledAt(System.currentTimeMillis());
            result.setExitCode(137);
            result.setReleasedCpu(((Number) row.get("cpu_limit")).intValue());
            result.setReleasedMemKb(((Number) row.get("mem_limit_kb")).longValue());
            return result;
        });
    }

    private Map<String, Object> locked(String id) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM judge_sandbox_instance WHERE sandbox_instance_id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw BusinessException.notFound("沙箱实例不存在：" + id);
        return rows.get(0);
    }

    private void progress(JudgeSubmission submission, String phase) {
        int status = "JUDGING".equals(phase) ? 2 : 1;
        db.update("UPDATE judge_submission SET status=? WHERE id=? AND status < 3", status, submission.getId());
        appendLog(submission.getSandboxInstanceId(), phase);
    }

    private void appendLog(String id, String message) {
        db.update("""
                UPDATE judge_sandbox_instance SET last_heartbeat_at=NOW(),
                  log_ref=RIGHT(CONCAT(COALESCE(log_ref,''), ?),12000)
                WHERE sandbox_instance_id=? AND state IN ('QUEUED','RUNNING')
                """, line(message.length() > 2000 ? message.substring(0,2000) + "...(truncated)" : message), id);
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void heartbeat() {
        for (String id : running.keySet()) {
            db.update("UPDATE judge_sandbox_instance SET last_heartbeat_at=NOW() WHERE sandbox_instance_id=? AND state='RUNNING'", id);
        }
    }

    private static String line(String text) { return Instant.now() + " " + text + "\n"; }
}
