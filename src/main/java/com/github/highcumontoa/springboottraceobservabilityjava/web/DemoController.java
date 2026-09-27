package com.github.highcumontoa.springboottraceobservabilityjava.web;

import com.github.highcumontoa.springboottraceobservabilityjava.observability.RequestMetricsFilter;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.SelfDiagnostics;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.TraceContext;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.TraceContextHolder;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * 本地验证用的演示接口。
 *
 * <ul>
 *     <li>GET /ping：最简单的成功请求；</li>
 *     <li>GET /users/{id}：路径变量接口，验证指标 uri 归一化；</li>
 *     <li>GET /async：切线程池后回传 traceId，验证异步链路不串号；</li>
 *     <li>GET /fail：抛出白名单内标准异常；</li>
 *     <li>GET /boom：抛出自定义异常，验证异常标签折叠为 OtherException；</li>
 *     <li>GET /internal/metrics：列出当前所有请求计时指标标签组合与计数，验证基数有界。</li>
 * </ul>
 */
@RestController
@RequestMapping
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final DemoAsyncService asyncService;
    private final MeterRegistry meterRegistry;

    public DemoController(DemoAsyncService asyncService, MeterRegistry meterRegistry) {
        this.asyncService = asyncService;
        this.meterRegistry = meterRegistry;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        TraceContext context = TraceContextHolder.get();
        log.info("pong business log");
        return Map.of("pong", true, "traceId",
                context == null ? null : context.traceId());
    }

    @GetMapping("/users/{id}")
    public Map<String, Object> getUser(@PathVariable String id) {
        log.info("fetching user, raw path variable does not become a metric tag");
        return Map.of("id", id);
    }

    @GetMapping("/async")
    public Map<String, Object> asyncTrace() throws ExecutionException, InterruptedException {
        TraceContext context = TraceContextHolder.get();
        String requestThreadTraceId = context == null ? null : context.traceId();
        String workerThreadTraceId = asyncService.asyncTraceEcho().get();
        return Map.of(
                "requestThreadTraceId", requestThreadTraceId == null ? "" : requestThreadTraceId,
                "workerThreadTraceId", workerThreadTraceId == null ? "" : workerThreadTraceId,
                "leaked", requestThreadTraceId == null
                        || !requestThreadTraceId.equals(workerThreadTraceId));
    }

    @GetMapping("/fail")
    public String fail() {
        throw new IllegalStateException("business failure with random content " + Math.random());
    }

    @GetMapping("/boom")
    public String boom() {
        throw new fixture.ThirdPartySyntheticException(
                "custom observability-unexpected failure " + Math.random());
    }

    /** 自检：列出所有 http.server.requests 指标的标签组合、计数，以及观测组件内部错误计数。 */
    @GetMapping("/internal/metrics")
    public Map<String, Object> internalMetrics() {
        List<Map<String, String>> series = meterRegistry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(RequestMetricsFilter.METRIC_NAME))
                .map(m -> {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (Tag tag : m.getId().getTags()) {
                        row.put(tag.getKey(), tag.getValue());
                    }
                    if (m instanceof Timer timer) {
                        row.put("count", String.valueOf(timer.count()));
                    }
                    return row;
                })
                .toList();
        long seriesCount = series.size();
        Map<String, Object> selfDiagnostics = new LinkedHashMap<>();
        selfDiagnostics.put("note",
                "per-operation failure counts inside observability components; must be queryable");
        selfDiagnostics.put("trace.parseHeaders",
                SelfDiagnostics.errorCount("trace.parseHeaders"));
        selfDiagnostics.put("trace.mdcPut", SelfDiagnostics.errorCount("trace.mdcPut"));
        selfDiagnostics.put("requestMetrics.record",
                SelfDiagnostics.errorCount("requestMetrics.record"));
        selfDiagnostics.put("jsonLogEncoder.encode",
                SelfDiagnostics.errorCount("jsonLogEncoder.encode"));
        return Map.of(
                "metric", "http.server.requests",
                "seriesCount", seriesCount,
                "series", series,
                "selfDiagnostics", selfDiagnostics);
    }
}
