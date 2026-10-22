package com.epam.aidial.analytics.service.enrichment.validation;

import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.model.WindowOrder;

import java.util.HashSet;
import java.util.Set;

/**
 * Walks a translated DSL tree and collects the source column names it references. Shared by
 * {@link FilterSqlValidator} (boolean filters and readiness signals) and {@link ExprSqlValidator}
 * (Class-1 scalar expressions) — and by the aggregate SQL declaration in {@code service/pipeline} — so entitlement
 * checks and scan projections see the same set of columns
 * whichever governed expression they came from.
 */
public final class ExprColumnCollector {

    private ExprColumnCollector() {
    }

    /** Collects the columns referenced by a filter tree into {@code out}. A null node contributes none. */
    public static void collect(FilterNode node, Set<String> out) {
        if (node instanceof LogicalNode logical) {
            for (FilterNode child : logical.args()) {
                collect(child, out);
            }
        } else if (node instanceof ComparisonNode comparison) {
            for (Expr arg : comparison.args()) {
                collect(arg, out);
            }
        }
    }

    /** Collects the columns referenced by a value expression into {@code out}. */
    public static void collect(Expr expr, Set<String> out) {
        switch (expr) {
            case FieldExpr field -> out.add(field.name());
            case FnExpr fn -> {
                for (Expr arg : fn.args()) {
                    collect(arg, out);
                }
            }
            case ArrayExpr array -> {
                for (Expr item : array.items()) {
                    collect(item, out);
                }
            }
            case CaseExpr caseExpr -> {
                if (caseExpr.when() != null) {
                    for (CaseBranch branch : caseExpr.when()) {
                        collect(branch.when(), out);
                        collect(branch.then(), out);
                    }
                }
                if (caseExpr.otherwise() != null) {
                    collect(caseExpr.otherwise(), out);
                }
            }
            case WindowExpr window -> {
                if (window.fn() != null) {
                    collect(window.fn(), out);
                }
                if (window.partitionBy() != null) {
                    for (Expr key : window.partitionBy()) {
                        collect(key, out);
                    }
                }
                if (window.orderBy() != null) {
                    for (WindowOrder order : window.orderBy()) {
                        collect(order.expr(), out);
                    }
                }
            }
            // A column captured inside a lambda body is an ordinary column read: the entitlement check and
            // the scan projection are both keyed on this set, so a body reaching a column the declaration
            // may not read, or one the scan does not project, must be visible here. The parameter is a
            // LambdaParamExpr, which names no column and stays on the default branch below — but a `field`
            // naming the parameter resolves to the parameter too (LambdaScope's shadowing rule), so the
            // body's names are collected apart and the parameters removed before they join the set.
            // Removing after the walk, rather than filtering during it, is what makes nesting right: an
            // inner lambda has already dropped its own parameter by the time the outer one drops its.
            case LambdaExpr lambda -> {
                if (lambda.body() != null) {
                    final Set<String> captured = new HashSet<>();
                    collect(lambda.body(), captured);
                    if (lambda.params() != null) {
                        lambda.params().forEach(captured::remove);
                    }
                    out.addAll(captured);
                }
            }
            default -> {
                // ValueExpr / ParamExpr / LambdaParamExpr carry no column reference; a scalar subquery is
                // uncorrelated, so it references nothing of the enclosing block either.
            }
        }
    }
}
