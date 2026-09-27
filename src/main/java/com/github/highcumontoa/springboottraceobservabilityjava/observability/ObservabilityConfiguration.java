package com.github.highcumontoa.springboottraceobservabilityjava.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 可观测性组件的 Spring 装配。
 *
 * <p>通过 {@code observability.enabled=false} 可整体关闭；默认开启。
 * 过滤器顺序：链路过滤器最先执行，保证指标过滤器记录时上下文已就绪。
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(ObservabilityProperties.class)
@ConditionalOnProperty(prefix = "observability", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ObservabilityConfiguration {

    /** 无外部指标后端时使用的内存注册表；classpath 上有 CompositeMeterRegistry 等时可直接替换。 */
    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    public MetricNameNormalizer metricNameNormalizer(ObservabilityProperties properties) {
        return new MetricNameNormalizer(
                java.util.Set.copyOf(properties.getAllowedExceptionPrefixes()),
                java.util.Set.copyOf(properties.getAllowedExceptionNames()));
    }

    @Bean
    public TraceHeaderParser traceHeaderParser() {
        return new TraceHeaderParser();
    }

    @Bean
    public TraceTaskDecorator traceTaskDecorator() {
        return new TraceTaskDecorator();
    }

    @Bean
    public FilterRegistrationBean<TraceServletFilter> traceFilterRegistration(
            TraceHeaderParser parser) {
        FilterRegistrationBean<TraceServletFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TraceServletFilter(parser));
        registration.addUrlPatterns("/*");
        registration.setName("traceServletFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RequestMetricsFilter> requestMetricsFilterRegistration(
            MeterRegistry meterRegistry, MetricNameNormalizer normalizer) {
        FilterRegistrationBean<RequestMetricsFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestMetricsFilter(meterRegistry, normalizer));
        registration.addUrlPatterns("/*");
        registration.setName("requestMetricsFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /** 演示用异步线程池：经 {@link TraceTaskDecorator} 传播并清理链路上下文。 */
    @Bean("obsTaskExecutor")
    public Executor obsTaskExecutor(ObservabilityProperties properties,
                                    TraceTaskDecorator traceTaskDecorator) {
        ObservabilityProperties.Async cfg = properties.getAsync();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(cfg.getCorePoolSize());
        executor.setMaxPoolSize(cfg.getMaxPoolSize());
        executor.setQueueCapacity(cfg.getQueueCapacity());
        executor.setThreadNamePrefix(cfg.getThreadNamePrefix());
        executor.setTaskDecorator(traceTaskDecorator);
        executor.initialize();
        return executor;
    }
}
