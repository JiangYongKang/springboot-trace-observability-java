package com.github.highcumontoa.springboottraceobservabilityjava.tests;

import com.github.highcumontoa.springboottraceobservabilityjava.observability.MetricNameNormalizer;
import fixture.WeirdThirdPartyException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 指标归一化：任意原始路径/异常都必须折叠成有界的标签集合。
 */
class MetricNameNormalizerTest {

    private static final Logger log = LoggerFactory.getLogger(MetricNameNormalizerTest.class);

    private final MetricNameNormalizer normalizer = new MetricNameNormalizer(
            Set.of("java.", "org.springframework.",
                    "com.github.highcumontoa.springboottraceobservabilityjava."),
            Set.of("IllegalArgumentException", "IllegalStateException", "RuntimeException"));

    @Test
    void springTemplateFoldsPathVariables() {
        String uri = normalizer.normalizePath("/users/12345/orders/abc", "/users/{id}/orders/{oid}");
        log.info("[input] raw=/users/12345/orders/abc pattern=/users/{{id}}/orders/{{oid}} -> [normalized] {}", uri);
        assertEquals("/users/{id}/orders/{oid}", uri);

        // 不同的具体 ID 必须得到同一个标签
        assertEquals(normalizer.normalizePath("/users/1", "/users/{id}"),
                normalizer.normalizePath("/users/999999", "/users/{id}"));
    }

    @Test
    void rawPathsWithoutTemplateCollapseToUnmatchedConstant() {
        // 判定依据：没有 best-matching-pattern 的原始地址不可信，保留任何字面量都会让基数无上限
        assertEquals("unmatched", normalizer.normalizePath("/users/12345", null));
        assertEquals("unmatched", normalizer.normalizePath(
                "/orders/3fa85f64-5717-4562-b3fc-2c963f66afa6", null));
        assertEquals("/", normalizer.normalizePath("/", null));
        log.info("[input] /users/12345 without template -> [normalized] {}",
                normalizer.normalizePath("/users/12345", null));
    }

    @Test
    void arbitraryAndRandomUnmatchedPathsProduceBoundedSet() {
        Set<String> normalized = new HashSet<>();
        String[] rawPaths = {
                "/a/1", "/a/2", "/a/3",
                "/users/" + "1".repeat(40),
                "/x/!@#$",
                "/x/" + "b".repeat(200),
                "/matrix;a=b",
                "/s1/s2/s3/s4/s5/s6/s7/s8/s9/s10/s11"};
        for (String p : rawPaths) {
            normalized.add(normalizer.normalizePath(p, null));
        }
        for (long i = 0; i < 1000; i++) {
            normalized.add(normalizer.normalizePath(
                    "/random-" + i + "/tail-" + (i * 31L), null));
        }
        log.info("[basis] 1008 distinct raw paths without template -> {} distinct uri tags: {}",
                normalized.size(), normalized);
        // 无模板路径只有一个有界常量
        assertEquals(Set.of("unmatched"), normalized);
    }

    @Test
    void untrustedTemplateIsRejectedAndCollapsed() {
        // 含非法字符的“模板”同样不能进入标签
        String n = normalizer.normalizePath("/x", "/users/{id}/../../etc/passwd");
        log.info("[input] malicious template -> [normalized] {}", n);
        assertEquals("unmatched", n);
    }

    @Test
    void resultValuesAreBounded() {
        assertEquals("SUCCESS", normalizer.resultOf(200, null));
        assertEquals("CLIENT_ERROR", normalizer.resultOf(404, null));
        assertEquals("SERVER_ERROR", normalizer.resultOf(503, null));
        assertEquals("ERROR", normalizer.resultOf(200, new RuntimeException()));
    }

    @Test
    void exceptionMessageNeverAppearsAndUnknownTypeIsCollapsed() {
        String randomMessage = "unexpected payload " + Math.random();
        String known = normalizer.normalizeException(new IllegalStateException(randomMessage));
        log.info("[input] IllegalStateException msg='{}' -> [tag] {}", randomMessage, known);
        assertEquals("IllegalStateException", known);

        String collapsed = normalizer.normalizeException(new WeirdThirdPartyException(randomMessage));
        log.info("[input] fixture.WeirdThirdPartyException (non-whitelisted package) -> [tag] {}",
                collapsed);
        assertEquals("OtherException", collapsed);
        assertEquals("None", normalizer.normalizeException(null));
    }
}
