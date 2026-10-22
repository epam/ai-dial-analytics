package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.web.dto.query.QuerySchemaFieldDto;
import org.jooq.Table;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The resolved, queryable view of a source entity, assembled from the catalog: the jOOQ source
 * {@code table}, the flat {@code bindings} (exposed field name → jOOQ field, covering the source and
 * every materialized enrichment, with grain-key columns hidden), the available {@code enrichments}
 * (joined on demand), the flat {@code baseSchema} for discovery, and the source's per-table
 * {@code tagOrder} ({@code null} when not set), a display hint used to sort the discovery schema, and
 * whether the entity is {@code partitionLocal}: every version-collapsing table it can reach — the source
 * and each active enrichment — keeps all versions of a key inside one partition (see
 * {@link PartitionLocality}), which is what lets the executor deduplicate a read inside each partition
 * instead of merging across all of them, and whether its source is {@code collapsing}: declared
 * {@code upsert_by_key}, so its engine collapses versions and a final read renders {@code FINAL} on it (see
 * {@code FinalDecision}), and whether the entity is {@code finalCapable}: its source collapses, or it has an
 * active enrichment the caller may <em>read</em> — decided at {@code TABLE_READ} whatever bar the model was
 * resolved at, so a model resolved for translation or discovery at {@code TABLE_GET} agrees with the one
 * execution resolves (see {@code FinalDecision#finalCapable}). Consumed by the query builder and executor.
 *
 * <p>The {@code bindings} map keeps its <strong>insertion order</strong> — the catalog's column order,
 * the source's columns followed by each enrichment's — and the canonical constructor preserves it
 * defensively rather than trusting the caller's map type. That order is observable: it is the column
 * order of a wildcard projection ({@link WildcardProjection}), hence of the SQL a bare query renders and
 * of the Arrow schema a Flight client is handed, so it must not depend on an unordered map's hashing.
 */
public record QueryEntityModel(
        String entity,
        Table<?> table,
        Map<String, QueryFieldBinding> bindings,
        List<EnrichmentJoin> enrichments,
        List<QuerySchemaFieldDto> baseSchema,
        List<String> tagOrder,
        boolean partitionLocal,
        boolean collapsing,
        boolean finalCapable) {

    public QueryEntityModel {
        bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
    }

    /**
     * A model whose final-capability follows from its own contents: it collapses, or it carries an enrichment.
     * Right for every model whose enrichments are exactly the ones its caller may read — synthetic models and
     * test fixtures; the catalog states the flag explicitly.
     */
    public QueryEntityModel(
            String entity,
            Table<?> table,
            Map<String, QueryFieldBinding> bindings,
            List<EnrichmentJoin> enrichments,
            List<QuerySchemaFieldDto> baseSchema,
            List<String> tagOrder,
            boolean partitionLocal,
            boolean collapsing) {
        this(entity, table, bindings, enrichments, baseSchema, tagOrder, partitionLocal, collapsing,
                collapsing || !enrichments.isEmpty());
    }

    /**
     * A model whose source is not known to collapse versions — every synthetic model (a derived table, a
     * join, a set operation) and a test fixture. Its source never takes {@code FINAL}; with no enrichment it is
     * read raw and refuses {@code FINAL} altogether.
     */
    public QueryEntityModel(
            String entity,
            Table<?> table,
            Map<String, QueryFieldBinding> bindings,
            List<EnrichmentJoin> enrichments,
            List<QuerySchemaFieldDto> baseSchema,
            List<String> tagOrder,
            boolean partitionLocal) {
        this(entity, table, bindings, enrichments, baseSchema, tagOrder, partitionLocal, false);
    }

    /**
     * A model that is not known to be partition-local. The safe default: a read of it deduplicates across
     * partitions, which is never wrong, only slower. The catalog-built model states the flag explicitly.
     */
    public QueryEntityModel(
            String entity,
            Table<?> table,
            Map<String, QueryFieldBinding> bindings,
            List<EnrichmentJoin> enrichments,
            List<QuerySchemaFieldDto> baseSchema,
            List<String> tagOrder) {
        this(entity, table, bindings, enrichments, baseSchema, tagOrder, false, false);
    }

    /** Convenience constructor for a model with no per-table tag ordering and no partition-locality claim. */
    public QueryEntityModel(
            String entity,
            Table<?> table,
            Map<String, QueryFieldBinding> bindings,
            List<EnrichmentJoin> enrichments,
            List<QuerySchemaFieldDto> baseSchema) {
        this(entity, table, bindings, enrichments, baseSchema, null, false, false);
    }

    /**
     * The enrichment exposing {@code field}, or {@code null} for a source field or an unknown name — the one answer to
     * which join a flat field name pulls in, shared by the read path and the pipeline paths.
     */
    public EnrichmentJoin ownerOf(String field) {
        for (final EnrichmentJoin enrichment : enrichments) {
            if (enrichment.exposedNames().contains(field)) {
                return enrichment;
            }
        }
        return null;
    }

    /**
     * This model as one statement renders it, given the {@code object} columns whose grain join that
     * statement emitted as a derived table projecting subcolumns: the named bindings carry their pruned
     * paths, every other binding is unchanged, and the map keeps its order.
     *
     * <p>Called only by {@link EnrichmentJoins}, and only for a join it actually keyed, because the
     * alias a pruned path renders against exists only in a derived table. Stamping a binding whose join
     * was emitted bare would render a reference to a column of the enrichment table that is not there.
     *
     * @param pruned exposed column name → the paths its derived table projects; an entry with an empty
     *     path set is ignored, so {@link ObjectPathUsage#none()} leaves the model identical
     */
    public QueryEntityModel withPrunedPaths(Map<String, Set<List<String>>> pruned) {
        if (pruned.isEmpty()) {
            return this;
        }
        final Map<String, QueryFieldBinding> rewritten = new LinkedHashMap<>(bindings);
        boolean changed = false;
        for (final Map.Entry<String, Set<List<String>>> entry : pruned.entrySet()) {
            final QueryFieldBinding binding = rewritten.get(entry.getKey());
            if (binding == null || entry.getValue().isEmpty()) {
                continue;
            }
            rewritten.put(entry.getKey(), binding.prunedTo(entry.getValue()));
            changed = true;
        }
        return changed
                ? new QueryEntityModel(entity, table, rewritten, enrichments, baseSchema, tagOrder, partitionLocal, collapsing, finalCapable)
                : this;
    }
}
