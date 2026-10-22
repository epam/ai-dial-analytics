package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.CompositeQuery;
import com.epam.aidial.analytics.service.query.model.DerivedRelation;
import com.epam.aidial.analytics.service.query.model.EntityRelation;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.JoinRelation;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.Relation;
import com.epam.aidial.analytics.service.query.model.ScalarSubqueryExpr;
import com.epam.aidial.analytics.service.query.model.SetOpKind;
import com.epam.aidial.analytics.service.query.model.SetOpRelation;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.SubqueryNode;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.model.WindowOrder;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What a planned statement reads, counted per <em>occurrence</em>: every {@link EntityRelation} in the relation
 * tree and in every subquery nested in a block's {@code select}, {@code filter} or {@code having}, plus the
 * constructs that put them there (joins, set operations, subqueries, derived blocks).
 *
 * <p>Occurrences, not names, because a self-join — or a CTE over one table read twice, which Calcite inlines
 * into two references — names a single entity and still reads it twice. The aggregate SQL declaration needs
 * exactly that distinction ("reads its input once"); {@link CompositeQueryEntities} needs only the names.
 *
 * @param entityCounts   entity name → number of references, in first-seen order
 * @param joins          number of {@link JoinRelation}s
 * @param setOperations  the set-operation kinds present
 * @param subqueries     number of scalar, {@code IN} and {@code EXISTS} subqueries
 * @param nestedSealing  what a nested (non-root) block carries that keeps it from folding into its parent:
 *                       {@code "an aggregate"}, {@code "DISTINCT"}, {@code "ORDER BY"}, {@code "LIMIT/OFFSET"},
 *                       {@code "a window function"}; empty when there is none
 */
public record QueryReferences(
        Map<String, Integer> entityCounts,
        int joins,
        Set<SetOpKind> setOperations,
        int subqueries,
        Set<String> nestedSealing) {

    /** The references of a composite plan. */
    public static QueryReferences of(CompositeQuery query) {
        final Walker walker = new Walker();
        walker.query(query, true);
        return walker.result();
    }

    /** The references of a single block: its own entity once, plus whatever its subqueries read. */
    public static QueryReferences of(StructuredQuery block) {
        final Walker walker = new Walker();
        if (block.entity() != null) {
            walker.entities.merge(block.entity(), 1, Integer::sum);
        }
        walker.block(block, true);
        return walker.result();
    }

    /** The total number of entity references, across all entities. */
    public int occurrences() {
        return entityCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static final class Walker {
        private final Map<String, Integer> entities = new LinkedHashMap<>();
        private final Set<SetOpKind> setOps = EnumSet.noneOf(SetOpKind.class);
        private final Set<String> sealing = new LinkedHashSet<>();
        private int joins;
        private int subqueries;

        QueryReferences result() {
            return new QueryReferences(
                    Collections.unmodifiableMap(entities), joins, Collections.unmodifiableSet(setOps), subqueries,
                    Collections.unmodifiableSet(sealing));
        }

        void query(CompositeQuery query, boolean root) {
            relation(query.source());
            block(query.block(), root);
        }

        private void relation(Relation relation) {
            switch (relation) {
                case EntityRelation entity -> entities.merge(entity.entity(), 1, Integer::sum);
                case JoinRelation join -> {
                    joins++;
                    relation(join.left());
                    relation(join.right());
                    filter(join.on());
                }
                case DerivedRelation derived -> query(derived.query(), false);
                case SetOpRelation setOp -> {
                    setOps.add(setOp.kind());
                    for (final CompositeQuery branch : setOp.branches()) {
                        query(branch, false);
                    }
                }
            }
        }

        void block(StructuredQuery block, boolean root) {
            if (!root) {
                seal(block);
            }
            if (block.select() != null) {
                for (final OutputColumn column : block.select()) {
                    expr(column.expr());
                }
            }
            filter(block.filter());
            filter(block.having());
        }

        /** Records what makes a nested block a block of its own rather than a fold into its parent. */
        private void seal(StructuredQuery block) {
            if (block.mode() == QueryMode.AGGREGATE || block.groupBy() != null && !block.groupBy().isEmpty()) {
                sealing.add("an aggregate");
            }
            if (block.distinct()) {
                sealing.add("DISTINCT");
            }
            if (block.sort() != null && !block.sort().isEmpty()) {
                sealing.add("ORDER BY");
            }
            if (block.page() != null) {
                sealing.add("LIMIT/OFFSET");
            }
            if (block.select() != null && block.select().stream().anyMatch(c -> c.expr() instanceof WindowExpr)) {
                sealing.add("a window function");
            }
        }

        private void expr(Expr expr) {
            switch (expr) {
                case null -> { }
                case ScalarSubqueryExpr scalar -> {
                    subqueries++;
                    query(scalar.subquery(), false);
                }
                case FnExpr fn -> {
                    if (fn.args() != null) {
                        fn.args().forEach(this::expr);
                    }
                }
                case ArrayExpr array -> {
                    if (array.items() != null) {
                        array.items().forEach(this::expr);
                    }
                }
                case CaseExpr caseExpr -> {
                    if (caseExpr.when() != null) {
                        for (final CaseBranch branch : caseExpr.when()) {
                            filter(branch.when());
                            expr(branch.then());
                        }
                    }
                    expr(caseExpr.otherwise());
                }
                case WindowExpr window -> {
                    expr(window.fn());
                    if (window.partitionBy() != null) {
                        window.partitionBy().forEach(this::expr);
                    }
                    if (window.orderBy() != null) {
                        for (final WindowOrder order : window.orderBy()) {
                            expr(order.expr());
                        }
                    }
                }
                case LambdaExpr lambda -> expr(lambda.body());
                default -> { } // FieldExpr, ValueExpr, ParamExpr, LambdaParamExpr reference no relation
            }
        }

        private void filter(FilterNode node) {
            switch (node) {
                case null -> { }
                case LogicalNode logical -> {
                    if (logical.args() != null) {
                        logical.args().forEach(this::filter);
                    }
                }
                case ComparisonNode comparison -> {
                    if (comparison.args() != null) {
                        comparison.args().forEach(this::expr);
                    }
                }
                case SubqueryNode subquery -> {
                    subqueries++;
                    if (subquery.operands() != null) {
                        subquery.operands().forEach(this::expr);
                    }
                    query(subquery.subquery(), false);
                }
            }
        }
    }
}
