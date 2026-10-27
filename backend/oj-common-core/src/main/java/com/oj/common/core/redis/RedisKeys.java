package com.oj.common.core.redis;

/**
 * Redis 12 类 Key 规范（设计文档 §3.5 逐条落地）。
 *
 * <p><b>为什么必须集中在这里：</b>网关（WebFlux 线程）与 5 个业务服务（Servlet 线程）
 * 都读写同一批 key，任何一处拼写漂移都会导致「登录成功但网关一直 401」这类幽灵 bug。
 * 全项目<b>禁止</b>在业务代码里手写 {@code "oj:auth:token:" + userId}，一律调本类静态方法。</p>
 *
 * <table border="1">
 *   <caption>12 类 Key 一览</caption>
 *   <tr><th>#</th><th>格式</th><th>类型</th><th>TTL</th><th>用途</th></tr>
 *   <tr><td>1</td><td>oj:auth:token:{userId}</td><td>String(JSON)</td><td>24h</td><td>Access Token 与 jti，网关逐字比对</td></tr>
 *   <tr><td>2</td><td>oj:judge:lock:{userId}</td><td>String("1")</td><td>3s</td><td>判题 3 秒防抖锁（SETNX）</td></tr>
 *   <tr><td>3</td><td>oj:ai:quota:{assignId}:{probId}:{userId}</td><td>String(Int)</td><td>随作业截止</td><td>作业模式单题 AI 提示次数</td></tr>
 *   <tr><td>4</td><td>oj:ai:level:{userId}:{probId}:{stage}</td><td>String(Int)</td><td>24h</td><td>渐进式提示阶梯轮次</td></tr>
 *   <tr><td>5</td><td>oj:ai:memory:{sessionId}</td><td>List(JSON)</td><td>2h</td><td>最近 6 轮对话滑动窗口</td></tr>
 *   <tr><td>6</td><td>oj:ai:config:channels</td><td>Hash</td><td>永久</td><td>模型通道与角色绑定热插拔</td></tr>
 *   <tr><td>7</td><td>oj:auth:refresh:{userId}</td><td>String(JSON)</td><td>7d / 12h</td><td>Refresh Token 轮换存储</td></tr>
 *   <tr><td>8</td><td>oj:seq:{biz}</td><td>String(Int)</td><td>永久</td><td>展示号全局自增序列</td></tr>
 *   <tr><td>9</td><td>oj:admin:probe:{channelCode}</td><td>String("1")</td><td>10s</td><td>模型通道探活防抖锁</td></tr>
 *   <tr><td>10</td><td>oj:plagiarism:task:{taskId}</td><td>Hash</td><td>2h</td><td>异步查重任务进度快照</td></tr>
 *   <tr><td>11</td><td>oj:exam:seq:{examId}</td><td>String(Int)</td><td>考试结束+7d</td><td>监考事件 seq 原子自增</td></tr>
 *   <tr><td>12</td><td>oj:rate:ai:stream:{userId}</td><td>String(Int)</td><td>60s</td><td>SSE 并发限制（上限 2 条）</td></tr>
 * </table>
 *
 * @author oj-cloud
 */
public final class RedisKeys {

    // ==================================================================
    // 0. 全局前缀
    // ==================================================================

    /** 全站统一前缀，杜绝与其他系统共用一个 Redis 库时撞 key */
    public static final String PREFIX = "oj:";

    private RedisKeys() {
    }

    // ==================================================================
    // 1. 认证：Access Token（String JSON · 24h）
    // ==================================================================

    /**
     * 1 · 当前有效 Access Token 与 jti；网关每次请求逐字比对
     *
     * @param userId 用户 ID
     * @return {@code oj:auth:token:{userId}}
     */
    public static String authToken(Long userId) {
        return PREFIX + "auth:token:" + userId;
    }

    /** 1 · Access Token 过期时间（秒）：24 小时 */
    public static final long TTL_AUTH_TOKEN = 24 * 60 * 60L;

    // ==================================================================
    // 2. 判题防抖锁（String "1" · 3s）
    // ==================================================================

    /**
     * 2 · 判题提交与样例运行 3 秒防抖锁（SETNX 获取，未过期则返回 429）
     *
     * @param userId 用户 ID
     * @return {@code oj:judge:lock:{userId}}
     */
    public static String judgeLock(Long userId) {
        return PREFIX + "judge:lock:" + userId;
    }

    /** 2 · 判题锁存活时间（秒）：3 秒 */
    public static final long TTL_JUDGE_LOCK = 3L;

    // ==================================================================
    // 3. AI 作业配额（String Int · 随作业截止）
    // ==================================================================

    /**
     * 3 · 作业模式单题 AI 提示已用次数计数器
     *
     * @param assignId 作业 ID
     * @param probId   题目 ID
     * @param userId   用户 ID
     * @return {@code oj:ai:quota:{assignId}:{probId}:{userId}}
     */
    public static String aiQuota(Long assignId, Long probId, Long userId) {
        return PREFIX + "ai:quota:" + assignId + ":" + probId + ":" + userId;
    }

    // ==================================================================
    // 4. AI 渐进式提示阶梯（String Int · 24h）
    // ==================================================================

    /**
     * 4 · 渐进式提示阶梯轮次计数（{@code hintLevel = min(count, 3)}）
     *
     * @param userId 用户 ID
     * @param probId 题目 ID
     * @param stage  阶段（1 = 思路启发，2 = 具体指路）
     * @return {@code oj:ai:level:{userId}:{probId}:{stage}}
     */
    public static String aiLevel(Long userId, Long probId, Integer stage) {
        return PREFIX + "ai:level:" + userId + ":" + probId + ":" + stage;
    }

    /** 4 · 提示阶梯计数 TTL（秒）：24 小时 */
    public static final long TTL_AI_LEVEL = 24 * 60 * 60L;

    // ==================================================================
    // 5. AI 会话记忆（List JSON · 2h）
    // ==================================================================

    /**
     * 5 · 最近 6 轮对话滑动窗口记忆
     *
     * @param sessionId 会话 ID
     * @return {@code oj:ai:memory:{sessionId}}
     */
    public static String aiMemory(String sessionId) {
        return PREFIX + "ai:memory:" + sessionId;
    }

    /** 5 · 会话记忆 TTL（秒）：2 小时 */
    public static final long TTL_AI_MEMORY = 2 * 60 * 60L;

    // ==================================================================
    // 6. AI 通道配置快照（Hash · 永久）
    // ==================================================================

    /**
     * 6 · 模型通道与角色绑定热插拔配置快照（Hash，字段为 channelCode）
     *
     * @return 固定 key {@code oj:ai:config:channels}
     */
    public static String aiChannelConfig() {
        return PREFIX + "ai:config:channels";
    }

    // ==================================================================
    // 7. Refresh Token（String JSON · 7d 学生 / 12h 管理员）
    // ==================================================================

    /**
     * 7 · Refresh Token 轮换存储 {@code {token, jti, issuedAt}}
     *
     * @param userId 用户 ID
     * @return {@code oj:auth:refresh:{userId}}
     */
    public static String authRefresh(Long userId) {
        return PREFIX + "auth:refresh:" + userId;
    }

    /** 7 · Refresh Token TTL（秒）：学生 7 天 */
    public static final long TTL_AUTH_REFRESH_STUDENT = 7 * 24 * 60 * 60L;

    /** 7 · Refresh Token TTL（秒）：教师/管理员 12 小时（权限更高，窗口更短） */
    public static final long TTL_AUTH_REFRESH_ADMIN = 12 * 60 * 60L;

    // ==================================================================
    // 8. 展示号自增序列（String Int · 永久）
    // ==================================================================

    /** 8 · 业务序列名：提交号 */
    public static final String SEQ_SUBMISSION = "submission";
    /** 8 · 业务序列名：作业号 */
    public static final String SEQ_ASSIGNMENT = "assignment";
    /** 8 · 业务序列名：查重报告号 */
    public static final String SEQ_PLAGIARISM = "plagiarism";
    /** 8 · 业务序列名：沙箱号 */
    public static final String SEQ_SANDBOX = "sandbox";

    /**
     * 8 · 展示号全局自增序列（INCR 使用）
     *
     * @param biz 业务名，取 {@link #SEQ_SUBMISSION} 等常量
     * @return {@code oj:seq:{biz}}
     */
    public static String seq(String biz) {
        return PREFIX + "seq:" + biz;
    }

    // ==================================================================
    // 9. 通道探活防抖锁（String "1" · 10s）
    // ==================================================================

    /**
     * 9 · 模型通道连通性探测防抖锁，防止连点打爆上游 API
     *
     * @param channelCode 通道编码，如 {@code deepseek} / {@code qwen}
     * @return {@code oj:admin:probe:{channelCode}}
     */
    public static String adminProbe(String channelCode) {
        return PREFIX + "admin:probe:" + channelCode;
    }

    /** 9 · 探活锁 TTL（秒）：10 秒 */
    public static final long TTL_ADMIN_PROBE = 10L;

    // ==================================================================
    // 10. 查重任务进度（Hash · 2h）
    // ==================================================================

    /**
     * 10 · 异步查重任务实时进度快照 {@code {state, progressPercent, comparedPairs...}}
     *
     * @param taskId 查重任务 ID
     * @return {@code oj:plagiarism:task:{taskId}}
     */
    public static String plagiarismTask(String taskId) {
        return PREFIX + "plagiarism:task:" + taskId;
    }

    /** 10 · 查重进度 TTL（秒）：2 小时 */
    public static final long TTL_PLAGIARISM_TASK = 2 * 60 * 60L;

    // ==================================================================
    // 11. 监考事件序列（String Int · 考试结束 + 7d）
    // ==================================================================

    /**
     * 11 · 考场监考事件 seq 原子自增器（支撑 SSE {@code Last-Event-ID} 断线重放）
     *
     * @param examId 考试 ID
     * @return {@code oj:exam:seq:{examId}}
     */
    public static String examSeq(Long examId) {
        return PREFIX + "exam:seq:" + examId;
    }

    /** 11 · 监考事件序列 TTL 的「考试结束 + 7 天」中的宽限期（秒） */
    public static final long TTL_EXAM_SEQ_GRACE = 7 * 24 * 60 * 60L;

    // ==================================================================
    // 12. AI SSE 并发限流（String Int · 60s）
    // ==================================================================

    /**
     * 12 · 同一用户 SSE 流并发数与频次限制（上限 2 条并发，超限返 429）
     *
     * @param userId 用户 ID
     * @return {@code oj:rate:ai:stream:{userId}}
     */
    public static String rateAiStream(Long userId) {
        return PREFIX + "rate:ai:stream:" + userId;
    }

    /** 12 · SSE 并发计数 TTL（秒）：60 秒 */
    public static final long TTL_RATE_AI_STREAM = 60L;

    /** 12 · 单用户 SSE 并发上限：2 条 */
    public static final int MAX_AI_STREAM_CONCURRENT = 2;
}
