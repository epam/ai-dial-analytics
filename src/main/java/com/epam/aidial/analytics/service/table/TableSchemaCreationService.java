package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.clickhouse.ClickHouseDdlExecutor;
import com.epam.aidial.analytics.clickhouse.ClickHouseTableInspector;
import com.epam.aidial.analytics.clickhouse.DdlExecutor;
import com.epam.aidial.analytics.exception.SensitiveClearanceRequiredException;
import com.epam.aidial.analytics.exception.TableConflictException;
import com.epam.aidial.analytics.exception.TableNotFoundException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.enrichment.support.ProvenanceColumns;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccess;
import com.epam.aidial.analytics.service.table.TableDdlRenderer.CreateSpec;
import com.epam.aidial.analytics.service.table.TableDdlRenderer.DdlColumn;
import com.epam.aidial.analytics.service.table.grant.SensitiveGrantInvariant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Defines the physical schema of a table and materializes it in a single call
 * ({@code POST /v1/tables/{name}/schema}). Allowed only while the table is <em>not</em> materialized
 * ({@code PENDING}/{@code FAILED}); an already-materialized ({@code ACTIVE}) table is rejected with 409
 * (its columns then evolve via {@link TableSchemaService}). The schema must be complete for the table's
 * kind (a source: at least one column and a non-empty {@code ordering_key}, plus the optional
 * scan-metadata pair {@code identity_column}/{@code version_column} — see {@link ScanMetadataRules}; an
 * enrichment: a {@code grain_key}, and neither scan column) and internally consistent — validated before anything is persisted, so an
 * incomplete/inconsistent request is a 422 that leaves the definition untouched (still
 * {@code PENDING}/{@code FAILED}). On a valid request it persists the columns + keys, issues the single
 * ClickHouse {@code CREATE TABLE} (writer credential), and flips the definition to {@code ACTIVE}; on a
 * ClickHouse failure — including an ambiguous timeout — it undoes any partial table with a best-effort
 * {@code DROP TABLE IF EXISTS} and records {@code FAILED} (retryable by re-posting the schema), never
 * leaving an {@code ACTIVE} definition without a confirmed ClickHouse table.
 */
@Service
@RequiredArgsConstructor
public class TableSchemaCreationService {

    private final TableDefinitionRepository definitions;
    private final ColumnMappingRepository columns;
    private final CatalogSchemaWriter writer;
    private final TableCatalogService catalogService;
    private final ClickHouseDdlExecutor ddlExecutor;
    private final TableDdlRenderer ddlRenderer;
    private final ClickHouseTypeMapper typeMapper;
    private final SensitiveGrantInvariant sensitiveGrants;
    private final SensitiveColumnAccess sensitiveAccess;
    private final ClickHouseTableInspector inspector;

    /**
     * Defines and materializes a not-yet-materialized table's physical schema. Rejects a {@code system}
     * table (409) and an already-materialized table (409); rejects an incomplete/inconsistent schema
     * (422, nothing persisted). Persists the columns + keys, then issues the ClickHouse
     * {@code CREATE TABLE} and flips to {@code ACTIVE}; on a ClickHouse failure it forces the
     * not-materialized outcome with a best-effort {@code DROP TABLE IF EXISTS}, records {@code FAILED},
     * and rethrows (if the status flip itself fails the row stays {@code PENDING}) — either way it never
     * leaves an {@code ACTIVE} definition without a confirmed ClickHouse table.
     */
    public TableView defineSchema(String name, DefineSchemaInput input) {
        return defineSchema(name, input, ddlExecutor);
    }

    /**
     * {@link #defineSchema(String, DefineSchemaInput)} with the DDL — the {@code CREATE TABLE} and the
     * compensating drop alike — run through {@code executor} instead of the ClickHouse one. Exists for the
     * catalog import's validation pass, which runs this inside a Postgres transaction it rolls back and
     * passes {@link DdlExecutor#NO_OP}, so nothing reaches ClickHouse. Every other caller uses the
     * two-argument form.
     */
    public TableView defineSchema(String name, DefineSchemaInput input, DdlExecutor executor) {
        final TableDefinitionEntity definition = definitions.findById(name)
                .orElseThrow(() -> new TableNotFoundException("table '" + name + "' not found"));
        definition.requireModifiable();
        requireNotMaterialized(definition);
        requireClearanceForSensitive(name, input);
        final DefineSchemaInput schema = withInjectedProvenance(definition.getType(), input);
        requireComplete(definition, schema);
        requireSensitiveGrantsNotWidened(definition, schema);

        // Persist first (validates internal consistency and rolls back on failure), then materialize.
        // Re-load after the committed persist so the spec sees the stored columns/keys (the entity
        // loaded above is detached and predates persistSchema's writes).
        writer.persistSchema(name, schema);
        final TableDefinitionEntity persisted = definitions.findById(name)
                .orElseThrow(() -> new TableNotFoundException("table '" + name + "' not found"));
        try {
            // The inherited TTL is read from the source's physical table, which a validation pass (NO_OP) does not
            // need: it issues no DDL, and must not fail an import preview over a ClickHouse read. Inside the try,
            // like the CREATE: the schema is committed by now, so a failed read must record FAILED rather than
            // leave a half-defined PENDING row.
            final CreateSpec spec = createSpec(persisted, executor != DdlExecutor.NO_OP, List.of());
            executor.execute(ddlRenderer.renderCreate(name, spec));
        } catch (RuntimeException ex) {
            safeDrop(name, executor);
            try {
                writer.markStatus(name, TableStatus.FAILED);
            } catch (RuntimeException ignored) {
                // Leave the row PENDING (not queryable); surface the original ClickHouse error below.
            }
            throw ex;
        }
        writer.markStatus(name, TableStatus.ACTIVE);
        return catalogService.get(name);
    }

    /**
     * Declaring a column {@code sensitive} needs the {@code TABLE_SENSITIVE} clearance on the table, as it
     * does on a {@code PATCH .../schema} {@code add}: anything touching sensitivity does, and it stops an
     * author creating a column it could not then see. Judged on the caller's own declaration, before
     * provenance injection, since an injected column is the service's declaration rather than the caller's.
     */
    private void requireClearanceForSensitive(String name, DefineSchemaInput input) {
        if (input.columns() == null || sensitiveAccess.maySeeSensitive(name)) {
            return;
        }
        for (final ColumnInput column : input.columns()) {
            if (column.sensitive()) {
                throw new SensitiveClearanceRequiredException("declaring column '" + column.name() + "' sensitive on table '"
                        + name + "' requires TABLE_SENSITIVE on it alongside TABLE_MODIFY");
            }
        }
    }

    /**
     * Appends the provenance columns an <b>enrichment</b> schema did not declare for itself.
     *
     * <p>Four authors have given four different answers on the four live targets — all five columns twice
     * (under two different tags, with two different wordings), two on one, and none at all on the fourth —
     * for columns that mean the same thing everywhere. Injecting them makes that one declaration.
     *
     * <p><b>Injection can only ever add.</b> A provenance name the caller declared is left exactly as
     * declared: not duplicated, not overwritten, not reordered. Both {@code llm} targets declare all five
     * themselves, with their own wording and position, and any existing authoring script replayed against a
     * new table will do the same — so injecting unconditionally would turn every such script into a
     * duplicate-column 422. The missing ones are appended in {@link ProvenanceColumns#DECLARED_ORDER}.
     *
     * <p>Public so the catalog import can count a bundle table's columns after injection, as define-schema
     * does, when it checks the column cap up front.
     *
     * <p><b>Scope: new tables only, and by construction rather than by choice.</b>
     * {@link #requireNotMaterialized} refuses this endpoint for every already-{@code ACTIVE} table, so
     * there is no "existing tables re-post cleanly" — a table defined before injection keeps its zero
     * provenance columns unless someone patches them in by hand. {@code PATCH .../schema} deliberately does
     * <b>not</b> inject: it is an explicit operation list, and silently widening it into a five-column DDL
     * on a populated table is the opposite of a caller declaring what they want.
     */
    public static DefineSchemaInput withInjectedProvenance(TableType type, DefineSchemaInput input) {
        if (type != TableType.ENRICHMENT) {
            return input;
        }
        final List<ColumnInput> declared = input.columns() == null ? List.of() : input.columns();
        final Set<String> present = declared.stream()
                .flatMap(column -> Stream.of(column.name(), column.sourceName()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        final List<ColumnInput> missing = ProvenanceColumns.canonicalColumns().stream()
                .filter(column -> !present.contains(column.name()))
                .toList();
        if (missing.isEmpty()) {
            return input;
        }
        final List<ColumnInput> columns = new ArrayList<>(declared);
        columns.addAll(missing);
        return new DefineSchemaInput(columns, input.orderingKey(), input.partitionColumn(),
                input.partitionGranularity(), input.grainKey(), input.cardinality(), input.ttl(),
                input.identityColumn(), input.versionColumn());
    }

    /**
     * The laundering subset rule for the <b>initial</b> schema, when it declares a {@code sensitive} column.
     *
     * <p>This is the third way a table first comes to carry one, and leaving it out left the whole invariant
     * bypassable through the front door: grant {@code sensitive} on a {@code PENDING} enrichment while
     * it has no columns (accepted — the pair has nothing to protect), then define a schema with a sensitive
     * column. The stored state is then exactly what the rule forbids, and the next pipeline registration is
     * refused over a grant edit the operator made days earlier.
     *
     * <p>Runs before anything is persisted, like every other check here, and on the same terms as the
     * {@code add} branch of {@code PATCH .../schema}. Whether the caller may declare the column sensitive at
     * all — the {@code TABLE_SENSITIVE} clearance — is a separate check, made first.
     */
    private void requireSensitiveGrantsNotWidened(TableDefinitionEntity definition, DefineSchemaInput schema) {
        final List<ColumnInput> declared = schema.columns() == null ? List.of() : schema.columns();
        if (declared.stream().anyMatch(ColumnInput::sensitive)) {
            sensitiveGrants.requireNotWidenedWithNewSensitiveColumn(definition);
        }
    }

    private void requireNotMaterialized(TableDefinitionEntity definition) {
        if (definition.isMaterialized()) {
            throw new TableConflictException("table '" + definition.getName()
                    + "' is already materialized; evolve its schema via PATCH /v1/tables/{name}/schema");
        }
    }

    /**
     * Completeness gate: a source needs columns and an ordering key, an enrichment needs a grain key.
     * The optional scan-metadata pair is a source's alone ({@link ScanMetadataRules}), and what a declared
     * name must physically be is checked against the request's own columns by
     * {@link ScanMetadataRules#validateSource}.
     */
    private void requireComplete(TableDefinitionEntity definition, DefineSchemaInput input) {
        ScanMetadataRules.requireDeclarable(definition, input);
        if (definition.getType() == TableType.ENRICHMENT) {
            if (input.grainKey() == null || input.grainKey().isBlank()) {
                throw new TableValidationException("enrichment '" + definition.getName()
                        + "' requires a grain_key to define its schema");
            }
            return;
        }
        if (input.columns() == null || input.columns().isEmpty()) {
            throw new TableValidationException(
                    "source '" + definition.getName() + "' requires at least one column to define its schema");
        }
        if (input.orderingKey() == null || input.orderingKey().isEmpty()) {
            throw new TableValidationException(
                    "source '" + definition.getName() + "' requires a non-empty ordering_key to define its schema");
        }
    }

    /**
     * The CREATE spec for {@code definition}'s persisted schema (completeness already enforced by the caller).
     * Public for the relayout of an existing enrichment, which creates the aligned copy of a live table from the
     * same spec define-schema would.
     *
     * @param readPhysical  whether an aligned enrichment with no TTL of its own may read its source's physical
     *     TTL to inherit (see {@link InheritedTtl}); false renders it without one
     * @param lookupIndexes columns to carry a lookup index from the start
     */
    public CreateSpec createSpec(TableDefinitionEntity definition, boolean readPhysical, List<String> lookupIndexes) {
        final List<ColumnMappingEntity> cols = columns.findByTableNameOrderByOrdinal(definition.getName());
        if (definition.getType() == TableType.ENRICHMENT) {
            return enrichmentSpec(definition, cols, readPhysical, lookupIndexes);
        }
        return sourceSpec(definition, cols);
    }

    private CreateSpec sourceSpec(TableDefinitionEntity definition, List<ColumnMappingEntity> cols) {
        final List<String> orderingKey = definition.getOrderingKey();
        final Map<String, ColumnMappingEntity> declared = new LinkedHashMap<>();
        cols.forEach(col -> declared.put(col.getSourceName(), col));
        for (final String key : orderingKey) {
            final ColumnMappingEntity column = declared.get(key);
            if (column == null) {
                throw new TableValidationException(
                        "ordering_key column '" + key + "' is not a declared column of this table");
            }
            TableColumnRules.requireKeyable("ordering_key", key, column.getType(), column.isNullable());
        }
        if (definition.getPartitionColumn() != null) {
            final ColumnMappingEntity column = declared.get(definition.getPartitionColumn());
            if (column == null) {
                throw new TableValidationException("partition_by column '" + definition.getPartitionColumn()
                        + "' is not a declared column of this table");
            }
            TableColumnRules.requireKeyable("partition_by", definition.getPartitionColumn(), column.getType(),
                    column.isNullable());
        }
        final List<DdlColumn> ddlColumns = new ArrayList<>(cols.size());
        for (final ColumnMappingEntity col : cols) {
            ddlColumns.add(new DdlColumn(col.getSourceName(),
                    typeMapper.toDdlType(col.getType(), col.getElementType(), col.getEnumValues(), col.isNullable())));
        }
        return new CreateSpec(TableType.SOURCE, definition.getWriteMode(), ddlColumns, orderingKey,
                definition.getPartitionColumn(), definition.getPartitionGranularity(),
                ttlDdl(definition, cols, null, List.of()));
    }

    private CreateSpec enrichmentSpec(TableDefinitionEntity definition, List<ColumnMappingEntity> cols,
            boolean readPhysical, List<String> lookupIndexes) {
        final String grainKey = definition.getGrainKey();
        if (grainKey == null) {
            throw new TableValidationException("enrichment '" + definition.getName()
                    + "' requires a grain_key to define its schema");
        }
        final List<ColumnMappingEntity> sourceColumns = columns.findByTableNameOrderByOrdinal(definition.getSourceTable());
        final ColumnMappingEntity sourceColumn = sourceColumn(definition, sourceColumns, grainKey, "grain_key");
        // Not sourceColumn.isNullable(): the grain column added below is rendered non-nullable whatever the
        // source declares, so the enrichment's ORDER BY is never over a Nullable(...) column.
        TableColumnRules.requireKeyable("grain_key", grainKey, sourceColumn.getType(), false);

        // The grain key is a hidden physical column (same name + type as on the source), followed by the
        // enrichment's user columns; the renderer adds the ReplacingMergeTree system columns.
        final List<DdlColumn> ddlColumns = new ArrayList<>(cols.size() + 1);
        ddlColumns.add(new DdlColumn(grainKey,
                typeMapper.toDdlType(sourceColumn.getType(), sourceColumn.getElementType(),
                        sourceColumn.getEnumValues(), false)));
        // The alignment column, when hidden, follows the grain key: like it, a physical column of the source's name
        // and type, non-nullable whatever the source declares. An adopted one is among the user columns already.
        final String alignment = definition.getPartitionColumn();
        if (alignment != null && EnrichmentAlignment.isHidden(alignment, cols)) {
            final ColumnMappingEntity partition = sourceColumn(definition, sourceColumns, alignment, "partition_by");
            ddlColumns.add(new DdlColumn(alignment, typeMapper.toDdlType(partition.getType(),
                    partition.getElementType(), partition.getEnumValues(), false)));
        }
        for (final ColumnMappingEntity col : cols) {
            ddlColumns.add(new DdlColumn(col.getSourceName(),
                    typeMapper.toDdlType(col.getType(), col.getElementType(), col.getEnumValues(), col.isNullable())));
        }
        TableDdlRenderer.TtlDdl ttl = ttlDdl(definition, cols, alignment, sourceColumns);
        boolean dropParts = false;
        if (ttl == null && alignment != null && readPhysical) {
            final InheritedTtl inherited = inspector.engineFull(definition.getSourceTable())
                    .flatMap(engine -> InheritedTtl.parse(engine, alignment))
                    .orElse(null);
            if (inherited != null) {
                ttl = inherited.ttl();
                dropParts = inherited.ttlOnlyDropParts();
            }
        }
        // An enrichment's engine is fixed by its kind, so the *effective* discipline is passed rather than
        // the declared one: the stored column is not caller-settable here and keeps its APPEND default,
        // which would make the spec contradict the engine the renderer goes on to choose.
        return new CreateSpec(TableType.ENRICHMENT, definition.effectiveWriteMode(), ddlColumns,
                List.of(grainKey), alignment, alignment == null ? null : definition.getPartitionGranularity(), ttl,
                dropParts, lookupIndexes);
    }

    private static ColumnMappingEntity sourceColumn(TableDefinitionEntity definition,
            List<ColumnMappingEntity> sourceColumns, String column, String field) {
        return EnrichmentAlignment.physicalColumn(sourceColumns, column)
                .orElseThrow(() -> new TableValidationException(field + " '" + column
                        + "' is not a column of source '" + definition.getSourceTable() + "'"));
    }

    /**
     * The persisted TTL as a renderable clause (null when the table has none), admitting a TTL over the hidden
     * alignment column {@code alignment} (null for none) — a {@code timestamp} or a {@code date} like its column
     * among {@code sourceColumns}, which only a {@code timestamp} wraps.
     */
    private TableDdlRenderer.TtlDdl ttlDdl(TableDefinitionEntity definition, List<ColumnMappingEntity> cols,
            String alignment, List<ColumnMappingEntity> sourceColumns) {
        final TtlInput ttl = definition.ttl();
        if (ttl == null) {
            return null;
        }
        if (ttl.column().equals(alignment) && EnrichmentAlignment.isHidden(alignment, cols)) {
            final ColumnMappingEntity partition = sourceColumn(definition, sourceColumns, alignment, "ttl");
            return new TableDdlRenderer.TtlDdl(ttl.column(), ttl.interval(), ttl.unit(),
                    TableTtlRules.wrapsToDateTime(partition.getType()));
        }
        final ColumnMappingEntity column = EnrichmentAlignment.physicalColumn(cols, ttl.column())
                .orElseThrow(() -> new TableValidationException(
                        "ttl column '" + ttl.column() + "' is not a declared column of this table"));
        return new TableDdlRenderer.TtlDdl(ttl.column(), ttl.interval(), ttl.unit(),
                TableTtlRules.wrapsToDateTime(column.getType()));
    }

    /** Best-effort idempotent drop used to undo an ambiguous create; swallows any failure. */
    private void safeDrop(String name, DdlExecutor executor) {
        try {
            executor.execute(ddlRenderer.renderDrop(name));
        } catch (RuntimeException ignored) {
            // ClickHouse may be unreachable (the very reason the create failed); nothing more to do.
        }
    }
}
