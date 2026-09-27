package com.github.highcumontoa.springboottraceobservabilityjava.observability.trace;

/**
 * 上游链路头的解析结果。状态机显式区分：合法延续 / 缺失 / 非法，
 * 以便在日志、指标和处置策略上区分对待。
 */
public record ExtractResult(Status status, TraceContext context, String reason) {

    public enum Status {
        EXTRACTED,
        MISSING,
        INVALID
    }
}
