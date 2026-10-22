package com.epam.aidial.analytics.service.query;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one rule for what a <strong>wildcard projection</strong> expands to: the entity's source columns
 * that are not marked {@code heavy}, in catalog (binding) order.
 *
 * <p>Every "give me everything" surface derives its projection from here, so they cannot disagree:
 *
 * <ul>
 *   <li>a row-mode DSL query with an omitted/empty {@code select}
 *       ({@code translate.StructuredQueryBuilder});
 *   <li>rendering that document back to the external SQL dialect
 *       ({@code sql.StructuredQueryToSqlRenderer});
 *   <li>a SQL {@code SELECT *} / {@code <alias>.*} ({@code sql.SqlStarExpander}, via the Calcite
 *       catalog snapshot).
 * </ul>
 *
 * <p>Two exclusions, for two different reasons:
 *
 * <ul>
 *   <li><strong>heavy</strong> columns are expensive to transfer (raw payload/blob columns), and the
 *       canonical first query of an external client — {@code SELECT * FROM <entity>} through the
 *       Flight SQL / ADBC pandas connector — should not pay for them. They stay resolvable by name in
 *       every clause; heaviness is a cost hint, never access control.
 *   <li><strong>enrichment</strong> columns are opt-in: naming one is what pulls its {@code LEFT JOIN}
 *       into the query, so a wildcard must never fan out into joins. They remain addressable by their
 *       qualified flat name ({@code <enrichment_table>.<column>}).
 * </ul>
 *
 * <p>Note that a caller-invisible {@code sensitive} column is already absent from the model
 * ({@code CatalogQueryService}), so it needs no filtering here.
 */
public final class WildcardProjection {

    private WildcardProjection() {}

    /**
     * The flat field names a wildcard projects for {@code model} (possibly empty), in the bindings'
     * insertion order — the catalog's column order, which {@link QueryEntityModel} guarantees is stable
     * because this projection's order is observable in rendered SQL and in the Arrow schema. Every
     * returned name is a key of {@code model.bindings()}, so callers may resolve it without a check.
     */
    public static List<String> sourceFields(QueryEntityModel model) {
        final Set<String> enrichmentColumns = new HashSet<>();
        for (final EnrichmentJoin enrichment : model.enrichments()) {
            enrichmentColumns.addAll(enrichment.exposedNames());
        }
        final List<String> names = new ArrayList<>(model.bindings().size());
        for (final Map.Entry<String, QueryFieldBinding> entry : model.bindings().entrySet()) {
            if (!entry.getValue().heavy() && !enrichmentColumns.contains(entry.getKey())) {
                names.add(entry.getKey());
            }
        }
        return names;
    }

    /**
     * The message for a wildcard that expands to nothing — every source column visible to the caller is
     * heavy. Shared so the DSL and SQL surfaces reject with identical wording.
     */
    public static String emptyExpansionMessage(String entity) {
        return "a wildcard projection over '" + entity + "' would return no columns: every column visible to "
                + "you is marked heavy; name the columns you need explicitly";
    }
}
