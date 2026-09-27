package com.github.highcumontoa.springboottraceobservabilityjava.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 可观测性组件的故障隔离与自检。
 *
 * <p>所有观测动作（解析头、写 MDC、注册/更新指标、输出结构化日志）都通过
 * {@link #runSafely} 执行：内部出错时：
 * <ol>
 *     <li><b>不抛给业务</b>：请求继续按正常路径执行，绝不因观测组件故障导致业务失败；</li>
 *     <li><b>不静默吞掉</b>：错误以 WARN 级别写入独立 logger {@code observability.self-diagnostics}，
 *         同时按操作名累加内存计数 {@code observability_self_errors_total}，
 *         可通过 /actuator/metrics 之外的自检接口或测试直接读取核对。</li>
 * </ol>
 * 计数器的 key 只有受信的操作名（代码常量），不会引入无界标签。
 */
public final class SelfDiagnostics {

    private static final Logger DIAGNOSTICS_LOGGER =
            LoggerFactory.getLogger("observability.self-diagnostics");

    private static final ConcurrentMap<String, AtomicLong> ERROR_COUNTS =
            new ConcurrentHashMap<>();

    private SelfDiagnostics() {
    }

    /** 执行一段观测逻辑，内部异常被隔离：仅记录，不抛出。 */
    public static void runSafely(String operation, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            handleFailure(operation, t);
        }
    }

    /** 可返回值的安全执行版本；失败时返回 {@code fallback}。 */
    public static <T> T supplySafely(String operation, T fallback, SafeSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            handleFailure(operation, t);
            return fallback;
        }
    }

    /** 返回某类观测操作的累计内部错误数（供自检与测试核对）。 */
    public static long errorCount(String operation) {
        AtomicLong counter = ERROR_COUNTS.get(operation);
        return counter == null ? 0L : counter.get();
    }

    /** 测试专用：清零计数。 */
    public static void resetErrorCounts() {
        ERROR_COUNTS.clear();
    }

    private static void handleFailure(String operation, Throwable t) {
        ERROR_COUNTS.computeIfAbsent(operation, k -> new AtomicLong()).incrementAndGet();
        // 不使用业务 MDC 字段，避免自身故障时再踩 MDC；日志内容保持稳定
        DIAGNOSTICS_LOGGER.warn("observability component failure isolated, operation={}, errorType={}",
                operation, t.getClass().getName(), t);
    }

    @FunctionalInterface
    public interface SafeSupplier<T> {
        T get() throws Exception;
    }
}
