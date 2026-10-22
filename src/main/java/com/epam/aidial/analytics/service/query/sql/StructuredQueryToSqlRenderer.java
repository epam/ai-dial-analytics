package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.ArrayValuedFunctions;
import com.epam.aidial.analytics.service.query.FinalDecision;
import com.epam.aidial.analytics.service.query.HigherOrderFunctions;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.QueryFunctionCatalog;
import com.epam.aidial.analytics.service.query.WildcardProjection;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.FrameBound;
import com.epam.aidial.analytics.service.query.model.FrameBoundKind;
import com.epam.aidial.analytics.service.query.model.FrameUnit;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LambdaParamExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.NullsOrder;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.OutputColumnNaming;
import com.epam.aidial.analytics.service.query.model.ParamExpr;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.ScalarSubqueryExpr;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.SortItem;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.SubqueryNode;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.model.WindowFrame;
import com.epam.aidial.analytics.service.query.model.WindowOrder;
import com.epam.aidial.analytics.service.query.translate.ValueExprToObjectMapper;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renders a validated {@link StructuredQuery} as a single SELECT statement in the <em>external</em>
 * SQL dialect — the exact subset the SQL front end ({@link SqlQueryFrontEnd}) accepts. The output
 * references only catalog-level identifiers (the entity name and flat field names, always
 * double-quoted), never physical ClickHouse tables or joins, so the rendered statement round-trips:
 * feeding it back through the front end yields a semantically equivalent query.
 *
 * <p>The caller must validate the query first (via {@code StructuredQueryBuilder}); this renderer
 * only re-checks the few DSL constructs that are executable but <em>inexpressible</em> in the SQL
 * subset and rejects them with {@link QueryValidationException}: contains values containing LIKE
 * metacharacters ({@code %}/{@code _}), null literals outside an {@code eq}/{@code ne} rewrite to
 * {@code IS [NOT] NULL}, {@code include_total} (the SQL surface has no total-count opt-in),
 * {@code having} on a row-mode query (execution ignores it; a rendered HAVING would be rejected by
 * the SQL front end), and {@code distinct} on an aggregate-mode query (execution deduplicates
 * aggregate rows, but the SQL front end rejects DISTINCT combined with GROUP BY as nested
 * aggregation). Every declared field type is SQL-visible, so no source column type makes a bare
 * projection untranslatable. Array and object fields are <em>projection-only</em> in
 * the SQL subset (the SQL front end rejects every other use on translate-back), so any
 * execution-acceptable document that references one in {@code filter}/{@code having}
 * (e.g. {@code eq null}), {@code sort}, {@code group_by}, a function/aggregate argument, or a
 * {@code distinct} projection is rejected here — rendering it would produce SQL outside
 * the accepted subset. Ordered-set aggregates ({@code percentile_cont}/{@code percentile_disc}) render
 * in the standard {@code fn(fraction) WITHIN GROUP (ORDER BY column)} form — the only shape the SQL
 * front end parses. Function names match case-insensitively, exactly as the execute path does, and
 * always render lowercase. A function's {@code distinct} flag renders as the {@code DISTINCT}
 * quantifier only on the aggregates whose execution honors it ({@code count}/{@code sum}/{@code avg});
 * on every other catalog function execution silently ignores the flag, so the renderer drops it —
 * rendering it would be semantically inert yet outside the accepted SQL subset (e.g.
 * {@code lower(DISTINCT x)} is rejected). Literals render from the same parsed values execution binds
 * ({@link ValueExprToObjectMapper}), so whitespace-padded wire values normalize identically on both
 * paths.
 *
 * <p>An absent/empty row-mode {@code select} executes as the wildcard expansion — the entity's
 * non-heavy source-owned columns ({@link com.epam.aidial.analytics.service.query.WildcardProjection}) —
 * which is exactly what SQL {@code *} expands to on translate-back, so it renders as the compact
 * {@code SELECT *} for every entity.
 *
 * <p>Contains operators render with exact semantics per the operator contract: {@code co}/{@code nc}
 * (case-sensitive) as {@code LIKE}/{@code NOT LIKE}, {@code ico}/{@code inc} (case-insensitive) as
 * {@code ILIKE}/{@code NOT ILIKE}. An absent page renders no LIMIT clause — the executing endpoint
 * applies its own default — and a zero offset/limit is likewise omitted.
 */
@Component
@RequiredArgsConstructor
public class StructuredQueryToSqlRenderer {

    private static final DateTimeFormatter TIMESTAMP_SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIMESTAMP_MILLIS = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    /** The aggregates whose {@code DISTINCT} quantifier the execute path honors ({@code ExprTranslator}); the flag is inert on every other catalog function. */
    private static final Set<String> DISTINCT_AGGREGATES = Set.of("count", "sum", "avg");

    /**
     * The accepted lambda parameter spelling, held identically by {@code LambdaScope} on the execute path.
     * Duplicated rather than shared because the two live in packages with a one-directional dependency,
     * and a pattern that drifted would be visible immediately: the round-trip tests render a lambda and
     * read it back.
     */
    private static final Pattern LAMBDA_PARAM_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,63}");

    /** The two clauses {@link #rejectUseInFilter} guards; named because the document carve-out is scoped to one of them. */
    private static final String FILTER_CLAUSE = "'filter'";
    private static final String HAVING_CLAUSE = "'having'";

    /**
     * The catalog arithmetic names and the infix operator each renders as. {@code modulo} renders as
     * {@code %} and never as {@code MOD}: the subset accepts both spellings inbound, but emitting one
     * canonical form per operation is what keeps the round-trip single-valued.
     */
    private static final Map<String, String> ARITHMETIC_OPERATORS = Map.of(
            "plus", "+",
            "minus", "-",
            "multiply", "*",
            "div", "/",
            "modulo", "%");

    /**
     * The two offset functions and the datetime operator each renders as, alongside an {@code INTERVAL}
     * literal. Emitted in the infix form rather than as {@code date_sub(...)} for the reason
     * {@link #ARITHMETIC_OPERATORS} exists: the rendered SQL's audience is external BI clients, for which
     * the operator form is the portable one. Safe because the SQL front end lowers
     * {@code ± INTERVAL n unit} back onto these same two functions, so the round trip is stable.
     */
    private static final Map<String, String> DATE_OFFSET_OPERATORS = Map.of(
            "date_add", "+",
            "date_sub", "-");

    private final ValueExprToObjectMapper valueExprToObjectMapper;

    /** Renders {@code query} (already validated against {@code model}) as external-dialect SQL. */
    public String render(StructuredQuery query, QueryEntityModel model) {
        rejectNonProjectionUse(query, model);
        rejectComputedArrayKey(query);
        final StringBuilder sql = new StringBuilder("SELECT ");
        if (query.distinct()) {
            // Execution accepts aggregate+distinct (deduplicating aggregate rows over an unprojected
            // group key), but the SQL front end rejects DISTINCT combined with GROUP BY as nested
            // aggregation — the combination has no expression in the SQL subset.
            if (query.mode() == QueryMode.AGGREGATE) {
                throw new QueryValidationException(
                        "'distinct' on an aggregate-mode query cannot be expressed in SQL (DISTINCT combined with grouping is outside the SQL subset)");
            }
            sql.append("DISTINCT ");
        }
        sql.append(selectList(query, model));
        sql.append(" FROM ").append(quoteIdentifier(query.entity()));
        // What the query says, never what query.force-final would force: the translation must read the same
        // once the flag is off. An absent `final` over a collapsing source reads final, so it renders FINAL.
        if (FinalDecision.readsFinal(model, query.finalRead(), false)) {
            sql.append(" FINAL");
        }
        if (query.filter() != null) {
            sql.append(" WHERE ").append(filter(query.filter()));
        }
        if (query.mode() == QueryMode.AGGREGATE && query.groupBy() != null && !query.groupBy().isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", query.groupBy().stream().map(StructuredQueryToSqlRenderer::quoteIdentifier).toList()));
        }
        if (query.having() != null) {
            if (query.mode() != QueryMode.AGGREGATE) {
                throw new QueryValidationException(
                        "'having' on a row-mode query cannot be expressed in SQL (execution ignores it); move the condition into 'filter' or use aggregate mode");
            }
            sql.append(" HAVING ").append(filter(query.having()));
        }
        if (query.sort() != null && !query.sort().isEmpty()) {
            sql.append(" ORDER BY ").append(String.join(", ", query.sort().stream().map(StructuredQueryToSqlRenderer::sortItem).toList()));
        }
        appendPage(sql, query);
        return sql.toString();
    }

    // ----------------------------------- projection-only type enforcement

    /**
     * Array and object fields are projection-only in the SQL subset, with one carve-out: an
     * {@code object} field is a legal <em>{@code filter}</em> comparison operand against a character
     * literal, because both surfaces can name the document's canonical JSON text. Outside that shape the
     * dialect has no array or object literal and no comparison over either, and the SQL front end
     * rejects such a field used as a predicate operand, sort or group key, {@code DISTINCT} projection,
     * or function/aggregate argument on translate-back.
     *
     * <p>The execute path accepts a sort or group_by key and a {@code count} argument over one, so
     * rendering those would emit SQL outside the accepted subset and break the round-trip guarantee —
     * each fails closed here, naming the field, its type and the restriction. A comparison operand is no
     * longer among them: {@code FilterTranslator} now applies the same classification on the execute
     * path, so an {@code eq null} filter or an array comparison is refused there too. This guard is the
     * second, independent one, kept for a render that skipped the builder pass.
     */
    private static void rejectNonProjectionUse(StructuredQuery query, QueryEntityModel model) {
        final Map<String, QueryFieldType> restricted = new LinkedHashMap<>();
        for (final QueryFieldBinding binding : model.bindings().values()) {
            if (SqlProjectionOnlyTypes.isProjectionOnly(binding.type())) {
                restricted.put(binding.name(), binding.type());
            }
        }
        if (restricted.isEmpty()) {
            return;
        }
        rejectUseInFilter(query.filter(), restricted, FILTER_CLAUSE);
        rejectUseInFilter(query.having(), restricted, HAVING_CLAUSE);

        // The names under which a bare projection-only field is projected (its resolved output name):
        // a sort or group_by key naming one orders/groups the field itself once translated back.
        final Map<String, String> projected = new LinkedHashMap<>();
        if (query.select() == null || query.select().isEmpty()) {
            // Only what the wildcard actually projects can be a projected projection-only field: a heavy
            // object/array column is left out of the expansion, so it must not trip this guard.
            for (final String name : WildcardProjection.sourceFields(model)) {
                if (restricted.containsKey(name)) {
                    projected.put(name, name);
                }
            }
        } else {
            final List<String> outputNames = OutputColumnNaming.resolve(query.select());
            for (int i = 0; i < query.select().size(); i++) {
                final OutputColumn column = query.select().get(i);
                if (column.expr() instanceof FieldExpr(String name) && restricted.containsKey(name)) {
                    projected.put(outputNames.get(i), name);
                } else {
                    rejectUseInExpr(column.expr(), restricted);
                }
            }
        }
        if (query.distinct() && !projected.isEmpty()) {
            throw projectionOnlyViolation(projected.values().iterator().next(), restricted, "'distinct' over");
        }
        if (query.groupBy() != null) {
            for (final String key : query.groupBy()) {
                final String field = restricted.containsKey(key) ? key : projected.get(key);
                if (field != null) {
                    throw projectionOnlyViolation(field, restricted, "'group_by' on");
                }
            }
        }
        if (query.sort() != null) {
            for (final SortItem item : query.sort()) {
                final String field = restricted.containsKey(item.field()) ? item.field() : projected.get(item.field());
                if (field != null) {
                    throw projectionOnlyViolation(field, restricted, "'sort' on");
                }
            }
        }
    }

    /**
     * Rejects a {@code group_by} or {@code sort} key naming the alias of a projected <b>array-valued
     * call</b> ({@code map_keys}, {@code split_string}, {@code json_extract_array},
     * {@code group_uniq_array}).
     *
     * <p><b>Separate from {@link #rejectNonProjectionUse}, and not a line inside it, for two reasons.</b>
     * That method is keyed on a declared column type, which a computed result does not have — its
     * {@code projected} map only ever holds bare {@link FieldExpr} projections — and it returns early when
     * the model declares no projection-only column at all, which is exactly when a computed array would
     * still slip through. So the hole was widest on entities with no container columns.
     *
     * <p>Without this, {@code group_by} on such an alias rendered as {@code GROUP BY "parts"} — SQL that
     * {@code RelToStructuredQueryTranslator.rejectArrayKeyOrOperand} then refuses on the way back, so
     * {@code POST /v1/queries/translate} emitted a statement {@code POST /v1/queries/translate-sql}
     * rejects. That is the round-trip asymmetry this renderer exists to prevent; the execution path
     * accepts the query either way (ClickHouse groups by whole arrays), which is why nothing failed
     * loudly.
     *
     * <p>Deliberately <b>not</b> applied to a {@code distinct} projection, matching the SQL front end:
     * {@code SELECT DISTINCT json_extract_array(…)} is accepted there, because a distinct projection is
     * not operand use.
     */
    private static void rejectComputedArrayKey(StructuredQuery query) {
        if (query.select() == null || query.select().isEmpty()
                || (isEmpty(query.groupBy()) && query.sort() == null)) {
            return;
        }
        final Map<String, String> arrayOutputs = new LinkedHashMap<>();
        final List<String> outputNames = OutputColumnNaming.resolve(query.select());
        for (int i = 0; i < query.select().size(); i++) {
            if (query.select().get(i).expr() instanceof FnExpr fn
                    && ArrayValuedFunctions.isArrayValued(fn.name())) {
                arrayOutputs.put(outputNames.get(i), fn.name());
            }
        }
        if (arrayOutputs.isEmpty()) {
            return;
        }
        if (query.groupBy() != null) {
            for (final String key : query.groupBy()) {
                rejectComputedArrayKey(arrayOutputs.get(key), key, "'group_by'");
            }
        }
        if (query.sort() != null) {
            for (final SortItem item : query.sort()) {
                rejectComputedArrayKey(arrayOutputs.get(item.field()), item.field(), "'sort'");
            }
        }
    }

    private static void rejectComputedArrayKey(String producer, String key, String clause) {
        if (producer != null) {
            throw new QueryValidationException("'" + producer + "' returns an array and can only be"
                    + " projected; " + clause + " on '" + key + "' cannot be expressed in SQL");
        }
    }

    private static boolean isEmpty(List<String> values) {
        return values == null || values.isEmpty();
    }

    private static void rejectUseInFilter(FilterNode node, Map<String, QueryFieldType> restricted, String clause) {
        switch (node) {
            case null -> { }
            case LogicalNode logical -> {
                if (logical.args() != null) {
                    for (final FilterNode child : logical.args()) {
                        rejectUseInFilter(child, restricted, clause);
                    }
                }
            }
            case ComparisonNode comparison -> {
                if (comparison.args() != null) {
                    // A document comparison over an `object` field renders as the bare quoted identifier
                    // and is accepted by the SQL subset on the way back, so it must not be rejected here.
                    // The carve-out is exactly the shape `<object field> <operator> <literal>`: the type
                    // is read off the *left* operand only, and a projection-only field on the right takes
                    // the query out of it, because the SQL front end blanket-refuses such a right operand
                    // and the rendered SQL would not translate back. An `eq`/`ne` against a null literal
                    // is not a document comparison either: it renders as IS [NOT] NULL, which the front
                    // end still refuses over a projection-only field.
                    // Scoped to 'filter' — a WHERE predicate. In HAVING the bare field is neither grouped
                    // nor aggregated (and GROUP BY on one is itself rejected above), so the rendered
                    // statement would fail the SQL front end's validator with "is not being grouped" and
                    // fail execution with NOT_AN_AGGREGATE. Rendering it would hand out SQL that neither
                    // executes nor translates back, which is precisely what this guard exists to prevent.
                    final List<Expr> args = comparison.args();
                    final boolean document = FILTER_CLAUSE.equals(clause)
                            && args.size() == 2
                            && SqlProjectionOnlyTypes.isComparableAs(
                                    leftOperandType(args, restricted), comparison.op())
                            && isCharacterLiteralOrList(args.get(1));
                    for (final Expr arg : args) {
                        if (arg instanceof FieldExpr(String name) && restricted.containsKey(name) && !document) {
                            throw projectionOnlyViolation(name, restricted, "a " + clause + " comparison on");
                        }
                        rejectUseInExpr(arg, restricted);
                    }
                }
            }
            // Internal to the SQL surface; never reaches this renderer (rejected in filter()).
            case SubqueryNode ignored -> { }
        }
    }

    /**
     * The declared type of the comparison's <b>left</b> operand when it is a bare projection-only field,
     * else {@code null}. Only the left side can carry the document carve-out — the shape the SQL subset
     * admits is {@code <object field> <operator> <literal>} — and only a bare field can carry the
     * restriction at all; a call over one is governed by {@link #rejectUseInExpr} and its function
     * carve-outs instead.
     */
    private static QueryFieldType leftOperandType(List<Expr> args, Map<String, QueryFieldType> restricted) {
        return args.getFirst() instanceof FieldExpr(String name) ? restricted.get(name) : null;
    }

    /**
     * Whether {@code expr} is the right-hand side the document carve-out admits: a character literal, or
     * — for {@code in} — a list of them. Stated positively rather than as a set of exclusions, so the
     * carve-out holds the property its comment claims ({@code <object field> <operator> <literal>}) for
     * every operand shape, including ones no endpoint can currently produce. A null literal fails it, so
     * a null test is not mistaken for a document comparison; so does a field of any type, which keeps
     * {@code "request_tags" = "event_kind"} — SQL the front end refuses — from ever being rendered.
     */
    private static boolean isCharacterLiteralOrList(Expr expr) {
        if (expr instanceof ArrayExpr(List<Expr> items)) {
            if (items == null || items.isEmpty()) {
                return false;
            }
            for (final Expr item : items) {
                if (!isCharacterLiteral(item)) {
                    return false;
                }
            }
            return true;
        }
        return isCharacterLiteral(expr);
    }

    private static boolean isCharacterLiteral(Expr expr) {
        return expr instanceof ValueExpr value && value.valueType() == ValueType.STRING;
    }

    /**
     * Rejects a projection-only field nested anywhere below the given expression (function
     * arguments, in-lists).
     */
    private static void rejectUseInExpr(Expr expr, Map<String, QueryFieldType> restricted) {
        switch (expr) {
            case FnExpr fn -> {
                if (fn.args() != null) {
                    for (int i = 0; i < fn.args().size(); i++) {
                        final Expr arg = fn.args().get(i);
                        // The JSON argument of json_extract_* is the one position where a restricted
                        // field is renderable; SqlProjectionOnlyTypes owns that rule so this direction
                        // and the SQL front end cannot disagree and break the round-trip.
                        if (arg instanceof FieldExpr(String name) && restricted.containsKey(name)
                                && !SqlProjectionOnlyTypes.isAllowedFunctionArgument(
                                        fn.name(), i, restricted.get(name))) {
                            throw projectionOnlyViolation(name, restricted, "a function call over");
                        }
                        rejectUseInExpr(arg, restricted);
                    }
                }
            }
            case ArrayExpr array -> {
                if (array.items() != null) {
                    for (final Expr item : array.items()) {
                        if (item instanceof FieldExpr(String name) && restricted.containsKey(name)) {
                            throw projectionOnlyViolation(name, restricted, "an 'in' list over");
                        }
                        rejectUseInExpr(item, restricted);
                    }
                }
            }
            case CaseExpr caseExpr -> {
                if (caseExpr.when() != null) {
                    for (final CaseBranch branch : caseExpr.when()) {
                        if (branch != null) {
                            // A branch condition is predicate position, so it takes the filter clause's rules.
                            rejectUseInFilter(branch.when(), restricted, FILTER_CLAUSE);
                            rejectBareRestricted(branch.then(), restricted, "a 'case' branch value over");
                            rejectUseInExpr(branch.then(), restricted);
                        }
                    }
                }
                rejectBareRestricted(caseExpr.otherwise(), restricted, "a 'case' branch value over");
                rejectUseInExpr(caseExpr.otherwise(), restricted);
            }
            case WindowExpr window -> {
                rejectUseInExpr(window.fn(), restricted);
                if (window.partitionBy() != null) {
                    for (final Expr key : window.partitionBy()) {
                        rejectBareRestricted(key, restricted, "a window PARTITION BY over");
                        rejectUseInExpr(key, restricted);
                    }
                }
                if (window.orderBy() != null) {
                    for (final WindowOrder order : window.orderBy()) {
                        if (order != null) {
                            rejectBareRestricted(order.expr(), restricted, "a window ORDER BY over");
                            rejectUseInExpr(order.expr(), restricted);
                        }
                    }
                }
            }
            case null, default -> { }
        }
    }

    private static void rejectBareRestricted(Expr expr, Map<String, QueryFieldType> restricted, String use) {
        if (expr instanceof FieldExpr(String name) && restricted.containsKey(name)) {
            throw projectionOnlyViolation(name, restricted, use);
        }
    }

    private static QueryValidationException projectionOnlyViolation(
            String field, Map<String, QueryFieldType> restricted, String use) {
        return new QueryValidationException(SqlProjectionOnlyTypes.message(
                field, restricted.get(field), use, "cannot be expressed in SQL"));
    }

    private String selectList(StructuredQuery query, QueryEntityModel model) {
        final List<OutputColumn> select = query.select();
        if (select == null || select.isEmpty()) {
            return bareSelectList(model);
        }
        final List<String> names = OutputColumnNaming.resolve(select);
        final List<String> rendered = new ArrayList<>(select.size());
        for (int i = 0; i < select.size(); i++) {
            rendered.add(outputColumn(select.get(i), names.get(i)));
        }
        return String.join(", ", rendered);
    }

    /**
     * An absent/empty select executes as the wildcard expansion — the entity's non-heavy source-owned
     * columns ({@link WildcardProjection}) — and SQL {@code *} expands to exactly that same set on
     * translate-back, so it renders as the compact {@code SELECT *} for every entity, enrichments and
     * heavy columns alike. (Before the two wildcards shared one rule, an enriched entity had to be
     * spelled out because {@code *} would have fanned out into enrichment columns.) Every declared field
     * type is SQL-visible, so no source column type can block a bare projection.
     */
    private static String bareSelectList(QueryEntityModel model) {
        if (WildcardProjection.sourceFields(model).isEmpty()) {
            throw new QueryValidationException(WildcardProjection.emptyExpansionMessage(model.entity()));
        }
        return "*";
    }

    /**
     * Renders one output column under its resolved name. The alias is emitted unless the bare
     * expression already carries that name — a plain field reference resolved to its own field name —
     * so the statement declares exactly the columns execution returns (a derived name included) while
     * a simple projection stays free of {@code "col" AS "col"} noise.
     */
    private String outputColumn(OutputColumn column, String name) {
        final String rendered = expr(column.expr());
        if (column.expr() instanceof FieldExpr(String field) && field.equals(name)) {
            return rendered;
        }
        return rendered + " AS " + quoteIdentifier(name);
    }

    // ---------------------------------------------------------------- filters

    private String filter(FilterNode node) {
        return switch (node) {
            case LogicalNode logical -> logical(logical);
            case ComparisonNode comparison -> comparison(comparison);
            case SubqueryNode ignored -> throw new QueryValidationException(
                    "a subquery predicate has no structured-query rendering");
        };
    }

    private String logical(LogicalNode node) {
        final List<FilterNode> args = node.args() == null ? List.of() : node.args();
        return switch (node.op()) {
            case AND, OR -> {
                if (args.isEmpty()) {
                    throw new QueryValidationException("'and'/'or' require at least one child node");
                }
                final String separator = node.op() == LogicalOp.AND ? " AND " : " OR ";
                yield "(" + String.join(separator, args.stream().map(this::filter).toList()) + ")";
            }
            case NOT -> {
                if (args.size() != 1) {
                    throw new QueryValidationException("'not' expects exactly one child node");
                }
                yield "(NOT " + filter(args.getFirst()) + ")";
            }
        };
    }

    private String comparison(ComparisonNode node) {
        final List<Expr> args = node.args() == null ? List.of() : node.args();
        if (args.size() != 2) {
            throw new QueryValidationException("comparison '" + node.op().code() + "' expects exactly two arguments");
        }
        final Expr left = args.get(0);
        final Expr right = args.get(1);
        final ComparisonOp op = node.op();

        if (op == ComparisonOp.IN) {
            return expr(left) + " IN (" + inList(right) + ")";
        }
        if (isNullLiteral(left)) {
            throw new QueryValidationException("a null literal is only expressible in SQL as the right operand of 'eq'/'ne' (IS [NOT] NULL)");
        }
        if (isNullLiteral(right)) {
            return switch (op) {
                case EQ -> expr(left) + " IS NULL";
                case NE -> expr(left) + " IS NOT NULL";
                default -> throw new QueryValidationException("null literal is only valid with 'eq'/'ne', not '" + op.code() + "'");
            };
        }
        return switch (op) {
            case EQ -> expr(left) + " = " + expr(right);
            case NE -> expr(left) + " <> " + expr(right);
            case LT -> expr(left) + " < " + expr(right);
            case GT -> expr(left) + " > " + expr(right);
            case LE -> expr(left) + " <= " + expr(right);
            case GE -> expr(left) + " >= " + expr(right);
            case CO -> expr(left) + " LIKE " + containsPattern(op, right);
            case NC -> expr(left) + " NOT LIKE " + containsPattern(op, right);
            case ICO -> expr(left) + " ILIKE " + containsPattern(op, right);
            case INC -> expr(left) + " NOT ILIKE " + containsPattern(op, right);
            case IN -> throw new IllegalStateException("'in' handled above");
        };
    }

    private String inList(Expr right) {
        if (!(right instanceof ArrayExpr(List<Expr> items)) || items == null || items.isEmpty()) {
            throw new QueryValidationException("'in' requires a non-empty array as its right operand");
        }
        return String.join(", ", items.stream().map(this::expr).toList());
    }

    /**
     * The SQL subset accepts only {@code '%text%'} contains patterns whose text carries no LIKE
     * metacharacters — a contains value containing {@code %} or {@code _} is executable by the DSL
     * (which escapes it) but inexpressible in SQL, so it is rejected rather than mistranslated.
     */
    private static String containsPattern(ComparisonOp op, Expr right) {
        if (!(right instanceof ValueExpr value) || value.value() == null) {
            throw new QueryValidationException("'" + op.code() + "' requires a string literal right operand");
        }
        final String text = value.value();
        if (text.contains("%") || text.contains("_")) {
            throw new QueryValidationException("'" + op.code() + "' value '" + text
                    + "' contains a LIKE metacharacter ('%' or '_') and cannot be expressed in the SQL contains pattern '%text%'");
        }
        return quoteString("%" + text + "%");
    }

    // ------------------------------------------------------------ expressions

    private String expr(Expr expr) {
        return switch (expr) {
            case FieldExpr field -> quoteIdentifier(field.name());
            case ValueExpr value -> literal(value);
            case FnExpr fn -> function(fn);
            case CaseExpr caseExpr -> caseExpression(caseExpr);
            case WindowExpr window -> window(window);
            // A lambda renders in ClickHouse's own shape, `x -> body`, and only where the function that
            // applies it puts it (argument 0); reaching it here from anywhere else is the same error the
            // DSL translator reports, so the two surfaces refuse the same document.
            case LambdaExpr lambda -> lambda(lambda);
            case LambdaParamExpr param -> param.name();
            case ParamExpr param -> throw new QueryValidationException("param expressions are not supported: '" + param.name() + "'");
            case ArrayExpr ignored -> throw new QueryValidationException("array expressions are only valid as the right operand of 'in'");
            case ScalarSubqueryExpr ignored -> throw new QueryValidationException(
                    "a scalar subquery has no structured-query rendering");
        };
    }

    /**
     * {@code CASE WHEN <predicate> THEN <value> […] [ELSE <value>] END}. The conditions render through the
     * filter grammar's rendering — this is the one expression position that admits it — so a condition that
     * is legal in {@code filter} is legal here and reads back to the same node through the SQL front end.
     */
    private String caseExpression(CaseExpr caseExpr) {
        final List<CaseBranch> branches = caseExpr.when() == null ? List.of() : caseExpr.when();
        if (branches.isEmpty()) {
            throw new QueryValidationException("'case' requires at least one 'when' branch");
        }
        final StringBuilder sql = new StringBuilder("CASE");
        for (final CaseBranch branch : branches) {
            if (branch == null || branch.when() == null || branch.then() == null) {
                throw new QueryValidationException("each 'case' branch needs a 'when' condition and a 'then' value");
            }
            sql.append(" WHEN ").append(filter(branch.when())).append(" THEN ").append(branchValue(branch.then()));
        }
        if (caseExpr.otherwise() != null) {
            sql.append(" ELSE ").append(branchValue(caseExpr.otherwise()));
        }
        return sql.append(" END").toString();
    }

    /** A branch value; the one place a null literal renders, as the SQL {@code NULL} the front end reads back. */
    private String branchValue(Expr value) {
        return isNullLiteral(value) ? "NULL" : expr(value);
    }

    /**
     * {@code <fn> OVER (PARTITION BY … ORDER BY … [ROWS|RANGE BETWEEN … AND …])}, each part omitted when
     * absent — the same shape the SQL front end reads back into a {@link WindowExpr}, with the default frame
     * left implicit on both sides.
     */
    private String window(WindowExpr window) {
        if (window.fn() == null || window.fn().name() == null) {
            throw new QueryValidationException("a 'window' expression requires a 'fn' to apply over the window");
        }
        final String name = window.fn().name().toLowerCase(Locale.ROOT);
        final String call = (window.fn().args() == null || window.fn().args().isEmpty()) && !"count".equals(name)
                ? name + "()"
                : function(window.fn());
        final List<String> parts = new ArrayList<>();
        if (window.partitionBy() != null && !window.partitionBy().isEmpty()) {
            parts.add("PARTITION BY " + String.join(", ", window.partitionBy().stream().map(this::expr).toList()));
        }
        if (window.orderBy() != null && !window.orderBy().isEmpty()) {
            final List<String> keys = new ArrayList<>(window.orderBy().size());
            for (final WindowOrder order : window.orderBy()) {
                if (order == null || order.expr() == null) {
                    throw new QueryValidationException("a window 'order_by' entry requires an 'expr'");
                }
                final StringBuilder key = new StringBuilder(expr(order.expr()));
                if (order.dir() != null) {
                    key.append(order.dir() == SortDir.DESC ? " DESC" : " ASC");
                }
                if (order.nulls() != null) {
                    key.append(order.nulls() == NullsOrder.FIRST ? " NULLS FIRST" : " NULLS LAST");
                }
                keys.add(key.toString());
            }
            parts.add("ORDER BY " + String.join(", ", keys));
        }
        if (window.frame() != null) {
            final WindowFrame frame = window.frame();
            if (frame.unit() == null || frame.start() == null || frame.end() == null) {
                throw new QueryValidationException("a window 'frame' requires 'unit', 'start' and 'end'");
            }
            parts.add((frame.unit() == FrameUnit.ROWS ? "ROWS" : "RANGE") + " BETWEEN "
                    + frameBound(frame.start()) + " AND " + frameBound(frame.end()));
        }
        return call + " OVER (" + String.join(" ", parts) + ")";
    }

    private static String frameBound(FrameBound bound) {
        if (bound.kind() == null) {
            throw new QueryValidationException("a window frame bound requires a 'kind'");
        }
        return switch (bound.kind()) {
            case UNBOUNDED_PRECEDING -> "UNBOUNDED PRECEDING";
            case CURRENT_ROW -> "CURRENT ROW";
            case UNBOUNDED_FOLLOWING -> "UNBOUNDED FOLLOWING";
            case PRECEDING, FOLLOWING -> {
                if (bound.offset() == null || bound.offset() < 0) {
                    throw new QueryValidationException(
                            "a '" + bound.kind().code() + "' frame bound requires a non-negative 'offset'");
                }
                yield bound.offset() + (bound.kind() == FrameBoundKind.PRECEDING ? " PRECEDING" : " FOLLOWING");
            }
        };
    }

    private String function(FnExpr fn) {
        // Function names validate case-insensitively on the execute path (ExprTranslator lowercases
        // before matching the closed catalog); mirror that here and render the lowercase name.
        final String name = fn.name() == null ? "" : fn.name().toLowerCase(Locale.ROOT);
        final List<Expr> args = fn.args() == null ? List.of() : fn.args();
        if (args.isEmpty()) {
            // The closed catalog has exactly two nullary functions, and each has its own SQL spelling:
            // count() is written count(*), while now() is written as itself (CURRENT_TIMESTAMP would also
            // parse, but one operation gets one rendering — the round trip has to be single-valued).
            if ("now".equals(name)) {
                return "now()";
            }
            if (!"count".equals(name)) {
                throw new QueryValidationException("function '" + fn.name() + "' requires arguments");
            }
            return "count(*)";
        }
        // An array-valued call nested in another call is rejected here as well as on the execute path: the
        // SQL front end refuses it on translate-back (an array is not an operand of anything in the subset),
        // so rendering it would emit SQL that does not round-trip. The exception is argument 0 of an array
        // accessor, which takes an array by definition — the same positional carve-out the execute path
        // applies, so array_length(map_keys(m)) renders and round-trips.
        for (int i = 0; i < args.size(); i++) {
            ArrayValuedFunctions.rejectAsOperand(args.get(i), name, i);
        }
        // Arithmetic renders as the infix operator rather than the catalog name: the rendered SQL's audience
        // is external BI clients, for which the operator form is the portable one. Safe because the SQL front
        // end lowers each operator back onto the same catalog function, so div -> / -> div is stable. Only %
        // is emitted for modulo, never MOD, so one operation has one rendering and the round-trip stays
        // single-valued.
        final String operator = ARITHMETIC_OPERATORS.get(name);
        if (operator != null) {
            if (args.size() != 2) {
                throw new QueryValidationException("'" + name + "' expects exactly two arguments");
            }
            // Parenthesised unconditionally rather than from a precedence table: the parentheses are
            // redundant at the top level and load-bearing when nested, Calcite discards the redundant ones
            // on the way back, and there is no precedence bug to get wrong.
            return "(" + expr(args.get(0)) + " " + operator + " " + expr(args.get(1)) + ")";
        }
        // The offset functions render as the infix INTERVAL form, for the reason arithmetic renders as an
        // operator. The unit and amount are validated here rather than trusted: this renderer must not be
        // able to emit a statement its own front end would refuse, and a document reaching it unvalidated
        // (a stored saved-query body, a translate-only call) would otherwise produce one.
        final String offsetOperator = DATE_OFFSET_OPERATORS.get(name);
        if (offsetOperator != null) {
            if (args.size() != 3) {
                throw new QueryValidationException(
                        "'" + name + "' expects exactly three arguments (unit, amount, timestamp)");
            }
            return "(" + expr(args.get(2)) + " " + offsetOperator
                    + " INTERVAL " + offsetAmount(name, args.get(1)) + " " + offsetUnit(name, args.get(0)) + ")";
        }
        // Ordered-set aggregates: the DSL packs (fraction, column) into the argument list, but the
        // SQL subset only parses the standard WITHIN GROUP form.
        if ("percentile_cont".equals(name) || "percentile_disc".equals(name)) {
            if (args.size() != 2) {
                throw new QueryValidationException("'" + name + "' expects exactly two arguments: fraction and column");
            }
            return name + "(" + expr(args.get(0)) + ") WITHIN GROUP (ORDER BY " + expr(args.get(1)) + ")";
        }
        final String rendered = String.join(", ", args.stream().map(this::argument).toList());
        // The ClickHouse-named functions keep their camelCase: lowercasing is right for the catalog's own
        // snake_case names (and is what the execute path matches on), but `arrayexists` is not how the
        // catalog advertises the function, and the rendered statement is what a caller reads back.
        final String canonical = canonicalName(name);
        if (canonical != null) {
            return canonical + "(" + rendered + ")";
        }
        // The DISTINCT quantifier renders only where the execute path honors it (ExprTranslator:
        // count/sum/avg). On every other catalog function execution silently ignores the flag, so
        // rendering it would change nothing semantically while producing SQL outside the accepted
        // subset (e.g. lower(DISTINCT x) is rejected by the SQL front end) — drop it instead.
        final boolean renderDistinct = fn.distinct() && DISTINCT_AGGREGATES.contains(name);
        return name + "(" + (renderDistinct ? "DISTINCT " : "") + rendered + ")";
    }

    /**
     * The ClickHouse camelCase spelling of {@code name} when it is one of the functions that carries one,
     * else {@code null}. Two families do: the higher-order calls and the flattening aggregates, both of
     * which are new enough that their ClickHouse name is their catalog name.
     */
    private static String canonicalName(String name) {
        final String higherOrder = HigherOrderFunctions.canonical(name);
        return higherOrder != null ? higherOrder : ArrayValuedFunctions.canonicalFlattening(name);
    }

    /**
     * {@code x -> <body>} — ClickHouse's lambda, rendered in the argument position the higher-order call
     * puts it in. The parameter name is the caller's own, which survives the round-trip because the SQL
     * front end carries it through validation rather than canonicalizing it.
     *
     * <p>The name is checked against {@link #LAMBDA_PARAM_NAME} here <b>as well as</b> in the translator,
     * and it is the one guard in this class that is not merely a second opinion: a parameter is the only
     * caller-supplied token that reaches a rendered statement <em>unquoted</em>, because ClickHouse does
     * not accept a quoted identifier in that position. Every caller of this renderer builds the query
     * first, so the translator has already refused a bad name — but "already refused upstream" is not a
     * property this class can check, and the cost of holding it independently is one match.
     */
    private String lambda(LambdaExpr lambda) {
        final List<String> params = lambda.params() == null ? List.of() : lambda.params();
        if (params.size() != 1 || params.getFirst() == null) {
            throw new QueryValidationException("a lambda takes exactly one parameter");
        }
        final String param = params.getFirst();
        if (!LAMBDA_PARAM_NAME.matcher(param).matches()) {
            throw new QueryValidationException("lambda parameter '" + param + "' is not a valid name;"
                    + " it must match " + LAMBDA_PARAM_NAME.pattern());
        }
        if (lambda.body() == null) {
            throw new QueryValidationException("a lambda requires a body");
        }
        return param + " -> " + expr(lambda.body());
    }

    /**
     * One function argument. Identical to {@link #expr} except that an {@link ArrayExpr} renders as a SQL
     * {@code ARRAY[...]} constructor rather than being refused: {@code array_has_any}/{@code array_has_all}
     * carry their value list in that shape, and the SQL front end reads the constructor back into the same
     * {@code ArrayExpr}. Everywhere else an array expression stays inexpressible, which is why this is a
     * separate method and not a widening of {@code expr}.
     */
    private String argument(Expr arg) {
        if (arg instanceof ArrayExpr(List<Expr> arrayItems)) {
            final List<Expr> items = arrayItems == null ? List.of() : arrayItems;
            if (items.isEmpty()) {
                throw new QueryValidationException("an empty value list is not expressible in SQL");
            }
            return "ARRAY[" + String.join(", ", items.stream().map(this::expr).toList()) + "]";
        }
        return expr(arg);
    }

    /**
     * Renders a literal from the <em>parsed</em> value — the same {@link ValueExprToObjectMapper}
     * parse the execute path binds — never from the raw wire text, so trimming/normalization (and
     * rejection of malformed literals) is identical on both paths. Rendering the raw text diverged:
     * a whitespace-padded boolean silently rendered the inverted value.
     */
    private String literal(ValueExpr value) {
        final Object parsed = valueExprToObjectMapper.map(value);
        return switch (value.valueType()) {
            case STRING, UUID -> quoteString((String) parsed);
            case INTEGER, LONG -> parsed.toString();
            case DECIMAL -> ((BigDecimal) parsed).toPlainString();
            case BOOLEAN -> Boolean.TRUE.equals(parsed) ? "TRUE" : "FALSE";
            case DATE -> "DATE " + quoteString(parsed.toString());
            case TIMESTAMP -> "TIMESTAMP " + quoteString(timestampText((Long) parsed));
            case NULL -> throw new QueryValidationException("a null literal is only expressible in SQL as the right operand of 'eq'/'ne' (IS [NOT] NULL)");
        };
    }

    /**
     * The {@code INTERVAL} keyword for an offset call's unit argument — uppercased and bare, not a quoted
     * string, which is what separates it from every other literal this renderer emits.
     *
     * <p>Validated against the advertised unit set rather than passed through, then uppercased — for these
     * six units the {@code INTERVAL} keyword <em>is</em> the DSL unit in upper case, which is why no second
     * mapping table lives here. ({@code ExprTranslator.DATE_OFFSET_UNITS} is the execute path's own map, and
     * the parity test binds it to this same advertised list, so the two cannot drift.) Two things follow
     * from validating first: the keyword reaching the SQL text is one of six known values, so there is no
     * injection surface even though it is concatenated; and a document carrying {@code 'week'} — which
     * {@code date_trunc} accepts and this function does not — is refused here rather than rendered into a
     * statement Calcite would lower back to a different document.
     */
    private String offsetUnit(String name, Expr arg) {
        if (!(arg instanceof ValueExpr value) || value.valueType() != ValueType.STRING
                || !(valueExprToObjectMapper.map(value) instanceof String unit)) {
            throw new QueryValidationException("'" + name + "' requires a string literal unit as its first argument");
        }
        final String canonical = unit.trim().toLowerCase(Locale.ROOT);
        if (!QueryFunctionCatalog.DATE_OFFSET_ALLOWED_UNITS.contains(canonical)) {
            throw new QueryValidationException("'" + name + "' unit must be one of "
                    + QueryFunctionCatalog.DATE_OFFSET_ALLOWED_UNITS + ", got '" + unit + "'");
        }
        return canonical.toUpperCase(Locale.ROOT);
    }

    /**
     * The magnitude for an offset call's amount argument: a positive integer literal, inlined as a validated
     * {@code int}. Negative and zero are refused for the reason the translator refuses them — direction is
     * carried by the function name, so {@code + INTERVAL -2 HOUR} would be a second rendering of
     * {@code date_sub}, and the round trip has to be single-valued.
     */
    private int offsetAmount(String name, Expr arg) {
        if (!(arg instanceof ValueExpr value)
                || !(valueExprToObjectMapper.map(value) instanceof Integer amount)) {
            throw new QueryValidationException("'" + name + "' requires an integer literal amount as its second argument");
        }
        if (amount <= 0) {
            throw new QueryValidationException("'" + name + "' amount must be a positive integer");
        }
        return amount;
    }

    /** DSL timestamps are epoch-milliseconds; the SQL literal form is a UTC wall-clock timestamp. */
    private static String timestampText(long epochMillis) {
        final Instant instant = Instant.ofEpochMilli(epochMillis);
        final DateTimeFormatter format = instant.getNano() == 0 ? TIMESTAMP_SECONDS : TIMESTAMP_MILLIS;
        return format.format(instant);
    }

    // ------------------------------------------------------------------ atoms

    private void appendPage(StringBuilder sql, StructuredQuery query) {
        // Cursor paging never reaches rendering: the builder rejects it during the validation pass.
        if (!(query.page() instanceof OffsetPage(long offset, int limit, boolean includeTotal))) {
            return;
        }
        if (includeTotal) {
            throw new QueryValidationException("'include_total' cannot be expressed in SQL — the SQL surface has no total-count opt-in");
        }
        if (limit > 0) {
            sql.append(" LIMIT ").append(limit);
        }
        if (offset > 0) {
            sql.append(" OFFSET ").append(offset);
        }
    }

    private static String sortItem(SortItem item) {
        final StringBuilder rendered = new StringBuilder(quoteIdentifier(item.field()));
        if (item.dir() != null) {
            rendered.append(switch (item.dir()) {
                case ASC -> " ASC";
                case DESC -> " DESC";
            });
        }
        if (item.nulls() != null) {
            rendered.append(switch (item.nulls()) {
                case FIRST -> " NULLS FIRST";
                case LAST -> " NULLS LAST";
            });
        }
        return rendered.toString();
    }

    private static boolean isNullLiteral(Expr expr) {
        return expr instanceof ValueExpr value && value.valueType() == ValueType.NULL;
    }

    private static String quoteIdentifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static String quoteString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
