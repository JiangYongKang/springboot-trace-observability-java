package com.github.highcumontoa.springboottraceobservabilityjava.observability.trace;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 一次请求的链路上下文：traceId 贯穿整条链路，spanId 标识当前跨度。
 * source 区分该上下文是延续上游合法头，还是本服务新起的。
 *
 * <p>ID 规则遵循 W3C Trace Context：traceId 为 16 字节(32 个十六进制字符)，
 * spanId 为 8 字节(16 个十六进制字符)，全部小写。</p>
 */
public record TraceContext(String traceId, String spanId, String parentSpanId, Source source) {

    public enum Source {
        /** 延续了上游传入的合法链路头 */
        EXTRACTED,
        /** 上游缺失或非法，本服务新起一条链路 */
        GENERATED
    }

    /**
     * 基于上游合法 traceparent 构造上下文：当前服务的 spanId 重新生成，
     * 上游 spanId 作为 parentSpanId，traceId 延续。
     */
    public static TraceContext extracted(String traceId, String parentSpanId) {
        return new TraceContext(traceId, randomSpanId(), parentSpanId, Source.EXTRACTED);
    }

    /** 上游缺失或非法时，生成一条全新链路。 */
    public static TraceContext generated() {
        return new TraceContext(randomTraceId(), randomSpanId(), null, Source.GENERATED);
    }

    public static String randomTraceId() {
        byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static String randomSpanId() {
        byte[] bytes = new byte[8];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
