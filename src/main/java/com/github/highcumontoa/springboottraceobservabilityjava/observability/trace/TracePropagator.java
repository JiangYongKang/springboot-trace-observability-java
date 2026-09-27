package com.github.highcumontoa.springboottraceobservabilityjava.observability.trace;

/**
 * 链路标识的解析与生成：抽象成接口便于注入故障实现做隔离测试。
 */
public interface TracePropagator {

    /**
     * 解析原始 traceparent 头；非法与缺失必须返回不同状态，不能抛异常。
     */
    ExtractResult extract(String rawTraceParentHeader);

    /** 生成一条全新的链路上下文。 */
    TraceContext generate();

    /** 把当前上下文格式化为向下游传播的 traceparent 头值。 */
    String formatW3CTraceParent(TraceContext context);
}
