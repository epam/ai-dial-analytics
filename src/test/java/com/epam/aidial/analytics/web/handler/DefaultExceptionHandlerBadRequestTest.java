package com.epam.aidial.analytics.web.handler;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The other half of the authored-message carve-out, and the half that keeps it safe: a parameter that fails
 * to bind <b>without</b> an authored message still gets the generic sentence.
 *
 * <p>A path variable that will not convert to its declared type is Spring's own conversion failure, whose
 * text names the target type and the offending value — exactly what the never-echo rule exists to keep off
 * the wire. If this ever starts reporting the detail, the carve-out has widened from "messages we wrote" to
 * "binding messages", which is the regression worth catching.
 *
 * <p>Driven directly rather than through a request because no route declares a typed path variable to send
 * a bad value to; the rule still has to hold for the first one that does. The query-parameter form is
 * driven end to end by {@code CatalogTransferIntegrationTest} ({@code acknowledge_reused_names=maybe}).
 */
class DefaultExceptionHandlerBadRequestTest {

    @SuppressWarnings("unchecked")
    private final DefaultExceptionHandler handler = new DefaultExceptionHandler(
            mock(ObjectProvider.class), new RefusalLogLimiter(System::currentTimeMillis, Duration.ofSeconds(60)));

    @Test
    void anUnauthoredBindingFailureStillGetsTheGenericMessage() throws Exception {
        final MethodParameter version = new MethodParameter(
                DefaultExceptionHandlerBadRequestTest.class.getDeclaredMethod("typedPathVariable", int.class), 0);
        final MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "not-a-number", int.class, "version", version, new NumberFormatException("For input string"));
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/things/x/versions/not-a-number");

        final ErrorView view = handler.handleBadRequest(request, ex);

        assertThat(view.getStatus()).isEqualTo(400);
        assertThat(view.getError()).isEqualTo("bad_request");
        assertThat(view.getMessage())
                .isEqualTo("The request could not be processed; check the request body, parameters, and content type");
    }

    @SuppressWarnings("unused")
    private static void typedPathVariable(int version) {
    }
}
