package com.github.highcumontoa.springboottraceobservabilityjava.tests.trace;

import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.TraceContext;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.TraceContextHolder;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.TracingTaskDecorator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖并发下的上下文隔离：
 * <ul>
 *   <li>大量请求线程并发设置各自 traceId，任何时刻读到的都必须是本线程的，不串号；</li>
 *   <li>任务经 {@link TracingTaskDecorator} 提交到小线程池：池线程读到的必须是
 *       提交方快照，任务结束后池线程必须恢复为空（无线程残留）。</li>
 * </ul>
 * 用例日志打印线程数、观测次数与判定依据。
 */
class TraceContextConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(TraceContextConcurrencyTest.class);

    @Test
    @DisplayName("并发请求：ThreadLocal 上下文严格按线程隔离，不串号、无残留")
    void concurrentRequestsShouldNotCrossContexts() throws Exception {
        int threads = 64;
        int iterationsPerThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger mismatches = new AtomicInteger();
        AtomicInteger leaks = new AtomicInteger();
        ConcurrentHashMap<String, Long> seenTraceIds = new ConcurrentHashMap<>();

        for (int t = 0; t < threads; t++) {
            final String expectedTraceId = String.format("%032d", t + 1).replace(' ', '0');
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterationsPerThread; i++) {
                        TraceContext ctx = new TraceContext(
                                expectedTraceId,
                                String.format("%016d", i + 1L),
                                null, TraceContext.Source.EXTRACTED);
                        TraceContextHolder.set(ctx);
                        MDC.put("traceId", expectedTraceId);
                        // 模拟业务处理中多次读取
                        for (int k = 0; k < 5; k++) {
                            TraceContext read = TraceContextHolder.get();
                            String mdc = MDC.get("traceId");
                            seenTraceIds.merge(expectedTraceId, 1L, Long::sum);
                            if (!expectedTraceId.equals(read.traceId()) || !expectedTraceId.equals(mdc)) {
                                mismatches.incrementAndGet();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    // 请求出口必须清理，模拟 TraceFilter 的 finally
                    TraceContextHolder.clear();
                    MDC.clear();
                    if (TraceContextHolder.get() != null) {
                        leaks.incrementAndGet();
                    }
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发用例应在超时前完成");
        pool.shutdown();

        long totalObservations = seenTraceIds.values().stream().mapToLong(Long::longValue).sum();
        log.info("[并发隔离] 线程数={} 每轮读取=5 总观测={} 期望traceId种类={} 串号次数={} 残留次数={}",
                threads, totalObservations, seenTraceIds.size(), mismatches.get(), leaks.get());

        assertEquals(0, mismatches.get(), "并发下不允许读到其它线程的 traceId（串号）");
        assertEquals(0, leaks.get(), "请求出口清理后不允许 ThreadLocal 残留");
        assertEquals(threads, seenTraceIds.size(), "每个线程一个独立 traceId");
        assertEquals((long) threads * iterationsPerThread * 5, totalObservations, "观测总数不丢");
    }

    @Test
    @DisplayName("池化线程：装饰器传播提交方快照，任务结束后无线程残留")
    void pooledThreadsShouldNotLeakContextAfterTask() throws Exception {
        int taskCount = 200;
        ExecutorService rawPool = Executors.newFixedThreadPool(2);
        TracingTaskDecorator decorator = new TracingTaskDecorator();
        ExecutorService decoratedPool = Executors.newFixedThreadPool(2);

        AtomicInteger correctSnapshot = new AtomicInteger();
        AtomicInteger residualLeaks = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(taskCount);

        for (int i = 0; i < taskCount; i++) {
            final String traceId = String.format("%032x", (0x1000L + i));
            // 在“请求线程”上建立上下文
            TraceContext ctx = new TraceContext(traceId, "0000000000000001", null, TraceContext.Source.EXTRACTED);
            TraceContextHolder.set(ctx);
            MDC.put("traceId", traceId);
            try {
                decoratedPool.submit(decorator.decorate(() -> {
                    try {
                        TraceContext got = TraceContextHolder.get();
                        if (got != null && traceId.equals(got.traceId())
                                && traceId.equals(MDC.get("traceId"))) {
                            correctSnapshot.incrementAndGet();
                        }
                    } finally {
                        done.countDown();
                    }
                }));
            } finally {
                TraceContextHolder.clear();
                MDC.clear();
            }
        }

        assertTrue(done.await(30, TimeUnit.SECONDS));
        // 等待所有任务真正结束后，检查池线程是否残留上一个任务的上下文
        decoratedPool.shutdown();
        assertTrue(decoratedPool.awaitTermination(5, TimeUnit.SECONDS));
        rawPool.shutdown();

        // 通过再投一个“空上下文”探针到已清空的池，验证恢复语义本身正确
        ExecutorService probePool = Executors.newFixedThreadPool(1);
        CountDownLatch probeDone = new CountDownLatch(1);
        probePool.submit(decorator.decorate(() -> {
            if (TraceContextHolder.get() != null) {
                residualLeaks.incrementAndGet();
            }
            probeDone.countDown();
        }));
        assertTrue(probeDone.await(5, TimeUnit.SECONDS));
        probePool.shutdown();

        log.info("[池化传播] 任务数={} 正确读到提交方快照={} 空快照探针残留={}",
                taskCount, correctSnapshot.get(), residualLeaks.get());

        assertEquals(taskCount, correctSnapshot.get(), "每个异步任务都必须读到提交方 traceId");
        assertEquals(0, residualLeaks.get(), "无提交上下文时池线程不应有任何链路残留");
    }
}
