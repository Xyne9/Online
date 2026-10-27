package com.oj.judge.service;

import com.oj.judge.dto.JudgeRequests;

import java.util.List;
import java.util.Map;

/**
 * 判题服务业务接口（契约 §4 的 7 个端点 + 2 个内部端点）。
 *
 * <p><b>命名与契约逐字对齐</b>，避免 controller 里再做一层「翻译」。
 * 所有方法的返回值都是「即将被 {@code Result<T>} 包住的 data」，
 * 不在这里构造 {@code Result} —— 那是 controller 的职责，混在一起会让
 * 异常路径与成功路径的包装口径不一致。</p>
 *
 * @author oj-cloud
 */
public interface JudgeService {

    /**
     * {@code POST /api/judge/self-test} —— 自测（v1.0 冻结端点）。
     *
     * <p>语义：用给定输入跑一次代码，<b>不比对期望输出、不落库</b>。
     * 前端工作台的「自测」按钮用它。</p>
     *
     * @param request 请求体
     * @param userId  当前用户 ID（防抖锁用；未登录时传 null 表示跳过锁）
     * @return {@code {status, statusCode, compileError, actualOutput, timeMs, memoryKb}}
     */
    Map<String, Object> selfTest(JudgeRequests.SelfTestRequest request, Long userId);

    /**
     * {@code POST /api/judge/submit} —— 提交判题（异步）。
     *
     * <p>同步只做三件事：落库（status=0 排队中）、发 MQ、返回提交号。
     * 真正的判题在 {@code JudgeMqConsumer} 里跑。</p>
     *
     * @param request 请求体
     * @param userId  当前用户 ID
     * @return {@code {submissionId, displayNo, status:0, isOverdue:0}}
     */
    Map<String, Object> submit(JudgeRequests.SubmitRequest request, Long userId);

    /**
     * {@code GET /api/judge/status/{submissionId}} —— 轮询判题状态。
     *
     * @param submissionId 提交 ID
     * @param userId       当前用户 ID（越权校验）
     * @param isTeacher    是否教师/管理员（可看他人）
     * @return 状态详情
     */
    Map<String, Object> status(Long submissionId, Long userId, boolean isTeacher);

    /**
     * {@code GET /api/judge/submissions} —— 提交记录列表（含内嵌统计）。
     *
     * @param page      页码
     * @param pageSize  每页条数
     * @param status    状态筛选（all/ac/wa/tle/mle/re/ce）
     * @param lang      语言筛选（all/cpp/python/java）
     * @param range     近 N 天（3/7/30）
     * @param keyword   关键词
     * @param sortBy    排序字段（id/timeMs/score）
     * @param sortOrder 排序方向（asc/desc）
     * @param userId    当前用户 ID（null = 不限，教师视角）
     * @return {@code {list, total, page, pageSize, hasNext, stats}}
     */
    Map<String, Object> submissions(int page, int pageSize, String status, String lang, Integer range,
                                    String keyword, String sortBy, String sortOrder, Long userId);

    /**
     * {@code GET /api/judge/submissions/{id}} —— 提交详情（含逐点预览与同题轨迹）。
     *
     * @param id           提交 ID
     * @param previewLimit 每个测试点预览字符数（默认 120，上限 500）
     * @param userId       当前用户 ID
     * @param isTeacher    是否教师/管理员
     * @return 详情
     */
    Map<String, Object> submissionDetail(Long id, Integer previewLimit, Long userId, boolean isTeacher);

    /**
     * {@code GET /api/judge/submissions/trace} —— 同题轨迹。
     *
     * @param problemId 题目 ID
     * @param userId    目标用户（null = 当前用户）
     * @param limit     条数（默认 20，上限 50）
     * @param currentUserId 当前登录用户（越权校验）
     * @param isTeacher     是否教师/管理员
     * @return {@code {problemId, list, bestSubmissionId, bestScore}}
     */
    Map<String, Object> trace(Long problemId, Long userId, Integer limit,
                              Long currentUserId, boolean isTeacher);

    /**
     * {@code POST /api/judge/run-sample} —— 运行样例 / 自定义输入。
     *
     * @param request 请求体
     * @param userId  当前用户 ID（防抖锁）
     * @return {@code {mode, status, compileError, cases, actualOutput, timeMs, memoryKb, truncated}}
     */
    Map<String, Object> runSample(JudgeRequests.RunSampleRequest request, Long userId);

    /**
     * 内部：{@code GET /inner/judge/failed-diagnosis} —— 最近一次失败用例诊断。
     *
     * @param userId       用户 ID
     * @param problemId    题目 ID
     * @param assignmentId 作业 ID（0 / null = 不限）
     * @return {@code FailedCaseDiagnosisDTO}；无失败记录返回 null
     */
    com.oj.common.api.dto.judge.FailedCaseDiagnosisDTO failedDiagnosis(Long userId, Long problemId,
                                                                       Long assignmentId);

    /**
     * 内部：{@code POST /inner/judge/batch-best-codes} —— 批量取最佳提交代码。
     *
     * @param submissionIds 提交 ID 列表
     * @return 代码列表
     */
    List<com.oj.common.api.dto.judge.SubmissionCodeDTO> batchBestCodes(List<Long> submissionIds);

    /**
     * MQ 消费入口：真正执行一次判题并把结果写回 DB。
     *
     * <p>从 {@code JudgeMqConsumer} 调用，<b>不在 HTTP 线程里跑</b>。</p>
     *
     * @param submissionId 提交 ID
     */
    void judgeSubmission(Long submissionId);
}
