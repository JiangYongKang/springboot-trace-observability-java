package com.github.highcumontoa.springboottraceobservabilityjava.observability.metric;

/**
 * 请求级指标记录器：并发安全，标签组合数必须有界。
 */
public interface RequestMetricRecorder {

    /**
     * 记录一次请求；当标签组合数达到上限时，归入 overflow 兜底桶并可发出告警。
     */
    void record(MetricLabels labels, long durationNanos);

    /** 返回当前累计快照的只读拷贝（用于本地暴露/断言）。 */
    java.util.Map<MetricLabels, MetricSnapshot> snapshot();

    /** 标签组合上限。 */
    int maxSeries();
}
