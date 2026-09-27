package com.github.highcumontoa.springboottraceobservabilityjava.tests.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.logging.StructuredJsonLogLayout;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证结构化日志：每行是合法 JSON；字段集合稳定；
 * MDC 中链路字段缺失时输出 null；引号/换行/控制字符正确转义。
 */
class StructuredJsonLogLayoutTest {

    private static final Set<String> EXPECTED_FIELDS = Set.of(
            "timestamp", "level", "logger", "thread", "message",
            "traceId", "spanId", "traceOrigin", "stack");

    private final ObjectMapper json = new ObjectMapper();

    private ILoggingEvent event(String message, Throwable t) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        LoggingEvent e = new LoggingEvent(
                this.getClass().getName(),
                context.getLogger(this.getClass()),
                Level.INFO,
                message, t, null);
        return e;
    }

    @Test
    void shouldEmitStableFieldsAndValidJsonWithTraceContext() throws Exception {
        StructuredJsonLogLayout layout = new StructuredJsonLogLayout();
        layout.setContext(new LoggerContext());
        layout.start();

        MDC.put("traceId", "0af7651916cd43dd8448eb211c80319c");
        MDC.put("spanId", "b7ad6b7169203331");
        MDC.put("traceOrigin", "EXTRACTED");
        try {
            String line = layout.doLayout(event("hello business", null));
            JsonNode node = json.readTree(line.strip());

            Set<String> actual = new LinkedHashSet<>();
            node.fieldNames().forEachRemaining(actual::add);
            assertEquals(EXPECTED_FIELDS, actual, "字段集合必须固定为 9 个");

            assertEquals("INFO", node.get("level").asText());
            assertEquals("hello business", node.get("message").asText());
            assertEquals("0af7651916cd43dd8448eb211c80319c", node.get("traceId").asText());
            assertEquals("b7ad6b7169203331", node.get("spanId").asText());
            assertEquals("EXTRACTED", node.get("traceOrigin").asText());
            assertTrue(node.get("timestamp").asText().contains("T"), "timestamp 应为 ISO-8601");
            assertTrue(node.get("stack").isNull(), "无异常时 stack 为 null");
        } finally {
            MDC.clear();
        }
    }

    @Test
    void shouldEmitNullTraceFieldsOutsideRequestContext() throws Exception {
        StructuredJsonLogLayout layout = new StructuredJsonLogLayout();
        layout.setContext(new LoggerContext());
        layout.start();

        String line = layout.doLayout(event("no request here", null));
        JsonNode node = json.readTree(line.strip());
        assertTrue(node.get("traceId").isNull(), "无请求上下文时 traceId 输出 null，字段仍保留");
        assertTrue(node.get("spanId").isNull());
        assertTrue(node.get("traceOrigin").isNull());
    }

    @Test
    void shouldEscapeSpecialCharactersAndRenderStack() throws Exception {
        StructuredJsonLogLayout layout = new StructuredJsonLogLayout();
        layout.setContext(new LoggerContext());
        layout.start();

        RuntimeException boom = new RuntimeException("boom with \"quote\"\nnewline");
        String line = layout.doLayout(event("weird\tmsg", boom));
        // 必须仍是合法 JSON（Jackson 能解析即说明转义正确）
        JsonNode node = json.readTree(line.strip());
        assertEquals("weird\tmsg", node.get("message").asText());
        assertTrue(node.get("stack").asText().contains("boom with \"quote\""));
        assertTrue(node.get("stack").asText().contains("RuntimeException"),
                "异常堆栈应进入 stack 字段");
    }

    @Test
    void escapeHelperShouldHandleControlChars() {
        String escaped = StructuredJsonLogLayout.escape("a\"b\\c\nd\te" + (char) 1);
        assertEquals("a\\\"b\\\\c\\nd\\te", escaped.substring(0, escaped.length() - 6));
        assertTrue(escaped.endsWith("0001"), "U+0001 应转义成 \\\\u0001 形式");
    }
}
