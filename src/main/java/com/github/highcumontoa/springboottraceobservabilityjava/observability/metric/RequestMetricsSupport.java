package com.github.highcumontoa.springboottraceobservabilityjava.observability.metric;

import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.TraceContext;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.TraceContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Filter 与 HandlerInterceptor 共用的指标记录逻辑，保证口径一致、不重复。
 */
final class RequestMetricsSupport {

    static final String RESULT_SUCCESS = "SUCCESS";
    static final String RESULT_CLIENT_ERROR = "CLIENT_ERROR";
    static final String RESULT_SERVER_ERROR = "SERVER_ERROR";
    static final String UNKNOWN_ORIGIN = "UNKNOWN";

    private RequestMetricsSupport() {
    }

    static void record(RequestMetricRecorder recorder, RouteNormalizer normalizer,
                       HttpServletRequest request, HttpServletResponse response,
                       long durationNanos, Throwable handlerException) {
        int status = response.getStatus();
        String pattern = extractPattern(request);
        String route = normalizer.normalize(request.getMethod(), request.getRequestURI(), pattern);
        // 若 handler 抛了异常，响应状态码此刻可能还停留在 200（真正的 500 到 ERROR
        // 分发时才设置），因此异常优先归类为 SERVER_ERROR，保证结果口径准确。
        String result = handlerException != null ? RESULT_SERVER_ERROR : classify(status);
        MetricLabels labels = new MetricLabels(
                safe(request.getMethod()), route, result, resolveOrigin());
        recorder.record(labels, durationNanos);
    }

    private static String extractPattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String s ? s : null;
    }

    private static String resolveOrigin() {
        TraceContext context = TraceContextHolder.get();
        return context == null ? UNKNOWN_ORIGIN : context.source().name();
    }

    /** 仅按状态码粗分类，不引入具体异常类型或异常文本，避免标签基数失控。 */
    static String classify(int status) {
        if (status >= 200 && status < 400) {
            return RESULT_SUCCESS;
        }
        if (status >= 400 && status < 500) {
            return RESULT_CLIENT_ERROR;
        }
        return RESULT_SERVER_ERROR;
    }

    private static String safe(String value) {
        return value == null ? "UNKNOWN" : value;
    }
}
