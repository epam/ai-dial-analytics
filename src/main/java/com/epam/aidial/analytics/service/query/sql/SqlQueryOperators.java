package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.service.query.ArrayValuedFunctions;
import com.epam.aidial.analytics.service.query.HigherOrderFunctions;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlBasicFunction;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.fun.SqlBasicAggFunction;
import org.apache.calcite.sql.fun.SqlLibraryOperators;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlOperandCountRanges;
import org.apache.calcite.sql.type.SqlOperandTypeChecker;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlSingleOperandTypeChecker;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeTransforms;
import org.apache.calcite.sql.util.SqlOperatorTables;

import java.util.List;

/**
 * The operator table for the SQL front end: the Calcite standard operators plus the non-standard
 * operators the DSL catalog needs, registered with the exact DSL signatures so they parse and type —
 * their semantics are enforced again by the DSL translator ({@code ExprTranslator}):
 *
 * <ul>
 *   <li>{@code date_bin(amount, unit, timestamp)} — e.g. {@code date_bin(5, 'minute', request_time)};
 *   <li>{@code date_trunc(unit, timestamp)} — e.g. {@code date_trunc('month', request_time)}; bound by
 *       {@link SqlDateTruncRebinder} rather than by this table, for the reason recorded there;
 *   <li>{@code width_bucket(operand, low, high, count)};
 *   <li>{@code length(text)} — the DSL catalog spelling, alongside the standard
 *       {@code char_length}/{@code character_length}, all mapping to DSL {@code length};
 *   <li>{@code json_extract_string|long|double|bool(json, key…)} — typed scalar extraction from JSON
 *       text, each with a fixed result type and a variadic string path;
 *   <li>{@code json_extract_string_ci(json, key)} — the same, matching the key case-insensitively, and
 *       fixed at two operands because a case-insensitive match is defined for one top-level key;
 *   <li>{@code json_extract_array(json, key…)} — array-valued extraction, typed as an array of text so a
 *       projection of it types as an array in the {@code Rel} tree;
 *   <li>{@code if(condition, then, else)} — the DSL's conditional;
 *   <li>{@code not_empty|starts_with|contains|equals(…)} — the boolean scalars that supply {@code if}'s
 *       condition, since the DSL has no comparison in expression position;
 *   <li>{@code group_uniq_array(value)} — the DSL's array-valued aggregate; with
 *       {@code json_extract_array} it is one of the two catalog functions whose result is not a scalar;
 *   <li><b>Arithmetic is not registered here at all.</b> The infix operators ({@code + - * / %}), the
 *       standard {@code MOD}, and {@code ROUND}/{@code FLOOR}/{@code CEIL} all come from the standard
 *       table, and {@code RexToExprTranslator} lowers each onto its catalog name — so the SQL subset
 *       spells arithmetic with operators and the catalog names ({@code plus}, {@code minus},
 *       {@code multiply}, {@code div}, {@code modulo}) are the <em>DSL</em> spelling. Registering them as
 *       functions too was tried and abandoned: <b>{@code MINUS} is a reserved set operator in the Babel
 *       grammar</b> (the {@code EXCEPT} alias), so {@code minus(a, b)} cannot parse as a function call
 *       whatever this table declares — measured, not assumed. Four of five parsing is a worse surface than
 *       a single rule, and the round-trip does not need them: the DSL→SQL renderer emits operators, so a
 *       catalog arithmetic name never appears in a statement this front end has to read back;
 *   <li>{@code map_value(map, key)}, {@code map_contains(map, key)}, {@code map_keys(map)} — the map
 *       accessors. A {@code map} column types as {@code VARCHAR} in the Calcite schema (as an
 *       {@code object} does), hence the character operand families;
 *   <li>{@code array_has|array_has_any|array_has_all(array, …)}, {@code array_length(array)} — the array
 *       accessors, over a real Calcite {@code ARRAY} operand;
 *   <li>{@code array_join(array)} — the row-expanding call, over the same {@code ARRAY} operand but typed as
 *       that array's <em>element</em>; the one entry here whose single-operand arity is load-bearing, since
 *       Calcite's own {@code SqlLibraryOperators.ARRAY_JOIN} is a different (Spark) function of two operands;
 *   <li>{@code split_string(text, delimiter)} — array-valued, typed like {@code json_extract_array};
 *   <li>{@code concat(value, value[, value…])} and {@code to_string(value)} — text composition over
 *       operands of <em>any</em> type, since the DSL functions render each operand as text themselves;
 *       the {@code ||} operator is re-bound onto {@code CONCAT} by {@link SqlTextRebinder};
 *   <li>{@code array_to_string(array, delimiter)} and {@code array_slice(array, offset, length)} — the
 *       remaining two array-operand functions, the second passing its operand's array type through;
 *   <li>{@code ILIKE}/{@code NOT ILIKE} — the DSL's case-insensitive contains operators;
 *   <li>{@code now()} — the clock, which the standard table does not have under that name (it has
 *       {@code CURRENT_TIMESTAMP}/{@code LOCALTIMESTAMP}, both mapped onto the same DSL function);
 *   <li>{@code date_add(unit, amount, timestamp)} / {@code date_sub(unit, amount, timestamp)} — the DSL
 *       spelling of an offset. The infix {@code ± INTERVAL n unit} form needs nothing here: it is the
 *       standard table's own {@code +}/{@code -} over an interval literal, which
 *       {@code RexToExprTranslator} lowers onto these two names.
 * </ul>
 *
 * <p>The custom table is chained before the standard one so these names resolve to the DSL
 * signatures even if a Calcite version ships its own variant.
 */
final class SqlQueryOperators {

    static final SqlFunction DATE_BIN = SqlBasicFunction.create(
            "DATE_BIN",
            ReturnTypes.cascade(ReturnTypes.ARG2, SqlTypeTransforms.TO_NULLABLE),
            OperandTypes.family(SqlTypeFamily.INTEGER, SqlTypeFamily.CHARACTER, SqlTypeFamily.TIMESTAMP));

    // Needs BOTH halves, unlike every other operator here. The Babel grammar binds DATE_TRUNC to Calcite's
    // own BigQuery-shaped operator at parse time, which no operator table can override — SqlDateTruncRebinder
    // swaps the parsed node onto this one. The table entry below is still required, because the validator
    // re-resolves a function call by name against the operator table when deriving its type; without it the
    // swap succeeds and validation then fails with "No match found for function signature".
    static final SqlFunction DATE_TRUNC = SqlBasicFunction.create(
            "DATE_TRUNC",
            ReturnTypes.cascade(ReturnTypes.ARG1, SqlTypeTransforms.TO_NULLABLE),
            OperandTypes.family(SqlTypeFamily.CHARACTER, SqlTypeFamily.TIMESTAMP));

    static final SqlFunction WIDTH_BUCKET = SqlBasicFunction.create(
            "WIDTH_BUCKET",
            ReturnTypes.INTEGER_NULLABLE,
            OperandTypes.family(
                    SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC, SqlTypeFamily.INTEGER));

    /**
     * {@code now()} — the clock. Registered because the standard table has no {@code NOW}: it spells the
     * clock {@code CURRENT_TIMESTAMP} / {@code LOCALTIMESTAMP}, both of which already validate and are
     * mapped onto this same DSL function by {@code RexToExprTranslator}. Without this entry the
     * engine-native and PostgreSQL spelling — the one most callers reach for — fails validation with
     * "No match found for function signature now()".
     *
     * <p>Typed {@code TIMESTAMP} and <b>not nullable</b>: a clock has no absent value, and declaring it
     * nullable would make every comparison against it nullable for no reason.
     *
     * <p>{@link OperandTypes#NILADIC} is what makes {@code now(0)} or {@code now('UTC')} an arity failure
     * at validation rather than reaching the translator — the same belt-and-braces the other entries here
     * get, since {@code ExprTranslator} refuses an argument too.
     */
    static final SqlFunction NOW = SqlBasicFunction.create("NOW", ReturnTypes.TIMESTAMP, OperandTypes.NILADIC);

    /**
     * {@code date_add(unit, amount, timestamp)} / {@code date_sub(unit, amount, timestamp)} — the DSL
     * spelling of an offset, accepted alongside the infix {@code ± INTERVAL n unit} form that
     * {@code RexToExprTranslator} lowers onto these same two functions. Both spellings exist because both
     * are what a ClickHouse-fluent caller already writes; the renderer emits only the infix one.
     *
     * <p>Deliberately <b>not</b> Calcite's {@code SqlLibraryOperators.DATEADD}, whose second operand is an
     * interval <em>qualifier</em> keyword rather than a string literal ({@code DATEADD(hour, -2, ts)}) — a
     * different function wearing a similar name, and one whose unit could not carry the DSL's own unit
     * vocabulary. The {@code CHARACTER} first operand is what keeps the unit a string literal, so the one
     * spelling of a unit is the same on both surfaces.
     *
     * <p>The amount operand is declared {@code INTEGER} so a fractional literal fails here; sign and
     * magnitude are still checked by the translator, which is where the message can explain that direction
     * lives in the function name.
     */
    static final SqlFunction DATE_ADD = offset("DATE_ADD");
    static final SqlFunction DATE_SUB = offset("DATE_SUB");

    /**
     * {@code group_uniq_array(value)} — an aggregate, so it must be a {@link SqlAggFunction} for the
     * validator to accept it in a {@code GROUP BY} query rather than reporting it as a scalar over an
     * aggregated column. {@link ReturnTypes#TO_ARRAY} makes the derived type an array of the operand's type,
     * which is what keeps a projection of it typing as an array in the {@code Rel} tree; the DSL translator
     * derives the result column's own kind from the operand independently
     * ({@code RelToStructuredQueryTranslator}). Non-nullable by construction: the function answers a group
     * with no non-null value with an empty array.
     *
     * <p>{@code DISTINCT} cannot be forbidden here — {@code SqlBasicAggFunction.withDistinct} is not public
     * API — so the quantifier parses and is rejected by the translator instead, where the message can say
     * why (the function is already a set).
     */
    static final SqlAggFunction GROUP_UNIQ_ARRAY = SqlBasicAggFunction.create(
            "GROUP_UNIQ_ARRAY", SqlKind.OTHER_FUNCTION, ReturnTypes.TO_ARRAY, OperandTypes.ANY);

    /**
     * {@code arg_max(value, order)} — an aggregate, so a {@link SqlAggFunction} for the same reason
     * {@link #GROUP_UNIQ_ARRAY} is: the validator must accept it in a {@code GROUP BY} query rather than
     * reporting it as a scalar over an aggregated column. Without this entry the SQL surface never
     * reaches the translator at all — validation fails first with "No match found for function
     * signature" — even though the DSL surface works perfectly.
     *
     * <p>{@link ReturnTypes#ARG0_FORCE_NULLABLE} rather than {@code ARG0}: the result follows the
     * {@code value} operand's type, but is nullable regardless of that operand's declared nullability,
     * because a group whose every {@code order} is null yields null. {@code FORCE} rather than
     * {@code ARG0_NULLABLE}, which would only propagate nullability the operands already had.
     *
     * <p>{@link OperandTypes#ANY_ANY} because neither operand is constrained by type — {@code value} is
     * whatever is being carried and {@code order} is whatever is being ranked by. Arity is what this
     * enforces, and the array/object exclusion comes from {@code SqlProjectionOnlyTypes}, which already
     * covers every function's operands.
     *
     * <p>{@code DISTINCT} cannot be forbidden here — {@code SqlBasicAggFunction.withDistinct} is not
     * public API — so the quantifier parses and is rejected by the translator instead, where the message
     * can say why. Same arrangement as {@code GROUP_UNIQ_ARRAY}.
     */
    static final SqlAggFunction ARG_MAX = SqlBasicAggFunction.create(
            "ARG_MAX", SqlKind.OTHER_FUNCTION, ReturnTypes.ARG0_FORCE_NULLABLE, OperandTypes.ANY_ANY);

    /**
     * {@code if(condition, then, else)}.
     *
     * <p>{@code IF} is a keyword of the Babel grammar but a <b>non-reserved</b> one, so it parses as an
     * ordinary function call and needs no alternative spelling — measured against this Calcite build rather
     * than read off the grammar, and re-asserted by a front-end test so an upgrade that reserved it fails
     * there instead of in every rendered statement. Registration is still required: parsing a name is not
     * resolving it, and the validator resolves against this table.
     *
     * <p>The return type is the <b>least restrictive of the two branches</b>, which is what enforces the
     * spec's branch-unification rule on this surface: Calcite cannot derive one when the branches share no
     * common type, so {@code if(c, 'x', 1)} is refused during validation, and the numeric widening comes
     * free with it. It is computed over operands 1 and 2 explicitly rather than through
     * {@code ReturnTypes.LEAST_RESTRICTIVE}, which would fold the boolean condition into the unification and
     * derive nonsense. The operand checker pins the condition to the boolean family, which is the other half
     * of the rule.
     */
    static final SqlFunction IF = SqlBasicFunction.create(
            "IF",
            binding -> binding.getTypeFactory().leastRestrictive(
                    List.of(binding.getOperandType(1), binding.getOperandType(2))),
            OperandTypes.family(SqlTypeFamily.BOOLEAN, SqlTypeFamily.ANY, SqlTypeFamily.ANY));

    static final SqlFunction NOT_EMPTY = SqlBasicFunction.create(
            "NOT_EMPTY", ReturnTypes.BOOLEAN_NULLABLE, OperandTypes.family(SqlTypeFamily.CHARACTER));

    static final SqlFunction STARTS_WITH = SqlBasicFunction.create(
            "STARTS_WITH", ReturnTypes.BOOLEAN_NULLABLE,
            OperandTypes.family(SqlTypeFamily.CHARACTER, SqlTypeFamily.CHARACTER));

    /**
     * {@code contains(text, substring)}. Calcite core defines {@code CONTAINS} as the SQL:2011 <em>period</em>
     * predicate — an infix operator of {@code BINARY} syntax, so a {@code FUNCTION}-category lookup does not
     * reach it — and this table is chained ahead of the standard one regardless.
     */
    static final SqlFunction CONTAINS = SqlBasicFunction.create(
            "CONTAINS", ReturnTypes.BOOLEAN_NULLABLE,
            OperandTypes.family(SqlTypeFamily.CHARACTER, SqlTypeFamily.CHARACTER));

    /** {@code equals(a, b)} — the expression-position counterpart of the filter grammar's {@code eq}. */
    static final SqlFunction EQUALS = SqlBasicFunction.create(
            "EQUALS", ReturnTypes.BOOLEAN_NULLABLE, OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.ANY));

    /**
     * {@code map_value(map, key)} — a {@code map} column types as {@code VARCHAR} in the Calcite schema, so
     * the operand family is character; the projection-only carve-out that lets the column reach argument 0 is
     * {@code SqlProjectionOnlyTypes}', not this checker's.
     */
    static final SqlFunction MAP_VALUE = SqlBasicFunction.create(
            "MAP_VALUE", ReturnTypes.VARCHAR_NULLABLE,
            OperandTypes.family(SqlTypeFamily.CHARACTER, SqlTypeFamily.CHARACTER));

    /** {@code map_contains(map, key)} — the presence test {@code map_value} cannot express. */
    static final SqlFunction MAP_CONTAINS = SqlBasicFunction.create(
            "MAP_CONTAINS", ReturnTypes.BOOLEAN_NULLABLE,
            OperandTypes.family(SqlTypeFamily.CHARACTER, SqlTypeFamily.CHARACTER));

    /** {@code map_keys(map)} — array-valued, typed like {@link #JSON_EXTRACT_ARRAY} for the same reason. */
    static final SqlFunction MAP_KEYS = SqlBasicFunction.create(
            "MAP_KEYS",
            ReturnTypes.cascade(ReturnTypes.VARCHAR, SqlTypeTransforms.TO_ARRAY),
            OperandTypes.family(SqlTypeFamily.CHARACTER));

    /** {@code array_has(array, value)} — argument 0 is a real Calcite {@code ARRAY}. */
    static final SqlFunction ARRAY_HAS = SqlBasicFunction.create(
            "ARRAY_HAS", ReturnTypes.BOOLEAN_NULLABLE,
            OperandTypes.family(SqlTypeFamily.ARRAY, SqlTypeFamily.ANY));

    /** {@code array_has_any(array, ARRAY[...])} — both operands are arrays. */
    static final SqlFunction ARRAY_HAS_ANY = SqlBasicFunction.create(
            "ARRAY_HAS_ANY", ReturnTypes.BOOLEAN_NULLABLE,
            OperandTypes.family(SqlTypeFamily.ARRAY, SqlTypeFamily.ARRAY));

    /** {@code array_has_all(array, ARRAY[...])}. */
    static final SqlFunction ARRAY_HAS_ALL = SqlBasicFunction.create(
            "ARRAY_HAS_ALL", ReturnTypes.BOOLEAN_NULLABLE,
            OperandTypes.family(SqlTypeFamily.ARRAY, SqlTypeFamily.ARRAY));

    /**
     * {@code array_length(array)} — a separate name from the standard {@code LENGTH} even though ClickHouse
     * overloads that one, because the closed catalog has no overload resolution and the discovery surface
     * cannot advertise two return types under one entry.
     */
    static final SqlFunction ARRAY_LENGTH = SqlBasicFunction.create(
            "ARRAY_LENGTH", ReturnTypes.INTEGER_NULLABLE, OperandTypes.family(SqlTypeFamily.ARRAY));

    /**
     * {@code array_join(array)} — the row-expanding call, typed as the <b>element</b> type of its operand so
     * a projection of it types as a scalar in the {@code Rel} tree rather than as the array it consumes.
     * That is the inverse of what {@link #GROUP_UNIQ_ARRAY} and {@link #JSON_EXTRACT_ARRAY} do with
     * {@link SqlTypeTransforms#TO_ARRAY}, and it is what keeps the result out of the projection-only
     * classification on translate-back.
     *
     * <p><b>Exactly one operand.</b> The engine's own function takes one, and the arity is doing real work
     * here beyond matching it: Calcite ships an unrelated {@code SqlLibraryOperators.ARRAY_JOIN} — Spark's
     * {@code array_join(array, delimiter)}, which joins an array into a string. That one is a <em>library</em>
     * operator, absent from {@link SqlStdOperatorTable}, so it is not in {@link #TABLE} and cannot be
     * resolved by accident; and this entry is chained first regardless. The residual hazard is a SQL
     * generator emitting the Spark spelling against this surface, and a single-operand signature makes that
     * fail closed on arity rather than be silently read as an expansion with a stray literal.
     *
     * <p>{@link ReturnTypes#TO_COLLECTION_ELEMENT} rather than either nullable variant: the result is an
     * element of the array, carrying the element type's own nullability and nothing added. The expansion
     * introduces no NULLs of its own — an empty array yields no row at all rather than a null one — so
     * forcing the column nullable would be a lie with a cost, since the declared nullability reaches clients
     * through {@code INFORMATION_SCHEMA.IS_NULLABLE} and a needlessly nullable column makes some BI
     * generators wrap every sort in a null-ordering {@code CASE}.
     */
    static final SqlFunction ARRAY_JOIN = SqlBasicFunction.create(
            "ARRAY_JOIN", ReturnTypes.TO_COLLECTION_ELEMENT, OperandTypes.family(SqlTypeFamily.ARRAY));

    /**
     * {@code concat(value, value[, value…])} — the DSL's text composition, and the operator {@code ||}
     * lowers onto this same entry ({@link SqlTextRebinder}).
     *
     * <p>{@link OperandTypes#ANY} repeated from two, <b>not</b> a character family: the DSL function renders
     * every operand as text itself, so a numeric or timestamp operand is legal and
     * {@code 'depth ' || 4} has to type. That is also why this cannot be Calcite's own
     * {@code SqlLibraryOperators.CONCAT_FUNCTION}, whose checker is string-only — and why the swap in the
     * re-binder is not enough on its own: the validator resolves the swapped call by name against this
     * table, so both halves are required, the arrangement {@link SqlDateTruncRebinder} describes.
     *
     * <p>{@link ReturnTypes#VARCHAR} rather than a nullable variant: the DSL contract is that a null operand
     * contributes the empty string, so the column is never null, and a needlessly nullable column reaches
     * clients through {@code INFORMATION_SCHEMA.IS_NULLABLE} — where some BI generators wrap every sort in a
     * null-ordering {@code CASE}.
     */
    static final SqlFunction CONCAT = SqlBasicFunction.create(
            "CONCAT", ReturnTypes.VARCHAR,
            OperandTypes.repeat(SqlOperandCountRanges.from(2), OperandTypes.ANY));

    /**
     * {@code to_string(value)} — one scalar rendered as text. One {@code ANY} operand: the non-scalar
     * exclusion is {@code SqlProjectionOnlyTypes}', which already covers every function's operands, and an
     * operand-type checker could not express it anyway (a {@code map} column types as {@code VARCHAR} here).
     *
     * <p>{@link ReturnTypes#VARCHAR_NULLABLE} — nullable exactly when its operand is — where
     * {@link #CONCAT} is unconditionally non-nullable: this function preserves NULL, which is what the
     * {@code CAST} it stands in for means, while {@code concat} guards each operand and cannot answer NULL.
     */
    static final SqlFunction TO_STRING = SqlBasicFunction.create(
            "TO_STRING", ReturnTypes.VARCHAR_NULLABLE, OperandTypes.family(SqlTypeFamily.ANY));

    /**
     * The <b>internal marker</b> that carries a caller-written {@code CAST(<expr> AS <character type>)}
     * through validation — signature-identical to {@link #TO_STRING}, including
     * {@link ReturnTypes#VARCHAR_NULLABLE}, which is exactly the nullability a {@code CAST} derives.
     *
     * <p><b>Why a second operator and not {@link #TO_STRING} itself.</b> The cast rewrite has to happen on
     * the <em>parsed</em> tree, the only one where a cast the caller wrote is still distinguishable from one
     * the validator injects ({@link SqlTextRebinder} says why). But the decision the identity case needs
     * cannot be taken there: no operand has a type yet, so lowering straight onto {@code TO_STRING} wrapped
     * a text-to-text cast too — and {@code CAST(<text column> AS VARCHAR)}, which BI clients emit
     * constantly, stopped translating to the bare column it has always been. In a predicate it became
     * {@code toString(project_id) = 'p1'} over a {@code LowCardinality(String)} sort-key column, and in a
     * group key it grouped on {@code String} rather than on the low-cardinality column. Carrying the request
     * on its own operator lets {@link RexToExprTranslator} decide where the operand's type is known, while a
     * caller-written {@code TO_STRING(<text column>)} still renders {@code toString} — which the DSL→SQL→DSL
     * round trip requires, since the renderer emits that spelling for the DSL's own {@code to_string}.
     *
     * <p>It is in {@link #TABLE} because the validator resolves the swapped call by name like any other —
     * which would also resolve the name in a statement a <em>client</em> wrote, and this name is in neither
     * {@code QueryFunctionCatalog} nor {@code GET /v1/queries/sql-functions}. The subset rejects a function
     * outside that catalog, so {@link SqlTextRebinder} refuses a caller-written {@code TO_STRING_CAST} call
     * before it introduces any marker of its own: the marker is reachable only through the rewrite, and the
     * spellings a client has are {@code CAST(x AS VARCHAR)} and {@link #TO_STRING}.
     */
    static final SqlFunction TO_STRING_CAST = SqlBasicFunction.create(
            "TO_STRING_CAST", ReturnTypes.VARCHAR_NULLABLE, OperandTypes.family(SqlTypeFamily.ANY));

    /**
     * {@code array_to_string(array, delimiter)} — an array rendered as one text value, over a real Calcite
     * {@code ARRAY} operand and a character delimiter. Non-nullable, as the other two text producers are:
     * an empty array renders as the empty string.
     */
    static final SqlFunction ARRAY_TO_STRING = SqlBasicFunction.create(
            "ARRAY_TO_STRING", ReturnTypes.VARCHAR,
            OperandTypes.family(SqlTypeFamily.ARRAY, SqlTypeFamily.CHARACTER));

    /**
     * {@code array_slice(array, offset, length)} — a bounded window of an array.
     *
     * <p>{@link ReturnTypes#ARG0} because a slice of an array is an array of the same element type: this is
     * the one entry here that neither wraps an operand into an array ({@link SqlTypeTransforms#TO_ARRAY})
     * nor unwraps one ({@link ReturnTypes#TO_COLLECTION_ELEMENT}) but passes the type through, which is what
     * makes {@code ARRAY_TO_STRING(ARRAY_SLICE(…), ', ')} and {@code ARRAY_JOIN(ARRAY_SLICE(…))} type
     * against the original element.
     *
     * <p>The bounds are {@code INTEGER} here and <b>literals</b> by the translator's own rule, which an
     * operand-type checker cannot express — the same split the {@code json_extract_*} path keys have.
     */
    static final SqlFunction ARRAY_SLICE = SqlBasicFunction.create(
            "ARRAY_SLICE", ReturnTypes.ARG0,
            OperandTypes.family(SqlTypeFamily.ARRAY, SqlTypeFamily.INTEGER, SqlTypeFamily.INTEGER));

    /** {@code split_string(text, delimiter)} — array-valued, typed like {@link #JSON_EXTRACT_ARRAY}. */
    static final SqlFunction SPLIT_STRING = SqlBasicFunction.create(
            "SPLIT_STRING",
            ReturnTypes.cascade(ReturnTypes.VARCHAR, SqlTypeTransforms.TO_ARRAY),
            OperandTypes.family(SqlTypeFamily.CHARACTER, SqlTypeFamily.CHARACTER));

    static final SqlFunction JSON_EXTRACT_STRING = jsonExtract("JSON_EXTRACT_STRING", ReturnTypes.VARCHAR_NULLABLE);
    static final SqlFunction JSON_EXTRACT_LONG = jsonExtract("JSON_EXTRACT_LONG", ReturnTypes.BIGINT_NULLABLE);
    static final SqlFunction JSON_EXTRACT_DOUBLE = jsonExtract("JSON_EXTRACT_DOUBLE", ReturnTypes.DOUBLE_NULLABLE);
    static final SqlFunction JSON_EXTRACT_BOOL = jsonExtract("JSON_EXTRACT_BOOL", ReturnTypes.BOOLEAN_NULLABLE);

    /**
     * {@code json_extract_string_ci(json, key)} — <b>exactly two</b> operands, unlike every other member of
     * the family. A case-insensitive key match is defined for one top-level key, so a third operand is an
     * arity error rather than a deeper path; declaring the arity here means the SQL surface says so before
     * the translator has to.
     */
    static final SqlFunction JSON_EXTRACT_STRING_CI = SqlBasicFunction.create(
            "JSON_EXTRACT_STRING_CI",
            ReturnTypes.VARCHAR_NULLABLE,
            OperandTypes.family(SqlTypeFamily.CHARACTER, SqlTypeFamily.CHARACTER));

    /**
     * {@code json_extract_array(json, key[, key…])} — variadic like the four scalar extractors, but typed as
     * an array of text so that a projection of it types as an array in the {@code Rel} tree, the way
     * {@link #GROUP_UNIQ_ARRAY} does. The DSL translator derives the result column's element kind
     * independently ({@code RelToStructuredQueryTranslator}), where it is fixed at {@code string} rather
     * than following an operand.
     */
    static final SqlFunction JSON_EXTRACT_ARRAY = SqlBasicFunction.create(
            "JSON_EXTRACT_ARRAY",
            ReturnTypes.cascade(ReturnTypes.VARCHAR, SqlTypeTransforms.TO_ARRAY),
            OperandTypes.repeat(SqlOperandCountRanges.from(2), OperandTypes.CHARACTER));

    /**
     * {@code match(haystack, pattern)} — ClickHouse's RE2 match, under ClickHouse's own name. Both operands
     * are character, as in the engine; the pattern is not restricted to a literal, so a per-row pattern is
     * as legal here as it is there. A pattern RE2 cannot compile is the engine's error, not this surface's.
     */
    static final SqlFunction MATCH = SqlBasicFunction.create(
            "MATCH", ReturnTypes.BOOLEAN_NULLABLE,
            OperandTypes.family(SqlTypeFamily.CHARACTER, SqlTypeFamily.CHARACTER));

    /**
     * The <b>internal marker</b> standing for a lambda parameter between {@link SqlLambdaRewriter} and
     * {@link RexToExprTranslator} — {@code __lambdaParam(<the array operand>, '<the parameter name>')},
     * typed as the array's <em>element</em>.
     *
     * <p><b>Why a marker rather than Calcite's own lambda.</b> Calcite parses {@code x -> body} and
     * validates it in a {@code SqlLambdaScope} that resolves an identifier <em>only</em> against the
     * lambda's parameters — so {@code arrayExists(x -> starts_with(x, deployment), roles)}, which
     * ClickHouse accepts, fails there with "Param 'deployment' not found". Inlining the parameter as this
     * marker leaves the body an ordinary expression in the enclosing scope, where a captured column
     * resolves like any other column and the parameter still carries the element's type.
     *
     * <p><b>The name operand is load-bearing, not decoration.</b>
     * {@code arrayExists(x -> arrayExists(y -> equals(y, x), roles), roles)} inlines two markers over the
     * <em>same</em> array expression; without the name the reconstruction could not tell which parameter
     * each stands for. Carrying it also preserves the caller's own parameter names through the round-trip.
     *
     * <p>Registered in {@link #TABLE} because the validator resolves the rewritten call by name, which
     * also makes the name resolvable in a statement a client wrote — so {@link SqlLambdaRewriter} rejects
     * a caller-spelled occurrence before introducing any marker of its own, exactly as
     * {@link SqlTextRebinder} does for {@link #TO_STRING_CAST}.
     */
    static final SqlFunction LAMBDA_PARAM = SqlBasicFunction.create(
            "__LAMBDAPARAM",
            SqlQueryOperators::lambdaParamType,
            OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.CHARACTER));

    /**
     * The five higher-order array functions, in ClickHouse's operand order: the lambda first, the array
     * second. By the time the validator sees one, {@link SqlLambdaRewriter} has replaced the lambda with
     * its inlined body and <b>appended the parameter's name</b> as a third operand, so the declared shape
     * is (body, array, name): the body's own type — boolean for the four predicates, anything for
     * {@code arrayMap} — then the array, then the name that binds the markers inside the body. The
     * checker's signature string still names the two-operand caller-facing form, which is what a
     * rejection has to read like.
     *
     * <p>{@code arrayFilter} and {@code arrayMap} answer with an array: {@link ReturnTypes#ARG1} for the
     * former (a filtered array holds the same elements) and, for the latter, an array of whatever the body
     * produced, which is {@link ReturnTypes#ARG0} carried back up into a collection.
     */
    static final SqlFunction ARRAY_EXISTS = higherOrderPredicate(HigherOrderFunctions.ARRAY_EXISTS);
    static final SqlFunction ARRAY_ALL = higherOrderPredicate(HigherOrderFunctions.ARRAY_ALL);

    static final SqlFunction ARRAY_COUNT = SqlBasicFunction.create(
            HigherOrderFunctions.ARRAY_COUNT, ReturnTypes.INTEGER,
            higherOrderOperands(HigherOrderFunctions.ARRAY_COUNT, OperandTypes.BOOLEAN));

    static final SqlFunction ARRAY_FILTER = SqlBasicFunction.create(
            HigherOrderFunctions.ARRAY_FILTER, ReturnTypes.ARG1,
            higherOrderOperands(HigherOrderFunctions.ARRAY_FILTER, OperandTypes.BOOLEAN));

    static final SqlFunction ARRAY_MAP = SqlBasicFunction.create(
            HigherOrderFunctions.ARRAY_MAP,
            ReturnTypes.cascade(ReturnTypes.ARG0, SqlTypeTransforms.TO_ARRAY),
            higherOrderOperands(HigherOrderFunctions.ARRAY_MAP, OperandTypes.ANY));

    /**
     * {@code groupUniqArrayArray(array)} / {@code groupArrayArray(array)} — ClickHouse's {@code -Array}
     * combinator on the two array-collecting aggregates. {@link SqlAggFunction}s for the reason
     * {@link #GROUP_UNIQ_ARRAY} is one, and over an {@code ARRAY} operand rather than {@code ANY}: these
     * are the two aggregates whose operand <em>must</em> be an array, and declaring it here is what makes
     * {@code groupUniqArrayArray(deployment)} an operand error naming the form rather than a ClickHouse
     * failure. {@link ReturnTypes#ARG0}: flattening a group of {@code Array(T)} yields one {@code Array(T)}.
     */
    static final SqlAggFunction GROUP_UNIQ_ARRAY_ARRAY = SqlBasicAggFunction.create(
            ArrayValuedFunctions.GROUP_UNIQ_ARRAY_ARRAY, SqlKind.OTHER_FUNCTION,
            ReturnTypes.ARG0, OperandTypes.ARRAY);

    static final SqlAggFunction GROUP_ARRAY_ARRAY = SqlBasicAggFunction.create(
            ArrayValuedFunctions.GROUP_ARRAY_ARRAY, SqlKind.OTHER_FUNCTION,
            ReturnTypes.ARG0, OperandTypes.ARRAY);

    // ILIKE (case-insensitive LIKE, the DSL's ico/inc) is a PostgreSQL-library operator, not part of
    // the std table — both variants must be registered explicitly for validation to accept them.
    // LENGTH is likewise a library operator (the std table only has CHAR_LENGTH/CHARACTER_LENGTH);
    // it must be registered so the DSL catalog name `length` — the exact spelling the DSL→SQL
    // renderer emits — is accepted, keeping rendered statements within the subset (round-trip).
    static final SqlOperatorTable TABLE = SqlOperatorTables.chain(
            SqlOperatorTables.of(
                    DATE_BIN, DATE_TRUNC, WIDTH_BUCKET, GROUP_UNIQ_ARRAY, ARG_MAX,
                    NOW, DATE_ADD, DATE_SUB,
                    SqlLibraryOperators.ILIKE, SqlLibraryOperators.NOT_ILIKE, SqlLibraryOperators.LENGTH,
                    JSON_EXTRACT_STRING, JSON_EXTRACT_LONG, JSON_EXTRACT_DOUBLE, JSON_EXTRACT_BOOL,
                    JSON_EXTRACT_STRING_CI, JSON_EXTRACT_ARRAY,
                    IF, NOT_EMPTY, STARTS_WITH, CONTAINS, EQUALS,
                    MAP_VALUE, MAP_CONTAINS, MAP_KEYS,
                    ARRAY_HAS, ARRAY_HAS_ANY, ARRAY_HAS_ALL, ARRAY_LENGTH, ARRAY_JOIN, SPLIT_STRING,
                    CONCAT, TO_STRING, TO_STRING_CAST, ARRAY_TO_STRING, ARRAY_SLICE,
                    MATCH, LAMBDA_PARAM, ARRAY_EXISTS, ARRAY_ALL, ARRAY_COUNT, ARRAY_FILTER, ARRAY_MAP,
                    GROUP_UNIQ_ARRAY_ARRAY, GROUP_ARRAY_ARRAY),
            SqlStdOperatorTable.instance());

    /**
     * The marker's type: its array operand's element type, or {@code ANY} when that operand is not an
     * array. <b>Deliberately permissive</b> — a non-array operand is the enclosing higher-order call's
     * error, and its checker says so in the caller's own vocabulary
     * ({@code arrayExists(<LAMBDA>, <ARRAY>)}). Declaring an {@code ARRAY} operand here instead made the
     * marker fail first, and a caller who wrote {@code arrayExists(x -> ..., deployment)} was answered
     * with a message naming an internal function they never wrote.
     */
    private static RelDataType lambdaParamType(SqlOperatorBinding binding) {
        final RelDataType operand = binding.getOperandType(0);
        final RelDataType element = operand.getComponentType();
        return element == null
                ? binding.getTypeFactory().createSqlType(SqlTypeName.ANY)
                : binding.getTypeFactory().createTypeWithNullability(element, false);
    }

    /** One of the two higher-order predicates: a boolean body, an array, and a boolean result. */
    private static SqlFunction higherOrderPredicate(String name) {
        return SqlBasicFunction.create(
                name, ReturnTypes.BOOLEAN, higherOrderOperands(name, OperandTypes.BOOLEAN));
    }

    /**
     * The operand checker for a higher-order call: the inlined body, then the array. The signature string
     * names the <b>caller-facing</b> form — the lambda, not the body the rewrite left in its place — so a
     * rejection reads like the statement the caller wrote rather than like an internal rewrite.
     */
    private static SqlOperandTypeChecker higherOrderOperands(String name, SqlSingleOperandTypeChecker body) {
        return OperandTypes.sequence(
                name + "(<LAMBDA>, <ARRAY>)", body, OperandTypes.ARRAY, OperandTypes.CHARACTER);
    }

    /**
     * One of the two offset functions: {@code fn(unit, amount, timestamp)} returning a timestamp that
     * follows its own operand's nullability (an offset of an absent instant is absent).
     */
    private static SqlFunction offset(String name) {
        return SqlBasicFunction.create(
                name,
                ReturnTypes.cascade(ReturnTypes.ARG2, SqlTypeTransforms.TO_NULLABLE),
                OperandTypes.family(SqlTypeFamily.CHARACTER, SqlTypeFamily.INTEGER, SqlTypeFamily.TIMESTAMP));
    }

    /**
     * A JSON extraction function: {@code fn(json, key[, key…])} over character operands, with a fixed
     * result type independent of its operands (the DSL's type inference is static per function name, so
     * each extracted type gets its own function rather than one polymorphic accessor). The count range
     * requires the JSON operand plus at least one path key; the path keys must additionally be
     * <em>literals</em>, which the DSL translator enforces — an operand-type checker cannot express it.
     */
    private static SqlFunction jsonExtract(String name, SqlReturnTypeInference returns) {
        return SqlBasicFunction.create(
                name, returns, OperandTypes.repeat(SqlOperandCountRanges.from(2), OperandTypes.CHARACTER));
    }

    private SqlQueryOperators() {}
}
