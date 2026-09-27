package com.github.highcumontoa.springboottraceobservabilityjava.observability.config;

import com.github.highcumontoa.springboottraceobservabilityjava.observability.metric.DefaultRequestMetricRecorder;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.metric.MetricsFilter;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.metric.RequestMetricRecorder;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.metric.RouteNormalizer;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.DefaultTracePropagator;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.DecoratedExecutorService;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.TraceFilter;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.TracePropagator;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.trace.TracingTaskDecorator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskDecorator;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可观测性组件自动装配。
 *
 * <p>所有关键扩展点（{@link TracePropagator}、{@link RequestMetricRecorder}）
 * 都有 {@code @ConditionalOnMissingBean} 默认实现，测试或接入方可注入自定义/故障实现。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties({TraceProperties.class, MetricsProperties.class})
public class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TracePropagator tracePropagator() {
        return new DefaultTracePropagator();
    }

    @Bean
    public TracingTaskDecorator tracingTaskDecorator() {
        return new TracingTaskDecorator();
    }

    /**
     * 业务示例用线程池：经 {@link TracingTaskDecorator} 装饰，
     * 提交到池中的任务自动携带提交方的链路上下文与 MDC，执行完即还原。
     */
    @Bean(name = "observabilityDemoExecutor", destroyMethod = "shutdown")
    public ExecutorService observabilityDemoExecutor(TracingTaskDecorator decorator) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "obs-demo-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2, factory);
        return new DecoratedExecutorService(pool, decorator);
    }

    @Bean
    @ConditionalOnMissingBean
    public RequestMetricRecorder requestMetricRecorder(MetricsProperties properties) {
        return new DefaultRequestMetricRecorder(properties.getMaxSeries());
    }

    @Bean
    public RouteNormalizer routeNormalizer(MetricsProperties properties) {
        return new RouteNormalizer(properties.getRouteMaxLength());
    }

    @Bean
    public FilterRegistrationBean<TraceFilter> traceFilterRegistration(
            TracePropagator propagator, TraceProperties properties) {
        FilterRegistrationBean<TraceFilter> bean = new FilterRegistrationBean<>(
                new TraceFilter(propagator, properties));
        bean.setOrder(TraceFilter.ORDER_HINT);
        // 只在初始 REQUEST 分发执行；500/404 后 FORWARD 到 /error 不再重入，
        // 避免重复设置/清理链路上下文与重复计数。
        bean.setDispatcherTypes(java.util.EnumSet.of(jakarta.servlet.DispatcherType.REQUEST));
        return bean;
    }

    @Bean
    public FilterRegistrationBean<MetricsFilter> metricsFilterRegistration(
            RequestMetricRecorder recorder, RouteNormalizer normalizer) {
        FilterRegistrationBean<MetricsFilter> bean = new FilterRegistrationBean<>(
                new MetricsFilter(recorder, normalizer));
        bean.setOrder(MetricsFilter.ORDER_HINT);
        bean.setDispatcherTypes(java.util.EnumSet.of(jakarta.servlet.DispatcherType.REQUEST));
        return bean;
    }
}
