package com.epam.aidial.analytics.service.pipeline;

import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.clickhouse.ClickHouseBudget;
import com.epam.aidial.analytics.clickhouse.FinalSettings;
import com.epam.aidial.analytics.clickhouse.StatementTag;
import com.epam.aidial.analytics.configuration.ResourceBudget;
import com.epam.aidial.analytics.exception.PipelineReadBudgetExceededException;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.repository.StructuredQueryExecutor;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.jooq.conf.ParamType;

import java.util.List;
import java.util.Map;

/**
 * The resource ceiling every pipeline-internal read runs under, and the translation of ClickHouse's refusal
 * into the error the runner sees. The mechanics are {@link ClickHouseBudget}'s, shared with the query
 * surface's budget and the aggregate preview's; what this class owns is the exception these endpoints report
 * and the remedy it names.
 *
 * <p>Applied at every {@code executeScan} call site the pipeline machinery owns — the enrichment scan page,
 * both phases of a group fetch, the materialize and refresh window probes, and every read an {@code enrich}
 * preview issues (which that preview reports under its own code; see {@code EnrichPreviewService}) —
 * and, through {@link #refusal}, to the Class-1 materialize write and the aggregate refresh write (both
 * {@code INSERT … SELECT}).
 *
 * <p>The write was once exempt, on the reasoning that its cost is bounded by the window its probe already
 * fixed. That holds only if probe and write read alike, and they do not: each statement emits a grain join
 * for the enrichments <em>its own</em> clauses reference, so a pipeline declaring no filter does its entire
 * enrichment read inside the write. Left exempt, that was the one unbounded statement in the service, and
 * it can be the largest single consumer of memory on the node. It is
 * bounded now, with narrowing rather than refusal as the first answer — a legitimately large window is
 * written in more passes instead of failing.
 *
 * <p>Public rather than package-private because the enrichment services live in a sibling package and must
 * use the same ceiling: two copies of a blast-radius bound is how one of them ends up not having it, which
 * is the defect this class exists to close.
 */
public final class PipelineReadBudget {

    /** What reduces the cost of a read already at its smallest window; the default remedy a refusal names. */
    private static final String READ_REMEDY = "A limit bounds the rows returned, not the rows read, and this read "
            + "is already at its smallest: reduce the heavy columns the declaration binds, give the enrichment "
            + "holding them a partition alignment so a point read can prune, or have the budget raised.";

    private PipelineReadBudget() {
    }

    /**
     * Runs {@code select} under the budget and reports a refusal as this capability's own error.
     *
     * <p>The pairing lives here rather than at each call site because the two halves are one decision: a
     * site that attached the settings but not the translation would report the ceiling it configured as a
     * bare {@code bad_request}, telling the runner to stop retrying a row that was never malformed. There
     * are seven such sites across two packages, so the pairing is enforced by there being one way to run
     * these statements at all.
     *
     * <p>{@code model} is the entity the statement was rendered against, so the read deduplicates inside each
     * partition when the entity is partition-local (see {@code FinalSettings}); every site holds it, having
     * emitted its joins from it.
     *
     * <p>{@code budget} is any {@link ResourceBudget}, so a call that runs several statements passes what is
     * left of its one ceiling ({@link ClickHouseBudget#remaining}) rather than a fresh one per statement.
     *
     * <p>Each run mints a fresh query id from {@code tag} (see {@link StatementTag}), so a re-probe is its own
     * row in {@code system.query_log} rather than a second statement under the first one's id. The request-body rebuild
     * statements a computed field costs get ids of their own from the same tag, under stage {@code <stage>-rebuild}.
     *
     * @param what the read being run, in the runner's own vocabulary, for the refusal message
     */
    public static List<Map<String, Object>> run(
            StructuredQueryExecutor executor,
            SelectQuery<Record> select,
            QueryEntityModel model,
            String what,
            ResourceBudget budget,
            StatementTag tag) {
        final QuerySettings settings = settings(budget);
        tag.applyTo(settings);
        try {
            return executor.executeScan(select, settings, model, tag);
        } catch (RuntimeException ex) {
            throw translate(ex, what, budget, READ_REMEDY);
        }
    }

    /**
     * {@code select} as {@link #run} would execute it under {@code budget}: inlined, with a {@code SETTINGS} clause
     * naming the deduplication and the budget's ceilings, rendered through the same settings merge the executor
     * applies so the printed statement cannot come to carry different settings from the one that ran.
     */
    public static String render(SelectQuery<Record> select, QueryEntityModel model, ResourceBudget budget) {
        return FinalSettings.render(select.getSQL(ParamType.INLINED), settings(budget), model.partitionLocal());
    }

    /**
     * The refusal {@code ex} amounts to under {@code budget}, or {@code ex} itself when it is not one of the
     * budget's.
     *
     * <p>Public for the budgeted statements this class cannot run for the caller: the Class-1 materialize and
     * aggregate refresh {@code INSERT … SELECT}s, which go through the insert executor rather than
     * {@code executeScan}. The pairing this class exists to enforce — ceiling attached, refusal translated —
     * still holds there, because the caller attaches the same {@code budget} it passes here.
     */
    public static RuntimeException refusal(RuntimeException ex, String what, ResourceBudget budget) {
        return translate(ex, what, budget, READ_REMEDY);
    }

    /**
     * As {@link #refusal(RuntimeException, String, ResourceBudget)}, naming {@code remedy} rather than the
     * read's: the aggregate refresh write recomputes every group key its window touches, so what lowers its
     * cost is a different lever.
     */
    public static RuntimeException refusal(RuntimeException ex, String what, ResourceBudget budget, String remedy) {
        return translate(ex, what, budget, remedy);
    }

    /** The budget as ClickHouse query settings, built from {@code pipeline.budget.*} or what is left of it. */
    private static QuerySettings settings(ResourceBudget budget) {
        return ClickHouseBudget.settings(budget);
    }

    /**
     * Translates a ClickHouse refusal into {@link PipelineReadBudgetExceededException} when it is one of the
     * budget's, and rethrows everything else untouched.
     *
     * <p>The message names the ceiling, its configured value and the two things that actually reduce the
     * cost, because the remedies that work here are not the ones that work on the query surface: a
     * pipeline's own {@code limit} is already as low as it goes (a group fetch that reads one member reads
     * one member), so what is left is reading fewer heavy columns, or giving the enrichment holding them a
     * partition alignment so a point read can prune.
     *
     * @param what the read that was refused, named as the runner's own vocabulary has it ({@code "group
     *     fetch"}, {@code "enrichment scan"}), so the message identifies the stage without the caller
     *     having to correlate it
     * @param remedy the sentence naming what reduces the cost
     */
    private static RuntimeException translate(
            RuntimeException ex, String what, ResourceBudget budget, String remedy) {
        return ClickHouseBudget.translate(ex, budget, (exceeded, cause) ->
                new PipelineReadBudgetExceededException("this " + what + " exceeded " + exceeded
                        + " (pipeline.budget.*). " + remedy, exceeded, what, cause));
    }
}
