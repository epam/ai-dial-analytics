package com.epam.aidial.analytics.configuration.logging;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Sets the W3C Trace Context {@code traceparent} header on every HTTP response, derived from the
 * current OpenTelemetry span context. When no valid trace context is active (tracing disabled, the
 * default), the header is simply absent.
 */
public class CorrelationIdInterceptor implements HandlerInterceptor {

    public static final String TRACEPARENT_HEADER_NAME = "traceparent";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String traceParent = TraceContextUtils.formatTraceParent();
        if (traceParent != null) {
            response.setHeader(TRACEPARENT_HEADER_NAME, traceParent);
        }
        return true;
    }
}
