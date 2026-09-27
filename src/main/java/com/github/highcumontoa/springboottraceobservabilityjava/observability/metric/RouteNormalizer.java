package com.github.highcumontoa.springboottraceobservabilityjava.observability.metric;

import java.util.regex.Pattern;

/**
 * 把请求归一化为有界的路由标签。
 *
 * <p>优先级：</p>
 * <ol>
 *   <li>Spring MVC 已匹配的 URI 模板（{@code BEST_MATCHING_PATTERN_ATTRIBUTE}），
 *       例如 {@code /api/users/{id}}，本身就是归一化模板；</li>
 *   <li>拿不到模板（未进入 HandlerMapping，如 404）时，对原始 path 做逐段归一化：
 *       纯数字段、UUID 段替换为 {@code {id}}，避免每个资源 ID 产生一条序列；</li>
 *   <li>超长路径截断到 {@code maxLength}，查询串（{@code requestUri} 天然不含）永不入标签。</li>
 * </ol>
 *
 * <p>归一化不使用异常信息等任意输入，标签取值集合天然有界；
 * 即使归一化本身失败，过滤器会使用 {@link #UNMAPPED} 兜底。</p>
 */
public class RouteNormalizer {

    public static final String UNMAPPED = "UNMAPPED";

    private static final String ID_PLACEHOLDER = "{id}";
    private static final Pattern DIGITS = Pattern.compile("^\\d+$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    /** 长十六进制（如 32 位 trace 风格串）也视为资源 ID。 */
    private static final Pattern LONG_HEX = Pattern.compile("^[0-9a-fA-F]{16,64}$");

    private final int maxLength;

    public RouteNormalizer(int maxLength) {
        if (maxLength < 16) {
            throw new IllegalArgumentException("maxLength must be >= 16, actual=" + maxLength);
        }
        this.maxLength = maxLength;
    }

    /**
     * @param httpMethod HTTP 方法（仅用于兜底标签拼装）
     * @param requestUri 不含查询串的请求路径
     * @param pattern    Spring 匹配到的 URI 模板，可能为 null
     */
    public String normalize(String httpMethod, String requestUri, String pattern) {
        String base = pattern != null && !pattern.isBlank() ? pattern : segmentNormalize(requestUri);
        if (base == null || base.isBlank()) {
            return UNMAPPED;
        }
        return cap(base);
    }

    /**
     * 对没有 URI 模板的路径逐段归一化：识别不了的路径（如空）返回 null，由上层兜底。
     */
    private String segmentNormalize(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            return null;
        }
        String[] segments = requestUri.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            sb.append('/');
            if (isIdLike(segment)) {
                sb.append(ID_PLACEHOLDER);
            } else {
                // 段内只保留安全字符，其余替换，防止任意输入制造基数
                sb.append(sanitize(segment));
            }
        }
        if (sb.isEmpty()) {
            return "/";
        }
        return sb.toString();
    }

    private static boolean isIdLike(String segment) {
        return DIGITS.matcher(segment).matches()
                || UUID.matcher(segment).matches()
                || LONG_HEX.matcher(segment).matches();
    }

    private static String sanitize(String segment) {
        String cleaned = segment.replaceAll("[^A-Za-z0-9_.\\-~]", "_");
        return cleaned.isBlank() ? "_" : cleaned;
    }

    private String cap(String route) {
        if (route.length() <= maxLength) {
            return route;
        }
        return route.substring(0, maxLength - 3) + "...";
    }
}
