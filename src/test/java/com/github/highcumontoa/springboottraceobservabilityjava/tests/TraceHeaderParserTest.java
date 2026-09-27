package com.github.highcumontoa.springboottraceobservabilityjava.tests;

import com.github.highcumontoa.springboottraceobservabilityjava.observability.TraceContext;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.TraceDecision;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.TraceHeaderParser;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.TraceIds;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 链路头解析：三类处置必须可区分，非法场景要给出判定依据。
 */
class TraceHeaderParserTest {

    private static final Logger log = LoggerFactory.getLogger(TraceHeaderParserTest.class);

    private final TraceHeaderParser parser = new TraceHeaderParser();

    @Test
    void missingHeadersGenerateNewTrace() {
        TraceContext c = parser.parse(null, "  ", null);
        log.info("[input] traceId=null, spanId=blank, parentSpanId=null -> "
                + "[decision] {}, traceId={}, spanId={}, rejectReason={}",
                c.decision(), c.traceId(), c.spanId(), c.rejectReason());
        assertEquals(TraceDecision.GENERATED_MISSING, c.decision());
        assertNull(c.parentSpanId());
        assertNull(c.rejectReason());
        assertTrue(TraceIds.isValidTraceId(c.traceId()), "generated traceId must be valid");
        assertTrue(TraceIds.isValidSpanId(c.spanId()), "generated spanId must be valid");
        assertFalse(c.inherited());
    }

    @Test
    void validHeadersAreInheritedAndNormalizedToLowercase() {
        String traceId = "0AF762B4464C4D578B2F1C042A32F699";
        String spanId = "ABCDEF1234567890";
        TraceContext c = parser.parse(traceId, spanId, null);
        log.info("[input] traceId={}, spanId={} -> [decision] {}, parentSpanId={}, newSpanId={}",
                traceId, spanId, c.decision(), c.parentSpanId(), c.spanId());
        assertEquals(TraceDecision.INHERITED, c.decision());
        assertEquals(traceId.toLowerCase(), c.traceId());
        assertEquals(spanId.toLowerCase(), c.parentSpanId(),
                "upstream spanId becomes this request's parentSpanId");
        assertNotEquals(spanId.toLowerCase(), c.spanId(), "a new spanId must be generated");
        assertTrue(c.inherited());
    }

    @Test
    void illegalTraceIdIsRejectedWithReason() {
        TraceContext c = parser.parse("not-a-trace-id", "ABCDEF1234567890", null);
        log.info("[input] traceId='not-a-trace-id' (bad), spanId legal -> "
                + "[decision] {}, [basis] {}", c.decision(), c.rejectReason());
        assertEquals(TraceDecision.GENERATED_INVALID, c.decision());
        assertNotNull(c.rejectReason());
        assertTrue(c.rejectReason().contains("illegal traceId"));
        assertTrue(TraceIds.isValidTraceId(c.traceId()));
    }

    @Test
    void allZeroTraceIdIsRejected() {
        TraceContext c = parser.parse("00000000000000000000000000000000",
                "0000000000000001", null);
        log.info("[input] all-zero traceId -> [decision] {}, [basis] {}",
                c.decision(), c.rejectReason());
        assertEquals(TraceDecision.GENERATED_INVALID, c.decision());
        assertTrue(c.rejectReason().contains("illegal traceId"));
    }

    @Test
    void partialHeadersAreTreatedAsInvalidNotMissing() {
        TraceContext c = parser.parse(null, "ABCDEF1234567890", null);
        log.info("[input] traceId missing but spanId present -> [decision] {}, [basis] {}",
                c.decision(), c.rejectReason());
        assertEquals(TraceDecision.GENERATED_INVALID, c.decision());
        assertNotNull(c.rejectReason());
    }

    @Test
    void illegalParentSpanIdIsRejected() {
        TraceContext c = parser.parse("0af762b4464c4d578b2f1c042a32f699",
                "abcdef1234567890", "short");
        log.info("[input] legal trace/span but illegal parentSpanId='short' -> "
                + "[decision] {}, [basis] {}", c.decision(), c.rejectReason());
        assertEquals(TraceDecision.GENERATED_INVALID, c.decision());
        assertTrue(c.rejectReason().contains("illegal parentSpanId"));
    }
}
