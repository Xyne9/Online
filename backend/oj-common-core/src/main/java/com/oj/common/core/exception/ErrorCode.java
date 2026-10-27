package com.oj.common.core.exception;

/**
 * 全局 9 大标准错误码（设计文档 §1.3 / 基线决策 §3.4）。
 *
 * <p><b>严禁新增其他状态码。</b>文档口径是「9 大」，但表中实际列出 10 个常量
 * （含成功的 200）；本枚举按<b>表格逐字实现全部 10 个</b>，不做删减——
 * 少一个前端就没法区分「通道挂了(502)」和「参数错了(400)」。</p>
 *
 * <table border="1">
 *   <caption>错误码语义</caption>
 *   <tr><th>code</th><th>语义</th><th>典型触发场景</th></tr>
 *   <tr><td>200</td><td>成功</td><td>正常业务返回</td></tr>
 *   <tr><td>400</td><td>参数校验失败</td><td>缺必填项、枚举越界、sortBy 不在白名单、测试点总分 ≠ 100</td></tr>
 *   <tr><td>401</td><td>未登录或 Token 失效</td><td>无 Bearer 头、签名错、与 Redis oj:auth:token:{uid} 不匹配</td></tr>
 *   <tr><td>403</td><td>权限不足（含越权）</td><td>学生调 /api/admin/**、查看他人提交、考试模式调 AI</td></tr>
 *   <tr><td>404</td><td>资源不存在</td><td>problemId / submissionId / reportId / taskId 不存在或已软删</td></tr>
 *   <tr><td>409</td><td>状态冲突</td><td>重复提交作业、超 5 秒撤销窗口、对已结束考试交卷、乐观锁 version 冲突</td></tr>
 *   <tr><td>429</td><td>限流 / 防抖</td><td>3 秒内重复判题、AI 配额耗尽、SSE 并发超 2 条</td></tr>
 *   <tr><td>500</td><td>服务内部错误</td><td>未捕获异常</td></tr>
 *   <tr><td>502</td><td>上游依赖失败</td><td>端云模型降级链全部失败、Go-Judge 返回非 200</td></tr>
 *   <tr><td>504</td><td>上游超时</td><td>通道探活超时、AI 首字 TTFT 超 15s、判题超时</td></tr>
 * </table>
 *
 * <p>枚举名与 HTTP 状态码语义对齐（Spring 的 {@code HttpStatus} 同名常量），
 * 便于 {@code HttpStatus.valueOf(errorCode.getCode())} 直接互转。</p>
 *
 * @author oj-cloud
 */
public enum ErrorCode {

    /** 200 · 成功。code 与 HTTP 状态码同值，是唯一表示「业务成功」的取值 */
    SUCCESS(200, "ok"),

    /** 400 · 参数校验失败：缺必填项、枚举越界、分页越界、sortBy 不在白名单 */
    BAD_REQUEST(400, "参数校验失败"),

    /** 401 · 未登录或 Token 失效：无 Authorization 头、签名错、Redis 在线态已过期 */
    UNAUTHORIZED(401, "未登录或 Token 失效"),

    /** 403 · 权限不足：角色不符，或越权访问他人资源 */
    FORBIDDEN(403, "没有权限执行该操作"),

    /** 404 · 资源不存在：ID 查不到或已被软删 */
    NOT_FOUND(404, "资源不存在"),

    /** 409 · 状态冲突：重复提交、窗口已过、乐观锁冲突 */
    CONFLICT(409, "状态冲突，请刷新页面后重试"),

    /** 429 · 限流 / 防抖：3 秒判题锁、AI 配额耗尽、SSE 并发超限 */
    TOO_MANY_REQUESTS(429, "操作过于频繁，请稍后再试"),

    /** 500 · 服务内部错误：未捕获异常，前端展示通用错误 + traceId */
    INTERNAL_ERROR(500, "服务内部错误"),

    /** 502 · 上游依赖失败：模型通道全挂、Go-Judge 沙箱返回非 200 */
    BAD_GATEWAY(502, "上游服务不可用"),

    /** 504 · 上游超时：通道探活超时、AI 首字超时、判题超时 */
    GATEWAY_TIMEOUT(504, "上游服务响应超时");

    /** 码值：与 HTTP 状态码严格同值 */
    private final int code;

    /** 默认中文提示语（可直接展示给终端用户） */
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    /**
     * 按码值反查枚举。
     *
     * @param code 码值
     * @return 命中的枚举；未命中返回 {@link #INTERNAL_ERROR}（兜底，绝不返回 null）
     */
    public static ErrorCode fromCode(int code) {
        for (ErrorCode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return INTERNAL_ERROR;
    }

    /**
     * 该码值是否在 9 大标准错误码白名单内（网关/全局异常处理器用它守住「严禁新增状态码」的铁律）。
     *
     * @param code 待校验码值
     * @return true 表示合法
     */
    public static boolean isStandardCode(int code) {
        for (ErrorCode value : values()) {
            if (value.code == code) {
                return true;
            }
        }
        return false;
    }
}
