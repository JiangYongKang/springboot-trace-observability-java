package com.github.highcumontoa.springboottraceobservabilityjava.tests;

import com.github.highcumontoa.springboottraceobservabilityjava.observability.config.ObservabilityAutoConfiguration;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.metric.MetricLabels;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.metric.MetricSnapshot;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.metric.RequestMetricRecorder;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.web.DemoController;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.web.MetricsController;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端切片：合法头延续、非法头另起、路径参数归并、500/404 结果分类。
 */
@WebMvcTest(controllers = {DemoController.class, MetricsController.class})
@Import({ObservabilityAutoConfiguration.class, WebMvcTestSupport.class})
class TraceAndMetricsWebMvcTest {

    private static final Logger log = LoggerFactory.getLogger(TraceAndMetricsWebMvcTest.class);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RequestMetricRecorder recorder;

    @Test
    void validHeaderContinuesTraceAndMetricsAreRecorded() throws Exception {
        String valid = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        mockMvc.perform(get("/api/ping").header("traceparent", valid))
                .andExpect(status().isOk())
                .andExpect(header().string("traceparent", startsWith("00-0af7651916cd43dd8448eb211c80319c-")))
                .andExpect(content().string(containsString("0af7651916cd43dd8448eb211c80319c")));

        mockMvc.perform(get("/api/users/100")).andExpect(status().isOk());
        mockMvc.perform(get("/api/users/200")).andExpect(status().isOk());
        // MockMvc 不经过容器错误页，业务异常会直接抛出；指标在 afterCompletion(ex!=null)
        // 已按 SERVER_ERROR 记录，这里接住异常继续断言。
        try {
            mockMvc.perform(get("/api/boom"));
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("intentional business failure")
                            || expected.getCause() instanceof IllegalStateException,
                    "应捕获业务异常以便继续校验指标: " + expected);
        }
        try {
            mockMvc.perform(get("/no/such/thing/9"));
        } catch (Exception ignored) {
            // 无匹配路由在不同 MockMvc 配置下可能抛异常或返回 404，均不影响指标断言
        }

        Map<MetricLabels, MetricSnapshot> snap = recorder.snapshot();
        long ping = count(snap, "/api/ping", "SUCCESS", "EXTRACTED");
        long users = snap.entrySet().stream()
                .filter(e -> e.getKey().route().equals("/api/users/{id}"))
                .mapToLong(e -> e.getValue().count()).findFirst().orElse(-1);
        boolean boomServerError = snap.keySet().stream()
                .anyMatch(l -> l.route().equals("/api/boom") && "SERVER_ERROR".equals(l.result()));
        boolean missingClientError = snap.keySet().stream()
                .anyMatch(l -> "CLIENT_ERROR".equals(l.result()));

        log.info("[端到端] ping(EXTRACTED)={} users合并={} boomSERVER_ERROR={} 存在CLIENT_ERROR={} 序列={}",
                ping, users, boomServerError, missingClientError, snap.keySet());

        assertEquals(1, ping, "合法头请求应记一次 EXTRACTED 成功指标");
        assertEquals(2, users, "不同路径 id 必须归并为同一路由标签");
        assertTrue(boomServerError, "业务异常必须归类 SERVER_ERROR");
        assertTrue(missingClientError, "无匹配路由必须归类 CLIENT_ERROR");
        assertTrue(snap.keySet().stream().noneMatch(l -> l.route().equals("/error")),
                "不得出现 /error 二次分发脏指标");
    }

    @Test
    void invalidHeaderStartsNewTrace() throws Exception {
        mockMvc.perform(get("/api/ping").header("traceparent", "garbage-value"))
                .andExpect(status().isOk())
                .andExpect(header().string("traceparent", not(containsString("garbage"))));
        log.info("[端到端] 非法头不延续，响应头为服务端新生成的 traceparent，业务仍成功");
    }

    private static long count(Map<MetricLabels, MetricSnapshot> snap, String route, String result, String origin) {
        return snap.entrySet().stream()
                .filter(e -> e.getKey().route().equals(route)
                        && result.equals(e.getKey().result())
                        && origin.equals(e.getKey().origin()))
                .mapToLong(e -> e.getValue().count()).findFirst().orElse(-1);
    }
}
