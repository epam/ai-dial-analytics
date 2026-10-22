package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.service.query.QueryFunctionCatalog;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionArgDto;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionArgKind;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionDto;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionGroup;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionReturnType;
import com.epam.aidial.analytics.web.dto.query.SqlFunctionDto;
import com.epam.aidial.analytics.web.dto.query.SqlFunctionForm;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The discovery source of truth for the <b>SQL</b> surface's function catalog, served by
 * {@code GET /v1/queries/sql-functions}: every spelling a caller may write in {@code execute-sql},
 * {@code validate-sql}, {@code translate-sql} and Flight SQL, how it is written there, and which DSL catalog
 * function it lowers onto.
 *
 * <p><b>Derived, not restated.</b> One entry per {@link QueryFunctionCatalog} descriptor outside
 * {@link #DSL_ONLY}, carrying that descriptor's group, return type, distinct support, determinism and
 * arguments; the maps below override only what SQL spells differently. The SQL-only entries follow —
 * operators, the standard functions that lower onto the DSL {@code case} expression, and the two keyword
 * constructs. So a DSL function added to the catalog appears here with no second edit, and
 * {@code SqlFunctionCatalogParityTest} fails until it has an example the SQL front end accepts.
 *
 * <p>Deliberately <b>not</b> derived from {@link SqlQueryOperators#TABLE}: that table carries internal
 * markers ({@code TO_STRING_CAST}, {@code __LAMBDAPARAM}) and chains the whole Calcite standard table, whose
 * names mostly validate and then fail at lowering ({@code SUBSTRING}, {@code POWER}). The parity test binds
 * the two the other way instead — every caller-facing name the custom table registers must be listed here.
 *
 * <p>This is also the model the Flight {@code SQL_*_FUNCTIONS} info sets would be projected from, should
 * they ever be declared — they are over SQL spellings, not DSL names ({@code AdasFlightSqlProducer} says why
 * they are still absent).
 */
@Component
public class SqlFunctionCatalog {

    /**
     * The DSL catalog names the SQL surface does not accept as function names: arithmetic is spelled with
     * operators there, and {@code minus(a, b)} cannot even parse ({@code MINUS} is the {@code EXCEPT} alias in
     * the Babel grammar). Each is the {@code dsl_function} of an operator entry instead.
     */
    static final Set<String> DSL_ONLY = Set.of("plus", "minus", "multiply", "div", "modulo");

    private static final String USAGE = " FROM dial_usage_log";
    private static final String METRICS = " FROM otel_claude_code_metrics";
    private static final String BODY = "\"dial_usage_log_payload.request_body\"";

    private static final Map<String, List<String>> ALIASES = Map.of(
            "length", List.of("char_length", "character_length"),
            "ceil", List.of("ceiling"),
            "now", List.of("current_timestamp", "localtimestamp"));

    private static final Map<String, String> SIGNATURES = Map.of(
            "count", "count(*) | count([DISTINCT] value)",
            "percentile_cont", "percentile_cont(fraction) WITHIN GROUP (ORDER BY value)",
            "percentile_disc", "percentile_disc(fraction) WITHIN GROUP (ORDER BY value)",
            "array_has_any", "array_has_any(array, ARRAY[value, ...])",
            "array_has_all", "array_has_all(array, ARRAY[value, ...])",
            "row_number", "row_number() OVER ([PARTITION BY ...] ORDER BY ...)",
            "rank", "rank() OVER ([PARTITION BY ...] ORDER BY ...)",
            "dense_rank", "dense_rank() OVER ([PARTITION BY ...] ORDER BY ...)");

    /** Sentences appended to the DSL description where SQL has a rule of its own. */
    private static final Map<String, String> SQL_NOTES = Map.of(
            "count", "In SQL the row count is written count(*); count() with no argument is an arity error.",
            "percentile_cont", "In SQL the ordered operand goes in the WITHIN GROUP (ORDER BY ...) clause, which is required.",
            "percentile_disc", "In SQL the ordered operand goes in the WITHIN GROUP (ORDER BY ...) clause, which is required.",
            "array_has_any", "In SQL the value list is an ARRAY[...] constructor.",
            "array_has_all", "In SQL the value list is an ARRAY[...] constructor.",
            "contains", "In a SQL predicate, x ILIKE '%text%' is the case-insensitive form.",
            "if", "In SQL a comparison is not accepted as the condition either; write CASE WHEN a > b THEN ... ELSE ... END, "
                    + "which is also the multi-branch form.");

    private static final Map<String, String> EXAMPLES = Map.ofEntries(
            Map.entry("abs", "SELECT abs(total_tokens - prompt_tokens) AS diff" + USAGE + " LIMIT 10"),
            Map.entry("arg_max", "SELECT chat_id, arg_max(deployment, request_time) AS last_deployment" + USAGE + " GROUP BY chat_id"),
            Map.entry("array_has", "SELECT count(*) AS n" + USAGE + " WHERE array_has(execution_path, 'gpt-4o')"),
            Map.entry("array_has_all", "SELECT count(*) AS n" + USAGE + " WHERE array_has_all(execution_path, ARRAY['assistant', 'gpt-4o'])"),
            Map.entry("array_has_any", "SELECT count(*) AS n" + USAGE + " WHERE array_has_any(execution_path, ARRAY['gpt-4o', 'gpt-4.1'])"),
            Map.entry("array_join", "SELECT array_join(execution_path) AS step, count(*) AS n" + USAGE + " GROUP BY step"),
            Map.entry("array_length", "SELECT array_length(execution_path) AS depth" + USAGE + " LIMIT 10"),
            Map.entry("array_slice", "SELECT array_slice(execution_path, 1, 2) AS head" + USAGE + " LIMIT 10"),
            Map.entry("array_to_string", "SELECT array_to_string(execution_path, ' > ') AS path" + USAGE + " LIMIT 10"),
            Map.entry("arrayAll", "SELECT count(*) AS n" + USAGE + " WHERE arrayAll(x -> not_empty(x), execution_path)"),
            Map.entry("arrayCount", "SELECT arrayCount(x -> starts_with(x, 'gpt-'), execution_path) AS models" + USAGE + " LIMIT 10"),
            Map.entry("arrayExists", "SELECT count(*) AS n" + USAGE + " WHERE arrayExists(x -> starts_with(x, 'gpt-'), execution_path)"),
            Map.entry("arrayFilter", "SELECT arrayFilter(x -> starts_with(x, 'gpt-'), execution_path) AS models" + USAGE + " LIMIT 10"),
            Map.entry("arrayMap", "SELECT arrayMap(x -> upper(x), execution_path) AS steps" + USAGE + " LIMIT 10"),
            Map.entry("avg", "SELECT deployment, avg(total_tokens) AS avg_tokens" + USAGE + " GROUP BY deployment"),
            Map.entry("ceil", "SELECT ceil(total_price) AS price" + USAGE + " LIMIT 10"),
            Map.entry("concat", "SELECT concat(parent_deployment, ' -> ', deployment) AS edge" + USAGE + " LIMIT 10"),
            Map.entry("contains", "SELECT count(*) AS n" + USAGE + " WHERE contains(deployment, 'gpt')"),
            Map.entry("count", "SELECT count(*) AS requests, count(DISTINCT chat_id) AS chats" + USAGE),
            Map.entry("date_add", "SELECT date_add('hour', 1, request_time) AS next_hour" + USAGE + " LIMIT 10"),
            Map.entry("date_bin", "SELECT date_bin(15, 'minute', request_time) AS bucket, count(*) AS n" + USAGE + " GROUP BY bucket"),
            Map.entry("date_sub", "SELECT count(*) AS n" + USAGE + " WHERE request_time >= date_sub('day', 7, now())"),
            Map.entry("date_trunc", "SELECT date_trunc('month', request_time) AS month, sum(total_price) AS cost" + USAGE + " GROUP BY month"),
            Map.entry("dense_rank", "SELECT deployment, dense_rank() OVER (ORDER BY total_tokens DESC) AS r" + USAGE + " LIMIT 10"),
            Map.entry("equals", "SELECT if(equals(deployment, parent_deployment), 'self', 'nested') AS kind" + USAGE + " LIMIT 10"),
            Map.entry("floor", "SELECT floor(total_price) AS price" + USAGE + " LIMIT 10"),
            Map.entry("group_uniq_array", "SELECT chat_id, group_uniq_array(deployment) AS deployments" + USAGE + " GROUP BY chat_id"),
            Map.entry("groupArrayArray", "SELECT chat_id, groupArrayArray(execution_path) AS steps" + USAGE + " GROUP BY chat_id"),
            Map.entry("groupUniqArrayArray", "SELECT chat_id, groupUniqArrayArray(execution_path) AS steps" + USAGE + " GROUP BY chat_id"),
            Map.entry("if", "SELECT if(success, 'ok', 'failed') AS outcome, count(*) AS n" + USAGE + " GROUP BY outcome"),
            Map.entry("json_extract_array", "SELECT json_extract_array(" + BODY + ", 'messages') AS messages" + USAGE + " LIMIT 10"),
            Map.entry("json_extract_bool", "SELECT json_extract_bool(" + BODY + ", 'stream') AS streamed" + USAGE + " LIMIT 10"),
            Map.entry("json_extract_double", "SELECT json_extract_double(" + BODY + ", 'temperature') AS temperature" + USAGE + " LIMIT 10"),
            Map.entry("json_extract_long", "SELECT json_extract_long(" + BODY + ", 'max_tokens') AS max_tokens" + USAGE + " LIMIT 10"),
            Map.entry("json_extract_string", "SELECT json_extract_string(" + BODY + ", 'model') AS model" + USAGE + " LIMIT 10"),
            Map.entry("json_extract_string_ci", "SELECT json_extract_string_ci(" + BODY + ", 'Model') AS model" + USAGE + " LIMIT 10"),
            Map.entry("length", "SELECT deployment, length(deployment) AS n" + USAGE + " LIMIT 10"),
            Map.entry("lower", "SELECT lower(deployment) AS deployment_lc" + USAGE + " LIMIT 10"),
            Map.entry("map_contains", "SELECT count(*) AS n" + METRICS + " WHERE map_contains(point_attributes, 'model')"),
            Map.entry("map_keys", "SELECT map_keys(point_attributes) AS attribute_keys" + METRICS + " LIMIT 10"),
            Map.entry("map_value", "SELECT map_value(point_attributes, 'model') AS model, sum(point_value) AS total" + METRICS
                    + " GROUP BY model"),
            Map.entry("match", "SELECT count(*) AS n" + USAGE + " WHERE match(deployment, '^gpt-4')"),
            Map.entry("max", "SELECT deployment, max(request_time) AS last_seen" + USAGE + " GROUP BY deployment"),
            Map.entry("min", "SELECT deployment, min(request_time) AS first_seen" + USAGE + " GROUP BY deployment"),
            Map.entry("not_empty", "SELECT count(*) AS n" + USAGE + " WHERE not_empty(chat_id)"),
            Map.entry("now", "SELECT count(*) AS n" + USAGE + " WHERE request_time >= now() - INTERVAL 1 HOUR"),
            Map.entry("percentile_cont", "SELECT deployment, percentile_cont(0.95) WITHIN GROUP (ORDER BY operation_duration_ms) AS p95"
                    + USAGE + " GROUP BY deployment"),
            Map.entry("percentile_disc", "SELECT deployment, percentile_disc(0.5) WITHIN GROUP (ORDER BY operation_duration_ms) AS median"
                    + USAGE + " GROUP BY deployment"),
            Map.entry("rank", "SELECT deployment, rank() OVER (ORDER BY total_tokens DESC) AS r" + USAGE + " LIMIT 10"),
            Map.entry("round", "SELECT round(total_price, 2) AS price" + USAGE + " LIMIT 10"),
            Map.entry("row_number", "SELECT chat_id, row_number() OVER (PARTITION BY chat_id ORDER BY request_time) AS turn"
                    + USAGE + " LIMIT 10"),
            Map.entry("split_string", "SELECT split_string(request_uri, '/') AS segments" + USAGE + " LIMIT 10"),
            Map.entry("starts_with", "SELECT count(*) AS n" + USAGE + " WHERE starts_with(deployment, 'gpt-')"),
            Map.entry("sum", "SELECT deployment, sum(total_price) AS cost" + USAGE + " GROUP BY deployment"),
            Map.entry("to_string", "SELECT to_string(response_status) AS status" + USAGE + " LIMIT 10"),
            Map.entry("trim", "SELECT trim(deployment) AS deployment" + USAGE + " LIMIT 10"),
            Map.entry("upper", "SELECT upper(deployment) AS deployment_uc" + USAGE + " LIMIT 10"),
            Map.entry("width_bucket", "SELECT width_bucket(operation_duration_ms, 0, 10000, 10) AS bucket, count(*) AS n"
                    + USAGE + " GROUP BY bucket"));

    private static final List<SqlFunctionDto> FUNCTIONS = build();

    /** The full SQL catalog, ordered by name compared case-insensitively. */
    public List<SqlFunctionDto> listFunctions() {
        return FUNCTIONS;
    }

    private static List<SqlFunctionDto> build() {
        final List<SqlFunctionDto> entries = new ArrayList<>();
        for (final QueryFunctionDto dsl : new QueryFunctionCatalog().listFunctions()) {
            if (!DSL_ONLY.contains(dsl.name())) {
                entries.add(derived(dsl));
            }
        }
        entries.add(arithmetic("+", "plus", "a + b",
                " Over a timestamp and an interval literal it is date_add instead: request_time + INTERVAL 1 HOUR.",
                "SELECT prompt_tokens + completion_tokens AS tokens" + USAGE + " LIMIT 10"));
        entries.add(arithmetic("-", "minus", "a - b",
                " Unary minus negates. Over a timestamp and an interval literal it is date_sub instead: now() - INTERVAL 2 HOUR.",
                "SELECT total_tokens - cached_prompt_tokens AS uncached" + USAGE + " LIMIT 10"));
        entries.add(arithmetic("*", "multiply", "a * b", "",
                "SELECT total_price * 100 AS cents" + USAGE + " LIMIT 10"));
        entries.add(arithmetic("/", "div", "a / b", "",
                "SELECT deployment, sum(total_price) / count(*) AS cost_per_request" + USAGE + " GROUP BY deployment"));
        entries.add(arithmetic("%", "modulo", "a % b", " MOD(a, b) is the function spelling.",
                "SELECT total_tokens % 1000 AS remainder" + USAGE + " LIMIT 10"));
        final QueryFunctionDto modulo = QueryFunctionCatalog.describe("modulo");
        entries.add(new SqlFunctionDto("mod", SqlFunctionForm.FUNCTION, List.of(), modulo.group(), "mod(a, b)",
                modulo.returns(), false, modulo.deterministic(),
                withoutDslOnlyNote(modulo.description()) + " The % operator is the operator spelling.",
                modulo.name(), "SELECT mod(total_tokens, 1000) AS remainder" + USAGE + " LIMIT 10", modulo.args()));
        final QueryFunctionDto concat = QueryFunctionCatalog.describe("concat");
        entries.add(new SqlFunctionDto("||", SqlFunctionForm.OPERATOR, List.of(), concat.group(), "a || b",
                concat.returns(), false, concat.deterministic(),
                "Text concatenation, a second spelling of concat: each operand is rendered as to_string renders it, so "
                        + "'depth ' || 4 is accepted, and a null operand contributes the empty string rather than "
                        + "making the result null — concat's rule, not standard ||'s null propagation.",
                concat.name(), "SELECT deployment || ' @ ' || project_id AS label" + USAGE + " LIMIT 10",
                List.of(expression("a"), expression("b"))));
        entries.add(like("like", "not like", "case-sensitively",
                "SELECT count(*) AS n" + USAGE + " WHERE deployment LIKE '%gpt%'"));
        entries.add(like("ilike", "not ilike", "ignoring case",
                "SELECT count(*) AS n" + USAGE + " WHERE deployment ILIKE '%GPT%'"));
        entries.add(new SqlFunctionDto("coalesce", SqlFunctionForm.FUNCTION, List.of(), QueryFunctionGroup.SCALAR,
                "coalesce(value, value[, value...])", QueryFunctionReturnType.SAME_AS_ARGUMENT, false, true,
                "The first non-null operand, or null when every operand is null. Operands unify as CASE branches do: "
                        + "numeric types widen against each other, any other mismatch is refused. Lowers onto a CASE "
                        + "expression rather than a catalog function, hence no dsl_function.",
                null, "SELECT coalesce(parent_deployment, deployment) AS root" + USAGE + " LIMIT 10",
                List.of(expression("value"), variadic("value"))));
        entries.add(new SqlFunctionDto("nullif", SqlFunctionForm.FUNCTION, List.of(), QueryFunctionGroup.SCALAR,
                "nullif(a, b)", QueryFunctionReturnType.SAME_AS_ARGUMENT, false, true,
                "Null when a equals b, otherwise a — the usual way to turn a sentinel such as the empty string into a "
                        + "null. Lowers onto a CASE expression rather than a catalog function, hence no dsl_function.",
                null, "SELECT nullif(parent_deployment, '') AS parent" + USAGE + " LIMIT 10",
                List.of(expression("a"), expression("b"))));
        entries.add(new SqlFunctionDto("case", SqlFunctionForm.SYNTAX, List.of(), QueryFunctionGroup.SCALAR,
                "CASE WHEN condition THEN value [WHEN ...] [ELSE value] END | CASE value WHEN match THEN value [...] [ELSE value] END",
                QueryFunctionReturnType.SAME_AS_ARGUMENT, false, true,
                "The multi-branch conditional, in both its searched and simple forms. A WHEN condition is anything a WHERE "
                        + "accepts, comparisons included — unlike if's condition. Branches must unify: numeric types "
                        + "widen against each other, any other mismatch (THEN 'yes' ELSE 1) is refused rather than "
                        + "coerced to text; a branch may be NULL when another has a type, and an omitted ELSE yields "
                        + "null. Accepted wherever an expression is, aggregate arguments included "
                        + "(sum(CASE WHEN success THEN 1 ELSE 0 END)). Lowers onto the DSL case expression rather than "
                        + "a catalog function, hence no dsl_function.",
                null, "SELECT CASE WHEN total_tokens > 10000 THEN 'large' WHEN total_tokens > 1000 THEN 'medium' ELSE 'small' END"
                        + " AS size, count(*) AS n" + USAGE + " GROUP BY size",
                List.of()));
        final QueryFunctionDto toString = QueryFunctionCatalog.describe("to_string");
        entries.add(new SqlFunctionDto("cast", SqlFunctionForm.SYNTAX, List.of(), toString.group(), "CAST(value AS VARCHAR)",
                toString.returns(), false, toString.deterministic(),
                "Renders a non-text value as text, as to_string does — which is what it lowers onto. Over a column this "
                        + "surface already types as text (string, uuid, enum, object, map) it is a no-op and the column "
                        + "passes through. A declared length (VARCHAR(50)) is ignored. Casts to other types are accepted "
                        + "only where the validator would insert the same identity or widening cast itself.",
                toString.name(), "SELECT CAST(response_status AS VARCHAR) AS status" + USAGE + " LIMIT 10",
                List.of(expression("value"))));
        entries.sort(Comparator.comparing(SqlFunctionDto::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(entries);
    }

    private static SqlFunctionDto derived(QueryFunctionDto dsl) {
        final String name = dsl.name();
        final String note = SQL_NOTES.get(name);
        return new SqlFunctionDto(
                name, SqlFunctionForm.FUNCTION, ALIASES.getOrDefault(name, List.of()), dsl.group(),
                SIGNATURES.getOrDefault(name, dsl.signature()), dsl.returns(), dsl.distinctSupported(), dsl.deterministic(),
                note == null ? dsl.description() : dsl.description() + " " + note,
                name, EXAMPLES.get(name), dsl.args());
    }

    /** An arithmetic operator: the DSL function's semantics, minus its "not callable in SQL" note. */
    private static SqlFunctionDto arithmetic(String operator, String dslName, String signature, String extra, String example) {
        final QueryFunctionDto dsl = QueryFunctionCatalog.describe(dslName);
        return new SqlFunctionDto(
                operator, SqlFunctionForm.OPERATOR, List.of(), dsl.group(), signature, dsl.returns(), false,
                dsl.deterministic(), withoutDslOnlyNote(dsl.description()) + extra, dslName, example, dsl.args());
    }

    private static SqlFunctionDto like(String name, String negated, String how, String example) {
        return new SqlFunctionDto(
                name, SqlFunctionForm.OPERATOR, List.of(negated), QueryFunctionGroup.SCALAR,
                "text " + name.toUpperCase(Locale.ROOT) + " '%substring%'", QueryFunctionReturnType.BOOLEAN, false, true,
                "Substring test, compared " + how + "; " + negated.toUpperCase(Locale.ROOT) + " negates it. Only the "
                        + "contains pattern is accepted: exactly '%text%', a literal, with no % or _ inside text and no "
                        + "ESCAPE — a prefix or suffix pattern ('gpt%') is refused; use starts_with for a prefix. A "
                        + "predicate, so it belongs in WHERE, HAVING, ON or a CASE WHEN. Lowers onto the filter "
                        + "grammar's contains comparison rather than a catalog function, hence no dsl_function.",
                null, example,
                List.of(expression("text"), new QueryFunctionArgDto("pattern", QueryFunctionArgKind.STRING_LITERAL, null, null, null)));
    }

    /** The DSL description with its closing "not callable by this name in SQL" note cut off. */
    private static String withoutDslOnlyNote(String description) {
        final int note = description.indexOf(QueryFunctionCatalog.DSL_ONLY_NOTE_PREFIX);
        return note < 0 ? description : description.substring(0, note).stripTrailing();
    }

    private static QueryFunctionArgDto expression(String name) {
        return new QueryFunctionArgDto(name, QueryFunctionArgKind.EXPRESSION, null, null, null);
    }

    private static QueryFunctionArgDto variadic(String name) {
        return new QueryFunctionArgDto(name, QueryFunctionArgKind.EXPRESSION, null, Boolean.TRUE, null);
    }
}
