package com.github.highcumontoa.springboottraceobservabilityjava.observability.trace;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 线程池任务装饰器：
 * 在提交线程捕获 {@link TraceContext} 与 MDC 的不可变快照，
 * 在执行线程恢复，任务结束（含异常）后还原执行线程原有值。
 *
 * <p>池化线程复用时，若原无线程上下文则恢复后必为空，不会把 A 请求的
 * traceId 残留到 B 请求；提交方未处于请求上下文时，快照为 null/空，
 * 同样会清空执行线程，防止“上一个任务”的标识泄漏。</p>
 */
public class TracingTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        TraceContext contextSnapshot = TraceContextHolder.get();
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
        return () -> {
            TraceContext previousContext = TraceContextHolder.get();
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            applySnapshot(contextSnapshot, mdcSnapshot);
            try {
                runnable.run();
            } finally {
                applySnapshot(previousContext, previousMdc);
            }
        };
    }

    private static void applySnapshot(TraceContext context, Map<String, String> mdc) {
        TraceContextHolder.set(context);
        if (mdc == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(mdc);
        }
    }
}
