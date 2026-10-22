package com.epam.aidial.analytics.service.enrichment.validation;

import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import com.epam.aidial.analytics.service.query.sql.SqlTranslation;
import com.epam.aidial.analytics.service.query.translate.WindowExpressions;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Validates a declaration's boolean predicates — {@code filter} and a group pipeline's
 * {@code ready_when.signal} — against its read source by reusing the existing Calcite SQL front end. It
 * wraps the predicate as {@code SELECT * FROM <source> WHERE <predicate>} and lets the front end
 * enforce the bounded grammar (no joins/subqueries/CTEs — those fail closed), boolean WHERE, and
 * unknown-column rejection against the source's schema. A window function is refused on top of that, for
 * the reason {@link ExprSqlValidator} refuses one: a pipeline predicate must be decidable from one source
 * row, not from whichever rows a materialization or scan window happened to cover. On any front-end failure
 * it raises {@link PipelineValidationException} (422 {@code pipeline_validation_failed}). It then walks the
 * translated {@link FilterNode} tree to collect the referenced source column names, and confines the result
 * to the pipeline's read source via {@link SourceScopeGuard} — the statement is built by concatenation, so
 * what the front end accepted is not necessarily a read of that source.
 *
 * <p>A predicate calling a function the catalog declares non-deterministic is refused on the same terms
 * ({@link NonDeterministicFunctions}). This one entry point is where every governed predicate in the
 * service passes — a pipeline {@code filter}, a group trigger's {@code ready_when.signal}, a
 * {@code member_select.prefer_sql}, and an aggregate declaration's {@code filter} and per-measure
 * {@code where} — so the rule is applied once here rather than at each of the callers, none of which could
 * be relied on to remember it.
 */
@Component
@RequiredArgsConstructor
public class FilterSqlValidator {

    private final SqlQueryFrontEnd frontEnd;
    private final SourceScopeGuard sourceScope;

    /** The parsed filter node (null for a blank filter) and the source columns it references. */
    public record FilterAnalysis(FilterNode filter, Set<String> columns) {}

    /**
     * Validates {@code filterSql} against {@code sourceEntity} and returns the set of referenced
     * source columns (insertion-ordered). A blank filter references no columns.
     */
    public Set<String> validateAndCollectColumns(String sourceEntity, String filterSql) {
        return analyze(sourceEntity, filterSql).columns();
    }

    /**
     * As {@link #validateAndCollectColumns(String, String)}, with {@code allowEnrichmentColumns} admitting
     * the qualified columns of {@code sourceEntity}'s ACTIVE enrichments — for a caller that emits the
     * grain join, and only such a caller.
     */
    public Set<String> validateAndCollectColumns(
            String sourceEntity, String filterSql, boolean allowEnrichmentColumns) {
        return analyze(sourceEntity, filterSql, "filter", allowEnrichmentColumns).columns();
    }

    /**
     * Validates {@code filterSql} against {@code sourceEntity}, returning both the parsed
     * {@link FilterNode} (for the scan to translate into a jOOQ condition) and its referenced
     * columns. A blank filter yields a null node and no columns.
     */
    public FilterAnalysis analyze(String sourceEntity, String filterSql) {
        return analyze(sourceEntity, filterSql, "filter");
    }

    /**
     * As {@link #analyze(String, String)}, with {@code what} naming the predicate in any error message
     * so a rejected readiness signal does not report itself as a rejected filter.
     *
     * @throws PipelineValidationException (422) if the predicate does not parse under the bounded grammar,
     *     or reaches outside {@code sourceEntity} (see {@link SourceScopeGuard})
     */
    public FilterAnalysis analyze(String sourceEntity, String filterSql, String what) {
        return analyze(sourceEntity, filterSql, what, false);
    }

    /**
     * As {@link #analyze(String, String, String)}, with {@code allowEnrichmentColumns} admitting the
     * qualified columns of {@code sourceEntity}'s ACTIVE enrichments — for a caller that emits the grain
     * join, and only such a caller. See {@link SourceScopeGuard#requireSourceScoped(String, String, Set,
     * String, boolean)} for why the two paths differ.
     */
    public FilterAnalysis analyze(
            String sourceEntity, String filterSql, String what, boolean allowEnrichmentColumns) {
        Set<String> columns = new LinkedHashSet<>();
        if (StringUtils.isBlank(filterSql)) {
            return new FilterAnalysis(null, columns);
        }
        SqlTranslation translation;
        try {
            // The probe projects a literal, never `*`: only the WHERE clause is consumed, and a star would
            // couple predicate validation to the wildcard-projection rules — an entity whose every visible
            // column is heavy rejects a star (see WildcardProjection), which would surface here as an
            // invalid filter the caller never wrote.
            translation = frontEnd.translate("SELECT 1 FROM " + sourceEntity + " WHERE " + filterSql);
        } catch (SqlTranslationException ex) {
            throw new PipelineValidationException(what + " is invalid: " + ex.getMessage());
        }
        FilterNode filter = translation.query().filter();
        if (WindowExpressions.containsWindow(filter)) {
            // Belt and braces: Calcite refuses OVER in a WHERE, so this is unreachable through the probe
            // above. It is asserted anyway because a rule predicate is rendered into the materialize and
            // scan statements, where a window would be evaluated over the rows of a bounded window rather
            // than over one source row — the same reason ExprSqlValidator refuses one.
            throw new PipelineValidationException(what + " uses a window function; a rule predicate must be"
                    + " decidable from one source row");
        }
        final String volatileFunction = NonDeterministicFunctions.findIn(filter);
        if (volatileFunction != null) {
            throw new PipelineValidationException(NonDeterministicFunctions.message(what, volatileFunction));
        }
        ExprColumnCollector.collect(filter, columns);
        sourceScope.requireSourceScoped(
                sourceEntity, translation.query().entity(), columns, what, allowEnrichmentColumns);
        return new FilterAnalysis(filter, columns);
    }
}
