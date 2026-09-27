package com.github.highcumontoa.springboottraceobservabilityjava.observability.web;

import com.github.highcumontoa.springboottraceobservabilityjava.observability.metric.MetricLabels;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.metric.MetricSnapshot;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.metric.RequestMetricRecorder;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.TraceContext;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.TraceContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地验证用：暴露进程内累计的请求指标快照，并回显当前链路标识，
 * 方便用一条命令同时核对“链路延续”和“指标归并”。
 */
@RestController
@RequestMapping("/observability")
public class MetricsController {

    private final RequestMetricRecorder recorder;

    public MetricsController(RequestMetricRecorder recorder) {
        this.recorder = recorder;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("maxSeries", recorder.maxSeries());

        Map<String, Object> series = new LinkedHashMap<>();
        long totalRequests = 0;
        for (Map.Entry<MetricLabels, MetricSnapshot> entry : recorder.snapshot().entrySet()) {
            MetricLabels l = entry.getKey();
            MetricSnapshot s = entry.getValue();
            String key = "method=" + l.method()
                    + "|route=" + l.route()
                    + "|result=" + l.result()
                    + "|origin=" + l.origin();
            Map<String, Number> values = new LinkedHashMap<>();
            values.put("count", s.count());
            values.put("totalNanos", s.totalNanos());
            values.put("maxNanos", s.maxNanos());
            series.put(key, values);
            totalRequests += s.count();
        }
        body.put("seriesCount", series.size());
        body.put("totalRequests", totalRequests);
        body.put("series", series);

        TraceContext ctx = TraceContextHolder.get();
        body.put("currentTraceId", ctx == null ? null : ctx.traceId());
        return body;
    }
}
