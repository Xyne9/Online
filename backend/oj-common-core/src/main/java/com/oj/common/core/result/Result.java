package com.oj.common.core.result;

import java.io.Serializable;
import java.util.UUID;

/**
 * 统一响应包装（设计文档 §1.3 / 基线决策 §3.1）。
 *
 * <p>全站所有 HTTP 接口的成功与失败响应，都必须包成这个结构：</p>
 * <pre>
 * { "code": 200, "message": "ok", "data": { ... }, "traceId": "b7c9e2f1a4d3", "timestamp": 1730000000000 }
 * </pre>
 *
 * <p><b>铁律：</b></p>
 * <ul>
 *   <li>{@code code} 与 HTTP 状态码<b>严格同值</b>，绝不出现「HTTP 200 包业务 500」的伪成功；</li>
 *   <li>出错时 {@code data} 必须为 {@code null}；</li>
 *   <li>JSON 序列化<b>保留 null 字段</b>（不配 NON_NULL），前端用 {@code === null} 判断空态。</li>
 * </ul>
 *
 * @param <T> 业务数据类型
 * @author oj-cloud
 */
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务状态码，取值必须命中 {@link com.oj.common.core.exception.ErrorCode}（与 HTTP 状态码同值） */
    private int code;

    /** 提示信息，成功固定为 {@code ok}；失败时为可直接展示给用户的中文文案 */
    private String message;

    /** 业务数据，失败时为 null */
    private T data;

    /** 链路追踪 ID，前端错误提示里展示，便于答辩演示定位 */
    private String traceId;

    /** 服务端时间戳（毫秒），便于排查时钟/超时问题 */
    private long timestamp;

    public Result() {
        this.timestamp = System.currentTimeMillis();
    }

    public Result(int code, String message, T data) {
        this();
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = TraceIdHolder.current();
    }

    // ==================================================================
    // 成功：ok()
    // ==================================================================

    /** 成功且无数据体（例如注销、下发任务这类只需要 200 的接口） */
    public static <T> Result<T> ok() {
        return new Result<>(com.oj.common.core.exception.ErrorCode.SUCCESS.getCode(),
                com.oj.common.core.exception.ErrorCode.SUCCESS.getMessage(), null);
    }

    /** 成功并携带数据体 —— 最常用 */
    public static <T> Result<T> ok(T data) {
        return new Result<>(com.oj.common.core.exception.ErrorCode.SUCCESS.getCode(),
                com.oj.common.core.exception.ErrorCode.SUCCESS.getMessage(), data);
    }

    /** 成功并自定义提示语（例如「作业已提交」），data 为 null */
    public static <T> Result<T> okMsg(String message) {
        return new Result<>(com.oj.common.core.exception.ErrorCode.SUCCESS.getCode(), message, null);
    }

    /** 成功、携带数据体、自定义提示语 */
    public static <T> Result<T> ok(T data, String message) {
        return new Result<>(com.oj.common.core.exception.ErrorCode.SUCCESS.getCode(), message, data);
    }

    // ==================================================================
    // 失败：fail(...)
    // ==================================================================

    /** 按错误码枚举失败（message 取枚举默认文案） */
    public static <T> Result<T> fail(com.oj.common.core.exception.ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /** 按错误码枚举失败，并覆盖为更具体的业务提示（例如「第 3 个测试点超时」） */
    public static <T> Result<T> fail(com.oj.common.core.exception.ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    /** 按裸 code + 自定义 message 失败（网关等无法抛出枚举时使用，code 必须仍在 9 大错误码内） */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    /** 由业务异常直接转 Result（全局异常处理器统一走这里） */
    public static <T> Result<T> fail(com.oj.common.core.exception.BusinessException ex) {
        return new Result<>(ex.getCode(), ex.getMessage(), null);
    }

    /** 是否成功（code == 200） */
    public boolean isSuccess() {
        return this.code == com.oj.common.core.exception.ErrorCode.SUCCESS.getCode();
    }

    // ==================================================================
    // 链路追踪 ID 兜底
    // ==================================================================

    /**
     * 补齐 traceId：如果构造时线程上下文里还没有（例如在定时任务、单元测试里构造），
     * 则生成一个 12 位短 ID，保证前端永远能拿到一个可展示的排障标识。
     *
     * @return 当前 traceId（绝不为 null）
     */
    public String getTraceId() {
        if (this.traceId == null || this.traceId.isEmpty()) {
            this.traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "Result{code=" + code + ", message='" + message + "', data=" + data
                + ", traceId='" + traceId + "', timestamp=" + timestamp + '}';
    }
}
