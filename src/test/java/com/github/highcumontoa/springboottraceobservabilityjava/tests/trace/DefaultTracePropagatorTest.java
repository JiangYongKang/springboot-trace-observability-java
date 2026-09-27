package com.github.highcumontoa.springboottraceobservabilityjava.tests.trace;

import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.DefaultTracePropagator;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.ExtractResult;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.TraceContext;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.TracePropagator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 W3C traceparent 传播判定：
 * 合法 -> 延续；缺失 -> 新起；各类非法 -> 拒绝并另起。
 * 每个用例都打印“输入 + 判定状态 + 判定依据 reason”，便于在单测日志中核对。
 */
class DefaultTracePropagatorTest {

    private static final Logger log = LoggerFactory.getLogger(DefaultTracePropagatorTest.class);
    private static final Pattern HEX32 = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern HEX16 = Pattern.compile("[0-9a-f]{16}");

    private final TracePropagator propagator = new DefaultTracePropagator();

    private ExtractResult judge(String input) {
        ExtractResult r = propagator.extract(input);
        log.info("[输入] traceparent={} -> [判定] status={} reason={} contextTraceId={}",
                input, r.status(), r.reason(),
                r.context() == null ? null : r.context().traceId());
        return r;
    }

    @Test
    @DisplayName("合法 traceparent：延续 traceId，新生成 spanId，parentSpanId 为上游 spanId")
    void shouldExtractValidTraceParent() {
        String header = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        ExtractResult r = judge(header);

        assertEquals(ExtractResult.Status.EXTRACTED, r.status());
        assertEquals("0af7651916cd43dd8448eb211c80319c", r.context().traceId());
        assertEquals("b7ad6b7169203331", r.context().parentSpanId());
        assertTrue(HEX16.matcher(r.context().spanId()).matches(), "本服务应生成新的 16 位 spanId");
        assertNotEquals("b7ad6b7169203331", r.context().spanId(), "当前 spanId 不能与上游 spanId 相同");
        assertEquals(TraceContext.Source.EXTRACTED, r.context().source());

        // 回写头应保留同一 traceId 并带新 spanId
        String outgoing = propagator.formatW3CTraceParent(r.context());
        log.info("[回写] traceparent={}", outgoing);
        assertTrue(outgoing.startsWith("00-0af7651916cd43dd8448eb211c80319c-"));
    }

    @Nested
    @DisplayName("缺失场景：必须判为 MISSING 并新起 GENERATED 链路")
    class Missing {

        @Test
        void nullHeader() {
            assertGenerated(judge(null), "null");
        }

        @Test
        void emptyHeader() {
            assertGenerated(judge(""), "empty");
        }

        @Test
        void blankHeader() {
            assertGenerated(judge("   "), "blank");
        }
    }

    private void assertGenerated(ExtractResult r, String desc) {
        assertEquals(ExtractResult.Status.MISSING, r.status(), "缺失输入[" + desc + "]应判 MISSING");
        assertNull(r.context(), "MISSING 不应携带上下文，由调用方显式 generate()");
        TraceContext fresh = propagator.generate();
        assertTrue(HEX32.matcher(fresh.traceId()).matches());
        assertTrue(HEX16.matcher(fresh.spanId()).matches());
        assertEquals(TraceContext.Source.GENERATED, fresh.source());
        log.info("[新起] {} -> traceId={} spanId={}", desc, fresh.traceId(), fresh.spanId());
    }

    @Nested
    @DisplayName("非法场景：必须判为 INVALID（区别于 MISSING），拒绝延续")
    class Invalid {

        @Test
        void wrongFieldCount() {
            assertInvalid("ZZ-not-a-valid-header");
            assertInvalid("00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb");
            assertInvalid("00-aa-bb-01-extra");
        }

        @Test
        void nonHexCharacters() {
            assertInvalid("00-0af7651916cd43dd8448eb211c80319z-b7ad6b7169203331-01");
        }

        @Test
        void upperCaseHexRejected() {
            // 规则：只接受小写十六进制；大写视为非法（归一化由调用方显式处理，避免隐式吞数据）
            assertInvalid("00-0AF7651916CD43DD8448EB211C80319C-B7AD6B7169203331-01");
        }

        @Test
        void allZeroTraceId() {
            assertInvalid("00-00000000000000000000000000000000-b7ad6b7169203331-01");
        }

        @Test
        void allZeroSpanId() {
            assertInvalid("00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01");
        }

        @Test
        void forbiddenVersion() {
            assertInvalid("ff-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        }

        @Test
        void wrongLengthForVersionZero() {
            assertInvalid("00-short-b7ad6b7169203331-01");
        }

        @Test
        void invalidFlags() {
            assertInvalid("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-xyz");
        }
    }

    private void assertInvalid(String header) {
        ExtractResult r = judge(header);
        assertEquals(ExtractResult.Status.INVALID, r.status(),
                "非法输入应判 INVALID 而非 MISSING/EXTRACTED: " + header);
        assertNull(r.context(), "非法头不得延续任何上下文: " + header);
        assertTrue(r.reason() != null && !r.reason().isBlank(), "非法必须给出可排查的 reason");
    }
}
