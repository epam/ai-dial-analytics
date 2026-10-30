package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.exception.QueryEntityNotFoundException;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccess;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.TagOrdering;
import com.epam.aidial.analytics.web.dto.query.QueryEntityDto;
import com.epam.aidial.analytics.web.dto.query.QueryEntitySchemaDto;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QuerySchemaFieldDto;
import com.epam.aidial.analytics.web.security.Privilege;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The catalog-backed source of truth for the query path: which entities are queryable (materialized
 * {@code source} tables), their flat schema (source columns plus every materialized enrichment's
 * exposed columns, with grain-key columns hidden), and the resolved {@link QueryEntityModel} the
 * builder/executor use. Discovery and execution both read from here, so they always agree on the set
 * of queryable entities.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogQueryService {

    private final TableDefinitionRepository definitions;
    private final ColumnMappingRepository columns;
    private final SensitiveColumnAccess sensitiveAccess;

    /**
     * Entities already reported as not partition-local. Models are built per request, so without this the
     * report would repeat on every query of the entity; one INFO line per entity per process is the signal,
     * the repeats go to DEBUG.
     */
    private final Set<String> reportedCrossPartition = ConcurrentHashMap.newKeySet();

    /**
     * Names of queryable entities the caller holds {@code bar} on, in stable alphabetical order. An entity
     * the caller does not clear the bar for is <b>absent</b> rather than present-but-forbidden, so naming it
     * later fails as an unknown entity and nothing confirms it exists.
     */
    @Transactional(readOnly = true)
    public List<String> queryableEntityNames(Privilege bar) {
        return definitions.findByTypeAndStatus(TableType.SOURCE, TableStatus.ACTIVE).stream()
                .map(TableDefinitionEntity::getName)
                .filter(name -> visibleAt(name, bar))
                .sorted()
                .toList();
    }

    /**
     * Discovery catalog: one descriptor per queryable source the caller may see. Filtered at
     * {@link Privilege#TABLE_LIST}, the lowest rung, because the response carries entity names alone — and
     * whether each may be read {@code FINAL}, which is a property of the tables' declared disciplines and of
     * which enrichments the caller may read, not of their contents.
     */
    @Transactional(readOnly = true)
    public List<QueryEntityDto> listEntities() {
        return definitions.findByTypeAndStatus(TableType.SOURCE, TableStatus.ACTIVE).stream()
                .filter(def -> visibleAt(def.getName(), Privilege.TABLE_LIST))
                .sorted(Comparator.comparing(TableDefinitionEntity::getName))
                .map(def -> new QueryEntityDto(def.getName(), supportsFinal(def)))
                .toList();
    }

    /**
     * Whether the entity of {@code source} may be read {@code FINAL} by this caller — the discovery face of
     * {@link FinalDecision#finalCapable}, computed by the same rule the query model is built with.
     */
    private boolean supportsFinal(TableDefinitionEntity source) {
        return finalCapable(source, activeEnrichmentsOf(source.getName()));
    }

    /**
     * Whether an entity may be read {@code FINAL} by this caller: its source collapses versions, or it has an
     * active enrichment (always version-collapsing) the caller may <b>read</b>. Always {@link Privilege#TABLE_READ}
     * — the bar execution resolves the entity at, which drops every enrichment the caller cannot read — whatever
     * bar the asking surface resolved at, so discovery ({@code TABLE_LIST}) and translation ({@code TABLE_GET})
     * report exactly the capability execution then honours. It reveals no enrichment the caller cannot read.
     */
    private boolean finalCapable(TableDefinitionEntity source, List<TableDefinitionEntity> enrichments) {
        return FinalDecision.collapses(source)
                || enrichments.stream().anyMatch(enrichment -> visibleAt(enrichment.getName(), Privilege.TABLE_READ));
    }

    /**
     * Flat base schema for discovery; 404 if {@code entity} is not a queryable source <b>or</b> the caller
     * lacks {@link Privilege#TABLE_GET} on it — the same door as the catalog read of the same table, so
     * neither surface is the weaker one.
     */
    @Transactional(readOnly = true)
    public QueryEntitySchemaDto getBaseSchema(String entity) {
        final QueryEntityModel model = findModel(entity, Privilege.TABLE_GET)
                .orElseThrow(() -> new QueryEntityNotFoundException("Unknown queryable entity: " + entity));
        final List<String> tagOrder = model.tagOrder();
        List<QuerySchemaFieldDto> fields = model.baseSchema();
        // When the source declares a tag ordering, return the (already sensitive-filtered) fields
        // sorted by it via the shared comparator, so discovery and the /v1/tables read surface agree.
        // tag_order itself is returned verbatim (unfiltered), the same for admin and non-admin callers.
        if (tagOrder != null && !tagOrder.isEmpty()) {
            fields = fields.stream()
                    .sorted(TagOrdering.comparator(
                            tagOrder, QuerySchemaFieldDto::tag, QuerySchemaFieldDto::name))
                    .toList();
        }
        return new QueryEntitySchemaDto(model.entity(), tagOrder, fields);
    }

    /**
     * The resolved query model for {@code entity}, present only if it is a materialized source
     * <b>and</b> the caller holds {@code bar} on it.
     *
     * <p>An entity is visible if and only if its <b>source</b> is: the source table is the statement's
     * {@code FROM}, so there is nothing to serve without it. An enrichment is never addressable on its own
     * (only a {@code SOURCE} passes the filter below), which is why a {@code read} grant on an enrichment
     * alone is inert — and why serving one through its source's entity is refused rather than accommodated:
     * the grain key is the <em>source's</em> column, so projecting it would disclose the source's row
     * population and its grain-key values, a source read in disguise.
     */
    @Transactional(readOnly = true)
    public Optional<QueryEntityModel> findModel(String entity, Privilege bar) {
        return definitions.findById(entity)
                .filter(def -> def.getType() == TableType.SOURCE && def.getStatus() == TableStatus.ACTIVE)
                .filter(def -> visibleAt(def.getName(), bar))
                .map(def -> buildModel(def, bar));
    }

    /** Whether the caller clears {@code bar} on {@code table}; always true when security is off. */
    private boolean visibleAt(String table, Privilege bar) {
        return switch (bar) {
            case TABLE_LIST -> sensitiveAccess.mayList(table);
            case TABLE_GET -> sensitiveAccess.mayGet(table);
            case TABLE_READ -> sensitiveAccess.mayRead(table);
            // Enumerated rather than defaulted: a default arm would resolve a future TABLE_MODIFY or
            // TABLE_SENSITIVE bar as a read check with nothing failing, which is the silent
            // miscalibration that threading the bar explicitly exists to prevent.
            default -> throw new IllegalArgumentException(
                    bar + " is not a catalog visibility bar; expected TABLE_LIST, TABLE_GET or TABLE_READ");
        };
    }

    /**
     * The enrichments of {@code source} that take part in its flat entity: ACTIVE, and carrying a grain
     * key. An ACTIVE enrichment always has a grain key; the second filter keeps a catalog row that somehow
     * lacks one out of the join rather than rendering an unqualified condition.
     *
     * <p>Exposed because membership has to be decided once. The aggregate pipeline surface resolves a
     * declaration's field references against this same set, and an enrichment that counted for validation
     * but not for model assembly would let a declaration name a column the compiled statement then never
     * joins — a failure that surfaces at execution rather than at declaration.
     */
    @Transactional(readOnly = true)
    public List<TableDefinitionEntity> activeEnrichmentsOf(String source) {
        return definitions.findBySourceTable(source).stream()
                .filter(enrichment -> enrichment.getStatus() == TableStatus.ACTIVE)
                .filter(enrichment -> enrichment.getGrainKey() != null)
                .toList();
    }

    private QueryEntityModel buildModel(TableDefinitionEntity source, Privilege bar) {
        // The access-control chokepoint, decided PER OWNING TABLE. A caller that may not read a table gets
        // a model with none of that table's columns, and one that may read it but not its sensitive columns
        // gets a model with those absent — from both `bindings` and `baseSchema`. Because discovery,
        // structured execution, SQL translation (via CalciteQueryCatalog), the translation endpoints and
        // Flight all resolve through this one model, both filters then apply everywhere: a hidden column is
        // omitted from discovery and rejected as an ordinary unknown field if referenced, in every clause
        // and in the empty-projection "select all" expansion.
        //
        // Per owning table rather than once per request, because a queryable entity is a composite: a
        // source flattened with its active enrichments. One caller can legitimately read the source and not
        // an enrichment, or see the source's sensitive columns and not the enrichment's. The decision is
        // taken where each column's owning table is in hand, which is the argument this method already had.
        final String sourceName = source.getName();
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        final List<QuerySchemaFieldDto> schema = new ArrayList<>();
        final boolean maySeeSourceSensitive = sensitiveAccess.maySeeSensitive(sourceName);
        for (final ColumnMappingEntity col : columns.findByTableNameOrderByOrdinal(sourceName)) {
            addColumn(bindings, schema, sourceName, false, maySeeSourceSensitive, col, null);
        }

        // Partition locality is decided for the whole entity, not per statement: one table whose key
        // versions may span partitions means every read of the entity merges across them (see
        // PartitionLocality). Reported once so a table that quietly costs its source the optimisation is
        // one log line away, not a query-plan investigation.
        boolean partitionLocal = PartitionLocality.isPartitionLocal(source, null);
        if (!partitionLocal) {
            reportCrossPartition(sourceName, "its partition column '" + source.getPartitionColumn()
                    + "' is outside its ordering key");
        }
        final List<EnrichmentJoin> enrichments = new ArrayList<>();
        final List<TableDefinitionEntity> activeEnrichments = activeEnrichmentsOf(sourceName);
        for (final TableDefinitionEntity enrichment : activeEnrichments) {
            if (partitionLocal && !PartitionLocality.isPartitionLocal(enrichment, source)) {
                partitionLocal = false;
                reportCrossPartition(sourceName, "enrichment '" + enrichment.getName() + "' partitions by '"
                        + enrichment.getPartitionColumn() + "', which is neither in its ordering key nor aligned with the source");
            }
            // An enrichment the caller may not read contributes nothing: its columns are absent from the
            // model and its join is not assembled at all. That is undetectable in the results, because the
            // grain join is 0..1 on a single key and so contributes at most one row per source row — the
            // row count is identical to a fully-privileged caller's.
            // Gated at the caller's own bar, the same one the source cleared — not hard-coded to
            // TABLE_READ. Translation and validation resolve at TABLE_GET, and a caller that may `get` the
            // source and its enrichment must see the flat schema it is about to be handed SQL for;
            // resolving the enrichment one rung higher would report every enrichment column as unknown.
            if (!visibleAt(enrichment.getName(), bar)) {
                continue;
            }
            final boolean maySeeEnrichmentSensitive = sensitiveAccess.maySeeSensitive(enrichment.getName());
            final String grainKey = enrichment.getGrainKey();
            final Set<String> exposed = new LinkedHashSet<>();
            final List<ColumnMappingEntity> enrichmentColumns = columns.findByTableNameOrderByOrdinal(enrichment.getName());
            for (final ColumnMappingEntity col : enrichmentColumns) {
                // Enrichment columns are exposed qualified by their owning table ("table.column") so that
                // two enrichments (or a source) can share a bare column name without colliding in the flat
                // schema. The qualified name is what the query path references and joins fire on.
                final String flatName =
                        addColumn(bindings, schema, enrichment.getName(), true, maySeeEnrichmentSensitive, col, null);
                if (flatName != null) {
                    exposed.add(flatName);
                }
            }
            // The enrichment's computed fields follow its catalog columns, through the same sensitive gate, and are
            // exposed like them so naming one pulls the grain join in. Never in tableBindings: a row policy judges
            // what the store holds.
            for (final ComputedFields.Descriptor computed : ComputedFields.of(enrichment.getName(), enrichmentColumns)) {
                final String flatName = addColumn(bindings, schema, enrichment.getName(), true, maySeeEnrichmentSensitive,
                        computed.column(), computed.field());
                if (flatName != null) {
                    exposed.add(flatName);
                }
            }
            // Grain join: the source and enrichment share the grain-key column name, and the join is
            // single-key (source.grain_key = enrichment.grain_key) with 0..1 cardinality, so it never
            // inflates rows. The two key fields travel with the join so EnrichmentJoins can restrict the
            // enrichment side to the keys a statement's own predicates select, instead of letting
            // ClickHouse hash the whole enrichment table for a lookup that needs a handful of its rows.
            final Field<Object> sourceKey = DSL.field(DSL.name(sourceName, grainKey));
            final Field<Object> enrichmentKey = DSL.field(DSL.name(enrichment.getName(), grainKey));
            final String partition = alignedPartitionColumn(source, enrichment);
            enrichments.add(EnrichmentJoin.keyed(
                    DSL.table(DSL.name(enrichment.getName())),
                    sourceKey,
                    enrichmentKey,
                    partition == null ? null : DSL.field(DSL.name(sourceName, partition)),
                    partition == null ? null : DSL.field(DSL.name(enrichment.getName(), partition)),
                    exposed,
                    partition == null ? null : source.getPartitionGranularity()));
        }

        final List<String> tagOrder = source.getTagOrder();
        return new QueryEntityModel(
                sourceName, DSL.table(DSL.name(sourceName)), bindings, List.copyOf(enrichments),
                List.copyOf(schema), tagOrder == null ? null : List.copyOf(tagOrder), partitionLocal,
                FinalDecision.collapses(source), finalCapable(source, activeEnrichments));
    }

    /**
     * The bindings of {@code table}'s <b>own</b> stored columns, every one of them — sensitive columns included,
     * no enrichment joined, nothing hidden for the caller — each under its bare exposed name and backed by the
     * physical {@code table.column} reference. This is the model a row policy on {@code table} is validated
     * and rendered against: a policy constrains what the store holds, so it is judged against what the store
     * holds rather than against the view of whoever writes it. Not a query model, and never handed to one.
     */
    @Transactional(readOnly = true)
    public Map<String, QueryFieldBinding> tableBindings(String table) {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        final List<QuerySchemaFieldDto> discarded = new ArrayList<>();
        for (final ColumnMappingEntity col : columns.findByTableNameOrderByOrdinal(table)) {
            addColumn(bindings, discarded, table, false, true, col, null);
        }
        return bindings;
    }

    private void reportCrossPartition(String entity, String reason) {
        if (reportedCrossPartition.add(entity)) {
            log.info("Entity '{}' is not partition-local: {}; its reads deduplicate across partitions", entity, reason);
        } else {
            log.debug("Entity '{}' is not partition-local: {}", entity, reason);
        }
    }

    /**
     * The partition column both tables carry, or null when the enrichment's partitioning is not aligned with
     * its source's and so cannot bound a keyed join (see {@link EnrichmentJoin}).
     *
     * <p>Alignment is claimed by the catalog and read, not inferred: both definitions must declare a
     * partition column, and it must be the <em>same</em> column name. The shared name is the claim's whole
     * content — that for a given grain key the two tables hold the same value in that column — and a table
     * populated from the same record on both sides is what makes it true. That is why this asks for equality
     * of names rather than merely for both being set: partitioning an enrichment by {@code created_at} while
     * its source partitions by {@code request_time} is a perfectly ordinary pair of declarations, and
     * bounding one by the other's values would silently drop rows from the join.
     *
     * <p>Granularity is deliberately <em>not</em> compared. The restriction this enables is a set of column
     * values, not a range over partition ids, so it prunes correctly whatever expression either table
     * partitions that column by; requiring the granularities to match would refuse the optimization for a
     * pairing that is sound.
     */
    private static String alignedPartitionColumn(
            TableDefinitionEntity source, TableDefinitionEntity enrichment) {
        final String sourceColumn = source.getPartitionColumn();
        final String enrichmentColumn = enrichment.getPartitionColumn();
        return sourceColumn != null && sourceColumn.equals(enrichmentColumn) ? sourceColumn : null;
    }

    /**
     * Adds one column to the flat schema and bindings, returning the flat field name it was published
     * under, or {@code null} when the column is sensitive and the caller may not see it (so callers can
     * skip it from an enrichment's exposed set). Source columns keep their bare exposed name; enrichment
     * columns are qualified {@code owningTable.exposedName} so the flat namespace stays collision-free
     * across a source and its enrichments. The backing jOOQ {@link Field} is always the two-part physical
     * reference {@code owningTable.sourceName}; only the published flat name differs. A {@code computed} field's
     * {@code col} is its transient descriptor, and its binding is backed by the field's stand-in instead.
     */
    private String addColumn(
            Map<String, QueryFieldBinding> bindings,
            List<QuerySchemaFieldDto> schema,
            String owningTable,
            boolean enrichment,
            boolean maySeeSensitive,
            ColumnMappingEntity col,
            ComputedField computed) {
        if (col.isSensitive() && !maySeeSensitive) {
            // Hidden, not forbidden: the column never enters the model, so discovery omits it and any
            // reference to it later fails as an ordinary unknown field, never revealing it exists.
            return null;
        }
        final String flatName = enrichment ? owningTable + "." + col.getName() : col.getName();
        // Array columns always carry an element type (declaration validation enforces it); the string
        // fallback is a defensive default for pre-validation catalog rows, matching the seeded column.
        final QueryFieldType elementType = col.getType() == QueryFieldType.ARRAY
                ? (col.getElementType() == null ? QueryFieldType.STRING : col.getElementType())
                : null;
        final QueryFieldBinding binding = computed != null
                ? QueryFieldBinding.computed(flatName, computed, owningTable)
                : new QueryFieldBinding(flatName, DSL.field(DSL.name(owningTable, col.getSourceName()), JooqTypes.of(col.getType())),
                        col.getType(), elementType, col.isHeavy());
        if (bindings.putIfAbsent(flatName, binding) != null) {
            // Qualification makes this impossible for a well-formed catalog; fail loud rather than
            // silently drop a column if the catalog is ever corrupted.
            throw new IllegalStateException(
                    "duplicate flat query field '" + flatName + "' from table '" + owningTable + "'");
        }
        // The heavy flag is published to every caller (unlike `sensitive`, which hides the field): it is a
        // transfer-cost hint telling the client which fields a wildcard projection leaves behind.
        schema.add(new QuerySchemaFieldDto(
                flatName, col.getType(), col.getElementType(), col.getEnumValues(), col.getSourceName(),
                col.getDescription(), col.getDisplayName(), col.getTag(), col.isSensitive(), col.isHeavy(), computed != null));
        return flatName;
    }
}
