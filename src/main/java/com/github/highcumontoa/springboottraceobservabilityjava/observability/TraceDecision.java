package com.github.highcumontoa.springboottraceobservabilityjava.observability;

/**
 * 链路上下文来源判定。
 */
public enum TraceDecision {
    /** 上游携带了合法链路信息，本次请求沿用。 */
    INHERITED,
    /** 上游未携带链路信息，本次请求新建一条链路。 */
    GENERATED_MISSING,
    /** 上游携带的链路信息非法，丢弃后新建一条链路。 */
    GENERATED_INVALID
}
