package com.epam.aidial.analytics.web.flight;

import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import com.epam.aidial.analytics.exception.QueryBudgetExceededException;
import com.epam.aidial.analytics.exception.QueryValidationException;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStatusCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a service failure looks like to a Flight SQL client. The mapper is the whole of the Flight surface's
 * error contract, so what it does with a new exception type is what a BI tool sees.
 *
 * <p>The budget refusal is the case that matters here. Flight is where a client pulls exactly the wide
 * result the query budget exists to refuse, and the refusal's value is entirely in its message — which
 * ceiling was hit, and what to narrow. Falling through to the generic {@code INTERNAL} would redact that,
 * leaving the caller with the same "something went wrong" the budget was introduced to replace.
 */
class FlightErrorMapperTest {

    @Test
    void budgetRefusalKeepsItsMessageAndIsNotRetryable() {
        final FlightRuntimeException mapped = FlightErrorMapper.toFlightException(
                new QueryBudgetExceededException("this query exceeded the PT20S execution timeout"
                        + " (query.budget.*). Narrow the time range.", new IllegalStateException("cause")));

        // INVALID_ARGUMENT mirrors the HTTP surface's 422: the refusal is deterministic, so the retryable
        // RESOURCE_EXHAUSTED would put a well-behaved client in a loop.
        assertThat(mapped.status().code()).isEqualTo(FlightStatusCode.INVALID_ARGUMENT);
        assertThat(mapped.status().description())
                .contains("execution timeout")
                .contains("query.budget.*")
                .contains("Narrow the time range");
        assertThat(mapped.status().description()).isNotEqualTo("internal error");
    }

    @Test
    void unrelatedFailuresKeepTheirExistingStatuses() {
        assertThat(FlightErrorMapper.toFlightException(new QueryValidationException("bad field")).status().code())
                .isEqualTo(FlightStatusCode.INVALID_ARGUMENT);
        assertThat(FlightErrorMapper.toFlightException(
                new ClickHouseUnavailableException("ClickHouse is unreachable", null)).status().code())
                .isEqualTo(FlightStatusCode.UNAVAILABLE);
        // Anything unrecognised is still redacted — a budget case must not widen what crosses the wire.
        final FlightRuntimeException unknown =
                FlightErrorMapper.toFlightException(new IllegalStateException("connection pool detail"));
        assertThat(unknown.status().code()).isEqualTo(FlightStatusCode.INTERNAL);
        assertThat(unknown.status().description()).isEqualTo("internal error");
    }
}
