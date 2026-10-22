package com.epam.aidial.analytics.web.controller.otel;

import com.epam.aidial.analytics.service.otel.OtelSinkService;
import com.epam.aidial.analytics.service.otel.OtelSinkView;
import com.epam.aidial.analytics.web.dto.otel.CreateSinkRequest;
import com.epam.aidial.analytics.web.dto.otel.SinkDto;
import com.epam.aidial.analytics.web.dto.otel.SinkListDto;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RequiresPrivilege;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Control plane for OTLP sinks. Creating a sink provisions one ClickHouse landing table per opted-in
 * signal ({@code otel_<sink>_<signal>}) and returns the OTLP base path an exporter should be configured
 * with; the data plane itself lives in {@link OtelIngestController}.
 *
 * <p>Create requires {@code TABLE_CREATE} and delete {@code TABLE_DELETE} (they issue catalog DDL,
 * exactly like
 * {@code POST /v1/tables}); the two reads take the ordinary application-role rule every catalog read
 * takes.
 */
@RestController
@RequestMapping("/v1/otel/sinks")
@RequiredArgsConstructor
@Tag(name = "OTLP sinks", description = "Provision OpenTelemetry landing zones and their ClickHouse tables.")
public class OtelSinkController {

    private final OtelSinkService sinkService;

    @PostMapping
    @RequiresPrivilege(Privilege.TABLE_CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an OTLP sink",
            description = "Creates one catalog source table per opted-in signal and materializes it in "
                    + "ClickHouse, then records the sink. All-or-nothing: if any signal's table cannot be "
                    + "created the tables created so far are dropped and no sink is recorded. Rejected if "
                    + "the sink name is taken (409), a derived table name is taken (409), or the name or "
                    + "signal set is invalid (422 — every rule about this body is reported that way, "
                    + "never as a binding 400). The returned `endpoint` is what an OTLP/HTTP exporter "
                    + "should be pointed at — it appends /v1/logs, /v1/traces or /v1/metrics itself.")
    public SinkDto create(@RequestBody CreateSinkRequest request) {
        return toDto(sinkService.create(
                request.name(), request.description(), request.signals(), request.retentionDays()));
    }

    @GetMapping
    @RequiresPrivilege(Privilege.REGISTRY_READ)
    @Operation(summary = "List OTLP sinks", description = "Returns all sinks, ordered by name.")
    public SinkListDto list() {
        return new SinkListDto(sinkService.list().stream().map(OtelSinkController::toDto).toList());
    }

    @GetMapping("/{name}")
    @RequiresPrivilege(Privilege.REGISTRY_READ)
    @Operation(summary = "Get an OTLP sink",
            description = "Returns the sink, its OTLP base path, and the table backing each enabled "
                    + "signal. A signal whose table was deleted through /v1/tables reports a null status.")
    public SinkDto get(@Parameter(description = "Sink name", example = "app") @PathVariable String name) {
        return toDto(sinkService.get(name));
    }

    @DeleteMapping("/{name}")
    @RequiresPrivilege(Privilege.TABLE_DELETE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an OTLP sink",
            description = "Drops every catalog table backing the sink's signals (ClickHouse table and "
                    + "catalog rows) and removes the sink. A signal whose table is already gone is skipped.")
    public void delete(@Parameter(description = "Sink name", example = "app") @PathVariable String name) {
        sinkService.delete(name);
    }

    private static SinkDto toDto(OtelSinkView view) {
        return new SinkDto(view.name(), view.description(), view.createdAt(), view.endpoint(),
                view.signals().stream()
                        .map(signal -> new SinkDto.SignalDto(signal.signal(), signal.table(), signal.status()))
                        .toList());
    }
}
