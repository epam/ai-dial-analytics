package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.service.query.ArrayValuedFunctions;
import com.epam.aidial.analytics.service.query.EnrichmentJoin;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.QueryFunctionCatalog;
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
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.OutputColumnNaming;
import com.epam.aidial.analytics.service.query.model.PageSpec;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.ScalarSubqueryExpr;
import com.epam.aidial.analytics.service.query.model.SortItem;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.SubqueryNode;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.model.WindowOrder;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.jooq.Condition;
import org.jooq.impl.DSL;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Decides how a single-entity read statement is <em>shaped</em> so a heavy enrichment column costs what the
 * statement returns or keeps rather than what its window holds. Three independent rewrites, each a closed rule
 * that changes no result (see {@code openspec/specs/structured-query/spec.md}):
 *
 * <ul>
 *   <li>{@link #latePlan late materialization} — a row page whose heavy enrichment columns are projection-only
 *       picks its page's source keys first and joins the heavy enrichments for exactly those keys;
 *   <li>{@link #pushableConjuncts conjunct pushdown} — an allow-listed enrichment-only conjunct that is false on
 *       an unmatched row is also evaluated inside the enrichment's derived table;
 *   <li>{@link #planCalls call pushdown} — an allow-listed call over a heavy text column whose value on an
 *       unmatched row is already its result type's default is computed inside the derived table.
 * </ul>
 *
 * <p>Only plans: what is rendered, and where, is {@link StructuredQueryBuilder}'s and {@code EnrichmentJoins}'
 * business. Every rule here reads the DSL as written, never the translated SQL, so a decision cannot depend on how
 * a later translation step happens to spell a call.
 */
final class ReadShaping {

    /** The page-key column the page block projects the source grain key under. */
    static final String PAGE_KEY = "__adas_lm_key";

    /** The page-key column the page block projects the aligned source partition column under. */
    static final String PAGE_PARTITION = "__adas_lm_part";

    /** The alias of the page block inside the page-key subquery. */
    static final String PAGE_ALIAS = "__adas_lm";

    /** The prefix of a pushed call's column inside its enrichment's derived table. */
    static final String CALL_PREFIX = "__adas_c";

    private static final String NOW = "now";
    private static final String STARTS_WITH = "starts_with";
    private static final String CONTAINS = "contains";
    private static final String LENGTH = "length";

    /**
     * Calls whose value on an unmatched row is already the default of their result type: over a
     * {@code Nullable} column each returns NULL — or, for the two that read an absent document as {@code ''}, the
     * empty array and the empty string — and over a non-nullable column each returns {@code 0}, {@code ''},
     * {@code false} or {@code []} for {@code ''}. {@code starts_with}/{@code contains} qualify only with a
     * non-empty literal, which {@link #pushableCall} checks.
     */
    private static final Set<String> PUSHABLE_CALLS = Set.of(LENGTH,
            "json_extract_string", "json_extract_long", "json_extract_double", "json_extract_bool",
            ArrayValuedFunctions.JSON_EXTRACT_ARRAY, "json_extract_string_ci", STARTS_WITH, CONTAINS);

    private static final Set<QueryFieldType> NUMERIC =
            Set.of(QueryFieldType.INTEGER, QueryFieldType.LONG, QueryFieldType.DECIMAL);

    private ReadShaping() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // A. Late materialization

    /**
     * What a late-materialized block needs: the heavy enrichments joined for the page's keys, the join whose
     * source key (and aligned source partition) every one of them shares, the select entries the page block
     * keeps (under their resolved names, since a sort item names them), and the page block's row bound.
     */
    record LatePlan(Set<String> tables, EnrichmentJoin keyJoin, List<OutputColumn> pageEntries, int pageRows) {
    }

    /**
     * The late-materialization plan for {@code query}, or {@code null} when the block does not qualify.
     * {@code limit} is the block's rendered row limit (the default substituted) and {@code offset} its offset.
     */
    static LatePlan latePlan(QueryEntityModel model, StructuredQuery query, long offset, int limit) {
        if (query.mode() != QueryMode.ROW || query.distinct() || query.having() != null
                || query.select() == null || query.select().isEmpty()
                || (query.groupBy() != null && !query.groupBy().isEmpty())) {
            return null;
        }
        if (offset < 0 || limit <= 0 || offset + limit > Integer.MAX_VALUE) {
            return null;
        }
        final Set<String> referenced = StructuredQueryBuilder.referencedFields(query);
        final Set<String> tables = new LinkedHashSet<>();
        final Set<String> heavyFields = new HashSet<>();
        EnrichmentJoin keyJoin = null;
        for (final EnrichmentJoin enrichment : model.enrichments()) {
            final Set<String> heavy = heavyFieldsOf(model, enrichment);
            if (Collections.disjoint(heavy, referenced)) {
                continue;
            }
            if (!enrichment.keyable()) {
                return null;
            }
            if (keyJoin == null) {
                keyJoin = enrichment;
            } else if (!sameSourceKeys(keyJoin, enrichment)) {
                return null;
            }
            tables.add(enrichment.table().getName());
            heavyFields.addAll(heavy);
        }
        if (tables.isEmpty()) {
            return null;
        }
        // Heavy columns only in the projection: not in the filter, not sorted directly or through a select output.
        if (!Collections.disjoint(StructuredQueryBuilder.referencedFields(query.filter()), heavyFields)) {
            return null;
        }
        final Set<String> sorted = new HashSet<>();
        if (query.sort() != null) {
            for (final SortItem item : query.sort()) {
                if (item.field() != null) {
                    sorted.add(item.field());
                }
            }
        }
        if (!Collections.disjoint(sorted, heavyFields)) {
            return null;
        }
        final List<String> names = OutputColumnNaming.resolve(query.select());
        final List<OutputColumn> pageEntries = new ArrayList<>();
        for (int i = 0; i < query.select().size(); i++) {
            final OutputColumn column = query.select().get(i);
            final String name = names.get(i);
            if (PAGE_KEY.equals(name) || PAGE_PARTITION.equals(name)) {
                return null;
            }
            final boolean[] disqualified = {false};
            walk(column.expr(), node -> {
                if (node instanceof WindowExpr
                        || node instanceof FnExpr fn && (ArrayValuedFunctions.ARRAY_JOIN.equalsIgnoreCase(fn.name())
                                || nonDeterministicPerRow(fn.name()))) {
                    disqualified[0] = true;
                }
            });
            if (disqualified[0]) {
                return null;
            }
            final boolean readsHeavy = !Collections.disjoint(referencedFields(column.expr()), heavyFields);
            if (sorted.contains(name)) {
                if (readsHeavy) {
                    return null;
                }
                // A sort item resolves to a select output by name, so the page block keeps the entry under that
                // name: dropping it would silently re-resolve the sort item to an entity column of the same name.
                pageEntries.add(new OutputColumn(column.expr(), name));
            }
        }
        final boolean[] nonDeterministic = {false};
        walkFilter(query.filter(), node -> {
            if (node instanceof FnExpr fn && nonDeterministicPerRow(fn.name())) {
                nonDeterministic[0] = true;
            }
        });
        if (nonDeterministic[0]) {
            return null;
        }
        return new LatePlan(tables, keyJoin, pageEntries, (int) (offset + limit));
    }

    /**
     * A catalog function that two evaluations of one statement may answer differently. {@code now} is exempt:
     * ClickHouse evaluates it once per statement, so the page block and the outer statement see one instant.
     */
    private static boolean nonDeterministicPerRow(String name) {
        return name != null && !NOW.equalsIgnoreCase(name) && QueryFunctionCatalog.isNonDeterministic(name);
    }

    private static boolean sameSourceKeys(EnrichmentJoin a, EnrichmentJoin b) {
        if (!a.sourceKey().getQualifiedName().equals(b.sourceKey().getQualifiedName())) {
            return false;
        }
        if (a.partitionAligned() != b.partitionAligned()) {
            return false;
        }
        return !a.partitionAligned()
                || a.sourcePartition().getQualifiedName().equals(b.sourcePartition().getQualifiedName());
    }

    private static Set<String> heavyFieldsOf(QueryEntityModel model, EnrichmentJoin enrichment) {
        final Set<String> heavy = new HashSet<>();
        for (final String name : enrichment.exposedNames()) {
            final QueryFieldBinding binding = model.bindings().get(name);
            if (binding != null && binding.heavy()) {
                heavy.add(name);
            }
        }
        return heavy;
    }

    // ---------------------------------------------------------------------------------------------------------
    // B. Conjunct pushdown

    /** A top-level conjunct over {@code field} alone, copied into {@code table}'s derived table. */
    record PushedConjunct(String table, String field, FilterNode node) {
    }

    /**
     * The top-level conjuncts of {@code filter} that may be copied into an enrichment's derived table, in filter
     * order. An enrichment named in {@code excluded} gets none.
     */
    static List<PushedConjunct> pushableConjuncts(QueryEntityModel model, FilterNode filter, Collection<String> excluded) {
        final List<PushedConjunct> pushed = new ArrayList<>();
        for (final FilterNode conjunct : StructuredQueryBuilder.conjuncts(filter)) {
            final String field = pushableConjunctField(model, conjunct);
            if (field == null) {
                continue;
            }
            final EnrichmentJoin owner = model.ownerOf(field);
            if (owner == null || !owner.keyable() || excluded.contains(owner.table().getName())) {
                continue;
            }
            pushed.add(new PushedConjunct(owner.table().getName(), field, conjunct));
        }
        return pushed;
    }

    /**
     * Whether {@code conjunct} is a <b>lookup</b> a partition pre-selection may be drawn from (see
     * {@code PartitionPreselection}): a pushable conjunct — so it names one enrichment field alone and is false on an
     * unmatched row — of the form {@code field = <literal>} or {@code field IN (<literals>)} where no literal is the
     * field type's default (both checked by {@link #pushableConjunctField}), over a field that is not heavy. A
     * substring match also rejects an unmatched row, but is not selective: it would cost a scan to name nearly every
     * partition. A heavy field is left out because the pre-selection reads its column across every partition, which
     * for a body is the very read the shaping exists to avoid. Whether the field's enrichment is aligned is the
     * pre-selection's own check.
     */
    static boolean lookup(QueryEntityModel model, FilterNode conjunct) {
        final String field = pushableConjunctField(model, conjunct);
        return field != null
                && conjunct instanceof ComparisonNode(ComparisonOp op, List<Expr> args)
                && (op == ComparisonOp.EQ || op == ComparisonOp.IN)
                && args.getFirst() instanceof FieldExpr
                && !heavy(model, field);
    }

    /**
     * The statement's bounds on {@code join}'s source partition column, rendered on the enrichment's alignment
     * column instead — what a partition pre-selection over {@code join} narrows its own read by. Each is a top-level
     * source-only comparison of {@code filter} that reads the source's partition column and nothing else, and holds
     * no subquery; under the tuple join an admitted row's enrichment row carries the same value, so the image holds
     * for it whenever the conjunct holds for the row. Empty when {@code join} is not aligned or nothing bounds it.
     */
    static List<Condition> partitionBounds(FilterTranslator translator, QueryEntityModel model, EnrichmentJoin join,
            FilterNode filter) {
        if (filter == null || !join.partitionAligned()) {
            return List.of();
        }
        final Map<String, QueryFieldBinding> rebound = new HashMap<>(model.bindings());
        final Set<String> partitionNames = new HashSet<>();
        for (final Map.Entry<String, QueryFieldBinding> entry : model.bindings().entrySet()) {
            final QueryFieldBinding binding = entry.getValue();
            if (binding.field().getQualifiedName().equals(join.sourcePartition().getQualifiedName())) {
                partitionNames.add(entry.getKey());
                rebound.put(entry.getKey(), new QueryFieldBinding(binding.name(),
                        DSL.field(join.enrichmentPartition().getQualifiedName(), binding.field().getDataType()),
                        binding.type(), binding.elementType(), binding.heavy()));
            }
        }
        if (partitionNames.isEmpty()) {
            return List.of();
        }
        final List<Condition> bounds = new ArrayList<>();
        for (final FilterNode conjunct : StructuredQueryBuilder.sourceOnlyConjuncts(model, filter)) {
            final Set<String> reads = StructuredQueryBuilder.referencedFields(conjunct);
            if (!(conjunct instanceof ComparisonNode) || reads.isEmpty() || !partitionNames.containsAll(reads)) {
                continue;
            }
            final boolean[] subquery = {false};
            walkFilter(conjunct, node -> subquery[0] |= node instanceof ScalarSubqueryExpr);
            if (!subquery[0]) {
                bounds.add(translator.toCondition(conjunct, rebound));
            }
        }
        return bounds;
    }

    /** The one enrichment field {@code conjunct} names when it is of an allow-listed shape, else {@code null}. */
    private static String pushableConjunctField(QueryEntityModel model, FilterNode conjunct) {
        if (!(conjunct instanceof ComparisonNode(ComparisonOp op, List<Expr> args)) || args == null || args.size() != 2) {
            return null;
        }
        final Expr left = args.get(0);
        final Expr right = args.get(1);
        if (left instanceof FieldExpr(String name)) {
            final QueryFieldType type = fieldType(model, name);
            if (type == null) {
                return null;
            }
            final boolean accepted = switch (op) {
                case CO, ICO -> type == QueryFieldType.STRING && nonEmptyString(right);
                case EQ -> nonDefaultLiteral(type, right);
                case IN -> right instanceof ArrayExpr(List<Expr> items) && items != null && !items.isEmpty()
                        && items.stream().allMatch(item -> nonDefaultLiteral(type, item));
                default -> false;
            };
            return accepted ? name : null;
        }
        // eq(starts_with(field, 'x'), true) / eq(contains(field, 'x'), true): startsWith('', 'x') and
        // position('', 'x') > 0 are both false, and both are NULL over NULL.
        if (op == ComparisonOp.EQ && left instanceof FnExpr(String fnName, boolean distinct, List<Expr> fnArgs) && !distinct
                && (STARTS_WITH.equalsIgnoreCase(fnName) || CONTAINS.equalsIgnoreCase(fnName))
                && fnArgs != null && fnArgs.size() == 2
                && fnArgs.get(0) instanceof FieldExpr(String name)
                && fieldType(model, name) == QueryFieldType.STRING
                && nonEmptyString(fnArgs.get(1))
                && right instanceof ValueExpr(ValueType valueType, String value) && valueType == ValueType.BOOLEAN
                && "true".equalsIgnoreCase(value)) {
            return name;
        }
        return null;
    }

    /** The type of {@code name} when it is an enrichment field, else {@code null}. */
    private static QueryFieldType fieldType(QueryEntityModel model, String name) {
        if (model.ownerOf(name) == null) {
            return null;
        }
        final QueryFieldBinding binding = model.bindings().get(name);
        return binding == null ? null : binding.type();
    }

    /** A non-null literal that is not {@code type}'s default: {@code ''} for text, {@code 0} for a number. */
    private static boolean nonDefaultLiteral(QueryFieldType type, Expr expr) {
        if (!(expr instanceof ValueExpr(ValueType valueType, String value)) || value == null) {
            return false;
        }
        if (type == QueryFieldType.STRING) {
            return valueType == ValueType.STRING && !value.isEmpty();
        }
        if (NUMERIC.contains(type)) {
            if (valueType != ValueType.INTEGER && valueType != ValueType.LONG && valueType != ValueType.DECIMAL) {
                return false;
            }
            try {
                return new BigDecimal(value.trim()).signum() != 0;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return false;
    }

    private static boolean nonEmptyString(Expr expr) {
        return expr instanceof ValueExpr(ValueType valueType, String value) && valueType == ValueType.STRING
                && value != null && !value.isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------------
    // C. Call pushdown

    /** A call computed inside {@code table}'s derived table and read back as {@code table.alias}. */
    record PushedCall(String table, String field, FnExpr call, String alias) {
    }

    /**
     * The call pushdown for one statement: the calls to compute inside their enrichment's derived table, and every
     * field the statement reads as a column somewhere — which therefore stays projected.
     */
    record CallPlan(List<PushedCall> calls, Set<String> raw) {
    }

    /**
     * Plans the calls of {@code exprs} and {@code filters} to compute inside their enrichment's derived table,
     * one per distinct call, numbered from {@code firstAlias} in first-seen order. A heavy text field is pushed
     * only when every reference to it is a pushable call; {@code rawNames} are further names the statement reads as
     * columns (sort items, group-by keys), which disqualify a field they name. An enrichment named in
     * {@code excluded} gets none.
     */
    static CallPlan planCalls(QueryEntityModel model, List<Expr> exprs, List<FilterNode> filters,
            Collection<String> rawNames, Collection<String> excluded, int firstAlias) {
        final Map<String, List<FnExpr>> calls = new LinkedHashMap<>();
        final Set<String> raw = new HashSet<>(rawNames);
        for (final Expr expr : exprs) {
            scan(model, expr, false, calls, raw);
        }
        for (final FilterNode filter : filters) {
            scanFilter(model, filter, false, calls, raw);
        }
        final List<PushedCall> pushed = new ArrayList<>();
        int next = firstAlias;
        for (final Map.Entry<String, List<FnExpr>> entry : calls.entrySet()) {
            final String field = entry.getKey();
            if (raw.contains(field)) {
                continue;
            }
            final EnrichmentJoin owner = model.ownerOf(field);
            if (owner == null || !owner.keyable() || excluded.contains(owner.table().getName())) {
                continue;
            }
            for (final FnExpr call : new LinkedHashSet<>(entry.getValue())) {
                pushed.add(new PushedCall(owner.table().getName(), field, call, CALL_PREFIX + next++));
            }
        }
        return new CallPlan(pushed, raw);
    }

    /** Whether {@code field} is a heavy enrichment column. */
    static boolean heavy(QueryEntityModel model, String field) {
        final QueryFieldBinding binding = model.bindings().get(field);
        return binding != null && binding.heavy() && model.ownerOf(field) != null;
    }

    private static void scan(QueryEntityModel model, Expr expr, boolean inLambda,
            Map<String, List<FnExpr>> calls, Set<String> raw) {
        switch (expr) {
            case null -> { }
            case FieldExpr field -> raw.add(field.name());
            case FnExpr fn -> {
                final String field = inLambda ? null : pushableCall(model, fn);
                if (field != null) {
                    calls.computeIfAbsent(field, ignored -> new ArrayList<>()).add(fn);
                } else if (fn.args() != null) {
                    fn.args().forEach(arg -> scan(model, arg, inLambda, calls, raw));
                }
            }
            case ArrayExpr array -> {
                if (array.items() != null) {
                    array.items().forEach(item -> scan(model, item, inLambda, calls, raw));
                }
            }
            case CaseExpr caseExpr -> {
                if (caseExpr.when() != null) {
                    for (final CaseBranch branch : caseExpr.when()) {
                        if (branch != null) {
                            scanFilter(model, branch.when(), inLambda, calls, raw);
                            scan(model, branch.then(), inLambda, calls, raw);
                        }
                    }
                }
                scan(model, caseExpr.otherwise(), inLambda, calls, raw);
            }
            case WindowExpr window -> {
                scan(model, window.fn(), inLambda, calls, raw);
                if (window.partitionBy() != null) {
                    window.partitionBy().forEach(key -> scan(model, key, inLambda, calls, raw));
                }
                if (window.orderBy() != null) {
                    for (final WindowOrder order : window.orderBy()) {
                        if (order != null) {
                            scan(model, order.expr(), inLambda, calls, raw);
                        }
                    }
                }
            }
            // A column captured inside a lambda body is read raw: the body is evaluated per array element, so a
            // call there is not a row-local value the derived table could compute once.
            case LambdaExpr lambda -> scan(model, lambda.body(), true, calls, raw);
            default -> { }
        }
    }

    private static void scanFilter(QueryEntityModel model, FilterNode node, boolean inLambda,
            Map<String, List<FnExpr>> calls, Set<String> raw) {
        switch (node) {
            case null -> { }
            case LogicalNode logical -> {
                if (logical.args() != null) {
                    logical.args().forEach(child -> scanFilter(model, child, inLambda, calls, raw));
                }
            }
            case ComparisonNode comparison -> {
                if (comparison.args() != null) {
                    comparison.args().forEach(arg -> scan(model, arg, inLambda, calls, raw));
                }
            }
            case SubqueryNode subquery -> {
                if (subquery.operands() != null) {
                    subquery.operands().forEach(arg -> scan(model, arg, inLambda, calls, raw));
                }
            }
        }
    }

    /** The heavy text enrichment field {@code fn} is a pushable call over, else {@code null}. */
    private static String pushableCall(QueryEntityModel model, FnExpr fn) {
        final String name = fn.name() == null ? "" : fn.name().toLowerCase(Locale.ROOT);
        if (fn.distinct() || !PUSHABLE_CALLS.contains(name) || fn.args() == null || fn.args().isEmpty()) {
            return null;
        }
        if (!(fn.args().getFirst() instanceof FieldExpr(String field))) {
            return null;
        }
        final QueryFieldBinding binding = model.bindings().get(field);
        if (binding == null || !binding.heavy() || binding.type() != QueryFieldType.STRING || model.ownerOf(field) == null) {
            return null;
        }
        final List<Expr> rest = fn.args().subList(1, fn.args().size());
        for (final Expr arg : rest) {
            if (!(arg instanceof ValueExpr(ValueType valueType, String value)) || valueType == ValueType.NULL || value == null) {
                return null;
            }
        }
        return switch (name) {
            case LENGTH -> rest.isEmpty() ? field : null;
            case STARTS_WITH, CONTAINS -> rest.size() == 1 && nonEmptyString(rest.getFirst()) ? field : null;
            default -> rest.isEmpty() ? null : field;
        };
    }

    // ---------------------------------------------------------------------------------------------------------
    // Shared

    /** Every field name {@code expr} references, a lambda body's captured columns included. */
    static Set<String> referencedFields(Expr expr) {
        final StructuredQuery probe = new StructuredQuery(
                null, null, QueryMode.ROW, false, List.of(new OutputColumn(expr, null)), null, null, null, null);
        return StructuredQueryBuilder.referencedFields(probe);
    }

    /** Pre-order visit of every expression below {@code expr}, lambda bodies included. */
    static void walk(Expr expr, Consumer<Expr> visitor) {
        WindowExpressions.visit(expr, node -> {
            visitor.accept(node);
            if (node instanceof LambdaExpr lambda) {
                walk(lambda.body(), visitor);
            }
        });
    }

    /** {@link #walk} over every expression operand of a filter tree. */
    static void walkFilter(FilterNode node, Consumer<Expr> visitor) {
        WindowExpressions.visitFilter(node, expr -> {
            visitor.accept(expr);
            if (expr instanceof LambdaExpr lambda) {
                walk(lambda.body(), visitor);
            }
        });
    }

    /** The rendered offset and limit of a governed page, or {@code null} for a page the builder will refuse. */
    static long[] governedPage(PageSpec page, int defaultLimit, int maxLimit) {
        if (page == null) {
            return new long[] {0, defaultLimit};
        }
        if (page instanceof OffsetPage offset) {
            if (offset.offset() < 0 || offset.limit() < 0 || offset.limit() > maxLimit) {
                return null;
            }
            return new long[] {offset.offset(), offset.limit() == 0 ? defaultLimit : offset.limit()};
        }
        return null;
    }
}
