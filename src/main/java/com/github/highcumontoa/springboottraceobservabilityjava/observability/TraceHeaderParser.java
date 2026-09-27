package com.github.highcumontoa.springboottraceobservabilityjava.observability;

/**
 * 解析并判定上游传入的链路头。
 *
 * <p>处置规则：
 * <ul>
 *     <li>三个头全部缺失/空白：另起一条新链路，{@link TraceDecision#GENERATED_MISSING}；</li>
 *     <li>traceId、spanId 均合法，parentSpanId 缺失或合法：延续上游链路，
 *         {@link TraceDecision#INHERITED}（标识统一转为小写）；</li>
 *     <li>只要出现“字段部分缺失、格式非法、全零”等任何一种情况，整个上游上下文作废，
 *         另起一条新链路，{@link TraceDecision#GENERATED_INVALID}，并在 rejectReason 中说明依据。</li>
 * </ul>
 *
 * <p>部分缺失（例如有 traceId 没有 spanId）按非法处理，不会只继承半截链路。
 */
public class TraceHeaderParser {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String SPAN_ID_HEADER = "X-Span-Id";
    public static final String PARENT_SPAN_ID_HEADER = "X-Parent-Span-Id";

    /**
     * @param incomingTraceId      上游 traceId 头原始值
     * @param incomingSpanId       上游 spanId 头原始值（上游跨度标识，作为本请求的 parentSpanId）
     * @param incomingParentSpanId 上游 parentSpanId 头原始值（可选）
     * @return 本次请求的链路上下文
     */
    public TraceContext parse(String incomingTraceId,
                              String incomingSpanId,
                              String incomingParentSpanId) {
        String traceId = trimToNull(incomingTraceId);
        String spanId = trimToNull(incomingSpanId);
        String parentSpanId = trimToNull(incomingParentSpanId);

        // 三个头全部缺失：新起链路
        if (traceId == null && spanId == null && parentSpanId == null) {
            return new TraceContext(TraceIds.newTraceId(), TraceIds.newSpanId(),
                    null, TraceDecision.GENERATED_MISSING, null);
        }

        // 只要有一个头出现，其余字段就必须完整合法；否则整个上下文作废
        String reason = validate(traceId, spanId, parentSpanId);
        if (reason != null) {
            return new TraceContext(TraceIds.newTraceId(), TraceIds.newSpanId(),
                    null, TraceDecision.GENERATED_INVALID, reason);
        }

        // 延续上游：上游的 spanId 成为本次请求的 parentSpanId，本次生成新的 spanId
        return new TraceContext(traceId.toLowerCase(), TraceIds.newSpanId(),
                spanId.toLowerCase(), TraceDecision.INHERITED, null);
    }

    private String validate(String traceId, String spanId, String parentSpanId) {
        if (traceId == null) {
            return "traceId header is missing while spanId/parentSpanId is present";
        }
        if (!TraceIds.isValidTraceId(traceId)) {
            return "illegal traceId: " + quote(traceId);
        }
        if (spanId == null) {
            return "spanId header is missing while traceId/parentSpanId is present";
        }
        if (!TraceIds.isValidSpanId(spanId)) {
            return "illegal spanId: " + quote(spanId);
        }
        if (parentSpanId != null && !TraceIds.isValidSpanId(parentSpanId)) {
            return "illegal parentSpanId: " + quote(parentSpanId);
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String quote(String value) {
        // 拒绝原因只用于日志，截断避免超长内容污染日志
        String shown = value.length() > 64 ? value.substring(0, 64) + "...(truncated)" : value;
        return "'" + shown.replace("'", "?") + "'";
    }
}
