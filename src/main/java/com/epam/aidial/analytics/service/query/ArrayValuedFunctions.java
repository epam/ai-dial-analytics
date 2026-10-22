package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FnExpr;

import java.util.Locale;
import java.util.Set;

/**
 * The catalog functions whose result is an array rather than a scalar — {@code group_uniq_array}, the
 * array-valued aggregate, and the array-valued scalars {@code json_extract_array}, {@code map_keys},
 * {@code split_string} and {@code array_slice}. Everything that has
 * to recognize one by name resolves that name here: the DSL translator, the DSL→SQL renderer, the SQL→DSL
 * translator, and the aggregate pipeline's measure rules.
 *
 * <p>It also owns the <b>projection-only</b> rule for such a result, which is the array-field restriction one
 * level up: the DSL has no array literal and no comparison over an array, so an array result is expressible
 * only as a projection item. {@code SqlProjectionOnlyTypes} states the same rule for a declared
 * {@code array} column, and cannot state this one — that classification is keyed on a column's declared
 * type, and a computed result has none.
 *
 * <p>Enforced on both surfaces deliberately. The SQL front end rejects the nesting through its own result
 * typing ({@code RelToStructuredQueryTranslator}), so without the guard here the DSL would accept a query
 * whose rendered SQL the SQL front end refuses — the round-trip asymmetry that
 * {@code StructuredQueryToSqlRenderer} exists to prevent.
 *
 * <p><b>A set rather than a single name</b> since {@code json_extract_array} joined it: the functions
 * share nothing but the shape of their result, and that shape is exactly what this rule is keyed on. A
 * further array-returning function is covered by adding its name to {@link #NAMES}, with no new guard beside
 * this one and no call site to revisit.
 *
 * <p><b>The projection-only rule has exactly one hole, and it is positional.</b> The array-operand functions
 * — the accessors {@code array_has}, {@code array_has_any}, {@code array_has_all} and {@code array_length},
 * plus the row-expanding {@code array_join}, the text-rendering {@code array_to_string} and the narrowing
 * {@code array_slice} — take an array as their <em>first</em> argument, so an
 * array-valued call is a legal operand there and nowhere else, which is what makes
 * {@code array_length(map_keys(m))}, {@code array_has(split_string(t, ','), 'x')},
 * {@code array_join(split_string(t, ','))} and
 * {@code array_to_string(array_slice(group_uniq_array(x), 1, 6), ', ')} expressible.
 * {@link #acceptsArrayOperand} is the single expression of that hole, deliberately mirroring
 * {@code SqlProjectionOnlyTypes.isAllowedFunctionArgument}: index 0, a named function set. The two classes
 * answer for a computed array and a declared {@code array} column respectively, and must agree on the
 * position or a query legal on one surface is refused on the other.
 *
 * <p><b>{@code array_join} and {@code array_to_string} are governed here as consumers, never as
 * producers.</b> One yields an element of its operand and the other the operand rendered as text, so both
 * belong to {@link #ARRAY_OPERAND_FUNCTIONS} and must stay out of
 * {@link #NAMES}: their results are ordinary scalars carrying no projection-only restriction, which is
 * exactly what lets them be grouped by, sorted on and compared — the point of having them.
 * {@code array_slice} is the one function in both sets, and the {@link #NAMES} membership is the
 * load-bearing half: without it {@code concat(array_slice(a, 1, 2), 'x')} would render an array into a text
 * operand.
 */
public final class ArrayValuedFunctions {

    /** The array-valued aggregate's catalog (and wire) name, lowercase as every DSL function name is. */
    public static final String GROUP_UNIQ_ARRAY = "group_uniq_array";

    /**
     * {@code groupUniqArrayArray(array)} — ClickHouse's {@code -Array} combinator on {@code groupUniqArray}:
     * the group's arrays flattened into one and deduplicated. Carries ClickHouse's own camelCase name, being
     * new rather than an alias of anything shipped.
     */
    public static final String GROUP_UNIQ_ARRAY_ARRAY = "groupUniqArrayArray";

    /** {@code groupArrayArray(array)} — the same flattening, keeping duplicates and each row's order. */
    public static final String GROUP_ARRAY_ARRAY = "groupArrayArray";

    /** The array-valued scalar extractor's catalog (and wire) name. */
    public static final String JSON_EXTRACT_ARRAY = "json_extract_array";

    /** The map-key lister's catalog (and wire) name; array-valued, so governed by this class. */
    public static final String MAP_KEYS = "map_keys";

    /** The delimited-string splitter's catalog (and wire) name; array-valued, so governed by this class. */
    public static final String SPLIT_STRING = "split_string";

    /**
     * The sub-array function's catalog (and wire) name. The one member of <b>both</b> sets below: it takes
     * an array at argument 0 and answers with an array, so it is governed as a producer and as a consumer.
     */
    public static final String ARRAY_SLICE = "array_slice";

    /** Every catalog function returning an array. */
    private static final Set<String> NAMES = Set.of(
            GROUP_UNIQ_ARRAY, JSON_EXTRACT_ARRAY, MAP_KEYS, SPLIT_STRING, ARRAY_SLICE,
            GROUP_UNIQ_ARRAY_ARRAY.toLowerCase(Locale.ROOT), GROUP_ARRAY_ARRAY.toLowerCase(Locale.ROOT),
            HigherOrderFunctions.ARRAY_FILTER.toLowerCase(Locale.ROOT),
            HigherOrderFunctions.ARRAY_MAP.toLowerCase(Locale.ROOT));

    /**
     * The array-returning <b>aggregates</b>. Separate from {@link #NAMES} because one position cares about
     * the difference: {@code arg_max}'s value admits an array, but not an aggregate — an aggregate inside
     * an aggregate is what ClickHouse refuses outright, and the array carve-out must not smuggle one in.
     */
    private static final Set<String> ARRAY_VALUED_AGGREGATES = Set.of(
            GROUP_UNIQ_ARRAY, GROUP_UNIQ_ARRAY_ARRAY.toLowerCase(Locale.ROOT),
            GROUP_ARRAY_ARRAY.toLowerCase(Locale.ROOT));

    /** The two aggregates whose operand is an array and whose result is that array's element type. */
    private static final Set<String> FLATTENING_AGGREGATES = Set.of(
            GROUP_UNIQ_ARRAY_ARRAY.toLowerCase(Locale.ROOT), GROUP_ARRAY_ARRAY.toLowerCase(Locale.ROOT));

    /**
     * {@code arg_max}'s catalog name. Not array-<em>valued</em> and not an array operand function in the
     * accessor sense, but its {@code value} operand admits an array — so the position rule below has to
     * know the name.
     */
    public static final String ARG_MAX = "arg_max";

    /** The row-expanding function's catalog (and wire) name; array-consuming, hence its place below. */
    public static final String ARRAY_JOIN = "array_join";

    /** The array-to-text function's catalog (and wire) name; array-consuming, so it belongs below too. */
    public static final String ARRAY_TO_STRING = "array_to_string";

    /**
     * The catalog functions taking an array as their first argument — the only position where an array-valued
     * result, or a declared {@code array} column, is a legal operand. Held lowercase, as every DSL function
     * name is.
     *
     * <p>Four of the seven are the array <em>accessors</em>, which read an array and answer a scalar about
     * it. The other three are not accessors and consume an array in the same position for their own
     * purposes: {@link #ARRAY_JOIN} expands it into rows, {@link #ARRAY_TO_STRING} renders it as one text
     * value, and {@link #ARRAY_SLICE} narrows it to a bounded window. All belong here because this set
     * answers one question — "may an array stand at argument 0 of this call?" — and the answer for each is
     * yes, for the same reason and with the same consequence: {@code array_join(map_keys(m))},
     * {@code array_to_string(group_uniq_array(x), ', ')} and
     * {@code array_to_string(array_slice(group_uniq_array(x), 1, 6), ', ')} are expressible exactly because
     * a computed array is admitted at that position, and {@code SqlProjectionOnlyTypes} admits a declared
     * {@code array} column there by delegating here.
     *
     * <p>{@link #ARRAY_SLICE} is in {@link #NAMES} as well, being the only member whose own result is an
     * array; {@link #ARRAY_TO_STRING} is deliberately <b>not</b>, since its result is ordinary text and
     * carries no projection-only restriction — which is what makes a rendered array groupable and sortable.
     */
    private static final Set<String> ARRAY_OPERAND_FUNCTIONS = Set.of(
            "array_has", "array_has_any", "array_has_all", "array_length",
            ARRAY_JOIN, ARRAY_TO_STRING, ARRAY_SLICE);

    private ArrayValuedFunctions() {}

    /**
     * Whether {@code name} is an array-returning catalog function. Matched case-insensitively, as every
     * other function-name match on both surfaces is.
     */
    public static boolean isArrayValued(String name) {
        return name != null && NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether {@code name} is one of the two flattening aggregates, matched case-insensitively. They are
     * the only aggregates whose operand may be an array, and the reason the blanket "no aggregate over an
     * array" rule is now a named carve-out rather than a rule without exceptions.
     */
    public static boolean isFlatteningAggregate(String name) {
        return name != null && FLATTENING_AGGREGATES.contains(name.toLowerCase(Locale.ROOT));
    }

    /** The canonical camelCase spelling of a flattening aggregate, or {@code null} when it is not one. */
    public static String canonicalFlattening(String name) {
        if (GROUP_UNIQ_ARRAY_ARRAY.equalsIgnoreCase(name)) {
            return GROUP_UNIQ_ARRAY_ARRAY;
        }
        return GROUP_ARRAY_ARRAY.equalsIgnoreCase(name) ? GROUP_ARRAY_ARRAY : null;
    }

    /**
     * Whether {@code name} accepts an array as its argument {@code index} — true only for argument 0 of an
     * array-operand function. Matched case-insensitively, as every other function-name match on both
     * surfaces is.
     *
     * <p>Public because both the classifier for computed arrays (this class) and the one for declared
     * {@code array} columns ({@code SqlProjectionOnlyTypes}) must consult the same position rule, and the DSL
     * translator has to ask it per argument rather than per call.
     */
    public static boolean acceptsArrayOperand(String name, int index) {
        if (name == null) {
            return false;
        }
        // ClickHouse's higher-order family takes its array SECOND, so the position is per function rather
        // than the flat `index == 0` this rule started as. Delegated rather than repeated, for the reason
        // SqlProjectionOnlyTypes delegates here: one answer, consulted by both classifiers.
        if (HigherOrderFunctions.acceptsArrayAt(name, index)) {
            return true;
        }
        if (index == 0 && isFlatteningAggregate(name)) {
            return true;
        }
        // arg_max's VALUE operand, and only that one: "the roles as of this user's last request" is a
        // question whose answer is an array, while the ordering key it ranks by stays scalar.
        if (index == 0 && ARG_MAX.equalsIgnoreCase(name)) {
            return true;
        }
        return index == 0 && ARRAY_OPERAND_FUNCTIONS.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Rejects an array-valued call used as an operand of something else, naming the attempted {@code use}
     * (e.g. {@code "an argument of 'length'"}) and the function that produced the array — which of the two it
     * is matters to the author, since the fixes differ.
     */
    public static void rejectAsOperand(Expr expr, String use) {
        if (expr instanceof FnExpr fn && isArrayValued(fn.name())) {
            throw new QueryValidationException("'" + reported(fn.name()) + "' returns an array"
                    + " and can only be projected; it is not valid as " + use);
        }
    }

    /**
     * Rejects an array-valued call used as argument {@code index} of {@code enclosing}, unless that position
     * is the array accessors' carve-out. The per-argument form of {@link #rejectAsOperand}, which every
     * function branch of the DSL translator runs over its own arguments.
     */
    public static void rejectAsOperand(Expr expr, String enclosing, int index) {
        if (acceptsArrayOperand(enclosing, index) && !nestedAggregate(expr, enclosing)) {
            return;
        }
        rejectAsOperand(expr, "an argument of '" + enclosing + "'");
    }

    /**
     * The spelling a message names {@code name} by: the catalog's canonical one. Lower-casing was right
     * while every array-valued name was snake_case; it now has camelCase members, and reporting
     * {@code 'arrayfilter'} would name a function neither {@code GET /v1/queries/functions} nor
     * {@code docs/sql-reference.md} shows.
     */
    private static String reported(String name) {
        final String higherOrder = HigherOrderFunctions.canonical(name);
        if (higherOrder != null) {
            return higherOrder;
        }
        final String flattening = canonicalFlattening(name);
        return flattening != null ? flattening : name.toLowerCase(Locale.ROOT);
    }

    /**
     * Whether {@code expr} is an array-valued aggregate standing inside an <em>aggregate's</em> array
     * carve-out. Every position this rule admits an array at belongs either to a scalar call or to one of
     * the three aggregates that take one — {@code arg_max}'s value and argument 0 of the two flattening
     * aggregates — and for those three the carve-out exists for an array <em>value</em>: a column, or a
     * scalar call that produces an array. Admitting an aggregate there would render
     * {@code argMax(groupUniqArray(x), ts)} or {@code groupUniqArrayArray(groupUniqArray(x))}, which
     * ClickHouse refuses as a nested aggregate — and the SQL surface refuses at Calcite validation, so
     * letting one through here would answer the same statement with a 503 on one surface and a 400 on the
     * other. Refused here instead, with the message the operand rule already gives.
     *
     * <p>A <em>scalar</em> array-valued call stays legal at all three positions, which is what keeps
     * {@code groupUniqArrayArray(arrayFilter(x -> …, roles))} and {@code arg_max(map_keys(m), ts)}
     * expressible.
     */
    private static boolean nestedAggregate(Expr expr, String enclosing) {
        return (ARG_MAX.equalsIgnoreCase(enclosing) || isFlatteningAggregate(enclosing))
                && expr instanceof FnExpr fn
                && fn.name() != null
                && ARRAY_VALUED_AGGREGATES.contains(fn.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Rejects the {@code distinct} quantifier on {@code group_uniq_array}, which it does not take: its result
     * is already the distinct values of the group, so the flag could only be a no-op. Refused rather than
     * ignored because the catalog advertises {@code distinct_supported: false} and the SQL front end refuses
     * the equivalent {@code DISTINCT} outright — silently accepting it here would make the two surfaces
     * disagree. Scoped to that function: the other three array-valued names are scalars, and the quantifier
     * never reaches a scalar branch.
     */
    public static void rejectDistinct(String fn, boolean distinct) {
        if (!distinct) {
            return;
        }
        final String reason = GROUP_ARRAY_ARRAY.equalsIgnoreCase(fn)
                ? " deduplicating the flattened elements would make it '" + GROUP_UNIQ_ARRAY_ARRAY + "'"
                : " it already returns the distinct values of the group";
        throw new QueryValidationException("'" + fn + "' does not accept 'distinct';" + reason);
    }
}
