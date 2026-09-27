package com.github.highcumontoa.springboottraceobservabilityjava.web;

import com.github.highcumontoa.springboottraceobservabilityjava.observability.TraceContext;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.TraceContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * 演示线程池切换后链路延续：在 {@code obsTaskExecutor} 工作线程上读取链路上下文并打日志。
 */
@Service
public class DemoAsyncService {

    private static final Logger log = LoggerFactory.getLogger(DemoAsyncService.class);

    @Async("obsTaskExecutor")
    public CompletableFuture<String> asyncTraceEcho() {
        TraceContext context = TraceContextHolder.get();
        String traceId = context == null ? null : context.traceId();
        log.info("async task executing on pooled thread, traceId={}", traceId);
        return CompletableFuture.completedFuture(traceId);
    }
}
