package com.epam.aidial.analytics.web.controller.otel;

import com.epam.aidial.analytics.service.otel.OtelIngestService;
import com.epam.aidial.analytics.service.otel.OtelSignal;
import com.epam.aidial.analytics.web.dto.otel.OtlpExportResponse;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RequiresPrivilege;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * OTLP/HTTP data plane for one sink. The three mappings below are exactly the paths an OpenTelemetry
 * exporter derives from a base endpoint, so pointing an exporter at
 * {@code https://<host>/v1/otel/sink/<name>} is the whole configuration — it appends {@code /v1/logs},
 * {@code /v1/traces} or {@code /v1/metrics} itself.
 *
 * <p><b>JSON encoding only.</b> Each mapping declares {@code consumes = application/json}, so an exporter
 * configured for {@code application/x-protobuf} is answered {@code 415 unsupported_media_type} rather
 * than failing deep in a decoder. The payload is taken as a raw {@code JsonNode} on purpose: OTLP is a
 * protobuf schema whose JSON form has oneof-shaped values and both camelCase and snake_case field
 * spellings, and it must tolerate fields from a newer OTLP version — a bound POJO would reject exactly
 * the payloads a tolerant reader should accept.
 *
 * <p><b>Authorization mirrors {@code POST /v1/tables/*&#47;rows}</b>: authenticated at the URL layer, then
 * the caller must hold the {@code write} grant on the signal's landing table, which
 * {@code OtelSinkAccess.requireIngest} resolves the landing table from the sink name, downstream of the
 * declaration here, because the sink name in the path is not the table name. Like every gate it
 * is inert in {@code none} security mode.
 */
@RestController
@RequestMapping("/v1/otel/sink/{sinkName}/v1")
@RequiredArgsConstructor
@Tag(name = "OTLP ingest", description = "OTLP/HTTP JSON export endpoints exposed by a sink.")
public class OtelIngestController {

    private final OtelIngestService ingestService;

    @PostMapping(path = "/logs", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPrivilege(value = Privilege.TABLE_WRITE, deferred = true)
    @Operation(summary = "Export logs to a sink",
            description = "Accepts an OTLP/HTTP JSON ExportLogsServiceRequest and lands one row per log "
                    + "record. Returns an empty partial_success — the batch is accepted whole or rejected "
                    + "whole. 404 if the sink is unknown or does not land logs; 409 if its table is gone or "
                    + "not ACTIVE; 415 for a non-JSON content type.")
    public OtlpExportResponse logs(
            @Parameter(description = "Sink name", example = "app") @PathVariable String sinkName,
            @RequestBody JsonNode payload) {
        ingestService.ingest(sinkName, OtelSignal.LOGS, payload);
        return OtlpExportResponse.accepted();
    }

    @PostMapping(path = "/traces", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPrivilege(value = Privilege.TABLE_WRITE, deferred = true)
    @Operation(summary = "Export traces to a sink",
            description = "Accepts an OTLP/HTTP JSON ExportTraceServiceRequest and lands one row per span. "
                    + "Span events and links are stored as compact JSON text columns.")
    public OtlpExportResponse traces(
            @Parameter(description = "Sink name", example = "app") @PathVariable String sinkName,
            @RequestBody JsonNode payload) {
        ingestService.ingest(sinkName, OtelSignal.TRACES, payload);
        return OtlpExportResponse.accepted();
    }

    @PostMapping(path = "/metrics", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPrivilege(value = Privilege.TABLE_WRITE, deferred = true)
    @Operation(summary = "Export metrics to a sink",
            description = "Accepts an OTLP/HTTP JSON ExportMetricsServiceRequest and lands one row per data "
                    + "point, discriminated by metric_type. Exemplars are not stored.")
    public OtlpExportResponse metrics(
            @Parameter(description = "Sink name", example = "app") @PathVariable String sinkName,
            @RequestBody JsonNode payload) {
        ingestService.ingest(sinkName, OtelSignal.METRICS, payload);
        return OtlpExportResponse.accepted();
    }
}
