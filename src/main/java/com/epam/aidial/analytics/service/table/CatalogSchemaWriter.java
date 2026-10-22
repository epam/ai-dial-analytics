package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.configuration.TableLimitsProperties;
import com.epam.aidial.analytics.exception.ColumnInRowPolicyException;
import com.epam.aidial.analytics.exception.TableNotFoundException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Transactional database half of the table-management write flows, kept as its own bean so its
 * {@code @Transactional} boundaries apply (a self-invoked {@code @Transactional} method on the
 * orchestrating service would bypass the Spring proxy). It never issues ClickHouse DDL — the
 * orchestrating services ({@link TableCatalogService}, {@link TableSchemaCreationService},
 * {@link TableSchemaService}) do that around these catalog writes.
 *
 * <p>Create persists identity only ({@link #persistCreate}, status {@code PENDING}); the physical
 * schema (columns + physical keys) is persisted by {@link #persistSchema} while the table is not yet
 * materialized; the materialization (issued by {@link TableSchemaCreationService}) flips the status via
 * {@link #markStatus}; and live column evolution on an {@code ACTIVE} table uses
 * {@link #appendColumn}/{@link #removeColumn}/{@link #renameColumn}/{@link #updateColumnMetadata}.
 */
@Component
@RequiredArgsConstructor
public class CatalogSchemaWriter {

    private final TableDefinitionRepository definitions;
    private final ColumnMappingRepository columns;
    private final PipelineGenerationBump pipelineGenerations;
    private final TableLimitsProperties limits;
    private final RowPolicyService rowPolicies;

    /**
     * Persists a new table definition as identity only (status {@code PENDING} — no columns, no grain, no
     * ClickHouse table). A source carries just its name/description/type/write discipline/tag ordering; an
     * enrichment additionally carries its (immutable) {@code source_table}. An unspecified write
     * discipline is stored as {@link TableWriteMode#APPEND}, so every definition written before the facet
     * existed reads back with the behaviour it already had. The physical schema is defined and
     * materialized later via {@link #persistSchema} plus the CREATE issued by
     * {@link TableSchemaCreationService}.
     */
    @Transactional
    public void persistCreate(CreateTableInput input) {
        final TableDefinitionEntity definition = new TableDefinitionEntity();
        definition.setName(input.name());
        definition.setDescription(input.description());
        definition.setType(input.type());
        definition.setWriteMode(input.write() == null ? TableWriteMode.APPEND : input.write());
        definition.setStatus(TableStatus.PENDING);
        definition.setSourceTable(input.type() == TableType.ENRICHMENT ? input.sourceTable() : null);
        definition.setTagOrder(TableColumnRules.normalizeTagOrder(input.tagOrder()));
        definitions.save(definition);
    }

    /**
     * Persists the physical schema of a not-yet-materialized table in full — columns plus the physical
     * keys for its kind. Catalog-only (no ClickHouse DDL); the CREATE is issued by
     * {@link TableSchemaCreationService} after this returns. Validates internal consistency (see
     * {@link TableColumnRules}, ordering-key/grain-key column membership, the column cap). Callers ensure
     * the table is modifiable, not yet materialized, and that the schema is complete before invoking.
     */
    @Transactional
    public void persistSchema(String name, DefineSchemaInput draft) {
        final TableDefinitionEntity definition = definitions.findById(name)
                .orElseThrow(() -> new TableNotFoundException("table '" + name + "' not found"));
        requireRowPolicyColumnsKept(definition, draft);
        if (definition.getType() == TableType.ENRICHMENT) {
            persistEnrichmentSchema(definition, draft);
        } else {
            persistSourceSchema(definition, draft);
        }
    }

    /**
     * Refuses a re-defined schema that no longer exposes a column a row policy on the table filters on, with
     * {@code 409 column_in_row_policy} naming the columns and the roles — the same guard a schema PATCH applies
     * to a drop or rename. A not-yet-materialized table can already carry policies (its columns were committed
     * before a failed CREATE), and a policy over a missing column would fail every read by its role.
     */
    private void requireRowPolicyColumnsKept(TableDefinitionEntity definition, DefineSchemaInput draft) {
        final Map<String, Set<String>> policyColumns = rowPolicies.referencedColumns(definition);
        if (policyColumns.isEmpty()) {
            return;
        }
        final Set<String> kept = new HashSet<>();
        if (draft.columns() != null) {
            draft.columns().forEach(col -> kept.add(col.name()));
        }
        if (definition.getType() == TableType.ENRICHMENT && draft.grainKey() != null) {
            kept.add(draft.grainKey());
        }
        final Map<String, Set<String>> missing = new TreeMap<>();
        policyColumns.forEach((column, roles) -> {
            if (!roles.isEmpty() && !kept.contains(column)) {
                missing.put(column, roles);
            }
        });
        if (!missing.isEmpty()) {
            throw new ColumnInRowPolicyException("the schema of table '" + definition.getName() + "' cannot drop "
                    + "column(s) " + missing.keySet() + ": the row policies of role(s) "
                    + missing.values().stream().flatMap(Set::stream).collect(Collectors.toCollection(TreeSet::new))
                    + " filter on them; change or remove those policies first");
        }
    }

    private void persistSourceSchema(TableDefinitionEntity definition, DefineSchemaInput draft) {
        final List<ColumnMappingEntity> rows =
                validatedColumns(definition.getName(), draft.columns(), TableType.SOURCE);
        final Map<String, ColumnMappingEntity> declared = new HashMap<>();
        rows.forEach(row -> declared.put(row.getSourceName(), row));

        final List<String> orderingKey = draft.orderingKey();
        if (orderingKey != null) {
            for (final String key : orderingKey) {
                final ColumnMappingEntity column = declared.get(key);
                if (column == null) {
                    throw new TableValidationException(
                            "ordering_key column '" + key + "' is not a declared column of this table");
                }
                TableColumnRules.requireKeyable("ordering_key", key, column.getType(), column.isNullable());
            }
        }
        if (draft.partitionColumn() != null) {
            final ColumnMappingEntity column = declared.get(draft.partitionColumn());
            if (column == null) {
                throw new TableValidationException("partition_by column '" + draft.partitionColumn()
                        + "' is not a declared column of this table");
            }
            TableColumnRules.requireKeyable("partition_by", draft.partitionColumn(), column.getType(), column.isNullable());
        }
        validateTtl(draft.ttl(), rows);
        ScanMetadataRules.validateSource(definition, draft, rows);

        replaceColumns(definition.getName(), rows);
        definition.setOrderingKey(orderingKey == null ? new ArrayList<>() : new ArrayList<>(orderingKey));
        definition.setPartitionColumn(draft.partitionColumn());
        definition.setPartitionGranularity(draft.partitionGranularity());
        definition.setTtl(draft.ttl());
        // A supplied name replaces the stored one; an omitted one leaves it alone. Leaving it alone (rather
        // than clearing) is what keeps re-posting a FAILED table's schema from silently dropping a value the
        // caller already declared — the completeness gate is what requires a source to supply both.
        if (draft.identityColumn() != null) {
            definition.setIdentityColumn(draft.identityColumn());
        }
        if (draft.versionColumn() != null) {
            definition.setVersionColumn(draft.versionColumn());
        }
        definitions.save(definition);
    }

    private void persistEnrichmentSchema(TableDefinitionEntity definition, DefineSchemaInput draft) {
        if (draft.cardinality() != null && draft.cardinality() != Cardinality.ZERO_OR_ONE) {
            throw new TableValidationException("only zero_or_one cardinality is supported");
        }
        // Refused rather than ignored, which is what it was until an enrichment's partition column became
        // something the query planner acts on. A declaration here claims that this table and its source
        // hold the same value in that column for the same grain key, which lets a grain join restrict this
        // table to the partitions a lookup can be in. Nothing can verify that claim of a table populated by
        // arbitrary row writes, and nothing catches a false one at query time — an over-restricted outer
        // join yields the column's type default, indistinguishable from an unmatched row. So the claim stays
        // available to a migration for a table whose population guarantees it, and a caller that offers one
        // is told no instead of having it quietly dropped.
        if (draft.partitionColumn() != null) {
            throw new TableValidationException("partition_by is not accepted for an enrichment: the "
                    + "declaration asserts that this table and '" + definition.getSourceTable() + "' hold "
                    + "the same value in that column for the same grain key, which cannot be established "
                    + "for a table written through the API");
        }
        final String grainKey = draft.grainKey();
        final List<ColumnMappingEntity> rows =
                validatedColumns(definition.getName(), draft.columns(), TableType.ENRICHMENT);
        final TableDefinitionEntity source = definitions.findById(definition.getSourceTable()).orElse(null);
        final List<ColumnMappingEntity> sourceColumns = columns.findByTableNameOrderByOrdinal(definition.getSourceTable());
        final ColumnMappingEntity alignment = alignment(definition, source, sourceColumns, grainKey, rows);
        // A hidden alignment column is a physical column of the table like any declared one, and a TTL may name
        // it: it is non-nullable however the source declares it, as the grain key is.
        validateTtl(draft.ttl(), rows, alignment == null || !EnrichmentAlignment.isHidden(alignment.getSourceName(), rows)
                ? null : alignment);
        definition.setTtl(draft.ttl());
        if (alignment != null) {
            definition.setPartitionColumn(alignment.getSourceName());
            definition.setPartitionGranularity(source.getPartitionGranularity());
        } else {
            definition.setPartitionColumn(null);
            definition.setPartitionGranularity(null);
        }

        if (grainKey != null && !grainKey.isBlank()) {
            Identifiers.requireUserIdentifier(grainKey);
            // The grain key is a declared column of the source (not necessarily an ordering-key column).
            final ColumnMappingEntity grainColumn = sourceColumn(definition.getSourceTable(), sourceColumns, grainKey);
            // Not grainColumn.isNullable(): the physical column this key is rendered over is the
            // *enrichment's* own grain column, which enrichmentSpec materializes non-nullable whatever the
            // source declares — so there is no nullable ClickHouse key here to refuse.
            TableColumnRules.requireKeyable("grain_key", grainKey, grainColumn.getType(), false);
            if (rows.stream().anyMatch(row -> row.getSourceName().equals(grainKey))) {
                throw new TableValidationException(
                        "column '" + grainKey + "' collides with the grain key of this enrichment");
            }
            definition.setGrainKey(grainKey);
            definition.setOrderingKey(new ArrayList<>(List.of(grainKey)));
        } else {
            // No grain key supplied — clear any prior grain (defensive; the caller requires a grain key).
            definition.setGrainKey(null);
            definition.setOrderingKey(new ArrayList<>());
        }
        replaceColumns(definition.getName(), rows);
        definitions.save(definition);
    }

    /**
     * The source column an enrichment keyed on {@code grainKey} is aligned on (see {@link EnrichmentAlignment}),
     * or null when it does not qualify. A declared column of the same name is adopted when it matches the source
     * column's type, is non-nullable and is neither sensitive nor heavy (see {@link EnrichmentAlignment#adoptable}),
     * and refused otherwise: the table carries one physical column of that name, and it holds the source row's value.
     */
    private ColumnMappingEntity alignment(TableDefinitionEntity definition, TableDefinitionEntity source,
            List<ColumnMappingEntity> sourceColumns, String grainKey, List<ColumnMappingEntity> rows) {
        if (grainKey == null || grainKey.isBlank()) {
            return null;
        }
        final String column = EnrichmentAlignment.eligibleColumn(grainKey, source);
        if (column == null) {
            return null;
        }
        final ColumnMappingEntity sourceColumn = sourceColumn(definition.getSourceTable(), sourceColumns, column);
        EnrichmentAlignment.physicalColumn(rows, column)
                .filter(row -> !EnrichmentAlignment.adoptable(row, sourceColumn))
                .ifPresent(row -> {
                    throw alignmentCollision(column, definition.getSourceTable(), sourceColumn);
                });
        return sourceColumn;
    }

    /**
     * The refusal of a declared column named like the alignment column that cannot be adopted. Shared with the
     * {@code add} and {@code rename} of {@code PATCH .../schema}, which refuse the same column before it exists.
     */
    static TableValidationException alignmentCollision(String column, String sourceTable, ColumnMappingEntity sourceColumn) {
        return new TableValidationException("column '" + column + "' collides with the alignment column of "
                + "this enrichment: ADAS partitions it like '" + sourceTable + "' and writes "
                + "'" + column + "' from the source row, so a declared column of that name must be a "
                + "non-nullable, non-sensitive, non-heavy " + sourceColumn.getType().name().toLowerCase(Locale.ROOT)
                + " or be left out");
    }

    /**
     * Validates the columns and builds their (unpersisted) mapping rows at ordinals 0..n.
     * {@code kind} drives the nullability default (source columns non-nullable, enrichment columns
     * nullable) when a column does not state {@code nullable} explicitly.
     */
    private List<ColumnMappingEntity> validatedColumns(String tableName, List<ColumnInput> cols, TableType kind) {
        final List<ColumnInput> input = cols == null ? List.of() : cols;
        TableColumnRules.requireColumnCount(input.size(), limits.maxColumnsPerTable());
        final Set<String> sourceNames = new HashSet<>();
        final Set<String> exposedNames = new HashSet<>();
        final List<ColumnMappingEntity> rows = new ArrayList<>(input.size());
        for (int i = 0; i < input.size(); i++) {
            final ColumnInput col = input.get(i);
            TableColumnRules.requireValidUserColumn(col);
            if (!sourceNames.add(col.sourceName())) {
                throw new TableValidationException("duplicate source_name '" + col.sourceName() + "'");
            }
            if (!exposedNames.add(col.name())) {
                throw new TableValidationException("duplicate exposed name '" + col.name() + "'");
            }
            rows.add(columnEntity(tableName, col, TableColumnRules.resolveNullable(col, kind), i));
        }
        return rows;
    }

    /**
     * Replaces all column mappings for {@code tableName} with {@code rows} (full replace semantics).
     *
     * <p>Deliberately left as delete-all-then-insert-all now that the catalog is audited. Envers merges
     * a delete and an insert of the same entity id within one revision (see
     * {@code DelWorkUnit.merge(AddWorkUnit)}), so this cannot collide on the audit table's
     * {@code (rev, table_name, source_name)} primary key.
     *
     * <p>It does, however, record an {@code Update} for <em>every</em> column re-stated by a re-post,
     * including unchanged ones — the merge yields a modification rather than nothing, because the state
     * arrays it compares are not equal across a delete-then-insert of a fresh instance. That coarseness
     * is accepted and stated in the {@code audit-trail} spec rather than papered over. Reconciling
     * row-by-row instead was tried and rejected: it would make the feed minimal but breaks a case this
     * handles, since two columns swapping exposed names violate {@code exposed_name_unique_per_table}
     * mid-flush where deleting both first does not.
     */
    private void replaceColumns(String tableName, List<ColumnMappingEntity> rows) {
        columns.deleteByTableName(tableName);
        columns.flush();
        if (!rows.isEmpty()) {
            columns.saveAll(rows);
        }
    }

    /** Validates a (nullable) TTL declaration against the table's declared column rows. */
    private void validateTtl(TtlInput ttl, List<ColumnMappingEntity> rows) {
        validateTtl(ttl, rows, null);
    }

    /** As {@link #validateTtl(TtlInput, List)}, also admitting the hidden alignment column {@code hidden}. */
    private void validateTtl(TtlInput ttl, List<ColumnMappingEntity> rows, ColumnMappingEntity hidden) {
        if (ttl == null) {
            return;
        }
        final Map<String, TableTtlRules.PhysicalColumn> physical = new HashMap<>();
        rows.forEach(row -> physical.put(row.getSourceName(),
                new TableTtlRules.PhysicalColumn(row.getType(), row.isNullable())));
        if (hidden != null) {
            physical.put(hidden.getSourceName(), new TableTtlRules.PhysicalColumn(hidden.getType(), false));
        }
        TableTtlRules.requireValid(ttl, physical);
    }

    /** One rename of a live patch: the physical column name now, and the new name. */
    public record RenameWrite(String fromSourceName, String to) {}

    /** One column append of a live patch: the validated column, its resolved nullability, and ordinal. */
    public record AppendWrite(ColumnInput column, boolean nullable, int ordinal) {}

    /** One column-metadata merge-patch of a live patch (see {@link #updateColumnMetadata}). */
    public record MetadataWrite(String exposedName, Change<String> tag, Change<String> displayName,
            Change<String> description, Change<Boolean> sensitive, Change<Boolean> heavy) {}

    /**
     * Applies all catalog changes of one live schema PATCH in a single transaction, after the
     * orchestrating service has issued the (single, all-or-nothing) ClickHouse {@code ALTER}: column
     * drops, renames (a rename of the TTL-referenced column also renames the stored TTL column,
     * mirroring ClickHouse rewriting the physical TTL expression), appends, metadata updates, and the
     * TTL merge-patch (a present {@code ttl} change sets or — with a null value — clears it).
     *
     * <p>A rename of a column the scan-metadata pair names repoints the stored
     * {@code identity_column}/{@code version_column} the same way, and for the same reason: the physical
     * column survives the rename with its data, so the catalog should keep pointing at it rather than at a
     * name that no longer resolves. A repointed {@code version_column} additionally bumps the generation of
     * every ENRICH pipeline reading this source ({@link PipelineGenerationBump}) — inside this transaction, so
     * the new name
     * and the signal to go and read it cannot be committed separately.
     */
    @Transactional
    public void applyPatch(String tableName, List<String> drops, List<RenameWrite> renames,
            List<AppendWrite> adds, List<MetadataWrite> updates, Change<TtlInput> ttl) {
        final TableDefinitionEntity definition = definitions.findById(tableName)
                .orElseThrow(() -> new TableNotFoundException("table '" + tableName + "' not found"));
        for (final String drop : drops) {
            removeColumn(tableName, drop);
        }
        boolean versionRepointed = false;
        for (final RenameWrite rename : renames) {
            renameColumn(tableName, rename.fromSourceName(), rename.to());
            if (rename.fromSourceName().equals(definition.getTtlColumn())) {
                definition.setTtlColumn(rename.to());
            }
            if (rename.fromSourceName().equals(definition.getIdentityColumn())) {
                definition.setIdentityColumn(rename.to());
            }
            if (rename.fromSourceName().equals(definition.getVersionColumn())) {
                definition.setVersionColumn(rename.to());
                versionRepointed = true;
            }
        }
        for (final AppendWrite add : adds) {
            appendColumn(tableName, add.column(), add.nullable(), add.ordinal());
        }
        // Read BEFORE the loop that overwrites them: the bump below turns on whether a description
        // actually CHANGED, and after the loop every stored value already equals what was supplied.
        final Map<String, String> descriptionsBefore = storedDescriptions(tableName, updates);
        for (final MetadataWrite update : updates) {
            updateColumnMetadata(tableName, update.exposedName(), update.tag(), update.displayName(),
                    update.description(), update.sensitive(), update.heavy());
        }
        if (ttl.present()) {
            definition.setTtl(ttl.value());
        }
        definitions.save(definition);
        if (versionRepointed) {
            pipelineGenerations.onVersionColumnRepointed(tableName);
        }
        // A column ADDED to, DROPPED from or RENAMED on an enrichment changes what a pipeline targeting it
        // composes and writes: an output's type, enum domain and enum order all come from its column, and
        // the output mapping is derived by matching output names to columns.
        //
        // A metadata update that CHANGES a description is in this condition too, and that inverts the rule
        // this comment used to state. An output's `prose` is optional now and defaults to the target
        // column's `description` at composition time, so the description is a live input to the composed
        // prompt: a patch that edits it changes what the model is told, and a consumer that never re-read
        // the feed would keep sending the old prompt. The rest of a metadata patch still bumps nothing — a
        // display_name, tag, sensitive or heavy change reaches neither the schema nor the mapping — and
        // `onTargetColumnsChanged` is itself `llm`-scoped, so no Class-1 materialized position is
        // discarded by this widening.
        //
        // CHANGED, not SUPPLIED, and the difference is not pedantry: Change.present means "the caller sent
        // this member", so testing presence alone would bump on every run of an idempotent authoring or
        // seed script that re-PATCHes the same metadata. A bump is not free — it re-syncs the runner,
        // stalls the rollups reading this enrichment, and (the runner keys its work-item ids on
        // `generation`) drops whatever that pipeline has in flight — so paying it for a no-op write is a
        // recurring cost for no signal. The comparison is against the values read before the loop above
        // overwrote them, and it is deliberately NOT narrowed further to the columns an output binds: the
        // catalog writer does not read pipeline declarations, and over-bumping a description edit on a
        // column no output binds is the safe side of a line only `onTargetColumnsChanged` can draw.
        final boolean descriptionChanged = updates.stream()
                .anyMatch(u -> u.description().present()
                        && !Objects.equals(descriptionsBefore.get(u.exposedName()), u.description().value()));
        if (definition.getType() == TableType.ENRICHMENT
                && !(adds.isEmpty() && drops.isEmpty() && renames.isEmpty() && !descriptionChanged)) {
            pipelineGenerations.onTargetColumnsChanged(tableName);
        }
    }

    /**
     * The descriptions the patched columns carry <em>now</em>, keyed by exposed name — read once for the
     * whole patch, and only for the columns a metadata update names.
     *
     * <p>A column an update names but the catalog does not hold is absent from the map rather than
     * refused here: {@link #updateColumnMetadata} is the method that owns that refusal, and reporting it
     * twice with two messages would be the drift this reads in one place to avoid. Its absence maps to a
     * null "before" value, which compares unequal to anything but a null "after" — so the unknown column
     * errs towards bumping, and then throws a moment later anyway.
     */
    private Map<String, String> storedDescriptions(String tableName, List<MetadataWrite> updates) {
        if (updates.isEmpty()) {
            return Map.of();
        }
        final Set<String> patched = updates.stream()
                .map(MetadataWrite::exposedName)
                .collect(Collectors.toSet());
        final Map<String, String> before = new HashMap<>();
        for (final ColumnMappingEntity column : columns.findByTableNameOrderByOrdinal(tableName)) {
            if (patched.contains(column.getName())) {
                before.put(column.getName(), column.getDescription());
            }
        }
        return before;
    }

    /** Appends one already-validated column to the mapping at the next ordinal. */
    @Transactional
    public void appendColumn(String tableName, ColumnInput col, boolean nullable, int ordinal) {
        columns.save(columnEntity(tableName, col, nullable, ordinal));
    }

    /** Removes the column with physical name {@code sourceName}. */
    @Transactional
    public void removeColumn(String tableName, String sourceName) {
        columns.findByTableNameOrderByOrdinal(tableName).stream()
                .filter(col -> col.getSourceName().equals(sourceName))
                .findFirst()
                .ifPresent(columns::delete);
    }

    /**
     * Renames the column with physical name {@code fromSourceName} to {@code to} (both physical and
     * exposed name). The composite PK includes {@code source_name}, so this is a delete-then-insert that
     * preserves the column's type, element type, enum domain, nullability, description, display name,
     * tag, sensitivity, heaviness, and ordinal. Every field is copied by hand, so one added to the entity
     * and forgotten here is silently dropped by a rename.
     */
    @Transactional
    public void renameColumn(String tableName, String fromSourceName, String to) {
        final ColumnMappingEntity existing = columns.findByTableNameOrderByOrdinal(tableName).stream()
                .filter(col -> col.getSourceName().equals(fromSourceName))
                .findFirst()
                .orElseThrow(() -> new TableValidationException(
                        "column '" + fromSourceName + "' is not a column of table '" + tableName + "'"));
        final ColumnMappingEntity renamed = new ColumnMappingEntity();
        renamed.setTableName(tableName);
        renamed.setSourceName(to);
        renamed.setName(to);
        renamed.setType(existing.getType());
        renamed.setElementType(existing.getElementType());
        renamed.setEnumValues(existing.getEnumValues());
        renamed.setNullable(existing.isNullable());
        renamed.setDescription(existing.getDescription());
        renamed.setDisplayName(existing.getDisplayName());
        renamed.setTag(existing.getTag());
        renamed.setSensitive(existing.isSensitive());
        renamed.setHeavy(existing.isHeavy());
        renamed.setOrdinal(existing.getOrdinal());
        columns.delete(existing);
        columns.flush();
        columns.save(renamed);
    }

    /**
     * Applies a metadata merge-patch to the column currently exposed as {@code exposedName}: each
     * <em>present</em> {@link Change} sets that attribute (a {@code null} value clears a string
     * attribute), while a "leave" change is a no-op. Catalog-only metadata — no ClickHouse DDL is
     * involved. Loads and saves the row once, regardless of how many attributes change.
     */
    @Transactional
    public void updateColumnMetadata(String tableName, String exposedName, Change<String> tag,
            Change<String> displayName, Change<String> description, Change<Boolean> sensitive,
            Change<Boolean> heavy) {
        final ColumnMappingEntity col = columns.findByTableNameOrderByOrdinal(tableName).stream()
                .filter(c -> c.getName().equals(exposedName))
                .findFirst()
                .orElseThrow(() -> new TableValidationException(
                        "column '" + exposedName + "' is not a column of table '" + tableName + "'"));
        if (tag.present()) {
            col.setTag(tag.value());
        }
        if (displayName.present()) {
            col.setDisplayName(displayName.value());
        }
        if (description.present()) {
            col.setDescription(description.value());
        }
        if (sensitive.present()) {
            col.setSensitive(sensitive.value());
        }
        if (heavy.present()) {
            col.setHeavy(heavy.value());
        }
        columns.save(col);
    }

    /**
     * Replaces the table's per-table tag ordering with {@code tags} (already validated/normalized); a
     * null or empty list clears it (stored as SQL {@code NULL} = "not set"). Table-level catalog-only
     * metadata — no ClickHouse DDL is involved.
     */
    @Transactional
    public void setTagOrder(String tableName, List<String> tags) {
        final TableDefinitionEntity definition = definitions.findById(tableName)
                .orElseThrow(() -> new TableNotFoundException("table '" + tableName + "' not found"));
        definition.setTagOrder(tags == null || tags.isEmpty() ? null : tags);
        definitions.save(definition);
    }

    @Transactional
    public TableDefinitionEntity markStatus(String name, TableStatus status) {
        final TableDefinitionEntity definition = definitions.findById(name)
                .orElseThrow(() -> new TableNotFoundException("table '" + name + "' not found"));
        definition.setStatus(status);
        return definitions.save(definition);
    }

    private ColumnMappingEntity columnEntity(String tableName, ColumnInput col, boolean nullable, int ordinal) {
        final ColumnMappingEntity entity = new ColumnMappingEntity();
        entity.setTableName(tableName);
        entity.setSourceName(col.sourceName());
        entity.setName(col.name());
        entity.setType(col.type());
        entity.setElementType(col.elementType());
        entity.setEnumValues(TableColumnRules.normalizeEnumValues(col.enumValues()));
        entity.setNullable(nullable);
        entity.setDescription(TableColumnRules.normalizeDescription(col.description()));
        entity.setDisplayName(TableColumnRules.normalizeDisplayName(col.displayName()));
        entity.setTag(TableColumnRules.normalizeTag(col.tag()));
        entity.setSensitive(col.sensitive());
        entity.setHeavy(col.heavy());
        entity.setOrdinal(ordinal);
        return entity;
    }

    private static ColumnMappingEntity sourceColumn(String sourceTable, List<ColumnMappingEntity> sourceColumns,
            String columnName) {
        return EnrichmentAlignment.physicalColumn(sourceColumns, columnName)
                .orElseThrow(() -> new TableValidationException(
                        "column '" + columnName + "' is not a column of source '" + sourceTable + "'"));
    }
}
