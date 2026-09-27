package com.github.highcumontoa.springboottraceobservabilityjava.observability.trace;

import com.github.highcumontoa.springboottraceobservabilityjava.observability.config.TraceProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求入口过滤器：
 * <ol>
 *   <li>读取并解析上游链路头：合法则延续（EXTRACTED），缺失/非法则新起（GENERATED），
 *       两种来源通过 traceOrigin 字段区分；</li>
 *   <li>把 traceId/spanId/traceOrigin 写入 MDC，供业务结构化日志稳定输出；</li>
 *   <li>可在响应头回写当前 traceparent；</li>
 *   <li>无论业务是否抛错，finally 中清理 ThreadLocal 与 MDC，杜绝线程残留；</li>
 *   <li>传播组件自身异常时降级为新起链路，并以独立 logger 记录错误，
 *       业务请求不受影响，且故障有迹可查。</li>
 * </ol>
 */
public class TraceFilter extends OncePerRequestFilter implements Ordered {

    /** 可观测性自检日志统一 logger 名，便于在日志中检索组件自身故障。 */
    public static final String OBS_LOGGER_NAME = "observability.self";

    static final String MDC_TRACE_ID = "traceId";
    static final String MDC_SPAN_ID = "spanId";
    static final String MDC_TRACE_ORIGIN = "traceOrigin";

    private static final Logger selfLog = LoggerFactory.getLogger(OBS_LOGGER_NAME);

    private final TracePropagator propagator;
    private final TraceProperties properties;

    public TraceFilter(TracePropagator propagator, TraceProperties properties) {
        this.propagator = propagator;
        this.properties = properties;
    }

    /** 过滤器注册顺序：最先执行，保证后续组件都能拿到链路上下文。 */
    public static final int ORDER_HINT = Ordered.HIGHEST_PRECEDENCE + 10;

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只处理初始 REQUEST 分发；FORWARD 到 /error 等二次分发直接放行，
        // 不重复建立/清理上下文，避免错误页请求出现 origin=UNKNOWN 的脏数据。
        return request.getDispatcherType() != jakarta.servlet.DispatcherType.REQUEST;
    }

    @Override
    public int getOrder() {
        return ORDER_HINT;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        TraceContext context;
        try {
            String header = request.getHeader(properties.getHeaderName());
            ExtractResult result = propagator.extract(header);
            switch (result.status()) {
                case EXTRACTED -> context = result.context();
                case MISSING -> context = propagator.generate();
                default -> {
                    // INVALID：拒绝延续，另起一条，并把判定依据打出来
                    context = propagator.generate();
                    selfLog.warn("reject inbound trace header and start new trace | headerName={} | reason={}",
                            properties.getHeaderName(), result.reason());
                }
            }
            if (result.status() == ExtractResult.Status.EXTRACTED) {
                selfLog.debug("trace continued from upstream | reason={}", result.reason());
            }
        } catch (RuntimeException e) {
            // 可观测性组件自身故障：隔离，降级新起，留痕，不影响业务
            selfLog.error("trace propagation failed, fallback to generated trace", e);
            context = TraceContext.generated();
        }

        TraceContextHolder.set(context);
        MDC.put(MDC_TRACE_ID, context.traceId());
        MDC.put(MDC_SPAN_ID, context.spanId());
        MDC.put(MDC_TRACE_ORIGIN, context.source().name());
        try {
            if (properties.isResponseHeaderEnabled()) {
                try {
                    response.setHeader(properties.getHeaderName(), propagator.formatW3CTraceParent(context));
                } catch (RuntimeException e) {
                    selfLog.error("failed to write trace response header", e);
                }
            }
            chain.doFilter(request, response);
        } finally {
            TraceContextHolder.clear();
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_SPAN_ID);
            MDC.remove(MDC_TRACE_ORIGIN);
        }
    }
}
