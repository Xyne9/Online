package com.oj.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.oj.ai.entity.AiChatRecord;
import com.oj.ai.mapper.AiChatRecordMapper;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.result.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.*;

@Service
public class AiHistoryService {
    private final AiChatRecordMapper records;
    private final JdbcTemplate db;
    public static final String[] STAGES = {"", "读题拆解", "编码引导", "排错诊断", "AC 复盘"};

    public AiHistoryService(AiChatRecordMapper records, JdbcTemplate db) { this.records = records; this.db = db; }

    public void save(AiChatRecord record) { records.insert(record); }

    public long used(Long uid, Long aid, Long pid) {
        return records.selectCount(scope(uid, aid, pid).ne(AiChatRecord::getGuardrailAction, 1));
    }

    public long stageUsed(Long uid, Long aid, Long pid, int stage) {
        return records.selectCount(scope(uid, aid, pid).eq(AiChatRecord::getStageType, stage)
                .ne(AiChatRecord::getGuardrailAction, 1));
    }

    public List<AiChatRecord> recent(Long uid, Long aid, Long pid) {
        var result = new ArrayList<>(records.selectList(scope(uid, aid, pid)
                .orderByDesc(AiChatRecord::getId).last("LIMIT 6")));
        Collections.reverse(result);
        return result;
    }

    public PageResult<Map<String, Object>> sessions(Long uid, Long aid, Long pid, Integer stage, int page, int size) {
        if (page < 1 || size < 1 || (stage != null && (stage < 1 || stage > 4))
                || (aid != null && aid < 0) || (pid != null && pid < 1)) throw BusinessException.badRequest("会话查询参数无效");
        var query = scope(uid, aid, pid).eq(stage != null, AiChatRecord::getStageType, stage).orderByDesc(AiChatRecord::getId);
        var result = records.selectPage(new Page<>(page, Math.min(size, 100)), query);
        return PageResult.of(result.getRecords().stream().map(this::view).toList(), result.getTotal(), page, Math.min(size, 100));
    }

    public Map<String, Object> view(AiChatRecord r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recordId", r.getId()); out.put("sessionId", r.getSessionId());
        out.put("stage", r.getStageType()); out.put("stageName", STAGES[r.getStageType()]); out.put("hintLevel", r.getHintLevel());
        out.put("userMessage", r.getUserMessage()); out.put("aiResponse", r.getAiResponse());
        out.put("actualModel", r.getActualModel()); out.put("isFallback", r.getIsFallback() == 1);
        out.put("guardrailAction", r.getGuardrailAction());
        out.put("guardrailCode", r.getGuardrailAction() == 0 ? "pass" : r.getGuardrailAction() == 1 ? "block" : "rewrite");
        out.put("tokensIn", r.getTokensIn()); out.put("tokensOut", r.getTokensOut()); out.put("ttftMs", r.getTtftMs());
        out.put("createdAt", r.getCreateTime() == null ? null : r.getCreateTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
        return out;
    }

    private LambdaQueryWrapper<AiChatRecord> scope(Long uid, Long aid, Long pid) {
        return new LambdaQueryWrapper<AiChatRecord>().eq(AiChatRecord::getUserId, uid)
                .eq(aid != null, AiChatRecord::getAssignmentId, aid).eq(pid != null, AiChatRecord::getProblemId, pid);
    }

    @Transactional
    public Map<String, Object> feedback(Long uid, Long recordId, Boolean helpful, String reason, String comment) {
        if (recordId == null || helpful == null || (reason != null && !Set.of("WRONG", "TOO_VAGUE", "LEAKED", "TOO_LONG", "OTHER").contains(reason))
                || (comment != null && comment.length() > 200)) throw BusinessException.badRequest("反馈参数无效");
        var record = records.selectById(recordId);
        if (record == null) throw BusinessException.notFound("对话记录不存在");
        if (!uid.equals(record.getUserId())) throw BusinessException.forbidden("只能评价本人的对话");
        db.update("""
                INSERT INTO ai_call_feedback(record_id,user_id,helpful,reason_code,comment) VALUES(?,?,?,?,?)
                ON DUPLICATE KEY UPDATE helpful=VALUES(helpful),reason_code=VALUES(reason_code),comment=VALUES(comment)
                """, recordId, uid, helpful, reason, comment);
        return db.queryForObject("SELECT id,create_time FROM ai_call_feedback WHERE record_id=? AND user_id=?",
                (r, n) -> Map.of("feedbackId", r.getLong("id"), "recordId", recordId,
                        "createdAt", r.getTimestamp("create_time").toLocalDateTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()), recordId, uid);
    }
}
