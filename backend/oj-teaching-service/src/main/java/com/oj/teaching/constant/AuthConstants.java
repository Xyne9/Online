package com.oj.teaching.constant;

import com.oj.common.core.redis.RedisKeys;

/**
 * 认证链路专用常量（Redis Key 前缀、限流阈值、默认配额、落地路由）。
 *
 * <p><b>为什么不放进 {@link RedisKeys}：</b>{@code oj-common-core} 是网关与 5 个服务共用的
 * 已定稿模块，本 Track 的硬约束是「不改 oj-common-core」。登录限流 key 属于认证链路的
 * <b>局部新增</b>，尚未进入基线 §3.5 的 12 类 Key 清单，因此按任务要求落在本常量类里。
 * 前缀仍然拼在 {@link RedisKeys#PREFIX} 之后，保证全站 key 都在 {@code oj:} 命名空间内，
 * 不会和别的系统共用 Redis 库时撞车。</p>
 *
 * <p><b>为什么 token / refresh 的 key 不在这里：</b>那两类 key 已经有权威定义
 * （{@link RedisKeys#authToken} / {@link RedisKeys#authRefresh}），本类绝不复写，
 * 避免「两处拼 key、拼写漂移」——那正是 {@code RedisKeys} 类注释里警告过的幽灵 bug。</p>
 *
 * @author oj-cloud
 */
public final class AuthConstants {

    private AuthConstants() {
    }

    // ==================================================================
    // 登录限流（契约 §2.2 错误码 429：同 IP 每分钟 10 次）
    // ==================================================================

    /**
     * 登录限流 key 前缀：{@code oj:rate:login:{ip}}
     *
     * <p>选 {@code oj:rate:login:} 而不是 {@code oj:auth:login:}：与基线 §3.5 第 12 类
     * {@code oj:rate:ai:stream:*} 保持同一「限流类」前缀族，语义一眼可辨。</p>
     */
    public static final String LOGIN_RATE_KEY_PREFIX = RedisKeys.PREFIX + "rate:login:";

    /** 登录限流窗口（秒）：60 秒 */
    public static final long LOGIN_RATE_WINDOW_SECONDS = 60L;

    /** 登录限流阈值（次 / 窗口）：默认每分钟 10 次，可被 {@code oj.login.rate-limit-per-minute} 覆盖 */
    public static final int LOGIN_RATE_LIMIT_DEFAULT = 10;

    // ==================================================================
    // 会话与令牌
    // ==================================================================

    /** Refresh Token 的可见前缀，与契约 §2.2 示例 {@code rt_9f2c...} 对齐 */
    public static final String REFRESH_TOKEN_PREFIX = "rt_";

    /**
     * Refresh Token 内部编码里 userId / jti / 随机串的分隔符（Base64URL 后对外不可见）。
     *
     * <p>刻意声明为 {@code String} 而非 {@code char}：拼字符串时
     * {@code Long + char} 会走数值加法（{@code 1 + ':' == 59}），是个静默的经典坑。</p>
     */
    public static final String REFRESH_TOKEN_SEPARATOR = ":";

    // ==================================================================
    // 业务默认值
    // ==================================================================

    /**
     * 单题 AI 提示配额的系统默认值（契约 §2.5 {@code aiQuotaDefault} 示例值 3）。
     *
     * <p><b>为什么是常量而不是查库：</b>{@code sys_user} 没有配额列——配额是
     * <b>作业级</b>属性（{@code teach_assignment.ai_quota_per_prob}），随具体作业变化。
     * {@code /api/auth/me} 是「我登录后默认拿几次」，发生在「还没进入任何作业」的时刻，
     * 因此只能是平台默认值；进入作业后的真实配额由 §6 的作业详情端点下发。</p>
     */
    public static final int AI_QUOTA_DEFAULT = 3;

    // ==================================================================
    // 角色落地路由（契约 §2.2 备注：落地判断不在前端硬编码）
    // ==================================================================

    /** 学生登录后落地页 */
    public static final String HOME_STUDENT = "s-problems.html";

    /** 教师登录后落地页 */
    public static final String HOME_TEACHER = "t-dashboard.html";

    /** 管理员登录后落地页 */
    public static final String HOME_ADMIN = "a-models.html";

    /** 未知角色的兜底落地页（正常不会发生，仅防 null） */
    public static final String HOME_FALLBACK = HOME_STUDENT;

    // ==================================================================
    // 统一文案（契约 §2.2：登录失败不区分账号是否存在）
    // ==================================================================

    /** 登录失败统一文案：绝不出现「用户不存在」/「密码错误」两种说法 */
    public static final String MSG_LOGIN_FAILED = "账号或密码错误";

    /** 账号被禁用（status=0）的文案 */
    public static final String MSG_ACCOUNT_DISABLED = "账号已被禁用，请联系管理员";

    /** Refresh Token 失效文案 */
    public static final String MSG_REFRESH_INVALID = "刷新令牌已失效，请重新登录";

    /** 未登录文案 */
    public static final String MSG_UNAUTHORIZED = "未登录或 Token 失效";

    /** 限流文案 */
    public static final String MSG_RATE_LIMITED = "登录尝试过于频繁，请 1 分钟后再试";

    // ==================================================================
    // 时区
    // ==================================================================

    /**
     * 数据库墙钟时区：必须与 JDBC URL 的 {@code serverTimezone=Asia/Shanghai}
     * 和 {@code spring.jackson.time-zone} 三者一致。
     *
     * <p><b>为什么不能直接用 {@code ZoneId.systemDefault()}：</b>JDBC 读出的
     * {@code DATETIME} 是「不带时区的墙钟字符串」，含义由连接参数 {@code serverTimezone}
     * 决定（这里是 Asia/Shanghai）。若 JVM 跑在 UTC（容器里很常见），
     * {@code systemDefault()} 会把「北京时间 08:30」当成「UTC 08:30」，
     * 转出来的 epoch 毫秒整整差 8 小时——前端显示「8 小时前登录」。
     * 写 {@code last_login_at} 同理。故时区必须钉死，不能跟着 JVM 走。</p>
     */
    public static final java.time.ZoneId DB_ZONE = java.time.ZoneId.of("Asia/Shanghai");
}
