package com.github.highcumontoa.springboottraceobservabilityjava.observability.config;

import com.github.highcumontoa.springboottraceobservabilityjava.observability.metric.MetricsHandlerInterceptor;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.metric.RequestMetricRecorder;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.metric.RouteNormalizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册请求指标拦截器，使其运行在 DispatcherServlet 内、能读到最终响应状态。
 */
@Configuration
public class WebMvcObservabilityConfiguration implements WebMvcConfigurer {

    private final RequestMetricRecorder recorder;
    private final RouteNormalizer normalizer;

    public WebMvcObservabilityConfiguration(RequestMetricRecorder recorder, RouteNormalizer normalizer) {
        this.recorder = recorder;
        this.normalizer = normalizer;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new MetricsHandlerInterceptor(recorder, normalizer));
    }
}
