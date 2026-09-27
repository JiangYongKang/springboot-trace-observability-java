package com.github.highcumontoa.springboottraceobservabilityjava.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 为每个进入的 HTTP 请求建立/延续链路上下文的 Servlet 过滤器。
 *
 * <p>职责：
 * <ol>
 *     <li>读取上游链路头并通过 {@link TraceHeaderParser} 判定来源（延续 / 缺失新建 / 非法新建）；</li>
 *     <li>把上下文放入 {@link TraceContextHolder}（ThreadLocal）与 MDC，供业务日志与异步传播使用；</li>
 *     <li>响应头回写 {@code X-Trace-Id}、{@code X-Span-Id}，便于调用方对账；</li>
 *     <li>打印一条带“输入原始值 + 判定结果 + 判定依据”的判定日志；</li>
 *     <li>finally 中无条件清理 ThreadLocal 与 MDC，防止容器线程复用串号/残留。</li>
 * </ol>
 *
 * <p>故障隔离：建立链路属于观测动作，任何内部异常都被 {@link SelfDiagnostics} 隔离；
 * 极端情况下若连上下文都未能建立，业务请求依然照常执行（仅缺失观测字段）。
 */
public class TraceServletFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceServletFilter.class);

    private final TraceHeaderParser parser;

    /** 响应头：本次链路来源判定（INHERITED / GENERATED_MISSING / GENERATED_INVALID）。 */
    public static final String DECISION_HEADER = "X-Trace-Decision";

    public TraceServletFilter(TraceHeaderParser parser) {
        this.parser = parser;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        TraceContext context = resolveContext(request);
        if (context != null) {
            TraceContextHolder.set(context);
            SelfDiagnostics.runSafely("trace.mdcPut", () -> TraceMdc.put(context));
            writeResponseHeaders(response, context);
            logDecision(context, request);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceContextHolder.clear();
            SelfDiagnostics.runSafely("trace.mdcClear", TraceMdc::clear);
        }
    }

    private TraceContext resolveContext(HttpServletRequest request) {
        return SelfDiagnostics.supplySafely("trace.parseHeaders", null, () ->
                parser.parse(
                        request.getHeader(TraceHeaderParser.TRACE_ID_HEADER),
                        request.getHeader(TraceHeaderParser.SPAN_ID_HEADER),
                        request.getHeader(TraceHeaderParser.PARENT_SPAN_ID_HEADER)));
    }

    private void writeResponseHeaders(HttpServletResponse response, TraceContext context) {
        SelfDiagnostics.runSafely("trace.responseHeaders", () -> {
            response.setHeader(TraceHeaderParser.TRACE_ID_HEADER, context.traceId());
            response.setHeader(TraceHeaderParser.SPAN_ID_HEADER, context.spanId());
            response.setHeader(DECISION_HEADER, context.decision().name());
        });
    }

    private void logDecision(TraceContext context, HttpServletRequest request) {
        SelfDiagnostics.runSafely("trace.logDecision", () -> {
            if (context.decision() == TraceDecision.INHERITED) {
                log.info("trace decision=INHERITED method={} path={} reason=upstream headers valid",
                        request.getMethod(), request.getRequestURI());
            } else if (context.decision() == TraceDecision.GENERATED_MISSING) {
                log.info("trace decision=GENERATED_MISSING method={} path={} reason=no upstream trace headers",
                        request.getMethod(), request.getRequestURI());
            } else {
                log.warn("trace decision=GENERATED_INVALID method={} path={} reason={}",
                        request.getMethod(), request.getRequestURI(), context.rejectReason());
            }
        });
    }
}
