package com.github.highcumontoa.springboottraceobservabilityjava.observability.trace;

import org.springframework.stereotype.Component;

import java.util.HexFormat;
import java.util.Locale;

/**
 * 基于 W3C Trace Context ({@code traceparent}) 的默认传播实现。
 *
 * <p>合法头格式：{@code version-traceId-spanId-flags}，共 4 段，
 * 例如 {@code 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01}。</p>
 * <ul>
 *   <li>请求头缺失或为空 -> {@link ExtractResult.Status#MISSING}，由本服务新起链路；</li>
 *   <li>格式非法、十六进制非法、ID 全 0、保留版本 ff -> {@link ExtractResult.Status#INVALID}，
 *       拒绝延续并由本服务另起一条，判定原因放在 reason 中可排查；</li>
 *   <li>采样标志 flags 不影响传播合法性，原样透传。</li>
 * </ul>
 */
@Component
public class DefaultTracePropagator implements TracePropagator {

    private static final String VERSION = "00";
    private static final String DEFAULT_FLAGS = "01";

    @Override
    public ExtractResult extract(String rawTraceParentHeader) {
        if (rawTraceParentHeader == null || rawTraceParentHeader.isBlank()) {
            return new ExtractResult(ExtractResult.Status.MISSING, null, "traceparent header is absent");
        }
        String value = rawTraceParentHeader.trim();
        String reason = validate(value);
        if (reason != null) {
            return new ExtractResult(ExtractResult.Status.INVALID, null, reason);
        }
        String[] parts = value.split("-");
        String traceId = parts[1];
        String parentSpanId = parts[2];
        return new ExtractResult(ExtractResult.Status.EXTRACTED,
                TraceContext.extracted(traceId, parentSpanId), "valid w3c traceparent");
    }

    @Override
    public TraceContext generate() {
        return TraceContext.generated();
    }

    @Override
    public String formatW3CTraceParent(TraceContext context) {
        if (context == null) {
            return null;
        }
        return VERSION + "-" + context.traceId() + "-" + context.spanId() + "-" + DEFAULT_FLAGS;
    }

    /**
     * 校验 traceparent；返回 null 表示合法，否则返回不合法原因。
     */
    private static String validate(String value) {
        String[] parts = value.split("-", -1);
        if (parts.length != 4) {
            return "traceparent must contain 4 dash-separated fields, actual=" + parts.length;
        }
        String version = parts[0];
        String traceId = parts[1];
        String spanId = parts[2];
        String flags = parts[3];

        if (version.length() != 2 || !isLowerHex(version)) {
            return "invalid version field: " + version;
        }
        if ("ff".equals(version)) {
            return "version 0xff is forbidden: " + version;
        }
        if ("00".equals(version) && value.length() != 55) {
            return "version 00 requires exactly 55 characters, actual length=" + value.length();
        }
        if (traceId.length() != 32 || !isLowerHex(traceId)) {
            return "invalid traceId field: " + traceId;
        }
        if (allZeros(traceId)) {
            return "traceId must not be all zeros";
        }
        if (spanId.length() != 16 || !isLowerHex(spanId)) {
            return "invalid spanId field: " + spanId;
        }
        if (allZeros(spanId)) {
            return "spanId must not be all zeros";
        }
        if (flags.length() != 2 || !isLowerHex(flags)) {
            return "invalid trace-flags field: " + flags;
        }
        return null;
    }

    private static boolean isLowerHex(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.equals(s)) {
            return false;
        }
        try {
            HexFormat.of().parseHex(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean allZeros(String hex) {
        for (int i = 0; i < hex.length(); i++) {
            if (hex.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }
}
