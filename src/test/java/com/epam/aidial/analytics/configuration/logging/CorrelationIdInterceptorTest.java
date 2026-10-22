package com.epam.aidial.analytics.configuration.logging;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdInterceptorTest {

    private final CorrelationIdInterceptor interceptor = new CorrelationIdInterceptor();

    @Test
    void setsTraceparentHeaderWhenValidSpanContextActive() {
        SpanContext spanContext = SpanContext.create(
                "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", TraceFlags.getSampled(), TraceState.getDefault());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (Scope ignored = Span.wrap(spanContext).makeCurrent()) {
            boolean proceed = interceptor.preHandle(request, response, new Object());

            assertThat(proceed).isTrue();
            assertThat(response.getHeader(CorrelationIdInterceptor.TRACEPARENT_HEADER_NAME))
                    .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        }
    }

    @Test
    void omitsTraceparentHeaderWithoutValidSpanContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        assertThat(response.getHeader(CorrelationIdInterceptor.TRACEPARENT_HEADER_NAME)).isNull();
    }
}
