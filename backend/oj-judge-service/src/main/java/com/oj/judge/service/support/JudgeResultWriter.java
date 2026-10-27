package com.oj.judge.service.support;

import com.oj.judge.adapter.JudgeExecutor;
import com.oj.judge.adapter.JudgeStatusEnum;
import com.oj.judge.entity.JudgeSubmission;
import com.oj.judge.mapper.JudgeSubmissionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 判题结果落库器（<b>单独成类是为了让 {@code @Transactional} 真正生效</b>）。
 *
 * <p><b>为什么不能直接写在 {@code JudgeServiceImpl} 里：</b>
 * Spring 的事务是基于代理实现的。同一个类内部 {@code this.persistResult(...)}
 * 这样的自调用<b>不经过代理</b>，{@code @Transactional} 会被静默忽略 ——
 * 代码看起来有事务、实际没有。这是最典型的「事务失效」事故，
 * 而且出问题时毫无报错，只有并发下才会暴露「状态改了但计数没改」。</p>
 *
 * <p>因此把「写 judge_submission + 更新 prob_question 计数」这两条必须同生共死的
 * 语句放进本类，由 service 通过注入的代理调用。</p>
 *
 * <p><b>跨服务直连说明：</b>{@code prob_question.pass_count} 的更新是本期妥协，
 * 详见 {@code JudgeServiceImpl} 类注释。</p>
 *
 * @author oj-cloud
 */
@Component
public class JudgeResultWriter {

    private static final Logger log = LoggerFactory.getLogger(JudgeResultWriter.class);

    private final JudgeSubmissionMapper submissionMapper;
    private final JudgeRedisSupport redisSupport;

    public JudgeResultWriter(JudgeSubmissionMapper submissionMapper, JudgeRedisSupport redisSupport) {
        this.submissionMapper = submissionMapper;
        this.redisSupport = redisSupport;
    }

    /**
     * 判题成功（含 CE / WA / TLE 等「用户侧结果」）落库。
     *
     * @param submission 提交记录
     * @param result     判题结果
     */
    @Transactional(rollbackFor = Exception.class)
    public void persistResult(JudgeSubmission submission, JudgeExecutor.ExecutionResult result) {
        JudgeSubmission patch = new JudgeSubmission();
        patch.setId(submission.getId());
        patch.setStatus(result.getStatus().getCode());
        patch.setOiScore(result.getOiScore());
        patch.setPassCaseCount(result.getPassCaseCount());
        patch.setTotalCaseCount(result.getTotalCaseCount());
        patch.setTimeUsed(result.getTimeUsedMs());
        patch.setMemoryUsed(result.getMemoryUsedKb());
        patch.setErrorMsg(result.getErrorMsg());
        patch.setFirstFailedCaseId(result.getFirstFailedSeqNo());
        patch.setFirstFailedSummary(result.getFirstFailedSummary());
        patch.setFinishedAt(LocalDateTime.now());
        patch.setCaseDetails(redisSupport.toJson(buildCaseDetailsForStorage(result)));
        submissionMapper.updateById(patch);

        // 【跨服务直连·本期妥协】AC 时题目通过数 +1（题库服务独立分库后改 Feign）。
        // 计数失败不能回滚判题结果：用户看到的判题结论必须准确，
        // 计数是统计口径，可事后对账修复（day3 §7 硬约束 6 提到每日对账 SQL）。
        if (result.getStatus() == JudgeStatusEnum.AC) {
            try {
                submissionMapper.increasePassCount(submission.getProblemId());
            } catch (Exception e) {
                log.warn("更新题目 pass_count 失败（不影响判题结论）：problem={} {}",
                        submission.getProblemId(), e.getMessage());
            }
        }
        log.info("判题完成：id={} displayNo={} status={} score={} pass={}/{} time={}ms mem={}KB",
                submission.getId(), submission.getDisplayNo(), result.getStatus(),
                result.getOiScore(), result.getPassCaseCount(), result.getTotalCaseCount(),
                result.getTimeUsedMs(), result.getMemoryUsedKb());
    }

    /**
     * 判题失败（基础设施问题：沙箱故障 / 数据缺失 / 队列不可用）落库。
     *
     * @param submissionId 提交 ID
     * @param status       终态（通常是 RE）
     * @param errorMsg     可直接展示给用户的错误说明
     */
    @Transactional(rollbackFor = Exception.class)
    public void finishWithFailure(Long submissionId, JudgeStatusEnum status, String errorMsg) {
        JudgeSubmission patch = new JudgeSubmission();
        patch.setId(submissionId);
        patch.setStatus(status.getCode());
        patch.setErrorMsg(abbreviate(errorMsg, 8000));
        patch.setFinishedAt(LocalDateTime.now());
        submissionMapper.updateById(patch);
        log.warn("判题失败落库：id={} status={} msg={}", submissionId, status, errorMsg);
    }

    /** 只更新状态（编译中 / 评测中这类中间态） */
    public void updateStatus(Long submissionId, JudgeStatusEnum status) {
        JudgeSubmission patch = new JudgeSubmission();
        patch.setId(submissionId);
        patch.setStatus(status.getCode());
        submissionMapper.updateById(patch);
    }

    /**
     * 逐点结果 → 落库 JSON（契约要求的 {@code case_details} 结构）。
     *
     * <p>期望/实际输出只存<b>截断预览</b>（500 字符）：完整隐藏用例落库等于
     * 把题库拷进判题库，既占空间又是泄题面。详情接口按 {@code is_public} 决定下发。</p>
     *
     * @param result 判题结果
     * @return 可序列化的列表
     */
    public List<Map<String, Object>> buildCaseDetailsForStorage(JudgeExecutor.ExecutionResult result) {
        List<Map<String, Object>> details = new ArrayList<>(result.getCases().size());
        for (JudgeExecutor.CaseOutcome outcome : result.getCases()) {
            Map<String, Object> item = new LinkedHashMap<>(12);
            item.put("caseId", outcome.getCaseId());
            item.put("seqNo", outcome.getSeqNo());
            item.put("kind", outcome.getKind());
            item.put("status", outcome.getStatus().getLabel());
            item.put("statusCode", outcome.getStatus().getStatusCode());
            item.put("score", outcome.getScore());
            item.put("gotScore", outcome.getGotScore());
            item.put("timeMs", outcome.getTimeMs());
            item.put("memKb", outcome.getMemKb());
            item.put("expectedPreview", abbreviate(outcome.getExpectedOutput(), 500));
            item.put("actualPreview", abbreviate(outcome.getActualOutput(), 500));
            details.add(item);
        }
        return details;
    }

    /** 截断（null 安全） */
    private static String abbreviate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
