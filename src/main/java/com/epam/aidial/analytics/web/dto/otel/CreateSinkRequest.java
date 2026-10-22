package com.epam.aidial.analytics.web.dto.otel;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Request body for {@code POST /v1/otel/sinks}: provision a named OTLP landing zone.
 *
 * <p><b>Nothing here is bean-validated and {@code signals} binds as raw text on purpose.</b> Every rule
 * about this body — the name grammar and length bound, the signal set, the retention floor — is enforced
 * by {@code OtelSinkService}/{@code OtelTableNames} and reported as HTTP 422 {@code validation_error},
 * which is the contract the rest of the catalog surface uses. A {@code @NotBlank}/{@code @NotEmpty}, or
 * binding {@code signals} straight to the enum, would turn those same rejections into a binding failure
 * and therefore a 400 {@code bad_request}, splitting one contract in two.
 */
@Schema(description = "Provisions an OTLP sink and one ClickHouse landing table per opted-in signal.")
public record CreateSinkRequest(

        @Schema(description = "Sink name; also the path segment in /v1/otel/sink/{name} and the prefix of "
                + "each landing table. snake_case, at most 51 characters.", example = "app")
        String name,

        @Schema(description = "Human-readable description", example = "Telemetry from the chat frontend")
        String description,

        @ArraySchema(
                arraySchema = @Schema(description = "Signals this sink lands; one table is created per "
                        + "entry. At least one is required and entries must be distinct.",
                        example = "[\"logs\", \"traces\"]"),
                schema = @Schema(allowableValues = {"logs", "traces", "metrics"}))
        List<String> signals,

        @Schema(description = "Row-expiry TTL in days for the landing tables. Omit to take the configured "
                + "default (otel-sink.default-retention-days).", example = "30")
        @JsonProperty("retention_days") Integer retentionDays) {
}
