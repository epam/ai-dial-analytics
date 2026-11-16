package com.epam.aidial.analytics.web.handler;

import com.epam.aidial.analytics.exception.IllegalFinalException;
import com.epam.aidial.analytics.exception.InlineQueryRejectedException;
import com.epam.aidial.analytics.exception.QueryEntityNotFoundException;
import com.epam.aidial.analytics.exception.SqlTranslationException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A dashboard widget's rejected inline body is answered exactly as the rejection would be answered on its
 * own — same status, same machine code — with only the widget's id prefixed to the message. The point is
 * the types whose code is not {@code bad_request}: rethrowing a supertype would have turned them into one.
 */
class DefaultExceptionHandlerInlineQueryTest {

    @SuppressWarnings("unchecked")
    private final DefaultExceptionHandler handler = new DefaultExceptionHandler(
            mock(ObjectProvider.class), new RefusalLogLimiter(System::currentTimeMillis, Duration.ofSeconds(60)));

    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/dashboards");

    @Test
    void keepsTheMachineCodeOfRejectionThatIsNotBadRequest() {
        final IllegalFinalException rejection = new IllegalFinalException("some_append_table");

        final ResponseEntity<ErrorView> response =
                handler.handleInlineQueryRejected(request, new InlineQueryRejectedException("w7", rejection));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getError()).isEqualTo("illegal_final");
        assertThat(response.getBody().getMessage()).isEqualTo("widget w7: " + rejection.getMessage());
    }

    @Test
    void keepsTheBadRequestOfTranslationFailure() {
        final ResponseEntity<ErrorView> response = handler.handleInlineQueryRejected(request,
                new InlineQueryRejectedException("w1", new SqlTranslationException("unknown column 'nope'")));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getError()).isEqualTo("bad_request");
        assertThat(response.getBody().getMessage()).isEqualTo("widget w1: unknown column 'nope'");
    }

    @Test
    void keepsStatusOtherThanBadRequest() {
        final ResponseEntity<ErrorView> response = handler.handleInlineQueryRejected(request,
                new InlineQueryRejectedException("w2", new QueryEntityNotFoundException("entity 'x' was not found")));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().getStatus()).isEqualTo(404);
        assertThat(response.getBody().getMessage()).startsWith("widget w2: ");
    }

    @Test
    void fallsBackToTheInternalErrorViewForAnUnmappedRejection() {
        final ResponseEntity<ErrorView> response = handler.handleInlineQueryRejected(request,
                new InlineQueryRejectedException("w3", new IllegalStateException("boom")));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getError()).isEqualTo("internal_error");
        assertThat(response.getBody().getMessage()).isEqualTo("widget w3: Internal server error");
    }

    /**
     * The delegation invokes a handler only when it is shaped {@code (HttpServletRequest, X) -> ErrorView} and
     * {@code X} accepts every exception it is declared for; any other shape falls back to {@code 500
     * internal_error}. So a handler added in another shape would silently turn an inline rejection's
     * {@code 4xx} into a {@code 500}. This keeps every handler but the delegating one in the shape.
     */
    @Test
    void everyOtherHandlerHasTheShapeTheDelegationInvokes() {
        final List<String> misshapen = new ArrayList<>();
        for (final Method method : DefaultExceptionHandler.class.getDeclaredMethods()) {
            final ExceptionHandler annotation = method.getAnnotation(ExceptionHandler.class);
            if (annotation == null || method.getName().equals("handleInlineQueryRejected")) {
                continue;
            }
            final Class<?>[] parameters = method.getParameterTypes();
            final boolean shaped = method.getReturnType() == ErrorView.class
                    && parameters.length == 2
                    && parameters[0] == HttpServletRequest.class
                    && Arrays.stream(annotation.value()).allMatch(parameters[1]::isAssignableFrom);
            if (!shaped) {
                misshapen.add(method.getName());
            }
        }
        assertThat(misshapen).isEmpty();
    }
}
