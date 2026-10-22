package com.epam.aidial.analytics.web.dto.enrichment;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The object form of a {@code vars} value: exactly one of a bound column or a JSONata transform over the
 * scanned row. Both forms may reference source columns, which the governance gate checks for entitlement.
 *
 * <p>A plain record with no custom deserializer, deliberately: {@link VarSpecDeserializer} delegates the
 * object branch here so the strict request mapper's {@code FAIL_ON_UNKNOWN_PROPERTIES} still applies
 * inside it and a misspelled member is a 400 naming it.
 */
public record VarSpecBody(
        @Schema(description = "A column of the read source's entity, under either spelling, or an "
                + "enrichment column under its qualified flat name", example = "dial_usage_log_payload.request_body")
        String column,

        @Schema(description = "A JSONata expression over the scanned row, parsed (parse-only) at "
                + "registration; runtime evaluation is the runner's")
        String jsonata) {
}
