package com.epam.aidial.analytics.service.enrichment.validation;

import com.epam.aidial.analytics.service.query.QueryFunctionCatalog;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.model.WindowOrder;

/**
 * Finds a call to a function the query function catalog declares <b>non-deterministic</b> anywhere in a
 * translated DSL tree — {@link ExprColumnCollector}'s sibling, walking the same shapes for a different
 * reason.
 *
 * <p><b>Why a pipeline needs this at all.</b> A pipeline declaration is a stored statement of intent that
 * something else re-runs on its own schedule, so "the same declaration means the same thing every time it
 * runs" is the property the paths downstream are built on: Class-1 materialize guarantees exactly-once
 * <em>effect</em> by re-running a window producing the same values, and an aggregate refresh recomputes
 * every affected group key in full. A fragment reading the clock breaks both silently — a re-materialize
 * writes a different label for the same row, a refresh rewrites an already-published bucket over a
 * different row set — and neither leaves anything behind to point at.
 *
 * <p>This check did not exist before, and did not need to: determinism was guaranteed by
 * <em>construction</em>, because no non-deterministic function was in the closed catalog for a declaration
 * to name. Adding {@code now()} to the query surfaces removed that floor, so the guarantee became something
 * to enforce.
 *
 * <p><b>It asks the catalog rather than carrying a list of names.</b> A denylist beside the catalog would
 * imply the catalog is open — it is not — and would rot the first time a function was added without
 * remembering the list exists. Adding a non-deterministic descriptor closes this gate around it with no
 * edit here.
 *
 * <p><b>The walk is total over the shapes that can reach it.</b> A nested call matters as much as a
 * top-level one: {@code date_sub('hour', 1, now())} is no more reproducible than {@code now()} is,
 * {@code CASE WHEN x THEN now() END} hides one two levels down, and a lambda body hides one behind a
 * parameter binding — {@code arrayExists(x -> equals(x, to_string(now())), roles)} reads the clock just
 * the same.
 *
 * <p>There is deliberately <b>no subquery arm</b>, and its absence is not a hole: both validators embed
 * their fragment through {@code SqlQueryFrontEnd.translate}, which refuses every composite plan, and a
 * {@code SubqueryNode} or {@code ScalarSubqueryExpr} is only ever constructed for such a plan — so a
 * subquery cannot be present in a tree that reaches here. ({@link ExprColumnCollector} also skips one,
 * but for its own and different reason: an uncorrelated subquery references no column of the enclosing
 * block. Unreachability is the argument that applies here.)
 */
final class NonDeterministicFunctions {

    private NonDeterministicFunctions() {
    }

    /** The name of the first non-deterministic function in a filter tree, or {@code null} if there is none. */
    static String findIn(FilterNode node) {
        if (node instanceof LogicalNode logical) {
            for (final FilterNode child : logical.args()) {
                final String found = findIn(child);
                if (found != null) {
                    return found;
                }
            }
        } else if (node instanceof ComparisonNode comparison) {
            for (final Expr arg : comparison.args()) {
                final String found = findIn(arg);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The name of the first non-deterministic function in an expression tree, or {@code null} if none. */
    static String findIn(Expr expr) {
        return switch (expr) {
            case FnExpr fn -> {
                if (QueryFunctionCatalog.isNonDeterministic(fn.name())) {
                    yield fn.name();
                }
                yield firstIn(fn.args());
            }
            case ArrayExpr array -> firstIn(array.items());
            case CaseExpr caseExpr -> {
                if (caseExpr.when() != null) {
                    for (final CaseBranch branch : caseExpr.when()) {
                        final String inCondition = findIn(branch.when());
                        if (inCondition != null) {
                            yield inCondition;
                        }
                        final String inResult = findIn(branch.then());
                        if (inResult != null) {
                            yield inResult;
                        }
                    }
                }
                yield caseExpr.otherwise() == null ? null : findIn(caseExpr.otherwise());
            }
            case WindowExpr window -> {
                if (window.fn() != null) {
                    final String inFunction = findIn(window.fn());
                    if (inFunction != null) {
                        yield inFunction;
                    }
                }
                final String inPartition = firstIn(window.partitionBy());
                if (inPartition != null) {
                    yield inPartition;
                }
                if (window.orderBy() != null) {
                    for (final WindowOrder order : window.orderBy()) {
                        final String inOrder = findIn(order.expr());
                        if (inOrder != null) {
                            yield inOrder;
                        }
                    }
                }
                yield null;
            }
            // A lambda body is an ordinary expression and hides a call as well as any other nesting does:
            // arrayExists(x -> equals(x, to_string(now())), roles) reads the clock exactly as
            // date_sub('hour', 1, now()) does. The parameter is a LambdaParamExpr, which names no
            // function and stays on the default branch below.
            case LambdaExpr lambda -> lambda.body() == null ? null : findIn(lambda.body());
            // A field reference, a literal and a parameter name no function; a scalar subquery cannot be
            // present (see the class javadoc).
            case null, default -> null;
        };
    }

    /** The refusal message for {@code function} found in {@code what}, phrased so the fix is visible. */
    static String message(String what, String function) {
        return what + " calls the non-deterministic function '" + function + "';"
                + " a pipeline declaration must be reproducible, because the value it derives is re-computed"
                + " on a schedule and a run that answered differently could not be told from a correct one."
                + " A moving time window belongs in a query, where it is free; date arithmetic over a column"
                + " (date_trunc, date_add, date_sub) is accepted here.";
    }

    private static String firstIn(Iterable<Expr> expressions) {
        if (expressions == null) {
            return null;
        }
        for (final Expr expression : expressions) {
            final String found = findIn(expression);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
