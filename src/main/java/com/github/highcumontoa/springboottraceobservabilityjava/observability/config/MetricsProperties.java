package com.github.highcumontoa.springboottraceobservabilityjava.observability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 指标相关配置项。
 */
@ConfigurationProperties(prefix = "observability.metrics")
public class MetricsProperties {

    /** 标签组合（时间序列）数量上限，防止基数爆炸 */
    private int maxSeries = 200;

    /** 归一化后路由标签的最大长度 */
    private int routeMaxLength = 128;

    public int getMaxSeries() {
        return maxSeries;
    }

    public void setMaxSeries(int maxSeries) {
        this.maxSeries = maxSeries;
    }

    public int getRouteMaxLength() {
        return routeMaxLength;
    }

    public void setRouteMaxLength(int routeMaxLength) {
        this.routeMaxLength = routeMaxLength;
    }
}
