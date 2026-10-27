package com.oj.common.core.exception;

import com.oj.common.core.result.TraceIdHolder;

/**
 * 业务异常（设计文档 §1.3）。
 *
 * <p>所有「可预期」的失败都应该抛它，而不是 {@code throw new RuntimeException("...")}：
 * 全局异常处理器 {@code GlobalExceptionHandler} 捕获后转成 {@code Result.fail()}，
 * 并把 {@link #getCode()} 原样写进 HTTP 状态码，从而守住「code 与 HTTP 状态码严格同值」的铁律。</p>
 *
 * <p>不填充堆栈：业务异常是流程分支而非程序缺陷，填栈会显著拖慢高频路径
 * （判题 3 秒防抖、AI 配额校验都在热路径上）。</p>
 *
 * @author oj-cloud
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 命中 9 大错误码哪一个 */
    private final int code;

    /** 触发时所在线程的 traceId，便于日志串联（为 null 时由 Result 兜底生成） */
    private final String traceId;

    /** 用错误码默认文案构造 */
    public BusinessException(ErrorCode errorCode) {
        this(errorCode.getCode(), errorCode.getMessage());
    }

    /** 用错误码 + 更具体的业务提示构造（推荐：前端直接展示 message） */
    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode.getCode(), message);
    }

    /** 裸码值构造：仅当调用方已经确定 code 在 9 大错误码内时使用 */
    public BusinessException(int code, String message) {
        super(message, null, false, false);
        this.code = code;
        this.traceId = TraceIdHolder.current();
    }

    public int getCode() {
        return code;
    }

    public String getTraceId() {
        return traceId;
    }

    // ==================================================================
    // 便捷工厂：覆盖 9 大错误码的高频场景，业务代码写起来更短
    // ==================================================================

    /** 400 · 参数校验失败 */
    public static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    /** 401 · 未登录或 Token 失效 */
    public static BusinessException unauthorized(String message) {
        return new BusinessException(ErrorCode.UNAUTHORIZED, message);
    }

    /** 403 · 权限不足 */
    public static BusinessException forbidden(String message) {
        return new BusinessException(ErrorCode.FORBIDDEN, message);
    }

    /** 404 · 资源不存在 */
    public static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }

    /** 409 · 状态冲突 */
    public static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }

    /** 429 · 限流 / 防抖 */
    public static BusinessException tooManyRequests(String message) {
        return new BusinessException(ErrorCode.TOO_MANY_REQUESTS, message);
    }

    /** 500 · 服务内部错误 */
    public static BusinessException internalError(String message) {
        return new BusinessException(ErrorCode.INTERNAL_ERROR, message);
    }

    /** 502 · 上游依赖失败 */
    public static BusinessException badGateway(String message) {
        return new BusinessException(ErrorCode.BAD_GATEWAY, message);
    }

    /** 504 · 上游超时 */
    public static BusinessException gatewayTimeout(String message) {
        return new BusinessException(ErrorCode.GATEWAY_TIMEOUT, message);
    }
}
