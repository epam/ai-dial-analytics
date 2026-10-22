package com.epam.aidial.analytics.service.enrichment.validation;

import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.sql.ResultColumnKind;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import com.epam.aidial.analytics.service.query.sql.SqlTranslation;
import com.epam.aidial.analytics.service.query.translate.WindowExpressions;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The scalar sibling of {@link FilterSqlValidator}: validates a Class-1 ({@code type=sql}) output
 * variable's expression against its source. It wraps the expression as
 * {@code SELECT <expr> AS v FROM <source>} through the same Calcite SQL front end, so the bounded
 * grammar, the closed function catalog, and unknown-column rejection all apply identically to what a
 * pipeline filter gets. On any front-end failure it raises {@link PipelineValidationException}
 * (422 {@code pipeline_validation_failed}). Because that statement is assembled by concatenation, the result
 * is confined to the pipeline's read source by {@link SourceScopeGuard} before it is returned.
 *
 * <p>On top of the front end it enforces the three properties Class-1 depends on:
 *
 * <ul>
 *   <li><b>Scalar, not aggregate.</b> An aggregating expression is an <em>aggregation</em>, not a
 *       Class-1 enrichment, and would silently break the target's 1:0..1 grain. The check is free:
 *       an aggregate lowers the translated query to {@link QueryMode#AGGREGATE}, so asserting
 *       {@link QueryMode#ROW} rejects it.
 *   <li><b>No window function.</b> A window stays in {@link QueryMode#ROW} and so slips past the check
 *       above, but it is computed over the rows of the block it appears in — here, the rows of one
 *       materialization window. {@code row_number() OVER ()} would write a value that depends on which
 *       source rows that window happened to cover, and re-materializing an overlapping window would write
 *       a different one, breaking the exactly-once <em>effect</em> guarantee the whole path rests on. The
 *       tree is therefore walked for a {@code WindowExpr}: the query surfaces' position rules
 *       ({@code WindowExpressions.rejectInFilter} and friends) do not run on this path, and
 *       {@code MaterializeService} renders the analysed expression straight into its {@code INSERT …
 *       SELECT}.
 *   <li><b>A single value.</b> The expression must yield exactly one column, and an {@code object}-valued
 *       one is rejected: it is projection-only in the SQL subset and cannot be collapsed into a column
 *       bound to a grain. An {@code array} is <em>accepted</em> — a cell holding an array is still one
 *       value per grain, not many rows — and the output-binding check then requires the bound target
 *       column to be array-typed, with element types deliberately uncompared (see
 *       {@code EnrichmentTypeMapping#isCompatible}).
 * </ul>
 *
 * <p><b>Determinism is checked here, against the catalog's own declared property</b>
 * ({@link NonDeterministicFunctions}). It did not used to be, and did not need to be: the closed catalog
 * held no non-deterministic function, so the front end already rejected {@code now()} as an unsupported
 * name alongside {@code rand()} and {@code generateUUIDv4()}. Adding a clock to the query surfaces removed
 * that floor, so the guarantee the materialize path's exactly-once <em>effect</em> rests on — re-running a
 * window produces the same values — became something to enforce rather than something to observe. What has
 * NOT changed is where the fact lives: the catalog declares volatility per function and this asks it, so
 * there is still no hand-written denylist to rot as the catalog grows. Functions the catalog does not have
 * at all are still refused by the front end as unsupported names.
 */
@Component
@RequiredArgsConstructor
public class ExprSqlValidator {

    private static final String ALIAS = "__expr";

    private final SqlQueryFrontEnd frontEnd;
    private final SourceScopeGuard sourceScope;

    /**
     * A validated Class-1 expression: the translated DSL expression (ready for
     * {@code ExprTranslator.toField}), the source columns it references, and the result type the front
     * end inferred for it.
     */
    public record ExprAnalysis(Expr expr, Set<String> columns, QueryFieldType type) {}

    /**
     * Validates {@code exprSql} against {@code sourceEntity}.
     *
     * @param what a label naming the expression's owner for error messages (e.g. the output var name)
     * @throws PipelineValidationException (422) if the expression is blank, unparseable, aggregating,
     *     windowed, multi-valued, object-typed, references an unknown column or an uncatalogued
     *     function, or reaches outside {@code sourceEntity} (see {@link SourceScopeGuard})
     */
    public ExprAnalysis analyze(String sourceEntity, String exprSql, String what) {
        return analyze(sourceEntity, exprSql, what, false);
    }

    /**
     * As {@link #analyze(String, String, String)}, with {@code allowEnrichmentColumns} admitting the
     * qualified columns of {@code sourceEntity}'s ACTIVE enrichments. Only a caller that emits the grain
     * join may pass true — the Class-1 materialize path does, so an expression may derive from a sibling
     * enrichment at the same grain.
     */
    public ExprAnalysis analyze(
            String sourceEntity, String exprSql, String what, boolean allowEnrichmentColumns) {
        if (StringUtils.isBlank(exprSql)) {
            throw new PipelineValidationException("expression for " + what + " must not be blank");
        }
        SqlTranslation translation;
        try {
            translation = frontEnd.translate(
                    "SELECT " + exprSql + " AS " + ALIAS + " FROM " + sourceEntity);
        } catch (SqlTranslationException ex) {
            throw new PipelineValidationException("expression for " + what + " is invalid: " + ex.getMessage());
        }
        StructuredQuery query = translation.query();
        if (query.mode() != QueryMode.ROW) {
            throw new PipelineValidationException("expression for " + what
                    + " aggregates; a Class-1 enrichment must be a scalar expression over one source row"
                    + " (an aggregating declaration is an aggregation, which this capability does not provide)");
        }
        List<OutputColumn> select = query.select();
        if (select == null || select.size() != 1) {
            throw new PipelineValidationException("expression for " + what + " must yield exactly one value");
        }
        ResultColumnKind kind = translation.columns().getFirst().kind();
        Set<String> columns = new LinkedHashSet<>();
        Expr expr = select.getFirst().expr();
        if (WindowExpressions.containsWindow(expr)) {
            throw new PipelineValidationException("expression for " + what
                    + " uses a window function; a Class-1 enrichment must be a scalar expression over one"
                    + " source row, and a window is computed over the rows of the materialization window"
                    + " rather than over one source row (so re-materializing an overlapping window would"
                    + " write different values)");
        }
        final String volatileFunction = NonDeterministicFunctions.findIn(expr);
        if (volatileFunction != null) {
            throw new PipelineValidationException(
                    NonDeterministicFunctions.message("expression for " + what, volatileFunction));
        }
        ExprColumnCollector.collect(expr, columns);
        sourceScope.requireSourceScoped(
                sourceEntity, query.entity(), columns, "expression for " + what, allowEnrichmentColumns);
        return new ExprAnalysis(expr, columns, toFieldType(kind, what));
    }

    /**
     * Maps the front end's inferred runtime family onto the catalog's logical types, so the inferred
     * type can be compared against the output variable's declared type with the same
     * {@code EnrichmentTypeMapping} compatibility rule the output-binding check already uses.
     *
     * <p>{@code OBJECT} is the one family with no logical type to map onto. An {@code ARRAY} has one, and
     * needs no special handling beyond returning it: the shared compatibility rule is equality outside the
     * numeric set, so an array expression passes against an array-typed target column and fails against a
     * scalar one without this method or that one knowing anything about arrays in particular.
     */
    private static QueryFieldType toFieldType(ResultColumnKind kind, String what) {
        return switch (kind) {
            case STRING -> QueryFieldType.STRING;
            case INT32 -> QueryFieldType.INTEGER;
            case INT64 -> QueryFieldType.LONG;
            case FLOAT64, DECIMAL -> QueryFieldType.DECIMAL;
            case BOOL -> QueryFieldType.BOOLEAN;
            case DATE -> QueryFieldType.DATE;
            case TIMESTAMP -> QueryFieldType.TIMESTAMP;
            // An array is a single value, not many rows, so it does not break the target's 1:0..1 grain —
            // it is accepted here and the output-binding check then requires an array-typed target column.
            // Element types are deliberately not compared; see EnrichmentTypeMapping#isCompatible.
            case ARRAY -> QueryFieldType.ARRAY;
            // An object is still refused, and for a reason an array does not share: it is projection-only in
            // the SQL subset, so it cannot be collapsed into a column at all.
            case OBJECT -> throw new PipelineValidationException("expression for " + what
                    + " yields an object; an enrichment column bound to a grain takes a single scalar or an"
                    + " array");
        };
    }
}
