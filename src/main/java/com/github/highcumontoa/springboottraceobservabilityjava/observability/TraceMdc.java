package com.github.highcumontoa.springboottraceobservabilityjava.observability;

import org.slf4j.MDC;

/**
 * 链路字段与 SLF4J MDC 之间的适配。
 *
 * <p>MDC key 稳定不变（{@code traceId}/{@code spanId}/{@code parentSpanId}/{@code traceDecision}），
 * logback 配置与日志解析方可直接依赖这些字段。请求结束必须 {@link #clear()}，
 * MDC 同样基于 ThreadLocal，残留会随线程复用串到下一个请求。
 */
public final class TraceMdc {

    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";
    public static final String PARENT_SPAN_ID = "parentSpanId";
    public static final String TRACE_DECISION = "traceDecision";

    private TraceMdc() {
    }

    public static void put(TraceContext context) {
        MDC.put(TRACE_ID, context.traceId());
        MDC.put(SPAN_ID, context.spanId());
        MDC.put(TRACE_DECISION, context.decision().name());
        if (context.parentSpanId() != null) {
            MDC.put(PARENT_SPAN_ID, context.parentSpanId());
        } else {
            MDC.remove(PARENT_SPAN_ID);
        }
    }

    public static void clear() {
        MDC.remove(TRACE_ID);
        MDC.remove(SPAN_ID);
        MDC.remove(PARENT_SPAN_ID);
        MDC.remove(TRACE_DECISION);
    }
}
