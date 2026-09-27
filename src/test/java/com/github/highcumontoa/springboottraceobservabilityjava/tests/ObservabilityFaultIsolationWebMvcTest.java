package com.github.highcumontoa.springboottraceobservabilityjava.tests;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.config.ObservabilityAutoConfiguration;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.metric.MetricLabels;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.metric.MetricSnapshot;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.metric.RequestMetricRecorder;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.ExtractResult;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.TraceContext;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.TraceFilter;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.TracePropagator;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.web.DemoController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 可观测性组件内部异常隔离：
 * 传播器 extract/generate/format 全部抛异常、记录器 record 也抛异常时，
 * 业务接口必须仍然返回 200，且故障必须落到 observability.self 日志（不静默吞掉）。
 */
@WebMvcTest(controllers = DemoController.class)
@Import({ObservabilityAutoConfiguration.class, WebMvcTestSupport.class,
        ObservabilityFaultIsolationWebMvcTest.FaultyBeans.class})
class ObservabilityFaultIsolationWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    private ListAppender<ILoggingEvent> appender;
    private Logger selfLogger;

    @BeforeEach
    void attachLogCapture() {
        selfLogger = (Logger) LoggerFactory.getLogger(TraceFilter.OBS_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        selfLogger.addAppender(appender);
    }

    @AfterEach
    void detachLogCapture() {
        selfLogger.detachAppender(appender);
    }

    @Test
    void observabilityFailureShouldNotFailBusinessRequest() throws Exception {
        // 业务请求必须成功，即使所有可观测性扩展点都在抛异常
        mockMvc.perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("pong")));

        List<ILoggingEvent> events = appender.list;
        boolean hasTraceFault = events.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("trace propagation failed"));
        boolean hasHeaderFault = events.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("failed to write trace response header"));
        boolean hasMetricFault = events.stream().anyMatch(e ->
                e.getFormattedMessage().contains("failed to record request metric"));
        boolean traceFaultHasStack = events.stream()
                .filter(e -> e.getFormattedMessage().contains("trace propagation failed"))
                .anyMatch(e -> e.getThrowableProxy() != null);

        events.forEach(e -> System.out.println("[SELF-LOG] level=" + e.getLevel()
                + " msg=" + e.getFormattedMessage()
                + " hasStack=" + (e.getThrowableProxy() != null)));

        assertTrue(hasTraceFault, "传播器故障必须在 observability.self 留下 ERROR 日志，不能静默吞掉");
        assertTrue(traceFaultHasStack, "故障日志必须带异常堆栈，便于排查");
        assertTrue(hasHeaderFault, "响应头写入故障必须留痕");
        assertTrue(hasMetricFault, "指标记录器故障必须留痕");
    }

    /**
     * 装配持续抛异常的传播器与记录器（@ConditionalOnMissingBean 会让它们覆盖默认实现）。
     */
    @TestConfiguration
    static class FaultyBeans {

        @Bean("faultyTracePropagator")
        @Primary
        TracePropagator tracePropagator() {
            return new TracePropagator() {
                @Override
                public ExtractResult extract(String raw) {
                    throw new RuntimeException("simulated propagator extract failure");
                }

                @Override
                public TraceContext generate() {
                    throw new RuntimeException("simulated propagator generate failure");
                }

                @Override
                public String formatW3CTraceParent(TraceContext context) {
                    throw new RuntimeException("simulated format failure");
                }
            };
        }

        @Bean("faultyRequestMetricRecorder")
        @Primary
        RequestMetricRecorder requestMetricRecorder() {
            return new RequestMetricRecorder() {
                @Override
                public void record(MetricLabels labels, long durationNanos) {
                    throw new RuntimeException("simulated recorder failure");
                }

                @Override
                public Map<MetricLabels, MetricSnapshot> snapshot() {
                    return Map.of();
                }

                @Override
                public int maxSeries() {
                    return 1;
                }
            };
        }
    }
}
