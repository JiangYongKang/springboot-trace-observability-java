package com.github.highcumontoa.springboottraceobservabilityjava.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * 按接口维度记录请求量、耗时与处理结果。
 *
 * <p>指标：{@code http.server.requests}（Timer，count 即请求量，histogram 之外可直接取总耗时分布）。
 * 标签全部有界：
 * <ul>
 *     <li>{@code method}：HTTP 方法；</li>
 *     <li>{@code uri}：经 {@link MetricNameNormalizer} 归一化的接口标识；</li>
 *     <li>{@code result}：SUCCESS / CLIENT_ERROR / SERVER_ERROR / ERROR；</li>
 *     <li>{@code exception}：白名单异常简单名 / OtherException / None，绝不包含异常消息。</li>
 * </ul>
 *
 * <p>并发安全：Micrometer {@link MeterRegistry} 内部对相同 Meter.Id 去重，
 * 多线程并发注册只会得到同一个 Timer 实例，Timer 自身的计数更新也是线程安全的，不重复不丢失。
 *
 * <p>故障隔离：计时与指标写入包裹在 {@link SelfDiagnostics} 中，观测组件异常不影响业务请求。
 */
public class RequestMetricsFilter extends OncePerRequestFilter {

    public static final String METRIC_NAME = "http.server.requests";

    private final MeterRegistry meterRegistry;
    private final MetricNameNormalizer normalizer;

    public RequestMetricsFilter(MeterRegistry meterRegistry, MetricNameNormalizer normalizer) {
        this.meterRegistry = meterRegistry;
        this.normalizer = normalizer;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        // Spring Boot 错误分发会再来一次过滤器链，避免重复计时
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        Throwable[] escaped = {null};
        try {
            filterChain.doFilter(request, response);
        } catch (Throwable t) {
            escaped[0] = t;
            throw t;
        } finally {
            long durationNanos = System.nanoTime() - startNanos;
            SelfDiagnostics.runSafely("requestMetrics.record",
                    () -> record(request, response, escaped[0], durationNanos));
        }
    }

    private void record(HttpServletRequest request,
                        HttpServletResponse response,
                        Throwable error,
                        long durationNanos) {
        String method = normalizeMethod(request.getMethod());
        String pattern = extractBestMatchingPattern(request);
        String uri = normalizer.normalizePath(request.getRequestURI(), pattern);
        Throwable rootCause = unwrap(error);
        String result = normalizer.resultOf(response.getStatus(), rootCause);
        String exception = normalizer.normalizeException(rootCause);

        Timer timer = Timer.builder(METRIC_NAME)
                .tags("method", method,
                        "uri", uri,
                        "result", result,
                        "exception", exception)
                .description("HTTP request metrics with bounded tag cardinality")
                .register(meterRegistry);
        timer.record(java.time.Duration.ofNanos(durationNanos));
    }

    @SuppressWarnings("unchecked")
    private String extractBestMatchingPattern(HttpServletRequest request) {        Object pattern = request.getAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String s ? s : null;
    }

    private String normalizeMethod(String method) {
        // HTTP 方法天然有限；防御性校验，异常方法统一 OTHER
        if (method == null || !method.matches("[A-Z]{1,10}")) {
            return "OTHER";
        }
        return method;
    }

    /**
     * 剥离 Servlet/异步执行器的包装异常，取业务根因用于 exception 标签。
     * 只解包已知包装类型；深度与去重保证有界且不会因自引用死循环。
     */
    static Throwable unwrap(Throwable error) {
        if (error == null) {
            return null;
        }
        Throwable current = error;
        int depth = 0;
        while (depth < 10 && isWrapper(current) && current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
            depth++;
        }
        return current;
    }

    private static boolean isWrapper(Throwable t) {
        return t instanceof ServletException
                || t instanceof java.util.concurrent.CompletionException
                || t instanceof java.util.concurrent.ExecutionException
                || (t.getClass().getName().equals(
                "org.springframework.web.util.NestedServletException"));
    }
}
