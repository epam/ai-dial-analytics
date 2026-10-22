package com.epam.aidial.analytics.web.dto.table;

import com.epam.aidial.analytics.service.table.TtlUnit;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Structured row-expiry TTL declaration: rows expire once {@code column} is older than
 * {@code interval} {@code unit}s (ClickHouse {@code TTL <column> + INTERVAL <interval> <UNIT>}).
 * The column must be a declared non-nullable {@code date} or {@code timestamp} column of the table
 * (referenced by its physical {@code source_name}); free-form TTL expressions are not accepted.
 * On {@code PATCH /v1/tables/{name}/schema} the member is a merge-patch: absent/null leaves the TTL
 * unchanged, a full object sets or replaces it, and an empty object {@code {}} removes it — a
 * partial object is rejected (422).
 */
public record TtlRequest(
        @Schema(description = "Physical (source_name) column the TTL is computed over; a declared "
                + "non-nullable date or timestamp column of the table", example = "request_time")
        String column,

        // Deliberately no bean-validation @Positive: a zero/negative interval is a semantic TTL
        // violation rejected with 422 by TableTtlRules.requireValid, and a binding-time constraint
        // would preempt it with a generic 400.
        @Schema(description = "Positive number of units after which a row expires; zero or negative "
                + "is rejected (422)", example = "90")
        Integer interval,

        @Schema(description = "Interval unit", example = "day")
        TtlUnit unit) {

    /** Whether this is the empty object {@code {}} (the "remove the TTL" form on PATCH). */
    public boolean isEmpty() {
        return column == null && interval == null && unit == null;
    }
}
