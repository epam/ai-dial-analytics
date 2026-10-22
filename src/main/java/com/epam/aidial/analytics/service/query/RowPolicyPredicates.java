package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LambdaParamExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.ParamExpr;
import com.epam.aidial.analytics.service.query.model.ScalarSubqueryExpr;
import com.epam.aidial.analytics.service.query.model.SubqueryNode;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.translate.FilterTranslator;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionDto;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionGroup;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Validates and renders a row policy's filter against the <b>table-only</b> model of the table it is stored
 * on ({@link CatalogQueryService#tableBindings}), with the query surface's own {@link FilterTranslator} — so
 * field-name to physical-column mapping, literal typing, enum and object comparisons are exactly what a
 * {@code filter} in {@code POST /v1/queries/execute} would get. The rendered text is a ClickHouse predicate
 * with every value inlined as a literal, ready to be one value of {@code additional_table_filters}.
 *
 * <p>A policy is narrower than a query filter in what it may contain, and the scope is checked here before
 * translation: no {@code param} (there is nothing to bind it to), no aggregate, ordered-set or window
 * function and no window expression (meaningless per row), no subquery, and no function the catalog declares
 * as calling a service outside the data store (it would run per row on every read of the table). A dotted
 * enrichment field needs no rule of its own: the table-only model binds bare names only, so it is an
 * unknown field like any other.
 */
@Component
@RequiredArgsConstructor
public class RowPolicyPredicates {

    /** The rendered predicate cap per policy, in UTF-8 bytes; it travels in the request URL on every read. */
    public static final int MAX_RENDERED_BYTES = 16 * 1024;

    private final CatalogQueryService catalog;
    private final FilterTranslator filterTranslator;
    private final DSLContext renderContext;

    /**
     * {@code filter} rendered as a ClickHouse predicate over {@code table}'s own columns.
     *
     * @throws QueryValidationException if the filter is outside the policy scope, does not validate against
     *     the table's columns, or renders longer than {@link #MAX_RENDERED_BYTES}
     */
    public String render(String table, FilterNode filter) {
        if (filter == null) {
            throw new QueryValidationException("a row policy filter must not be empty");
        }
        requireInScope(filter);
        final Map<String, QueryFieldBinding> bindings = catalog.tableBindings(table);
        final Condition condition = filterTranslator.toCondition(filter, bindings);
        final String rendered = renderContext.renderInlined(condition);
        if (rendered.getBytes(StandardCharsets.UTF_8).length > MAX_RENDERED_BYTES) {
            throw new QueryValidationException("the filter renders to more than " + MAX_RENDERED_BYTES
                    + " bytes; a row policy travels with every read of the table, so keep it small");
        }
        return rendered;
    }

    private static void requireInScope(FilterNode node) {
        switch (node) {
            case null -> { }
            case LogicalNode logical -> {
                if (logical.args() != null) {
                    logical.args().forEach(RowPolicyPredicates::requireInScope);
                }
            }
            case ComparisonNode comparison -> {
                if (comparison.args() != null) {
                    comparison.args().forEach(RowPolicyPredicates::requireInScope);
                }
            }
            case SubqueryNode ignored -> throw new QueryValidationException("a row policy may not contain a subquery");
        }
    }

    private static void requireInScope(Expr expr) {
        switch (expr) {
            case null -> { }
            case FieldExpr ignored -> { }
            case ValueExpr ignored -> { }
            case LambdaParamExpr ignored -> { }
            case ParamExpr param -> throw new QueryValidationException("a row policy may not use a parameter ('"
                    + param.name() + "'): there is nothing to bind it to when the policy is applied");
            case FnExpr fn -> {
                requireScalar(fn.name());
                requireAll(fn.args());
            }
            case ArrayExpr array -> requireAll(array.items());
            case CaseExpr caseExpr -> {
                if (caseExpr.when() != null) {
                    for (final CaseBranch branch : caseExpr.when()) {
                        if (branch != null) {
                            requireInScope(branch.when());
                            requireInScope(branch.then());
                        }
                    }
                }
                requireInScope(caseExpr.otherwise());
            }
            case LambdaExpr lambda -> requireInScope(lambda.body());
            case WindowExpr ignored -> throw new QueryValidationException(
                    "a row policy may not contain a window expression; a policy is evaluated per row");
            case ScalarSubqueryExpr ignored -> throw new QueryValidationException(
                    "a row policy may not contain a subquery");
        }
    }

    private static void requireAll(List<Expr> exprs) {
        if (exprs != null) {
            exprs.forEach(RowPolicyPredicates::requireInScope);
        }
    }

    /**
     * Refuses a catalog function outside the scalar group, or one declared as calling out of the store. An
     * unknown name passes here and is refused by the translator as an unsupported function, which is the
     * accurate reason.
     */
    private static void requireScalar(String name) {
        final QueryFunctionDto function = QueryFunctionCatalog.describe(name);
        if (function == null) {
            return;
        }
        if (function.group() != QueryFunctionGroup.SCALAR) {
            throw new QueryValidationException("a row policy may not call the " + function.group().code()
                    + " function '" + function.name() + "'; a policy is evaluated per row");
        }
        if (QueryFunctionCatalog.callsExternalService(function.name())) {
            throw new QueryValidationException("a row policy may not call '" + function.name()
                    + "', which calls a service outside the data store on every row it reads");
        }
    }
}
