package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.QueryFunctionCatalog;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.SubqueryNode;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.model.WindowOrder;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionDto;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionGroup;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The position rules of a {@link WindowExpr}, shared by the builder (which decides where in a query a
 * window may stand) and the translators (which refuse one nested where it must not be).
 *
 * <p>A window is a per-row computation over the block's <em>result</em>, so it belongs in {@code select}
 * and nowhere else: not in a predicate (SQL forbids it in {@code WHERE}/{@code HAVING}), not inside an
 * aggregate's arguments (it is computed after aggregation), and not inside another window. In aggregate
 * mode its operands are judged like any other non-aggregated select expression — every bare field must be
 * a group key — because ClickHouse would otherwise refuse the statement with a message that names its own
 * internals rather than the caller's mistake.
 *
 * <p>The two {@code containsWindow} predicates are public because the rule-validation gate needs the same
 * walk: an enrichment expression is rendered into an {@code INSERT … SELECT} over a materialization window,
 * where a window function would compute over whichever rows that window happened to contain.
 */
public final class WindowExpressions {
    private WindowExpressions() {}

    /** Whether {@code expr} is or contains a window expression. */
    public static boolean containsWindow(Expr expr) {
        final boolean[] found = {false};
        visit(expr, e -> {
            if (e instanceof WindowExpr) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /** Whether {@code node} contains a window expression anywhere in its operands. */
    public static boolean containsWindow(FilterNode node) {
        final boolean[] found = {false};
        visitFilter(node, e -> {
            if (e instanceof WindowExpr) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /** Refuses a window expression nested anywhere below {@code expr}, naming {@code where} it was found. */
    static void rejectNested(Expr expr, String where) {
        if (containsWindow(expr)) {
            throw new QueryValidationException("a window expression is not allowed in " + where
                    + "; window expressions are allowed in 'select' entries only");
        }
    }

    /** Refuses a window expression anywhere in a filter tree, naming the clause. */
    static void rejectInFilter(FilterNode node, String clause) {
        if (containsWindow(node)) {
            throw new QueryValidationException("a window expression is not allowed in " + clause
                    + "; window expressions are allowed in 'select' entries only");
        }
    }

    /**
     * Aggregate-mode operand rule: every bare field the window's function arguments, partition keys or order
     * keys reference must be a {@code group_by} key (or a select alias resolving to one) or lie inside an
     * aggregate call. A {@code select} alias of an aggregate output counts as aggregated.
     */
    static void requireGroupedOperands(WindowExpr window, Set<String> groupKeys, Set<String> aggregateAliases) {
        final Consumer<Expr> check = expr -> requireGrouped(expr, groupKeys, aggregateAliases);
        if (window.fn() != null && window.fn().args() != null) {
            window.fn().args().forEach(check);
        }
        if (window.partitionBy() != null) {
            window.partitionBy().forEach(check);
        }
        if (window.orderBy() != null) {
            for (final WindowOrder order : window.orderBy()) {
                if (order != null) {
                    check.accept(order.expr());
                }
            }
        }
    }

    private static void requireGrouped(Expr expr, Set<String> groupKeys, Set<String> aggregateAliases) {
        switch (expr) {
            case null -> { }
            case FieldExpr field -> {
                if (!groupKeys.contains(field.name()) && !aggregateAliases.contains(field.name())) {
                    throw new QueryValidationException("field '" + field.name() + "' inside a window expression of an"
                            + " aggregate-mode query must be a 'group_by' key or lie inside an aggregate call");
                }
            }
            case FnExpr fn -> {
                if (isAggregate(fn.name())) {
                    return; // anything under an aggregate is aggregated
                }
                if (fn.args() != null) {
                    fn.args().forEach(arg -> requireGrouped(arg, groupKeys, aggregateAliases));
                }
            }
            case CaseExpr caseExpr -> {
                if (caseExpr.when() != null) {
                    for (final CaseBranch branch : caseExpr.when()) {
                        visitFilter(branch.when(), e -> requireGrouped(e, groupKeys, aggregateAliases));
                        requireGrouped(branch.then(), groupKeys, aggregateAliases);
                    }
                }
                requireGrouped(caseExpr.otherwise(), groupKeys, aggregateAliases);
            }
            case ArrayExpr array -> {
                if (array.items() != null) {
                    array.items().forEach(item -> requireGrouped(item, groupKeys, aggregateAliases));
                }
            }
            default -> { } // literals, params, nested windows (refused elsewhere), subqueries (uncorrelated)
        }
    }

    /** Whether {@code name} is an aggregate of the catalog (plain or ordered-set). */
    static boolean isAggregate(String name) {
        final QueryFunctionDto descriptor = QueryFunctionCatalog.describe(name);
        return descriptor != null
                && (descriptor.group() == QueryFunctionGroup.AGGREGATE
                        || descriptor.group() == QueryFunctionGroup.ORDERED_SET_AGGREGATE);
    }

    /** Pre-order visit of every expression below {@code expr}, including {@code expr} itself. */
    static void visit(Expr expr, Consumer<Expr> visitor) {
        if (expr == null) {
            return;
        }
        visitor.accept(expr);
        switch (expr) {
            case FnExpr fn -> {
                if (fn.args() != null) {
                    fn.args().forEach(arg -> visit(arg, visitor));
                }
            }
            case ArrayExpr array -> {
                if (array.items() != null) {
                    array.items().forEach(item -> visit(item, visitor));
                }
            }
            case CaseExpr caseExpr -> {
                if (caseExpr.when() != null) {
                    for (final CaseBranch branch : caseExpr.when()) {
                        if (branch != null) {
                            visitFilter(branch.when(), visitor);
                            visit(branch.then(), visitor);
                        }
                    }
                }
                visit(caseExpr.otherwise(), visitor);
            }
            case WindowExpr window -> {
                visit(window.fn(), visitor);
                if (window.partitionBy() != null) {
                    window.partitionBy().forEach(key -> visit(key, visitor));
                }
                if (window.orderBy() != null) {
                    for (final WindowOrder order : window.orderBy()) {
                        if (order != null) {
                            visit(order.expr(), visitor);
                        }
                    }
                }
            }
            default -> { } // field, value, param, scalar subquery: leaves of this walk
        }
    }

    /** Pre-order visit of every expression operand below a filter tree. */
    static void visitFilter(FilterNode node, Consumer<Expr> visitor) {
        switch (node) {
            case null -> { }
            case LogicalNode logical -> {
                if (logical.args() != null) {
                    logical.args().forEach(child -> visitFilter(child, visitor));
                }
            }
            case ComparisonNode comparison -> {
                if (comparison.args() != null) {
                    comparison.args().forEach(arg -> visit(arg, visitor));
                }
            }
            case SubqueryNode subquery -> {
                final List<Expr> operands = subquery.operands() == null ? List.of() : subquery.operands();
                operands.forEach(arg -> visit(arg, visitor));
            }
        }
    }
}
