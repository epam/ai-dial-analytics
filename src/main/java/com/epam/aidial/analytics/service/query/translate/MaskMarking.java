package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.MaskFlag;
import com.epam.aidial.analytics.service.query.OutputFlag;
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
import com.epam.aidial.analytics.service.query.model.ScalarSubqueryExpr;
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

    /** Which flags a block projects. */
    enum Mode {
        /** None: the statement reads no masked field, or the block's rows mark nothing (an {@code in} subquery). */
        NONE,
        /** The statement's own outputs: one {@link MaskFlag} per (mark source, context), carrying the outputs it marks. */
        ROOT,
        /** A nested block's: an {@link OutputFlag} mark flag and bare-absent flag per output, in output order. */
        NESTED
    }

    /**
     * One (mark source, context) pair: the source a {@link QueryFieldBinding#markSource()} or a marked scalar subquery,
     * {@code window} {@code null} for the output's own row or group.
     */
    private record Key(Object source, WindowExpr window) {}

    /** The bare-absent flag of the output it keys, apart from every mark key. */
    private record BareKey(Object source) {}

    private MaskMarking() {
    }

    /**
     * The flag columns {@code outputs} need under {@code marks}, aliased apart from every output name. Under
     * {@link Mode#ROOT} each carries the outputs it marks and nulls, and there are none when no output reads an absent
     * input; under {@link Mode#NESTED} every output has its two, a constant {@code 0} where nothing applies.
     *
     * @param outputs   by output name, in projection order, the expression the output computes
     * @param bindings  the block's bindings, a masked field's carrying its mask and an operand column's its marks
     * @param partition for {@link Collapse#WINDOWED}, the rendered expression of every output — never its name, which
     *     the data store can resolve to a stored column instead of the output — and the outputs' bare-absent flags
     */
    static List<Field<?>> flags(ExprTranslator translator, Map<String, QueryFieldBinding> bindings, QueryMode mode,
            Map<String, Expr> outputs, Collapse collapse, List<Field<?>> partition, Mode marks) {
        if (marks == Mode.NONE) {
            return List.of();
        }
        final SubqueryRenderer subqueries = translator.subqueryRenderer();
        final Map<Object, Field<?>> indicators = new HashMap<>();
        final List<Set<Key>> perOutput = new ArrayList<>(outputs.size());
        final List<Field<?>> bares = new ArrayList<>(outputs.size());
        for (final Expr expr : outputs.values()) {
            final Set<Key> keys = new LinkedHashSet<>();
            collect(expr, null, Set.of(), bindings, subqueries, indicators, keys);
            perOutput.add(keys);
            bares.add(bareIndicator(expr, bindings));
        }
        return marks == Mode.ROOT
                ? rootFlags(translator, bindings, mode, outputs, collapse, partition, indicators, perOutput, bares)
                : nestedFlags(translator, bindings, mode, outputs, collapse, partition, indicators, perOutput, bares);
    }

    private static List<Field<?>> rootFlags(ExprTranslator translator, Map<String, QueryFieldBinding> bindings, QueryMode mode,
            Map<String, Expr> outputs, Collapse collapse, List<Field<?>> partition, Map<Object, Field<?>> indicators,
            List<Set<Key>> perOutput, List<Field<?>> bares) {
        final List<String> names = List.copyOf(outputs.keySet());
        final Map<Object, Set<String>> marks = new LinkedHashMap<>();
        final Map<Object, Set<String>> nulls = new HashMap<>();
        final Map<Object, Field<?>> expressions = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            final String name = names.get(i);
            for (final Key key : perOutput.get(i)) {
                marks.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(name);
            }
            if (bares.get(i) == null) {
                continue;
            }
            final QueryFieldBinding binding = bindings.get(((FieldExpr) outputs.get(name)).name());
            // A masked composite field's own flag is set exactly where it is absent; an operand column's mark is not.
            final Object key = binding.isMasked() ? new Key(binding.mask(), null) : new BareKey(binding.markSource());
            if (key instanceof BareKey) {
                marks.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
                expressions.put(key, own(mode, bares.get(i)));
            }
            nulls.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(name);
        }
        final List<Field<?>> flags = new ArrayList<>(marks.size());
        int next = 0;
        for (final Map.Entry<Object, Set<String>> mark : marks.entrySet()) {
            String name;
            do {
                name = MaskFlag.NAME_PREFIX + next++;
            } while (outputs.containsKey(name));
            final Field<?> expression = mark.getKey() instanceof Key key
                    ? expression(translator, bindings, mode, key, indicators) : expressions.get(mark.getKey());
            flags.add(new MaskFlag(collapsed(expression, collapse, partition), mark.getValue(),
                    nulls.getOrDefault(mark.getKey(), Set.of())).as(name));
        }
        return flags;
    }

    private static List<Field<?>> nestedFlags(ExprTranslator translator, Map<String, QueryFieldBinding> bindings, QueryMode mode,
            Map<String, Expr> outputs, Collapse collapse, List<Field<?>> partition, Map<Object, Field<?>> indicators,
            List<Set<Key>> perOutput, List<Field<?>> bares) {
        final Field<?> none = DSL.field("toUInt8(0)", Integer.class);
        final List<Field<?>> flags = new ArrayList<>(2 * outputs.size());
        for (int i = 0; i < perOutput.size(); i++) {
            final List<Field<?>> keys = new ArrayList<>();
            for (final Key key : perOutput.get(i)) {
                keys.add(expression(translator, bindings, mode, key, indicators));
            }
            final Field<?> mark = keys.isEmpty() ? none
                    : keys.size() == 1 ? keys.getFirst() : DSL.field("greatest({0})", Integer.class, DSL.list(keys));
            final Field<?> bare = bares.get(i) == null ? none : own(mode, bares.get(i));
            flags.add(new OutputFlag(collapsed(mark, collapse, partition), i, false, keys.isEmpty())
                    .as(unique("__adas_mf_" + i, outputs)));
            flags.add(new OutputFlag(collapsed(bare, collapse, partition), i, true, bares.get(i) == null)
                    .as(unique("__adas_bf_" + i, outputs)));
        }
        return flags;
    }

    /** Whether some output of {@code outputs} reads an input that can be absent, so its block's rows carry marks. */
    static boolean readsMarks(ExprTranslator translator, Map<String, QueryFieldBinding> bindings, Map<String, Expr> outputs) {
        final Set<Key> keys = new LinkedHashSet<>();
        for (final Expr expr : outputs.values()) {
            collect(expr, null, Set.of(), bindings, translator.subqueryRenderer(), new HashMap<>(), keys);
            if (!keys.isEmpty() || bareIndicator(expr, bindings) != null) {
                return true;
            }
        }
        return false;
    }

    /** {@code name}, suffixed until no output is named so. */
    private static String unique(String name, Map<String, Expr> outputs) {
        String candidate = name;
        while (outputs.containsKey(candidate)) {
            candidate = candidate + "_";
        }
        return candidate;
    }

    /** {@code flag} in the output's own row ({@code flag}) or group ({@code max(flag)}). */
    private static Field<?> own(QueryMode mode, Field<?> flag) {
        return mode == QueryMode.AGGREGATE ? DSL.field("max({0})", Integer.class, flag) : flag;
    }

    private static Field<?> collapsed(Field<?> expression, Collapse collapse, List<Field<?>> partition) {
        return switch (collapse) {
            case NONE -> expression;
            case GROUPED -> DSL.field("max({0})", Integer.class, expression);
            case WINDOWED -> DSL.field("max({0}) OVER (PARTITION BY {1})", Integer.class, expression, DSL.list(partition));
        };
    }

    /**
     * The flag that nulls {@code expr}'s output cell when it is a bare composite field whose cell is absent — a masked
     * {@code array}, {@code map} or {@code object} field, whose absent value inside expressions is its empty value, or an
     * operand column that is one — else {@code null}.
     */
    static Field<?> bareIndicator(Expr expr, Map<String, QueryFieldBinding> bindings) {
        if (!(expr instanceof FieldExpr(String name))) {
            return null;
        }
        final QueryFieldBinding binding = bindings.get(name);
        return binding == null ? null : binding.bareIndicator();
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

    private static Field<?> expression(ExprTranslator translator, Map<String, QueryFieldBinding> bindings, QueryMode mode, Key key,
            Map<Object, Field<?>> indicators) {
        final Field<?> absent = indicators.get(key.source());
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

    /**
     * Adds to {@code into} the (mark source, context) pairs {@code expr} reads, in {@code window}'s context ({@code null}:
     * own), and to {@code indicators} each source's absent indicator.
     */
    private static void collect(Expr expr, WindowExpr window, Set<String> params, Map<String, QueryFieldBinding> bindings,
            SubqueryRenderer subqueries, Map<Object, Field<?>> indicators, Set<Key> into) {
        switch (expr) {
            case null -> { }
            case FieldExpr(String name) -> {
                final QueryFieldBinding binding = params.contains(name) ? null : bindings.get(name);
                if (binding != null && binding.markSource() != null) {
                    indicators.putIfAbsent(binding.markSource(), binding.absentIndicator());
                    into.add(new Key(binding.markSource(), window));
                }
            }
            // A scalar subquery carries its own output's mark, rendered with it.
            case ScalarSubqueryExpr scalar -> {
                final Field<?> flag = scalar.subquery() == null ? null : subqueries.scalarFlag(scalar.subquery());
                if (flag != null) {
                    indicators.putIfAbsent(scalar.subquery(), flag);
                    into.add(new Key(scalar.subquery(), window));
                }
            }
            case FnExpr fn -> {
                if (fn.args() != null) {
                    fn.args().forEach(arg -> collect(arg, window, params, bindings, subqueries, indicators, into));
                }
            }
            case ArrayExpr array -> {
                if (array.items() != null) {
                    array.items().forEach(item -> collect(item, window, params, bindings, subqueries, indicators, into));
                }
            }
            case CaseExpr caseExpr -> {
                if (caseExpr.when() != null) {
                    for (final CaseBranch branch : caseExpr.when()) {
                        if (branch != null) {
                            collect(branch.when(), window, params, bindings, subqueries, indicators, into);
                            collect(branch.then(), window, params, bindings, subqueries, indicators, into);
                        }
                    }
                }
                collect(caseExpr.otherwise(), window, params, bindings, subqueries, indicators, into);
            }
            case WindowExpr inner -> {
                collect(inner.fn(), inner, params, bindings, subqueries, indicators, into);
                if (inner.partitionBy() != null) {
                    inner.partitionBy().forEach(key -> collect(key, inner, params, bindings, subqueries, indicators, into));
                }
                if (inner.orderBy() != null) {
                    for (final WindowOrder order : inner.orderBy()) {
                        if (order != null) {
                            collect(order.expr(), inner, params, bindings, subqueries, indicators, into);
                        }
                    }
                }
            }
            case LambdaExpr lambda -> {
                final Set<String> scoped = new HashSet<>(params);
                if (lambda.params() != null) {
                    scoped.addAll(lambda.params());
                }
                collect(lambda.body(), window, scoped, bindings, subqueries, indicators, into);
            }
            // A value, a parameter or a lambda parameter reads no field.
            default -> { }
        }
    }

    private static void collect(FilterNode node, WindowExpr window, Set<String> params, Map<String, QueryFieldBinding> bindings,
            SubqueryRenderer subqueries, Map<Object, Field<?>> indicators, Set<Key> into) {
        switch (node) {
            case null -> { }
            case LogicalNode logical -> {
                if (logical.args() != null) {
                    logical.args().forEach(arg -> collect(arg, window, params, bindings, subqueries, indicators, into));
                }
            }
            case ComparisonNode comparison -> {
                if (comparison.args() != null) {
                    comparison.args().forEach(arg -> collect(arg, window, params, bindings, subqueries, indicators, into));
                }
            }
            // A set or truth a subquery yields marks nothing; the operands compared with it mark by the row rule.
            case SubqueryNode subquery -> {
                if (subquery.operands() != null) {
                    subquery.operands().forEach(arg -> collect(arg, window, params, bindings, subqueries, indicators, into));
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
