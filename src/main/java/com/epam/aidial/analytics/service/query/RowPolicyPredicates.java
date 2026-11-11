package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LambdaParamExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.ParamExpr;
import com.epam.aidial.analytics.service.query.model.ScalarSubqueryExpr;
import com.epam.aidial.analytics.service.query.model.SubqueryNode;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.translate.FilterTranslator;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionDto;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionGroup;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Validates and renders a row policy's filter against the <b>table-only</b> model of the table it is stored
 * on ({@link CatalogQueryService#tableBindings}), with the query surface's own {@link FilterTranslator} — so
 * field-name to physical-column mapping, literal typing, enum and object comparisons are exactly what a
 * {@code filter} in {@code POST /v1/queries/execute} would get. The rendered text is a ClickHouse predicate
 * with every value inlined as a literal, ready to be one value of {@code additional_table_filters}.
 *
 * <p>A policy is narrower than a query filter in what it may contain, and the scope is checked here before
 * translation: no {@code param} other than a caller value, no aggregate, ordered-set or window
 * function and no window expression (meaningless per row), no subquery, and no function the catalog declares
 * as calling a service outside the data store (it would run per row on every read of the table). A dotted
 * enrichment field needs no rule of its own: the table-only model binds bare names only, so it is an
 * unknown field like any other.
 *
 * <p><b>Caller values.</b> A {@code param} named {@link #CALLER_USER_HASH} or {@link #CALLER_PROJECT} stands
 * for the reading caller's own identity and is admitted in one position only: a direct operand of {@code eq}
 * or {@code ne} against a {@code string} field. On a read the comparison is rewritten <em>before</em> it
 * reaches the translator: a known value becomes a string literal; an unknown one replaces the whole
 * comparison with a predicate that is {@code NULL} for every row, so it matches nothing, its negation
 * matches nothing, and an {@code OR} still matches through another branch. The position rule is what keeps
 * that sound — a function around the value ({@code coalesce(caller.user_hash, '')}) could turn the unknown
 * into a constant, and rows written for callers without the identity store exactly that constant.
 */
@Component
@RequiredArgsConstructor
public class RowPolicyPredicates {

    /** The rendered predicate cap per policy, in UTF-8 bytes; it travels in the request URL on every read. */
    public static final int MAX_RENDERED_BYTES = 16 * 1024;

    /** The caller's user hash, comparable with a {@code user_hash} column. */
    public static final String CALLER_USER_HASH = "caller.user_hash";

    /** The project of the caller's project API key. */
    public static final String CALLER_PROJECT = "caller.project";

    /** A longer caller value is unknown, which bounds what a read can add to a predicate accepted at save time. */
    public static final int MAX_CALLER_VALUE_BYTES = 512;

    private static final Set<String> CALLER_VALUES = Set.of(CALLER_USER_HASH, CALLER_PROJECT);

    private static final String CALLER_VALUE_PLACEMENT = "may use a caller value (" + CALLER_USER_HASH + ", "
            + CALLER_PROJECT + ") only as a direct operand of 'eq' or 'ne' against a string field of the table";

    /** The largest a caller value can render: every byte one that literal escaping doubles. */
    private static final String WORST_CASE_VALUE = "'".repeat(MAX_CALLER_VALUE_BYTES);

    /** {@code NULL} as a filter-typed operand: no row matches it, and no row matches its negation. */
    private static final Condition UNKNOWN = DSL.condition("CAST(NULL, 'Nullable(UInt8)')");

    private final CatalogQueryService catalog;
    private final FilterTranslator filterTranslator;
    private final DSLContext renderContext;

    /**
     * Validates {@code filter} for storing: rendered with every caller value at its worst-case length, so a
     * policy accepted here cannot exceed {@link #MAX_RENDERED_BYTES} on a read.
     *
     * @throws QueryValidationException as {@link #render(String, FilterNode, UnaryOperator)}
     */
    public String render(String table, FilterNode filter) {
        return render(table, filter, ignored -> WORST_CASE_VALUE);
    }

    /**
     * {@code filter} rendered as a ClickHouse predicate over {@code table}'s own columns.
     *
     * @param callerValues the reading caller's value for a caller-value name, or {@code null} when it has none
     * @throws QueryValidationException if the filter is outside the policy scope, does not validate against
     *     the table's columns, or renders longer than {@link #MAX_RENDERED_BYTES}
     */
    public String render(String table, FilterNode filter, UnaryOperator<String> callerValues) {
        if (filter == null) {
            throw new QueryValidationException("a row policy filter must not be empty");
        }
        final Map<String, QueryFieldBinding> bindings = catalog.tableBindings(table);
        requireInScope(filter, bindings);
        final Condition condition = toCondition(filter, bindings, callerValues);
        final String rendered = renderContext.renderInlined(condition);
        if (rendered.getBytes(StandardCharsets.UTF_8).length > MAX_RENDERED_BYTES) {
            throw new QueryValidationException("the filter renders to more than " + MAX_RENDERED_BYTES
                    + " bytes; a row policy travels with every read of the table, so keep it small");
        }
        return rendered;
    }

    /** {@code value} when a policy may compare with it, or {@code null}: absent, blank and over-long are unknown. */
    public static String knownCallerValue(String value) {
        return StringUtils.isBlank(value) || value.getBytes(StandardCharsets.UTF_8).length > MAX_CALLER_VALUE_BYTES
                ? null : value;
    }

    /**
     * Translates the tree, handing every subtree without a caller value to the translator untouched, so a
     * policy that uses none renders exactly as it always did.
     */
    private Condition toCondition(
            FilterNode node, Map<String, QueryFieldBinding> bindings, UnaryOperator<String> callerValues) {
        return switch (node) {
            case LogicalNode logical when usesCallerValue(logical) -> {
                final List<Condition> args = logical.args().stream()
                        .map(arg -> toCondition(arg, bindings, callerValues))
                        .toList();
                yield combine(logical.op(), args);
            }
            case ComparisonNode comparison when usesCallerValue(comparison) -> {
                final List<Expr> bound = comparison.args().stream()
                        .map(arg -> arg instanceof ParamExpr param ? callerLiteral(callerValues.apply(param.name())) : arg)
                        .toList();
                yield bound.contains(null)
                        ? UNKNOWN : filterTranslator.toCondition(new ComparisonNode(comparison.op(), bound), bindings);
            }
            case null, default -> filterTranslator.toCondition(node, bindings);
        };
    }

    private static Condition combine(LogicalOp op, List<Condition> args) {
        if (op == LogicalOp.NOT) {
            if (args.size() != 1) {
                throw new QueryValidationException("'not' expects exactly one child node");
            }
            return DSL.not(args.getFirst());
        }
        return op == LogicalOp.AND ? DSL.and(args) : DSL.or(args);
    }

    private static Expr callerLiteral(String value) {
        final String known = knownCallerValue(value);
        return known == null ? null : new ValueExpr(ValueType.STRING, known);
    }

    private static boolean isCallerValue(Expr expr) {
        return expr instanceof ParamExpr param && CALLER_VALUES.contains(param.name());
    }

    private static boolean usesCallerValue(FilterNode node) {
        return switch (node) {
            case LogicalNode logical -> logical.args() != null
                    && logical.args().stream().anyMatch(RowPolicyPredicates::usesCallerValue);
            case ComparisonNode comparison -> comparison.args() != null
                    && comparison.args().stream().anyMatch(RowPolicyPredicates::isCallerValue);
            case null, default -> false;
        };
    }

    private static void requireInScope(FilterNode node, Map<String, QueryFieldBinding> bindings) {
        switch (node) {
            case null -> { }
            case LogicalNode logical -> {
                if (logical.args() != null) {
                    logical.args().forEach(arg -> requireInScope(arg, bindings));
                }
            }
            case ComparisonNode comparison -> {
                if (usesCallerValue(comparison)) {
                    requireCallerComparison(comparison, bindings);
                } else if (comparison.args() != null) {
                    comparison.args().forEach(RowPolicyPredicates::requireExprInScope);
                }
            }
            case SubqueryNode ignored -> throw new QueryValidationException("a row policy may not contain a subquery");
        }
    }

    /**
     * The one shape a caller value is admitted in: {@code eq} or {@code ne} between it and a {@code string}
     * field. A field the table does not store is left to the translator, which names it as unknown.
     */
    private static void requireCallerComparison(ComparisonNode comparison, Map<String, QueryFieldBinding> bindings) {
        final List<Expr> args = comparison.args();
        final boolean equality = comparison.op() == ComparisonOp.EQ || comparison.op() == ComparisonOp.NE;
        final FieldExpr field = args.size() == 2
                ? args.stream().filter(FieldExpr.class::isInstance).map(FieldExpr.class::cast).findFirst().orElse(null)
                : null;
        if (!equality || field == null) {
            throw new QueryValidationException(CALLER_VALUE_PLACEMENT);
        }
        final QueryFieldBinding binding = bindings.get(field.name());
        if (binding != null && binding.type() != QueryFieldType.STRING) {
            throw new QueryValidationException(CALLER_VALUE_PLACEMENT + "; '" + field.name() + "' is "
                    + binding.type().code());
        }
    }

    private static void requireExprInScope(Expr expr) {
        switch (expr) {
            case null -> { }
            case FieldExpr ignored -> { }
            case ValueExpr ignored -> { }
            case LambdaParamExpr ignored -> { }
            case ParamExpr param -> {
                if (CALLER_VALUES.contains(param.name())) {
                    throw new QueryValidationException(CALLER_VALUE_PLACEMENT);
                }
                throw new QueryValidationException("a row policy may not use a parameter ('" + param.name()
                        + "') other than the caller values " + CALLER_USER_HASH + " and " + CALLER_PROJECT
                        + ": there is nothing to bind it to when the policy is applied");
            }
            case FnExpr fn -> {
                requireScalar(fn.name());
                requireAll(fn.args());
            }
            case ArrayExpr array -> requireAll(array.items());
            case CaseExpr caseExpr -> {
                if (caseExpr.when() != null) {
                    for (final CaseBranch branch : caseExpr.when()) {
                        if (branch != null) {
                            if (usesCallerValue(branch.when())) {
                                throw new QueryValidationException(CALLER_VALUE_PLACEMENT);
                            }
                            requireInScope(branch.when(), Map.of());
                            requireExprInScope(branch.then());
                        }
                    }
                }
                requireExprInScope(caseExpr.otherwise());
            }
            case LambdaExpr lambda -> requireExprInScope(lambda.body());
            case WindowExpr ignored -> throw new QueryValidationException(
                    "a row policy may not contain a window expression; a policy is evaluated per row");
            case ScalarSubqueryExpr ignored -> throw new QueryValidationException(
                    "a row policy may not contain a subquery");
        }
    }

    private static void requireAll(List<Expr> exprs) {
        if (exprs != null) {
            exprs.forEach(RowPolicyPredicates::requireExprInScope);
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
