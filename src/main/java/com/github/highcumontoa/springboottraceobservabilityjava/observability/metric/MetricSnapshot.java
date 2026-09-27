package com.github.highcumontoa.springboottraceobservabilityjava.observability.metric;

/**
 * 单个标签组合的累计指标快照（值用 long/double 承载，读取时拷贝）。
 */
public record MetricSnapshot(long count, long totalNanos, long maxNanos) {
}
