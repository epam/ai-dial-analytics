package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.exception.IllegalFinalException;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableWriteMode;

/**
 * The one place that decides whether a user-facing read of an entity is {@code FINAL} (deduplicated,
 * latest-version-wins) or raw. Every surface asks it — the structured query, each entity reference of a SQL
 * statement, the translation endpoints — so they cannot disagree about what a query reads.
 *
 * <p><b>The unit is the entity</b>, and an entity is <em>final-capable</em> when anything it reads collapses
 * versions: its source is {@code upsert_by_key}, or it has an enrichment (always a
 * {@code ReplacingMergeTree}). A final read renders {@code FINAL} on each table of the entity that collapses —
 * the source when it does, every enrichment the query joins — and on nothing else, since ClickHouse refuses
 * the modifier on a plain {@code MergeTree}.
 *
 * <p>The order of the rules is the contract:
 *
 * <ol>
 *   <li>an explicit {@code FINAL} on an entity that collapses nothing — an {@code append} source with no
 *       enrichment, a bare plain {@code MergeTree}, which ClickHouse refuses {@code FINAL} on — is refused
 *       first, so the migration flag never hides the refusal a client will meet once the flag is off;
 *   <li>an entity that collapses nothing is read raw — it has nothing to deduplicate;
 *   <li>{@code force} (the {@code query.force-final} migration flag) reads every other entity final;
 *   <li>an absent request reads it final;
 *   <li>otherwise the request decides.
 * </ol>
 *
 * <p>Pipeline reads never come here: they deduplicate through the query-level {@code final = 1} setting,
 * which resolves against the actual engine rather than the declared discipline.
 */
public final class FinalDecision {

    private FinalDecision() {
    }

    /**
     * Whether the read of {@code model} is final.
     *
     * @param requested the query's {@code final}: {@code null} when unstated
     * @param force whether {@code query.force-final} applies — {@code true} only on the execute paths
     * @throws IllegalFinalException when {@code requested} is {@code true} and the entity collapses nothing
     */
    public static boolean readsFinal(QueryEntityModel model, Boolean requested, boolean force) {
        return readsFinal(model.entity(), finalCapable(model), requested, force);
    }

    /** As {@link #readsFinal(QueryEntityModel, Boolean, boolean)} over the entity's name and capability. */
    public static boolean readsFinal(String entity, boolean finalCapable, Boolean requested, boolean force) {
        if (Boolean.TRUE.equals(requested) && !finalCapable) {
            throw new IllegalFinalException(entity);
        }
        if (!finalCapable) {
            return false;
        }
        return force || requested == null || requested;
    }

    /**
     * Whether anything {@code model} reads collapses versions: its source, or an active enrichment the caller
     * may read. Decided when the model is built ({@code QueryEntityModel.finalCapable()}) and always at
     * {@code TABLE_READ} — the bar execution resolves at — so translation (resolved at {@code TABLE_GET}) and
     * discovery never call an entity final-capable that execution would refuse {@code FINAL} on.
     */
    public static boolean finalCapable(QueryEntityModel model) {
        return model.finalCapable();
    }

    /** Whether {@code table}'s own engine collapses versions: its effective write discipline is {@code upsert_by_key}. */
    public static boolean collapses(TableDefinitionEntity table) {
        return table.effectiveWriteMode() == TableWriteMode.UPSERT_BY_KEY;
    }
}
