package com.github.highcumontoa.springboottraceobservabilityjava.observability.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.LayoutBase;

import java.time.Instant;

/**
 * 字段稳定的单行 JSON 日志布局（手写严格转义，不引入额外 JSON 库）。
 *
 * <p>固定字段集合（不随调用方变化）：
 * {@code timestamp, level, logger, thread, message, traceId, spanId, traceOrigin, stack}。
 * 业务日志通过 MDC 自动带上当次请求的 {@code traceId/spanId/traceOrigin}；
 * 非请求线程（无上下文）这些字段输出 null，字段集合仍然稳定。</p>
 *
 * <p>布局自身不做任何可能抛异常影响业务的操作：取值失败时对应字段回退为 null。</p>
 */
public class StructuredJsonLogLayout extends LayoutBase<ILoggingEvent> {

    private static final int MAX_MESSAGE_LENGTH = 8192;

    private final ThrowableProxyConverter throwableConverter = new ThrowableProxyConverter();

    @Override
    public void start() {
        throwableConverter.setContext(getContext());
        throwableConverter.start();
        super.start();
    }

    @Override
    public String doLayout(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendString(sb, "timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        sb.append(',');
        appendString(sb, "level", levelOf(event));
        sb.append(',');
        appendString(sb, "logger", safe(event::getLoggerName));
        sb.append(',');
        appendString(sb, "thread", safe(event::getThreadName));
        sb.append(',');
        appendString(sb, "message", bounded(safe(event::getFormattedMessage, "")));
        sb.append(',');
        appendString(sb, "traceId", event.getMDCPropertyMap().get("traceId"));
        sb.append(',');
        appendString(sb, "spanId", event.getMDCPropertyMap().get("spanId"));
        sb.append(',');
        appendString(sb, "traceOrigin", event.getMDCPropertyMap().get("traceOrigin"));
        sb.append(',');
        appendString(sb, "stack", renderStack(event));
        sb.append("}\n");
        return sb.toString();
    }

    private static String levelOf(ILoggingEvent event) {
        try {
            return event.getLevel() == null ? null : event.getLevel().toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String renderStack(ILoggingEvent event) {        try {
            if (event.getThrowableProxy() == null) {
                return null;
            }
            return throwableConverter.convert(event);
        } catch (RuntimeException e) {
            return "<stack render failed: " + e.getMessage() + ">";
        }
    }

    private static String bounded(String value) {
        if (value.length() <= MAX_MESSAGE_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_MESSAGE_LENGTH) + "...(truncated)";
    }

    private interface StringSupplier {
        String get();
    }

    private static String safe(StringSupplier supplier) {
        return safe(supplier, null);
    }

    private static String safe(StringSupplier supplier, String fallback) {
        try {
            String v = supplier.get();
            return v == null ? fallback : v;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static void appendString(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escape(value)).append('"');
        }
    }

    /**
     * JSON 字符串转义（按 RFC 8259），控制字符使用 unicode 转义序列。
     */
    public static String escape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        // 注意：源码里不能直接写反斜杠加 u 的四位数转义形式
                        //（Java 词法层在注释/字符串外就会预处理 unicode 转义），故分开 append。
                        sb.append('\\').append('u').append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
