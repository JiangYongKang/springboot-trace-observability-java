package com.github.highcumontoa.springboottraceobservabilityjava.observability;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 指标标签归一化：保证指标基数（cardinality）严格有界。
 *
 * <p>归一化规则：
 * <ul>
 *     <li><b>接口标识 uri</b>：
 *     正常进入 Spring MVC 的请求一定带 best-matching-pattern（如 {@code /users/{id}}），
 *     它天然折叠路径变量，优先采用（仍做字符集与长度校验）；
 *     拿不到模板的请求（典型是未命中任何处理器的 404）原始路径不可信——
 *     即使只保留“字母段”，{@code /random-1}、{@code /random-2} 仍会无限膨胀，
 *     因此统一折叠为常量 {@code unmatched}，从根本上封顶。根路径保留为 {@code /}。</li>
 *     <li><b>处理结果 result</b>：只有 SUCCESS / CLIENT_ERROR / SERVER_ERROR / ERROR 四种。</li>
 *     <li><b>异常标识 exception</b>：异常消息绝不入指标；只取类简单名，
 *     且必须命中白名单包前缀或已知异常名集合，否则统一 {@code OtherException}。</li>
 * </ul>
 */
public class MetricNameNormalizer {

    public static final String OTHER_PATH = "other";
    /** 未命中 MVC 模板的请求（含绝大多数 404）统一使用的有界常量。 */
    public static final String UNMATCHED_PATH = "unmatched";
    public static final String OTHER_EXCEPTION = "OtherException";
    public static final String NO_EXCEPTION = "None";
    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_CLIENT_ERROR = "CLIENT_ERROR";
    public static final String RESULT_SERVER_ERROR = "SERVER_ERROR";
    public static final String RESULT_ERROR = "ERROR";

    private static final Pattern SAFE_TEMPLATE =
            Pattern.compile("[/A-Za-z0-9_.{}\\-]{1,128}");
    private static final Pattern SAFE_CLASS_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    private final Set<String> allowedExceptionPrefixes;
    private final Set<String> allowedExceptionNames;

    public MetricNameNormalizer(Set<String> allowedExceptionPrefixes,
                                Set<String> allowedExceptionNames) {
        this.allowedExceptionPrefixes = Set.copyOf(allowedExceptionPrefixes);
        this.allowedExceptionNames = Set.copyOf(allowedExceptionNames);
    }

    /**
     * 归一化 HTTP 接口标识。
     *
     * @param path                原始请求路径
     * @param bestMatchingPattern Spring MVC 匹配到的路径模板，可能为 {@code null}
     */
    public String normalizePath(String path, String bestMatchingPattern) {
        String template = sanitizeTemplate(bestMatchingPattern);
        if (template != null) {
            return template;
        }
        // 无模板：原始路径不可信，统一有界常量（根路径除外）
        if (path != null && "/".equals(path.trim())) {
            return "/";
        }
        return UNMATCHED_PATH;
    }

    /**
     * 归一化异常标识：异常消息绝不进入指标，非白名单异常折叠为 {@link #OTHER_EXCEPTION}。
     */
    public String normalizeException(Throwable throwable) {
        if (throwable == null) {
            return NO_EXCEPTION;
        }
        Class<?> type = throwable.getClass();
        String simpleName = type.getSimpleName();
        boolean whitelisted = allowedExceptionNames.contains(simpleName)
                || allowedExceptionPrefixes.stream().anyMatch(type.getName()::startsWith);
        if (!whitelisted || !SAFE_CLASS_NAME.matcher(simpleName).matches()) {
            return OTHER_EXCEPTION;
        }
        return simpleName;
    }

    /**
     * 处理结果分类，取值有界。
     *
     * @param status HTTP 响应状态码
     * @param error  穿透过滤器链、未被处理掉的异常，可能为 {@code null}
     */
    public String resultOf(int status, Throwable error) {
        if (error != null) {
            return RESULT_ERROR;
        }
        if (status < 400) {
            return RESULT_SUCCESS;
        }
        if (status < 500) {
            return RESULT_CLIENT_ERROR;
        }
        return RESULT_SERVER_ERROR;
    }

    private String sanitizeTemplate(String template) {
        if (template == null || template.isBlank()) {
            return null;
        }
        String t = template.trim();
        if (!SAFE_TEMPLATE.matcher(t).matches()) {
            return null;
        }
        // 拒绝路径穿越段，防止构造出绕过字符集校验的“模板”
        for (String segment : t.split("/")) {
            if ("..".equals(segment)) {
                return null;
            }
        }
        return t;
    }
}
