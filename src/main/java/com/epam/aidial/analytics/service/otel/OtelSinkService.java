package com.epam.aidial.analytics.service.otel;

import com.epam.aidial.analytics.configuration.OtelSinkProperties;
import com.epam.aidial.analytics.exception.OtelDisabledException;
import com.epam.aidial.analytics.exception.OtelSinkNotFoundException;
import com.epam.aidial.analytics.exception.TableConflictException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.table.CreateTableInput;
import com.epam.aidial.analytics.service.table.TableCatalogService;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableSchemaCreationService;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.TableWriteMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Provisioning half of the OTLP sink capability: creates, reads and deletes sinks and the catalog tables
 * that back their signals.
 *
 * <p>This service <b>composes</b> the table catalog rather than reaching past it — every landing table is
 * created through {@link TableCatalogService#create} and materialized through
 * {@link TableSchemaCreationService#defineSchema}, so it inherits the writer credential, the name
 * validation, the catalog cap, and the compensating drop on a failed {@code CREATE TABLE}. The tables are
 * deliberately <b>not</b> {@code system}-flagged: that flag is specified as settable only by migration, so
 * claiming it here would contradict a shipped requirement (see {@code openspec/specs/otel-sink/spec.md}).
 *
 * <p><b>Create is all-or-nothing as observed through the API.</b> Provisioning three signals is three
 * separate catalog transactions plus three {@code CREATE TABLE}s, which cannot be one atomic unit across
 * two stores. Instead the tables created so far are dropped on any failure, so a caller never sees a sink
 * with fewer signals than it asked for — the sink row itself is written last, so a compensation that
 * itself fails leaves orphan tables rather than a half-provisioned sink.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OtelSinkService {

    private final OtelSinkRepository sinks;
    private final OtelSinkProperties properties;
    private final TableCatalogService catalogService;
    private final TableSchemaCreationService schemaCreationService;
    private final TableDefinitionRepository definitions;

    /** Rejects every call while the OTLP surface is switched off; called by both controllers. */
    public void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new OtelDisabledException("the OTLP sink surface is disabled (otel-sink.enabled=false)");
        }
    }

    /**
     * Provisions a sink and one landing table per opted-in signal.
     *
     * @param signals       the wire signal codes ({@code logs}/{@code traces}/{@code metrics}); taken as
     *                      raw text so an unknown one is a validation failure here rather than a binding
     *                      failure at the DTO boundary, which would report 400 instead of 422
     * @param retentionDays row-expiry TTL in days, or null to take the configured default
     * @throws TableValidationException if the name or signal set is invalid (422)
     * @throws TableConflictException   if the sink or one of its derived table names already exists (409)
     */
    // Deliberately NOT @Transactional: TableSchemaCreationService.defineSchema re-loads the definition
    // after a *committed* persist and then issues ClickHouse DDL, so wrapping the loop in one JPA
    // transaction would both hide that commit from it and leave ClickHouse tables behind on a rollback
    // the compensation below could no longer see. Per-step commits plus explicit compensation is the
    // only shape that works across two stores.
    public OtelSinkView create(String name, String description, List<String> signals, Integer retentionDays) {
        requireEnabled();
        OtelTableNames.requireValidSinkName(name);
        final Set<OtelSignal> requested = requireSignals(signals);
        if (sinks.existsById(name)) {
            throw new TableConflictException("otel sink '" + name + "' already exists");
        }
        final int retention = resolveRetention(retentionDays);

        // Check every derived name up front: finding the collision after two tables exist would mean
        // compensating work the caller never asked for, and the answer is the same either way.
        for (final OtelSignal signal : requested) {
            final String table = OtelTableNames.tableName(name, signal);
            if (definitions.existsById(table)) {
                throw new TableConflictException("table '" + table + "' already exists; sink '" + name
                        + "' cannot be created because it would need that name for its " + signal.code() + " signal");
            }
        }

        final List<String> created = new ArrayList<>(requested.size());
        final OtelSinkEntity sink = new OtelSinkEntity();
        sink.setName(name);
        sink.setDescription(description);
        sink.setCreatedAt(Instant.now());
        try {
            for (final OtelSignal signal : requested) {
                final String table = OtelTableNames.tableName(name, signal);
                catalogService.create(new CreateTableInput(table,
                        "OTLP " + signal.code() + " landing table for sink '" + name + "'",
                        TableType.SOURCE, null, TableWriteMode.APPEND, null));
                created.add(table);
                schemaCreationService.defineSchema(table, OtelSinkSchemas.schemaFor(signal, retention));
                sink.addSignal(new OtelSinkSignalEntity(signal, table));
            }
            sinks.save(sink);
        } catch (RuntimeException failed) {
            dropAll(created);
            throw failed;
        }
        return view(sink);
    }

    /** Every sink ordered by name. */
    @Transactional(readOnly = true)
    public List<OtelSinkView> list() {
        requireEnabled();
        return sinks.findAllByOrderByNameAsc().stream().map(this::view).toList();
    }

    /** One sink; 404 when it does not exist. */
    @Transactional(readOnly = true)
    public OtelSinkView get(String name) {
        requireEnabled();
        return view(require(name));
    }

    /**
     * Drops every table backing {@code name}'s signals and removes the sink. A signal whose table has
     * already been deleted through {@code /v1/tables} is skipped rather than failing the delete.
     */
    // Not @Transactional, for the same reason create is not: each catalogService.delete issues its own
    // ClickHouse DROP, which no JPA rollback can undo.
    public void delete(String name) {
        requireEnabled();
        final OtelSinkEntity sink = require(name);
        for (final OtelSinkSignalEntity signal : sink.getSignals()) {
            if (definitions.existsById(signal.getTableName())) {
                catalogService.delete(signal.getTableName());
            }
        }
        sinks.deleteById(name);
    }

    /**
     * The catalog table backing {@code signal} on sink {@code name}.
     *
     * @throws OtelSinkNotFoundException if the sink does not exist or did not enable that signal
     */
    @Transactional(readOnly = true)
    public String tableFor(String name, OtelSignal signal) {
        return require(name).getSignals().stream()
                .filter(entry -> entry.getSignal() == signal)
                .map(OtelSinkSignalEntity::getTableName)
                .findFirst()
                .orElseThrow(() -> new OtelSinkNotFoundException(
                        "otel sink '" + name + "' does not land the '" + signal.code() + "' signal"));
    }

    private OtelSinkEntity require(String name) {
        return sinks.findById(name)
                .orElseThrow(() -> new OtelSinkNotFoundException("otel sink '" + name + "' not found"));
    }

    /**
     * Resolves the requested signal codes: non-empty, all recognised, and free of duplicates. Resolution
     * happens here rather than at the DTO boundary so that an empty list, a null entry and an unrecognised
     * code all report the same 422 {@code validation_error} the duplicate case does.
     */
    private static Set<OtelSignal> requireSignals(List<String> signals) {
        if (signals == null || signals.isEmpty()) {
            throw new TableValidationException(
                    "at least one signal is required; choose from logs, traces, metrics");
        }
        final List<OtelSignal> resolved = new ArrayList<>(signals.size());
        for (final String code : signals) {
            final OtelSignal signal = OtelSignal.fromCode(code);
            if (signal == null) {
                throw new TableValidationException(
                        "unknown signal '" + code + "'; choose from logs, traces, metrics");
            }
            resolved.add(signal);
        }
        final Set<OtelSignal> distinct = new LinkedHashSet<>(resolved);
        if (distinct.size() != resolved.size()) {
            throw new TableValidationException("signals contains a duplicate entry");
        }
        // Provision in a stable declaration order rather than the caller's, so two sinks created with the
        // same signals in different orders are byte-identical in the catalog.
        return EnumSet.copyOf(distinct);
    }

    private int resolveRetention(Integer retentionDays) {
        if (retentionDays == null) {
            return Math.max(0, properties.defaultRetentionDays());
        }
        if (retentionDays < 1) {
            throw new TableValidationException(
                    "retention_days must be at least 1; omit it to take the configured default");
        }
        return retentionDays;
    }

    /** Best-effort compensation for a partially provisioned sink; a failure here is logged, not rethrown. */
    private void dropAll(List<String> tables) {
        for (final String table : tables) {
            try {
                catalogService.delete(table);
            } catch (RuntimeException ignored) {
                log.warn("Could not drop '{}' while undoing a failed otel sink creation; it is left behind",
                        table, ignored);
            }
        }
    }

    private OtelSinkView view(OtelSinkEntity sink) {
        final List<OtelSinkView.SignalView> signals = sink.getSignals().stream()
                .sorted(java.util.Comparator.comparing(OtelSinkSignalEntity::getSignal))
                .map(entry -> new OtelSinkView.SignalView(
                        entry.getSignal(), entry.getTableName(), statusOf(entry.getTableName())))
                .toList();
        return new OtelSinkView(sink.getName(), sink.getDescription(), sink.getCreatedAt(),
                OtelTableNames.endpoint(sink.getName()), signals);
    }

    /** The backing table's status, or null when it is no longer in the catalog. */
    private TableStatus statusOf(String table) {
        return definitions.findById(table).map(TableDefinitionEntity::getStatus).orElse(null);
    }
}
