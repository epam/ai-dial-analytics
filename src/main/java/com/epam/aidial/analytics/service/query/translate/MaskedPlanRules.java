package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.CompositeQuery;
import com.epam.aidial.analytics.service.query.model.DerivedRelation;
import com.epam.aidial.analytics.service.query.model.EntityRelation;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.JoinRelation;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.OutputColumnNaming;
import com.epam.aidial.analytics.service.query.model.Relation;
import com.epam.aidial.analytics.service.query.model.RelationColumn;
import com.epam.aidial.analytics.service.query.model.ScalarSubqueryExpr;
import com.epam.aidial.analytics.service.query.model.SetOpKind;
import com.epam.aidial.analytics.service.query.model.SetOpRelation;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.SubqueryNode;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.model.WindowOrder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The refusals a masked field brings to a whole planned statement (see {@code openspec/specs/structured-query/spec.md},
 * "Set operations over a masked output" and the table-named names), checked where every SQL surface plans a statement,
 * so {@code validate-sql} and the save of a {@code sql} saved query or widget refuse what its execution would:
 *
 * <ul>
 *   <li>an {@code INTERSECT} or {@code EXCEPT} branch with an output that depends on a masked field, traced through
 *       derived tables, joins and set operations: the rows they compare have no single source a mark could come from;</li>
 *   <li>in a statement that reads a masked field anywhere, an output name at any level or a lambda parameter that equals
 *       a table the statement reads, or begins with it followed by {@code .}, except an output named after the field it
 *       projects bare.</li>
 * </ul>
 */
public final class MaskedPlanRules {

    private final Function<String, Set<String>> maskedFields;
    private final Function<String, Set<String>> entityTables;
    private final Set<String> tables = new HashSet<>();
    private final Map<String, Boolean> names = new HashMap<>();
    private final Set<String> params = new LinkedHashSet<>();
    private boolean readsMasked;

    private MaskedPlanRules(Function<String, Set<String>> maskedFields, Function<String, Set<String>> entityTables) {
        this.maskedFields = maskedFields;
        this.entityTables = entityTables;
    }

    /**
     * Refuses {@code query}, one block over its entity, as the class note says.
     *
     * @param maskedFields by entity, the flat names of the fields a column policy masks for the caller
     * @param entityTables by entity, the tables it reads: its source and its enrichments
     */
    public static void check(StructuredQuery query, Function<String, Set<String>> maskedFields,
            Function<String, Set<String>> entityTables) {
        final MaskedPlanRules rules = new MaskedPlanRules(maskedFields, entityTables);
        rules.block(query, rules.input(new EntityRelation(query.entity(), List.of())), List.of());
        rules.refuseNames();
    }

    /** As {@link #check(StructuredQuery, Function, Function)} for a composite plan. */
    public static void check(CompositeQuery query, Function<String, Set<String>> maskedFields,
            Function<String, Set<String>> entityTables) {
        final MaskedPlanRules rules = new MaskedPlanRules(maskedFields, entityTables);
        rules.outputs(query);
        rules.refuseNames();
    }

    /**
     * Whether {@code query} reads a field {@code maskedFields} names anywhere — any block, any clause, a subquery's
     * included — so its rendering carries marks.
     */
    public static boolean readsMaskedField(CompositeQuery query, Function<String, Set<String>> maskedFields) {
        final MaskedPlanRules rules = new MaskedPlanRules(maskedFields, entity -> Set.of());
        rules.outputs(query);
        return rules.readsMasked;
    }

    /** Every output name, at every level, and every lambda parameter {@code query} declares. */
    public static Set<String> declaredNames(CompositeQuery query) {
        final MaskedPlanRules rules = new MaskedPlanRules(entity -> Set.of(), entity -> Set.of());
        rules.outputs(query);
        final Set<String> declared = new HashSet<>(rules.names.keySet());
        declared.addAll(rules.params);
        return declared;
    }

    /** By position, the masked fields each output of {@code query} depends on. */
    private List<Set<String>> outputs(CompositeQuery query) {
        return block(query.block(), input(query.source()), query.columns());
    }

    private List<Set<String>> block(StructuredQuery block, Function<String, Set<String>> input, List<RelationColumn> columns) {
        for (final String name : StructuredQueryBuilder.referencedFields(block)) {
            readsMasked |= !input.apply(name).isEmpty();
        }
        visitFilter(block.filter());
        visitFilter(block.having());
        final List<OutputColumn> select = block.select() == null ? List.of() : block.select();
        final List<Set<String>> outputs = new ArrayList<>();
        if (select.isEmpty()) {
            for (final RelationColumn column : columns) {
                outputs.add(input.apply(column.name()));
                names.merge(column.name(), MaskMarking.exemptOutput(column.name(), new FieldExpr(column.name())), Boolean::logicalAnd);
            }
            return outputs;
        }
        final List<String> resolved = OutputColumnNaming.resolve(select);
        for (int i = 0; i < select.size(); i++) {
            final Expr expr = select.get(i).expr();
            final Set<String> depends = new LinkedHashSet<>();
            for (final String name : StructuredQueryBuilder.referencedFields(expr)) {
                depends.addAll(input.apply(name));
            }
            visitExpr(expr, depends);
            outputs.add(depends);
            names.merge(resolved.get(i), MaskMarking.exemptOutput(resolved.get(i), expr), Boolean::logicalAnd);
        }
        return outputs;
    }

    /** By column name, the masked fields a column of {@code relation} depends on. */
    private Function<String, Set<String>> input(Relation relation) {
        return switch (relation) {
            case EntityRelation entity -> {
                tables.addAll(entityTables.apply(entity.entity()));
                final Set<String> masked = maskedFields.apply(entity.entity());
                yield name -> masked.contains(name) ? Set.of(name) : Set.of();
            }
            case DerivedRelation derived -> byName(derived.query().columns(), outputs(derived.query()));
            case SetOpRelation setOp -> {
                final List<Set<String>> combined = new ArrayList<>();
                for (final CompositeQuery branch : setOp.branches()) {
                    final List<Set<String>> outputs = outputs(branch);
                    for (int i = 0; i < outputs.size(); i++) {
                        if (setOp.kind() != SetOpKind.UNION && !outputs.get(i).isEmpty()) {
                            throw new QueryValidationException(setOp.kind().keyword() + " is refused over output column "
                                    + (i + 1) + ", which reads masked field '" + outputs.get(i).iterator().next()
                                    + "': the rows it compares have no single source a masked cell's mark could come from");
                        }
                        if (combined.size() <= i) {
                            combined.add(new LinkedHashSet<>());
                        }
                        combined.get(i).addAll(outputs.get(i));
                    }
                }
                yield byName(setOp.columns(), combined);
            }
            case JoinRelation join -> {
                final Function<String, Set<String>> left = input(join.left());
                final Function<String, Set<String>> right = input(join.right());
                final Map<String, Set<String>> exposed = new HashMap<>();
                for (final JoinRelation.JoinColumn column : join.joinColumns()) {
                    exposed.put(column.column().name(), (column.fromLeft() ? left : right).apply(column.sourceName()));
                }
                for (final String name : StructuredQueryBuilder.referencedFields(join.on())) {
                    readsMasked |= !exposed.getOrDefault(name, Set.of()).isEmpty();
                }
                visitFilter(join.on());
                yield name -> exposed.getOrDefault(name, Set.of());
            }
        };
    }

    private static Function<String, Set<String>> byName(List<RelationColumn> columns, List<Set<String>> outputs) {
        final Map<String, Set<String>> named = new HashMap<>();
        for (int i = 0; i < columns.size() && i < outputs.size(); i++) {
            named.put(columns.get(i).name(), outputs.get(i));
        }
        return name -> named.getOrDefault(name, Set.of());
    }

    private void refuseNames() {
        if (!readsMasked) {
            return;
        }
        names.forEach((name, exempt) -> {
            if (!exempt && captures(name)) {
                throw new QueryValidationException("output name '" + name + "' is refused: it names a table the query reads"
                        + " (or begins with one followed by '.'), and the query reads a masked field, whose mask such a name"
                        + " could capture. Alias the output to another name");
            }
        });
        for (final String param : params) {
            if (captures(param)) {
                throw new QueryValidationException("lambda parameter '" + param + "' is refused: it names a table the query"
                        + " reads, and the query reads a masked field, whose mask such a name could capture. Name the"
                        + " parameter otherwise");
            }
        }
    }

    private boolean captures(String name) {
        return name != null && tables.stream().anyMatch(table -> name.equals(table) || name.startsWith(table + "."));
    }

    /** Walks {@code expr} for lambda parameters and nested statements; a scalar subquery's outputs join {@code depends}. */
    private void visitExpr(Expr expr, Set<String> depends) {
        switch (expr) {
            case null -> { }
            case ScalarSubqueryExpr scalar -> {
                if (scalar.subquery() != null) {
                    outputs(scalar.subquery()).forEach(depends::addAll);
                }
            }
            case LambdaExpr lambda -> {
                if (lambda.params() != null) {
                    params.addAll(lambda.params());
                }
                visitExpr(lambda.body(), depends);
            }
            case FnExpr fn -> {
                if (fn.args() != null) {
                    fn.args().forEach(arg -> visitExpr(arg, depends));
                }
            }
            case ArrayExpr array -> {
                if (array.items() != null) {
                    array.items().forEach(item -> visitExpr(item, depends));
                }
            }
            case CaseExpr caseExpr -> {
                if (caseExpr.when() != null) {
                    for (final CaseBranch branch : caseExpr.when()) {
                        if (branch != null) {
                            // A condition's operands decide the output's value, so they mark it, as MaskMarking walks them.
                            visitFilter(branch.when(), depends);
                            visitExpr(branch.then(), depends);
                        }
                    }
                }
                visitExpr(caseExpr.otherwise(), depends);
            }
            case WindowExpr window -> {
                visitExpr(window.fn(), depends);
                if (window.partitionBy() != null) {
                    window.partitionBy().forEach(key -> visitExpr(key, depends));
                }
                if (window.orderBy() != null) {
                    for (final WindowOrder order : window.orderBy()) {
                        if (order != null) {
                            visitExpr(order.expr(), depends);
                        }
                    }
                }
            }
            default -> { }
        }
    }

    /** Walks a {@code where}, {@code having} or {@code on} tree, whose truth marks no output. */
    private void visitFilter(FilterNode node) {
        visitFilter(node, new HashSet<>());
    }

    /**
     * Walks {@code node} for lambda parameters and nested statements; its operands' scalar subqueries join {@code depends}.
     * A subquery's set or truth value marks nothing, so its body's outputs do not.
     */
    private void visitFilter(FilterNode node, Set<String> depends) {
        switch (node) {
            case null -> { }
            case LogicalNode logical -> {
                if (logical.args() != null) {
                    logical.args().forEach(arg -> visitFilter(arg, depends));
                }
            }
            case ComparisonNode comparison -> {
                if (comparison.args() != null) {
                    comparison.args().forEach(arg -> visitExpr(arg, depends));
                }
            }
            case SubqueryNode subquery -> {
                if (subquery.operands() != null) {
                    subquery.operands().forEach(arg -> visitExpr(arg, depends));
                }
                if (subquery.subquery() != null) {
                    outputs(subquery.subquery());
                }
            }
        }
    }
}
