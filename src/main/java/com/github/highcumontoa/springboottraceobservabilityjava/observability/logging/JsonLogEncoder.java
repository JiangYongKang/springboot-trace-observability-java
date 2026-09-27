package com.github.highcumontoa.springboottraceobservabilityjava.observability.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Jackson 的轻量 JSON 日志编码器（每行一条 JSON）。
 *
 * <p>输出字段固定且稳定，业务日志可直接依赖：
 * {@code timestamp, level, logger, thread, traceId, spanId, parentSpanId,
 * traceDecision, message, stacktrace}。
 *
 * <p>链路四字段始终出现（没有上下文时为 {@code null}），避免日志解析端因字段缺失而产生歧义；
 * 其余 MDC 不输出，防止动态字段把日志结构撑散。
 *
 * <p>编码器自身出错属于可观测性组件内部故障：不向业务抛异常（Logback encoder 的异常会影响输出流），
 * 而是输出一条降级的安全 JSON 行，留下可查依据。
 */
public class JsonLogEncoder extends EncoderBase<ILoggingEvent> {

    private static final byte[] EMPTY = new byte[0];
    private static final List<String> STABLE_FIELDS = List.of(
            "timestamp", "level", "logger", "thread",
            "traceId", "spanId", "parentSpanId", "traceDecision",
            "message", "stacktrace");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public byte[] headerBytes() {
        return EMPTY;
    }

    @Override
    public byte[] footerBytes() {
        return EMPTY;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        try {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("timestamp", java.time.Instant.ofEpochMilli(event.getTimeStamp()).toString());
            row.put("level", event.getLevel().toString());
            row.put("logger", event.getLoggerName());
            row.put("thread", event.getThreadName());
            Map<String, String> mdc = event.getMDCPropertyMap();
            row.put("traceId", mdc == null ? null : mdc.get("traceId"));
            row.put("spanId", mdc == null ? null : mdc.get("spanId"));
            row.put("parentSpanId", mdc == null ? null : mdc.get("parentSpanId"));
            row.put("traceDecision", mdc == null ? null : mdc.get("traceDecision"));
            row.put("message", event.getFormattedMessage());
            row.put("stacktrace", renderStacktrace(event));
            String json = objectMapper.writeValueAsString(row) + System.lineSeparator();
            return json.getBytes(StandardCharsets.UTF_8);
        } catch (Throwable t) {
            // 编码器自身故障也不能炸掉业务线程的日志输出，降级为一条可检索的安全 JSON
            String fallback = "{\"level\":\"ERROR\",\"logger\":\"" + getClass().getName()
                    + "\",\"message\":\"json log encoder failure: "
                    + t.getClass().getSimpleName().replace("\"", "'") + "\"}"
                    + System.lineSeparator();
            return fallback.getBytes(StandardCharsets.UTF_8);
        }
    }

    private String renderStacktrace(ILoggingEvent event) {
        if (event.getThrowableProxy() instanceof ThrowableProxy tp && tp.getThrowable() != null) {
            StringWriter sw = new StringWriter(512);
            tp.getThrowable().printStackTrace(new PrintWriter(sw));
            return sw.toString();
        }
        return null;
    }

    /** 供文档/测试引用的稳定字段清单。 */
    public static List<String> stableFields() {
        return STABLE_FIELDS;
    }
}
