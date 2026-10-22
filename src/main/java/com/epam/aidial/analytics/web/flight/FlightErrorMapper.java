package com.epam.aidial.analytics.web.flight;

import com.epam.aidial.analytics.exception.ClickHouseQueryException;
import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import com.epam.aidial.analytics.exception.QueryBudgetExceededException;
import com.epam.aidial.analytics.exception.QueryEntityNotFoundException;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.exception.SqlTranslationException;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightRuntimeException;

/**
 * Maps service exceptions to stable gRPC statuses, mirroring the {@code ErrorView}
 * philosophy: client errors carry their (already sanitized) message, anything unexpected is
 * redacted to a generic INTERNAL. Raw backend internals never cross the wire.
 */
public final class FlightErrorMapper {

    private FlightErrorMapper() {}

    /** The gRPC-status-bearing exception for {@code e}; safe to hand to Flight listeners. */
    public static FlightRuntimeException toFlightException(RuntimeException e) {
        return switch (e) {
            case FlightRuntimeException flight -> flight;
            case SqlTranslationException sql ->
                    CallStatus.INVALID_ARGUMENT.withDescription(sql.getMessage()).withCause(e).toRuntimeException();
            case QueryValidationException validation ->
                    CallStatus.INVALID_ARGUMENT.withDescription(validation.getMessage()).withCause(e).toRuntimeException();
            case QueryEntityNotFoundException entity ->
                    CallStatus.INVALID_ARGUMENT.withDescription(entity.getMessage()).withCause(e).toRuntimeException();
            case ClickHouseQueryException query ->
                    CallStatus.INVALID_ARGUMENT.withDescription(query.getMessage()).withCause(e).toRuntimeException();
            // INVALID_ARGUMENT, mirroring the HTTP surface's 422 and for the same reason: the refusal is
            // deterministic, so RESOURCE_EXHAUSTED — which a gRPC client treats as retryable — would put the
            // caller in a loop. The message names the ceiling and what to narrow, and is the caller's own
            // (it echoes no driver text), so it must survive rather than be redacted to a generic INTERNAL;
            // Flight is where a BI client pulls exactly the wide result this budget refuses.
            case QueryBudgetExceededException budget ->
                    CallStatus.INVALID_ARGUMENT.withDescription(budget.getMessage()).withCause(e).toRuntimeException();
            case ClickHouseUnavailableException unavailable -> CallStatus.UNAVAILABLE
                    .withDescription(unavailable.getMessage()).withCause(e).toRuntimeException();
            case FlightStreamSaturatedException busy ->
                    CallStatus.RESOURCE_EXHAUSTED.withDescription(busy.getMessage()).withCause(e).toRuntimeException();
            default -> CallStatus.INTERNAL.withDescription("internal error").withCause(e).toRuntimeException();
        };
    }
}
