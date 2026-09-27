package com.github.highcumontoa.springboottraceobservabilityjava.observability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 链路相关配置项。
 */
@ConfigurationProperties(prefix = "observability.trace")
public class TraceProperties {

    private String headerName = "traceparent";
    private boolean responseHeaderEnabled = true;

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public boolean isResponseHeaderEnabled() {
        return responseHeaderEnabled;
    }

    public void setResponseHeaderEnabled(boolean responseHeaderEnabled) {
        this.responseHeaderEnabled = responseHeaderEnabled;
    }
}
