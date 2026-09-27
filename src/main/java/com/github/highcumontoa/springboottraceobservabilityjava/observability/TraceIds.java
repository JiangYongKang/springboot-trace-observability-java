package com.github.highcumontoa.springboottraceobservabilityjava.observability;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * 链路/跨度标识的生成与合法性校验。
 *
 * <p>标识采用十六进制字符串，生成时统一小写：
 * <ul>
 *     <li>traceId：32 个十六进制字符（128 bit）</li>
 *     <li>spanId：16 个十六进制字符（64 bit）</li>
 * </ul>
 *
 * <p>校验规则（同时适用于上游传入值，大小写不敏感，通过后规范化为小写）：
 * <ul>
 *     <li>traceId 长度 16~32，spanId / parentSpanId 长度 8~16；</li>
 *     <li>只能包含十六进制字符；</li>
 *     <li>不允许全为 0。</li>
 * </ul>
 */
public final class TraceIds {

    /** traceId 合法长度区间（含端点）。 */
    public static final int TRACE_ID_MIN_LENGTH = 16;
    public static final int TRACE_ID_LENGTH = 32;
    /** spanId 合法长度区间（含端点）。 */
    public static final int SPAN_ID_MIN_LENGTH = 8;
    public static final int SPAN_ID_LENGTH = 16;

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("(?i)^[0-9a-f]{16,32}$");
    private static final Pattern SPAN_ID_PATTERN = Pattern.compile("(?i)^[0-9a-f]{8,16}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private TraceIds() {
    }

    /** 生成 32 位十六进制 traceId，保证非全零。 */
    public static String newTraceId() {
        return randomHex(TRACE_ID_LENGTH);
    }

    /** 生成 16 位十六进制 spanId，保证非全零。 */
    public static String newSpanId() {
        return randomHex(SPAN_ID_LENGTH);
    }

    private static String randomHex(int length) {
        char[] chars = new char[length];
        // 极小概率（2^-128 / 2^-64）会出现全零，循环保证不会
        boolean allZero;
        do {
            allZero = true;
            byte[] bytes = new byte[(length + 1) / 2];
            RANDOM.nextBytes(bytes);
            for (int i = 0; i < length; i++) {
                int v;
                if ((i & 1) == 0) {
                    v = (bytes[i / 2] >> 4) & 0x0F;
                } else {
                    v = bytes[i / 2] & 0x0F;
                }
                if (v != 0) {
                    allZero = false;
                }
                chars[i] = HEX[v];
            }
        } while (allZero);
        return new String(chars);
    }

    public static boolean isValidTraceId(String value) {
        return value != null
                && value.length() >= TRACE_ID_MIN_LENGTH
                && value.length() <= TRACE_ID_LENGTH
                && TRACE_ID_PATTERN.matcher(value).matches()
                && !isAllZero(value);
    }

    public static boolean isValidSpanId(String value) {
        return value != null
                && value.length() >= SPAN_ID_MIN_LENGTH
                && value.length() <= SPAN_ID_LENGTH
                && SPAN_ID_PATTERN.matcher(value).matches()
                && !isAllZero(value);
    }

    private static boolean isAllZero(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }
}
