package com.github.highcumontoa.springboottraceobservabilityjava.observability;

/**
 * 基于 ThreadLocal 的请求链路上下文持有器。
 *
 * <p>仅承载当前线程所属请求的链路信息。过滤器在请求结束时必须调用 {@link #clear()}，
 * 线程池场景由 {@link TraceTaskDecorator} 在任务结束时清理，避免线程复用导致串号或残留。
 */
public final class TraceContextHolder {

    private static final ThreadLocal<TraceContext> CURRENT = new ThreadLocal<>();

    private TraceContextHolder() {
    }

    public static void set(TraceContext context) {
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
    }

    public static TraceContext get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static boolean isPresent() {
        return CURRENT.get() != null;
    }
}
