package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.ColumnMask;
import com.epam.aidial.analytics.service.query.MaskFlag;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.FrameBound;
import com.epam.aidial.analytics.service.query.model.FrameBoundKind;
import com.epam.aidial.analytics.service.query.model.FrameUnit;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.SubqueryNode;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.model.WindowFrame;
import com.epam.aidial.analytics.service.query.model.WindowOrder;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Plans the marks of a statement over masked fields (see {@code openspec/specs/structured-query/spec.md}, "Masked inputs
 * are reported per output cell"): one {@link MaskFlag} column per (mask condition, context) pair its outputs depend on,
 * where the context is the output's own row or group, or a window it reads the field through. Also the two refusals a
 * masked field brings into a block: a name that could capture the table-qualified columns a mask is evaluated over, and
 * an output that would shadow the reserved {@code $masked} member.
 */
final class MaskMarking {

    /** The frame a ranking function reads whatever frame its window declares: the partition's start to the current row. */
    private static final WindowFrame TO_CURRENT_ROW = new WindowFrame(FrameUnit.ROWS,
            new FrameBound(FrameBoundKind.UNBOUNDED_PRECEDING, null), new FrameBound(FrameBoundKind.CURRENT_ROW, null));

    /** How rows a block collapses take a flag. */
    enum Collapse {
        /** No collapse: each output row keeps its own flag. */
        NONE,
        /** A row-mode distinct grouped by every output: the flag over the collapsed rows, {@code max(<flag>)}. */
        GROUPED,
        /**
         * An aggregate-mode distinct, which keeps {@code DISTINCT}: the flag over the groups that collapse into one row,
         * {@code max(<flag>) OVER (PARTITION BY <every output>)}, so every duplicate carries the same flag.
         */
        WINDOWED
    }

    /** One (mask condition, context) pair; {@code window} is {@code null} for the output's own row or group. */
    private record Key(ColumnMask mask, WindowExpr window) {}

    private MaskMarking() {
    }

    /**
     * The flag columns {@code outputs} need, aliased apart from every output name, each carrying the outputs it marks.
     * Empty when no output reads a masked field.
     *
     * @param outputs   by output name, in projection order, the expression the output computes
     * @param bindings  the block's bindings, a masked field's carrying its mask
     * @param partition for {@link Collapse#WINDOWED}, the rendered expression of every output — never its name, which
     *     the data store can resolve to a stored column instead of the output
     */
    static List<Field<?>> flags(ExprTranslator translator, Map<String, QueryFieldBinding> bindings, QueryMode mode,
            Map<String, Expr> outputs, Collapse collapse, List<Field<?>> partition) {
        final Map<Key, Set<String>> marks = new LinkedHashMap<>();
        final Map<Key, Set<String>> nulls = new HashMap<>();
        for (final Map.Entry<String, Expr> output : outputs.entrySet()) {
            final Set<Key> keys = new LinkedHashSet<>();
            collect(output.getValue(), null, Set.of(), bindings, keys);
            keys.forEach(key -> marks.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(output.getKey()));
            final QueryFieldBinding bare = bareComposite(output.getValue(), bindings);
            if (bare != null) {
                nulls.computeIfAbsent(new Key(bare.mask(), null), ignored -> new LinkedHashSet<>()).add(output.getKey());
            }
        }
        final List<Field<?>> flags = new ArrayList<>(marks.size());
        int next = 0;
        for (final Map.Entry<Key, Set<String>> mark : marks.entrySet()) {
            String name;
            do {
                name = MaskFlag.NAME_PREFIX + next++;
            } while (outputs.containsKey(name));
            Field<?> expression = expression(translator, bindings, mode, mark.getKey());
            if (collapse == Collapse.GROUPED) {
                expression = DSL.field("max({0})", Integer.class, expression);
            } else if (collapse == Collapse.WINDOWED) {
                expression = DSL.field("max({0}) OVER (PARTITION BY {1})", Integer.class, expression, DSL.list(partition));
            }
            flags.add(new MaskFlag(expression, mark.getValue(), nulls.getOrDefault(mark.getKey(), Set.of())).as(name));
        }
        return flags;
    }

    /** Whether some output reads a masked field through a window. */
    static boolean readsThroughWindow(Map<String, QueryFieldBinding> bindings, Map<String, Expr> outputs) {
        for (final Expr expr : outputs.values()) {
            final Set<Key> keys = new LinkedHashSet<>();
            collect(expr, null, Set.of(), bindings, keys);
            if (keys.stream().anyMatch(key -> key.window() != null)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The masked binding {@code expr} is when it is a bare masked composite field — an {@code array}, {@code map} or
     * {@code object} — whose absent value inside expressions is its empty value, and whose output cell is therefore
     * nulled from its flag; else {@code null}.
     */
    static QueryFieldBinding bareComposite(Expr expr, Map<String, QueryFieldBinding> bindings) {
        if (!(expr instanceof FieldExpr(String name))) {
            return null;
        }
        final QueryFieldBinding binding = bindings.get(name);
        return binding != null && binding.isMasked() && !binding.isComputed() && !ExprFieldType.isScalar(binding.type()) ? binding : null;
    }

    /**
     * Refuses, for a block over {@code model} that references a masked field, an output name or lambda parameter that
     * equals a table the block reads or begins with that name followed by {@code .}: such a name, declared in the scope
     * a mask is evaluated in, could capture the {@code "<table>"."<column>"} references it reads. An output named after
     * the field it projects bare (an enrichment field projected without {@code as}) is exempt, and the builder renders
     * every reference to it as the field's masked expression, never its name.
     *
     * @param names the block's resolved output names, in projection order (one per {@code query.select()} entry)
     */
    static void refuseCapturingNames(QueryEntityModel model, StructuredQuery query, List<String> names, boolean wildcardMasked) {
        if (!wildcardMasked && StructuredQueryBuilder.referencedFields(query).stream()
                .map(name -> model.bindings().get(name))
                .noneMatch(binding -> binding != null && binding.isMasked())) {
            return;
        }
        final Set<String> tables = new HashSet<>();
        tables.add(model.table().getName());
        model.enrichments().forEach(join -> tables.add(join.table().getName()));
        final List<OutputColumn> select = query.select() == null ? List.of() : query.select();
        for (int i = 0; i < select.size() && i < names.size(); i++) {
            final String name = names.get(i);
            final boolean exempt = select.get(i).expr() instanceof FieldExpr(String field) && field.equals(name);
            if (!exempt && captures(name, tables)) {
                throw new QueryValidationException("output name '" + name + "' is refused: it names a table the query reads"
                        + " (or begins with one followed by '.'), and the query reads a masked field, whose mask such a name"
                        + " could capture. Alias the output to another name");
            }
        }
        final Set<String> params = new LinkedHashSet<>();
        select.forEach(column -> lambdaParams(column.expr(), params));
        lambdaParams(query.filter(), params);
        lambdaParams(query.having(), params);
        for (final String param : params) {
            if (captures(param, tables)) {
                throw new QueryValidationException("lambda parameter '" + param + "' is refused: it names a table the query"
                        + " reads, and the query reads a masked field, whose mask such a name could capture. Name the"
                        + " parameter otherwise");
            }
        }
    }

    /** Refuses a top-level output named {@code $masked}, the reserved row member. */
    static void refuseReservedName(List<String> names) {
        if (names.contains(MaskFlag.MEMBER)) {
            throw new QueryValidationException("output name '" + MaskFlag.MEMBER + "' is reserved: a JSON row lists its"
                    + " absent and partial cells under it. Alias the output to another name");
        }
    }

    private static boolean captures(String name, Set<String> tables) {
        if (name == null) {
            return false;
        }
        for (final String table : tables) {
            if (name.equals(table) || name.startsWith(table + ".")) {
                return true;
            }
        }
        return false;
    }

    private static Field<?> expression(ExprTranslator translator, Map<String, QueryFieldBinding> bindings, QueryMode mode, Key key) {
        final Field<?> absent = DSL.field("(NOT {0})", Integer.class, key.mask().reference());
        if (key.window() == null) {
            return mode == QueryMode.AGGREGATE ? DSL.field("max({0})", Integer.class, absent) : absent;
        }
        final Field<?> function = mode == QueryMode.AGGREGATE
                ? DSL.field("max(max({0}))", Integer.class, absent)
                : DSL.field("max({0})", Integer.class, absent);
        final String fn = key.window().fn() == null || key.window().fn().name() == null
                ? "" : key.window().fn().name().toLowerCase(Locale.ROOT);
        return translator.over(function, key.window(),
                ExprTranslator.RANKING_FUNCTIONS.contains(fn) ? TO_CURRENT_ROW : key.window().frame(), bindings);
    }

    /** Adds to {@code into} the (mask, context) pairs {@code expr} reads, in {@code window}'s context ({@code null}: own). */
    private static void collect(Expr expr, WindowExpr window, Set<String> params, Map<String, QueryFieldBinding> bindings,
            Set<Key> into) {
        switch (expr) {
            case null -> { }
            case FieldExpr(String name) -> {
                if (!params.contains(name)) {
                    final QueryFieldBinding binding = bindings.get(name);
                    if (binding != null && binding.isMasked()) {
                        into.add(new Key(binding.mask(), window));
                    }
                }
            }
            case FnExpr fn -> {
                if (fn.args() != null) {
                    fn.args().forEach(arg -> collect(arg, window, params, bindings, into));
                }
            }
            case ArrayExpr array -> {
                if (array.items() != null) {
                    array.items().forEach(item -> collect(item, window, params, bindings, into));
                }
            }
            case CaseExpr caseExpr -> {
                if (caseExpr.when() != null) {
                    for (final CaseBranch branch : caseExpr.when()) {
                        if (branch != null) {
                            collect(branch.when(), window, params, bindings, into);
                            collect(branch.then(), window, params, bindings, into);
                        }
                    }
                }
                collect(caseExpr.otherwise(), window, params, bindings, into);
            }
            case WindowExpr inner -> {
                collect(inner.fn(), inner, params, bindings, into);
                if (inner.partitionBy() != null) {
                    inner.partitionBy().forEach(key -> collect(key, inner, params, bindings, into));
                }
                if (inner.orderBy() != null) {
                    for (final WindowOrder order : inner.orderBy()) {
                        if (order != null) {
                            collect(order.expr(), inner, params, bindings, into);
                        }
                    }
                }
            }
            case LambdaExpr lambda -> {
                final Set<String> scoped = new HashSet<>(params);
                if (lambda.params() != null) {
                    scoped.addAll(lambda.params());
                }
                collect(lambda.body(), window, scoped, bindings, into);
            }
            // A value, a parameter or a lambda parameter reads no field; a scalar subquery's mark is its own.
            default -> { }
        }
    }

    private static void collect(FilterNode node, WindowExpr window, Set<String> params, Map<String, QueryFieldBinding> bindings,
            Set<Key> into) {
        switch (node) {
            case null -> { }
            case LogicalNode logical -> {
                if (logical.args() != null) {
                    logical.args().forEach(arg -> collect(arg, window, params, bindings, into));
                }
            }
            case ComparisonNode comparison -> {
                if (comparison.args() != null) {
                    comparison.args().forEach(arg -> collect(arg, window, params, bindings, into));
                }
            }
            // A set or truth a subquery yields marks nothing; the operands compared with it mark by the row rule.
            case SubqueryNode subquery -> {
                if (subquery.operands() != null) {
                    subquery.operands().forEach(arg -> collect(arg, window, params, bindings, into));
                }
            }
        }
    }

    private static void lambdaParams(Expr expr, Set<String> into) {
        switch (expr) {
            case null -> { }
            case LambdaExpr lambda -> {
                if (lambda.params() != null) {
                    into.addAll(lambda.params());
                }
                lambdaParams(lambda.body(), into);
            }
            case FnExpr fn -> {
                if (fn.args() != null) {
                    fn.args().forEach(arg -> lambdaParams(arg, into));
                }
            }
            case ArrayExpr array -> {
                if (array.items() != null) {
                    array.items().forEach(item -> lambdaParams(item, into));
                }
            }
            case CaseExpr caseExpr -> {
                if (caseExpr.when() != null) {
                    for (final CaseBranch branch : caseExpr.when()) {
                        if (branch != null) {
                            lambdaParams(branch.when(), into);
                            lambdaParams(branch.then(), into);
                        }
                    }
                }
                lambdaParams(caseExpr.otherwise(), into);
            }
            case WindowExpr window -> {
                lambdaParams(window.fn(), into);
                if (window.partitionBy() != null) {
                    window.partitionBy().forEach(key -> lambdaParams(key, into));
                }
                if (window.orderBy() != null) {
                    window.orderBy().stream().filter(order -> order != null).forEach(order -> lambdaParams(order.expr(), into));
                }
            }
            default -> { }
        }
    }

    private static void lambdaParams(FilterNode node, Set<String> into) {
        switch (node) {
            case null -> { }
            case LogicalNode logical -> {
                if (logical.args() != null) {
                    logical.args().forEach(arg -> lambdaParams(arg, into));
                }
            }
            case ComparisonNode comparison -> {
                if (comparison.args() != null) {
                    comparison.args().forEach(arg -> lambdaParams(arg, into));
                }
            }
            case SubqueryNode subquery -> {
                if (subquery.operands() != null) {
                    subquery.operands().forEach(arg -> lambdaParams(arg, into));
                }
            }
        }
    }
}
