package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.EntityRelation;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.JoinKind;
import com.epam.aidial.analytics.service.query.model.JoinRelation;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LambdaParamExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.ParamExpr;
import com.epam.aidial.analytics.service.query.model.ScalarSubqueryExpr;
import com.epam.aidial.analytics.service.query.model.SubqueryNode;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.WindowExpr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which top-level conjuncts of a join block's {@code WHERE} move into one of the join's entity operands, and on
 * which equality the other operand may be reduced to the keys the first can produce (the {@code structured-query}
 * spec's "A composite join's operand-only conjuncts restrict that operand" and "An equi-join operand is reduced to
 * the keys its restricted partner can produce").
 *
 * <p>Purely syntactic: it reads the join's kind, its exposed columns and its {@code ON} condition, and decides from
 * those alone. Whether a moved conjunct reaches only the operand's <em>source</em> columns — what decides if it can
 * build a reduction — needs the operand's model, so {@link CompositeQueryBuilder} decides that part.
 *
 * <p>A moved conjunct is returned renamed to the operand's own column names: the join exposes a right-hand column
 * under the name the SQL front end made unique ({@code trace_id0}), the operand's block knows it as {@code trace_id}.
 */
final class OperandPushdown {

    /**
     * The plan for one join block.
     *
     * @param left      conjuncts moved into the left operand, renamed to its own columns
     * @param right     conjuncts moved into the right operand, renamed to its own columns
     * @param remaining what stays above the join, {@code null} for nothing
     * @param leftKey   the left operand's column of the reduction equality, {@code null} when the join has none
     * @param rightKey  the right operand's column of that equality
     */
    record Plan(List<FilterNode> left, List<FilterNode> right, FilterNode remaining, String leftKey, String rightKey) {

        /** Whether the left operand may be reduced by the right one's keys. */
        boolean reducesLeft(JoinKind kind) {
            return leftKey != null && (kind == JoinKind.INNER || kind == JoinKind.RIGHT);
        }

        /** Whether the right operand may be reduced by the left one's keys. */
        boolean reducesRight(JoinKind kind) {
            return rightKey != null && (kind == JoinKind.INNER || kind == JoinKind.LEFT);
        }
    }

    private OperandPushdown() {
    }

    /** No move and no reduction: the block renders as it did before either existed. */
    static Plan none(FilterNode filter) {
        return new Plan(List.of(), List.of(), filter, null, null);
    }

    static Plan plan(JoinRelation join, FilterNode filter) {
        final Map<String, JoinRelation.JoinColumn> columns = new HashMap<>();
        join.joinColumns().forEach(column -> columns.put(column.column().name(), column));
        final boolean intoLeft = join.left() instanceof EntityRelation && admitsLeft(join.kind());
        final boolean intoRight = join.right() instanceof EntityRelation && admitsRight(join.kind());

        final List<FilterNode> left = new ArrayList<>();
        final List<FilterNode> right = new ArrayList<>();
        final List<FilterNode> remaining = new ArrayList<>();
        for (final FilterNode conjunct : StructuredQueryBuilder.conjuncts(filter)) {
            final Boolean side = movable(conjunct) ? side(conjunct, columns) : null;
            if (Boolean.TRUE.equals(side) && intoLeft) {
                left.add(renameFilter(conjunct, columns));
            } else if (Boolean.FALSE.equals(side) && intoRight) {
                right.add(renameFilter(conjunct, columns));
            } else {
                remaining.add(conjunct);
            }
        }
        final String[] key = join.left() instanceof EntityRelation && join.right() instanceof EntityRelation
                ? equality(join.on(), columns) : null;
        return new Plan(List.copyOf(left), List.copyOf(right), and(remaining),
                key == null ? null : key[0], key == null ? null : key[1]);
    }

    /** The conjunction of {@code conjuncts}, {@code null} for none. */
    static FilterNode and(List<FilterNode> conjuncts) {
        if (conjuncts.isEmpty()) {
            return null;
        }
        return conjuncts.size() == 1 ? conjuncts.getFirst() : new LogicalNode(LogicalOp.AND, List.copyOf(conjuncts));
    }

    private static boolean admitsLeft(JoinKind kind) {
        return kind == JoinKind.INNER || kind == JoinKind.CROSS || kind == JoinKind.LEFT;
    }

    private static boolean admitsRight(JoinKind kind) {
        return kind == JoinKind.INNER || kind == JoinKind.CROSS || kind == JoinKind.RIGHT;
    }

    /**
     * The first top-level {@code ON} equality between a bare column of each side, as {@code [left, right]} operand
     * column names; {@code null} when there is none.
     */
    private static String[] equality(FilterNode on, Map<String, JoinRelation.JoinColumn> columns) {
        for (final FilterNode conjunct : StructuredQueryBuilder.conjuncts(on)) {
            if (conjunct instanceof ComparisonNode(ComparisonOp op, List<Expr> args) && op == ComparisonOp.EQ
                    && args != null && args.size() == 2
                    && args.get(0) instanceof FieldExpr(String first) && args.get(1) instanceof FieldExpr(String second)) {
                final JoinRelation.JoinColumn a = columns.get(first);
                final JoinRelation.JoinColumn b = columns.get(second);
                if (a != null && b != null && a.fromLeft() != b.fromLeft()) {
                    final JoinRelation.JoinColumn leftColumn = a.fromLeft() ? a : b;
                    final JoinRelation.JoinColumn rightColumn = a.fromLeft() ? b : a;
                    return new String[] {leftColumn.sourceName(), rightColumn.sourceName()};
                }
            }
        }
        return null;
    }

    /** {@code TRUE} when every column {@code conjunct} names is the left operand's, {@code FALSE} the right's, else null. */
    private static Boolean side(FilterNode conjunct, Map<String, JoinRelation.JoinColumn> columns) {
        Boolean side = null;
        for (final String name : StructuredQueryBuilder.referencedFields(conjunct)) {
            final JoinRelation.JoinColumn column = columns.get(name);
            if (column == null || side != null && side != column.fromLeft()) {
                return null;
            }
            side = column.fromLeft();
        }
        return side;
    }

    /**
     * Whether {@code node} may move at all: no subquery predicate or scalar subquery (their scope reasoning is not
     * worth the rare case), no lambda (its parameters shadow column names, so renaming its body is not safe) and no
     * window (never valid in a {@code WHERE}).
     */
    private static boolean movable(FilterNode node) {
        return switch (node) {
            case null -> true;
            case LogicalNode logical -> logical.args() == null || logical.args().stream().allMatch(OperandPushdown::movable);
            case ComparisonNode comparison -> comparison.args() == null
                    || comparison.args().stream().allMatch(OperandPushdown::movable);
            case SubqueryNode ignored -> false;
        };
    }

    private static boolean movable(Expr expr) {
        return switch (expr) {
            case null -> true;
            case FieldExpr ignored -> true;
            case ValueExpr ignored -> true;
            case ParamExpr ignored -> true;
            case FnExpr fn -> fn.args() == null || fn.args().stream().allMatch(OperandPushdown::movable);
            case ArrayExpr array -> array.items() == null || array.items().stream().allMatch(OperandPushdown::movable);
            case CaseExpr caseExpr -> (caseExpr.when() == null || caseExpr.when().stream()
                    .allMatch(branch -> branch == null || movable(branch.when()) && movable(branch.then())))
                    && movable(caseExpr.otherwise());
            case WindowExpr ignored -> false;
            case LambdaExpr ignored -> false;
            case LambdaParamExpr ignored -> false;
            case ScalarSubqueryExpr ignored -> false;
        };
    }

    private static FilterNode renameFilter(FilterNode node, Map<String, JoinRelation.JoinColumn> columns) {
        return switch (node) {
            case null -> null;
            case LogicalNode logical -> new LogicalNode(logical.op(), logical.args() == null ? null
                    : logical.args().stream().map(arg -> renameFilter(arg, columns)).toList());
            case ComparisonNode comparison -> new ComparisonNode(comparison.op(), comparison.args() == null ? null
                    : comparison.args().stream().map(arg -> rename(arg, columns)).toList());
            case SubqueryNode subquery -> throw new IllegalStateException("a subquery predicate is never moved: " + subquery);
        };
    }

    private static Expr rename(Expr expr, Map<String, JoinRelation.JoinColumn> columns) {
        return switch (expr) {
            case null -> null;
            case FieldExpr(String name) -> new FieldExpr(columns.get(name).sourceName());
            case FnExpr fn -> new FnExpr(fn.name(), fn.distinct(),
                    fn.args() == null ? null : fn.args().stream().map(arg -> rename(arg, columns)).toList());
            case ArrayExpr array -> new ArrayExpr(
                    array.items() == null ? null : array.items().stream().map(item -> rename(item, columns)).toList());
            case CaseExpr caseExpr -> new CaseExpr(caseExpr.when() == null ? null : caseExpr.when().stream()
                    .map(branch -> branch == null ? null
                            : new CaseBranch(renameFilter(branch.when(), columns), rename(branch.then(), columns)))
                    .toList(), rename(caseExpr.otherwise(), columns));
            case ValueExpr value -> value;
            case ParamExpr param -> param;
            case WindowExpr window -> throw new IllegalStateException("a window is never moved: " + window);
            case LambdaExpr lambda -> throw new IllegalStateException("a lambda is never moved: " + lambda);
            case LambdaParamExpr param -> throw new IllegalStateException("a lambda parameter is never moved: " + param);
            case ScalarSubqueryExpr scalar -> throw new IllegalStateException("a scalar subquery is never moved: " + scalar);
        };
    }
}
