package com.github.highcumontoa.springboottraceobservabilityjava.observability.trace;

/**
 * 基于 ThreadLocal 的请求链路上下文持有器。
 *
 * <p>仅允许在请求入口设置、出口清理；线程切换时用 {@link #wrap(Runnable)}
 * 捕获调用方的上下文快照，在执行线程上恢复，执行完毕后还原执行线程的旧值
 * （池化线程正常应为空），保证线程复用不残留、不串号。</p>
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

    /**
     * 在调用方线程捕获上下文快照，返回的 Runnable 在任意线程执行时：
     * 先保存执行线程原有上下文 -> 放入快照 -> finally 恢复原有上下文。
     */
    public static Runnable wrap(Runnable delegate) {
        TraceContext snapshot = CURRENT.get();
        return () -> {
            TraceContext previous = CURRENT.get();
            set(snapshot);
            try {
                delegate.run();
            } finally {
                set(previous);
            }
        };
    }
}
