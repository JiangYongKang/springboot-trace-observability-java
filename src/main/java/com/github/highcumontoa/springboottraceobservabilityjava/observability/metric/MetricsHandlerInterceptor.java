package com.github.highcumontoa.springboottraceobservabilityjava.observability.metric;

import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.TraceFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 在 DispatcherServlet 内部采集正常 handler 请求的指标。
 *
 * <p>{@link #afterCompletion} 执行时，Spring MVC 已把业务异常解析为最终响应
 * 状态码（如 500），且 URI 模板属性已就绪，分类与路由归一化都准确。
 * 记录后打上标记，{@link MetricsFilter} 兜底逻辑据此跳过，避免重复计数。
 * 指标组件自身异常被隔离并写入 {@code observability.self}，业务不受影响。</p>
 */
public class MetricsHandlerInterceptor implements HandlerInterceptor {

    private static final Logger selfLog = LoggerFactory.getLogger(TraceFilter.OBS_LOGGER_NAME);

    private final RequestMetricRecorder recorder;
    private final RouteNormalizer normalizer;

    public MetricsHandlerInterceptor(RequestMetricRecorder recorder, RouteNormalizer normalizer) {
        this.recorder = recorder;
        this.normalizer = normalizer;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 只统计初始 REQUEST 分发；容器把异常/404 FORWARD 到 /error 属于二次分发，
        // 那时没有计时起点属性、链路上下文也已清理，必须跳过以免产生
        // route=/error、origin=UNKNOWN 的重复脏指标。
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return;
        }
        try {
            Object startAttr = request.getAttribute(MetricsFilter.START_NANOS_ATTR);
            long durationNanos = startAttr instanceof Long start ? System.nanoTime() - start : 0L;
            RequestMetricsSupport.record(recorder, normalizer, request, response, durationNanos, ex);
            request.setAttribute(MetricsFilter.RECORDED_ATTR, Boolean.TRUE);
        } catch (RuntimeException e) {
            // 拦截器内记录失败则不打标记，交由 Filter 兜底重试一次；仍失败只留痕
            selfLog.error("failed to record request metric (interceptor)", e);
        }
    }
}
