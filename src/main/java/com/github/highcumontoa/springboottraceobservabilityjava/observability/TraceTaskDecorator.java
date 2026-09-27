package com.github.highcumontoa.springboottraceobservabilityjava.observability;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 线程池/异步任务的链路上下文传播装饰器。
 *
 * <p>在提交任务的线程（通常是请求线程）拍下 ThreadLocal 上下文与 MDC 的快照；
 * 在实际执行任务的工作线程上：
 * <ol>
 *     <li>先保存工作线程原有的（理论上应为空的）上下文；</li>
 *     <li>设置为快照内容后执行任务；</li>
 *     <li>无论成功失败，finally 中恢复原有上下文并强制清理本次写入的所有字段。</li>
 * </ol>
 * 因此跨线程不会串到别的请求，任务结束后工作线程也不会残留链路信息。
 */
public class TraceTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 在提交线程上捕获快照
        final TraceContext captured = TraceContextHolder.get();
        final Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
        return () -> {
            if (captured == null) {
                // 提交时没有请求上下文，确保工作线程也不会带着残留执行
                TraceContextHolder.clear();
                MDC.clear();
                try {
                    runnable.run();
                } finally {
                    TraceContextHolder.clear();
                    MDC.clear();
                }
                return;
            }
            // 保存工作线程原有上下文（嵌套场景下的保险措施）
            TraceContext previous = TraceContextHolder.get();
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            TraceContextHolder.set(captured);
            setMdc(mdcSnapshot);
            try {
                runnable.run();
            } finally {
                if (previous != null) {
                    TraceContextHolder.set(previous);
                } else {
                    TraceContextHolder.clear();
                }
                if (previousMdc != null) {
                    setMdc(previousMdc);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    private static void setMdc(Map<String, String> mdc) {
        MDC.clear();
        if (mdc != null) {
            MDC.setContextMap(mdc);
        }
    }
}
