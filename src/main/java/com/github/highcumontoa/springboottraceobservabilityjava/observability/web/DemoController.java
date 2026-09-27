package com.github.highcumontoa.springboottraceobservabilityjava.observability.web;

import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.TraceContext;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.TraceContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

/**
 * 本地验证用演示接口：
 * <ul>
 *   <li>GET /api/ping            正常，业务日志带 traceId；</li>
 *   <li>GET /api/users/{id}      路径参数，验证不同 id 归并为一条指标；</li>
 *   <li>GET /api/async           切到装饰过的线程池，验证异步日志/上下文不串号；</li>
 *   <li>GET /api/boom            业务异常 500，验证错误分类与链路不中断记录。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final ExecutorService demoExecutor;

    public DemoController(@Qualifier("observabilityDemoExecutor") ExecutorService demoExecutor) {
        this.demoExecutor = demoExecutor;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        TraceContext ctx = TraceContextHolder.get();
        log.info("ping handled | traceId={} | origin={}", ctx.traceId(), ctx.source());
        return body("pong");
    }

    @GetMapping("/users/{id}")
    public Map<String, Object> user(@PathVariable String id) {
        log.info("lookup user | userId={}", id);
        Map<String, Object> result = body("user-" + id);
        result.put("pathId", id);
        return result;
    }

    @GetMapping("/async")
    public Map<String, Object> async(@RequestParam(defaultValue = "20") long delayMillis)
            throws ExecutionException, InterruptedException {
        String requestTraceId = TraceContextHolder.get().traceId();
        log.info("submit async work | requestTraceId={}", requestTraceId);

        CompletableFuture<Map<String, String>> future = CompletableFuture.supplyAsync(() -> {
            // 运行在装饰过的池线程：这里的 MDC/上下文必须是提交方请求的
            TraceContext asyncCtx = TraceContextHolder.get();
            log.info("async work running on pooled thread");
            try {
                Thread.sleep(Math.max(delayMillis, 0L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of(
                    "requestTraceId", requestTraceId,
                    "asyncTraceId", asyncCtx == null ? null : asyncCtx.traceId(),
                    "mdcTraceId", String.valueOf(MDC.get("traceId")));
        }, demoExecutor);

        Map<String, Object> result = body("async-done");
        result.put("work", future.get());
        return result;
    }

    @GetMapping("/boom")
    public Map<String, Object> boom() {
        log.warn("about to fail on purpose");
        throw new IllegalStateException("intentional business failure");
    }

    private static Map<String, Object> body(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        TraceContext ctx = TraceContextHolder.get();
        map.put("message", message);
        map.put("traceId", ctx == null ? null : ctx.traceId());
        return map;
    }
}
