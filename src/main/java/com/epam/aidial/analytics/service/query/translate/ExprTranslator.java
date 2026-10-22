package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.ArrayValuedFunctions;
import com.epam.aidial.analytics.service.query.HigherOrderFunctions;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.JsonExtractFunctions;
import com.epam.aidial.analytics.service.query.ObjectPathUsage;
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
import com.epam.aidial.analytics.service.query.model.FrameBound;
import com.epam.aidial.analytics.service.query.model.FrameBoundKind;
import com.epam.aidial.analytics.service.query.model.FrameUnit;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LambdaParamExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.ParamExpr;
import com.epam.aidial.analytics.service.query.model.ScalarSubqueryExpr;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.SubqueryNode;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.model.WindowFrame;
import com.epam.aidial.analytics.service.query.model.WindowOrder;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.jooq.CaseConditionStep;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.QueryPart;
import org.jooq.Select;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Translates a single {@link Expr} into a jOOQ {@link Field} for use in projections, comparison
 * operands, and aggregate arguments. The grammar permits arbitrary expressions, but this translator
 * covers the common, safely-supported subset:
 *
 * <ul>
 *   <li>{@link FieldExpr} → the bound column (unknown names are rejected — the field allowlist is
 *       the schema the client discovered);
 *   <li>{@link ValueExpr} → a typed bind value via {@link ValueExprToObjectMapper};
 *   <li>{@link FnExpr} → a small allowlist of scalar/aggregate SQL functions;
 *   <li>{@link ParamExpr} → rejected (no server-side param registry);
 *   <li>{@link ArrayExpr} → rejected here (only meaningful as the right operand of {@code in},
 *       handled by {@link FilterTranslator}).
 * </ul>
 *
 * <p>Field resolution is against the bindings map only — ClickHouse {@code dial_usage_log} is a flat
 * table, so there are no nested/JSONB path fields to resolve.
 */
@Component
public class ExprTranslator {

    /**
     * Closed allowlist of {@code date_bin} units → their ClickHouse {@code INTERVAL} keyword. Only
     * fixed-width units are supported: variable-width calendar units (month, quarter, year) are
     * excluded by design, mirroring Postgres {@code date_bin} (calendar truncation is {@code date_trunc}
     * territory). The key set must equal {@code QueryFunctionCatalog.DATE_BIN_ALLOWED_UNITS} (the
     * units advertised by {@code GET /v1/queries/functions}); the parity test asserts it.
     */
    static final Map<String, String> DATE_BIN_UNITS = Map.of(
            "second", "SECOND",
            "minute", "MINUTE",
            "hour", "HOUR",
            "day", "DAY",
            "week", "WEEK");

    /**
     * Closed allowlist of {@code date_trunc} units → their ClickHouse {@code INTERVAL} keyword. This is
     * {@link #DATE_BIN_UNITS} plus the calendar units, which is the PostgreSQL shape: a caller should not
     * have to learn which units live behind which function, so {@code date_trunc('day', …)} — the call a
     * Postgres-fluent client or a BI tool writes first — resolves rather than erroring.
     *
     * <p>The overlap is not ambiguity. On a shared unit both functions render the identical
     * {@code toStartOfInterval(col, INTERVAL 1 <unit>, 'UTC')} and agree by construction; what separates
     * them is that {@code date_bin} carries an {@code amount} multiplier and this does not. A period of
     * <em>n</em> calendar months has no fixed width, so there is no epoch grid for a multiplier to step
     * along — and {@code 3} would silently differ from {@code quarter}, which is calendar-quarter-aligned.
     *
     * <p>The key set must equal {@code QueryFunctionCatalog.DATE_TRUNC_ALLOWED_UNITS}; the parity test
     * asserts it.
     */
    static final Map<String, String> DATE_TRUNC_UNITS = Map.of(
            "second", "SECOND",
            "minute", "MINUTE",
            "hour", "HOUR",
            "day", "DAY",
            "week", "WEEK",
            "month", "MONTH",
            "quarter", "QUARTER",
            "year", "YEAR");

    /**
     * Closed allowlist of {@code date_add}/{@code date_sub} units → their ClickHouse {@code INTERVAL}
     * keyword. These are the six base units Calcite's interval {@code SqlTypeName}s carry, which is what lets
     * the SQL surface's {@code ± INTERVAL n unit} lower onto exactly this set.
     *
     * <p><b>Narrower than {@link #DATE_TRUNC_UNITS}, and deliberately so</b>: {@code week} and
     * {@code quarter} are absent. Truncation needs them because they name a calendar <em>alignment</em>
     * nothing else expresses — Monday, the quarter's first day — but as an <em>offset</em> a week is exactly
     * seven days and a quarter exactly three months, so they would be a second spelling of a call that
     * already exists. Worse, they would break the round trip: {@code date_sub('week', 1, …)} renders
     * {@code - INTERVAL 1 WEEK}, which Calcite lowers back to an {@code INTERVAL_DAY} of 604800000 ms, so the
     * document that came back would differ from the one that went in. The SQL surface still accepts
     * {@code WEEK}/{@code QUARTER} and normalizes them here, which costs a client nothing.
     *
     * <p>{@code second} through {@code day} are fixed-width; {@code month} and {@code year} are calendar
     * steps, which ClickHouse evaluates against the operand's own time zone — the reason
     * {@link #dateBin}'s {@code 'UTC'} argument matters applies to these too, and is why {@link #now}
     * renders a UTC-typed value rather than a server-zone one.
     */
    static final Map<String, String> DATE_OFFSET_UNITS = Map.of(
            "second", "SECOND",
            "minute", "MINUTE",
            "hour", "HOUR",
            "day", "DAY",
            "month", "MONTH",
            "year", "YEAR");

    /** The case-insensitive extractor's catalog (and wire) name — the one non-variadic member of the family. */
    private static final String CASE_INSENSITIVE_EXTRACT = JsonExtractFunctions.STRING_CI;

    /**
     * The numeric branch types an {@code if} may widen across, <b>widest first</b> — the order
     * {@link #branchType} walks to pick a mixed-numeric call's result type. Exactly the catalog's numeric
     * family, in the catalog's own vocabulary, so the widening an {@code if} performs and the widening
     * {@code EnrichmentTypeMapping} accepts for an output binding are the same three types.
     */
    private static final QueryFieldType[] NUMERIC_WIDTH_ORDER = {
        QueryFieldType.DECIMAL, QueryFieldType.LONG, QueryFieldType.INTEGER};

    /**
     * The dynamic types a JSON member holding an <em>integer</em> number can take, as
     * {@code dynamicType} spells them — the one non-boolean group {@code JSONExtractBool} reads as
     * {@code value != 0}. A member of any other type (a fractional number, a string, an array, a nested
     * document, an absent path) reads as {@code false}, so nothing else needs a numeric test. Used only
     * by {@link #subcolumnBool(Field)}.
     */
    private static final String INTEGER_DYNAMIC_TYPES =
            "'Int8', 'Int16', 'Int32', 'Int64', 'Int128', 'Int256', "
                    + "'UInt8', 'UInt16', 'UInt32', 'UInt64', 'UInt128', 'UInt256'";

    /** The window-only ranking functions: legal as the function of a {@link WindowExpr} and nowhere else. */
    static final Set<String> RANKING_FUNCTIONS = Set.of("row_number", "rank", "dense_rank");

    /** The aggregates a window may apply; every other catalog function is refused under {@code OVER}. */
    static final Set<String> WINDOW_AGGREGATES = Set.of("count", "sum", "avg", "min", "max");

    private final ValueExprToObjectMapper valueExprToObjectMapper;
    private final SubqueryRenderer subqueries;
    private final Map<Expr, Field<?>> pushed;

    @Autowired
    public ExprTranslator(ValueExprToObjectMapper valueExprToObjectMapper) {
        this(valueExprToObjectMapper, SubqueryRenderer.none(), Map.of());
    }

    private ExprTranslator(
            ValueExprToObjectMapper valueExprToObjectMapper, SubqueryRenderer subqueries, Map<Expr, Field<?>> pushed) {
        this.valueExprToObjectMapper = valueExprToObjectMapper;
        this.subqueries = subqueries;
        this.pushed = pushed;
    }

    /**
     * A translator that renders {@link ScalarSubqueryExpr} through {@code subqueries}. Only the composite
     * SQL renderer asks for one; the shared bean refuses every subquery, so the DSL surface never reaches
     * this path.
     */
    public ExprTranslator withSubqueryRenderer(SubqueryRenderer subqueries) {
        return new ExprTranslator(valueExprToObjectMapper, subqueries, pushed);
    }

    /**
     * A translator for one statement that renders each expression in {@code calls} — matched by value, so every
     * occurrence of an equal call — as the given field instead of translating it. The read path's call pushdown
     * computes those calls inside an enrichment's derived table and reads the result back through this. Only
     * rendering is substituted: every type rule and output name is still decided on the expression as written,
     * by the callers that consult it directly.
     */
    ExprTranslator withPushedCalls(Map<? extends Expr, ? extends Field<?>> calls) {
        return new ExprTranslator(valueExprToObjectMapper, subqueries, Map.copyOf(calls));
    }

    /** The renderer this translator hands nested subqueries to. */
    SubqueryRenderer subqueryRenderer() {
        return subqueries;
    }

    /** Resolves a {@link FieldExpr} to its bound column, rejecting unknown field names. */
    public Field<?> resolveField(FieldExpr expr, Map<String, QueryFieldBinding> bindings) {
        return resolveField(expr.name(), bindings);
    }

    /** Resolves a field name to its bound jOOQ {@link Field}, rejecting unknown names. */
    public Field<?> resolveField(String name, Map<String, QueryFieldBinding> bindings) {
        final Field<?> field = resolveFieldOrNull(name, bindings);
        if (field == null) {
            throw new QueryValidationException("unknown field '" + name + "'");
        }
        return field;
    }

    /**
     * Like {@link #resolveField(String, Map)} but returns {@code null} for an unknown name instead of
     * throwing, so callers (e.g. group-key resolution) can fall back to other sources before failing.
     */
    public Field<?> resolveFieldOrNull(String name, Map<String, QueryFieldBinding> bindings) {
        final QueryFieldBinding binding = bindings.get(name);
        return binding != null ? binding.field() : null;
    }

    /** Translates any non-array expression into a jOOQ {@link Field}. */
    public Field<?> toField(Expr expr, Map<String, QueryFieldBinding> bindings) {
        if (expr == null) {
            throw new QueryValidationException("missing expression; a select/filter entry has no 'expr'");
        }
        final Field<?> substituted = pushed.isEmpty() ? null : pushed.get(expr);
        if (substituted != null) {
            return substituted;
        }
        return switch (expr) {
            case FieldExpr field -> resolveField(field, bindings);
            case ValueExpr value -> toValueField(value);
            case FnExpr fn -> toFunction(fn, bindings);
            case CaseExpr caseExpr -> conditionalCase(caseExpr, bindings);
            case WindowExpr window -> window(window, bindings);
            case ScalarSubqueryExpr scalar -> scalarSubquery(scalar);
            case LambdaParamExpr param -> LambdaScope.resolve(param, bindings);
            case LambdaExpr ignored -> throw new QueryValidationException(
                    "a lambda is only valid as the first argument of a higher-order array function ("
                            + HigherOrderFunctions.names() + ")");
            case ParamExpr param -> throw new QueryValidationException(
                    "param expressions are not supported by the query translator: '" + param.name() + "'");
            case ArrayExpr ignored -> throw new QueryValidationException(
                    "array expressions are only valid as the right operand of 'in'");
        };
    }

    /**
     * The closed function allowlist — the {@code default} branch rejects anything unregistered.
     * Every branch here is advertised to clients by {@code service.query.QueryFunctionCatalog}
     * ({@code GET /v1/queries/functions}); when adding a branch, add its catalog descriptor and the
     * structured-query spec table row in the same change (parity is test-enforced one way: every
     * advertised descriptor must be accepted here).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Field<?> toFunction(FnExpr fn, Map<String, QueryFieldBinding> bindings) {
        final String name = fn.name() == null ? "" : fn.name().toLowerCase(Locale.ROOT);
        final List<Expr> args = fn.args() == null ? List.of() : fn.args();
        // The ClickHouse-named families branch here rather than in the switch below: a case label must be a
        // compile-time constant, and `name` has been lowercased, so a camelCase catalog name cannot be one.
        // Resolving them by the same case-insensitive match every other name match uses keeps the two
        // spellings of that rule together.
        if (HigherOrderFunctions.isHigherOrder(name)) {
            return higherOrder(HigherOrderFunctions.canonical(name), args, bindings);
        }
        if (ArrayValuedFunctions.isFlatteningAggregate(name)) {
            final String canonical = ArrayValuedFunctions.canonicalFlattening(name);
            ArrayValuedFunctions.rejectDistinct(canonical, fn.distinct());
            requireArity(canonical, args, 1);
            final Expr operand = args.getFirst();
            // The per-argument operand rule runs here too. The early return used to skip the loop below,
            // which left argument 0 of these two the one array carve-out nothing checked: an array-valued
            // aggregate typed as an array, so it passed arrayArgument and rendered
            // groupUniqArrayArray(groupUniqArray(x)) — a nested aggregate ClickHouse refuses, reaching the
            // caller as a 503 where the SQL surface answers 400 at Calcite validation.
            rejectAggregateOperand(canonical, operand);
            ArrayValuedFunctions.rejectAsOperand(operand, canonical, 0);
            return flatteningAggregate(canonical, arrayArgument(canonical, operand, bindings));
        }
        // Almost every branch below takes scalar operands, and an array result nested in one is not
        // expressible in the DSL — so it fails here rather than answering a question nobody asked. The one
        // exception is argument 0 of an array-operand function, which takes an array by definition; the
        // rejection is therefore per-argument rather than per-call, and the carve-out's position lives in
        // ArrayValuedFunctions beside the array-valued names themselves.
        for (int i = 0; i < args.size(); i++) {
            ArrayValuedFunctions.rejectAsOperand(args.get(i), name, i);
        }
        return switch (name) {
            case "lower" -> DSL.lower(requireSingleTextArg(name, args, bindings));
            case "upper" -> DSL.upper(requireSingleTextArg(name, args, bindings));
            case "length" -> DSL.length(requireSingleTextArg(name, args, bindings));
            case "trim" -> DSL.trim(requireSingleTextArg(name, args, bindings));
            case "abs" -> DSL.abs((Field) requireSingleArg(name, args, bindings));
            case "plus" -> arithmetic(name, args, bindings, "({0} + {1})");
            case "minus" -> arithmetic(name, args, bindings, "({0} - {1})");
            case "multiply" -> arithmetic(name, args, bindings, "({0} * {1})");
            // The divisor guard is the whole reason these two are not rendered as bare operators: see
            // guardedDivision.
            case "div" -> guardedDivision(name, args, bindings, "divide");
            case "modulo" -> guardedDivision(name, args, bindings, "modulo");
            case "round" -> rounding(name, args, bindings);
            case "floor" -> singleNumeric(name, args, bindings, "floor({0})");
            case "ceil" -> singleNumeric(name, args, bindings, "ceil({0})");
            case "map_value" -> mapValue(name, args, bindings);
            case "map_contains" -> mapContains(name, args, bindings);
            case ArrayValuedFunctions.MAP_KEYS -> mapKeys(name, args, bindings);
            case "array_has" -> arrayHas(name, args, bindings);
            case "array_has_any" -> arrayHasMany(name, args, bindings, "hasAny");
            case "array_has_all" -> arrayHasMany(name, args, bindings, "hasAll");
            case "array_length" -> arrayLength(name, args, bindings);
            case ArrayValuedFunctions.ARRAY_JOIN -> arrayJoin(name, args, bindings);
            case ArrayValuedFunctions.ARRAY_TO_STRING -> arrayToString(name, args, bindings);
            case ArrayValuedFunctions.ARRAY_SLICE -> arraySlice(name, args, bindings);
            case "to_string" -> {
                requireArity(name, args, 1);
                yield asText(name, args.getFirst(), bindings, "operand");
            }
            case "concat" -> concat(name, args, bindings);
            case ArrayValuedFunctions.SPLIT_STRING -> splitString(name, args, bindings);
            case "width_bucket" -> widthBucket(name, args, bindings);
            case "date_bin" -> dateBin(name, args, bindings);
            case "date_trunc" -> dateTrunc(name, args, bindings);
            case "now" -> now(name, args);
            case "date_add" -> dateOffset(name, args, bindings, "+");
            case "date_sub" -> dateOffset(name, args, bindings, "-");
            case "json_extract_string", "json_extract_long", "json_extract_double", "json_extract_bool",
                 "json_extract_string_ci", ArrayValuedFunctions.JSON_EXTRACT_ARRAY ->
                    jsonExtract(name, args, bindings);
            case "if" -> conditional(name, args, bindings);
            case "not_empty" -> {
                final Field<?> operand = requireSingleArg(name, args, bindings);
                requireText(name, args.getFirst(), bindings, "operand");
                yield DSL.field("toBool(notEmpty(ifNull({0}, '')))", Boolean.class, operand);
            }
            case "starts_with" -> textPredicate(name, args, bindings, "startsWith({0}, {1})", "prefix");
            case "match" -> textPredicate(name, args, bindings, "match({0}, {1})", "pattern");
            case "contains" -> textPredicate(name, args, bindings, "position({0}, {1}) > 0", "substring");
            case "equals" -> equality(name, args, bindings);
            // The single-operand aggregates delegate to toAggregate, which is also what the aggregate
            // pipeline calls when it has built the operand itself (a CASE, for a measure carrying a
            // `where`). One renderer per function, reached two ways — the quantifier included, so it
            // cannot come to mean one thing here and another on the pipeline path.
            case "count" -> {
                if (args.isEmpty()) {
                    yield DSL.count();
                }
                yield toAggregate(name, requireSingleArg(name, args, bindings), fn.distinct());
            }
            case "sum", "avg", "min", "max", ArrayValuedFunctions.GROUP_UNIQ_ARRAY ->
                    toAggregate(name, requireSingleArg(name, args, bindings), fn.distinct());
            // Not routed through toAggregate: that method takes one prepared operand and cannot carry a
            // second, which is exactly the case its javadoc anticipates. Its quantifier handling is
            // therefore out of reach here too, so the rejection below is this branch's own.
            case "arg_max" -> argMax(name, args, bindings, fn.distinct());
            case "percentile_cont" -> percentile(name, args, bindings, true);
            case "percentile_disc" -> percentile(name, args, bindings, false);
            // The catalog advertises the ranking functions, so "unsupported function" would contradict the
            // discovery surface this same service publishes. They are supported — only not here: the arm
            // names the position they are legal in, which is also the fix.
            default -> {
                if (RANKING_FUNCTIONS.contains(name)) {
                    throw new QueryValidationException("function '" + fn.name() + "' is a window-only function"
                            + " and requires a window (an 'OVER' clause): wrap the call in a 'window' expression");
                }
                throw new QueryValidationException(
                        "unsupported function '" + fn.name() + "' in query expression");
            }
        };
    }

    /**
     * Applies a single-operand catalog aggregate to an operand the caller has already built, bypassing the
     * {@link Expr} grammar.
     *
     * <p>This exists for the aggregate pipeline, whose measures may carry a {@code where}: the operand it
     * needs is {@code CASE WHEN <predicate> THEN <column> END}, and the {@code Expr} grammar has no
     * {@code CASE} — deliberately, since a conditional value is not something the query DSL offers callers.
     * Rather than widen the grammar for one internal caller, or let the pipeline assemble
     * {@code sumIf}/{@code arraySort(groupUniqArray(…))} itself and drift from what {@link #toFunction}
     * renders, both paths meet here. {@code toFunction} delegates every single-operand branch to this
     * method, so there is exactly one rendering per function.
     *
     * <p><b>{@code distinct} is a parameter because both callers now set it.</b> A pipeline measure may
     * declare the quantifier, and the operand it needs may be the {@code CASE} above, so the two compose:
     * {@code count(DISTINCT CASE WHEN … THEN col END)}. That composition needs no special case, because
     * {@code count(DISTINCT x)} counts distinct <em>non-NULL</em> values — a non-matching row contributes
     * the {@code CASE}'s implicit NULL and is ignored, exactly as it is in the non-distinct form.
     *
     * <p>Each function owns what the flag means for it, which is why this is the only place that decides:
     * {@code count}/{@code sum}/{@code avg} render the quantifier, {@code group_uniq_array} refuses it (its
     * result is already the group's distinct values), and {@code min}/{@code max} ignore it — a minimum over
     * deduplicated values is the same minimum, so there is nothing to render and nothing to refuse. The
     * catalog marks the latter three {@code distinct_supported: false}, and {@code AggregateSpecValidator}
     * rejects a measure declaring it for any of them before a declaration is ever stored; the query path is
     * deliberately left as it is, since tightening it would change shipped query behaviour under a change
     * scoped to the pipeline declaration.
     *
     * @throws QueryValidationException if {@code fn} is not a single-operand aggregate — the ordered-set
     *     aggregates need a {@code WITHIN GROUP} clause this shape cannot carry, and any future aggregate
     *     taking a second operand could not be given one through this signature either — or if
     *     {@code distinct} is set for {@code group_uniq_array}
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Field<?> toAggregate(String fn, Field<?> operand, boolean distinct) {
        final String name = fn == null ? "" : fn.toLowerCase(Locale.ROOT);
        final Field arg = operand;
        if (ArrayValuedFunctions.isFlatteningAggregate(name)) {
            final String canonical = ArrayValuedFunctions.canonicalFlattening(name);
            ArrayValuedFunctions.rejectDistinct(canonical, distinct);
            return flatteningAggregate(canonical, operand);
        }
        return switch (name) {
            case "count" -> distinct ? DSL.countDistinct(arg) : DSL.count(arg);
            case "sum" -> distinct ? DSL.sumDistinct(arg) : DSL.sum(arg);
            case "avg" -> distinct ? DSL.avgDistinct(arg) : DSL.avg(arg);
            case "min" -> DSL.min(arg);
            case "max" -> DSL.max(arg);
            case ArrayValuedFunctions.GROUP_UNIQ_ARRAY -> {
                ArrayValuedFunctions.rejectDistinct(ArrayValuedFunctions.GROUP_UNIQ_ARRAY, distinct);
                yield groupUniqArray(operand);
            }

            default -> throw new QueryValidationException("function '" + fn + "' is not a single-operand "
                    + "aggregate and cannot be applied to a prepared operand");
        };
    }

    /**
     * Translates a literal to a jOOQ field. {@code timestamp} literals are epoch-milliseconds (the DSL
     * convention), but the ClickHouse columns are {@code DateTime64(3)} — a bare integer would not
     * compare correctly against them. Wrapping the literal in {@code fromUnixTimestamp64Milli(...)}
     * produces a matching {@code DateTime64(3)} so the comparison is by instant. Other types inline as-is.
     */
    private Field<?> toValueField(ValueExpr value) {
        final Object mapped = valueExprToObjectMapper.map(value);
        if (value.valueType() == ValueType.TIMESTAMP && mapped instanceof Long millis) {
            return DSL.function("fromUnixTimestamp64Milli", Object.class, DSL.val(millis));
        }
        return DSL.val(mapped);
    }

    private Field<?> requireSingleArg(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        if (args.size() != 1) {
            throw new QueryValidationException("function '" + fn + "' expects exactly one argument");
        }
        return toField(args.getFirst(), bindings);
    }

    /**
     * The single <b>text</b> operand of {@code lower}/{@code upper}/{@code trim}/{@code length} — arity,
     * the text check and the cast their renderings need, in one place.
     *
     * <p>These four take the check for the reason {@link #textPredicate} does, and it is the same check:
     * ClickHouse defines no string function over an {@code Enum8} column, and {@code SqlQueryOperators}
     * declares each of them with a {@code CHARACTER} operand family — so without it here the DSL surface
     * would render {@code lower(<enum column>)} and fail inside ClickHouse, while the SQL surface answered
     * the same call with a clean 400.
     */
    @SuppressWarnings("unchecked")
    private Field<String> requireSingleTextArg(
            String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        final Field<?> operand = requireSingleArg(fn, args, bindings);
        requireText(fn, args.getFirst(), bindings, "operand");
        return (Field<String>) operand;
    }

    /**
     * {@code if(condition, then, else)} — the DSL's only conditional, rendered as ClickHouse
     * {@code if(cond, then, else)}.
     *
     * <p><b>The condition must be boolean, and the DSL has no comparison to build one with.</b> That is
     * deliberate (a comparison is filter-grammar vocabulary and stays there), so the condition comes from a
     * boolean column or from one of the catalog's predicate producers — {@code not_empty},
     * {@code starts_with}, {@code contains}, {@code equals}, {@code json_extract_bool}. The rejection names
     * {@code not_empty} because the overwhelmingly common mistake is writing {@code length(x) > 0}.
     *
     * <p><b>A null condition takes the else branch</b>, which is ClickHouse's own semantics for both
     * {@code if} and {@code CASE WHEN} (verified on 26.3) rather than something this rendering arranges. The
     * predicate producers are non-null by construction anyway; the guarantee matters for a nullable boolean
     * column used directly.
     *
     * <p><b>The condition and branch types are read in the DSL's own vocabulary, not jOOQ's.</b> See
     * {@link ExprFieldType} for why: the jOOQ Java type is a lossy image of the DSL type, and reading branch
     * types off it unified a timestamp with a long, unified an object with an array, and refused a timestamp
     * column against a timestamp literal. Numeric branches widen (the {@code EnrichmentTypeMapping} rule, so
     * an {@code if} bound to an enrichment column is judged the same way every other output binding is);
     * anything else must match exactly. Without this the branches would be unified by ClickHouse at
     * execution, turning an authoring mistake into a coerced value — or, for a pair ClickHouse has no
     * supertype for, into a raw server error where the contract promises HTTP 400.
     */
    private Field<?> conditional(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        if (args.size() != 3) {
            throw new QueryValidationException(
                    "function '" + fn + "' expects exactly three arguments (condition, then, else)");
        }
        final Field<?> condition = toField(args.getFirst(), bindings);
        final QueryFieldType conditionType = ExprFieldType.of(args.getFirst(), bindings);
        if (conditionType != QueryFieldType.BOOLEAN) {
            throw new QueryValidationException("function '" + fn + "' requires a boolean condition, got "
                    + (conditionType == null ? "a null literal" : conditionType.code())
                    + "; the DSL has no comparison operator in expression position, so"
                    + " write a test such as length(x) > 0 as not_empty(x)");
        }
        final Field<?> whenTrue = toField(args.get(1), bindings);
        final Field<?> whenFalse = toField(args.get(2), bindings);
        final QueryFieldType branch = branchType(fn, args.get(1), args.get(2), bindings);
        return DSL.field("if({0}, {1}, {2})", JooqTypes.javaType(branch), condition, whenTrue, whenFalse);
    }

    /**
     * The result type of an {@code if}: its two branches must be the same type, except that the numeric
     * types are mutually acceptable and the call then carries the widest of them — the same widening
     * {@code EnrichmentTypeMapping} applies to an output binding, so an {@code if} bound to an enrichment
     * column is judged by one rule rather than two.
     *
     * <p>Three shapes are refused ahead of the equality test, each with its own message because each has its
     * own fix: a {@code null} branch (the DSL cannot render one, and a null <em>test</em> belongs in
     * {@code filter} as {@code eq}/{@code ne} against a null literal), a non-scalar branch (an
     * {@code object}, {@code array} or {@code map} value is not something a conditional can carry, and
     * letting one through would also produce a non-scalar result that the array guard — keyed on function
     * names — cannot see), and only then a genuine mismatch.
     */
    private static QueryFieldType branchType(
            String fn, Expr whenTrue, Expr whenFalse, Map<String, QueryFieldBinding> bindings) {
        return unify(fn, "branch", "branches", whenTrue, whenFalse, bindings, "then", "else");
    }

    /**
     * The single type two operands share: equal, the widest of two numerics, or {@code enum} against a
     * {@code string}. Shared by {@code if}'s branch rule and {@code equals}'s comparability rule, which
     * are the same question — {@code noun} and {@code plural} are what the caller calls one and both of
     * them ({@code "branch"}/{@code "branches"}, {@code "operand"}/{@code "operands"}), so the message
     * reads as the caller's own. Passed rather than derived: two words are cheaper than a rule for
     * making English plurals.
     *
     * <p><b>An {@code enum} unifies with a {@code string}.</b> The DSL's literal vocabulary
     * ({@link com.epam.aidial.analytics.service.query.model.ValueType}) has no enum member and will not
     * gain one: a caller names an enum value with the string it is. An enum operand can therefore only
     * ever meet a {@code string} literal, and the same-type rule above would reject that pair, so
     * {@code equals(status, "running")} — and an {@code if} whose branches are an enum field and a
     * string — would be unwritable. It resolves to {@code enum}: the narrower of the two, and the type
     * the underlying column actually has.
     *
     * <p>This gates the <em>expression-position</em> functions only. The filter grammar's
     * {@code eq}/{@code ne}/{@code lt}/… render straight through {@code FilterTranslator} with no
     * unification (ClickHouse compares an {@code Enum8} against a string literal natively), so a plain
     * {@code filter} over an enum column never depended on this rule.
     */
    private static QueryFieldType unify(
            String fn, String noun, String plural, Expr leftExpr, Expr rightExpr,
            Map<String, QueryFieldBinding> bindings, String leftPosition, String rightPosition) {
        final QueryFieldType left = requireComparableType(fn, noun, leftExpr, bindings, leftPosition);
        final QueryFieldType right = requireComparableType(fn, noun, rightExpr, bindings, rightPosition);
        return unifyTypes(fn, plural, left, right);
    }

    /** {@link #unify}'s rule over two already-resolved types; shared with the N-branch {@code case}. */
    static QueryFieldType unifyTypes(String fn, String plural, QueryFieldType left, QueryFieldType right) {
        if (left == right) {
            return left;
        }
        if (ExprFieldType.isNumeric(left) && ExprFieldType.isNumeric(right)) {
            for (final QueryFieldType candidate : NUMERIC_WIDTH_ORDER) {
                if (left == candidate || right == candidate) {
                    return candidate;
                }
            }
        }
        if (isEnumAndString(left, right)) {
            return QueryFieldType.ENUM;
        }
        throw new QueryValidationException("function '" + fn + "' " + plural + " must be of the same type"
                + " (numeric types widen against each other, and an enum against a string), got "
                + left.code() + " and " + right.code());
    }

    /** Whether the pair is an {@code enum} and a {@code string}, in either order. */
    private static boolean isEnumAndString(QueryFieldType left, QueryFieldType right) {
        return (left == QueryFieldType.ENUM && right == QueryFieldType.STRING)
                || (left == QueryFieldType.STRING && right == QueryFieldType.ENUM);
    }

    /** One operand's DSL type, with the two shapes that can never unify reported on their own terms. */
    private static QueryFieldType requireComparableType(
            String fn, String noun, Expr operand, Map<String, QueryFieldBinding> bindings, String position) {
        final QueryFieldType type = ExprFieldType.of(operand, bindings);
        if (type == null) {
            throw new QueryValidationException("function '" + fn + "' cannot take a null literal as its '"
                    + position + "' " + noun + "; the DSL has no null in expression position,"
                    + " and a null test belongs in 'filter' as an 'eq'/'ne' comparison against a null literal");
        }
        if (!ExprFieldType.isScalar(type)) {
            throw new QueryValidationException("function '" + fn + "' cannot take " + type.code()
                    + " as its '" + position + "' " + noun + "; " + type.code() + " values can"
                    + " only be projected, so there is nothing here for them to reduce to");
        }
        return type;
    }

    /**
     * The two-operand boolean scalars — {@code starts_with}, {@code contains}, {@code equals} — each
     * wrapped in {@code ifNull(…, false)}.
     *
     * <p><b>The wrap is the contract, not caution.</b> ClickHouse gives none of these a false for a null
     * operand: {@code startsWith(NULL, 'x')}, {@code position(NULL, 'x') > 0} and {@code NULL = 'x'} all
     * evaluate to NULL (verified on 26.3). A NULL would serialize as a null boolean in a projection, and as
     * an {@code if} condition it would fall to the else branch by accident rather than by contract. Making
     * them explicitly false for a null operand is what lets one set of producers serve both nullable columns
     * and ADAS's empty-string absence convention — the same argument that shapes {@code not_empty}.
     *
     * <p>All three compare <b>case-sensitively</b>, which is ClickHouse's own behaviour for
     * {@code startsWith}/{@code position}. A caller wanting a case-insensitive test wraps both operands in
     * {@code lower}, which is already in the catalog; a second {@code _ci} spelling of every predicate would
     * double the catalog to save one call.
     *
     * <p>The outer {@code toBool} is the same wrap {@code json_extract_bool}, {@code map_contains} and the
     * array predicates carry, and for the same reason: every ClickHouse predicate yields {@code UInt8}, and
     * {@code ifNull(<UInt8>, false)} stays {@code UInt8}, so without it these read back over the REST
     * surfaces as the numbers {@code 1}/{@code 0} while the other boolean scalars read back as
     * {@code true}/{@code false}. The declared {@code Boolean} result kind only governs the Arrow/Flight
     * schema, so it cannot close that gap on its own — one function advertised as returning boolean would
     * have two spellings depending on the endpoint asked.
     */
    private Field<?> predicate(
            String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings, String template) {
        if (args.size() != 2) {
            throw new QueryValidationException("function '" + fn + "' expects exactly two arguments");
        }
        final Field<?> left = toField(args.getFirst(), bindings);
        final Field<?> right = toField(args.get(1), bindings);
        return DSL.field("toBool(ifNull(" + template + ", false))", Boolean.class, left, right);
    }

    /**
     * {@code starts_with}/{@code contains} — {@link #predicate} plus the operand-type check the SQL surface
     * already makes.
     *
     * <p><b>The check exists so the two surfaces answer alike.</b> {@code SqlQueryOperators} declares these
     * with a {@code CHARACTER} operand family, so {@code contains(total_tokens, 'x')} is a translation-time
     * 400 on the SQL surface. Without the same check here the DSL surface would accept it, render
     * {@code position(total_tokens, 'x')}, and fail inside ClickHouse — the same query answered with a clean
     * 400 through one endpoint and a raw server error through the other.
     */
    private Field<?> textPredicate(
            String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings,
            String template, String secondOperand) {
        if (args.size() != 2) {
            throw new QueryValidationException("function '" + fn + "' expects exactly two arguments");
        }
        requireText(fn, args.getFirst(), bindings, "operand");
        requireText(fn, args.get(1), bindings, secondOperand);
        return predicate(fn, args, bindings, template);
    }

    /**
     * {@code equals(a, b)} — {@link #predicate} plus the requirement that the operands be <b>comparable</b>,
     * under the same rule {@code if} applies to its branches.
     *
     * <p>Unlike the two text predicates this one takes any type, so the check is unification rather than a
     * family: {@code equals(deployment, 1)} has no answer ClickHouse will give — it raises rather than
     * returning false — and the contract says this function returns a boolean. A null operand is refused for
     * its own reason, in {@link #requireComparableType}: {@code equals(x, null)} would render {@code x = NULL},
     * which is null for every row and therefore {@code false} for every row after the wrap. That is a silent
     * always-false, and it is also not what the name suggests — the filter grammar's {@code eq} against a
     * null literal means {@code IS NULL}, so a caller writing this almost certainly wants that instead.
     */
    private Field<?> equality(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        if (args.size() != 2) {
            throw new QueryValidationException("function '" + fn + "' expects exactly two arguments");
        }
        unify(fn, "operand", "operands", args.getFirst(), args.get(1), bindings, "a", "b");
        return predicate(fn, args, bindings, "{0} = {1}");
    }

    /**
     * The four arithmetic operators that need no guard — {@code plus}, {@code minus}, {@code multiply} —
     * rendered from a two-placeholder template. Both operands must be numeric, checked here rather than left
     * to ClickHouse so the DSL surface answers with the same HTTP 400 the SQL surface gives (where
     * {@code SqlQueryOperators} declares a numeric operand family).
     *
     * <p>The template carries its own parentheses. Precedence is not this layer's problem — jOOQ renders the
     * template verbatim and the operands are already-rendered fields — but wrapping makes a nested
     * {@code multiply(plus(a, b), c)} render as {@code ((a + b) * c)} rather than relying on the reader to
     * trust operator precedence, and it is what keeps the DSL→SQL rendering and this one in agreement.
     */
    private Field<?> arithmetic(
            String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings, String template) {
        requireArity(fn, args, 2);
        requireNumeric(fn, args.getFirst(), bindings, "left operand");
        requireNumeric(fn, args.get(1), bindings, "right operand");
        return DSL.field(template, Object.class, toField(args.getFirst(), bindings), toField(args.get(1), bindings));
    }

    /**
     * {@code div} and {@code modulo}, rendered {@code if(coalesce(<b>, 0) = 0, NULL, fn(<a>, coalesce(nullIf(<b>, 0), 1)))}
     * so a zero or NULL divisor yields NULL instead of failing the query.
     *
     * <p><b>This is a contract, not a convenience, and it is not the engine's default.</b> ClickHouse's
     * {@code /} yields {@code inf}/{@code nan} on floats and <em>raises</em> on integers, and {@code modulo}
     * raises on a zero divisor for every operand type. A ratio or bucketing panel must not fail because one
     * group's denominator is empty, so a zero or NULL divisor yields NULL. One rendering covers every operand
     * family, which is why there is no integer special case.
     *
     * <p><b>Why not the shorter {@code fn(<a>, nullIf(<b>, 0))}.</b> It returns the same NULLs, but ClickHouse
     * computes a NULL row's hidden value anyway, as {@code a / 0}, so that row carries {@code inf}/{@code nan}
     * under its NULL mask. Converting the result to {@code Decimal} or an integer checks those hidden values too
     * and fails with {@code DECIMAL_OVERFLOW} / {@code CANNOT_CONVERT_TYPE}. That conversion is exactly what an
     * {@code INSERT … SELECT} into a {@code decimal} column does, so an aggregate ratio measure failed every
     * refresh as soon as one group had an empty denominator. Dividing by {@code coalesce(nullIf(<b>, 0), 1)}
     * means no row ever divides by zero, and the outer {@code if} restores the NULL. The result type is the same
     * as the shorter form's.
     *
     * <p>{@code divide} always returns {@code Float64} in ClickHouse (it is {@code intDiv} that truncates), so
     * an integer-over-integer ratio keeps its fractional part with no cast of ours. {@code modulo} preserves
     * the operand type instead — an integer remainder stays an integer — which is what a bucketing expression
     * needs and why the two are not collapsed into one rendering with a cast.
     *
     * <p>The sign of a {@code modulo} result follows its <b>left</b> operand ({@code modulo(-7, 3) = -1}),
     * which is ClickHouse's own behaviour and is specified in the structured-query spec so a future rendering
     * change cannot silently flip it: the two common conventions disagree, and a cyclical bucketing
     * expression that changed convention would mis-bucket every negative value without erroring.
     */
    private Field<?> guardedDivision(
            String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings, String clickhouseFn) {
        requireArity(fn, args, 2);
        requireNumeric(fn, args.getFirst(), bindings, "left operand");
        requireNumeric(fn, args.get(1), bindings, "right operand");
        return DSL.field("if(coalesce({1}, 0) = 0, NULL, " + clickhouseFn + "({0}, coalesce(nullIf({1}, 0), 1)))",
                Object.class, toField(args.getFirst(), bindings), toField(args.get(1), bindings));
    }

    /**
     * {@code round(value[, digits])}. The optional second argument is an integer literal — negative is legal
     * (rounding to tens, hundreds) — and is required to be a literal rather than an expression because a
     * per-row precision is not something the catalog advertises and ClickHouse would accept silently.
     */
    private Field<?> rounding(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        if (args.size() != 1 && args.size() != 2) {
            throw new QueryValidationException(
                    "function '" + fn + "' expects one or two arguments (value[, digits])");
        }
        requireNumeric(fn, args.getFirst(), bindings, "operand");
        final Field<?> value = toField(args.getFirst(), bindings);
        if (args.size() == 1) {
            return DSL.field("round({0})", Object.class, value);
        }
        if (!(args.get(1) instanceof ValueExpr digits)
                || (digits.valueType() != ValueType.INTEGER && digits.valueType() != ValueType.LONG)
                || !(valueExprToObjectMapper.map(digits) instanceof Number parsed)
                || parsed.longValue() != (int) parsed.longValue()) {
            throw new QueryValidationException(
                    "function '" + fn + "' requires an integer literal as its second argument (digits)");
        }
        return DSL.field("round({0}, {1})", Object.class, value, DSL.val((int) parsed.longValue()));
    }

    /** {@code floor}/{@code ceil} — one numeric operand, rendered from a single-placeholder template. */
    private Field<?> singleNumeric(
            String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings, String template) {
        requireArity(fn, args, 1);
        requireNumeric(fn, args.getFirst(), bindings, "operand");
        return DSL.field(template, Object.class, toField(args.getFirst(), bindings));
    }

    /**
     * {@code map_value(map, key)} → ClickHouse {@code <map>[<key>]}, whose miss returns the value type's
     * default — the empty string for a {@code Map(String, String)} — which is the contract's absent-key answer
     * with no guard of ours. No null guard is needed either: ClickHouse refuses {@code Nullable(Map(...))},
     * so a {@code map} cell is the empty map rather than NULL even where a column would otherwise be null
     * (an unmatched LEFT JOIN on an enrichment reads type defaults, not nulls).
     *
     * <p><b>The key is one literal, matched exactly, and deliberately not a path.</b> Every
     * {@code json_extract_*} function takes a variadic path and traverses nesting; a ClickHouse
     * {@code Map(String, String)} has one flat key namespace, so there is nothing to traverse. Copying the
     * variadic {@code pathKey()} shape here would be the natural mistake and a silent one: OTLP attribute
     * keys such as those in a {@code point_attributes} map are typically dotted ({@code user.email},
     * {@code session.id}), and a dotted key read as a path returns the empty string for every row with
     * nothing raised anywhere — the same failure mode as {@code json_extract_string_ci} handed a native
     * JSON operand.
     */
    private Field<?> mapValue(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        requireArity(fn, args, 2);
        final Field<?> map = mapArgument(fn, args.getFirst(), bindings);
        return DSL.field("{0}[{1}]", String.class, map, DSL.val(mapKeyLiteral(fn, args.get(1))));
    }

    /**
     * {@code map_contains(map, key)} → {@code mapContains(<map>, <key>)}.
     *
     * <p><b>Not redundant with {@code map_value}.</b> Because a miss and a key stored with an empty value both
     * read as the empty string, {@code map_value} alone cannot distinguish "absent" from "present but empty" —
     * a real distinction on producer-defined attribute maps, where an empty value is an observation. This is
     * the only way to ask the question; {@code not_empty(map_value(m, k))} answers a different one.
     */
    private Field<?> mapContains(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        requireArity(fn, args, 2);
        final Field<?> map = mapArgument(fn, args.getFirst(), bindings);
        // toBool, for the reason json_extract_bool carries it: ClickHouse mapContains yields UInt8, which
        // reads back over the REST path as the number 1 rather than as true. The declared result kind only
        // governs the Arrow/Flight surface, so without this the same value has two spellings by endpoint.
        return DSL.field("toBool(ifNull(mapContains({0}, {1}), false))", Boolean.class,
                map, DSL.val(mapKeyLiteral(fn, args.get(1))));
    }

    /**
     * {@code map_keys(map)} → {@code mapKeys(<map>)}, an array of the map's keys in stored order.
     *
     * <p>Array-valued, so its result is projection-only under {@code ArrayValuedFunctions} — except as
     * argument 0 of an array accessor, which is what makes {@code array_length(map_keys(m))} a map's size and
     * {@code array_has(map_keys(m), 'k')} a second spelling of {@code map_contains}. That overlap is accepted:
     * the direct form is what a filter should use, while this answers the question nothing else can — which
     * keys a producer-defined map actually carries, on a feed whose attribute set varies per row.
     */
    private Field<?> mapKeys(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        requireArity(fn, args, 1);
        return DSL.field("mapKeys({0})", Object.class, mapArgument(fn, args.getFirst(), bindings));
    }

    /**
     * One of the five higher-order array calls — {@code arrayExists}, {@code arrayAll}, {@code arrayCount},
     * {@code arrayFilter}, {@code arrayMap} — rendered in ClickHouse's own shape, {@code fn(x -> body, arr)}
     * with the lambda <b>first</b>.
     *
     * <p>The parameter is bound to the array's <em>element</em> type and added to the bindings the body is
     * translated against ({@link LambdaScope}), so a body over a {@code string} array resolves
     * {@code starts_with} and one over a {@code long} array does not — the same operand check every other
     * branch here makes, one level down. The statement's own bindings stay in scope, which is what lets a
     * body capture a column of the enclosing row as it does in ClickHouse.
     *
     * <p><b>No {@code ifNull} wrapper.</b> Elsewhere in this class a wrapper turns a NULL into a usable
     * scalar; here ClickHouse's own answers for an <em>empty</em> array are already the contract
     * ({@code arrayExists} false, {@code arrayAll} true, {@code arrayCount} 0, the two array-valued ones
     * empty), and a wrapper would only paper over an array column that cannot be null in the first place.
     * The boolean results still pass through {@code toBool}, for the reason every boolean here does: the
     * engine answers {@code UInt8} and the advertised type must not depend on that.
     */
    private Field<?> higherOrder(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        requireArity(fn, args, 2);
        final Expr arrayArg = args.get(1);
        final Field<?> array = arrayArgument(fn, arrayArg, bindings);
        if (!(args.getFirst() instanceof LambdaExpr lambda)) {
            throw new QueryValidationException("function '" + fn + "' requires a lambda as its first"
                    + " argument, as in " + fn + "(x -> ..., array)");
        }
        final String param = LambdaScope.parameterOf(fn, lambda);
        final Map<String, QueryFieldBinding> scoped =
                LambdaScope.extend(bindings, param, ExprFieldType.ofElement(arrayArg, bindings));
        final Expr body = lambda.body();
        if (body == null) {
            throw new QueryValidationException("function '" + fn + "' has a lambda with no body");
        }
        rejectAggregateOrWindowInBody(fn, body);
        if (HigherOrderFunctions.requiresBooleanBody(fn)) {
            final QueryFieldType bodyType = ExprFieldType.of(body, scoped);
            if (bodyType != QueryFieldType.BOOLEAN) {
                throw new QueryValidationException("function '" + fn + "' requires a lambda returning a"
                        + " boolean, got " + (bodyType == null ? "a null literal" : bodyType.code()));
            }
        }
        // Two names are interpolated into the template rather than bound: `fn`, which is one of five
        // canonical constants (HigherOrderFunctions.canonical, never the caller's spelling), and the
        // parameter, which LambdaScope has matched against its name pattern — ClickHouse takes a bare
        // identifier there, so binding it is not an option. Everything else is a bound operand.
        final String call = fn + "({0} -> {1}, {2})";
        final String template = HigherOrderFunctions.returnsBoolean(fn) ? "toBool(" + call + ")" : call;
        return DSL.field(template, HigherOrderFunctions.resultClass(fn),
                DSL.field(param), toField(body, scoped), array);
    }

    /**
     * Refuses an aggregate or a window call inside a lambda body — the DSL-side counterpart of the SQL
     * surface's {@code SqlLambdaRewriter.rejectAggregate}, worded the same so the two surfaces refuse the
     * same bodies with the same message.
     *
     * <p>Neither is caught anywhere else on this path. A window is refused in {@code filter} and
     * {@code having} by {@link WindowExpressions}, which leaves a select-position body unchecked; and in
     * aggregate mode a nested aggregate is an ordinary expression, so {@code max(deployment)} in a body
     * would translate exactly as it does one level up and reach ClickHouse, which answers with a message
     * about its own internals — a 503 where the contract promises a 400 naming the call.
     *
     * <p>The walk does not descend into a nested lambda's body, and does not need to: the inner
     * higher-order call runs this check itself when {@link #higherOrder} translates it.
     */
    private static void rejectAggregateOrWindowInBody(String fn, Expr body) {
        WindowExpressions.visit(body, expr -> {
            if (expr instanceof WindowExpr) {
                throw new QueryValidationException("a window function cannot appear inside the lambda of '"
                        + fn + "': the body is evaluated per element of one row's array");
            }
            if (expr instanceof FnExpr call && WindowExpressions.isAggregate(call.name())) {
                throw new QueryValidationException("'" + call.name() + "' is an aggregate and cannot appear"
                        + " inside the lambda of '" + fn + "': the body is evaluated per element of one"
                        + " row's array. An aggregate may produce the array itself");
            }
        });
    }

    /**
     * Refuses an aggregate as the operand of a flattening aggregate. The carve-out that admits an array at
     * that position exists for an array <em>value</em> — an {@code array} column, or a scalar call
     * producing one such as {@code arrayFilter} or {@code map_keys} — and an aggregate inside an aggregate
     * is what ClickHouse refuses outright. Keyed on the catalog's own group rather than on a name list, so
     * it covers both the array-valued aggregates ({@code group_uniq_array}, the other flattening one,
     * {@code arg_max} over an array) and a scalar one such as {@code max}, which would otherwise be
     * reported as a type mismatch and send the author looking for the wrong fix.
     *
     * <p>{@link ArrayValuedFunctions#rejectAsOperand(Expr, String, int)} states the same refusal for the
     * array-valued half, because that is the rule both surfaces consult per argument; this is the one
     * place that can name the aggregate, so it runs first.
     */
    private static void rejectAggregateOperand(String fn, Expr operand) {
        if (operand instanceof FnExpr call && WindowExpressions.isAggregate(call.name())) {
            throw new QueryValidationException("'" + call.name() + "' is an aggregate and cannot be the"
                    + " operand of '" + fn + "': ClickHouse refuses an aggregate inside an aggregate."
                    + " '" + fn + "' flattens the arrays the group's rows carry, so its operand is an"
                    + " array column or an array-valued scalar call");
        }
    }

    /**
     * {@code groupUniqArrayArray}/{@code groupArrayArray} — ClickHouse's {@code -Array} combinator on the
     * two array-collecting aggregates, which flatten the group's arrays into one. The reason they exist
     * here rather than being spelled {@code group_uniq_array(<array>)}: that call's result would be an
     * array of arrays, which the DSL has no type for, while these two answer with the element type itself.
     */
    private Field<?> flatteningAggregate(String fn, Field<?> array) {
        // `fn` is one of two canonical constants, resolved by ArrayValuedFunctions rather than taken
        // from the caller's spelling; the operand is bound.
        return DSL.field(fn + "({0})", Object.class, array);
    }

    /**
     * {@code array_has(array, value)} → {@code has(<array>, <value>)}, wrapped so an empty or NULL array is
     * {@code false} rather than NULL. Returns boolean, so it composes as {@code if}'s condition — which is
     * what turns a closed value set into a distribution in a single aggregate query
     * ({@code sum(if(array_has(roles, 'admin'), price, 0))}).
     */
    private Field<?> arrayHas(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        requireArity(fn, args, 2);
        final Field<?> array = arrayArgument(fn, args.getFirst(), bindings);
        ArrayValuedFunctions.rejectAsOperand(args.get(1), "the value operand of '" + fn + "'");
        return DSL.field("toBool(ifNull(has({0}, {1}), false))", Boolean.class,
                array, toField(args.get(1), bindings));
    }

    /**
     * {@code array_has_any}/{@code array_has_all} → {@code hasAny}/{@code hasAll} over a literal value list.
     *
     * <p>The second operand is an {@link ArrayExpr}, the DSL's array-of-literals shape — which {@code toField}
     * refuses outright ("only valid as the right operand of 'in'"), so it is unpacked here the way the filter
     * grammar's {@code in} unpacks it. An empty list is rejected rather than answered: {@code hasAll} over an
     * empty list is vacuously true and {@code hasAny} vacuously false, and neither is plausibly what a caller
     * writing an empty list meant.
     */
    private Field<?> arrayHasMany(
            String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings, String clickhouseFn) {
        requireArity(fn, args, 2);
        final Field<?> array = arrayArgument(fn, args.getFirst(), bindings);
        if (!(args.get(1) instanceof ArrayExpr(List<Expr> values))) {
            throw new QueryValidationException("function '" + fn
                    + "' requires an array of literal values as its second argument");
        }
        final List<Expr> items = values == null ? List.of() : values;
        if (items.isEmpty()) {
            throw new QueryValidationException("function '" + fn + "' requires a non-empty value list");
        }
        final List<Field<?>> literals = new ArrayList<>(items.size());
        for (final Expr item : items) {
            if (!(item instanceof ValueExpr value)) {
                throw new QueryValidationException("function '" + fn + "' value list items must be literals");
            }
            literals.add(toField(value, bindings));
        }
        final StringBuilder template = new StringBuilder("toBool(ifNull(").append(clickhouseFn).append("({0}, [");
        for (int i = 0; i < literals.size(); i++) {
            template.append(i == 0 ? "" : ", ").append('{').append(i + 1).append('}');
        }
        template.append("]), false))");
        final Field<?>[] operands = new Field<?>[literals.size() + 1];
        operands[0] = array;
        for (int i = 0; i < literals.size(); i++) {
            operands[i + 1] = literals.get(i);
        }
        return DSL.field(template.toString(), Boolean.class, operands);
    }

    /**
     * {@code array_length(array)} → {@code length(<array>)}, wrapped so a NULL array counts 0 rather than
     * NULL. A separate catalog name from {@code length(text)} even though ClickHouse's {@code length} accepts
     * both: two DSL functions sharing one name would need overload resolution the closed catalog has no
     * concept of, and the discovery surface would have no way to advertise two return types for one entry.
     */
    private Field<?> arrayLength(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        requireArity(fn, args, 1);
        return DSL.field("ifNull(length({0}), 0)", Integer.class, arrayArgument(fn, args.getFirst(), bindings));
    }

    /**
     * {@code array_join(array)} → {@code arrayJoin(<array>)}, the one catalog function that changes the
     * number of rows a query returns: the enclosing row is replaced by one row per element, and a row whose
     * array is empty is replaced by none.
     *
     * <p><b>Exactly one operand, deliberately.</b> ClickHouse's {@code arrayJoin} takes one
     * ({@code arrayJoin(x, 'left')} is {@code NUMBER_OF_ARGUMENTS_DOESNT_MATCH}), and its outer form is the
     * {@code LEFT ARRAY JOIN} <em>clause</em>, not a second operand. A {@code mode} argument here would be
     * surface this service invented while wearing ClickHouse's name — and a caller who read
     * {@code array_join(x, 'left')} would reasonably expect the same call to work against the engine, where
     * it fails. The arity message says so, because "expects exactly one argument" alone would read like an
     * oversight. The arity also makes the <em>other</em> dialect's same-named function — Spark's
     * {@code array_join(array, delimiter)}, which joins an array into a string — fail closed rather than be
     * silently reinterpreted.
     *
     * <p><b>No positional guard.</b> An earlier draft refused this call in {@code filter}/{@code having},
     * inside an aggregate's arguments and inside a window, on the assumption that ClickHouse refused it
     * there. Measured against ClickHouse 26.3, it accepts all three (and {@code ORDER BY} besides), so the
     * guards were narrowing the engine for no reason of ours and are not present. The operand check below is
     * the engine's own rule ({@code ILLEGAL_TYPE_OF_ARGUMENT}), moved earlier so the message arrives from
     * translation rather than from the server.
     *
     * <p>Rendered bare, with no {@code ifNull} or {@code empty} wrapper — unlike every other function here,
     * whose wrappers exist to turn a NULL into a usable scalar. There is nothing to normalize: an empty array
     * yielding no rows <em>is</em> the contract, and a wrapper would be the outer variant this deliberately
     * does not have.
     */
    private Field<?> arrayJoin(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        if (args.size() != 1) {
            throw new QueryValidationException("function '" + fn + "' expects exactly one argument, the array to"
                    + " expand; it takes no mode and has no outer variant, so an empty array yields no rows");
        }
        return DSL.field("arrayJoin({0})", Object.class, arrayArgument(fn, args.getFirst(), bindings));
    }

    /**
     * One scalar value rendered as text — the single definition of that rendering, which
     * {@code to_string}, {@code concat} and {@code array_to_string} all reach.
     *
     * <p><b>NULL is preserved rather than normalized</b>, unlike almost every other text-producing branch
     * here. This is the {@code CAST} semantics the function stands in for on the SQL surface: a cast of
     * nothing is nothing, and a caller projecting {@code to_string(price)} over a nullable column wants an
     * empty cell rather than a row claiming the empty string. The never-null contract lives on the two
     * functions that <em>compose</em> text and would otherwise lose a whole label to one absent operand —
     * {@link #concat} and {@link #arrayToString} — each wrapping this rendering itself.
     *
     * <p><b>The boolean branch is not a micro-optimization.</b> A DSL {@code boolean} may sit on a
     * ClickHouse {@code Bool} or a {@code UInt8} column, and a bare {@code toString} answers
     * {@code 'true'} for one and {@code '1'} for the other — the same divergence the catalog's
     * {@code toBool} rule exists to close on the way out. Normalizing through {@code toBool} first makes
     * the advertised text independent of the physical type, so the spec can state one answer.
     *
     * <p>Rejects a non-scalar operand ({@code object}/{@code array}/{@code map}) and a null literal, naming
     * what it got: those have no scalar text, and the array case additionally has an
     * {@code ArrayValuedFunctions} rule for the <em>computed</em> form that this complements for the
     * declared one.
     */
    private Field<?> asText(String fn, Expr arg, Map<String, QueryFieldBinding> bindings, String position) {
        final QueryFieldType type = ExprFieldType.of(arg, bindings);
        if (type == null) {
            throw new QueryValidationException("function '" + fn + "' requires a scalar " + position
                    + ", got a null literal; there is no text for a value of no type");
        }
        if (!ExprFieldType.isScalar(type)) {
            throw new QueryValidationException("function '" + fn + "' requires a scalar " + position + ", got "
                    + type.code() + ", which can only be projected");
        }
        return DSL.field(textRendering(type, "{0}"), String.class, toField(arg, bindings));
    }

    /**
     * The text rendering of a value of {@code type}, as a plain-SQL fragment over {@code operand} — the one
     * place the {@code to_string} rendering table is written down, so {@link #asText} and
     * {@link #arrayToString}'s per-element lambda cannot drift apart. {@code operand} is a jOOQ template
     * placeholder ({@code {0}}) for the former and the lambda variable ({@code x}) for the latter.
     *
     * <p><b>Both branches end in {@code toString}, and that is what preserves NULL.</b> An
     * {@code if(<operand>, 'true', 'false')} would not: ClickHouse takes the else branch for a NULL
     * condition and answers the non-nullable {@code 'false'} — the very behaviour {@link #conditional}'s
     * note records for the {@code if} function — so a nullable {@code boolean} operand would render as
     * {@code false} rather than as an empty cell, breaking {@code to_string}'s null contract for the one
     * type that reached it through a conditional. {@code toBool} normalizes the physical type while
     * keeping the nullability ({@code Nullable(UInt8)} → {@code Nullable(Bool)}), and {@code toString}
     * over that answers NULL, exactly as it does for every other type here. Verified on 26.3, the
     * version this project pins.
     */
    private static String textRendering(QueryFieldType type, String operand) {
        return type == QueryFieldType.BOOLEAN
                ? "toString(toBool(" + operand + "))"
                : "toString(" + operand + ")";
    }

    /**
     * {@code concat(a, b[, …])} → {@code concat(ifNull(<a as text>, ''), …)}, every operand rendered by
     * {@link #asText} — so an operand needs no text type — and every one of them null-guarded, so the whole
     * call is never NULL.
     *
     * <p>The guard is <b>here</b> rather than inside {@link #asText} because this is the function whose
     * result a null operand would otherwise destroy: PostgreSQL's own {@code concat} ignores NULLs for the
     * same reason, and a dashboard label reading {@code a -> } is more useful than a row reading null.
     * {@code to_string} keeps NULL, which is what a cast means.
     *
     * <p><b>Two operands minimum.</b> A one-argument call is {@code to_string} spelled differently, and one
     * operation with two spellings is what the closed catalog exists to avoid; the message names the other
     * function rather than only the arity, since that is the fix.
     */
    private Field<?> concat(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        if (args.size() < 2) {
            throw new QueryValidationException("function '" + fn + "' expects at least two operands to join;"
                    + " rendering a single value as text is 'to_string'");
        }
        final StringBuilder template = new StringBuilder("concat(");
        final Field<?>[] operands = new Field<?>[args.size()];
        for (int i = 0; i < args.size(); i++) {
            template.append(i == 0 ? "" : ", ").append("ifNull({").append(i).append("}, '')");
            operands[i] = asText(fn, args.get(i), bindings, "operand " + (i + 1));
        }
        return DSL.field(template.append(')').toString(), String.class, operands);
    }

    /**
     * {@code array_to_string(array, delimiter)} → the array rendered as one text value, empty for an empty
     * array and never NULL.
     *
     * <p><b>The {@code arrayMap} is load-bearing, and for one type only.</b> On 26.3 — the version this
     * project pins — {@code arrayStringConcat} renders a numeric element itself
     * ({@code arrayStringConcat([1, 2, 3], ',')} → {@code '1,2,3'}) and skips a NULL one
     * ({@code ['a', NULL, 'b']} → {@code 'a,b'}), so neither is what the map is for. A {@code boolean}
     * element is: a DSL boolean may sit on a ClickHouse {@code UInt8} — every {@code group_uniq_array}
     * over {@code not_empty(…)}, {@code map_contains(…)} or a {@code CASE} yielding a comparison produces
     * {@code Array(UInt8)} — where the engine's own rendering prints {@code 1}/{@code 0}
     * ({@code arrayStringConcat(CAST([1, 0] AS Array(UInt8)), ',')} → {@code '1,0'}) while
     * {@code to_string} of the same value prints {@code true}/{@code false}. Mapping every element through
     * the same {@link #textRendering} {@link #asText} uses is what makes the spec's "each element rendered
     * exactly as {@code to_string} renders a scalar of that type" true of the executed SQL, whatever the
     * operand's element type — which it has to be of, since the motivating operand is a
     * {@code group_uniq_array} over whatever column the caller grouped. The element type comes from
     * {@link ExprFieldType#ofElement}, the same derivation {@code array_join} is typed by; an operand whose
     * element type cannot be derived falls back to {@code toString}, which is what every non-boolean
     * element renders as anyway.
     *
     * <p><b>A NULL element becomes an empty slot, not a dropped one</b> — the inner {@code ifNull}, which
     * is the one place this rendering deliberately parts from both namesakes: ClickHouse's
     * {@code arrayStringConcat} and PostgreSQL's {@code array_to_string} (without its {@code null_string})
     * both omit a NULL element, so {@code ['a', NULL, 'b']} joins as {@code 'a,b'} there and as
     * {@code 'a,,b'} here. Absence keeping its position is the contract {@code concat} carries for an
     * operand and {@code map_value} and {@code json_extract_string} carry for a missing value; dropping it
     * would make the rendered cell's element count disagree with {@code array_length} of the same operand.
     * Rarely reachable — {@code group_uniq_array} drops nulls and a ClickHouse array column's elements are
     * non-nullable — but it is a contract either way, so the spec states it.
     *
     * <p>An <b>empty</b> delimiter is accepted, unlike {@code split_string}'s: joining on nothing is a
     * meaningful concatenation, while splitting on nothing is not defined.
     */
    private Field<?> arrayToString(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        requireArity(fn, args, 2);
        final Field<?> array = arrayArgument(fn, args.getFirst(), bindings);
        if (!(args.get(1) instanceof ValueExpr(ValueType delimiterType, String delimiter))
                || delimiterType != ValueType.STRING
                || delimiter == null) {
            throw new QueryValidationException("function '" + fn
                    + "' requires a string literal delimiter as its second argument");
        }
        final String element = textRendering(ExprFieldType.ofElement(args.getFirst(), bindings), "x");
        return DSL.field(
                "ifNull(arrayStringConcat(arrayMap(x -> ifNull(" + element + ", ''), {0}), {1}), '')",
                String.class, array, DSL.val(delimiter));
    }

    /**
     * {@code array_slice(array, offset, length)} → {@code arraySlice(<array>, <offset>, <length>)}, the
     * bounded window that caps a rendered cell.
     *
     * <p>Both bounds are <b>integer literals</b>, as {@code round}'s {@code digits} and
     * {@code split_string}'s delimiter are: a computed bound would have to be range-checked per row inside
     * ClickHouse, where the failure is the engine's message rather than a 400 naming the argument.
     *
     * <p>A <b>negative offset</b> is accepted and counts from the end, because ClickHouse defines it and
     * narrowing the engine for no reason of ours is what {@link #arrayJoin}'s note argues against. Zero is
     * not, and here we do narrow the engine deliberately: {@code arraySlice(a, 0, n)} does not raise on
     * 26.3, it answers the empty array — a silently empty cell for what is almost always a caller reading
     * the offset as 0-based, which the 400 names instead. A {@code length} below 1 is refused for the same
     * reason ({@code 0} answers the empty array and a negative one drops that many from the end, so
     * {@code array_slice(a, 2, -1)} would quietly mean something other than "at most -1 elements").
     *
     * <p>Rendered bare, with no {@code ifNull}: a ClickHouse {@code Array(T)} column cannot be
     * {@code Nullable}, so there is no NULL to normalize — and no typed empty-array literal to normalize it
     * to, which is why {@code array_length} wraps its <em>result</em> instead.
     */
    private Field<?> arraySlice(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        requireArity(fn, args, 3);
        final Field<?> array = arrayArgument(fn, args.getFirst(), bindings);
        final int offset = intLiteral(fn, args.get(1), "offset");
        if (offset == 0) {
            throw new QueryValidationException("function '" + fn + "' requires a non-zero integer literal offset;"
                    + " the first element is at 1, and a negative offset counts from the end");
        }
        final int length = intLiteral(fn, args.get(2), "length");
        if (length < 1) {
            throw new QueryValidationException(
                    "function '" + fn + "' requires a positive integer literal length");
        }
        return DSL.field("arraySlice({0}, {1}, {2})", Object.class, array, DSL.val(offset), DSL.val(length));
    }

    /** One integer-literal argument, named by {@code position}, or a 400 saying which argument is at fault. */
    private int intLiteral(String fn, Expr arg, String position) {
        if (!(arg instanceof ValueExpr value)
                || (value.valueType() != ValueType.INTEGER && value.valueType() != ValueType.LONG)
                || !(valueExprToObjectMapper.map(value) instanceof Number parsed)
                || parsed.longValue() != (int) parsed.longValue()) {
            throw new QueryValidationException("function '" + fn
                    + "' requires an integer literal " + position);
        }
        return (int) parsed.longValue();
    }

    /**
     * {@code split_string(text, delimiter)} → the split with every element trimmed. An empty or NULL text
     * yields an empty array rather than the one-element {@code ['']} ClickHouse's {@code splitByString} would
     * give, which is the contract and also the answer a caller grouping by the result wants.
     */
    private Field<?> splitString(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        requireArity(fn, args, 2);
        requireText(fn, args.getFirst(), bindings, "operand");
        if (!(args.get(1) instanceof ValueExpr(ValueType delimiterType, String delimiter))
                || delimiterType != ValueType.STRING
                || delimiter == null
                || delimiter.isEmpty()) {
            throw new QueryValidationException("function '" + fn
                    + "' requires a non-empty string literal delimiter as its second argument");
        }
        final Field<?> text = toField(args.getFirst(), bindings);
        return DSL.field(
                "if(empty(ifNull({0}, '')), emptyArrayString(),"
                        + " arrayMap(x -> trimBoth(x), splitByString({1}, ifNull({0}, ''))))",
                Object.class, text, DSL.val(delimiter));
    }

    /** Rejects a call whose argument count is not {@code expected}, naming the function. */
    private static void requireArity(String fn, List<Expr> args, int expected) {
        if (args.size() != expected) {
            throw new QueryValidationException("function '" + fn + "' expects exactly "
                    + (expected == 1 ? "one argument" : expected + " arguments"));
        }
    }

    /**
     * Requires the argument at {@code position} to be numeric, naming what it actually is. Mirrors
     * {@link #requireText}: the SQL surface makes the same check through its operand families, and without it
     * here the DSL surface would render arithmetic over a string and fail inside ClickHouse — the same query
     * answered with a clean 400 through one endpoint and a raw server error through the other.
     */
    private static void requireNumeric(
            String fn, Expr arg, Map<String, QueryFieldBinding> bindings, String position) {
        final QueryFieldType type = ExprFieldType.of(arg, bindings);
        if (ExprFieldType.isNumeric(type)) {
            return;
        }
        throw new QueryValidationException("function '" + fn + "' requires a numeric " + position + ", got "
                + (type == null ? "a null literal" : type.code()));
    }

    /**
     * The {@code map}-typed operand of a map accessor, coerced so a NULL cell behaves as the empty map.
     * A {@code map} field is projection-only everywhere else, and this is the position that admits it —
     * {@code SqlProjectionOnlyTypes} states the mirror rule for the SQL surface, and the two must agree on
     * the position or a query legal on one is refused on the other.
     */
    private Field<?> mapArgument(String fn, Expr arg, Map<String, QueryFieldBinding> bindings) {
        final QueryFieldType type = ExprFieldType.of(arg, bindings);
        if (type != QueryFieldType.MAP) {
            throw new QueryValidationException("function '" + fn + "' requires a map operand, got "
                    + (type == null ? "a null literal" : type.code()));
        }
        return toField(arg, bindings);
    }

    /**
     * The {@code array}-typed operand of an array-operand function — a declared {@code array} column or an
     * array-returning call ({@code map_keys}, {@code split_string}, {@code json_extract_array},
     * {@code group_uniq_array}), which is the composition that makes
     * {@code array_length(map_keys(m))} expressible.
     */
    private Field<?> arrayArgument(String fn, Expr arg, Map<String, QueryFieldBinding> bindings) {
        final QueryFieldType type = ExprFieldType.of(arg, bindings);
        if (type != QueryFieldType.ARRAY) {
            throw new QueryValidationException("function '" + fn + "' requires an array operand, got "
                    + (type == null ? "a null literal" : type.code()));
        }
        return toField(arg, bindings);
    }

    /**
     * One literal map key. Unlike {@code pathKey} this needs no backslash or emptiness guard for a rendering
     * reason — the key renders as an escaped string literal, never an identifier segment — but an empty key is still refused,
     * since a {@code Map} key of {@code ''} is not something any producer writes and accepting it would make
     * a typo look like a miss.
     */
    private static String mapKeyLiteral(String fn, Expr arg) {
        if (!(arg instanceof ValueExpr(ValueType valueType, String value))
                || valueType != ValueType.STRING
                || value == null
                || value.isEmpty()) {
            throw new QueryValidationException("function '" + fn
                    + "' requires a non-empty string literal key as its second argument;"
                    + " the key is matched literally, not as a path");
        }
        return value;
    }

    /**
     * Requires the argument at {@code position} to be a text expression, naming what it actually is. Text is
     * {@code string} and {@code uuid}: both render as ClickHouse strings and both type as {@code CHARACTER}
     * in the Calcite schema, so accepting the pair here is what agrees with the SQL surface.
     *
     * <p><b>{@code enum} is deliberately not text</b>, despite typing as {@code CHARACTER} on the SQL
     * surface. ClickHouse refuses a string function over an {@code Enum8} column — {@code
     * startsWith(status, 'run')} raises rather than evaluating — so admitting it here would produce a
     * statement the server rejects, turning a knowable input error into a failed query. Comparison,
     * {@code in} and sorting all work, because those ClickHouse does define over an enum. Making the
     * string functions work would mean rendering the field as {@code toString(col)} in text position on
     * both surfaces; that is a rendering change, not a validation one, and is left out of the change that
     * introduced the type.
     */
    private static void requireText(
            String fn, Expr arg, Map<String, QueryFieldBinding> bindings, String position) {
        requireTextOperand("function '" + fn + "'", arg, bindings, position);
    }

    /**
     * {@link #requireText} with the subject spelled by the caller, so the same rule can name something that
     * is not a function call. {@link FilterTranslator} applies it to the filter grammar's contains family
     * ({@code co}/{@code nc}/{@code ico}/{@code inc}) — the same {@code LIKE} over the same operand the
     * {@code contains} function expresses, and the same ClickHouse rejection if a non-text operand reaches
     * the server — so the two spellings of one operation cannot disagree about what they accept.
     *
     * @param subject what the message names, already quoted (e.g. {@code function 'starts_with'})
     */
    static void requireTextOperand(
            String subject, Expr arg, Map<String, QueryFieldBinding> bindings, String position) {
        final QueryFieldType type = ExprFieldType.of(arg, bindings);
        if (type == QueryFieldType.STRING || type == QueryFieldType.UUID) {
            return;
        }
        throw new QueryValidationException(subject + " requires a text " + position + ", got "
                + (type == null ? "a null literal" : type.code()));
    }

    /**
     * Records how {@code expr} addresses each {@code object} column of {@code bindings}, so a keyed grain
     * join can project the addressed subcolumns instead of the whole native {@code JSON} column.
     *
     * <p>Deliberately a method of this class rather than a walker beside it: the rule it applies —
     * which functions can be served from a subcolumn, and what counts as a literal path — is
     * {@link #jsonExtract}'s, and the derived table's projection has to agree with what
     * {@link #subcolumnExtract} will render or the statement names a column that does not exist. Two
     * copies of that rule is how they come to disagree, so there is one, and it lives with the renderer.
     *
     * <p><b>Conservative by construction.</b> Anything this walk cannot account for marks every
     * {@code object} column whole rather than leaving it unmentioned: an unrecognised construct, or a
     * subquery, whose scope is another statement's. Missing a whole-column reference is the one error with
     * teeth — it would prune away a column the statement still reads — so the walk fails toward the
     * rendering that was correct before pruning existed.
     */
    public void collectObjectPaths(
            Expr expr, Map<String, QueryFieldBinding> bindings, ObjectPathUsage.Builder into) {
        switch (expr) {
            case null -> {
                // A missing optional operand (a CASE without ELSE) contributes no reference.
            }
            case FieldExpr(String name) -> {
                // The column as a value in its own right needs the document, not a path within it.
                if (isObjectField(name, bindings)) {
                    into.markWhole(name);
                }
            }
            case FnExpr fn -> collectFnObjectPaths(fn, bindings, into);
            case ArrayExpr(List<Expr> items) ->
                    nullSafe(items).forEach(item -> collectObjectPaths(item, bindings, into));
            case CaseExpr(List<CaseBranch> when, Expr otherwise) -> {
                for (final CaseBranch branch : nullSafe(when)) {
                    collectObjectPaths(branch.when(), bindings, into);
                    collectObjectPaths(branch.then(), bindings, into);
                }
                collectObjectPaths(otherwise, bindings, into);
            }
            case WindowExpr window -> {
                collectObjectPaths(window.fn(), bindings, into);
                nullSafe(window.partitionBy()).forEach(arg -> collectObjectPaths(arg, bindings, into));
                nullSafe(window.orderBy()).forEach(order -> collectObjectPaths(order.expr(), bindings, into));
            }
            // A scalar subquery selects from its own relation, so its object reads belong to that
            // statement's joins, not this one's. Nothing here can tell whether it also reaches this
            // statement's columns, so everything stays whole.
            case ScalarSubqueryExpr ignored -> markEveryObjectWhole(bindings, into);
            case LambdaExpr lambda -> collectObjectPaths(lambda.body(), bindings, into);
            case LambdaParamExpr ignored -> {
                // A lambda parameter stands for an array element, never for a column of this row.
            }
            case ValueExpr ignored -> {
                // A literal references no column.
            }
            case ParamExpr ignored -> {
                // A bound parameter references no column.
            }
        }
    }

    /** As {@link #collectObjectPaths(Expr, Map, ObjectPathUsage.Builder)}, over a filter tree. */
    public void collectObjectPaths(
            FilterNode node, Map<String, QueryFieldBinding> bindings, ObjectPathUsage.Builder into) {
        switch (node) {
            case null -> {
                // A blank filter references no column.
            }
            case LogicalNode(LogicalOp ignored, List<FilterNode> args) ->
                    nullSafe(args).forEach(arg -> collectObjectPaths(arg, bindings, into));
            case ComparisonNode(ComparisonOp ignored, List<Expr> args) ->
                    nullSafe(args).forEach(arg -> collectObjectPaths(arg, bindings, into));
            case SubqueryNode ignored -> markEveryObjectWhole(bindings, into);
        }
    }

    /**
     * The one place a path read is distinguished from a whole-document read, mirroring
     * {@link #jsonExtract}'s own branch: the five path-addressable extractors over an {@code object} field
     * contribute their literal path, and everything else that touches the column contributes the document.
     */
    private void collectFnObjectPaths(
            FnExpr fn, Map<String, QueryFieldBinding> bindings, ObjectPathUsage.Builder into) {
        final List<Expr> args = nullSafe(fn.args());
        final QueryFieldBinding object = args.isEmpty()
                ? null
                : objectFieldArgument(args.getFirst(), bindings);
        if (object != null && JsonExtractFunctions.isJsonExtract(fn.name())) {
            if (readsSerializedText(fn.name()) || !isLiteralPath(args)) {
                into.markWhole(object.name());
            } else {
                into.addPath(object.name(), literalPath(args));
            }
            // The JSON argument is accounted for; the remaining arguments are the path literals, which
            // reference nothing. Walking them anyway would be harmless but says something false about why.
            return;
        }
        args.forEach(arg -> collectObjectPaths(arg, bindings, into));
    }

    /** Whether every argument after the JSON operand is a string literal, which is what a path requires. */
    private static boolean isLiteralPath(List<Expr> args) {
        if (args.size() < 2) {
            return false;
        }
        for (int i = 1; i < args.size(); i++) {
            if (!(args.get(i) instanceof ValueExpr value) || value.valueType() != ValueType.STRING) {
                return false;
            }
        }
        return true;
    }

    /**
     * The literal path of a call {@link #isLiteralPath} accepted, read without {@code pathKey}'s
     * validation: this walk runs before translation and must not raise, and a key the validation would
     * reject reaches {@code pathKey} moments later through the ordinary rendering. A key that renders as
     * more than one identifier segment would be a real hazard here, which is why an empty key — the one
     * such spelling that reaches this far — is folded to the whole column by
     * {@link ObjectPathUsage.Builder#addPath}.
     */
    private static List<String> literalPath(List<Expr> args) {
        final List<String> path = new ArrayList<>(args.size() - 1);
        for (int i = 1; i < args.size(); i++) {
            final String key = ((ValueExpr) args.get(i)).value();
            // A dot in a key makes the alias ambiguous rather than merely ugly: the alias joins column and
            // path with dots, so the single key 'a.b' and the two-segment path ['a','b'] would both render
            // as "request_tags.a.b". One derived table projecting both under one name resolves the outer
            // reference to whichever it likes, so one read silently returns the other's value. Refused into
            // the whole-column fallback, which is correct for both and cheap for neither.
            if (key == null || key.isEmpty() || key.indexOf('\\') >= 0 || key.indexOf('.') >= 0) {
                return List.of();
            }
            path.add(key);
        }
        return path;
    }

    private boolean isObjectField(String name, Map<String, QueryFieldBinding> bindings) {
        final QueryFieldBinding binding = bindings.get(name);
        return binding != null && binding.type() == QueryFieldType.OBJECT;
    }

    private static void markEveryObjectWhole(
            Map<String, QueryFieldBinding> bindings, ObjectPathUsage.Builder into) {
        for (final QueryFieldBinding binding : bindings.values()) {
            if (binding.type() == QueryFieldType.OBJECT) {
                into.markWhole(binding.name());
            }
        }
    }

    private static <T> List<T> nullSafe(List<T> items) {
        return items == null ? List.of() : items;
    }

    /**
     * {@code json_extract_*(json, key[, key…])} — typed scalar extraction from a JSON document. Every
     * argument after the first must be a <b>string literal</b> path key, so the accessed path is
     * statically known (a computed path would hide it from a rule's referenced-column analysis).
     *
     * <p>The first argument may be either an expression yielding JSON <em>text</em> (typically a
     * {@code string} column) or an {@code object} field, and the rendering differs because the two
     * are different ClickHouse types — but the <b>observable result does not</b>. The four functions
     * are overloaded, not duplicated: for the same document, both renderings return equal values for
     * a present value, an absent path, a member stored as an explicit JSON {@code null}, and a
     * requested type that does not match the stored member's type. Barring the exception below,
     * nothing a caller sees may depend on whether a document is stored as an {@code object} column or
     * as JSON text.
     *
     * <p><b>One member shape is outside that guarantee</b>, deliberately and by documented exception:
     * a <em>composite</em> member — a nested document or an array — read as <b>text</b>. Over JSON text
     * {@code JSONExtractString} falls back to the member's <em>raw serialization</em> and hands back the
     * source spelling; a native {@code JSON} column keeps no such spelling, it keeps typed values, and
     * the two shapes lose it differently:
     *
     * <ul>
     *   <li>a <b>nested document</b> is materialized under deeper sub-paths, leaving the addressed
     *       path's own value empty, so {@code json_extract_string} over an {@code object} field yields
     *       the empty string (the type default) where the text form yields {@code {"a":1}};</li>
     *   <li>an <b>array</b> is materialized as an array value, which {@code toString} renders in
     *       ClickHouse's array syntax rather than JSON's — {@code ['p','q']} for a stored
     *       {@code ["p","q"]}, {@code ['1','q']} for {@code [1,"q"]}, {@code [NULL,1]} for
     *       {@code [null,1]}, {@code ['{"x":"1"}']} for {@code [{"x":1}]}. The two spellings coincide
     *       only where the two syntaxes do — an all-integer, null-free array such as {@code [1,2]} —
     *       which is a coincidence of shape, not a guarantee.</li>
     * </ul>
     *
     * <p>Serializing the member back would not close the gap either: ClickHouse re-serializes a
     * {@code JSON} sub-object from its typed paths, so member order and even value spelling
     * ({@code {"a":"1"}} for a stored {@code {"a":1}}) differ from the source text. A composite member
     * is therefore <b>not addressable as text</b> through this DSL — it is read by addressing its own
     * members, which is what the path arguments are for. The other three functions are unaffected:
     * they return their type default for a composite member on both sides.
     */
    private Field<?> jsonExtract(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        if (args.size() < 2) {
            throw new QueryValidationException("function '" + fn
                    + "' expects a JSON expression plus at least one path key");
        }
        // The case-insensitive extractor is the one member of the family that is not variadic: matching a
        // key without knowing its case is defined for a single top-level key, and a second segment would
        // have to be matched inside a sub-document this rendering never addresses. Refused for arity rather
        // than silently treated as a deeper path.
        if (CASE_INSENSITIVE_EXTRACT.equals(fn) && args.size() != 2) {
            throw new QueryValidationException("function '" + fn + "' expects exactly two arguments"
                    + " (json, key); case-insensitive matching is defined for a single top-level key");
        }
        final List<String> path = new ArrayList<>(args.size() - 1);
        for (int i = 1; i < args.size(); i++) {
            if (!(args.get(i) instanceof ValueExpr value) || value.valueType() != ValueType.STRING) {
                throw new QueryValidationException("function '" + fn
                        + "' requires string literal path keys after the JSON expression");
            }
            path.add(pathKey(fn, value));
        }
        final QueryFieldBinding object = objectFieldArgument(args.getFirst(), bindings);
        // The five path-addressable extractors read an object column through native subcolumn access.
        // json_extract_string_ci cannot (see documentSource): it reads the serialized text instead, so for
        // it an object argument only changes what feeds the same text rendering.
        if (object != null && !readsSerializedText(fn)) {
            return subcolumnExtract(fn, object, path);
        }
        return textExtract(fn, documentSource(fn, args.getFirst(), object, bindings), path);
    }

    /**
     * Whether {@code fn} reads an {@code object} column through its <b>serialized text</b> rather than
     * through subcolumn access. True for {@code json_extract_string_ci} alone — the one extractor whose
     * shape has no statically known subcolumn to address.
     */
    private static boolean readsSerializedText(String fn) {
        return JsonExtractFunctions.readsSerializedText(fn);
    }

    /**
     * The JSON document a text rendering reads: the argument itself when it yields JSON text, and
     * {@code toJSONString(<column>)} when it is an {@code object} field.
     *
     * <p><b>Passing the {@code object} column itself would be silently wrong.</b> Measured on ClickHouse
     * 26.3: {@code JSONExtractKeysAndValues(<JSON column>, 'String')} returns an <em>empty key set and
     * raises nothing</em>, so {@code json_extract_string_ci} over {@code request_tags} — an {@code object}
     * column, and the motivating case for the function — would return the empty string for every row with
     * no error anywhere to notice. {@code JSONExtract(<JSON column>, 'k', 'Array(String)')} at least fails
     * loudly ({@code ILLEGAL_TYPE_OF_ARGUMENT}). Neither is acceptable, and the serialization is what both
     * need.
     *
     * <p>Why this one cannot join the other five on subcolumn access, which reads ~20x less data: a
     * case-insensitive match has no statically known subcolumn to address — not knowing the key's spelling
     * is the entire point. {@code json_extract_array} used to share this fallback on the ground that an
     * array member comes back typed {@code Dynamic}; it now addresses that subcolumn and re-serializes it
     * ({@link #subcolumnArray}), which leaves this rendering with the one shape that genuinely needs the
     * whole document — the cost {@code json_extract_string_ci} already carries over a text column.
     */
    private Field<?> documentSource(
            String fn, Expr arg, QueryFieldBinding object, Map<String, QueryFieldBinding> bindings) {
        if (object == null) {
            return toField(arg, bindings);
        }
        // Unreachable through jsonExtract, which routes the other four to subcolumnExtract before calling
        // here. Kept because this method is the one place that decides an object column is read as text, and
        // a caller reaching it for one of those four would be taking the ~20x-wider read by accident.
        if (!readsSerializedText(fn)) {
            throw new IllegalStateException("json_extract function '" + fn + "' reads an object column"
                    + " through subcolumn access and must not reach the text rendering");
        }
        return DSL.function("toJSONString", String.class, object.field());
    }

    /**
     * One literal path key, validated for both renderings.
     *
     * <p><b>A backslash is rejected outright.</b> Over an {@code object} field the key becomes a quoted
     * identifier segment, and ClickHouse's identifier lexer honours C-style backslash escapes inside a
     * double-quoted identifier — {@code "a\""} closes the identifier just as {@code "a"""} does. jOOQ
     * doubles an embedded double quote (which is why a quote in a key is inert) but leaves a backslash
     * alone, so a key carrying one could terminate the identifier early and continue as SQL of its own.
     * A rendering must never depend on an escape the quoter does not perform, so the key is refused
     * before it can reach one.
     *
     * <p><b>An empty key is rejected for the same reason.</b> jOOQ's name building drops an empty
     * segment instead of rendering it ({@code AbstractName.append} returns the receiver unchanged when
     * the appended name is empty), so {@code json_extract_string(meta, '')} would render as
     * {@code toString("t"."meta")} — the whole serialized document rather than the empty-string type
     * default the text form returns — and {@code json_extract_long(meta, '', 'usage')} would silently
     * read {@code "t"."meta"."usage"}, a different path than the one asked for. Over a native
     * {@code JSON} column the dropped segment can also make the query fail outright:
     * {@code json_extract_bool}'s {@code dynamicType} rejects a {@code JSON} operand
     * ({@code ILLEGAL_TYPE_OF_ARGUMENT}) where the text form returns {@code false}. A segment that
     * disappears cannot address anything, so it is refused before it can.
     *
     * <p>Both refusals deliberately apply to the <b>text</b> rendering too, where the key is a
     * single-quoted literal jOOQ escapes correctly and nothing is at risk. The overload's contract is
     * that the two forms are interchangeable: were the key accepted over a {@code string} column and
     * refused over an {@code object} column, the same query would succeed or fail depending on how the
     * document happens to be stored — the one caller-visible difference this feature exists to avoid.
     * A JSON member name that is empty or contains a backslash is not addressable through this DSL;
     * nothing else narrows.
     */
    private String pathKey(String fn, ValueExpr value) {
        if (!(valueExprToObjectMapper.map(value) instanceof String key)) {
            throw new QueryValidationException("function '" + fn
                    + "' requires non-null string literal path keys after the JSON expression");
        }
        if (key.isEmpty()) {
            throw new QueryValidationException("function '" + fn
                    + "' path keys must not be empty");
        }
        if (key.indexOf('\\') >= 0) {
            throw new QueryValidationException("function '" + fn
                    + "' path keys must not contain a backslash");
        }
        return key;
    }

    /**
     * The {@code object}-typed field backing the JSON argument, or {@code null} when the argument is
     * anything else — including an unknown field name, which falls through to the text rendering so
     * {@code toField} raises the ordinary "unknown field" error rather than a type-specific one.
     */
    private QueryFieldBinding objectFieldArgument(Expr arg, Map<String, QueryFieldBinding> bindings) {
        if (!(arg instanceof FieldExpr(String name))) {
            return null;
        }
        final QueryFieldBinding binding = bindings.get(name);
        return binding != null && binding.type() == QueryFieldType.OBJECT ? binding : null;
    }

    /**
     * JSON <em>text</em> → the native ClickHouse {@code JSONExtract*} accessors. ClickHouse's own
     * semantics make these total: an absent path, a null value, or unparseable JSON yields the
     * extracting type's default rather than an error.
     */
    private Field<?> textExtract(String fn, Field<?> json, List<String> path) {
        if (CASE_INSENSITIVE_EXTRACT.equals(fn)) {
            return caseInsensitiveExtract(nonNullJsonText(json), path.getFirst());
        }
        final Field<?>[] fields = new Field<?>[path.size() + 1];
        fields[0] = json;
        for (int i = 0; i < path.size(); i++) {
            fields[i + 1] = DSL.val(path.get(i));
        }
        if (ArrayValuedFunctions.JSON_EXTRACT_ARRAY.equals(fn)) {
            fields[0] = nonNullJsonText(json);
            // The type name is a constant, never a caller value; every path key is a bound literal.
            final Field<?>[] withType = Arrays.copyOf(fields, fields.length + 1);
            withType[fields.length] = DSL.val("Array(String)");
            return DSL.function("JSONExtract", Object.class, withType);
        }
        return switch (fn) {
            case "json_extract_string" -> DSL.function("JSONExtractString", String.class, fields);
            case "json_extract_long" -> DSL.function("JSONExtractInt", Long.class, fields);
            case "json_extract_double" -> DSL.function("JSONExtractFloat", Double.class, fields);
            // JSONExtractBool yields UInt8 (1/0), which reads back as a number; toBool makes the value a
            // real ClickHouse Bool so it serializes as true/false, matching the advertised return type.
            case "json_extract_bool" -> DSL.field("toBool({0})", Boolean.class,
                    DSL.function("JSONExtractBool", Boolean.class, fields));
            default -> throw new IllegalStateException("unhandled json_extract function '" + fn + "'");
        };
    }

    /**
     * The JSON-text operand of the two <b>array-typed</b> extractions, pinned non-nullable — the text-column
     * counterpart of {@link #subcolumnArray}'s wrapper, and needed for the same reason. A text column read
     * across an outer join (the payload enrichment's {@code request_body}, joined {@code LEFT} onto
     * {@code dial_usage_log}) is typed {@code Nullable(String)}, and ClickHouse refuses
     * {@code JSONExtract(…, 'Array(String)')} and {@code JSONExtractKeysAndValues} over a {@code Nullable}
     * operand at analysis time: {@code Code 43: Nested type Array(String) cannot be inside Nullable type}.
     * The scalar extractors are unaffected — their result type may be {@code Nullable} — which is why only
     * these two are wrapped. Measured against ClickHouse 26.8 through the SQL surface's own examples.
     *
     * <p>{@code ifNull(…, '')} substitutes the empty document for an absent one, which extracts to exactly
     * what both functions promise for an absent path — the empty array, and the empty string after
     * {@code .2} — so the contract is unchanged; {@code assumeNotNull} pins the type, as in
     * {@link #subcolumnArray}. Over a non-nullable column both are no-ops.
     */
    private static Field<String> nonNullJsonText(Field<?> json) {
        return DSL.field("assumeNotNull(ifNull({0}, ''))", String.class, json);
    }

    /**
     * {@code json_extract_string_ci(json, key)} — {@code json_extract_string} with the JSON <b>key</b>
     * matched case-insensitively, for reading a header map whose producers disagree on capitalization.
     *
     * <p>Rendered as a first-match scan over the document's key/value pairs with the <em>keys</em> lowered:
     * {@code arrayFirst(kv -> kv.1 = '<key>', arrayMap(kv -> (lower(kv.1), kv.2), JSONExtractKeysAndValues(…,
     * 'String'))).2}. The search key is a literal, so it is lowered once here at translation time rather than
     * once per row.
     *
     * <p><b>Only the key is folded, never the value.</b> The obvious cheap alternative —
     * {@code JSONExtractString(lower(<json>), '<key>')} — lowercases the JSON <em>text</em>, which
     * lowercases values too and silently corrupts e-mail addresses, display names and any case-sensitive
     * session id. Cheap and wrong; {@code arrayFirst} over lowered keys is the version that returns the
     * stored value verbatim.
     *
     * <p>The cost is real and is the function's whole downside, and it is not per row: over an
     * {@code object} column ClickHouse stores one stream per JSON path per part, so materializing the whole
     * document opens paths x parts read buffers whatever the values weigh. Hundreds of paths across hundreds
     * of parts, for a column holding only a few hundred MiB in total, is enough to exhaust a 2 GiB
     * ceiling on buffers alone, for a window matching no rows at all. It is for keys whose case is
     * genuinely unknown, not a default.
     *
     * <p>Absence behaves as the rest of the family does: {@code arrayFirst} yields the tuple's zero value
     * when nothing matches, so {@code .2} is the empty string — the same answer {@code JSONExtractString}
     * gives for a missing key, with no error and no null.
     */
    private Field<?> caseInsensitiveExtract(Field<?> json, String key) {
        return DSL.field("arrayFirst(kv -> kv.1 = {1}, arrayMap(kv -> (lower(kv.1), kv.2),"
                        + " JSONExtractKeysAndValues({0}, 'String'))).2",
                String.class, json, DSL.val(key.toLowerCase(Locale.ROOT)));
    }

    /**
     * An {@code object} field → native JSON <b>subcolumn access</b> over the literal path, plus the
     * per-function coercion that reproduces the text form's result (the one documented exception, a
     * composite member — nested document or array — read as text, is in {@link #jsonExtract}'s
     * contract).
     *
     * <p>The text accessors cannot be reused here: ClickHouse rejects a native {@code JSON} operand
     * outright ({@code ILLEGAL_TYPE_OF_ARGUMENT}). Serializing the document back to text with
     * {@code toJSONString} would work but reads every member of every row; subcolumn access reads
     * only the addressed path, typically an order of magnitude less data.
     *
     * <p>The path is built as {@link DSL#name} identifier segments appended to the column's own
     * qualified name, so jOOQ quotes each key — a key is never spliced into SQL as text. jOOQ's
     * quoting doubles an embedded double quote but does not escape a backslash, which ClickHouse's
     * identifier lexer would honour as an escape; jOOQ also drops an empty segment rather than
     * rendering it. {@link #pathKey} refuses both kinds of key before they get here, so every key that
     * reaches this method renders as exactly one identifier segment.
     *
     * <p>Deliberately the <b>untyped</b> subcolumn plus {@code toString}, not typed access
     * ({@code .:String}): typed access is strict and yields NULL when the stored member's type
     * differs from the requested one, where the text accessors coerce. Using it would make the same
     * DSL query answer differently depending on physical storage.
     */
    private Field<?> subcolumnExtract(String fn, QueryFieldBinding binding, List<String> path) {
        final Field<?> value = subcolumn(binding, path);
        // json_extract_array is deliberately answered BEFORE `text` below, and must never be routed
        // through it. `toString` over an array subcolumn renders ClickHouse's array syntax, not JSON —
        // ['a','b'] for a stored ["a","b"] — and JSONExtract cannot parse single-quoted strings, so
        // JSONExtract(toString(<sub>), 'Array(String)') returns [] for every array carrying a quoted
        // string, a null or a composite element, while still returning the right answer for arrays of
        // bare integers, floats or booleans, whose two syntaxes coincide. That is a silent emptying of
        // the result, and it passes any spot check that avoids the three kinds that diverge:
        // over a column of string arrays such as jwt_claims.roles it agrees with the text form on no row
        // holding a quoted string (empty arrays agree). Reusing
        // the shared `text` field is the natural edit and is exactly this bug, so the array branch
        // takes `value` directly. See subcolumnArray for what it does instead.
        if (ArrayValuedFunctions.JSON_EXTRACT_ARRAY.equals(fn)) {
            return subcolumnArray(value);
        }
        final Field<String> text = DSL.function("toString", String.class, value);
        return switch (fn) {
            case "json_extract_string" -> text;
            case "json_extract_long" -> subcolumnLong(text);
            case "json_extract_double" -> DSL.function("toFloat64OrZero", Double.class, text);
            case "json_extract_bool" -> subcolumnBool(value);
            default -> throw new IllegalStateException("unhandled json_extract function '" + fn + "'");
        };
    }

    /**
     * {@code json_extract_array} over an {@code object} field — the addressed subcolumn re-serialized to
     * JSON text and extracted from, which reads one stream instead of one per path in the column.
     *
     * <p>The member is typed {@code Dynamic}, which is why this function was long classified as needing
     * the whole document. It does not: {@code toJSONString} over the <em>subcolumn</em> yields that
     * member's own JSON text, and {@code JSONExtract(…, 'Array(String)')} over it returns what the same
     * call returns over the whole document's text. Verified value-for-value against ClickHouse 26.3 —
     * every row and every non-empty result agreeing with the whole-document form, across arrays of strings, of numbers, of mixed types, of objects and of
     * nulls, plus a non-array member, a nested document, an explicit null, an absent path, a
     * multi-segment path, and a {@code LEFT JOIN} that did not match under {@code join_use_nulls=0}.
     * Cost over the same window is roughly three orders of magnitude lower in bytes read.
     *
     * <p><b>Both null wrappers are load-bearing, and for different reasons.</b> {@code toJSONString} over
     * a subcolumn is typed {@code Nullable(String)} — an absent path really is NULL — and
     * {@code JSONExtract} refuses a {@code Nullable} operand outright at analysis time with
     * {@code Code 43: Nested type Array(String) cannot be inside Nullable type}, the same refusal
     * {@code EntityFields} records for a {@code Nullable(String)} column. That un-wrapped form fails on
     * every version measured (26.3.21 and 26.3.24 alike). {@code ifNull} fixes the <em>value</em>: it
     * substitutes the empty string, which extracts to the empty array — what the whole-document form
     * returns for an absent path, and what this function's contract promises. {@code assumeNotNull} pins
     * the <em>type</em> rather than relying on the analyzer's {@code Nullable} inference, which has moved
     * across releases; both versions measured fold {@code ifNull(Nullable(String), '')} to {@code String}
     * and accept the extraction without it, so it is a cheap pin rather than a fix for an observed
     * failure. Since {@code ifNull} has already guaranteed a non-NULL value, {@code assumeNotNull} never
     * meets a NULL and its unspecified-on-NULL behaviour is never reached.
     */
    private Field<?> subcolumnArray(Field<?> value) {
        final Field<String> json = DSL.field("assumeNotNull(ifNull(toJSONString({0}), ''))", String.class, value);
        return DSL.function("JSONExtract", Object.class, json, DSL.val("Array(String)"));
    }

    /**
     * {@code json_extract_long} over an {@code object} field — {@code JSONExtractInt}'s conversion
     * reproduced over a JSON subcolumn.
     *
     * <p>A bare {@code toInt64OrZero} is not that conversion: it parses only wholly integral text,
     * where {@code JSONExtractInt} converts a <em>number</em> to an integer by truncating toward zero.
     * The two disagree on a member stored as the fractional number {@code 1.5} (the text form yields
     * {@code 1}, a bare {@code toInt64OrZero} yields {@code 0}) and equally on a member stored as the
     * <em>string</em> {@code "1.5"}, which the text accessor parses as a number too. So integral text is
     * read directly — the branch that matters for the ordinary case and for a value near the
     * {@code Int64} bounds, whose exact digits a {@code Float64} round trip would lose — and everything
     * else falls back to a float read truncated toward zero.
     *
     * <p>The fallback stays total, and matches the text form where the text form gives up: text that is
     * no number at all reads as {@code 0.0} and truncates to {@code 0}, and a magnitude outside
     * {@code Int64} renders back as digits {@code toInt64OrZero} refuses, which is again {@code 0} —
     * exactly what {@code JSONExtractInt} returns when the value does not fit. Verified case by case
     * against ClickHouse 25.3.14 and re-verified against 26.3.21 (integer, fractional and
     * negative-fractional numbers, numeric and non-numeric strings, booleans, arrays, nested documents,
     * explicit nulls, absent paths, and values at and above the {@code Int64}/{@code UInt64} bounds) —
     * every case agrees with the text form on both versions. The two servers store an above-{@code Int64}
     * integer differently ({@code Float64} on 25.3, {@code UInt64} on 26.3), which this overload survives:
     * both spellings render back as digits {@code toInt64OrZero} refuses, so both yield {@code 0} — what
     * the text form returns.
     */
    private Field<?> subcolumnLong(Field<String> text) {
        return DSL.field("ifNull(toInt64OrNull({0}), toInt64OrZero(toString(trunc(toFloat64OrZero({0})))))",
                Long.class, text);
    }

    /**
     * {@code json_extract_bool} over an {@code object} field — {@code JSONExtractBool}'s coercion
     * reproduced over a JSON subcolumn, which is what keeps the overload observationally identical to
     * the text form.
     *
     * <p>Reading the member's text and comparing it to {@code 'true'} is not that coercion, and the
     * divergence runs both ways: a member stored as the number {@code 1} is {@code true} to
     * {@code JSONExtractBool} but renders {@code '1'}, while a member stored as the <em>string</em>
     * {@code "true"} is {@code false} to it yet renders {@code 'true'}. So the stored type is consulted
     * ({@code dynamicType}) and each branch reproduces what the text accessor does with that type: a
     * {@code Bool} member yields its own value, an integer member yields {@code value != 0}, and
     * everything else — a fractional number included, which {@code JSONExtractBool} also reads as
     * {@code false} — yields {@code false}. Verified case by case against ClickHouse 25.3.14 and
     * re-verified against 26.3.21.
     *
     * <p><b>One divergence, on 25.3 only.</b> {@code dynamicType} reports the type the server
     * <em>stored</em>, not the type the document wrote: an integer above the {@code Int64} bound (verified
     * at {@code 9223372036854775808} and at the {@code UInt64} maximum) is stored as {@code Float64} on
     * 25.3 and as {@code UInt64} from 26.3, so 26.3 takes the integer branch and agrees with the text
     * form's {@code true} while 25.3 falls through to {@code false}. {@code structured-query} exempts the
     * {@code JSON} type's own storage choices across server versions, so this is recorded rather than
     * papered over: catching it would mean reading some {@code Float64} members as integers, which breaks
     * the fractional cases this rendering exists to get right.
     *
     * <p>The value is reached by comparison rather than by casting the path's text: {@code toBool} over
     * an absent path's empty string throws {@code CANNOT_PARSE_BOOL}, which would break the totality
     * guarantee. The outer {@code toBool} is still required — a comparison renders as ClickHouse
     * {@code UInt8}, exactly as {@code JSONExtractBool} does, and without the wrap the advertised
     * boolean would serialize as {@code 1}/{@code 0}. It receives the comparison's {@code UInt8}, never
     * the raw path text.
     */
    private Field<?> subcolumnBool(Field<?> value) {
        return DSL.field("toBool(multiIf("
                        + "dynamicType({0}) = 'Bool', toString({0}) = 'true', "
                        + "dynamicType({0}) in (" + INTEGER_DYNAMIC_TYPES + "), toString({0}) != '0', "
                        + "false))",
                Boolean.class, value);
    }

    /** {@code "table"."column"."key1"."key2"} — the JSON subcolumn addressed by the literal path. */
    private Field<?> subcolumn(QueryFieldBinding binding, List<String> path) {
        // A pruned column is not a column of the derived table any more — only the paths it projected are,
        // each under the single dotted identifier ObjectPathUsage.alias spells. Addressing it as nested
        // segments would name something nothing projects.
        if (binding.prunedPaths().contains(path)) {
            final String[] parts = binding.field().getQualifiedName().getName().clone();
            final int last = parts.length - 1;
            parts[last] = ObjectPathUsage.alias(parts[last], path);
            return DSL.field(DSL.name(parts));
        }
        return ObjectPathUsage.subcolumn(binding.field(), path);
    }

    /**
     * {@code width_bucket(operand, low, high, count)} — the histogram bucket (1..count) operand falls
     * into across {@code count} equal-width buckets spanning {@code [low, high)}; {@code 0} below
     * {@code low} and {@code count + 1} at or above {@code high}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Field<?> widthBucket(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        if (args.size() != 4) {
            throw new QueryValidationException(
                    "function '" + fn + "' expects exactly four arguments (operand, low, high, count)");
        }
        final Field operand = toField(args.get(0), bindings);
        final Field low = toField(args.get(1), bindings);
        final Field high = toField(args.get(2), bindings);
        // ClickHouse's widthBucket requires the bucket count to be unsigned (UInt8/16/32/64). A jOOQ
        // cast(Integer) renders a signed Int32, which ClickHouse rejects (ILLEGAL_TYPE_OF_ARGUMENT), so
        // coerce the count with toUInt32(...) instead.
        final Field<Integer> count = DSL.field("toUInt32({0})", Integer.class, toField(args.get(3), bindings));
        return DSL.widthBucket(operand, low, high, count);
    }

    /**
     * {@code date_bin(amount, unit, timestamp)} — truncates {@code timestamp} down to the start of the
     * fixed-width interval {@code amount × unit}, aligned to the epoch origin so bucket boundaries fall
     * on clean {@code :00}-second marks (a value at {@code 12:03} yields {@code 12:00} for a 5-minute
     * bin). {@code amount} must be a positive integer literal and {@code unit} one of
     * {@link #DATE_BIN_UNITS}. Renders to native ClickHouse
     * {@code toStartOfInterval(timestamp, INTERVAL amount unit, 'UTC')} — {@code amount} is inlined as a
     * validated integer and the unit as an allowlisted keyword, so there is no injection surface.
     *
     * <p><b>The {@code 'UTC'} argument is not decoration.</b> Without it, {@code toStartOfInterval} aligns
     * in the time zone of the operand's own type, and a {@code DateTime64} declared without one resolves to
     * the <em>ClickHouse server's</em> zone — deployment configuration ADAS does not own. The same bucket
     * declaration would then cut days at a different instant on two differently-configured servers, and a
     * server-zone change would silently re-align every future bucket while leaving already-materialized
     * ones on the old boundary, mixing two alignments in one column with nothing to flag it. Output is
     * rendered from the bucket start, so both alignments print an identical {@code 00:00:00} and the
     * divergence is invisible in the result. Pinning UTC also fixes the step arithmetic downstream: the
     * pinned call is typed {@code DateTime('UTC')}, so {@code + INTERVAL 1 <unit>} on its result (the
     * aggregate pipeline's bucket bound) advances a true 24 hours rather than a DST-shortened local day.
     *
     * <p>UTC is the only alignment ADAS offers, and it matches both ends of the pipe: {@code dial_usage_log}
     * declares its timestamps {@code DateTime64(3, 'UTC')}, and the read path ({@code ArrowBatchWriter})
     * converts a zoneless {@code LocalDateTime} to epoch millis at UTC. A caller-chosen bucket zone would
     * be an additive change (a fourth argument), not a change to this default.
     */
    private Field<?> dateBin(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        if (args.size() != 3) {
            throw new QueryValidationException(
                    "function '" + fn + "' expects exactly three arguments (amount, unit, timestamp)");
        }
        final int amount = dateBinAmount(fn, args.get(0));
        final String keyword = dateBinUnit(fn, args.get(1));
        final Field<?> operand = toField(args.get(2), bindings);
        return DSL.field("toStartOfInterval({0}, INTERVAL " + amount + " " + keyword + ", 'UTC')",
                Object.class, operand);
    }

    /**
     * {@code date_trunc(unit, timestamp)} — truncates {@code timestamp} down to the start of the calendar
     * period named by {@code unit}: a {@code month} to midnight on the 1st, a {@code week} to Monday (ISO),
     * a {@code quarter} to the first day of the quarter. {@code unit} must be a string literal from
     * {@link #DATE_TRUNC_UNITS}. Renders to native ClickHouse
     * {@code toStartOfInterval(timestamp, INTERVAL 1 unit, 'UTC')} — the unit is an allowlisted keyword, so
     * there is no injection surface.
     *
     * <p><b>No amount argument</b>, unlike {@link #dateBin}. A period of <em>n</em> calendar months has no
     * fixed width, so nothing defines where the grid of such periods starts; and {@code 3} would not be
     * {@code quarter}, which is aligned to the calendar quarter rather than to an arbitrary origin.
     * Omitting the argument keeps every call unambiguous, and {@code date_bin} still serves "every 90 days".
     *
     * <p><b>The {@code 'UTC'} argument carries the same weight as in {@link #dateBin}</b> — see that method
     * for why alignment must not be left to the operand's declared zone. It matters once more here: the
     * pinned call is typed {@code DateTime('UTC')}, so the aggregate pipeline's bucket bound
     * ({@code + INTERVAL 1 MONTH} on this result) steps a true calendar month in the same zone the
     * truncation used.
     */
    private Field<?> dateTrunc(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        if (args.size() != 2) {
            throw new QueryValidationException(
                    "function '" + fn + "' expects exactly two arguments (unit, timestamp)");
        }
        final String keyword = dateTruncUnit(fn, args.get(0));
        final Field<?> operand = toField(args.get(1), bindings);
        return DSL.field("toStartOfInterval({0}, INTERVAL 1 " + keyword + ", 'UTC')", Object.class, operand);
    }

    /**
     * {@code now()} — the current instant, rendered as ClickHouse {@code now64(3, 'UTC')}.
     *
     * <p><b>Both arguments of that call are load-bearing.</b> The precision {@code 3} matches the
     * millisecond timestamps this service stores ({@code dial_usage_log.request_time} is
     * {@code DateTime64(3, 'UTC')}); the second-precision {@code now()} would compare a coarser value
     * against a finer column, which at a window edge silently includes or drops up to a second of rows. The
     * {@code 'UTC'} does not change the instant — an instant has no zone — but it does fix the result's
     * <em>type</em>, and the type is what downstream calendar arithmetic aligns by: without it the value
     * resolves to the <em>ClickHouse server's</em> configured zone, so {@code date_trunc('day', now())} would
     * cut the day at whatever local midnight that server happens to keep, and a server-zone change would
     * silently re-align every future window. This is the same argument {@link #dateBin} makes at length for
     * its own {@code 'UTC'}; {@code now()} joins that convention rather than introducing a second one.
     *
     * <p><b>No zone argument is accepted</b>, so a call carrying anything at all is an arity error. UTC is
     * the only alignment this surface offers, and a caller-chosen zone would be an additive change here and
     * in six other places rather than a parameter this one function can honour alone.
     *
     * <p>ClickHouse evaluates each {@code now64} <em>call site</em> once per statement, so one call is the same
     * value in every row. Separate call sites are evaluated separately and may straddle a millisecond tick
     * (measured on 26.3: 7 in 300 runs, with and without {@code FINAL}), so a window's two bounds written as
     * two calls may be a millisecond apart. That is deliberately left as ClickHouse has it: pinning one
     * instant here — a literal, an injected clock, a parameter — would make a query behave unlike the same
     * SQL sent to ClickHouse, and would read this service's clock instead of the store's.
     */
    private Field<?> now(String fn, List<Expr> args) {
        if (!args.isEmpty()) {
            throw new QueryValidationException("function '" + fn + "' takes no arguments"
                    + " (there is no zone parameter: the value is UTC)");
        }
        return DSL.field("now64(3, 'UTC')", Object.class);
    }

    /**
     * {@code date_add(unit, amount, timestamp)} / {@code date_sub(unit, amount, timestamp)} — the operand
     * moved forward or back by {@code amount × unit}, rendered as native ClickHouse
     * {@code (<operand> ± INTERVAL amount unit)}.
     *
     * <p><b>The name and argument order are borrowed from ClickHouse's own</b>
     * {@code dateAdd(unit, value, date)} / {@code date_add(unit, value, date)} — verified against
     * clickhouse-server 26.3, where {@code date_add(HOUR, 2, now())} evaluates. The borrowing is not exact:
     * ClickHouse takes the unit as a <em>bare interval keyword</em>, while this DSL takes a <b>string
     * literal</b> ({@code date_add('hour', 2, …)}), which is the convention every other unit-taking
     * function here follows ({@code date_trunc('day', …)}, {@code date_bin(5, 'minute', …)}) because the DSL
     * has no keyword type. So {@code date_add('hour', 2, ts)} is <em>not</em> itself valid ClickHouse — with
     * a quoted unit the engine resolves {@code date_add} to its two-argument {@code plus} alias and fails on
     * arity. Nothing depends on that, because this method never emits the function: it emits the infix
     * {@code INTERVAL} form, which is native. What the borrowed order buys is only that a reader fluent in
     * one surface reads the other without reordering arguments in their head.
     *
     * <p>It differs from {@link #dateBin}'s {@code (amount, unit, timestamp)}, which mirrors nothing and is
     * simply older.
     *
     * <p><b>{@code amount} must be positive.</b> Direction is carried by the function name, so a negative
     * amount would give one offset two spellings — {@code date_add('hour', -2, …)} and
     * {@code date_sub('hour', 2, …)} — and the SQL round trip would have to pick one anyway. Rejecting it
     * here is what makes that normalization total: {@code RexToExprTranslator} folds a signed interval into
     * the matching function name, and there is no second document for it to produce.
     *
     * <p>Rendering is parenthesised unconditionally. {@code INTERVAL} binds loosely enough that
     * {@code a - INTERVAL 1 DAY > b} and a nested offset both need the parentheses, they are harmless at the
     * top level, and there is no precedence table to get wrong. The unit reaches the SQL as an allowlisted
     * keyword from {@link #DATE_OFFSET_UNITS} and the amount as a validated {@code int}, so neither is an
     * injection surface — the same construction {@link #dateBin} uses.
     */
    private Field<?> dateOffset(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings, String operator) {
        if (args.size() != 3) {
            throw new QueryValidationException(
                    "function '" + fn + "' expects exactly three arguments (unit, amount, timestamp)");
        }
        final String keyword = dateOffsetUnit(fn, args.get(0));
        final int amount = dateOffsetAmount(fn, args.get(1));
        final Field<?> operand = toField(args.get(2), bindings);
        return DSL.field("({0} " + operator + " INTERVAL " + amount + " " + keyword + ")", Object.class, operand);
    }

    private String dateOffsetUnit(String fn, Expr arg) {
        if (!(arg instanceof ValueExpr value) || !(valueExprToObjectMapper.map(value) instanceof String unit)) {
            throw new QueryValidationException(
                    "function '" + fn + "' requires a string literal unit as its first argument");
        }
        final String keyword = DATE_OFFSET_UNITS.get(unit.trim().toLowerCase(Locale.ROOT));
        if (keyword == null) {
            // week and quarter are named explicitly: they are accepted by date_trunc and by the SQL surface's
            // INTERVAL form, so "not one of [...]" alone would read as an oversight rather than as a
            // redirection to the exact equivalent.
            throw new QueryValidationException("function '" + fn + "' unit must be one of "
                    + QueryFunctionCatalog.DATE_OFFSET_ALLOWED_UNITS + ", got '" + unit + "'"
                    + " (as an offset, a week is exactly 7 'day' and a quarter exactly 3 'month')");
        }
        return keyword;
    }

    private int dateOffsetAmount(String fn, Expr arg) {
        if (!(arg instanceof ValueExpr value) || !(valueExprToObjectMapper.map(value) instanceof Integer amount)) {
            throw new QueryValidationException(
                    "function '" + fn + "' requires an integer literal amount as its second argument");
        }
        if (amount <= 0) {
            throw new QueryValidationException("function '" + fn + "' amount must be a positive integer"
                    + " (the direction is carried by the function name: use date_add or date_sub)");
        }
        return amount;
    }

    private String dateTruncUnit(String fn, Expr arg) {
        if (!(arg instanceof ValueExpr value) || !(valueExprToObjectMapper.map(value) instanceof String unit)) {
            throw new QueryValidationException(
                    "function '" + fn + "' requires a string literal unit as its first argument");
        }
        final String keyword = DATE_TRUNC_UNITS.get(unit.trim().toLowerCase(Locale.ROOT));
        if (keyword == null) {
            throw new QueryValidationException(
                    "function '" + fn + "' unit must be one of " + DATE_TRUNC_UNITS.keySet() + ", got '" + unit + "'");
        }
        return keyword;
    }

    private int dateBinAmount(String fn, Expr arg) {
        if (!(arg instanceof ValueExpr value) || !(valueExprToObjectMapper.map(value) instanceof Integer amount)) {
            throw new QueryValidationException(
                    "function '" + fn + "' requires an integer literal amount as its first argument");
        }
        if (amount <= 0) {
            throw new QueryValidationException("function '" + fn + "' amount must be a positive integer");
        }
        return amount;
    }

    private String dateBinUnit(String fn, Expr arg) {
        if (!(arg instanceof ValueExpr value) || !(valueExprToObjectMapper.map(value) instanceof String unit)) {
            throw new QueryValidationException(
                    "function '" + fn + "' requires a string literal unit as its second argument");
        }
        final String keyword = DATE_BIN_UNITS.get(unit.trim().toLowerCase(Locale.ROOT));
        if (keyword == null) {
            throw new QueryValidationException(
                    "function '" + fn + "' unit must be one of " + DATE_BIN_UNITS.keySet() + ", got '" + unit + "'");
        }
        return keyword;
    }

    /**
     * {@code percentile_cont(fraction, column)} / {@code percentile_disc(fraction, column)} — the
     * ordered-set aggregate {@code percentile_(cont|disc)(fraction) WITHIN GROUP (ORDER BY column)}.
     * {@code fraction} must be a numeric literal in {@code [0, 1]}; {@code column} is any resolvable
     * field expression. {@code cont} interpolates between adjacent values; {@code disc} returns an
     * actual member.
     */
    private Field<?> percentile(
            String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings, boolean continuous) {
        if (args.size() != 2) {
            throw new QueryValidationException("function '" + fn + "' expects exactly two arguments (fraction, column)");
        }
        final Field<BigDecimal> fraction = DSL.val(percentileFraction(fn, args.getFirst()));
        final Field<?> orderField = toField(args.get(1), bindings);
        return continuous
                ? DSL.percentileCont(fraction).withinGroupOrderBy(orderField)
                : DSL.percentileDisc(fraction).withinGroupOrderBy(orderField);
    }

    /**
     * {@code group_uniq_array(value)} — the distinct non-null values of {@code value} over the group,
     * collected into an array. Renders to ClickHouse
     * {@code arraySort(groupUniqArray(value))}.
     *
     * <p><b>The {@code arraySort} is not cosmetic.</b> {@code groupUniqArray} builds its result from a hash
     * set, so element order is an implementation detail that varies with insertion order, thread count and
     * part layout — two runs over the identical rows can emit the same set in different orders. The
     * aggregate pipeline's idempotence rests on the opposite property: {@code AggregateCompiler} recomputes
     * whole keys and lets the {@code ReplacingMergeTree} target collapse them, which is only a no-op when a
     * re-run writes the <em>same</em> value for a key. Unsorted, every refresh would look like a change to
     * every key it touched, and two readers comparing a conversation's array against a stored copy would
     * disagree for no reason. Sorting makes the value a function of the set alone.
     *
     * <p>NULLs are skipped (ClickHouse aggregate semantics), so a group holding only NULLs yields an empty
     * array rather than NULL — which is what lets an array measure land in a target column, since ClickHouse
     * has no {@code Nullable(Array)}. "No values" and "the empty set" are the same fact here, unlike the
     * numeric measures where {@code AggregateSpecValidator} insists a nullable input keeps a nullable target.
     */
    private Field<?> groupUniqArray(Field<?> operand) {
        return DSL.field("arraySort(groupUniqArray({0}))", Object.class, operand);
    }

    /**
     * {@code arg_max(value, order)} — the {@code value} of the group's row bearing the greatest
     * {@code order}. Renders to native ClickHouse {@code argMax(value, order)}.
     *
     * <p><b>Hand-built template, not a jOOQ aggregate.</b> jOOQ has no {@code argMax}, and it does not
     * need one: the function name is emitted directly, exactly as {@link #groupUniqArray} emits
     * {@code groupUniqArray} and as {@code AggregateRefreshService} already emits {@code argMax} for its
     * cursor arithmetic. So this rendering is deterministic and is <em>not</em> subject to the
     * experimental-ClickHouse-dialect caveat that covers the ordered-set aggregates and
     * {@code width_bucket} — nothing here asks the dialect to know the function.
     *
     * <p>Semantics are ClickHouse's, pinned by the structured-query spec rather than re-implemented, and
     * verified against a live server (25.3, re-verified on 26.3 — unchanged) rather than assumed — the
     * assumption was wrong. A row competes only when <b>both</b> operands are non-null: a null
     * {@code order} is not ranked, and a null {@code value} is <em>skipped</em> rather than winning with
     * a null. So the result is the latest <b>non-null</b> value, not the value of the latest row, and it
     * is null only when no row of the group qualifies — which is why anything bound to it must be
     * nullable. A tie on the greatest
     * {@code order} returns an unspecified one of the tied values, so a caller needing determinism must
     * order by a column unique within the group.
     *
     * <p>{@code distinct} is refused rather than ignored: deduplicating {@code value} before the function
     * runs would sever the pairing between a value and the order that selects it, leaving "some value,
     * from a set, ordered by something that no longer corresponds to it". That is meaningless rather than
     * merely lossy, unlike {@code min}/{@code max}, where the quantifier is a harmless no-op.
     */
    private Field<?> argMax(
            String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings, boolean distinct) {
        if (args.size() != 2) {
            throw new QueryValidationException(
                    "function '" + fn + "' expects exactly two arguments (value, order)");
        }
        if (distinct) {
            throw new QueryValidationException("'" + fn + "' does not accept 'distinct'; deduplicating the "
                    + "values would detach a value from the order that selects it");
        }
        final Field<?> value = toField(args.getFirst(), bindings);
        final Field<?> order = toField(args.get(1), bindings);
        return DSL.field("argMax({0}, {1})", Object.class, value, order);
    }

    private BigDecimal percentileFraction(String fn, Expr arg) {
        if (!(arg instanceof ValueExpr value)) {
            throw new QueryValidationException(
                    "function '" + fn + "' requires a numeric literal fraction as its first argument");
        }
        if (!(valueExprToObjectMapper.map(value) instanceof Number number)) {
            throw new QueryValidationException("function '" + fn + "' fraction must be a numeric literal");
        }
        final BigDecimal fraction = new BigDecimal(number.toString());
        if (fraction.compareTo(BigDecimal.ZERO) < 0 || fraction.compareTo(BigDecimal.ONE) > 0) {
            throw new QueryValidationException("function '" + fn + "' fraction must be within [0, 1]");
        }
        return fraction;
    }
    // ------------------------------------------------------------------ case / window / subquery

    /**
     * {@code case} — the first branch whose condition holds supplies the value, {@code else} (or null)
     * otherwise. Rendered as standard {@code CASE WHEN … THEN … [ELSE …] END}, which ClickHouse evaluates as
     * {@code multiIf}.
     *
     * <p>The conditions are <em>filter</em> trees, rendered by a {@link FilterTranslator} over this very
     * translator — the one expression position where the filter grammar is admitted, because SQL
     * {@code CASE WHEN a > b} has no boolean-function spelling. The branches unify under {@code if}'s rule
     * ({@link #unifyTypes}), extended pairwise across every branch. Unlike {@code if}, a null-literal branch
     * is legal here — {@code NULLIF} lowers to one, and a {@code CASE} with no {@code ELSE} already yields null
     * — as long as at least one branch has a type for the result to take; an <em>absent</em> {@code else} is
     * the SQL default and renders no {@code ELSE} at all.
     */
    private Field<?> conditionalCase(CaseExpr caseExpr, Map<String, QueryFieldBinding> bindings) {
        final List<CaseBranch> branches = caseExpr.when() == null ? List.of() : caseExpr.when();
        if (branches.isEmpty()) {
            throw new QueryValidationException("'case' requires at least one 'when' branch");
        }
        final FilterTranslator filters = new FilterTranslator(this);
        QueryFieldType type = null;
        CaseConditionStep<Object> step = null;
        for (final CaseBranch branch : branches) {
            if (branch == null || branch.when() == null || branch.then() == null) {
                throw new QueryValidationException("each 'case' branch needs a 'when' condition and a 'then' value");
            }
            type = unifyBranch(type, branch.then(), bindings, "then");
            final Condition condition = filters.toCaseCondition(branch.when(), bindings);
            final Field<Object> value = branchValue(branch.then(), bindings);
            step = step == null ? DSL.when(condition, value) : step.when(condition, value);
        }
        if (caseExpr.otherwise() != null) {
            type = unifyBranch(type, caseExpr.otherwise(), bindings, "else");
        }
        if (type == null) {
            throw new QueryValidationException("'case' needs at least one branch that is not a null literal");
        }
        if (caseExpr.otherwise() == null) {
            return DSL.field("{0}", JooqTypes.javaType(type), step);
        }
        return DSL.field("{0}", JooqTypes.javaType(type), step.otherwise(branchValue(caseExpr.otherwise(), bindings)));
    }

    /** The running branch type, unchanged by a null literal, unified with any other branch. */
    private static QueryFieldType unifyBranch(
            QueryFieldType type, Expr branch, Map<String, QueryFieldBinding> bindings, String position) {
        if (isNullLiteral(branch)) {
            return type;
        }
        final QueryFieldType branchType = requireComparableType("case", "branch", branch, bindings, position);
        return type == null ? branchType : unifyTypes("case", "branches", type, branchType);
    }

    @SuppressWarnings("unchecked")
    private Field<Object> branchValue(Expr branch, Map<String, QueryFieldBinding> bindings) {
        if (isNullLiteral(branch)) {
            return DSL.inline((Object) null);
        }
        return (Field<Object>) toField(branch, bindings);
    }

    private static boolean isNullLiteral(Expr expr) {
        return expr instanceof ValueExpr value && value.valueType() == ValueType.NULL;
    }

    /**
     * {@code window} — {@code <fn> OVER (PARTITION BY … ORDER BY … [frame])}. The function is either a
     * ranking function ({@link #RANKING_FUNCTIONS}, no arguments) or one of {@link #WINDOW_AGGREGATES},
     * rendered by the very same branch of {@link #toFunction} that renders it as a plain aggregate, so an
     * aggregate under {@code OVER} cannot render differently from the aggregate itself. The {@code OVER}
     * clause is assembled as a template so the frame keywords are emitted verbatim; the frame is omitted
     * when absent, leaving the engine's standard default (the same default PostgreSQL applies).
     *
     * <p>Where a window may appear is {@link StructuredQueryBuilder}'s rule; here only the shape is checked:
     * a window nested in a window, a {@code distinct} quantifier, a non-window function, and a frame bound
     * missing or carrying an offset it must not have are each refused by name.
     */
    private Field<?> window(WindowExpr window, Map<String, QueryFieldBinding> bindings) {
        final FnExpr fn = window.fn();
        if (fn == null || fn.name() == null) {
            throw new QueryValidationException("a 'window' expression requires a 'fn' to apply over the window");
        }
        final String name = fn.name().toLowerCase(Locale.ROOT);
        if (fn.distinct()) {
            throw new QueryValidationException("'" + fn.name() + "' does not accept 'distinct' inside a window");
        }
        WindowExpressions.rejectNested(fn, "a window function's arguments");
        final Field<?> function;
        if (RANKING_FUNCTIONS.contains(name)) {
            if (fn.args() != null && !fn.args().isEmpty()) {
                throw new QueryValidationException("function '" + fn.name() + "' takes no arguments");
            }
            function = DSL.field(name + "()", Long.class);
        } else if (WINDOW_AGGREGATES.contains(name)) {
            function = toFunction(fn, bindings);
        } else {
            throw new QueryValidationException("function '" + fn.name() + "' cannot be used as a window function;"
                    + " supported: row_number, rank, dense_rank, count, sum, avg, min, max");
        }
        final StringBuilder template = new StringBuilder("{0} OVER (");
        final List<QueryPart> parts = new ArrayList<>();
        parts.add(function);
        final List<Expr> partitionBy = window.partitionBy() == null ? List.of() : window.partitionBy();
        if (!partitionBy.isEmpty()) {
            final List<Field<?>> keys = new ArrayList<>(partitionBy.size());
            for (final Expr key : partitionBy) {
                WindowExpressions.rejectNested(key, "PARTITION BY");
                keys.add(toField(key, bindings));
            }
            template.append("PARTITION BY {").append(parts.size()).append('}');
            parts.add(DSL.list(keys));
        }
        final List<WindowOrder> orderBy = window.orderBy() == null ? List.of() : window.orderBy();
        if (!orderBy.isEmpty()) {
            final List<SortField<?>> keys = new ArrayList<>(orderBy.size());
            for (final WindowOrder order : orderBy) {
                if (order == null || order.expr() == null) {
                    throw new QueryValidationException("a window 'order_by' entry requires an 'expr'");
                }
                WindowExpressions.rejectNested(order.expr(), "a window ORDER BY");
                final Field<?> key = toField(order.expr(), bindings);
                SortField<?> sortField = order.dir() == SortDir.DESC ? key.desc() : key.asc();
                if (order.nulls() != null) {
                    sortField = switch (order.nulls()) {
                        case FIRST -> sortField.nullsFirst();
                        case LAST -> sortField.nullsLast();
                    };
                }
                keys.add(sortField);
            }
            if (!partitionBy.isEmpty()) {
                template.append(' ');
            }
            template.append("ORDER BY {").append(parts.size()).append('}');
            parts.add(DSL.list(keys));
        }
        if (window.frame() != null) {
            if (!partitionBy.isEmpty() || !orderBy.isEmpty()) {
                template.append(' ');
            }
            appendFrame(template, parts, window.frame());
        }
        template.append(')');
        return DSL.field(template.toString(), function.getType(), parts.toArray());
    }

    private static void appendFrame(StringBuilder template, List<QueryPart> parts, WindowFrame frame) {
        if (frame.unit() == null || frame.start() == null || frame.end() == null) {
            throw new QueryValidationException("a window 'frame' requires 'unit', 'start' and 'end'");
        }
        template.append(frame.unit() == FrameUnit.ROWS ? "ROWS" : "RANGE").append(" BETWEEN ");
        appendBound(template, parts, frame.start());
        template.append(" AND ");
        appendBound(template, parts, frame.end());
    }

    private static void appendBound(StringBuilder template, List<QueryPart> parts, FrameBound bound) {
        if (bound.kind() == null) {
            throw new QueryValidationException("a window frame bound requires a 'kind'");
        }
        final boolean offsetBound = bound.kind() == FrameBoundKind.PRECEDING || bound.kind() == FrameBoundKind.FOLLOWING;
        if (offsetBound && (bound.offset() == null || bound.offset() < 0)) {
            throw new QueryValidationException("a '" + bound.kind().code() + "' frame bound requires a non-negative 'offset'");
        }
        if (!offsetBound && bound.offset() != null) {
            throw new QueryValidationException("a '" + bound.kind().code() + "' frame bound takes no 'offset'");
        }
        switch (bound.kind()) {
            case UNBOUNDED_PRECEDING -> template.append("UNBOUNDED PRECEDING");
            case CURRENT_ROW -> template.append("CURRENT ROW");
            case UNBOUNDED_FOLLOWING -> template.append("UNBOUNDED FOLLOWING");
            case PRECEDING -> {
                template.append('{').append(parts.size()).append("} PRECEDING");
                parts.add(DSL.inline(bound.offset()));
            }
            case FOLLOWING -> {
                template.append('{').append(parts.size()).append("} FOLLOWING");
                parts.add(DSL.inline(bound.offset()));
            }
            default -> throw new IllegalStateException("unknown frame bound kind " + bound.kind());
        }
    }

    /** A scalar subquery, rendered by the composite renderer this translator was given (or refused). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Field<?> scalarSubquery(ScalarSubqueryExpr scalar) {
        if (scalar.subquery() == null) {
            throw new QueryValidationException("a scalar subquery requires a query");
        }
        final Select select = subqueries.render(scalar.subquery());
        return DSL.field(select);
    }
}
