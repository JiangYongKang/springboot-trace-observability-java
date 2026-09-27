package com.github.highcumontoa.springboottraceobservabilityjava.observability;

/**
 * 一次请求的链路上下文（不可变）。
 *
 * @param traceId      本次链路标识（稳定贯穿整条链路）
 * @param spanId       本次请求自身的跨度标识
 * @param parentSpanId 上游跨度标识；新起链路时为 {@code null}
 * @param decision     链路来源判定，区分“延续上游”“缺失新建”“非法新建”
 * @param rejectReason 当 decision 为 {@link TraceDecision#GENERATED_INVALID} 时记录拒绝原因，否则为 {@code null}
 */
public record TraceContext(String traceId,
                           String spanId,
                           String parentSpanId,
                           TraceDecision decision,
                           String rejectReason) {

    /** 是否延续了上游携带的合法链路信息。 */
    public boolean inherited() {
        return decision == TraceDecision.INHERITED;
    }
}
