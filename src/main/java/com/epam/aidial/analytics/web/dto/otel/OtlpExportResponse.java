package com.epam.aidial.analytics.web.dto.otel;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * The OTLP/HTTP success body: an {@code Export<Signal>ServiceResponse} carrying an empty
 * {@code partial_success}, which is OTLP's encoding of "everything was accepted".
 *
 * <p>The member is spelled {@code partial_success} rather than the lowerCamelCase {@code partialSuccess}
 * an OTLP/JSON producer would emit. That is not a deviation: protojson accepts <em>both</em> the original
 * proto field name and its lowerCamelCase form on input, so a collector unmarshalling this body reads it
 * correctly either way — and the snake_case spelling is what every other ADAS DTO uses.
 *
 * <p>No field is added beyond the OTLP message (a row count would be useful but is not part of it):
 * protojson rejects unknown fields by default, so an extra member would turn a success into a client-side
 * unmarshalling failure.
 */
@Schema(description = "OTLP export response; an empty partial_success means the whole batch was accepted.")
public record OtlpExportResponse(
        @JsonProperty("partial_success") Map<String, Object> partialSuccess) {

    /** The full-success body: an empty {@code partial_success} object. */
    public static OtlpExportResponse accepted() {
        return new OtlpExportResponse(Map.of());
    }
}
