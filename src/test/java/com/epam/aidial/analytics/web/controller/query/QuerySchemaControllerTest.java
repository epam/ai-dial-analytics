package com.epam.aidial.analytics.web.controller.query;

import com.epam.aidial.analytics.exception.QueryEntityNotFoundException;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.QueryFunctionCatalog;
import com.epam.aidial.analytics.service.query.sql.SqlFunctionCatalog;
import com.epam.aidial.analytics.web.dto.query.QueryEntityDto;
import com.epam.aidial.analytics.web.dto.query.QueryEntitySchemaDto;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionDto;
import com.epam.aidial.analytics.web.dto.query.QuerySchemaFieldDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QuerySchemaController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({QueryFunctionCatalog.class, SqlFunctionCatalog.class})
class QuerySchemaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CatalogQueryService catalog;

    @Test
    void listsEntities() throws Exception {
        BDDMockito.given(catalog.listEntities())
                .willReturn(List.of(new QueryEntityDto("dial_usage_log", true)));

        mockMvc.perform(get("/v1/queries/entities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("dial_usage_log"));
    }

    @Test
    void returnsBaseSchema() throws Exception {
        BDDMockito.given(catalog.getBaseSchema("dial_usage_log"))
                .willReturn(new QueryEntitySchemaDto(
                        "dial_usage_log",
                        List.of(
                                new QuerySchemaFieldDto("project_id", QueryFieldType.STRING, null, null, "project_id",
                                        "Identifier of the DIAL project the request belongs to.", "Project ID", null,
                                        false, false),
                                new QuerySchemaFieldDto("dial_usage_log_payload.response_body", QueryFieldType.STRING, null, null, "dial_usage_log_payload.response_body",
                                        null, null, "payload", true, true),
                                new QuerySchemaFieldDto("chat_id", QueryFieldType.STRING, null, null, "chat_id",
                                        null, null, null, false, false))));

        mockMvc.perform(get("/v1/queries/entities/schema/dial_usage_log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entity").value("dial_usage_log"))
                .andExpect(jsonPath("$.fields[0].name").value("project_id"))
                .andExpect(jsonPath("$.fields[0].type").value("string"))
                .andExpect(jsonPath("$.fields[0].description")
                        .value("Identifier of the DIAL project the request belongs to."))
                .andExpect(jsonPath("$.fields[0].display_name").value("Project ID"))
                // The heavy flag is serialized per field: true for the payload column, false elsewhere.
                .andExpect(jsonPath("$.fields[0].heavy").value(false))
                .andExpect(jsonPath("$.fields[1].name").value("dial_usage_log_payload.response_body"))
                .andExpect(jsonPath("$.fields[1].heavy").value(true))
                .andExpect(jsonPath("$.fields[1].sensitive").value(true))
                .andExpect(jsonPath("$.fields[2].name").value("chat_id"))
                .andExpect(jsonPath("$.fields[2].description").isEmpty())
                .andExpect(jsonPath("$.fields[2].display_name").isEmpty())
                .andExpect(jsonPath("$.fields[2].heavy").value(false))
                // No ordering set on this stub → tag_order is serialized as null (present, empty).
                .andExpect(jsonPath("$.tag_order").isEmpty());
    }

    @Test
    void returnsTagOrderWhenSet() throws Exception {
        BDDMockito.given(catalog.getBaseSchema("dial_usage_log"))
                .willReturn(new QueryEntitySchemaDto(
                        "dial_usage_log",
                        List.of("identifier", "cost"),
                        List.of(new QuerySchemaFieldDto("chat_id", QueryFieldType.STRING, null, null, "chat_id",
                                null, null, "identifier", false, false))));

        mockMvc.perform(get("/v1/queries/entities/schema/dial_usage_log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tag_order[0]").value("identifier"))
                .andExpect(jsonPath("$.tag_order[1]").value("cost"));
    }

    // Positions in the alphabetically-ordered function catalog. Named once here because adding a function
    // shifts every later index — the seven added for derivation expressions moved most of them — and a wall
    // of renumbered literals is where that edit goes wrong silently.
    private static final int ARG_MAX = index("arg_max");
    private static final int COUNT = index("count");
    private static final int DATE_BIN = index("date_bin");
    private static final int DATE_TRUNC = index("date_trunc");
    private static final int GROUP_UNIQ_ARRAY = index("group_uniq_array");
    private static final int IF = index("if");
    private static final int JSON_EXTRACT_ARRAY = index("json_extract_array");
    private static final int JSON_EXTRACT_BOOL = index("json_extract_bool");
    private static final int JSON_EXTRACT_DOUBLE = index("json_extract_double");
    private static final int JSON_EXTRACT_LONG = index("json_extract_long");
    private static final int JSON_EXTRACT_STRING = index("json_extract_string");
    private static final int JSON_EXTRACT_STRING_CI = index("json_extract_string_ci");
    private static final int MAX = index("max");
    private static final int MIN = index("min");
    private static final int NOT_EMPTY = index("not_empty");
    private static final int PERCENTILE_CONT = index("percentile_cont");
    private static final int PERCENTILE_DISC = index("percentile_disc");
    private static final int STARTS_WITH = index("starts_with");
    private static final int SUM = index("sum");
    private static final int DIV = index("div");
    private static final int MODULO = index("modulo");
    private static final int ROUND = index("round");
    private static final int NOW = index("now");
    private static final int DATE_ADD = index("date_add");
    private static final int DATE_SUB = index("date_sub");
    private static final int MAP_VALUE = index("map_value");
    private static final int MAP_CONTAINS = index("map_contains");
    private static final int MAP_KEYS = index("map_keys");
    private static final int ARRAY_HAS = index("array_has");
    private static final int ARRAY_LENGTH = index("array_length");
    private static final int SPLIT_STRING = index("split_string");
    private static final int CONTAINS = index("contains");
    private static final int EQUALS = index("equals");

    /**
     * The advertised position of a function, read from the catalog rather than written down.
     *
     * <p>These were literal indices until the catalog grew by sixteen entries and every one of them shifted.
     * The assertions below are about each entry's <em>content</em>, not about its ordinal — the ordering
     * itself is asserted once, by {@link #listsSupportedFunctionsAlphabetically} — so deriving the position
     * keeps that one fact in one place and stops a catalog addition from failing a dozen unrelated tests.
     */
    private static int index(String name) {
        final List<QueryFunctionDto> functions = new QueryFunctionCatalog().listFunctions();
        for (int i = 0; i < functions.size(); i++) {
            if (functions.get(i).name().equals(name)) {
                return i;
            }
        }
        throw new IllegalStateException("no catalog function named '" + name + "'");
    }

    @Test
    void listsSupportedFunctionsAlphabetically() throws Exception {
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(63)))
                // Case-insensitively alphabetical: the ClickHouse-named functions are camelCase, and an
                // underscore sorts before a letter, so array_* precedes arrayAll and group_uniq_array
                // precedes groupArrayArray.
                .andExpect(jsonPath("$[*].name").value(contains(
                        "abs", "arg_max", "array_has", "array_has_all", "array_has_any", "array_join", "array_length",
                        "array_slice", "array_to_string",
                        "arrayAll", "arrayCount", "arrayExists", "arrayFilter", "arrayMap",
                        "avg",
                        "ceil", "concat", "contains", "count", "date_add", "date_bin", "date_sub", "date_trunc",
                        "dense_rank", "div",
                        "equals", "floor",
                        "group_uniq_array", "groupArrayArray", "groupUniqArrayArray",
                        "if", "json_extract_array", "json_extract_bool", "json_extract_double",
                        "json_extract_long", "json_extract_string", "json_extract_string_ci", "length", "lower",
                        "map_contains", "map_keys", "map_value", "match", "max", "min", "minus", "modulo",
                        "multiply",
                        "not_empty", "now", "percentile_cont", "percentile_disc", "plus", "rank", "round",
                        "row_number",
                        "split_string", "starts_with", "sum", "to_string", "trim", "upper", "width_bucket")));
    }

    @Test
    void windowOnlyFunctionsAreDiscoverable() throws Exception {
        final int rowNumber = index("row_number");
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + rowNumber + "].name").value("row_number"))
                .andExpect(jsonPath("$[" + rowNumber + "].group").value("window"))
                .andExpect(jsonPath("$[" + rowNumber + "].returns").value("long"))
                .andExpect(jsonPath("$[" + rowNumber + "].distinct_supported").value(false))
                .andExpect(jsonPath("$[" + rowNumber + "].args", hasSize(0)))
                .andExpect(jsonPath("$[" + rowNumber + "].description").value(containsString("window expression")))
                .andExpect(jsonPath("$[" + index("rank") + "].group").value("window"))
                .andExpect(jsonPath("$[" + index("dense_rank") + "].group").value("window"));
    }

    @Test
    void theClockIsDiscoverableAndMarkedNonDeterministic() throws Exception {
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + NOW + "].name").value("now"))
                .andExpect(jsonPath("$[" + NOW + "].group").value("scalar"))
                .andExpect(jsonPath("$[" + NOW + "].signature").value("now()"))
                .andExpect(jsonPath("$[" + NOW + "].returns").value("timestamp"))
                .andExpect(jsonPath("$[" + NOW + "].args", hasSize(0)))
                .andExpect(jsonPath("$[" + NOW + "].distinct_supported").value(false))
                .andExpect(jsonPath("$[" + NOW + "].deterministic").value(false))
                // The three facts a client cannot guess and would otherwise discover by being rejected:
                // whose clock it is, what zone it is in, and that a pipeline will refuse it.
                .andExpect(jsonPath("$[" + NOW + "].description").value(containsString("DATA STORE's clock")))
                .andExpect(jsonPath("$[" + NOW + "].description").value(containsString("UTC")))
                .andExpect(jsonPath("$[" + NOW + "].description").value(containsString("REFUSED in a pipeline")));
    }

    @Test
    void offsetFunctionsAdvertiseTheirLiteralConstraints() throws Exception {
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + DATE_ADD + "].name").value("date_add"))
                .andExpect(jsonPath("$[" + DATE_ADD + "].group").value("scalar"))
                .andExpect(jsonPath("$[" + DATE_ADD + "].returns").value("timestamp"))
                .andExpect(jsonPath("$[" + DATE_ADD + "].deterministic").value(true))
                .andExpect(jsonPath("$[" + DATE_ADD + "].args", hasSize(3)))
                .andExpect(jsonPath("$[" + DATE_ADD + "].args[0].name").value("unit"))
                .andExpect(jsonPath("$[" + DATE_ADD + "].args[0].kind").value("string_literal"))
                .andExpect(jsonPath("$[" + DATE_ADD + "].args[0].constraints.allowed_values")
                        .value(contains("day", "hour", "minute", "month", "second", "year")))
                .andExpect(jsonPath("$[" + DATE_ADD + "].args[1].name").value("amount"))
                .andExpect(jsonPath("$[" + DATE_ADD + "].args[1].kind").value("integer_literal"))
                .andExpect(jsonPath("$[" + DATE_ADD + "].args[1].constraints.min").value(1))
                .andExpect(jsonPath("$[" + DATE_ADD + "].args[2].name").value("timestamp"))
                .andExpect(jsonPath("$[" + DATE_ADD + "].args[2].kind").value("expression"))
                .andExpect(jsonPath("$[" + DATE_SUB + "].name").value("date_sub"))
                .andExpect(jsonPath("$[" + DATE_SUB + "].returns").value("timestamp"))
                .andExpect(jsonPath("$[" + DATE_SUB + "].args[0].constraints.allowed_values")
                        .value(contains("day", "hour", "minute", "month", "second", "year")))
                .andExpect(jsonPath("$[" + DATE_SUB + "].args[1].constraints.min").value(1));
    }

    /**
     * The member is on every entry, not only where it is false — a client reading a catalog that omits it
     * cannot tell "deterministic" from "a service too old to say".
     */
    @Test
    void everyEntryStatesItsDeterminism() throws Exception {
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.deterministic == true)]", hasSize(62)))
                .andExpect(jsonPath("$[?(@.deterministic == false)].name").value(contains("now")));
    }

    @Test
    void arithmeticScalarsAreDiscoverable() throws Exception {
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + DIV + "].name").value("div"))
                .andExpect(jsonPath("$[" + DIV + "].group").value("scalar"))
                .andExpect(jsonPath("$[" + DIV + "].returns").value("numeric"))
                .andExpect(jsonPath("$[" + DIV + "].args", hasSize(2)))
                // The zero-divisor contract is the fact a client cannot guess, so the description must say it.
                .andExpect(jsonPath("$[" + DIV + "].description").value(containsString("yields NULL")))
                .andExpect(jsonPath("$[" + MODULO + "].name").value("modulo"))
                .andExpect(jsonPath("$[" + MODULO + "].returns").value("numeric"))
                .andExpect(jsonPath("$[" + MODULO + "].description").value(containsString("yields NULL")))
                // The two sign conventions disagree, so the advertised one has to be stated.
                .andExpect(jsonPath("$[" + MODULO + "].description")
                        .value(containsString("SIGN OF THE LEFT OPERAND")));
    }

    @Test
    void arithmeticScalarsSayTheyAreNotCallableInSql() throws Exception {
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name in ['plus', 'minus', 'multiply', 'div', 'modulo'])].description",
                        hasSize(5)))
                .andExpect(jsonPath("$[?(@.name in ['plus', 'minus', 'multiply', 'div', 'modulo'])].description",
                        everyItem(containsString("Not callable by this name in SQL"))))
                .andExpect(jsonPath("$[?(@.name in ['plus', 'minus', 'multiply', 'div', 'modulo'])].description",
                        everyItem(containsString("GET /v1/queries/sql-functions"))))
                // "Also spelled with the + operator" implied the name worked in SQL too.
                .andExpect(jsonPath("$[*].description", everyItem(not(containsString("Also spelled")))))
                .andExpect(jsonPath("$[" + MODULO + "].description").value(containsString("write a % b or MOD(a, b)")));
    }

    @Test
    void sqlFunctionCatalogIsAnOrderedEnvelope() throws Exception {
        final String body = mockMvc.perform(get("/v1/queries/sql-functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.functions").isArray())
                .andReturn().getResponse().getContentAsString();
        // A strict parse: jsonPath accepts input a real client's parser would reject.
        final JsonNode functions = new ObjectMapper().readTree(body).get("functions");
        final List<String> names = new ArrayList<>();
        functions.forEach(entry -> names.add(entry.get("name").asText()));
        assertThat(names).isNotEmpty().isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
        // Every member on every entry, dsl_function included where it is null: "lowers onto no catalog
        // function" is an answer a client joins on.
        for (final JsonNode entry : functions) {
            final List<String> members = new ArrayList<>();
            entry.fieldNames().forEachRemaining(members::add);
            assertThat(members).as("members of '%s'", entry.get("name").asText()).contains(
                    "name", "form", "aliases", "group", "signature", "returns", "distinct_supported", "deterministic",
                    "description", "dsl_function", "example", "args");
        }
    }

    @Test
    void sqlFunctionCatalogReplacesTheDslOnlyNamesWithOperators() throws Exception {
        mockMvc.perform(get("/v1/queries/sql-functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.functions[*].name", not(hasItem("plus"))))
                .andExpect(jsonPath("$.functions[*].name", not(hasItem("minus"))))
                .andExpect(jsonPath("$.functions[*].name", not(hasItem("multiply"))))
                .andExpect(jsonPath("$.functions[*].name", not(hasItem("div"))))
                .andExpect(jsonPath("$.functions[*].name", not(hasItem("modulo"))))
                .andExpect(jsonPath("$.functions[?(@.name == '+')].form").value(contains("operator")))
                .andExpect(jsonPath("$.functions[?(@.name == '+')].dsl_function").value(contains("plus")))
                .andExpect(jsonPath("$.functions[?(@.name == '-')].dsl_function").value(contains("minus")))
                .andExpect(jsonPath("$.functions[?(@.name == '*')].dsl_function").value(contains("multiply")))
                .andExpect(jsonPath("$.functions[?(@.name == '/')].dsl_function").value(contains("div")))
                .andExpect(jsonPath("$.functions[?(@.name == '/')].description").value(contains(containsString("yields NULL"))))
                .andExpect(jsonPath("$.functions[?(@.name == '%%')].dsl_function").value(contains("modulo")))
                .andExpect(jsonPath("$.functions[?(@.name == 'mod')].form").value(contains("function")))
                .andExpect(jsonPath("$.functions[?(@.name == 'mod')].dsl_function").value(contains("modulo")))
                .andExpect(jsonPath("$.functions[?(@.name == '||')].dsl_function").value(contains("concat")));
    }

    @Test
    void sqlFunctionCatalogListsTheSqlOnlySpellings() throws Exception {
        mockMvc.perform(get("/v1/queries/sql-functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.functions[?(@.name == 'coalesce')].form").value(contains("function")))
                .andExpect(jsonPath("$.functions[?(@.name == 'coalesce')].dsl_function").value(contains(nullValue())))
                .andExpect(jsonPath("$.functions[?(@.name == 'nullif')].form").value(contains("function")))
                .andExpect(jsonPath("$.functions[?(@.name == 'case')].form").value(contains("syntax")))
                .andExpect(jsonPath("$.functions[?(@.name == 'cast')].form").value(contains("syntax")))
                .andExpect(jsonPath("$.functions[?(@.name == 'cast')].dsl_function").value(contains("to_string")))
                .andExpect(jsonPath("$.functions[?(@.name == 'like')].form").value(contains("operator")))
                .andExpect(jsonPath("$.functions[?(@.name == 'like')].returns").value(contains("boolean")))
                .andExpect(jsonPath("$.functions[?(@.name == 'like')].aliases[*]").value(contains("not like")))
                .andExpect(jsonPath("$.functions[?(@.name == 'like')].description").value(contains(containsString("'%text%'"))))
                .andExpect(jsonPath("$.functions[?(@.name == 'ilike')].aliases[*]").value(contains("not ilike")));
    }

    @Test
    void sqlFunctionCatalogListsSynonymsAsAliases() throws Exception {
        mockMvc.perform(get("/v1/queries/sql-functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.functions[?(@.name == 'length')].aliases[*]")
                        .value(hasItems("char_length", "character_length")))
                .andExpect(jsonPath("$.functions[?(@.name == 'ceil')].aliases[*]").value(contains("ceiling")))
                .andExpect(jsonPath("$.functions[?(@.name == 'now')].aliases[*]")
                        .value(hasItems("current_timestamp", "localtimestamp")))
                .andExpect(jsonPath("$.functions[?(@.name == 'now')].deterministic").value(contains(false)))
                .andExpect(jsonPath("$.functions[?(@.name == 'length')].dsl_function").value(contains("length")));
    }

    @Test
    void sqlFunctionCatalogWritesSignaturesTheWaySqlRequires() throws Exception {
        mockMvc.perform(get("/v1/queries/sql-functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.functions[?(@.name == 'percentile_cont')].signature")
                        .value(contains(containsString("WITHIN GROUP (ORDER BY"))))
                .andExpect(jsonPath("$.functions[?(@.name == 'percentile_disc')].signature")
                        .value(contains(containsString("WITHIN GROUP (ORDER BY"))))
                .andExpect(jsonPath("$.functions[?(@.name == 'count')].signature").value(contains(containsString("count(*)"))))
                .andExpect(jsonPath("$.functions[?(@.name == 'array_has_any')].signature").value(contains(containsString("ARRAY["))))
                .andExpect(jsonPath("$.functions[?(@.name == 'array_has_all')].signature").value(contains(containsString("ARRAY["))))
                .andExpect(jsonPath("$.functions[?(@.name == 'row_number')].signature").value(contains(containsString("OVER ("))))
                .andExpect(jsonPath("$.functions[?(@.name == 'rank')].signature").value(contains(containsString("OVER ("))))
                .andExpect(jsonPath("$.functions[?(@.name == 'dense_rank')].signature").value(contains(containsString("OVER ("))));
    }

    @Test
    void roundAdvertisesItsOptionalPrecision() throws Exception {
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + ROUND + "].name").value("round"))
                .andExpect(jsonPath("$[" + ROUND + "].returns").value("numeric"))
                .andExpect(jsonPath("$[" + ROUND + "].args", hasSize(2)))
                .andExpect(jsonPath("$[" + ROUND + "].args[0].optional").doesNotExist())
                .andExpect(jsonPath("$[" + ROUND + "].args[1].name").value("digits"))
                .andExpect(jsonPath("$[" + ROUND + "].args[1].kind").value("integer_literal"))
                .andExpect(jsonPath("$[" + ROUND + "].args[1].optional").value(true));
    }

    @Test
    void mapAccessorsAdvertiseSingleLiteralKey() throws Exception {
        // Not variadic is the load-bearing difference from every json_extract_* entry: these match one flat
        // key literally, and a client reading a variadic path here would write a dotted key as a path.
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + MAP_VALUE + "].name").value("map_value"))
                .andExpect(jsonPath("$[" + MAP_VALUE + "].returns").value("string"))
                .andExpect(jsonPath("$[" + MAP_VALUE + "].args", hasSize(2)))
                .andExpect(jsonPath("$[" + MAP_VALUE + "].args[1].kind").value("string_literal"))
                .andExpect(jsonPath("$[" + MAP_VALUE + "].args[1].variadic").doesNotExist())
                .andExpect(jsonPath("$[" + MAP_VALUE + "].description").value(containsString("NOT a path")))
                .andExpect(jsonPath("$[" + MAP_CONTAINS + "].name").value("map_contains"))
                .andExpect(jsonPath("$[" + MAP_CONTAINS + "].returns").value("boolean"))
                .andExpect(jsonPath("$[" + MAP_CONTAINS + "].args[1].variadic").doesNotExist())
                .andExpect(jsonPath("$[" + MAP_CONTAINS + "].description").value(containsString("NOT a path")));
    }

    @Test
    void mapKeysAdvertisesAnArrayReturnAndSingleArity() throws Exception {
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + MAP_KEYS + "].name").value("map_keys"))
                .andExpect(jsonPath("$[" + MAP_KEYS + "].group").value("scalar"))
                .andExpect(jsonPath("$[" + MAP_KEYS + "].returns").value("array"))
                .andExpect(jsonPath("$[" + MAP_KEYS + "].args", hasSize(1)))
                .andExpect(jsonPath("$[" + MAP_KEYS + "].description")
                        .value(containsString("projectable only")));
    }

    @Test
    void arrayPredicatesAdvertiseBooleanReturn() throws Exception {
        // Boolean is what tells a client they may supply if's condition, which is how a closed value set
        // becomes a distribution in one query.
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + ARRAY_HAS + "].name").value("array_has"))
                .andExpect(jsonPath("$[" + ARRAY_HAS + "].returns").value("boolean"))
                .andExpect(jsonPath("$[" + ARRAY_HAS + "].args", hasSize(2)))
                .andExpect(jsonPath("$[" + ARRAY_LENGTH + "].name").value("array_length"))
                .andExpect(jsonPath("$[" + ARRAY_LENGTH + "].returns").value("integer"))
                .andExpect(jsonPath("$[" + ARRAY_LENGTH + "].args", hasSize(1)));
    }

    @Test
    void arrayJoinAdvertisesElementReturnAndSingleOperand() throws Exception {
        final int arrayJoin = index("array_join");
        final String body = mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + arrayJoin + "].name").value("array_join"))
                .andExpect(jsonPath("$[" + arrayJoin + "].group").value("scalar"))
                .andExpect(jsonPath("$[" + arrayJoin + "].signature").value("array_join(array)"))
                // element_of_argument, not array and not same_as_argument: the result is one ELEMENT of the
                // operand, which neither of the other two tokens can name.
                .andExpect(jsonPath("$[" + arrayJoin + "].returns").value("element_of_argument"))
                .andExpect(jsonPath("$[" + arrayJoin + "].distinct_supported").value(false))
                // Exactly one argument, and no constraints object: there is no mode operand to constrain.
                .andExpect(jsonPath("$[" + arrayJoin + "].args", hasSize(1)))
                .andExpect(jsonPath("$[" + arrayJoin + "].args[0].kind").value("expression"))
                // The row-count effect is the one thing a client cannot infer from the signature.
                .andExpect(jsonPath("$[" + arrayJoin + "].description",
                        containsString("CHANGES THE NUMBER OF ROWS")))
                .andReturn().getResponse().getContentAsString();
        // A strict parse, not only jsonPath: jsonPath accepts input jackson would reject, so a malformed
        // response can satisfy every matcher above and still be unusable to a client.
        assertThatCode(() -> new ObjectMapper().readTree(body)).doesNotThrowAnyException();
    }

    @Test
    void theTextCompositionFunctionsAreDiscoverable() throws Exception {
        final int concat = index("concat");
        final int toString = index("to_string");
        final String body = mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + concat + "].name").value("concat"))
                .andExpect(jsonPath("$[" + concat + "].group").value("scalar"))
                .andExpect(jsonPath("$[" + concat + "].signature").value("concat(value, value[, value...])"))
                .andExpect(jsonPath("$[" + concat + "].returns").value("string"))
                .andExpect(jsonPath("$[" + concat + "].distinct_supported").value(false))
                // Two declared arguments, the second variadic: that pair is how "two or more operands"
                // becomes discoverable rather than only documented.
                .andExpect(jsonPath("$[" + concat + "].args", hasSize(2)))
                .andExpect(jsonPath("$[" + concat + "].args[1].variadic").value(true))
                .andExpect(jsonPath("$[" + concat + "].description", containsString("null")))
                .andExpect(jsonPath("$[" + toString + "].name").value("to_string"))
                .andExpect(jsonPath("$[" + toString + "].signature").value("to_string(value)"))
                .andExpect(jsonPath("$[" + toString + "].returns").value("string"))
                .andExpect(jsonPath("$[" + toString + "].args", hasSize(1)))
                .andExpect(jsonPath("$[" + toString + "].args[0].kind").value("expression"))
                // The null rule is the one thing a client cannot infer from the signature, and it differs
                // from concat's on purpose.
                .andExpect(jsonPath("$[" + toString + "].description", containsString("NULL")))
                .andReturn().getResponse().getContentAsString();
        assertThatCode(() -> new ObjectMapper().readTree(body)).doesNotThrowAnyException();
    }

    @Test
    void theArrayTextAndSlicingFunctionsAreDiscoverable() throws Exception {
        final int arrayToString = index("array_to_string");
        final int arraySlice = index("array_slice");
        final String body = mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + arrayToString + "].name").value("array_to_string"))
                .andExpect(jsonPath("$[" + arrayToString + "].signature")
                        .value("array_to_string(array, delimiter)"))
                .andExpect(jsonPath("$[" + arrayToString + "].returns").value("string"))
                .andExpect(jsonPath("$[" + arrayToString + "].args", hasSize(2)))
                .andExpect(jsonPath("$[" + arrayToString + "].args[1].kind").value("string_literal"))
                .andExpect(jsonPath("$[" + arraySlice + "].name").value("array_slice"))
                .andExpect(jsonPath("$[" + arraySlice + "].signature")
                        .value("array_slice(array, offset, length)"))
                // array, not element_of_argument: a slice narrows an array and answers with one.
                .andExpect(jsonPath("$[" + arraySlice + "].returns").value("array"))
                .andExpect(jsonPath("$[" + arraySlice + "].args", hasSize(3)))
                .andExpect(jsonPath("$[" + arraySlice + "].args[1].kind").value("integer_literal"))
                .andExpect(jsonPath("$[" + arraySlice + "].args[2].kind").value("integer_literal"))
                .andExpect(jsonPath("$[" + arraySlice + "].args[2].constraints.min").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThatCode(() -> new ObjectMapper().readTree(body)).doesNotThrowAnyException();
    }

    @Test
    void splitStringAdvertisesAnArrayReturn() throws Exception {
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + SPLIT_STRING + "].name").value("split_string"))
                .andExpect(jsonPath("$[" + SPLIT_STRING + "].returns").value("array"))
                .andExpect(jsonPath("$[" + SPLIT_STRING + "].args", hasSize(2)))
                .andExpect(jsonPath("$[" + SPLIT_STRING + "].args[1].kind").value("string_literal"));
    }

    @Test
    void refusedDistinctQuantifierCarriesItsReason() throws Exception {
        // distinct_supported: false used to be a flag with no stated rationale, so it read as arbitrary.
        // Each refusal now says why, and they are NOT the same reason — a no-op for the extremes, a
        // different statistic for the percentiles.
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + MIN + "].distinct_supported").value(false))
                .andExpect(jsonPath("$[" + MIN + "].description").value(containsString("silent no-op")))
                .andExpect(jsonPath("$[" + MAX + "].description").value(containsString("silent no-op")))
                .andExpect(jsonPath("$[" + PERCENTILE_CONT + "].description")
                        .value(containsString("different statistic")))
                .andExpect(jsonPath("$[" + PERCENTILE_DISC + "].description")
                        .value(containsString("different distribution")))
                .andExpect(jsonPath("$[" + ARG_MAX + "].description")
                        .value(containsString("detach a value")));
    }

    @Test
    void argMaxEntryAdvertisesTwoRequiredExpressionArguments() throws Exception {
        // Two REQUIRED args is the fact with consequences beyond discovery: AggregateSpecValidator reads
        // this descriptor's required arity to refuse arg_max as a pipeline measure, so an `optional` flag
        // slipping onto either argument would silently widen the measure vocabulary.
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + ARG_MAX + "].name").value("arg_max"))
                .andExpect(jsonPath("$[" + ARG_MAX + "].group").value("aggregate"))
                .andExpect(jsonPath("$[" + ARG_MAX + "].signature").value("arg_max(value, order)"))
                .andExpect(jsonPath("$[" + ARG_MAX + "].returns").value("same_as_argument"))
                .andExpect(jsonPath("$[" + ARG_MAX + "].distinct_supported").value(false))
                .andExpect(jsonPath("$[" + ARG_MAX + "].args", hasSize(2)))
                .andExpect(jsonPath("$[" + ARG_MAX + "].args[0].name").value("value"))
                .andExpect(jsonPath("$[" + ARG_MAX + "].args[0].kind").value("expression"))
                .andExpect(jsonPath("$[" + ARG_MAX + "].args[0].optional").doesNotExist())
                .andExpect(jsonPath("$[" + ARG_MAX + "].args[1].name").value("order"))
                .andExpect(jsonPath("$[" + ARG_MAX + "].args[1].kind").value("expression"))
                .andExpect(jsonPath("$[" + ARG_MAX + "].args[1].optional").doesNotExist())
                // The description is where the tie trap reaches a client, so it must actually say it.
                .andExpect(jsonPath("$[" + ARG_MAX + "].description").value(containsString("Ties on the greatest order")));
    }

    @Test
    void jsonExtractEntriesAdvertiseVariadicLiteralPath() throws Exception {
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + JSON_EXTRACT_BOOL + "].name").value("json_extract_bool"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_BOOL + "].returns").value("boolean"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_DOUBLE + "].returns").value("numeric"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_LONG + "].returns").value("long"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_STRING + "].name").value("json_extract_string"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_STRING + "].group").value("scalar"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_STRING + "].returns").value("string"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_STRING + "].distinct_supported").value(false))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_STRING + "].args", hasSize(2)))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_STRING + "].args[0].name").value("json"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_STRING + "].args[0].kind").value("expression"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_STRING + "].args[0].variadic").doesNotExist())
                .andExpect(jsonPath("$[" + JSON_EXTRACT_STRING + "].args[1].name").value("key"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_STRING + "].args[1].kind").value("string_literal"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_STRING + "].args[1].variadic").value(true));
    }

    @Test
    void dateBinEntryAdvertisesLiteralConstraints() throws Exception {
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + DATE_BIN + "].name").value("date_bin"))
                .andExpect(jsonPath("$[" + DATE_BIN + "].group").value("scalar"))
                .andExpect(jsonPath("$[" + DATE_BIN + "].signature").value("date_bin(amount, unit, timestamp)"))
                .andExpect(jsonPath("$[" + DATE_BIN + "].returns").value("timestamp"))
                .andExpect(jsonPath("$[" + DATE_BIN + "].distinct_supported").value(false))
                .andExpect(jsonPath("$[" + DATE_BIN + "].args", hasSize(3)))
                .andExpect(jsonPath("$[" + DATE_BIN + "].args[0].name").value("amount"))
                .andExpect(jsonPath("$[" + DATE_BIN + "].args[0].kind").value("integer_literal"))
                .andExpect(jsonPath("$[" + DATE_BIN + "].args[0].constraints.min").value(1))
                .andExpect(jsonPath("$[" + DATE_BIN + "].args[1].name").value("unit"))
                .andExpect(jsonPath("$[" + DATE_BIN + "].args[1].kind").value("string_literal"))
                .andExpect(jsonPath("$[" + DATE_BIN + "].args[1].constraints.allowed_values")
                        .value(contains("day", "hour", "minute", "second", "week")))
                .andExpect(jsonPath("$[" + DATE_BIN + "].args[2].name").value("timestamp"))
                .andExpect(jsonPath("$[" + DATE_BIN + "].args[2].kind").value("expression"))
                .andExpect(jsonPath("$[" + DATE_BIN + "].args[2].constraints").doesNotExist());
    }

    @Test
    void dateTruncEntryAdvertisesTheCalendarUnitsAndNoAmount() throws Exception {
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + DATE_TRUNC + "].name").value("date_trunc"))
                .andExpect(jsonPath("$[" + DATE_TRUNC + "].group").value("scalar"))
                .andExpect(jsonPath("$[" + DATE_TRUNC + "].signature").value("date_trunc(unit, timestamp)"))
                .andExpect(jsonPath("$[" + DATE_TRUNC + "].returns").value("timestamp"))
                // Two args, not three: a period of n calendar months has no fixed width, so there is no
                // grid for an amount multiplier to step along. That stays date_bin's job.
                .andExpect(jsonPath("$[" + DATE_TRUNC + "].args", hasSize(2)))
                .andExpect(jsonPath("$[" + DATE_TRUNC + "].args[0].name").value("unit"))
                .andExpect(jsonPath("$[" + DATE_TRUNC + "].args[0].kind").value("string_literal"))
                .andExpect(jsonPath("$[" + DATE_TRUNC + "].args[0].constraints.allowed_values")
                        .value(contains("day", "hour", "minute", "month", "quarter", "second", "week", "year")))
                .andExpect(jsonPath("$[" + DATE_TRUNC + "].args[1].name").value("timestamp"))
                .andExpect(jsonPath("$[" + DATE_TRUNC + "].args[1].kind").value("expression"));
    }

    @Test
    void aggregatesAdvertiseDistinctAndOptionalCountArgument() throws Exception {
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + COUNT + "].name").value("count"))
                .andExpect(jsonPath("$[" + COUNT + "].group").value("aggregate"))
                .andExpect(jsonPath("$[" + COUNT + "].distinct_supported").value(true))
                .andExpect(jsonPath("$[" + COUNT + "].args[0].optional").value(true))
                .andExpect(jsonPath("$[" + SUM + "].name").value("sum"))
                .andExpect(jsonPath("$[" + SUM + "].distinct_supported").value(true))
                .andExpect(jsonPath("$[" + SUM + "].args[0].optional").doesNotExist())
                .andExpect(jsonPath("$[" + PERCENTILE_CONT + "].name").value("percentile_cont"))
                .andExpect(jsonPath("$[" + PERCENTILE_CONT + "].group").value("ordered_set_aggregate"))
                .andExpect(jsonPath("$[" + PERCENTILE_CONT + "].args[0].constraints.min").value(0))
                .andExpect(jsonPath("$[" + PERCENTILE_CONT + "].args[0].constraints.max").value(1))
                .andExpect(jsonPath("$[" + MAX + "].name").value("max"))
                .andExpect(jsonPath("$[" + MAX + "].returns").value("same_as_argument"))
                .andExpect(jsonPath("$[" + MAX + "].distinct_supported").value(false));
    }

    @Test
    void arrayValuedAggregateAdvertisesItsShapeAndRefusesDistinct() throws Exception {
        // `array` names the shape only: the element type follows the operand, which discovery does not
        // type-infer — the same reason min/max advertise same_as_argument.
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + GROUP_UNIQ_ARRAY + "].name").value("group_uniq_array"))
                .andExpect(jsonPath("$[" + GROUP_UNIQ_ARRAY + "].group").value("aggregate"))
                .andExpect(jsonPath("$[" + GROUP_UNIQ_ARRAY + "].signature").value("group_uniq_array(value)"))
                .andExpect(jsonPath("$[" + GROUP_UNIQ_ARRAY + "].returns").value("array"))
                .andExpect(jsonPath("$[" + GROUP_UNIQ_ARRAY + "].distinct_supported").value(false))
                .andExpect(jsonPath("$[" + GROUP_UNIQ_ARRAY + "].args", hasSize(1)))
                .andExpect(jsonPath("$[" + GROUP_UNIQ_ARRAY + "].args[0].name").value("value"))
                .andExpect(jsonPath("$[" + GROUP_UNIQ_ARRAY + "].args[0].kind").value("expression"));
    }

    @Test
    void conditionalAndItsPredicateProducersAreDiscoverable() throws Exception {
        // A client has to be able to tell from the catalog alone which functions may supply if's condition,
        // since the DSL has no comparison in expression position to build one with.
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + IF + "].name").value("if"))
                .andExpect(jsonPath("$[" + IF + "].group").value("scalar"))
                .andExpect(jsonPath("$[" + IF + "].signature").value("if(condition, then, else)"))
                .andExpect(jsonPath("$[" + IF + "].returns").value("same_as_argument"))
                .andExpect(jsonPath("$[" + IF + "].args", hasSize(3)))
                .andExpect(jsonPath("$[" + IF + "].args[0].name").value("condition"))
                .andExpect(jsonPath("$[" + NOT_EMPTY + "].name").value("not_empty"))
                .andExpect(jsonPath("$[" + NOT_EMPTY + "].group").value("scalar"))
                .andExpect(jsonPath("$[" + NOT_EMPTY + "].returns").value("boolean"))
                .andExpect(jsonPath("$[" + STARTS_WITH + "].name").value("starts_with"))
                .andExpect(jsonPath("$[" + STARTS_WITH + "].returns").value("boolean"))
                .andExpect(jsonPath("$[" + CONTAINS + "].name").value("contains"))
                .andExpect(jsonPath("$[" + CONTAINS + "].returns").value("boolean"))
                .andExpect(jsonPath("$[" + EQUALS + "].name").value("equals"))
                .andExpect(jsonPath("$[" + EQUALS + "].returns").value("boolean"));
    }

    @Test
    void caseInsensitiveExtractorAdvertisesItsFixedArity() throws Exception {
        // The one json_extract_* entry whose key is NOT variadic: a case-insensitive match is defined for a
        // single top-level key, and the descriptor is where a client learns that before being rejected.
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + JSON_EXTRACT_STRING_CI + "].name").value("json_extract_string_ci"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_STRING_CI + "].group").value("scalar"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_STRING_CI + "].returns").value("string"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_STRING_CI + "].args", hasSize(2)))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_STRING_CI + "].args[0].name").value("json"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_STRING_CI + "].args[0].kind").value("expression"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_STRING_CI + "].args[1].name").value("key"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_STRING_CI + "].args[1].kind").value("string_literal"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_STRING_CI + "].args[1].variadic").doesNotExist());
    }

    @Test
    void arrayExtractorAdvertisesAnArrayReturn() throws Exception {
        mockMvc.perform(get("/v1/queries/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[" + JSON_EXTRACT_ARRAY + "].name").value("json_extract_array"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_ARRAY + "].group").value("scalar"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_ARRAY + "].returns").value("array"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_ARRAY + "].args", hasSize(2)))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_ARRAY + "].args[0].name").value("json"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_ARRAY + "].args[0].kind").value("expression"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_ARRAY + "].args[1].name").value("key"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_ARRAY + "].args[1].kind").value("string_literal"))
                .andExpect(jsonPath("$[" + JSON_EXTRACT_ARRAY + "].args[1].variadic").value(true));
    }

    @Test
    void mapsUnknownEntityToNotFound() throws Exception {
        BDDMockito.given(catalog.getBaseSchema("nope"))
                .willThrow(new QueryEntityNotFoundException("Unknown queryable entity: nope"));

        mockMvc.perform(get("/v1/queries/entities/schema/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("not_found"));
    }
}
