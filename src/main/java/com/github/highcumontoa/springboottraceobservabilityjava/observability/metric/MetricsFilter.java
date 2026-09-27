package com.github.highcumontoa.springboottraceobservabilityjava.observability.metric;

import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.TraceFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求指标过滤器（紧跟在 {@link TraceFilter} 之后），负责：
 * <ol>
 *   <li>在请求最外层打时间戳并放入 request 属性，作为唯一计时刻度；</li>
 *   <li>正常请求由 {@link MetricsHandlerInterceptor} 在 DispatcherServlet 内记录
 *       （那时最终状态码与 URI 模板都已确定），并打上已记录标记；</li>
 *   <li>未进入 handler 链的请求（如无匹配路由的 404）拦截器不会执行，
 *       由本过滤器在 finally 中兜底记录，保证不漏；</li>
 *   <li>指标组件自身异常被隔离并写入 {@code observability.self}，业务不受影响。</li>
 * </ol>
 */
public class MetricsFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger selfLog = LoggerFactory.getLogger(TraceFilter.OBS_LOGGER_NAME);

    public static final int ORDER_HINT = Ordered.HIGHEST_PRECEDENCE + 20;
    static final String START_NANOS_ATTR = MetricsFilter.class.getName() + ".startNanos";
    static final String RECORDED_ATTR = MetricsFilter.class.getName() + ".recorded";

    private final RequestMetricRecorder recorder;
    private final RouteNormalizer normalizer;

    public MetricsFilter(RequestMetricRecorder recorder, RouteNormalizer normalizer) {
        this.recorder = recorder;
        this.normalizer = normalizer;
    }

    @Override
    public int getOrder() {
        return ORDER_HINT;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只在初始 REQUEST 分发采集一次。业务异常/404 后容器 FORWARD 到 /error
        // 属于二次分发：此时跳过，避免出现 route=/error、origin=UNKNOWN 的重复指标。
        return request.getDispatcherType() != DispatcherType.REQUEST;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(START_NANOS_ATTR, System.nanoTime());
        try {
            chain.doFilter(request, response);
        } finally {
            // 注意：绝不能在 finally 中 return——那会吞掉 try 块正在传播的异常，
            // 导致业务抛出的 500 被容器当成正常 200 完成。
            // 已被拦截器记录的请求仅跳过兜底；其余（如未匹配路由的 404）在此兜底，
            // 且兜底记录本身的异常必须被隔离，不能盖过业务异常。
            if (!Boolean.TRUE.equals(request.getAttribute(RECORDED_ATTR))) {
                try {
                    Object startAttr = request.getAttribute(START_NANOS_ATTR);
                    long durationNanos = startAttr instanceof Long start ? System.nanoTime() - start : 0L;
                    RequestMetricsSupport.record(recorder, normalizer, request, response, durationNanos, null);
                } catch (RuntimeException e) {
                    selfLog.error("failed to record request (filter fallback)", e);
                }
            }
        }
    }
}
