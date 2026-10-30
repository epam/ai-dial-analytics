package com.epam.aidial.analytics.service.enrichment.validation;

import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.ComputedFields;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Confines a pipeline's governed SQL to its <b>read source</b> — the table the pipeline declared, or its target
 * enrichment's {@code source_table} when it declared none. Both SQL-embedding validators
 * build their statement by concatenation — {@code SELECT * FROM <source> WHERE <predicate>} and
 * {@code SELECT <expr> AS __expr FROM <source>} — and the SQL front end only screens for <em>one
 * statement of SELECT kind</em>, so what comes back is not necessarily a read of the declared source at
 * all. This class is the check both of them apply afterwards, in one place so they cannot drift apart.
 *
 * <p>It closes two gaps that both end up in the same place — a referenced column the governance gate
 * cannot see:
 *
 * <ul>
 *   <li><b>A substituted FROM clause.</b> A fragment ending in a SQL line comment supplies its own
 *       source ({@code request_body FROM dial_usage_log --}), and the entitlement check then looks the
 *       collected names up in the <em>declared</em> source's {@code column_mapping}, where they are
 *       absent — so a {@code sensitive} column passes ungated. Only a caller entitled to that column may
 *       register a
 *       pipeline, so this is defence in depth rather than an open door, but a gate that is silently
 *       non-total is not a gate.
 *   <li><b>A column of something other than the entity being read.</b> The query model publishes every
 *       materialized enrichment's columns into its bindings (qualified {@code table.column}), and the
 *       front end validates against those bindings — so a filter or expression referencing <em>any</em>
 *       enrichment parses cleanly, including one belonging to a different source. A read emits a grain
 *       join only for an enrichment of its own source, so the rest leave a statement referencing a table
 *       absent from its own {@code FROM} clause; ClickHouse rejects it at execution. Rejecting it here
 *       turns a 400 at scan/materialize time into the 422 it always was.
 * </ul>
 *
 * <p>Both failures are {@link PipelineValidationException} (422 {@code pipeline_validation_failed}), which is
 * what every caller of these validators already expects — including
 * {@code SensitiveProjectionGuard#reattributing}, which re-attributes such a failure to a 403 for a
 * caller who may not read the source's sensitive columns.
 */
@Component
@RequiredArgsConstructor
public class SourceScopeGuard {

    private final ColumnMappingRepository columns;
    private final CatalogQueryService catalog;

    /**
     * Rejects a translated fragment that reads anything other than {@code source}.
     *
     * @param source     the pipeline's read source (its declared {@code source_table}, else its target's)
     * @param entity     the entity the front end actually translated the statement against
     * @param referenced the source columns the fragment references
     * @param what       label naming the fragment in the error message (e.g. {@code "filter"})
     * @throws PipelineValidationException (422) if the entity differs or a column is not the source's
     */
    public void requireSourceScoped(String source, String entity, Set<String> referenced, String what) {
        requireSourceScoped(source, entity, referenced, what, false);
    }

    /**
     * As above, but {@code allowEnrichmentColumns} additionally admits the qualified columns of the
     * source's ACTIVE enrichments.
     *
     * <p><b>Only a caller that emits the grain join may pass true.</b> The rejection this relaxes is not a
     * policy but a consequence: a statement that names an enrichment it does not join references a table
     * absent from its own {@code FROM}. Both the aggregate path and the enrich paths do emit the
     * join for an enrichment of the entity they read, so for them the reference resolves and executes —
     * which is why they share this guard with a flag rather than skipping it. What the flag never admits is
     * an enrichment of <em>another</em> source, which no statement here joins. Everything else the guard
     * enforces, the substituted-{@code FROM} check especially, still applies unchanged.
     */
    public void requireSourceScoped(
            String source, String entity, Set<String> referenced, String what, boolean allowEnrichmentColumns) {
        // The front end parses case-insensitively, so a differing spelling is still the same entity.
        if (entity != null && !entity.equalsIgnoreCase(source)) {
            throw new PipelineValidationException(what + " must read only its pipeline's source '" + source
                    + "', but resolves against '" + entity + "'");
        }
        if (referenced.isEmpty()) {
            return;
        }
        Set<String> known = knownColumns(source, allowEnrichmentColumns);
        for (String column : referenced) {
            // Every governed fragment passes here, so this is the one place a computed field in SQL is refused by
            // name: its value exists only after a read's statement has run, so no fragment can compute over it.
            if (ComputedFields.isComputed(column)) {
                throw new PipelineValidationException(ComputedFields.declarationMessage(what, column));
            }
            if (!known.contains(column)) {
                throw new PipelineValidationException(what + " references '" + column
                        + "', which is not a column of source '" + source + "'"
                        + (allowEnrichmentColumns
                                ? "; name an enrichment column '<enrichment>.<column>'"
                                : "; a pipeline's SQL may reference only its source's own columns"
                                        + " (an enrichment column is not joined on a pipeline-scoped read)"));
            }
        }
    }

    /** Either spelling of every declared source column, matching the convention the gate uses elsewhere. */
    private Set<String> knownColumns(String source, boolean allowEnrichmentColumns) {
        List<ColumnMappingEntity> declared = columns.findByTableNameOrderByOrdinal(source);
        Set<String> known = new HashSet<>(declared.size() * 2);
        for (ColumnMappingEntity column : declared) {
            known.add(column.getName());
            known.add(column.getSourceName());
        }
        if (allowEnrichmentColumns) {
            for (TableDefinitionEntity enrichment : catalog.activeEnrichmentsOf(source)) {
                for (ColumnMappingEntity column : columns.findByTableNameOrderByOrdinal(enrichment.getName())) {
                    // The qualified flat name only: an enrichment column has no unqualified spelling in the
                    // entity namespace, and admitting a bare one would let it shadow a source column.
                    known.add(enrichment.getName() + "." + column.getName());
                }
            }
        }
        return known;
    }
}
