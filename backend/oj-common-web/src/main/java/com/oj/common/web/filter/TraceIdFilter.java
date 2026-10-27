package com.oj.common.web.filter;

import com.oj.common.core.result.TraceIdHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 链路追踪过滤器：把网关注入的 {@code X-Trace-Id} 放进 {@link TraceIdHolder}。
 *
 * <p><b>为什么必须有它：</b>网关会给每个请求生成 traceId 并写进响应头，但下游服务
 * 的 {@link com.oj.common.core.result.Result} 在构造时读的是 {@code TraceIdHolder}。
 * 不桥接的话，服务返回的 {@code traceId} 永远是随机新生成的——和网关响应头、
 * 和日志里的 traceId 三者对不上，答辩演示时「报错 → 查日志」这条链路直接断掉。</p>
 *
 * <p><b>必须 finally 清理：</b>Tomcat 线程池复用，不清理会让下一个请求带着上一个的 traceId。</p>
 *
 * @author oj-cloud
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 与网关、{@link TraceIdHolder#TRACE_HEADER} 保持同一个头名 */
    private static final String TRACE_HEADER = TraceIdHolder.TRACE_HEADER;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(TRACE_HEADER);
        String traceId = (incoming == null || incoming.isBlank())
                ? UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                : incoming;
        TraceIdHolder.set(traceId);
        // 回写响应头：即使请求没经过网关（本地直连 8081 调试），前端也能拿到 traceId
        response.setHeader(TRACE_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            TraceIdHolder.clear();
        }
    }
}
