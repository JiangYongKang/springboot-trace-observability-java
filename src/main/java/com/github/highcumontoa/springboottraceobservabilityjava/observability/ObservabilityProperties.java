package com.github.highcumontoa.springboottraceobservabilityjava.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * observability.* 配置项。
 *
 * <pre>
 * observability.enabled                      总开关，默认 true
 * observability.async.core-pool-size         演示用异步线程池核心线程数，默认 2
 * observability.async.max-pool-size          最大线程数，默认 4
 * observability.async.queue-capacity         队列容量，默认 64
 * observability.async.thread-name-prefix     线程名前缀，默认 obs-async-
 * observability.allowed-exception-prefixes   允许进入 exception 标签的类名前缀白名单
 * observability.allowed-exception-names      允许进入 exception 标签的简单类名白名单
 * </pre>
 */
@ConfigurationProperties(prefix = "observability")
public class ObservabilityProperties {

    private boolean enabled = true;
    private Async async = new Async();
    /** 允许出现在指标 exception 标签中的类名前缀（包名）白名单。 */
    private List<String> allowedExceptionPrefixes = new ArrayList<>(List.of(
            "java.", "jakarta.", "org.springframework.",
            "com.github.highcumontoa.springboottraceobservabilityjava."));
    /** 允许出现在指标 exception 标签中的异常简单类名白名单。 */
    private List<String> allowedExceptionNames = new ArrayList<>(List.of(
            "IllegalArgumentException", "IllegalStateException",
            "RuntimeException", "IOException", "ServletException"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Async getAsync() {
        return async;
    }

    public void setAsync(Async async) {
        this.async = async;
    }

    public List<String> getAllowedExceptionPrefixes() {
        return allowedExceptionPrefixes;
    }

    public void setAllowedExceptionPrefixes(List<String> allowedExceptionPrefixes) {
        this.allowedExceptionPrefixes = allowedExceptionPrefixes;
    }

    public List<String> getAllowedExceptionNames() {
        return allowedExceptionNames;
    }

    public void setAllowedExceptionNames(List<String> allowedExceptionNames) {
        this.allowedExceptionNames = allowedExceptionNames;
    }

    public static class Async {
        private int corePoolSize = 2;
        private int maxPoolSize = 4;
        private int queueCapacity = 64;
        private String threadNamePrefix = "obs-async-";

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }
    }
}
