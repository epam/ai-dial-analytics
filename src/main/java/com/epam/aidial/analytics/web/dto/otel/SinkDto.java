package com.epam.aidial.analytics.web.dto.otel;

import com.epam.aidial.analytics.service.otel.OtelSignal;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** One OTLP sink: its identity, its OTLP base path, and the table backing each enabled signal. */
public record SinkDto(

        @Schema(description = "Sink name", example = "app")
        String name,

        @Schema(description = "Human-readable description")
        String description,

        @Schema(description = "When the sink was provisioned")
        @JsonProperty("created_at") Instant createdAt,

        @Schema(description = "The sink's OTLP base path — configure an OTLP/HTTP exporter with this and it "
                + "appends /v1/logs, /v1/traces or /v1/metrics itself.", example = "/v1/otel/sink/app")
        String endpoint,

        @Schema(description = "One entry per enabled signal, ordered by signal")
        List<SignalDto> signals) {

    /** One enabled signal and the catalog table behind it. */
    public record SignalDto(

            @Schema(description = "The signal this entry lands", example = "logs")
            OtelSignal signal,

            @Schema(description = "The catalog table backing it", example = "otel_app_logs")
            String table,

            @Schema(description = "The backing table's status, or null when it is no longer in the catalog "
                    + "(it was deleted through /v1/tables); ingest for such a signal answers 409.")
            TableStatus status) {
    }
}
