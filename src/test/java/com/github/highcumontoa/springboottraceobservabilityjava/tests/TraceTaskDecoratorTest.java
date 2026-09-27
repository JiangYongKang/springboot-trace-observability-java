package com.github.highcumontoa.springboottraceobservabilityjava.tests;

import com.github.highcumontoa.springboottraceobservabilityjava.observability.TraceContext;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.TraceContextHolder;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.TraceDecision;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.TraceHeaderParser;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.TraceIds;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.TraceMdc;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.TraceTaskDecorator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 异步/线程池切换：链路必须正确传播、任务结束后必须清理、复用线程不得串号。
 */
class TraceTaskDecoratorTest {

    private static final Logger log = LoggerFactory.getLogger(TraceTaskDecoratorTest.class);

    private final TraceHeaderParser parser = new TraceHeaderParser();

    @AfterEach
    void cleanMainThread() {
        TraceContextHolder.clear();
        TraceMdc.clear();
    }

    @Test
    void contextPropagatesToWorkerAndIsClearedAfterwards() throws Exception {
        ThreadPoolExecutor pool = newPool(1);
        try {
            TraceContext ctx = parser.parse(TraceIds.newTraceId(), TraceIds.newSpanId(), null);
            TraceContextHolder.set(ctx);
            TraceMdc.put(ctx);
            log.info("[input] submit task with traceId={}", ctx.traceId());

            AtomicReference<String> seenInWorker = new AtomicReference<>();
            AtomicReference<String> mdcInWorker = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            pool.execute(new TraceTaskDecorator().decorate(() -> {
                seenInWorker.set(TraceContextHolder.get() == null
                        ? null : TraceContextHolder.get().traceId());
                mdcInWorker.set(MDC.get(TraceMdc.TRACE_ID));
                done.countDown();
            }));
            assertTrue(done.await(5, TimeUnit.SECONDS));

            log.info("[basis] worker saw holderTraceId={}, mdcTraceId={}, expected={}",
                    seenInWorker.get(), mdcInWorker.get(), ctx.traceId());
            assertEquals(ctx.traceId(), seenInWorker.get());
            assertEquals(ctx.traceId(), mdcInWorker.get());

            // 线程复用后必须无残留
            CountDownLatch cleanLatch = new CountDownLatch(1);
            AtomicReference<Boolean> residual = new AtomicReference<>(true);
            pool.execute(() -> {
                residual.set(TraceContextHolder.isPresent() || MDC.get(TraceMdc.TRACE_ID) != null);
                cleanLatch.countDown();
            });
            assertTrue(cleanLatch.await(5, TimeUnit.SECONDS));
            log.info("[basis] residual context on reused worker thread: {}", residual.get());
            assertFalse(residual.get(), "no context may leak onto the pooled thread");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentTasksWithDifferentTracesDoNotCrossTalk() throws Exception {
        int threads = 8;
        int perThread = 50;
        ThreadPoolExecutor pool = newPool(4);
        TraceTaskDecorator decorator = new TraceTaskDecorator();
        CountDownLatch all = new CountDownLatch(threads * perThread);
        @SuppressWarnings("unchecked")
        java.util.Set<Long> mismatches = java.util.concurrent.ConcurrentHashMap.newKeySet();
        try {
            for (int t = 0; t < threads; t++) {
                final int submitter = t;
                Thread submitterThread = new Thread(() -> {
                    for (int i = 0; i < perThread; i++) {
                        TraceContext ctx = new TraceContext(
                                TraceIds.newTraceId(), TraceIds.newSpanId(), null,
                                TraceDecision.INHERITED, null);
                        TraceContextHolder.set(ctx);
                        pool.execute(decorator.decorate(() -> {
                            try {
                                TraceContext actual = TraceContextHolder.get();
                                if (actual == null || !actual.traceId().equals(ctx.traceId())) {
                                    mismatches.add((long) submitter);
                                    log.error("[CROSS-TALK] expected {} but worker saw {}",
                                            ctx.traceId(), actual == null ? null : actual.traceId());
                                }
                                // 制造线程切换/排队窗口
                                Thread.sleep(2);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                all.countDown();
                            }
                        }));
                        TraceContextHolder.clear();
                    }
                }, "submitter-" + t);
                submitterThread.start();
                submitterThread.join();
            }
            assertTrue(all.await(30, TimeUnit.SECONDS), "all async tasks must finish");
            log.info("[basis] {} tasks across {} submitters on 4 workers, cross-talk count={}",
                    threads * perThread, threads, mismatches.size());
            assertEquals(0, mismatches.size(), "trace context must not cross between requests");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void taskWithoutContextLeavesWorkerClean() throws Exception {
        ThreadPoolExecutor pool = newPool(1);
        try {
            TraceContextHolder.clear();
            TraceMdc.clear();
            AtomicReference<Boolean> present = new AtomicReference<>(true);
            CountDownLatch done = new CountDownLatch(1);
            pool.execute(new TraceTaskDecorator().decorate(() -> {
                present.set(TraceContextHolder.isPresent());
                done.countDown();
            }));
            assertTrue(done.await(5, TimeUnit.SECONDS));
            log.info("[input] task submitted without any trace context -> worker present={}",
                    present.get());
            assertFalse(present.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private ThreadPoolExecutor newPool(int size) {
        return new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
    }
}
