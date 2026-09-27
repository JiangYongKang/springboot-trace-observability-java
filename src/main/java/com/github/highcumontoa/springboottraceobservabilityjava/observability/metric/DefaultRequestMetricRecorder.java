package com.github.highcumontoa.springboottraceobservabilityjava.observability.metric;

import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.TraceFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 默认请求指标记录器。
 *
 * <p>并发安全：读/更新走 {@link ConcurrentHashMap} 与 {@link AtomicLong}
 * 原子累加（更新不丢失）；新标签组合在同步块内双重检查注册（注册不重复）；
 * 读取走快照拷贝。</p>
 *
 * <p>系列数（标签组合基数）硬上限：超过 {@code maxSeries} 的新组合不再注册，
 * 统一计入 {@code __overflow__} 兜底桶并首次告警，保证指标数量有界。</p>
 */
public class DefaultRequestMetricRecorder implements RequestMetricRecorder {

    private static final Logger selfLog = LoggerFactory.getLogger(TraceFilter.OBS_LOGGER_NAME);

    /** 超出基数上限的标签统一归入的兜底路由名。 */
    public static final String OVERFLOW_ROUTE = "__overflow__";
    public static final String OTHER = "OTHER";

    private final int maxSeries;
    private final Map<MetricLabels, Series> seriesMap = new ConcurrentHashMap<>();
    /** 已分配的标签组合槽位；在 computeIfAbsent 的原子块内递增，保证严格不超上限。 */
    private final AtomicLong allocatedSlots = new AtomicLong();
    private final Series overflow = new Series();
    private final AtomicLong overflowDropped = new AtomicLong();
    private final AtomicLong overflowWarned = new AtomicLong();

    public DefaultRequestMetricRecorder(int maxSeries) {
        if (maxSeries < 1) {
            throw new IllegalArgumentException("maxSeries must be >= 1, actual=" + maxSeries);
        }
        this.maxSeries = maxSeries;
    }

    @Override
    public void record(MetricLabels labels, long durationNanos) {
        if (labels == null) {
            throw new IllegalArgumentException("labels must not be null");
        }
        long d = Math.max(durationNanos, 0L);
        Series target = seriesMap.get(labels);
        if (target == null) {
            target = register(labels);
        }
        target.add(d);
    }

    /**
     * 读路径无锁；仅当出现新标签组合时进入同步块注册（冷路径）。
     * 同步块内双重检查 + 槽位计数，保证：同一标签组合只注册一次，
     * 已注册组合总数严格不超过 maxSeries，超限组合进入兜底桶。
     */
    private synchronized Series register(MetricLabels labels) {
        Series existing = seriesMap.get(labels);
        if (existing != null) {
            return existing;
        }
        if (allocatedSlots.get() >= maxSeries) {
            long dropped = overflowDropped.incrementAndGet();
            if (overflowWarned.compareAndSet(0, 1)) {
                selfLog.error("metric series limit reached, further label sets go to overflow bucket | maxSeries={} | droppedLabels={}",
                        maxSeries, dropped);
            }
            return overflow;
        }
        Series created = new Series();
        seriesMap.put(labels, created);
        allocatedSlots.incrementAndGet();
        return created;
    }

    @Override
    public Map<MetricLabels, MetricSnapshot> snapshot() {
        Map<MetricLabels, MetricSnapshot> copy = new LinkedHashMap<>();
        seriesMap.forEach((labels, series) -> copy.put(labels, series.snapshot()));
        if (overflow.count.get() > 0) {
            copy.put(new MetricLabels(OTHER, OVERFLOW_ROUTE, OTHER, OTHER), overflow.snapshot());
        }
        return Map.copyOf(copy);
    }

    @Override
    public int maxSeries() {
        return maxSeries;
    }

    /**
     * 单个标签组合的累计值。
     */
    private static final class Series {
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong totalNanos = new AtomicLong();
        private final AtomicLong maxNanos = new AtomicLong();

        void add(long durationNanos) {
            count.incrementAndGet();
            totalNanos.addAndGet(durationNanos);
            maxNanos.accumulateAndGet(durationNanos, Math::max);
        }

        MetricSnapshot snapshot() {
            return new MetricSnapshot(count.get(), totalNanos.get(), maxNanos.get());
        }
    }
}
