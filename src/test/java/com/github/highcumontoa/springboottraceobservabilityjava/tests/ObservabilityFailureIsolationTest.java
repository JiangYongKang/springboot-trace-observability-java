package com.github.highcumontoa.springboottraceobservabilityjava.tests;

import com.github.highcumontoa.springboottraceobservabilityjava.observability.MetricNameNormalizer;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.RequestMetricsFilter;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.SelfDiagnostics;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 可观测性组件内部异常：必须被隔离（业务请求照常成功），又必须留下可查依据（计数 + WARN 日志）。
 */
class ObservabilityFailureIsolationTest {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityFailureIsolationTest.class);

    @BeforeEach
    void reset() {
        SelfDiagnostics.resetErrorCounts();
    }

    @AfterEach
    void clean() {
        SelfDiagnostics.resetErrorCounts();
    }

    @Test
    void metricsRegistryFailureDoesNotBreakBusinessAndIsCounted() throws Exception {
        // 模拟 MeterRegistry 在获取/注册 Timer 时内部故障
        MeterRegistry brokenRegistry = new SimpleMeterRegistry() {
            @Override
            protected Timer newTimer(Meter.Id id, DistributionStatisticConfig config,
                                     PauseDetector pauseDetector) {
                throw new IllegalStateException("registry backend down");
            }
        };

        RequestMetricsFilter filter =
                new RequestMetricsFilter(brokenRegistry,
                        new MetricNameNormalizer(java.util.Set.of(), java.util.Set.of()));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ping");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean businessRan = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> {
            businessRan.set(true);
            ((MockHttpServletResponse) res).setStatus(200);
        };

        // 连续两次请求，证明故障是“可恢复地被隔离”，不是一次性吞掉
        filter.doFilter(request, response, chain);
        filter.doFilter(request, response, chain);

        long errors = SelfDiagnostics.errorCount("requestMetrics.record");
        log.info("[input] MeterRegistry.timer throws on every request -> [basis] businessRan={}, "
                + "isolatedErrors={}, responseStatus={}", businessRan.get(), errors, response.getStatus());
        assertTrue(businessRan.get(), "business chain must still execute");
        assertEquals(200, response.getStatus(), "business response must be intact");
        assertEquals(2, errors, "every failure must be recorded, errors must not be swallowed");
    }

    @Test
    void traceHeaderParserFailureIsIsolatedAndRequestStillServed() throws Exception {
        // 解析器抛 Error 也要被兜住（SelfDiagnostics 捕获 Throwable）
        com.github.highcumontoa.springboottraceobservabilityjava.observability.TraceHeaderParser brokenParser =
                new com.github.highcumontoa.springboottraceobservabilityjava.observability.TraceHeaderParser() {
                    @Override
                    public com.github.highcumontoa.springboottraceobservabilityjava.observability.TraceContext parse(
                            String t, String s2, String p) {
                        throw new InternalError("parser exploded");
                    }
                };

        var filter = new com.github.highcumontoa.springboottraceobservabilityjava.observability.TraceServletFilter(brokenParser);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ping");
        request.addHeader("X-Trace-Id", "abcdef0123456789abcdef0123456789");
        request.addHeader("X-Span-Id", "0011223344556677");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean businessRan = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> {
            businessRan.set(true);
            ((MockHttpServletResponse) res).setStatus(204);
        };

        filter.doFilter(request, response, chain);
        long errors = SelfDiagnostics.errorCount("trace.parseHeaders");
        log.info("[input] parser throws InternalError -> [basis] businessRan={}, isolatedErrors={}",
                businessRan.get(), errors);
        assertTrue(businessRan.get());
        assertEquals(204, response.getStatus());
        assertEquals(1, errors);
    }

    @Test
    void runSafelyReturnsFallbackAndNeverThrows() {
        String result = SelfDiagnostics.supplySafely("unit.failure", "fallback", () -> {
            throw new RuntimeException("boom");
        });
        SelfDiagnostics.runSafely("unit.failure", () -> {
            throw new RuntimeException("boom2");
        });
        log.info("[input] two failing observability operations -> [basis] result={}, errorCount={}",
                result, SelfDiagnostics.errorCount("unit.failure"));
        assertEquals("fallback", result);
        assertEquals(2, SelfDiagnostics.errorCount("unit.failure"));
    }

    @Test
    void happyPathRecordsNoSelfErrorsAndMetricIsWritten() {
        MeterRegistry registry = new SimpleMeterRegistry();
        RequestMetricsFilter filter = new RequestMetricsFilter(registry,
                new MetricNameNormalizer(java.util.Set.of(), java.util.Set.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ping");
        request.setAttribute(
                org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/ping");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> ((MockHttpServletResponse) res).setStatus(200);
        try {
            filter.doFilter(request, response, chain);
        } catch (Exception e) {
            fail(e);
        }
        Timer timer = registry.find(RequestMetricsFilter.METRIC_NAME)
                .tags("uri", "/ping", "result", "SUCCESS").timer();
        log.info("[input] healthy request -> [basis] timerCount={}, selfErrors={}",
                timer == null ? null : timer.count(),
                SelfDiagnostics.errorCount("requestMetrics.record"));
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertEquals(0, SelfDiagnostics.errorCount("requestMetrics.record"));
    }
}
