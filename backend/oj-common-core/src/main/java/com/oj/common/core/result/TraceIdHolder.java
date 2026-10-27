package com.oj.common.core.result;

/**
 * 链路追踪 ID 持有者（ThreadLocal）。
 *
 * <p>网关 {@code AuthGlobalFilter} 为每个请求生成（或透传上游带来的）{@code X-Trace-Id}，
 * 写入 Reactor Context / 本 ThreadLocal；下游任意层调用 {@link Result} 的静态工厂时，
 * 会自动把它带进响应体，前端错误 toast 直接展示，答辩演示时能一眼定位。</p>
 *
 * <p><b>使用纪律：</b>放进 ThreadLocal 的 ID 必须在同一线程的 finally 块里 {@link #clear()}，
 * 否则线程池复用会导致 traceId 串号。WebFlux 场景请使用 Reactor Context，
 * 不要依赖本类（本类主要服务于 5 个 Servlet 服务）。</p>
 *
 * @author oj-cloud
 */
public final class TraceIdHolder {

    /** 网关注入的链路头名，与设计文档 §1.2 的 X-Trace-Id 一致 */
    public static final String TRACE_HEADER = "X-Trace-Id";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TraceIdHolder() {
    }

    /** 写入当前线程的 traceId */
    public static void set(String traceId) {
        HOLDER.set(traceId);
    }

    /** 取当前线程的 traceId，没有则返回 null */
    public static String get() {
        return HOLDER.get();
    }

    /** 取当前线程的 traceId，没有则返回 null（与 {@link #get()} 等价，语义化别名） */
    public static String current() {
        return HOLDER.get();
    }

    /** 清除，防止线程池复用串号 */
    public static void clear() {
        HOLDER.remove();
    }
}
