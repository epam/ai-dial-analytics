package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.enrichment.support.SensitiveProjectionGuard;
import com.epam.aidial.analytics.service.enrichment.validation.FilterSqlValidator;

/**
 * Analyses an aggregate pipeline's membership predicates through the shared {@link FilterSqlValidator},
 * with the sensitive-column re-attribution every such analysis needs applied uniformly.
 *
 * <p><b>Why the re-attribution is the whole job.</b> A caller who may not read the input's sensitive
 * columns sees a schema with those columns hidden, so a predicate over one fails as an <em>unknown
 * column</em> — a parse failure that is really an entitlement refusal, and would otherwise answer 422
 * {@code pipeline_validation_failed} where the contract promises 403
 * {@code sensitive_column_not_entitled}. {@link SensitiveProjectionGuard#reattributing} recognises that
 * case and returns an entitled caller's failure untouched, so a genuinely malformed filter still reports
 * as one. Doing it in this one place is what keeps registration and refresh from reporting the same
 * refusal two different ways.
 *
 * <p>It no longer converts the exception type, and that removal is the point rather than an omission: the
 * shared front end raises {@link PipelineValidationException} itself since the enrichment registry folded
 * into this one, so the old re-wrap into the same type only discarded the original as the cause.
 */
final class PipelineFilters {

    /** Names this capability in a sensitive-column refusal message. */
    private static final String OPERATION = "aggregate";

    private PipelineFilters() {
    }

    /**
     * Analyses {@code filterSql} against {@code inputTable}; a blank filter yields an empty analysis.
     *
     * @throws com.epam.aidial.analytics.exception.SensitiveColumnNotEntitledException (403) when the
     *     failure is attributable to the caller's narrowed schema view
     * @throws PipelineValidationException (422) on a genuine grammar failure
     */
    static FilterSqlValidator.FilterAnalysis analyze(FilterSqlValidator validator,
            SensitiveProjectionGuard sensitiveGuard, String inputTable, String filterSql) {
        // Enrichment columns are admitted, and are included in the re-attribution for the same reason:
        // unlike a pipeline-scoped read, every statement the aggregate path compiles emits the grain join
        // for an enrichment it references — so a predicate here may name one, and asking only about the
        // input source would answer 422 whenever the source declares no sensitive column of its own.
        return sensitiveGuard.reattributing(inputTable, OPERATION,
                () -> validator.analyze(inputTable, filterSql, "filter", true), true);
    }
}
