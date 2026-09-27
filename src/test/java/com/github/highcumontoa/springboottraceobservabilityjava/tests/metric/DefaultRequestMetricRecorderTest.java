package com.github.highcumontoa.springboottraceobservabilityjava.tests.metric;

import com.github.highcumontoa.springboottraceobservabilityjava.observability.metric.DefaultRequestMetricRecorder;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.metric.MetricLabels;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.metric.MetricSnapshot;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.metric.RequestMetricRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖指标记录器的并发正确性与基数有界性：
 * <ul>
 *   <li>多线程对同一组标签并发累加：count/total 不重不丢；</li>
 *   <li>多线程并发注册大量不同标签：实际注册组合数严格不超过 maxSeries，
 *       超出部分全部进入 __overflow__ 兜底桶，总数不丢。</li>
 * </ul>
 */
class DefaultRequestMetricRecorderTest {

    private static final Logger log = LoggerFactory.getLogger(DefaultRequestMetricRecorderTest.class);

    private MetricLabels labels(String route, String result, String origin) {
        return new MetricLabels("GET", route, result, origin);
    }

    @Test
    @DisplayName("并发累加：同一标签组合的 count/total 精确不重不丢")
    void concurrentRecordingShouldNotLoseOrDuplicate() throws Exception {
        RequestMetricRecorder recorder = new DefaultRequestMetricRecorder(10);
        int threads = 32;
        int perThread = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();

        MetricLabels target = labels("/api/ping", "SUCCESS", "GENERATED");
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        recorder.record(target, 100L);
                    }
                } catch (InterruptedException e) {
                    failures.incrementAndGet();
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        MetricSnapshot snap = recorder.snapshot().get(target);
        long expected = (long) threads * perThread;
        log.info("[并发累加] 线程数={} 每线程={} 期望count={} 实际count={} 期望total={} 实际total={} 序列数={} 异常={}",
                threads, perThread, expected, snap.count(), expected * 100L, snap.totalNanos(),
                recorder.snapshot().size(), failures.get());

        assertEquals(expected, snap.count(), "并发 count 必须精确，不重不丢");
        assertEquals(expected * 100L, snap.totalNanos(), "并发 total 必须精确");
        assertEquals(100L, snap.maxNanos());
        assertEquals(1, recorder.snapshot().size(), "同一标签组合只允许注册一次");
        assertEquals(0, failures.get());
    }

    @Test
    @DisplayName("基数有界：并发注册超过上限的标签时，序列数严格不超 maxSeries，且计数不丢")
    void seriesCountShouldBeBounded() throws Exception {
        int maxSeries = 50;
        int distinctLabels = 500;
        int repetitions = 20;
        RequestMetricRecorder recorder = new DefaultRequestMetricRecorder(maxSeries);

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(distinctLabels);
        for (int i = 0; i < distinctLabels; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 每个唯一路由重复写多次，制造注册竞争
                    MetricLabels l = labels("/api/r/" + idx, "SUCCESS", "GENERATED");
                    for (int r = 0; r < repetitions; r++) {
                        recorder.record(l, 1L);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        Map<MetricLabels, MetricSnapshot> snapshot = recorder.snapshot();
        long totalCount = snapshot.values().stream().mapToLong(MetricSnapshot::count).sum();
        boolean hasOverflow = snapshot.keySet().stream()
                .anyMatch(l -> DefaultRequestMetricRecorder.OVERFLOW_ROUTE.equals(l.route()));
        int normalSeries = snapshot.size() - (hasOverflow ? 1 : 0);

        log.info("[基数有界] 唯一标签={} 每标签重复={} maxSeries={} 实际普通序列={} 含兜底桶={} 总计数={} 期望总数={}",
                distinctLabels, repetitions, maxSeries, normalSeries, hasOverflow,
                totalCount, (long) distinctLabels * repetitions);

        assertTrue(normalSeries <= maxSeries, "普通序列数严格不超过上限");
        assertTrue(hasOverflow, "超出上限的标签必须进入 __overflow__ 兜底桶");
        assertEquals((long) distinctLabels * repetitions, totalCount, "兜底桶承接后总计数不丢");
    }
}
