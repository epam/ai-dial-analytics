package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.web.dto.query.QueryFunctionArgConstraintsDto;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionArgDto;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionArgKind;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionDto;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionGroup;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionReturnType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The discovery source of truth for the closed function catalog accepted in structured query
 * expressions, served by {@code GET /v1/queries/functions}. Pure static data: names, groups,
 * signatures, return types, {@code distinct} support, and argument kinds/constraints — one entry
 * per branch of {@code translate.ExprTranslator#toFunction}, in alphabetical order.
 *
 * <p>Validation itself stays in {@code ExprTranslator}; parity between the two is enforced by
 * {@code QueryFunctionCatalogParityTest} (every descriptor is accepted by the translator, and the
 * advertised {@code date_bin} units equal the translator's accepted unit set). When adding a
 * function to the translator, add its descriptor here and to the structured-query spec table in the
 * same change.
 */
@Component
public class QueryFunctionCatalog {

    /**
     * The {@code date_bin} units advertised to clients — must equal the key set of
     * {@code ExprTranslator.DATE_BIN_UNITS} (asserted by the parity test). Alphabetical for a
     * stable wire order.
     */
    public static final List<String> DATE_BIN_ALLOWED_UNITS = List.of("day", "hour", "minute", "second", "week");

    /**
     * The {@code date_trunc} units advertised to clients — must equal the key set of
     * {@code ExprTranslator.DATE_TRUNC_UNITS} (asserted by the parity test). Alphabetical for a
     * stable wire order. A superset of {@link #DATE_BIN_ALLOWED_UNITS}: the two functions agree on the
     * fixed-width units and differ in that {@code date_bin} takes an amount multiplier while
     * {@code date_trunc} reaches the calendar units a multiplier cannot express.
     */
    public static final List<String> DATE_TRUNC_ALLOWED_UNITS =
            List.of("day", "hour", "minute", "month", "quarter", "second", "week", "year");

    /**
     * The {@code date_add}/{@code date_sub} units advertised to clients — must equal the key set of
     * {@code ExprTranslator.DATE_OFFSET_UNITS} (asserted by the parity test). Alphabetical for a stable wire
     * order.
     *
     * <p><b>Narrower than {@link #DATE_TRUNC_ALLOWED_UNITS} by design</b>, and the omission is
     * {@code week}/{@code quarter}. Truncation needs them because they name a calendar <em>alignment</em> no
     * other unit expresses; as an <em>offset</em> one week is exactly seven days and one quarter exactly
     * three months, so admitting them would give one operation two spellings and cost a stable round trip
     * for nothing — {@code date_sub('week', 1, …)} would render {@code - INTERVAL 1 WEEK}, which Calcite
     * lowers back to days, returning a different document than went in.
     */
    public static final List<String> DATE_OFFSET_ALLOWED_UNITS =
            List.of("day", "hour", "minute", "month", "second", "year");

    /**
     * How the description of a DSL-only name opens its SQL note. The five arithmetic names are the DSL
     * spelling only — {@code MINUS} is a reserved set operator in the Babel grammar, so the SQL surface spells
     * all five with operators (see {@code SqlQueryOperators}) — and the note says so. The SQL function
     * catalog reuses each description's semantics for its operator entries and cuts it at this prefix,
     * so the two cannot disagree about what the operation does.
     */
    public static final String DSL_ONLY_NOTE_PREFIX = "Not callable by this name in SQL";

    private static final QueryFunctionArgConstraintsDto UNIT_CONSTRAINTS =
            new QueryFunctionArgConstraintsDto(DATE_BIN_ALLOWED_UNITS, null, null);
    private static final QueryFunctionArgConstraintsDto TRUNC_UNIT_CONSTRAINTS =
            new QueryFunctionArgConstraintsDto(DATE_TRUNC_ALLOWED_UNITS, null, null);
    private static final QueryFunctionArgConstraintsDto OFFSET_UNIT_CONSTRAINTS =
            new QueryFunctionArgConstraintsDto(DATE_OFFSET_ALLOWED_UNITS, null, null);
    private static final QueryFunctionArgConstraintsDto POSITIVE_INTEGER =
            new QueryFunctionArgConstraintsDto(null, BigDecimal.ONE, null);
    private static final QueryFunctionArgConstraintsDto FRACTION_RANGE =
            new QueryFunctionArgConstraintsDto(null, BigDecimal.ZERO, BigDecimal.ONE);

    private static final List<QueryFunctionDto> FUNCTIONS = List.of(
            scalar("abs", "abs(value)", QueryFunctionReturnType.NUMERIC,
                    "Absolute value of a numeric expression.",
                    expression("value")),
            aggregate("arg_max", "arg_max(value, order)", QueryFunctionReturnType.SAME_AS_ARGUMENT, false,
                    "Value of the group's row bearing the greatest order — the latest row's value, not the greatest value. "
                            + "A row competes only when both operands are non-null: a null order is not ranked and a null "
                            + "value is skipped rather than winning, so the result is the latest NON-NULL value and is null "
                            + "only when no row qualifies. Ties on the greatest order return an unspecified one of the tied "
                            + "values, so order must be discriminating for the result to be deterministic. "
                            + "Distinct is not accepted: deduplicating values would detach a value from the order "
                            + "that selects it.",
                    expression("value"),
                    expression("order")),
            scalar("array_has", "array_has(array, value)", QueryFunctionReturnType.BOOLEAN,
                    "True when the array carries value; false — never null — when the array is empty or null. "
                            + "Returns boolean, so it is valid in a filter and as if's condition, which is how a "
                            + "closed value set becomes a distribution in one aggregate query.",
                    expression("array"),
                    expression("value")),
            scalar("array_has_all", "array_has_all(array, [value...])", QueryFunctionReturnType.BOOLEAN,
                    "True when the array carries every listed value; false — never null — when the array is empty "
                            + "or null. The value list must be non-empty.",
                    expression("array"),
                    expression("values")),
            scalar("array_has_any", "array_has_any(array, [value...])", QueryFunctionReturnType.BOOLEAN,
                    "True when the array carries at least one listed value; false — never null — when the array is "
                            + "empty or null. The value list must be non-empty.",
                    expression("array"),
                    expression("values")),
            scalar("array_join", "array_join(array)", QueryFunctionReturnType.ELEMENT_OF_ARGUMENT,
                    "Expands an array into one result row per element, so the call CHANGES THE NUMBER OF ROWS the "
                            + "query returns — the only catalog function that does. A row whose array is empty "
                            + "produces no rows at all; there is no outer variant, because the underlying engine's "
                            + "function has none and one would be invented rather than exposed. The result is the "
                            + "array's element type, an ordinary scalar, so it is groupable through the select "
                            + "entry's alias — which is how a distribution over the individual elements of an "
                            + "open-vocabulary array is expressed. Two select entries expanding different "
                            + "expressions produce the cartesian product of their elements; two expanding the same "
                            + "expression advance together over one expansion.",
                    expression("array")),
            scalar("array_length", "array_length(array)", QueryFunctionReturnType.INTEGER,
                    "Element count of an array expression; 0 — never null — for an empty or null array. Combined "
                            + "with map_keys it gives a map's entry count.",
                    expression("array")),
            scalar("array_slice", "array_slice(array, offset, length)", QueryFunctionReturnType.ARRAY,
                    "The sub-array starting at offset and holding at most length elements; the element type is the "
                            + "operand's, unchanged. offset is a non-zero integer literal, 1-based, counting from the "
                            + "end when negative; length is a positive integer literal. A slice reaching past the end "
                            + "returns the elements that exist and an offset past the end returns an empty array, "
                            + "rather than failing. The result is an array, so it is projectable only — except as the "
                            + "first argument of an array-operand function, which is what caps a rendered cell: "
                            + "array_to_string(array_slice(group_uniq_array(x), 1, 6), ', ').",
                    expression("array"),
                    literal("offset", QueryFunctionArgKind.INTEGER_LITERAL, null),
                    literal("length", QueryFunctionArgKind.INTEGER_LITERAL, POSITIVE_INTEGER)),
            scalar("array_to_string", "array_to_string(array, delimiter)", QueryFunctionReturnType.STRING,
                    "Renders an array as one text value: every element rendered as to_string renders it, joined by "
                            + "delimiter in the array's own order. Returns the empty string — never null — for an "
                            + "empty array, and a null element keeps its position as an empty slot rather than "
                            + "being dropped (['a', null, 'b'] joins as 'a,,b'). delimiter is a string literal and "
                            + "may be empty. The result is ordinary "
                            + "text carrying no restriction, so it is groupable, sortable and comparable — which is "
                            + "what makes a group_uniq_array result readable as a cell, the one thing no other "
                            + "catalog function can do.",
                    expression("array"),
                    literal("delimiter", QueryFunctionArgKind.STRING_LITERAL, null)),
            scalar(HigherOrderFunctions.ARRAY_ALL, "arrayAll(x -> boolean, array)",
                    QueryFunctionReturnType.BOOLEAN,
                    "True when every element satisfies the lambda, and true for an empty array — ClickHouse's own "
                            + "answers. The lambda's parameter is typed as the array's element, and its body may also "
                            + "read columns of the same row. Reads every element and cannot use a data-skipping "
                            + "index, where array_has_any over an enumerated value set can.",
                    lambda("func"),
                    expression("array")),
            scalar(HigherOrderFunctions.ARRAY_COUNT, "arrayCount(x -> boolean, array)",
                    QueryFunctionReturnType.INTEGER,
                    "How many elements satisfy the lambda; 0 for an empty array. The lambda's parameter is typed as "
                            + "the array's element, and its body may also read columns of the same row. Reads every "
                            + "element and cannot use a data-skipping index.",
                    lambda("func"),
                    expression("array")),
            scalar(HigherOrderFunctions.ARRAY_EXISTS, "arrayExists(x -> boolean, array)",
                    QueryFunctionReturnType.BOOLEAN,
                    "True when any element satisfies the lambda; false for an empty array. Returns boolean, so it is "
                            + "valid in a filter, in having and as if's condition — which is what makes a prefix, "
                            + "substring or regex test over an array expressible. Reads every element and cannot use "
                            + "a data-skipping index, where array_has_any over an enumerated value set can.",
                    lambda("func"),
                    expression("array")),
            scalar(HigherOrderFunctions.ARRAY_FILTER, "arrayFilter(x -> boolean, array)",
                    QueryFunctionReturnType.ARRAY,
                    "The elements satisfying the lambda, as an array of the operand's element type; empty for an "
                            + "empty array. Array-valued, so it can only be projected or passed as the array operand "
                            + "of an array-operand function — array_to_string(arrayFilter(...), ', ') renders the "
                            + "matches in one cell.",
                    lambda("func"),
                    expression("array")),
            scalar(HigherOrderFunctions.ARRAY_MAP, "arrayMap(x -> expression, array)",
                    QueryFunctionReturnType.ARRAY,
                    "The lambda applied to each element, as an array of the body's type; empty for an empty array. "
                            + "Array-valued, so it carries the same projection-only restriction arrayFilter does.",
                    lambda("func"),
                    expression("array")),
            aggregate("avg", "avg(value)", QueryFunctionReturnType.NUMERIC, true,
                    "Average of a numeric expression over the group; distinct deduplicates values first.",
                    expression("value")),
            scalar("ceil", "ceil(value)", QueryFunctionReturnType.NUMERIC,
                    "Rounds a numeric expression up toward positive infinity; null for a null operand.",
                    expression("value")),
            scalar("concat", "concat(value, value[, value...])", QueryFunctionReturnType.STRING,
                    "Joins two or more values into one text value, each operand rendered as to_string renders it — "
                            + "so an operand needs no text type and a number goes into a label directly. Never "
                            + "returns null, unlike to_string: a null operand contributes the empty string, which is PostgreSQL "
                            + "concat()'s own behaviour and deliberately differs from the || operator's null "
                            + "propagation in standard SQL (a label reading 'a -> ' is more useful than a row "
                            + "reading null, and it matches the empty-string absence contract map_value and "
                            + "json_extract_string already carry). A single operand is rejected rather than "
                            + "accepted: that call is to_string, and one operation has one spelling. In SQL the || "
                            + "operator is a second spelling of this function.",
                    expression("value"),
                    variadicExpression("value")),
            scalar("contains", "contains(text, substring)", QueryFunctionReturnType.BOOLEAN,
                    "True when text contains substring, compared case-sensitively; false — never null — when either "
                            + "operand is null. Wrap both operands in lower for a case-insensitive test.",
                    expression("text"),
                    expression("substring")),
            aggregate("count", "count([value])", QueryFunctionReturnType.LONG, true,
                    "Row count; with an argument counts non-null values, with distinct counts unique values.",
                    new QueryFunctionArgDto("value", QueryFunctionArgKind.EXPRESSION, Boolean.TRUE, null, null)),
            scalar("date_add", "date_add(unit, amount, timestamp)", QueryFunctionReturnType.TIMESTAMP,
                    "Advances timestamp by amount × unit. The name and argument order match the underlying engine's "
                            + "own dateAdd(unit, value, date), though the unit is a STRING literal here where the engine "
                            + "takes a bare interval keyword, so the call is not itself engine SQL; in SQL it is also "
                            + "spelled as the infix timestamp + INTERVAL amount unit, which is what a rendered "
                            + "statement carries. amount is a "
                            + "POSITIVE integer literal — direction lives in the function name, so a negative amount is "
                            + "refused rather than silently inverting the call into date_sub. second, minute, hour and day "
                            + "are fixed-width; month and year are calendar steps evaluated in UTC, so one month after "
                            + "31 January is 28 February rather than a day count. week and quarter are absent on purpose: "
                            + "as an offset they are exactly 7 day and 3 month.",
                    literal("unit", QueryFunctionArgKind.STRING_LITERAL, OFFSET_UNIT_CONSTRAINTS),
                    literal("amount", QueryFunctionArgKind.INTEGER_LITERAL, POSITIVE_INTEGER),
                    expression("timestamp")),
            scalar("date_bin", "date_bin(amount, unit, timestamp)", QueryFunctionReturnType.TIMESTAMP,
                    "Truncates timestamp down to the start of the fixed-width interval amount × unit, aligned to the epoch origin.",
                    literal("amount", QueryFunctionArgKind.INTEGER_LITERAL, POSITIVE_INTEGER),
                    literal("unit", QueryFunctionArgKind.STRING_LITERAL, UNIT_CONSTRAINTS),
                    expression("timestamp")),
            scalar("date_sub", "date_sub(unit, amount, timestamp)", QueryFunctionReturnType.TIMESTAMP,
                    "Moves timestamp back by amount × unit — date_add's mirror, sharing its argument order, its unit set, "
                            + "its positive-amount rule and its UTC calendar semantics. With now() it is how a stored query "
                            + "expresses a moving window: date_sub('hour', 2, now()) is the last two hours at whatever "
                            + "time the query is next run. In SQL it is also spelled timestamp - INTERVAL amount unit.",
                    literal("unit", QueryFunctionArgKind.STRING_LITERAL, OFFSET_UNIT_CONSTRAINTS),
                    literal("amount", QueryFunctionArgKind.INTEGER_LITERAL, POSITIVE_INTEGER),
                    expression("timestamp")),
            scalar("date_trunc", "date_trunc(unit, timestamp)", QueryFunctionReturnType.TIMESTAMP,
                    "Truncates timestamp down to the start of the calendar period named by unit "
                            + "(month to the 1st, week to Monday, quarter to the quarter's first day).",
                    literal("unit", QueryFunctionArgKind.STRING_LITERAL, TRUNC_UNIT_CONSTRAINTS),
                    expression("timestamp")),
            window("dense_rank", "Rank of the row within its window partition by the window's ORDER BY, without gaps "
                    + "after ties (1, 1, 2). Accepted only as the function of a window expression (an OVER clause in SQL)."),
            scalar("div", "div(a, b)", QueryFunctionReturnType.NUMERIC,
                    "Quotient of two numeric expressions. A zero or null divisor yields NULL — never an engine "
                            + "error — so a ratio panel does not fail because one group has an empty denominator; "
                            + "this holds for integer operands too, where a bare division would raise. The result "
                            + "is always fractional, so an integer-over-integer ratio is not truncated. "
                            + dslOnlyNote("a / b"),
                    expression("a"),
                    expression("b")),
            scalar("equals", "equals(a, b)", QueryFunctionReturnType.BOOLEAN,
                    "True when the operands are equal; false — never null — when either is null. The "
                            + "expression-position counterpart of the filter grammar's eq comparison.",
                    expression("a"),
                    expression("b")),
            scalar("floor", "floor(value)", QueryFunctionReturnType.NUMERIC,
                    "Rounds a numeric expression down toward negative infinity; null for a null operand. The "
                            + "SQL form taking a time unit (floor(ts TO hour)) is not accepted — use date_trunc.",
                    expression("value")),
            aggregate("group_uniq_array", "group_uniq_array(value)", QueryFunctionReturnType.ARRAY, false,
                    "Ascending-sorted array of the distinct non-null values of an expression over the group; "
                            + "an empty array for a group holding no non-null value (never null). The element type is the "
                            + "operand's. Distinct is inherent, so the quantifier is not accepted.",
                    expression("value")),
            aggregate(ArrayValuedFunctions.GROUP_ARRAY_ARRAY, "groupArrayArray(array)",
                    QueryFunctionReturnType.ARRAY, false,
                    "ClickHouse's -Array combinator on groupArray: the group's arrays flattened into one, keeping "
                            + "duplicates and each row's element order; the order between rows is unspecified. Empty "
                            + "— never null — for a group whose arrays are all empty. Array-valued, so it can only "
                            + "be projected. Holds an accumulator per group whose size grows with the group's "
                            + "elements, so an unfiltered GROUP BY over it is worth bounding by time.",
                    expression("array")),
            aggregate(ArrayValuedFunctions.GROUP_UNIQ_ARRAY_ARRAY, "groupUniqArrayArray(array)",
                    QueryFunctionReturnType.ARRAY, false,
                    "ClickHouse's -Array combinator on groupUniqArray: the group's arrays flattened into one and "
                            + "deduplicated. It is what makes a test over the values a group held anywhere in the "
                            + "window expressible in having, leaving the group's own measures computed over every "
                            + "row. Empty — never null — for a group whose arrays are all empty. Array-valued, so it "
                            + "can only be projected. Holds an accumulator per group whose size grows with the "
                            + "group's distinct elements.",
                    expression("array")),
            scalar("if", "if(condition, then, else)", QueryFunctionReturnType.SAME_AS_ARGUMENT,
                    "Evaluates to then when condition is true and to else otherwise; a null condition takes the else "
                            + "branch. The condition must be a boolean expression — a boolean column or a call to "
                            + "not_empty, starts_with, contains, equals or json_extract_bool — since the DSL has no "
                            + "comparison operator in expression position. Branches must be of the same type, except "
                            + "that numeric types widen against each other. Calls nest for a multi-branch classification.",
                    expression("condition"),
                    expression("then"),
                    expression("else")),
            scalar("json_extract_array", "json_extract_array(json, key[, key...])", QueryFunctionReturnType.ARRAY,
                    "Array of text values at the literal path inside a JSON expression; an empty array — never null — "
                            + "when the path is absent, unparseable, or names a member that is not an array. The result "
                            + "is projectable only, as group_uniq_array's is.",
                    expression("json"),
                    pathKey()),
            scalar("json_extract_bool", "json_extract_bool(json, key[, key...])", QueryFunctionReturnType.BOOLEAN,
                    "Boolean value at the literal path inside a JSON text expression; false when absent or unparseable.",
                    expression("json"),
                    pathKey()),
            scalar("json_extract_double", "json_extract_double(json, key[, key...])", QueryFunctionReturnType.NUMERIC,
                    "Floating-point value at the literal path inside a JSON text expression; 0 when absent or unparseable.",
                    expression("json"),
                    pathKey()),
            scalar("json_extract_long", "json_extract_long(json, key[, key...])", QueryFunctionReturnType.LONG,
                    "Integer value at the literal path inside a JSON text expression; 0 when absent or unparseable.",
                    expression("json"),
                    pathKey()),
            scalar("json_extract_string", "json_extract_string(json, key[, key...])", QueryFunctionReturnType.STRING,
                    "Text value at the literal path inside a JSON text expression; empty when absent or unparseable.",
                    expression("json"),
                    pathKey()),
            scalar("json_extract_string_ci", "json_extract_string_ci(json, key)", QueryFunctionReturnType.STRING,
                    "Text value of the first member whose key equals key ignoring case; empty when no member matches. "
                            + "Only the key is case-folded — the value is returned verbatim. Takes a single top-level "
                            + "key, not a path, and reads the whole document rather than stopping at the key. Over an "
                            + "object column that is one read stream per JSON path per part, so the cost scales with "
                            + "paths times parts rather than with rows matched — prefer json_extract_string wherever "
                            + "the key's case is known.",
                    expression("json"),
                    literal("key", QueryFunctionArgKind.STRING_LITERAL, null)),
            scalar("length", "length(text)", QueryFunctionReturnType.INTEGER,
                    "Character length of a text expression.",
                    expression("text")),
            scalar("lower", "lower(text)", QueryFunctionReturnType.STRING,
                    "Lowercases a text expression.",
                    expression("text")),
            scalar("map_contains", "map_contains(map, key)", QueryFunctionReturnType.BOOLEAN,
                    "True when a map expression carries key; false — never null — when it does not, is empty, or "
                            + "is null. The presence test map_value cannot give: an absent key and a key stored "
                            + "with an empty value both read as the empty string, and this distinguishes them. "
                            + "Takes a single top-level key matched literally, NOT a path — a key containing a dot "
                            + "is looked up as that one flat key.",
                    expression("map"),
                    literal("key", QueryFunctionArgKind.STRING_LITERAL, null)),
            scalar("map_keys", "map_keys(map)", QueryFunctionReturnType.ARRAY,
                    "The keys of a map expression as an array of text, in stored order; an empty array — never "
                            + "null — for an empty or null map. Answers which keys a producer-defined map actually "
                            + "carries. The result is projectable only, as group_uniq_array's is, except as the "
                            + "first argument of an array_has*/array_length call.",
                    expression("map")),
            scalar("map_value", "map_value(map, key)", QueryFunctionReturnType.STRING,
                    "Text value stored in a map expression under key; empty when the map does not carry that key, "
                            + "is empty, or is null — the same absent-key contract as json_extract_string. Takes a "
                            + "single top-level key matched literally, NOT a path: unlike json_extract_string it "
                            + "traverses nothing, so a key containing a dot (user.email) is looked up as that one "
                            + "flat key rather than split into segments.",
                    expression("map"),
                    literal("key", QueryFunctionArgKind.STRING_LITERAL, null)),
            scalar("match", "match(haystack, pattern)", QueryFunctionReturnType.BOOLEAN,
                    "ClickHouse's RE2 regular-expression match. Both operands are ordinary text expressions, so the "
                            + "pattern need not be a literal; a pattern the engine cannot compile fails there rather "
                            + "than here. Returns boolean, so it is valid in a filter, as if's condition, and inside "
                            + "a lambda body.",
                    expression("haystack"),
                    expression("pattern")),
            aggregate("max", "max(value)", QueryFunctionReturnType.SAME_AS_ARGUMENT, false,
                    "Maximum value of an expression over the group. Distinct is not accepted: deduplicating the "
                            + "input cannot change the extreme value, so the quantifier would be a silent no-op.",
                    expression("value")),
            aggregate("min", "min(value)", QueryFunctionReturnType.SAME_AS_ARGUMENT, false,
                    "Minimum value of an expression over the group. Distinct is not accepted: deduplicating the "
                            + "input cannot change the extreme value, so the quantifier would be a silent no-op.",
                    expression("value")),
            scalar("minus", "minus(a, b)", QueryFunctionReturnType.NUMERIC,
                    "Difference of two numeric expressions; null when either operand is null. " + dslOnlyNote("a - b"),
                    expression("a"),
                    expression("b")),
            scalar("modulo", "modulo(a, b)", QueryFunctionReturnType.NUMERIC,
                    "Remainder of dividing a by b. A zero or null right operand yields NULL — never an engine "
                            + "error — which matters more than for div, since a bare remainder raises on a zero "
                            + "divisor for every operand type. The result takes the SIGN OF THE LEFT OPERAND, so "
                            + "modulo(-7, 3) is -1; wrap in abs for a non-negative cycle position. Unlike div the "
                            + "operand type is preserved, so an integer remainder stays an integer. "
                            + dslOnlyNote("a % b or MOD(a, b)"),
                    expression("a"),
                    expression("b")),
            scalar("multiply", "multiply(a, b)", QueryFunctionReturnType.NUMERIC,
                    "Product of two numeric expressions; null when either operand is null. " + dslOnlyNote("a * b"),
                    expression("a"),
                    expression("b")),
            scalar("not_empty", "not_empty(text)", QueryFunctionReturnType.BOOLEAN,
                    "True when the operand is neither null nor the empty string; false — never null — for a null "
                            + "operand. The expression-position form of a presence test, and what makes "
                            + "if(not_empty(a), a, b) the DSL's first-non-absent merge.",
                    expression("text")),
            nonDeterministicScalar("now", "now()", QueryFunctionReturnType.TIMESTAMP,
                    "The current instant, in UTC at millisecond precision, read from the DATA STORE's clock at "
                            + "execution rather than from this service's — the comparison it takes part in is evaluated "
                            + "there, so a caller reconciling a result against the store's own clock is not shown a "
                            + "third one. Takes no arguments; there is no zone parameter, because UTC is the only "
                            + "alignment this surface offers. Each call is one value across every row of the statement, "
                            + "but two calls are evaluated separately, as in ClickHouse, and may differ by a millisecond "
                            + "tick, so a window bounded by two calls may see bounds that far apart. With date_sub it is "
                            + "how a SAVED query expresses a moving window instead of the two literal instants it was "
                            + "authored with; today's start is date_trunc('day', now()), which is why no today() exists. "
                            + "In SQL it is also spelled CURRENT_TIMESTAMP and LOCALTIMESTAMP. "
                            + "The one non-deterministic function in the catalog, and therefore REFUSED in a pipeline "
                            + "declaration — a derivation something else re-runs on its own schedule has to be "
                            + "reproducible — while every query surface accepts it freely.",
                    new QueryFunctionArgDto[0]),
            orderedSetAggregate("percentile_cont", "percentile_cont(fraction, column)", QueryFunctionReturnType.NUMERIC,
                    "Continuous percentile: interpolates between adjacent values at the given fraction of the "
                            + "ordered group. Distinct is not accepted: deduplicating changes which distribution "
                            + "is measured — the percentile of a value set is a different statistic from the "
                            + "percentile of the observations, not the same answer computed differently.",
                    literal("fraction", QueryFunctionArgKind.NUMERIC_LITERAL, FRACTION_RANGE),
                    expression("column")),
            orderedSetAggregate("percentile_disc", "percentile_disc(fraction, column)", QueryFunctionReturnType.SAME_AS_ARGUMENT,
                    "Discrete percentile: returns an actual member of the ordered group at the given fraction. "
                            + "Distinct is not accepted, for the same reason percentile_cont does not accept it: "
                            + "deduplicating measures a different distribution.",
                    literal("fraction", QueryFunctionArgKind.NUMERIC_LITERAL, FRACTION_RANGE),
                    expression("column")),
            scalar("plus", "plus(a, b)", QueryFunctionReturnType.NUMERIC,
                    "Sum of two numeric expressions; null when either operand is null. " + dslOnlyNote("a + b"),
                    expression("a"),
                    expression("b")),
            window("rank", "Rank of the row within its window partition by the window's ORDER BY, with gaps after "
                    + "ties (1, 1, 3). Accepted only as the function of a window expression (an OVER clause in SQL)."),
            scalar("round", "round(value[, digits])", QueryFunctionReturnType.NUMERIC,
                    "Rounds a numeric expression half away from zero, to digits decimal places when supplied and "
                            + "to zero places otherwise; digits is an integer literal and may be negative. Null "
                            + "for a null operand.",
                    expression("value"),
                    new QueryFunctionArgDto("digits", QueryFunctionArgKind.INTEGER_LITERAL, Boolean.TRUE, null, null)),
            window("row_number", "Sequential number of the row within its window partition by the window's ORDER BY, "
                    + "starting at 1. Accepted only as the function of a window expression (an OVER clause in SQL)."),
            scalar("split_string", "split_string(text, delimiter)", QueryFunctionReturnType.ARRAY,
                    "Splits a text expression on each occurrence of delimiter into an array of text, stripping "
                            + "leading and trailing whitespace from every element. An empty array for a null or "
                            + "empty text; a single-element array when the text carries no delimiter. The result is "
                            + "projectable only, as group_uniq_array's is, except as the first argument of an "
                            + "array_has*/array_length call.",
                    expression("text"),
                    literal("delimiter", QueryFunctionArgKind.STRING_LITERAL, null)),
            scalar("starts_with", "starts_with(text, prefix)", QueryFunctionReturnType.BOOLEAN,
                    "True when text begins with prefix, compared case-sensitively; false — never null — when either "
                            + "operand is null. Wrap both operands in lower for a case-insensitive test.",
                    expression("text"),
                    expression("prefix")),
            aggregate("sum", "sum(value)", QueryFunctionReturnType.NUMERIC, true,
                    "Sum of a numeric expression over the group; distinct deduplicates values first.",
                    expression("value")),
            scalar("to_string", "to_string(value)", QueryFunctionReturnType.STRING,
                    "Renders one scalar value as text. A null operand yields NULL, not the empty string: this is "
                            + "the CAST semantics the function stands in for — a cast of nothing is nothing — and it is "
                            + "concat and array_to_string that guard against an absent operand, each treating one as "
                            + "the empty string so a whole label is not lost to it. A non-scalar operand "
                            + "(object, array, map, or a call returning an array) is rejected: those have no scalar "
                            + "text. Numbers render as plain decimal digits, a decimal with its trailing zeros "
                            + "trimmed rather than padded to the column's scale; a boolean as 'true'/'false', "
                            + "never 1/0 and never 'false' for a null one — a nullable boolean follows the null "
                            + "rule above like every other type; a timestamp as 'YYYY-MM-DD "
                            + "hh:mm:ss.fff' in UTC and a date as 'YYYY-MM-DD'; an enum as its label. "
                            + "There is no format control. An enum IS "
                            + "accepted, unlike by the inspecting text functions (lower, starts_with, length, ...), "
                            + "because rendering an enum is defined where reading it as text is not — so this is "
                            + "also how those functions are reached over an enum domain: "
                            + "starts_with(to_string(status), 'run'). In SQL, a CAST(x AS VARCHAR) you write is a "
                            + "second spelling of this function wherever x is not already text; over a column the "
                            + "SQL surface types as text — an enum included — that cast is a no-op, so the call "
                            + "itself is what reaches the inspecting functions over an enum.",
                    expression("value")),
            scalar("trim", "trim(text)", QueryFunctionReturnType.STRING,
                    "Strips leading and trailing whitespace from a text expression.",
                    expression("text")),
            scalar("upper", "upper(text)", QueryFunctionReturnType.STRING,
                    "Uppercases a text expression.",
                    expression("text")),
            scalar("width_bucket", "width_bucket(operand, low, high, count)", QueryFunctionReturnType.INTEGER,
                    "Histogram bucket (1..count) the operand falls into across count equal-width buckets spanning [low, high); "
                            + "0 below low and count + 1 at or above high.",
                    expression("operand"),
                    expression("low"),
                    expression("high"),
                    expression("count")));

    /**
     * The names of every catalog function whose value is not reproducible, <b>derived from the descriptors</b>
     * rather than written out again. This is the whole point: the derivation paths
     * ({@code ExprSqlValidator}, {@code FilterSqlValidator}) refuse a function by asking this set, so adding a
     * non-deterministic descriptor closes those gates around it with no second edit. A hand-maintained list
     * beside the catalog would imply the catalog is open — it is not — and would rot the first time a
     * function was added without remembering the list exists.
     */
    private static final Set<String> NON_DETERMINISTIC = FUNCTIONS.stream()
            .filter(function -> !function.deterministic())
            .map(QueryFunctionDto::name)
            .collect(Collectors.toUnmodifiableSet());

    /** The full catalog, alphabetically ordered by function name. */
    public List<QueryFunctionDto> listFunctions() {
        return FUNCTIONS;
    }

    /**
     * Whether {@code name} is a catalog function this catalog declares non-deterministic. Matched in the
     * canonical lower case, as every other function-name match on both surfaces is. A name that is not in the
     * catalog at all answers {@code false}: it is rejected as an unsupported function long before determinism
     * is anyone's question, and answering {@code true} would report the wrong reason.
     *
     * <p>Static for the reason {@link #describe} is: the callers are validators deep inside the enrichment
     * package that already hold no catalog bean.
     */
    public static boolean isNonDeterministic(String name) {
        return name != null && NON_DETERMINISTIC.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether {@code name} is a catalog function that calls a service outside the data store while it is
     * evaluated — a model call, say — so that it costs a remote round trip per row it reads. A row policy may
     * not call one, because a policy is evaluated on every read of its table (see {@code RowPolicyPredicates}).
     *
     * <p>No function in this catalog does today, so the answer is {@code false} for every name. The change that
     * adds the first such function declares it here, beside its descriptor; the policy validator asks this
     * method rather than holding a list of its own.
     */
    public static boolean callsExternalService(String name) {
        return false;
    }

    /**
     * The descriptor for {@code name}, or {@code null} when the name is not in the catalog. Matched
     * <b>case-insensitively</b>, as every other function-name match on both surfaces is — not by
     * lower-casing the argument and comparing, which stopped working the moment the catalog gained
     * ClickHouse's camelCase names and silently answered "no such function" for every one of them.
     *
     * <p>Static because the descriptors are static data and the callers that need to read a declared return
     * type are deep inside translation ({@code translate.ExprFieldType}), where threading the bean would mean
     * a constructor change on {@code ExprTranslator} and everything that builds one. A lookup on a
     * fixed-size list is not worth an index.
     */
    public static QueryFunctionDto describe(String name) {
        if (name == null) {
            return null;
        }
        for (final QueryFunctionDto function : FUNCTIONS) {
            if (function.name().equalsIgnoreCase(name)) {
                return function;
            }
        }
        return null;
    }

    /**
     * The closing note of a DSL-only name's description: that SQL refuses the name, what to write there
     * instead, and where the SQL spellings are listed. "Also spelled with the operator" was the old wording,
     * and it implied the name worked in SQL too.
     */
    private static String dslOnlyNote(String sqlSpelling) {
        return DSL_ONLY_NOTE_PREFIX + ": write " + sqlSpelling + " there — GET /v1/queries/sql-functions lists the SQL spellings.";
    }

    private static QueryFunctionDto scalar(
            String name, String signature, QueryFunctionReturnType returns, String description, QueryFunctionArgDto... args) {
        return new QueryFunctionDto(name, QueryFunctionGroup.SCALAR, signature, returns, false, true, description, List.of(args));
    }

    /**
     * A scalar whose value is not reproducible — {@code now()} is the only one. Given its own factory rather
     * than a boolean parameter on {@link #scalar} so the property cannot be set by accident on a function
     * that is in fact deterministic: writing a non-deterministic descriptor takes naming it as such.
     */
    private static QueryFunctionDto nonDeterministicScalar(
            String name, String signature, QueryFunctionReturnType returns, String description, QueryFunctionArgDto... args) {
        return new QueryFunctionDto(name, QueryFunctionGroup.SCALAR, signature, returns, false, false, description, List.of(args));
    }

    private static QueryFunctionDto aggregate(
            String name, String signature, QueryFunctionReturnType returns, boolean distinct, String description, QueryFunctionArgDto... args) {
        return new QueryFunctionDto(name, QueryFunctionGroup.AGGREGATE, signature, returns, distinct, true, description, List.of(args));
    }

    /** A window-only ranking function: zero arguments, a 64-bit result, legal only under a window expression. */
    private static QueryFunctionDto window(String name, String description) {
        return new QueryFunctionDto(
                name, QueryFunctionGroup.WINDOW, name + "()", QueryFunctionReturnType.LONG, false, true, description, List.of());
    }

    private static QueryFunctionDto orderedSetAggregate(
            String name, String signature, QueryFunctionReturnType returns, String description, QueryFunctionArgDto... args) {
        return new QueryFunctionDto(
                name, QueryFunctionGroup.ORDERED_SET_AGGREGATE, signature, returns, false, true, description, List.of(args));
    }

    /**
     * A lambda argument — the per-element body of a higher-order array call, and the one argument kind that
     * is not a value. Given its own factory for the reason {@link #expression} has one: the kind is what a
     * client reads to know the call takes {@code x -> ...} in that position rather than an expression.
     */
    private static QueryFunctionArgDto lambda(String name) {
        return new QueryFunctionArgDto(name, QueryFunctionArgKind.LAMBDA, null, null, null);
    }

    private static QueryFunctionArgDto expression(String name) {
        return new QueryFunctionArgDto(name, QueryFunctionArgKind.EXPRESSION, null, null, null);
    }

    /**
     * A repeatable expression argument: one is required, further ones extend the call. {@code concat}'s
     * second argument, which is what makes its "two or more operands" arity discoverable rather than only
     * documented — the same shape {@link #pathKey()} gives the JSON extractors' variadic path.
     */
    private static QueryFunctionArgDto variadicExpression(String name) {
        return new QueryFunctionArgDto(name, QueryFunctionArgKind.EXPRESSION, null, Boolean.TRUE, null);
    }

    private static QueryFunctionArgDto literal(String name, QueryFunctionArgKind kind, QueryFunctionArgConstraintsDto constraints) {
        return new QueryFunctionArgDto(name, kind, null, null, constraints);
    }

    /** A repeatable string-literal JSON path segment: one is required, further segments nest deeper. */
    private static QueryFunctionArgDto pathKey() {
        return new QueryFunctionArgDto("key", QueryFunctionArgKind.STRING_LITERAL, null, Boolean.TRUE, null);
    }
}
