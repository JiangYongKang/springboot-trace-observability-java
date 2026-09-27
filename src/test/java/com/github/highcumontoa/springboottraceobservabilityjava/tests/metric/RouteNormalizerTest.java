package com.github.highcumontoa.springboottraceobservabilityjava.tests.metric;

import com.github.highcumontoa.springboottraceobservabilityjava.observability.metric.RouteNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖路由归一化规则：
 * URI 模板优先；无模板时数字/UUID/长 hex 段替换为 {id}；
 * 超长截断；查询串天然不进入；空路径兜底 UNMAPPED。
 * 每个用例打印输入与归一化结果作为判定依据。
 */
class RouteNormalizerTest {

    private static final Logger log = LoggerFactory.getLogger(RouteNormalizerTest.class);
    private final RouteNormalizer normalizer = new RouteNormalizer(32);

    private String norm(String method, String uri, String pattern) {
        String out = normalizer.normalize(method, uri, pattern);
        log.info("[归一化] method={} uri={} pattern={} -> route={}", method, uri, pattern, out);
        return out;
    }

    @Test
    @DisplayName("Spring URI 模板存在时优先使用模板")
    void prefersMatchingPattern() {
        assertEquals("/api/users/{id}", norm("GET", "/api/users/123", "/api/users/{id}"));
    }

    @Test
    @DisplayName("无模板：数字段归一为 {id}")
    void normalizesNumericSegments() {
        assertEquals("/api/users/{id}", norm("GET", "/api/users/123", null));
        assertEquals("/api/users/{id}/orders/{id}",
                norm("GET", "/api/users/123/orders/456", null));
    }

    @Test
    @DisplayName("无模板：UUID 段与长 hex 段归一为 {id}，不同值归并到同一路由")
    void normalizesUuidAndHexSegments() {
        String uuid1 = "550e8400-e29b-41d4-a716-446655440000";
        String uuid2 = "6ba7b810-9dad-11d1-80b4-00c04fd430c8";
        assertEquals("/api/keys/{id}", norm("GET", "/api/keys/" + uuid1, null));
        assertEquals("/api/keys/{id}", norm("GET", "/api/keys/" + uuid2, null));
        assertEquals("/api/keys/{id}",
                norm("GET", "/api/keys/0123456789abcdef0123456789abcdef", null));
    }

    @Test
    @DisplayName("无模板：非 id 段保留安全字符，危险字符替换，避免任意基数")
    void sanitizesUnsafeSegments() {
        assertEquals("/api/a_b", norm("GET", "/api/a b", null));
    }

    @Test
    @DisplayName("超长路由按 maxLength 截断并加省略标记，长度有界")
    void truncatesLongRoute() {
        String longPath = "/api/" + "a".repeat(200);
        String out = norm("GET", longPath, null);
        assertEquals(32, out.length(), "归一化标签长度必须受 maxLength 约束");
        assertTrue(out.endsWith("..."));
    }

    @Test
    @DisplayName("空/缺失路径兜底为 UNMAPPED")
    void unmappedForBlankPath() {
        assertEquals(RouteNormalizer.UNMAPPED, norm("GET", "", null));
        assertEquals(RouteNormalizer.UNMAPPED, norm("GET", null, null));
    }

    @Test
    @DisplayName("根路径归一为单斜杠；不同查询串不影响标签（调用方传入的 uri 不含 query）")
    void rootPathAndQueryExcluded() {
        assertEquals("/", norm("GET", "/", null));
        // 规范化的责任边界：requestURI 本身不含查询串；这里直接验证相同前缀不同“值”不产生新路由
        assertEquals("/api/items/{id}", norm("GET", "/api/items/1?foo=bar", "/api/items/{id}"));
    }
}
