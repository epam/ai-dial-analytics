package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.clickhouse.ClickHouseDdlExecutor;
import com.epam.aidial.analytics.clickhouse.ClickHouseTableInspector;
import com.epam.aidial.analytics.configuration.TableLimitsProperties;
import com.epam.aidial.analytics.exception.ColumnInRowPolicyException;
import com.epam.aidial.analytics.exception.SensitiveClearanceRequiredException;
import com.epam.aidial.analytics.exception.TableConflictException;
import com.epam.aidial.analytics.exception.TableNotFoundException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.enrichment.support.ProvenanceColumns;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccess;
import com.epam.aidial.analytics.service.table.PatchInput.Rename;
import com.epam.aidial.analytics.service.table.TableDdlRenderer.DdlColumn;
import com.epam.aidial.analytics.service.table.grant.SensitiveGrantInvariant;
import com.epam.aidial.analytics.service.table.relayout.RelayoutStateStore;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyService;
import com.epam.aidial.analytics.web.security.Privilege;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Orchestrates {@code PATCH /v1/tables/{name}/schema}, the <b>live</b>-schema surface: it evolves a
 * <em>materialized</em> ({@code ACTIVE}) table's columns and row-expiry TTL. A not-yet-materialized
 * table is rejected with 409 (its schema is defined and materialized via
 * {@link TableSchemaCreationService}). Operations are validated up front against the running post-op
 * state; all physical commands (drop → rename → add → ttl) are then issued as a <b>single
 * multi-command</b> ClickHouse {@code ALTER} statement (writer credential) that ClickHouse validates
 * as a whole — rejected or applied in its entirety — after which every catalog change (column mapping,
 * metadata updates, TTL) is committed in one transaction (via {@link CatalogSchemaWriter#applyPatch}).
 * Type changes, and drops/renames of system ({@code _}-prefixed) columns, the grain key, or
 * ordering-key columns, are rejected (422); so is a drop of the column the stored TTL references
 * (remove or repoint the TTL first) — while a rename of it is allowed (ClickHouse rewrites the
 * physical TTL expression, and the stored TTL column follows). A ClickHouse {@code ALTER} failure
 * surfaces as an error and leaves the table {@code ACTIVE} with the catalog untouched — it never
 * transitions to {@code FAILED} (that status is reserved for a failed materialization).
 *
 * <p><b>Drop and rename are not the same rule.</b> A source's scan-metadata column may be renamed —
 * {@link CatalogSchemaWriter#applyPatch} repoints the stored name in the same transaction, as it already
 * does for the TTL column — but not dropped, since nothing repoints the pair to a different column (see
 * {@link ScanMetadataRules#requireDroppable}). Conversely a source column that any <em>materialized</em>
 * enrichment of that source is keyed on is immutable to both: the auto-join fires on the shared physical
 * name, and the enrichment's half of that name is its ordering key, which neither this endpoint nor
 * ClickHouse will rename — so the pair of names cannot be kept in step and a break would have no repair.
 * The {@code update} operation stays free of mutability restrictions with one exception:
 * {@code sensitive} on a cursor column ({@link ScanMetadataRules#requireNotSensitive}).
 *
 * <p><b>Sensitivity needs the clearance on top of this endpoint's own privilege.</b> Disclosure
 * monotonicity governs what a privilege reveals; it does not govern what a privilege lets its holder
 * <em>change in order to reveal more</em>, and clearing the flag is exactly that. So a caller without
 * {@link Privilege#TABLE_SENSITIVE} on the table works on the table's <b>visible</b> columns only — a
 * sensitive column is unknown to it, as it is on {@code GET /v1/tables/{name}} and on the query surface —
 * and may not declare or change any column's flag (403). A caller holding both {@code TABLE_MODIFY} and
 * the clearance may do all of it: it already sees the values it would be reclassifying, so no change here
 * gives it a read it did not have. That is what lets an operator delegate a table outright through its
 * grant lists; the previous gate, global-only {@code TABLE_GRANT}, could not be delegated at all.
 *
 * <p>A name collision with a hidden column on {@code add} or {@code rename.to} is still refused (422,
 * already exists), because the physical table has the column: that discloses a taken name, the accepted
 * limit of hiding — column names are not a secrecy guarantee.
 */
@Service
@RequiredArgsConstructor
public class TableSchemaService {

    private final TableDefinitionRepository definitions;
    private final ColumnMappingRepository columns;
    private final SensitiveColumnAccess sensitiveAccess;
    private final SensitiveGrantInvariant sensitiveGrants;
    private final CatalogSchemaWriter writer;
    private final TableCatalogService catalogService;
    private final ClickHouseDdlExecutor ddlExecutor;
    private final TableDdlRenderer ddlRenderer;
    private final ClickHouseTypeMapper typeMapper;
    private final TableLimitsProperties limits;
    private final PipelineInputDependents pipelineReads;
    private final RowPolicyService rowPolicies;
    private final ClickHouseTableInspector inspector;
    private final RelayoutStateStore relayouts;

    /** A resolved add: the declared column, its rendered DDL type, and its target ordinal. */
    private record ResolvedAdd(ColumnInput column, String ddlType, int ordinal) {}

    /** A resolved rename: the physical column name now, and the new name. */
    private record ResolvedRename(String fromSourceName, String to) {}

    /**
     * A resolved metadata update: the (post-op) exposed column name, and the normalized {@link Change}
     * for each metadata attribute (string changes carry null to clear; a "leave" change is a no-op).
     */
    private record ResolvedMetadataUpdate(
            String exposedName, Change<String> tag, Change<String> displayName,
            Change<String> description, Change<Boolean> sensitive, Change<Boolean> heavy) {}

    public TableView patchSchema(String name, PatchInput patch) {
        final TableDefinitionEntity definition = definitions.findById(name)
                .orElseThrow(() -> new TableNotFoundException("table '" + name + "' not found"));
        definition.requireModifiable();
        // PATCH is the live-schema surface: it emits immediate ClickHouse ALTERs, so it applies only to a
        // materialized table. A not-yet-materialized table's schema is defined and materialized via
        // POST .../schema; reject with 409 to point the caller there.
        if (!definition.isMaterialized()) {
            throw new TableConflictException("table '" + name + "' is not materialized; define and "
                    + "materialize its schema via POST /v1/tables/{name}/schema before evolving its live schema");
        }
        // A relayout copies the columns and TTL it planned from at its start into a new table that replaces this one:
        // an ALTER now would reach only the table being replaced, and a dropped column would fail the next copy.
        if (relayouts.find(name).map(state -> state.step().inProgress()).orElse(false)) {
            throw new TableConflictException("table '" + name + "' is being relaid out by ADAS (layout: migrating); "
                    + "retry the schema change once GET /v1/tables/{name} reports layout: aligned");
        }

        final List<ColumnMappingEntity> current = columns.findByTableNameOrderByOrdinal(name);
        // Fails open in none mode, like every read of the clearance.
        final boolean cleared = sensitiveAccess.maySeeSensitive(name);
        // What the operations may NAME — drop, rename.from, update, ttl.column: the caller-visible columns,
        // the same set GET /v1/tables/{name} returns to it. Everything that asks what the TABLE holds —
        // name collisions, the column limit — uses the full sets below, since the physical table and the
        // catalog's uniqueness constraints have every column whoever is asking.
        final Map<String, ColumnMappingEntity> byExposed = new LinkedHashMap<>();
        final Set<String> hidden = new LinkedHashSet<>();
        final Set<String> hiddenPhysical = new LinkedHashSet<>();
        for (final ColumnMappingEntity col : current) {
            if (col.isSensitive() && !cleared) {
                hidden.add(col.getName());
                hiddenPhysical.add(col.getSourceName());
            } else {
                byExposed.put(col.getName(), col);
            }
        }
        // Null unless this is an enrichment whose schema has been defined: the grain_key_only_on_enrichment
        // check makes the column null on every source, so the kind test the separate table needed is gone.
        final String grainKey = definition.getGrainKey();
        // The other side of the same invariant: when the patched table is the source, the columns its
        // materialized enrichments are keyed on. Resolved once per request so the drop and rename loops
        // judge against the same facts.
        final Map<String, String> keyedEnrichments = definition.getType() == TableType.ENRICHMENT
                ? Map.of()
                : enrichmentsKeyedOn(name);

        // Resolve + validate every operation against the running post-op state before issuing any DDL.
        // Every exposed name, hidden ones included: seeding this from the visible set alone let an add or a
        // rename.to take a hidden column's exposed name, which ClickHouse accepted (the physical names
        // differ) and the catalog's per-table exposed-name constraint then refused — a 500 after the ALTER,
        // leaving the two out of step.
        final Set<String> exposed = new LinkedHashSet<>();
        current.forEach(col -> exposed.add(col.getName()));
        final Set<String> physical = new LinkedHashSet<>();
        current.forEach(col -> physical.add(col.getSourceName()));
        // A hidden alignment column is physical though absent from the mapping, so an add may not take its name.
        final String alignment = definition.getType() == TableType.ENRICHMENT ? definition.getPartitionColumn() : null;
        final boolean hiddenAlignment = alignment != null && physical.add(alignment);
        // An eligible enrichment not laid out aligned yet — its relayout is waiting out the grace delay, or running:
        // a column of the alignment column's name added or renamed in now is one the relayout must adopt, so it is
        // refused unless it can be. Otherwise the relayout would find it unadoptable and leave the table unsupported.
        final PendingAlignment pendingAlignment = pendingAlignment(definition, grainKey, alignment);
        // Exposed name -> physical name as this request leaves it. An `update` is keyed by exposed name
        // while the scan-metadata pair names physical ones, and the two spellings may differ.
        final Map<String, String> physicalByExposed = new LinkedHashMap<>();
        current.forEach(col -> physicalByExposed.put(col.getName(), col.getSourceName()));

        // The columns the table's row policies filter on, by exposed name. A policy over a column that no longer
        // exists fails every read of the table once enforced, so neither verb may take one away.
        final Map<String, Set<String>> policyColumns = rowPolicies.referencedColumns(definition);

        final List<String> drops = new ArrayList<>();
        for (final String drop : patch.drop()) {
            final ColumnMappingEntity col = requireColumn(byExposed, drop);
            requireNoRowPolicy(name, col.getName(), policyColumns, "dropped");
            requireMutable(col.getSourceName(), definition, grainKey, keyedEnrichments);
            ScanMetadataRules.requireDroppable(definition, col.getSourceName());
            // The TTL-referenced column is undroppable while the TTL stands (ClickHouse itself rejects
            // the DROP): the caller removes or repoints the TTL in a prior request, then drops.
            if (col.getSourceName().equals(definition.getTtlColumn())) {
                throw new TableValidationException("column '" + col.getSourceName()
                        + "' is referenced by the table TTL; remove or repoint the TTL first");
            }
            if (!exposed.remove(drop)) {
                throw new TableValidationException("duplicate drop of column '" + drop + "'");
            }
            requireNoPipelineReads(definition, col.getName(), "dropped");
            requireNoPipelineWrites(definition, col.getSourceName(), "dropped");
            physical.remove(col.getSourceName());
            physicalByExposed.remove(drop);
            drops.add(col.getSourceName());
        }

        final List<ResolvedRename> renames = new ArrayList<>();
        for (final Rename rename : patch.rename()) {
            final ColumnMappingEntity col = requireColumn(byExposed, rename.from());
            requireNoRowPolicy(name, col.getName(), policyColumns, "renamed");
            // No scan-metadata check here: unlike a drop, a rename has a repoint (below, and in
            // CatalogSchemaWriter.applyPatch), and it preserves every property the pair was verified for.
            requireMutable(col.getSourceName(), definition, grainKey, keyedEnrichments);
            // A rename moves the stamped values under the new name while the write path goes on stamping the
            // canonical one, which the target no longer carries — so lineage stops with no error. A drop stays
            // allowed: it is an explicit opt-out, and the only one a Class-1 target's always-empty columns have.
            if (definition.getType() == TableType.ENRICHMENT && ProvenanceColumns.NAMES.contains(col.getSourceName())) {
                throw new TableValidationException("provenance column '" + col.getSourceName()
                        + "' cannot be renamed: the pipeline stamps it by this name");
            }
            // The converse: a column renamed onto a freed provenance name would have the pipeline's stamps
            // written over its own values, coerced to whatever type it was declared with.
            if (definition.getType() == TableType.ENRICHMENT && ProvenanceColumns.NAMES.contains(rename.to())) {
                throw new TableValidationException("column '" + rename.from() + "' cannot be renamed to '"
                        + rename.to() + "': the pipeline stamps a provenance column by that name");
            }
            Identifiers.requireUserIdentifier(rename.to());
            if (!exposed.remove(rename.from())) {
                throw new TableValidationException("duplicate rename of column '" + rename.from() + "'");
            }
            physical.remove(col.getSourceName());
            if (!exposed.add(rename.to()) || !physical.add(rename.to())) {
                throw new TableValidationException("rename target '" + rename.to() + "' collides with another column");
            }
            if (pendingAlignment != null && rename.to().equals(pendingAlignment.column())
                    && !EnrichmentAlignment.adoptable(col, pendingAlignment.sourceColumn())) {
                throw pendingAlignment.collision();
            }
            requireNoPipelineReads(definition, rename.from(), "renamed");
            // And the name the rename takes over. Freeing a read name is only half of a repoint: the other
            // half is another column moving into it, which is how a `sensitive` column can be given the
            // name an expression already reads without the declaration or the flag changing.
            requireNoPipelineReads(definition, rename.to(), "renamed onto");
            requireNoPipelineWrites(definition, col.getSourceName(), "renamed");
            // A rename sets both spellings to the new name (CatalogSchemaWriter.renameColumn).
            physicalByExposed.remove(rename.from());
            physicalByExposed.put(rename.to(), rename.to());
            renames.add(new ResolvedRename(col.getSourceName(), rename.to()));
        }

        int nextOrdinal = current.stream().mapToInt(ColumnMappingEntity::getOrdinal).max().orElse(-1) + 1;
        final List<ResolvedAdd> adds = new ArrayList<>();
        boolean addsSensitiveColumn = false;
        for (final ColumnInput declared : patch.add()) {
            TableColumnRules.requireValidUserColumn(declared);
            if (Boolean.TRUE.equals(declared.sensitive()) && !cleared) {
                throw clearanceRequired(name, "declaring column '" + declared.name() + "' sensitive");
            }
            // Both writes below have to render from the same list. The catalog row is written from the
            // normalized (trimmed) domain, so rendering the ALTER from the raw declaration would materialize
            // Enum8(' high ' = 1) while GET /v1/tables/{name} and discovery advertise 'high' — a column that
            // refuses the value it publishes, and unrepairable, since enum_values is immutable by design.
            final ColumnInput add = TableColumnRules.withNormalizedEnumValues(declared);
            if (hiddenAlignment && add.sourceName().equals(alignment)) {
                throw new TableValidationException("column '" + alignment + "' collides with the alignment column of "
                        + "this enrichment, which ADAS writes from the source row");
            }
            if (!physical.add(add.sourceName())) {
                throw new TableValidationException("column '" + add.sourceName() + "' already exists");
            }
            if (!exposed.add(add.name())) {
                throw new TableValidationException("exposed name '" + add.name() + "' already exists");
            }
            physicalByExposed.put(add.name(), add.sourceName());
            final boolean nullable = TableColumnRules.resolveNullable(add, definition.getType());
            if (pendingAlignment != null && add.sourceName().equals(pendingAlignment.column())
                    && !EnrichmentAlignment.adoptable(add.type(), add.elementType(), nullable, add.sensitive(), add.heavy(),
                    pendingAlignment.sourceColumn())) {
                throw pendingAlignment.collision();
            }
            addsSensitiveColumn |= Boolean.TRUE.equals(add.sensitive());
            adds.add(new ResolvedAdd(add,
                    typeMapper.toDdlType(add.type(), add.elementType(), add.enumValues(), nullable), nextOrdinal++));
        }
        if (addsSensitiveColumn) {
            // An `add` that declares the column sensitive is the other way an enrichment first comes to
            // carry a derived sensitive column, so the subset rule has to run here too. Missing it left the
            // whole invariant bypassable: grant `sensitive` on an enrichment while it has no sensitive
            // column (accepted, nothing to protect), then add one.
            // (Declaring the column sensitive at all needed the clearance, checked in the loop above.)
            sensitiveGrants.requireNotWidenedWithNewSensitiveColumn(definition);
        }

        if (exposed.size() > limits.maxColumnsPerTable()) {
            throw new TableValidationException(
                    "a table may declare at most " + limits.maxColumnsPerTable() + " columns");
        }

        // The scan-metadata pair as this request leaves it: a rename repoints the stored name (the write
        // itself is in CatalogSchemaWriter.applyPatch), so an update in the same request is judged against
        // the post-rename spelling rather than the stored one.
        String identityColumn = definition.getIdentityColumn();
        String versionColumn = definition.getVersionColumn();
        for (final ResolvedRename rename : renames) {
            if (rename.fromSourceName().equals(identityColumn)) {
                identityColumn = rename.to();
            }
            if (rename.fromSourceName().equals(versionColumn)) {
                versionColumn = rename.to();
            }
        }

        // Metadata updates resolve against the post-op exposed names, so an update may target a
        // just-added or just-renamed column. They are metadata only (no ALTER) and carry no mutability
        // restriction — system, grain, and ordering-key columns may all be given metadata — with one
        // exception: `sensitive` on a cursor column, which the scan reads (see ScanMetadataRules).
        final List<ResolvedMetadataUpdate> updates = new ArrayList<>();
        final Set<String> updated = new LinkedHashSet<>();
        boolean marksColumnSensitive = false;
        for (final PatchInput.ColumnMetadataUpdate update : patch.update()) {
            if (!exposed.contains(update.name()) || hidden.contains(update.name())) {
                throw new TableValidationException("unknown column '" + update.name() + "'");
            }
            if (!updated.add(update.name())) {
                throw new TableValidationException("duplicate update of column '" + update.name() + "'");
            }
            // The adopted alignment column — or the one an eligible enrichment's relayout will adopt — stays
            // adoptable: the system writes the source row's partition value into it (see EnrichmentAlignment#adoptable).
            final String physicalName = physicalByExposed.get(update.name());
            if ((Boolean.TRUE.equals(update.sensitive().value()) || Boolean.TRUE.equals(update.heavy().value()))
                    && (physicalName.equals(alignment) || pendingAlignment != null && physicalName.equals(pendingAlignment.column()))) {
                throw new TableValidationException("column '" + update.name() + "' is the alignment column of enrichment '"
                        + name + "' and cannot be marked sensitive or heavy: ADAS writes the source row's partition value "
                        + "into it");
            }
            if (update.sensitive().present()) {
                if (!cleared) {
                    throw clearanceRequired(name, "changing the 'sensitive' flag of column '" + update.name() + "'");
                }
                if (Boolean.TRUE.equals(update.sensitive().value())) {
                    ScanMetadataRules.requireNotSensitive(
                            identityColumn, versionColumn, physicalByExposed.get(update.name()));
                    // Marking an enrichment column sensitive is one of the moments its grants first have
                    // something to launder, so the subset rule is checked here as well as on a grant edit.
                    // Accumulated rather than checked inline, like the `add` branch above: the rule's
                    // verdict is a property of the pair, not of the column, and it parses every stored
                    // declaration to find the grain-join edge — running it once per patched column would
                    // multiply that scan for an answer that cannot differ.
                    marksColumnSensitive = true;
                }
            }
            updates.add(new ResolvedMetadataUpdate(
                    update.name(),
                    normalize(update.tag(), TableColumnRules::normalizeTag),
                    normalize(update.displayName(), TableColumnRules::normalizeDisplayName),
                    normalize(update.description(), TableColumnRules::normalizeDescription),
                    update.sensitive(),
                    update.heavy()));
        }
        if (marksColumnSensitive && !addsSensitiveColumn) {
            // Once for the whole patch, and skipped when the `add` branch above already ran it: both ask
            // the same question of the same pair.
            sensitiveGrants.requireNotWidenedWithNewSensitiveColumn(definition);
        }

        // Resolve the TTL merge-patch against the post-op physical columns (type + nullability), so a
        // just-added or just-renamed column is referenceable. A removal when no TTL is stored is an
        // idempotent no-op (no REMOVE TTL command is emitted).
        //
        // A caller without the clearance resolves against the visible columns only: a TTL over a hidden
        // column would let its validation errors reveal the column's type and nullability, and an accepted one
        // would expire rows by sensitive values — observable afterwards through row counts. For the same reason
        // such a caller may not change a stored TTL that is on a hidden column, in either direction.
        if (patch.ttl().present() && !cleared && hiddenPhysical.contains(definition.getTtlColumn())) {
            throw clearanceRequired(name, "changing a TTL that is on a sensitive column");
        }
        final Map<String, TableTtlRules.PhysicalColumn> physicalTypes = new LinkedHashMap<>();
        for (final ColumnMappingEntity col : current) {
            if (!hiddenPhysical.contains(col.getSourceName())) {
                physicalTypes.put(col.getSourceName(), new TableTtlRules.PhysicalColumn(col.getType(), col.isNullable()));
            }
        }
        if (hiddenAlignment) {
            // The hidden alignment column carries its source column's type, non-nullable like the grain key.
            EnrichmentAlignment.physicalColumn(columns.findByTableNameOrderByOrdinal(definition.getSourceTable()), alignment)
                    .ifPresent(col -> physicalTypes.put(alignment, new TableTtlRules.PhysicalColumn(col.getType(), false)));
        }
        drops.forEach(physicalTypes::remove);
        for (final ResolvedRename rename : renames) {
            physicalTypes.put(rename.to(), physicalTypes.remove(rename.fromSourceName()));
        }
        for (final ResolvedAdd add : adds) {
            physicalTypes.put(add.column().sourceName(), new TableTtlRules.PhysicalColumn(
                    add.column().type(), TableColumnRules.resolveNullable(add.column(), definition.getType())));
        }
        final Change<TtlInput> ttlChange = patch.ttl();
        TableDdlRenderer.TtlDdl modifyTtl = null;
        boolean removeTtl = false;
        if (ttlChange.present()) {
            if (ttlChange.value() == null) {
                removeTtl = definition.getTtlColumn() != null;
            } else {
                TableTtlRules.requireValid(ttlChange.value(), physicalTypes);
                modifyTtl = new TableDdlRenderer.TtlDdl(
                        ttlChange.value().column(), ttlChange.value().interval(), ttlChange.value().unit(),
                        TableTtlRules.wrapsToDateTime(physicalTypes.get(ttlChange.value().column()).type()));
            }
        }

        // All valid — apply: one multi-command ALTER (validated by ClickHouse as a whole, so it is
        // rejected or applied in its entirety), then all catalog changes in one transaction.
        final List<String> commands = new ArrayList<>();
        // A skip index over a dropped column goes first, in the same ALTER: ClickHouse refuses to drop a column an
        // index depends on, and a lookup index outlives the pipeline that asked for it by design.
        if (!drops.isEmpty()) {
            for (final ClickHouseTableInspector.SkipIndex index : inspector.skipIndexes(name)) {
                if (drops.stream().anyMatch(index::covers)) {
                    commands.add(ddlRenderer.dropIndexCommand(index.name()));
                }
            }
        }
        drops.forEach(drop -> commands.add(ddlRenderer.dropColumnCommand(drop)));
        renames.forEach(rename -> commands.add(ddlRenderer.renameColumnCommand(rename.fromSourceName(), rename.to())));
        adds.forEach(add -> commands.add(
                ddlRenderer.addColumnCommand(new DdlColumn(add.column().sourceName(), add.ddlType()))));
        if (modifyTtl != null) {
            commands.add(ddlRenderer.modifyTtlCommand(modifyTtl));
        }
        if (removeTtl) {
            commands.add(ddlRenderer.removeTtlCommand());
        }
        if (!commands.isEmpty()) {
            ddlExecutor.execute(ddlRenderer.renderAlter(name, commands));
        }

        writer.applyPatch(name,
                drops,
                renames.stream().map(r -> new CatalogSchemaWriter.RenameWrite(r.fromSourceName(), r.to())).toList(),
                adds.stream().map(a -> new CatalogSchemaWriter.AppendWrite(a.column(),
                        TableColumnRules.resolveNullable(a.column(), definition.getType()), a.ordinal())).toList(),
                updates.stream().map(u -> new CatalogSchemaWriter.MetadataWrite(u.exposedName(),
                        u.tag(), u.displayName(), u.description(), u.sensitive(), u.heavy())).toList(),
                ttlChange);
        return catalogService.get(name);
    }

    /**
     * The alignment column an eligible enrichment would be laid out on, and its source column, when it is not laid
     * out aligned yet (see {@code pendingAlignment} in {@link #patchSchema}).
     */
    private record PendingAlignment(String column, String sourceTable, ColumnMappingEntity sourceColumn) {

        TableValidationException collision() {
            return CatalogSchemaWriter.alignmentCollision(column, sourceTable, sourceColumn);
        }
    }

    private PendingAlignment pendingAlignment(TableDefinitionEntity definition, String grainKey, String alignment) {
        if (definition.getType() != TableType.ENRICHMENT || alignment != null || grainKey == null) {
            return null;
        }
        final String column = EnrichmentAlignment.eligibleColumn(grainKey,
                definitions.findById(definition.getSourceTable()).orElse(null));
        if (column == null) {
            return null;
        }
        return EnrichmentAlignment.physicalColumn(columns.findByTableNameOrderByOrdinal(definition.getSourceTable()), column)
                .map(sourceColumn -> new PendingAlignment(column, definition.getSourceTable(), sourceColumn))
                .orElse(null);
    }

    /** Applies {@code normalizer} to a present string change's value; leaves a "leave" change as-is. */
    private static Change<String> normalize(Change<String> change, UnaryOperator<String> normalizer) {
        return change.present() ? Change.set(normalizer.apply(change.value())) : change;
    }

    private ColumnMappingEntity requireColumn(Map<String, ColumnMappingEntity> byExposed, String exposedName) {
        final ColumnMappingEntity col = byExposed.get(exposedName);
        if (col == null) {
            throw new TableValidationException("unknown column '" + exposedName + "'");
        }
        return col;
    }

    /**
     * The rules a column must satisfy to be dropped <em>or</em> renamed. The two operations differ only in
     * what {@link ScanMetadataRules} adds on top of this (a drop of a cursor column is refused, a rename is
     * not), which is why the shared part lives here rather than being duplicated per loop.
     */
    private void requireMutable(String sourceName, TableDefinitionEntity definition, String grainKey,
            Map<String, String> keyedEnrichments) {
        if (TableColumnRules.isSystemColumn(sourceName)) {
            throw new TableValidationException("system column '" + sourceName + "' cannot be modified");
        }
        if (sourceName.equals(grainKey)) {
            throw new TableValidationException("the grain key '" + sourceName + "' cannot be modified");
        }
        // An adopted alignment column: the enrichment's partition key, written from the source row.
        if (definition.getType() == TableType.ENRICHMENT && sourceName.equals(definition.getPartitionColumn())) {
            throw new TableValidationException("column '" + sourceName + "' is the alignment column of enrichment '"
                    + definition.getName() + "' and cannot be modified: the table is partitioned by it, and ADAS "
                    + "writes it from the source row");
        }
        if (definition.getOrderingKey().contains(sourceName)) {
            throw new TableValidationException("ordering-key column '" + sourceName + "' cannot be modified");
        }
        final String keyedBy = keyedEnrichments.get(sourceName);
        if (keyedBy != null) {
            throw new TableValidationException("column '" + sourceName + "' is the grain key of enrichment '"
                    + keyedBy + "' and cannot be modified; the enrichment is joined on the shared column"
                    + " name, and its own half of that name is its ordering key — which ClickHouse will not"
                    + " rename — so drop the enrichment first if this column has to change");
        }
    }

    /**
     * The columns of {@code source} that its <b>materialized</b> enrichments are keyed on, mapped to the
     * enrichment naming each. {@code ACTIVE} only, matching the query model's own filter: a not-yet-
     * materialized enrichment contributes no join, and its grain key is re-resolved against the source
     * when its schema is materialized. Two enrichments may share a grain key; the message names one of
     * them, which is enough to explain the rejection.
     */
    private Map<String, String> enrichmentsKeyedOn(String source) {
        final Map<String, String> keyed = new LinkedHashMap<>();
        for (final TableDefinitionEntity enrichment : definitions.findBySourceTable(source)) {
            if (enrichment.getStatus() != TableStatus.ACTIVE) {
                continue;
            }
            if (enrichment.getGrainKey() != null) {
                keyed.putIfAbsent(enrichment.getGrainKey(), enrichment.getName());
            }
        }
        return keyed;
    }

    /**
     * A column a stored declaration reads may not be dropped, renamed, or renamed <em>onto</em> — on an
     * enrichment (reached through its source's grain join) or on a source (read by its bare name).
     *
     * <p>Nothing repoints a declaration's field references, so any of those operations would leave it
     * reading a column its statements can no longer resolve — failing on the next run rather than at the
     * moment the dependency broke, which is the bargain the input and enrichment-drop checks already make.
     *
     * <p><b>The source side is a security control, not only an availability one, and that is why the
     * "already governed by the grain-key and scan-metadata rules" reasoning this method used to carry was
     * wrong.</b> A rename preserves the {@code sensitive} flag and changes only the name, so on a source a
     * holder of {@code TABLE_MODIFY} and the {@code TABLE_SENSITIVE} clearance — which together confer no
     * {@code TABLE_READ} — could free the name an expression reads and move a protected column into it. The
     * declaration is untouched, the flag is untouched, so neither the clearance rule on the flag nor the
     * grant subset rule fires; the
     * pipeline's elevated runner then copies the protected values into an unflagged enrichment column that
     * plain {@code TABLE_READ} can read. Before privileges existed this was unreachable because
     * {@code PATCH .../schema} required the full administrative role, which conferred global sensitive
     * read; letting schema authority over sensitive columns be held without {@code TABLE_READ} is what opened
     * it. The
     * enrich lane has no runtime backstop (see {@code enrichment-runtime}), so the edit is the only moment
     * the rule can be applied.
     *
     * <p>Both halves of a rename are asked, because freeing a read name and occupying one are the two ways
     * to repoint a reference.
     */
    private void requireNoPipelineReads(
            TableDefinitionEntity definition, String exposedName, String operation) {
        final boolean enrichment = definition.getType() == TableType.ENRICHMENT;
        final List<String> readers = enrichment
                ? pipelineReads.pipelinesReadingEnrichmentColumn(definition.getName(), exposedName)
                : pipelineReads.pipelinesReadingSourceColumn(definition.getName(), exposedName);
        if (readers.isEmpty()) {
            return;
        }
        throw new TableConflictException("column '" + exposedName + "' of "
                + (enrichment ? "enrichment '" : "source '") + definition.getName()
                + "' is read by pipeline(s) " + readers + " and cannot be " + operation
                + "; update those declarations first");
    }

    /**
     * Refuses dropping or renaming a column that this enrichment's <b>own</b> pipeline writes.
     *
     * <p>The mirror of {@link #requireNoPipelineReads}, and the reason it has to be here rather than only in
     * the catalog write is <b>ordering</b>. An enrich pipeline's output mapping is derived by matching its
     * transform's output names to this table's columns, so a bound column that stops existing under that
     * name leaves the mapping unresolvable — which
     * {@link PipelineGenerationBump#onTargetColumnsChanged} already refuses. But that refusal runs inside
     * {@code CatalogSchemaWriter.applyPatch}, i.e. <em>after</em> the single ClickHouse {@code ALTER} above
     * has been applied: rolling the catalog transaction back there would leave ClickHouse without the column
     * and the catalog with it, permanently, with no operation that repairs the pair. Asked here, before any
     * DDL is rendered, the same change is refused with nothing having happened.
     *
     * <p>Keyed on the <b>physical</b> name, because that is the spelling the derived mapping stores.
     */
    private void requireNoPipelineWrites(
            TableDefinitionEntity definition, String sourceName, String operation) {
        if (definition.getType() != TableType.ENRICHMENT) {
            return;
        }
        final List<String> writers =
                pipelineReads.pipelinesWritingEnrichmentColumn(definition.getName(), sourceName);
        if (!writers.isEmpty()) {
            throw new TableConflictException("column '" + sourceName + "' of enrichment '"
                    + definition.getName() + "' is written by pipeline(s) " + writers + " and cannot be "
                    + operation + "; first remove '" + sourceName + "' from their transform outputs, or"
                    + " re-point or delete those pipelines");
        }
    }

    /**
     * The refusal for touching sensitivity without the clearance. A 403 rather than an unknown column,
     * because every column it can concern — a new one, or a visible non-sensitive one — is one the caller
     * already sees; a hidden column never reaches here, it is refused as unknown first.
     */
    private static SensitiveClearanceRequiredException clearanceRequired(String table, String what) {
        return new SensitiveClearanceRequiredException(what + " on table '" + table + "' requires TABLE_SENSITIVE on it "
                + "alongside TABLE_MODIFY");
    }

    /**
     * Refuses a drop or rename of a column a row policy on the table filters on, with
     * {@code 409 column_in_row_policy} naming the column and the roles. Applies whoever the caller is, in
     * {@code none} mode too: the policy is stored state that becomes enforced the moment security is on.
     */
    private static void requireNoRowPolicy(
            String table, String column, Map<String, Set<String>> policyColumns, String verb) {
        final Set<String> roles = policyColumns.get(column);
        if (roles != null && !roles.isEmpty()) {
            throw new ColumnInRowPolicyException("column '" + column + "' of table '" + table + "' cannot be "
                    + verb + ": the row policies of role(s) " + roles + " filter on it; change or remove those "
                    + "policies first");
        }
    }
}
