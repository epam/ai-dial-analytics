package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.google.common.collect.BoundType;
import com.google.common.collect.Range;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUnknownAs;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.Sarg;

import java.util.ArrayList;
import java.util.List;

/**
 * Expands Calcite {@code SEARCH(operand, Sarg)} calls — the normalized form Calcite lowers
 * {@code IN} lists, {@code BETWEEN}, and merged range predicates into — back into the DSL's
 * comparison vocabulary: point sets become {@code eq}/{@code in}, complemented point sets become
 * {@code NOT(eq/in)}, and ranges become {@code gt/ge/lt/le} conjunctions (OR-ed across disjoint
 * ranges). A Sarg that cannot be expanded losslessly is rejected (fail-closed).
 */
final class SargExpander {

    private SargExpander() {}

    static FilterNode expand(RexCall search, RexToExprTranslator translator) {
        final RexNode operandRex = search.getOperands().get(0);
        final Expr operand = translator.toExpr(operandRex);
        if (!(search.getOperands().get(1) instanceof RexLiteral literal)
                || !(literal.getValue() instanceof Sarg<?> sarg)) {
            throw new SqlTranslationException("SEARCH predicate without a literal argument is not supported");
        }
        // Operator-aware for a point set, blanket for a range. A point set is a membership test, which an
        // object field admits; a range is the ordering comparisons, which it does not. Calcite lowers
        // small IN lists to OR chains today, so an object operand reaches `points` only if that ever
        // changes — but classifying by Sarg shape here rather than rejecting outright is what keeps a
        // future Calcite from breaking an `in` that the DSL surface and the renderer both accept.
        if (sarg.isPoints() || sarg.isComplementedPoints()) {
            translator.rejectProjectionOnlyOperand(operand, ComparisonOp.IN, "a range/IN predicate on");
        } else {
            translator.rejectProjectionOnlyOperand(operand, "a range/IN predicate on");
        }
        final SqlTypeName type = operandRex.getType().getSqlTypeName();

        final FilterNode expanded;
        if (sarg.isPoints()) {
            expanded = points(operand, sarg, type, translator, false);
        } else if (sarg.isComplementedPoints()) {
            expanded = points(operand, sarg, type, translator, true);
        } else {
            expanded = ranges(operand, sarg, type, translator);
        }
        return withNullSemantics(expanded, operand, sarg.nullAs);
    }

    /** A point set: one value → {@code eq}, several → {@code in}; complemented → wrapped in NOT. */
    private static FilterNode points(
            Expr operand, Sarg<?> sarg, SqlTypeName type, RexToExprTranslator translator, boolean complemented) {
        final Sarg<?> effective = complemented ? sarg.negate() : sarg;
        final List<Expr> values = new ArrayList<>();
        for (final Range<?> range : effective.rangeSet.asRanges()) {
            values.add(translator.sargValueExpr(range.lowerEndpoint(), type));
        }
        final FilterNode membership = values.size() == 1
                ? translator.comparisonOf(ComparisonOp.EQ, operand, values.getFirst())
                : inNode(operand, values, translator);
        return complemented ? new LogicalNode(LogicalOp.NOT, List.of(membership)) : membership;
    }

    private static FilterNode inNode(Expr operand, List<Expr> values, RexToExprTranslator translator) {
        // Route each element through the comparison builder so literals are retyped against the
        // field's declared type (e.g. string -> uuid), then reassemble the array.
        final List<Expr> retyped = new ArrayList<>(values.size());
        for (final Expr value : values) {
            final ComparisonNode typed = translator.comparisonOf(ComparisonOp.EQ, operand, value);
            retyped.add(typed.args().get(1));
        }
        return new ComparisonNode(ComparisonOp.IN, List.of(operand, new ArrayExpr(retyped)));
    }

    /** Disjoint ranges: each becomes a bound conjunction; several ranges OR together. */
    private static FilterNode ranges(
            Expr operand, Sarg<?> sarg, SqlTypeName type, RexToExprTranslator translator) {
        final List<FilterNode> disjuncts = new ArrayList<>();
        for (final Range<?> range : sarg.rangeSet.asRanges()) {
            disjuncts.add(range(operand, range, type, translator));
        }
        if (disjuncts.isEmpty()) {
            throw new SqlTranslationException("always-false range predicate is not supported");
        }
        return disjuncts.size() == 1 ? disjuncts.getFirst() : new LogicalNode(LogicalOp.OR, disjuncts);
    }

    private static FilterNode range(
            Expr operand, Range<?> range, SqlTypeName type, RexToExprTranslator translator) {
        final List<FilterNode> bounds = new ArrayList<>(2);
        if (range.hasLowerBound()) {
            final ComparisonOp op = range.lowerBoundType() == BoundType.CLOSED ? ComparisonOp.GE : ComparisonOp.GT;
            bounds.add(translator.comparisonOf(op, operand, translator.sargValueExpr(range.lowerEndpoint(), type)));
        }
        if (range.hasUpperBound()) {
            final ComparisonOp op = range.upperBoundType() == BoundType.CLOSED ? ComparisonOp.LE : ComparisonOp.LT;
            bounds.add(translator.comparisonOf(op, operand, translator.sargValueExpr(range.upperEndpoint(), type)));
        }
        if (bounds.isEmpty()) {
            throw new SqlTranslationException("unbounded range predicate is not supported");
        }
        return bounds.size() == 1 ? bounds.getFirst() : new LogicalNode(LogicalOp.AND, bounds);
    }

    /**
     * Sarg null semantics: {@code UNKNOWN}/{@code FALSE} match plain comparison behavior in a WHERE
     * clause; {@code TRUE} (e.g. from {@code x = 1 OR x IS NULL}) adds an explicit IS NULL disjunct.
     */
    private static FilterNode withNullSemantics(FilterNode expanded, Expr operand, RexUnknownAs nullAs) {
        if (nullAs != RexUnknownAs.TRUE) {
            return expanded;
        }
        final FilterNode isNull =
                new ComparisonNode(ComparisonOp.EQ, List.of(operand, new ValueExpr(ValueType.NULL, null)));
        return new LogicalNode(LogicalOp.OR, List.of(expanded, isNull));
    }
}
