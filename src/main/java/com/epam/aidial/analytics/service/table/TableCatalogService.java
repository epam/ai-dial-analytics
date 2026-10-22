package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.clickhouse.ClickHouseDdlExecutor;
import com.epam.aidial.analytics.configuration.TableLimitsProperties;
import com.epam.aidial.analytics.exception.TableConflictException;
import com.epam.aidial.analytics.exception.TableNotFoundException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.query.sql.InformationSchema;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccess;
import com.epam.aidial.analytics.service.table.grant.TableCallerPermissions;
import com.epam.aidial.analytics.web.security.Privilege;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Identity + catalog-metadata half of the {@code table_definition} catalog. Create records the table's
 * identity only (name/type, an enrichment's immutable {@code source_table}, plus optional
 * description/tag ordering) as a {@code PENDING} definition — it issues no ClickHouse DDL. The physical
 * schema is defined and materialized in one call via {@link TableSchemaCreationService#defineSchema}.
 * Update mutates only catalog metadata (description and tag
 * ordering); read/list expose definitions and their columns; delete drops the ClickHouse table and
 * removes the catalog rows (a source with dependent enrichments cannot be deleted).
 */
@Service
@RequiredArgsConstructor
public class TableCatalogService {

    /** The table-name prefix reserved for tables the service owns in ClickHouse; never accepted from a caller. */
    public static final String INTERNAL_TABLE_PREFIX = "_dial_internal_";

    private final TableDefinitionRepository definitions;
    private final ColumnMappingRepository columns;
    private final TableCallerPermissions callerPermissions;
    private final SensitiveColumnAccess sensitiveAccess;
    private final CatalogSchemaWriter writer;
    private final ClickHouseDdlExecutor ddlExecutor;
    private final TableDdlRenderer ddlRenderer;
    private final TableLimitsProperties limits;
    private final PipelineRepository pipelines;
    private final PipelineInputDependents pipelineReads;
    private final PipelineTargetCascade pipelineTargetCascade;

    /**
     * The definitions the caller may see, ordered by name, each with its exposed-column count (one
     * aggregate query, no N+1).
     *
     * <p>Filtered at {@link Privilege#TABLE_LIST}, the lowest read rung, so a table the caller holds no
     * read-side privilege on is <b>absent</b> rather than present-but-forbidden. An empty result is a
     * {@code 200} with no entries, not a {@code 403}: a collection endpoint that answered {@code 403} when
     * a caller happened to be entitled to nothing would disclose exactly that, and would make the empty
     * case behave differently from the one-entry case for no reason a client could act on.
     *
     * <p>The count is the caller's: sensitive columns are counted only where the caller holds the clearance,
     * so it equals the length of the {@code columns} array the same caller gets from the single read.
     */
    @Transactional(readOnly = true)
    public List<TableSummary> list() {
        final Map<String, ColumnMappingRepository.TableColumnCount> counts = columns.countByTable().stream()
                .collect(Collectors.toMap(ColumnMappingRepository.TableColumnCount::getTableName, count -> count));
        return definitions.findAll(Sort.by("name")).stream()
                .filter(definition -> callerPermissions.resolve(definition).list())
                .map(definition -> new TableSummary(definition, visibleColumnCount(definition, counts)))
                .toList();
    }

    private int visibleColumnCount(
            TableDefinitionEntity definition, Map<String, ColumnMappingRepository.TableColumnCount> counts) {
        final ColumnMappingRepository.TableColumnCount count = counts.get(definition.getName());
        if (count == null) {
            return 0;
        }
        final long hidden = sensitiveAccess.maySeeSensitive(definition.getName()) ? 0 : count.getSensitiveCount();
        return (int) (count.getColumnCount() - hidden);
    }

    /** A single definition with its current exposed columns (and grain, for an enrichment); 404 if absent. */
    @Transactional(readOnly = true)
    public TableView get(String name) {
        final TableDefinitionEntity definition = definitions.findById(name)
                .orElseThrow(() -> new TableNotFoundException("table '" + name + "' not found"));
        return view(definition);
    }

    /**
     * Creates a table's identity as {@code PENDING} — no columns, no grain, no ClickHouse DDL. Validates
     * the name grammar, duplicate name (409), the catalog cap, and (for an enrichment) that
     * {@code source_table} names an existing source. The physical schema is supplied afterwards via
     * {@code POST /v1/tables/{name}/schema}, which defines it and materializes the table.
     */
    public TableView create(CreateTableInput input) {
        requireNameIsNotInternal(input.name());
        Identifiers.requireUserIdentifier(input.name());
        requireNameIsNotSchemaName(input.name());
        if (definitions.existsById(input.name())) {
            throw new TableConflictException("table '" + input.name() + "' already exists");
        }
        requireCapacity(definitions.count(), 1, limits.maxTables());
        validateSourceTable(input.type(), input.sourceTable());
        validateWriteMode(input.type(), input.write());
        writer.persistCreate(input);
        return get(input.name());
    }

    /**
     * The catalog cap: refuses adding {@code adding} tables to a catalog already holding {@code existing}
     * when the total would exceed {@code maxTables}. Public so the catalog import can check a whole bundle's
     * total up front, with the same rule a single create applies.
     */
    public static void requireCapacity(long existing, int adding, int maxTables) {
        if (existing + adding <= maxTables) {
            return;
        }
        if (adding == 1) {
            throw new TableValidationException("the catalog already holds the maximum of " + maxTables + " tables");
        }
        throw new TableValidationException("creating " + adding + " tables would bring the catalog to "
                + (existing + adding) + ", above the maximum of " + maxTables + " tables");
    }

    /**
     * Rejects a name under the {@value #INTERNAL_TABLE_PREFIX} prefix, which belongs to tables the service owns
     * in ClickHouse and never catalogues (the ingest ledger). Checked before the identifier grammar — which
     * refuses a leading {@code _} today — so relaxing that grammar cannot open the prefix: a create of such a
     * name would reach ClickHouse, fail because the table exists, and its compensating
     * {@code DROP TABLE IF EXISTS} would drop the service's table.
     */
    public static void requireNameIsNotInternal(String name) {
        if (name != null && name.startsWith(INTERNAL_TABLE_PREFIX)) {
            throw new TableValidationException("table name '" + name + "' is reserved: the prefix '"
                    + INTERNAL_TABLE_PREFIX + "' is reserved for service-internal tables");
        }
    }

    /**
     * Rejects a table name that the SQL surface uses as a schema name.
     *
     * <p>The SQL front end exposes every entity a second time under the data schema {@code adas}, and the
     * catalog relations under {@code INFORMATION_SCHEMA}. Calcite resolves a one-part name against
     * sub-schemas before tables, so a table named after either schema would be unreachable by its bare
     * name — {@code SELECT ... FROM adas} would report "Object 'adas' not found" while
     * {@code /v1/queries/entities} and {@code INFORMATION_SCHEMA.TABLES} both went on listing it. Refusing
     * the name up front turns a contradiction a caller cannot act on into one sentence at the point of
     * naming.
     */
    public static void requireNameIsNotSchemaName(String name) {
        if (InformationSchema.DATA_SCHEMA.equalsIgnoreCase(name) || InformationSchema.isMetadataSchema(name)) {
            throw new TableValidationException("table name '" + name + "' is reserved: the SQL surface exposes"
                    + " every entity under the schema name '" + InformationSchema.DATA_SCHEMA + "' and its catalog"
                    + " relations under '" + InformationSchema.SCHEMA + "', so a table with either name could not"
                    + " be resolved by its bare name in SQL");
        }
    }

    /**
     * Updates a table's catalog metadata — its {@code description} (set when non-null; a null leaves it
     * unchanged) and its {@code tagOrder} (merge-patch: a "leave" change is a no-op, a "set" of a
     * null/empty list clears it). All physical-schema and identity fields are immutable here.
     */
    @Transactional
    public TableView updateMetadata(String name, String description, Change<List<String>> tagOrder) {
        final TableDefinitionEntity entity = definitions.findById(name)
                .orElseThrow(() -> new TableNotFoundException("table '" + name + "' not found"));
        entity.requireModifiable();
        if (description != null) {
            entity.setDescription(description);
        }
        if (tagOrder.present()) {
            entity.setTagOrder(TableColumnRules.normalizeTagOrder(tagOrder.value()));
        }
        return view(definitions.save(entity));
    }

    @Transactional
    public void delete(String name) {
        final TableDefinitionEntity entity = definitions.findById(name)
                .orElseThrow(() -> new TableNotFoundException("table '" + name + "' not found"));
        entity.requireModifiable();
        if (entity.getType() == TableType.SOURCE) {
            final List<TableDefinitionEntity> dependents = definitions.findBySourceTable(name);
            if (!dependents.isEmpty()) {
                throw new TableConflictException("table '" + name + "' has " + dependents.size()
                        + " enrichment(s) referencing it; delete them first");
            }
        }
        if (entity.getType() == TableType.ENRICHMENT) {
            // A pipeline may reach this enrichment's columns through its input's grain join without
            // targeting it. Dropping it would leave those declarations naming a table their statements can
            // no longer join, failing on the next run rather than at the moment the dependency broke.
            //
            // This one cannot be answered by the input lookup below, and that is why it survives the merge
            // of the two registries rather than collapsing into it: it is a DECLARATION-PARSING question —
            // which declarations name a column of this enrichment through the grain join — and the reader's
            // own input is a different table entirely.
            final List<String> enrichmentReaders = pipelineReads.pipelinesReadingEnrichment(name);
            if (!enrichmentReaders.isEmpty()) {
                throw new TableConflictException("table '" + name + "' is read by pipeline(s) "
                        + enrichmentReaders + " through their input's grain join; delete them first");
            }
        }
        // A pipeline reading this table would survive pointing at nothing and fail on its next run, so the
        // dependency breaks here instead. A pipeline whose TARGET this is goes with the table — it exists
        // only to fill it — and is deleted just below.
        //
        // ONE lookup for both kinds, where the two registries needed two. The enrichment registry asked
        // "which rules declare this as their read source" through its own repository; that question is now
        // this query, because a declared read source IS the pipeline's `input_table`.
        final List<PipelineEntity> readers = pipelines.findByInputTable(name);
        if (!readers.isEmpty()) {
            throw new TableConflictException("table '" + name + "' is the input of pipeline(s) "
                    + readers.stream().map(PipelineEntity::getName).toList() + "; delete them first");
        }
        // The pipeline whose TARGET this is is removed through the registry's own repository rather than
        // left to pipeline.target's ON DELETE CASCADE, for the same reason the columns below are: a
        // database cascade fires no Hibernate event, so the pipeline's deletion would be silently absent
        // from the audit trail — and the snapshot surface, which decides existence from the latest audit
        // row at or before a revision, would go on answering as though it still existed.
        //
        // Asked unconditionally rather than per type, because one call now covers what the two registries
        // needed two for: `pipeline.target` is UNIQUE across both kinds, an AGGREGATE row targets a SOURCE
        // and an ENRICH row targets an ENRICHMENT, so "the pipeline targeting this table" is one question
        // whatever this table is.
        pipelineTargetCascade.deletePipelineTargetingTable(name);
        // DROP IF EXISTS is idempotent, so it is safe for a materialized (ACTIVE) table and for a
        // never-materialized one (PENDING/FAILED) whose ClickHouse table may or may not exist.
        ddlExecutor.execute(ddlRenderer.renderDrop(name));
        // The columns are removed through their repository rather than left to the ON DELETE CASCADE
        // that would otherwise take them: a database cascade fires no Hibernate event, so the audit
        // trail would record the table's deletion and SILENTLY NOTHING for its columns. That is not
        // merely a gap in the feed — the snapshot surface decides membership from the latest audit row
        // at or before a revision, excluding deletes, so a column removed without a delete row would go
        // on answering as though it still existed at every future revision. The derived deleteBy… loads
        // and deletes each row (unlike a @Modifying bulk query), so the events fire; the flush keeps
        // that ordered before the parent's own delete. The cascade stays in place as a backstop and
        // simply finds nothing left to remove. The grain and ordering key need nothing: they are
        // columns of the definition row and go with it.
        columns.deleteByTableName(name);
        columns.flush();
        definitions.delete(entity);
    }

    private TableView view(TableDefinitionEntity definition) {
        return new TableView(definition, columns.findByTableNameOrderByOrdinal(definition.getName()));
    }

    private void validateSourceTable(TableType type, String sourceTable) {
        if (type == TableType.ENRICHMENT) {
            if (sourceTable == null || sourceTable.isBlank()) {
                throw new TableValidationException("an enrichment must name a source_table");
            }
            final TableDefinitionEntity source = definitions.findById(sourceTable)
                    .orElseThrow(() -> new TableValidationException(
                            "source_table '" + sourceTable + "' does not exist"));
            if (source.getType() != TableType.SOURCE) {
                throw new TableValidationException("source_table '" + sourceTable + "' is not a source");
            }
            return;
        }
        if (sourceTable != null) {
            throw new TableValidationException("source_table is only valid for an enrichment");
        }
    }

    /**
     * An enrichment's write discipline is implied by its kind — it is always keyed and collapsed on its
     * grain — so accepting the member would create a second source of truth for the same fact (and a way
     * to ask for {@code append} on a table that cannot be append). Rejecting it keeps one truth per kind.
     */
    private void validateWriteMode(TableType type, TableWriteMode write) {
        if (type == TableType.ENRICHMENT && write != null) {
            throw new TableValidationException("write is only valid for a source; an enrichment always "
                    + "collapses by its grain key");
        }
    }
}
