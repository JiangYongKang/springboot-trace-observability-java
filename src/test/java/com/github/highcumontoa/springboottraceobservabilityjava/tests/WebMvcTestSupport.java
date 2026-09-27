package com.github.highcumontoa.springboottraceobservabilityjava.tests;

import com.github.highcumontoa.springboottraceobservabilityjava.observability.config.MetricsProperties;
import com.github.highcumontoa.springboottraceobservabilityjava.observability.config.TraceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;

/**
 * 切片测试共享：显式启用配置属性绑定
 * （@WebMvcTest 默认不启用 @ConfigurationProperties）。
 * 用 @EnableConfigurationProperties 注册，避免与自动装配中的同名 Bean 冲突。
 */
@TestConfiguration
@EnableConfigurationProperties({TraceProperties.class, MetricsProperties.class})
public class WebMvcTestSupport {
}
