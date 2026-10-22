package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.service.query.ArrayValuedFunctions;
import com.epam.aidial.analytics.service.query.DocumentComparisons;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;

import java.util.Locale;
import java.util.Set;

/**
 * The field types that SQL can only <em>project</em>: {@code array}, {@code object} and {@code map}. All are
 * visible in the Calcite schema (so referencing one is never a misleading "unknown column"), but
 * the DSL has no literal or comparison for either, so any use outside a plain projection item is
 * inexpressible and fails closed. This class is the single source of that classification and of
 * the rejection wording, for both directions: the SQL→DSL translators call {@link #reject}, and
 * the DSL→SQL renderer builds its own {@code QueryValidationException} around {@link #message}
 * with its own tail ("cannot be expressed in SQL" rather than "is not supported").
 *
 * <p>There are two <b>kinds</b> of carve-out. The first is <b>operator-specific</b>: an {@code object}
 * field is a legal operand of the contains and equality comparisons, whose meaning is defined over the
 * document's canonical JSON text. {@code DocumentComparisons} owns that rule and {@link #isComparableAs}
 * delegates to it, so this class and the DSL surface's {@code FilterTranslator} cannot drift on which
 * operators are admitted. The second kind is <b>positional and type-specific</b> — argument 0 of a named
 * function set, for one declared type each — and is expressed by {@link #isAllowedFunctionArgument}:
 *
 * <ul>
 *   <li>an {@code object} field as the <em>JSON argument of a {@code json_extract_*} call</em>, because
 *       both surfaces can express that — the DSL renders native subcolumn access and the SQL subset
 *       spells it {@code JSON_EXTRACT_*(…)};
 *   <li>a {@code map} field as the <em>map argument of {@code map_value}/{@code map_contains}/
 *       {@code map_keys}</em>, the map's own accessors;
 *   <li>an {@code array} field as the <em>array argument of an array-operand function</em> — the set
 *       {@code ArrayValuedFunctions.ARRAY_OPERAND_FUNCTIONS} holds, which {@link #isAllowedFunctionArgument}
 *       delegates to rather than repeating. Named there rather than here on purpose: the set grows (it is
 *       currently the four accessors that read an array, plus the ones that expand it into rows, render it
 *       as text and narrow it), and a copy in this javadoc would go stale the next time it does — which it
 *       already had.
 * </ul>
 *
 * <p>Both directions must agree on these or the DSL↔SQL round-trip breaks,
 * which is why the rule lives here rather than as a call-site condition in each translator.
 * {@code ArrayValuedFunctions.acceptsArrayOperand} states the same array position for a <em>computed</em>
 * array, which this class cannot see — it classifies declared column types only — and the array carve-out
 * below <em>delegates</em> to it rather than repeating its name set, so the two answers cannot drift.
 *
 * <p><b>The map carve-out is not a widening of the JSON one.</b> A {@code JSON_EXTRACT_*} call over a
 * {@code map} field stays rejected: a ClickHouse {@code Map(String, String)} is not a JSON document and
 * neither surface has a rendering that extracts a path from one. The map accessors read one flat key by
 * exact literal match and traverse nothing, which is precisely why they are expressible where the
 * extractors are not.
 *
 * <p><b>The carve-out is about the argument, and says nothing about the result.</b> Five of the six
 * extractions yield an ordinary scalar carrying no restriction of any kind. {@code json_extract_array} is
 * the exception: its first argument is accepted here like the others, and its <em>result</em> is then
 * projection-only in its own right — an array, governed by {@code ArrayValuedFunctions} rather than by this
 * class, which classifies declared column types and cannot see a computed one.
 */
final class SqlProjectionOnlyTypes {

    /**
     * The DSL functions whose first argument is the JSON document — the carve-out's only position.
     * Held in the catalog's canonical lower case; {@link #isAllowedFunctionArgument} normalizes the
     * name it is given before looking it up.
     */
    private static final Set<String> JSON_EXTRACT_FUNCTIONS = Set.of(
            "json_extract_string", "json_extract_long", "json_extract_double", "json_extract_bool",
            "json_extract_string_ci", "json_extract_array");

    /** The DSL functions whose first argument is the map — the map carve-out's only position. */
    private static final Set<String> MAP_ACCESSOR_FUNCTIONS =
            Set.of("map_value", "map_contains", "map_keys");

    private SqlProjectionOnlyTypes() {}

    /**
     * Whether a field of {@code type} may be an operand of the comparison {@code op} despite being
     * projection-only — the operator carve-out. Delegates to {@link DocumentComparisons} rather than
     * repeating its operator set, exactly as the array carve-out below delegates to
     * {@code ArrayValuedFunctions}, so the SQL surface and the DSL surface cannot answer differently for
     * the same query.
     *
     * <p>Note this says nothing about the <em>right</em> operand: the JSON well-formedness a document
     * comparison requires of its literal is a property of the literal, checked where literals are read,
     * not of the field's type.
     */
    static boolean isComparableAs(QueryFieldType type, ComparisonOp op) {
        return DocumentComparisons.accepts(type, op);
    }

    /** Whether {@code type} can appear only as a projection item in the SQL subset. */
    static boolean isProjectionOnly(QueryFieldType type) {
        return type == QueryFieldType.ARRAY
                || type == QueryFieldType.OBJECT
                || type == QueryFieldType.MAP;
    }

    /**
     * Whether a field of {@code type} may be the JSON argument of a {@code json_extract_*} call.
     * True for every type that is not projection-only (a {@code string} column of JSON text is the
     * original case) plus {@code object}; false for {@code array} and {@code map}.
     *
     * <p>{@code map} is excluded by the projection-only test rather than by a case of its own, which is
     * the intended reading: a ClickHouse {@code Map(String, String)} is not a JSON document, and the DSL
     * has no rendering that would extract a path from one. Adding a type to {@link #isProjectionOnly}
     * therefore excludes it here by default — the safe direction for a carve-out.
     */
    static boolean isExtractableFrom(QueryFieldType type) {
        return type == QueryFieldType.OBJECT || !isProjectionOnly(type);
    }

    /**
     * Whether a field of {@code type} is allowed as argument {@code index} of the DSL function
     * {@code fnName} despite being projection-only. The single expression of the carve-out's
     * <em>position</em>, so the DSL→SQL renderer and the SQL→DSL translator cannot drift on it.
     *
     * <p>The name is matched <b>case-insensitively</b>, as every other function-name match on both
     * surfaces is ({@code ExprTranslator} lowercases before consulting the closed catalog, and the
     * renderer lowercases before emitting the call). Matching the raw spelling here would make an
     * upper-case {@code JSON_EXTRACT_STRING} over an {@code object} field execute but fail to
     * translate. A null name is not a match rather than an NPE: {@code build()} rejects it first
     * today, but this classifier does not need to depend on that.
     */
    static boolean isAllowedFunctionArgument(String fnName, int index, QueryFieldType type) {
        if (fnName == null) {
            return false;
        }
        final String canonical = fnName.toLowerCase(Locale.ROOT);
        // The array-operand functions' names AND positions live in ArrayValuedFunctions rather than being
        // repeated here, so the declared-column rule and the computed-array rule cannot drift apart on
        // which functions — or which argument — admit an array. Asked before the index test below, because
        // the position is no longer always 0: ClickHouse's higher-order family takes its array second.
        if (ArrayValuedFunctions.acceptsArrayOperand(canonical, index)) {
            return type == QueryFieldType.ARRAY;
        }
        if (index != 0) {
            return false;
        }
        if (JSON_EXTRACT_FUNCTIONS.contains(canonical)) {
            return isExtractableFrom(type);
        }
        if (MAP_ACCESSOR_FUNCTIONS.contains(canonical)) {
            return type == QueryFieldType.MAP;
        }
        return false;
    }

    /**
     * Rejects {@code field} when its declared {@code type} is projection-only, naming the field,
     * its type and the attempted {@code use} (e.g. {@code "a comparison on"}).
     */
    static void reject(String field, QueryFieldType type, String use) {
        if (isProjectionOnly(type)) {
            throw new SqlTranslationException(message(field, type, use, "is not supported"));
        }
    }

    /**
     * The shared rejection wording: {@code <type> field '<field>' can only be projected; <use> a[n]
     * <type> field <tail>}. Delegates to {@link DocumentComparisons#rejectionMessage}, which the DSL
     * surface's {@code FilterTranslator} also calls — the sentence must not reveal which of the three
     * enforcement points refused the query.
     */
    static String message(String field, QueryFieldType type, String use, String tail) {
        return DocumentComparisons.rejectionMessage(field, type, use, tail);
    }
}
