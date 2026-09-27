package com.github.highcumontoa.springboottraceobservabilityjava.observability.metric;

/**
 * 一条请求指标的标签集合。所有标签取值都必须经过归一化，
 * 不允许出现原始 URL、原始异常信息等无界基数内容。
 *
 * @param method HTTP 方法，如 GET
 * @param route  归一化后的接口标识，如 /api/users/{id}；无法识别时为 UNMAPPED
 * @param result 处理结果分类：SUCCESS / CLIENT_ERROR / SERVER_ERROR
 * @param origin 链路来源：EXTRACTED / GENERATED
 */
public record MetricLabels(String method, String route, String result, String origin) {
}
