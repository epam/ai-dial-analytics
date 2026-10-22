package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The {@code /v1/saved-queries} CRUD surface in {@code none} security mode, over the full application
 * against the real PostgreSQL catalog and the real translation front ends. ClickHouse is never
 * contacted: a saved query is validated by translation, which renders SQL and discards it.
 *
 * <p>{@code none} mode is the fail-open case — no authenticated caller, so no owner is recorded, no
 * owner filter is applied, and every write is permitted including at {@code common} scope. The
 * scope-based authorization rules are covered separately in {@link SavedQueryAuthIntegrationTest}.
 */
class SavedQueryIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String BASE = "/v1/saved-queries";

    private static final String STRUCTURED_BODY = """
            {
              "name": "Chats by project",
              "description": "Used in the Monday review.",
              "tag": "Adoption",
              "scope": "personal",
              "query": {
                "entity": "dial_usage_log",
                "mode": "row",
                "select": [ { "expr": { "type": "field", "name": "project_id" } } ],
                "filter": { "op": "eq", "args": [
                    { "type": "field", "name": "event_kind" },
                    { "type": "value", "value_type": "string", "value": "chat" } ] }
              },
              "time": { "mode": "relative", "period": "last_2d" },
              "result_view": "chart",
              "chart": { "type": "bar", "x_field": "project_id", "y_field": null }
            }
            """;

    @Test
    void createsReadsAndDeletesSavedQuery() throws Exception {
        final String id = create(STRUCTURED_BODY);

        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Chats by project"))
                .andExpect(jsonPath("$.description").value("Used in the Monday review."))
                .andExpect(jsonPath("$.tag").value("Adoption"))
                .andExpect(jsonPath("$.scope").value("personal"))
                .andExpect(jsonPath("$.result_view").value("chart"))
                .andExpect(jsonPath("$.chart.type").value("bar"))
                .andExpect(jsonPath("$.chart.x_field").value("project_id"))
                // The stored body comes back exactly as persisted: the read path applies no redaction,
                // including to a literal bound to a sensitive column (see the saved-queries spec).
                .andExpect(jsonPath("$.query.entity").value("dial_usage_log"))
                .andExpect(jsonPath("$.query.mode").value("row"))
                .andExpect(jsonPath("$.sql").doesNotExist())
                .andExpect(jsonPath("$.generation").value(1));

        mockMvc.perform(delete(BASE + "/" + id)).andExpect(status().isNoContent());
        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void assignsOpaquePrefixedIdAndServerTimestamps() throws Exception {
        mockMvc.perform(post(BASE).contentType("application/json").content(STRUCTURED_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", startsWith("sq_")))
                .andExpect(jsonPath("$.created_at", notNullValue()))
                .andExpect(jsonPath("$.updated_at", notNullValue()))
                // Derived from the body, never accepted from the request.
                .andExpect(jsonPath("$.source", contains("dial_usage_log")));
    }

    @Test
    void recordsNoOwnerWhenSecurityIsDisabled() throws Exception {
        // Fail open, mirroring the sensitive-column filter: no authenticated caller means no owner, and
        // deliberately no synthetic placeholder that would collide with a real identity under oidc.
        // A null owner is absent from the response body, not present-and-null (NON_NULL inclusion).
        mockMvc.perform(post(BASE).contentType("application/json").content(STRUCTURED_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.owner_id").doesNotExist());
    }

    @Test
    void allowsCommonScopeWritesWhenSecurityIsDisabled() throws Exception {
        final String id = create(STRUCTURED_BODY.replace("\"scope\": \"personal\"", "\"scope\": \"common\""));
        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("common"));
    }

    @Test
    void storesTheRelativePeriodAsIntentRatherThanInstants() throws Exception {
        final String id = create(STRUCTURED_BODY);
        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.time.mode").value("relative"))
                .andExpect(jsonPath("$.time.period").value("last_2d"))
                .andExpect(jsonPath("$.time.from").doesNotExist())
                .andExpect(jsonPath("$.time.to").doesNotExist());
    }

    @Test
    void listDefaultsToPersonalScope() throws Exception {
        // Names unique to this test: the suite shares one database, so asserting on a name any other
        // test also creates would couple this assertion to execution order.
        create(STRUCTURED_BODY.replace("Chats by project", "Scope default personal"));
        create(STRUCTURED_BODY.replace("\"scope\": \"personal\"", "\"scope\": \"common\"")
                .replace("Chats by project", "Scope default common"));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved_queries[?(@.name=='Scope default personal')]", hasSize(1)))
                .andExpect(jsonPath("$.saved_queries[?(@.name=='Scope default common')]", hasSize(0)));

        mockMvc.perform(get(BASE).param("scope", "common"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved_queries[?(@.name=='Scope default common')]", hasSize(1)))
                .andExpect(jsonPath("$.saved_queries[?(@.name=='Scope default personal')]", hasSize(0)));
    }

    @Test
    void listReturnsTheFullObject() throws Exception {
        final String id = create(STRUCTURED_BODY.replace("Chats by project", "List body probe"));
        final Map<String, Object> entry = listEntry("List body probe");

        assertThat(entry).containsEntry("id", id)
                .containsEntry("source", List.of("dial_usage_log"))
                .containsEntry("result_view", "chart");
        // The body is present and complete, not a projection: a client derives which editor a saved
        // query opens in from it, so a list entry it cannot act on costs a round trip per selection.
        assertThat(read(entry, "query.entity")).isEqualTo("dial_usage_log");
        assertThat(read(entry, "query.mode")).isEqualTo("row");
        assertThat(read(entry, "query.filter.op")).isEqualTo("eq");
        assertThat(read(entry, "query.select[0].expr.name")).isEqualTo("project_id");
        assertThat(read(entry, "time.mode")).isEqualTo("relative");
        assertThat(read(entry, "time.period")).isEqualTo("last_2d");
        assertThat(read(entry, "chart.type")).isEqualTo("bar");
        assertThat(read(entry, "chart.x_field")).isEqualTo("project_id");
    }

    @Test
    void listEntryIsIdenticalToTheSingleRead() throws Exception {
        // One read shape, asserted as such: the list entry and GET /{id} must not drift apart.
        final String id = create(STRUCTURED_BODY.replace("Chats by project", "List parity probe"));
        final String single = mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(listEntry("List parity probe"))
                .isEqualTo(com.jayway.jsonpath.JsonPath.parse(single).read("$", Map.class));
    }

    @Test
    void listOmitsNullsRatherThanEmittingThem() throws Exception {
        // The structured probe has no sql, and its chart has no y_field; security is off so there is no
        // owner. All three must be absent from the entry, not present as null.
        create(STRUCTURED_BODY.replace("Chats by project", "List null probe"));
        final Map<String, Object> entry = listEntry("List null probe");

        assertThat(entry).doesNotContainKey("sql").doesNotContainKey("owner_id");
        @SuppressWarnings("unchecked")
        final Map<String, Object> chart = (Map<String, Object>) entry.get("chart");
        assertThat(chart).doesNotContainKey("y_field");
    }

    @Test
    void listCarriesTheSqlBodyForSqlKindEntries() throws Exception {
        create("""
                {"name":"List sql probe","sql":"SELECT project_id FROM dial_usage_log"}
                """);
        final Map<String, Object> entry = listEntry("List sql probe");

        assertThat(entry).containsEntry("sql", "SELECT project_id FROM dial_usage_log")
                // `sql` set is what tells the client this opens in the SQL editor; `query` stays absent.
                .doesNotContainKey("query");
    }

    @Test
    void listAndReadAgreeOnMultiEntitySource() throws Exception {
        final String id = create("""
                {"name":"List join probe","sql":"SELECT a.project_id FROM dial_usage_log a \
                JOIN rate_analytics b ON a.project_id = b.project_id"}
                """);
        final Map<String, Object> entry = listEntry("List join probe");

        assertThat(entry).containsEntry("source", List.of("dial_usage_log", "rate_analytics"));
        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source", contains("dial_usage_log", "rate_analytics")));
    }

    @Test
    void listIsOrderedByMostRecentlyUpdated() throws Exception {
        final String older = STRUCTURED_BODY.replace("Chats by project", "Order probe older");
        final String newer = STRUCTURED_BODY.replace("Chats by project", "Order probe newer");
        final String olderId = create(older);
        create(newer);
        // Touching the older row must move it ahead of the newer one: the ordering is by update, not
        // creation.
        mockMvc.perform(put(BASE + "/" + olderId).contentType("application/json").content(older))
                .andExpect(status().isOk());

        final String body = mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        final List<String> names = com.jayway.jsonpath.JsonPath.read(body, "$.saved_queries[*].name");
        assertThat(names.indexOf("Order probe older")).isLessThan(names.indexOf("Order probe newer"));
    }

    @Test
    void rejectsUnrecognisedScopeParameter() throws Exception {
        mockMvc.perform(get(BASE).param("scope", "everything"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void replacePreservesIdentityAndBumpsGeneration() throws Exception {
        final String id = create(STRUCTURED_BODY);
        final String replacement = STRUCTURED_BODY.replace("Chats by project", "Renamed");

        mockMvc.perform(put(BASE + "/" + id).contentType("application/json").content(replacement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.generation").value(2));

        mockMvc.perform(put(BASE + "/" + id).contentType("application/json").content(replacement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(3));
    }

    @Test
    void reportsUnknownIdAsNotFound() throws Exception {
        mockMvc.perform(get(BASE + "/sq_does_not_exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mockMvc.perform(delete(BASE + "/sq_does_not_exist"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put(BASE + "/sq_does_not_exist")
                        .contentType("application/json").content(STRUCTURED_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void storedBodyIsAcceptedByTheQuerySurfaceUnchanged() throws Exception {
        // The executable-as-stored guarantee, end to end: save, read back, and hand the stored body
        // straight to the query layer with no substitution step. Asserted against /v1/queries/translate
        // rather than /execute so it holds without ClickHouse — translation is the whole validation the
        // execute path applies before it touches the data store.
        final String id = create(STRUCTURED_BODY);
        final String stored = mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        final Object query = com.jayway.jsonpath.JsonPath.read(stored, "$.query");
        final String body = com.jayway.jsonpath.JsonPath.parse(query).jsonString();

        mockMvc.perform(post("/v1/queries/translate").contentType("application/json").content(body))
                .andExpect(status().isOk());
    }

    @Test
    void exposesNoExecuteEndpoint() throws Exception {
        // A saved query does not execute itself: the client posts the stored body to /v1/queries/execute.
        final String id = create(STRUCTURED_BODY);
        mockMvc.perform(post(BASE + "/" + id + "/execute").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsBothBodiesAndNeither() throws Exception {
        final String both = """
                {"name":"Both","query":{"entity":"dial_usage_log","mode":"row"},"sql":"SELECT 1"}
                """;
        mockMvc.perform(post(BASE).contentType("application/json").content(both))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));

        mockMvc.perform(post(BASE).contentType("application/json").content("{\"name\":\"Neither\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void treatsBlankSqlAsAbsentAllTheWayToTheRow() throws Exception {
        // Validation reads a blank `sql` as absent, so this is one body — and it must be *stored* as one.
        // Writing "" alongside the structured body would trip the table's exactly-one-body CHECK and turn
        // what is at worst a 422 into a 500.
        final String blankSql = """
                {"name":"Blank sql beside a query",
                 "query":{"entity":"dial_usage_log","mode":"row",
                   "select":[{"expr":{"type":"field","name":"project_id"}}]},
                 "sql":""}
                """;
        final String id = create(blankSql);
        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sql").doesNotExist())
                .andExpect(jsonPath("$.query.entity").value("dial_usage_log"));
    }

    @Test
    void rejectsBlankName() throws Exception {
        final String blank = """
                {"name":"  ","query":{"entity":"dial_usage_log","mode":"row"}}
                """;
        mockMvc.perform(post(BASE).contentType("application/json").content(blank))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void rejectsSuppliedServerAssignedField() throws Exception {
        final String withSource = """
                {"name":"Derived","source":"rate_analytics",
                 "query":{"entity":"dial_usage_log","mode":"row"}}
                """;
        mockMvc.perform(post(BASE).contentType("application/json").content(withSource))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void rejectsParameterizedBody() throws Exception {
        // A parameterized body cannot be executed by the translator, so storing one would persist a query
        // that could never run. Refused with this capability's own message, not the translator's.
        final String templated = """
                {"name":"Templated","query":{"entity":"dial_usage_log","mode":"row",
                 "filter":{"op":"eq","args":[
                   {"type":"field","name":"project_id"},
                   {"type":"param","name":"which"}]}}}
                """;
        mockMvc.perform(post(BASE).contentType("application/json").content(templated))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void savesAndDerivesTheSourceOfSqlBody() throws Exception {
        final String sqlBody = """
                {"name":"Raw","sql":"SELECT project_id FROM dial_usage_log"}
                """;
        mockMvc.perform(post(BASE).contentType("application/json").content(sqlBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source", contains("dial_usage_log")))
                .andExpect(jsonPath("$.sql").value("SELECT project_id FROM dial_usage_log"))
                .andExpect(jsonPath("$.query").doesNotExist());
    }

    @Test
    void savesCompositeSqlWithDerivedTableAndWindowFunctions() throws Exception {
        // The motivating case: a derived table aggregates one entity, and window functions (a moving
        // average, a running total) run over that derived table. Executes via execute-sql already;
        // this asserts it can also be saved, with source naming the one entity it reads from.
        final String sqlBody = """
                {"name":"Composite single entity","sql":"SELECT day, cost, \
                round(avg(cost) OVER (ORDER BY day ROWS BETWEEN 6 PRECEDING AND CURRENT ROW), 2) AS avg_7d, \
                round(sum(cost) OVER (ORDER BY day ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), 2) AS cumulative \
                FROM (SELECT date_trunc('day', request_time) AS day, round(sum(deployment_price), 2) AS cost \
                FROM dial_usage_log GROUP BY day) d ORDER BY day"}
                """;
        mockMvc.perform(post(BASE).contentType("application/json").content(sqlBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source", contains("dial_usage_log")));
    }

    @Test
    void savesCompositeSqlJoiningTwoEntitiesWithBothInSource() throws Exception {
        final String sqlBody = """
                {"name":"Composite join","sql":"SELECT a.project_id FROM dial_usage_log a \
                JOIN rate_analytics b ON a.project_id = b.project_id"}
                """;
        mockMvc.perform(post(BASE).contentType("application/json").content(sqlBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source", contains("dial_usage_log", "rate_analytics")));
    }

    @Test
    void savesCompositeSqlWithAnInSubqueryAgainstAnotherEntityWithBothInSource() throws Exception {
        final String sqlBody = """
                {"name":"Composite subquery","sql":"SELECT project_id FROM dial_usage_log \
                WHERE project_id IN (SELECT project_id FROM rate_analytics)"}
                """;
        mockMvc.perform(post(BASE).contentType("application/json").content(sqlBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source", contains("dial_usage_log", "rate_analytics")));
    }

    @Test
    void translateSqlStillRejectsJoiningStatement() throws Exception {
        // POST /v1/queries/translate-sql produces a DSL document, which a join genuinely has none of;
        // this capability's widened save path leaves that endpoint's behavior unchanged.
        final String joiningSql = """
                {"sql":"SELECT a.project_id FROM dial_usage_log a JOIN rate_analytics b ON a.project_id = b.project_id"}
                """;
        mockMvc.perform(post("/v1/queries/translate-sql").contentType("application/json").content(joiningSql))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void rejectsSqlThatWillNotTranslate() throws Exception {
        // SQL the front end cannot translate is SQL no ADAS endpoint could execute; saving it would store
        // a query that can never run. The error is the same one /v1/queries/execute-sql gives.
        final String badSql = """
                {"name":"Broken","sql":"SELECT nope FROM not_a_table"}
                """;
        mockMvc.perform(post(BASE).contentType("application/json").content(badSql))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void rejectsCompositeSqlJoiningAnUnresolvableEntity() throws Exception {
        // A join naming an entity that does not exist fails exactly like the single-entity case:
        // 400 bad_request, regardless of how many entities the rest of the statement reads from.
        final String badJoin = """
                {"name":"Broken join","sql":"SELECT a.project_id FROM dial_usage_log a \
                JOIN not_a_table b ON a.project_id = b.project_id"}
                """;
        mockMvc.perform(post(BASE).contentType("application/json").content(badJoin))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void rejectsStructuredBodyNamingUnknownEntity() throws Exception {
        final String unknownEntity = """
                {"name":"Nowhere","query":{"entity":"no_such_entity","mode":"row"}}
                """;
        mockMvc.perform(post(BASE).contentType("application/json").content(unknownEntity))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void rejectsStructuredBodyNamingUnknownField() throws Exception {
        // Deliberately the same error the query endpoints give for the same mistake, so a restricted
        // column stays indistinguishable from a misspelled one here too.
        final String unknownField = """
                {"name":"Typo","query":{"entity":"dial_usage_log","mode":"row",
                 "select":[{"expr":{"type":"field","name":"no_such_column"}}]}}
                """;
        mockMvc.perform(post(BASE).contentType("application/json").content(unknownField))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void rejectsStructuredBodyOmittingMode() throws Exception {
        // `mode` is required by the structured query's own contract, which /v1/queries/execute enforces at
        // its boundary rather than during translation — translation reads a null mode as a row query and
        // renders SQL for it. Were the save to accept that, it would store a body the execute endpoint
        // refuses: a saved query that can never be run. The answer is the same 400 that endpoint gives.
        final String noMode = """
                {"name":"No mode","query":{"entity":"dial_usage_log"}}
                """;
        mockMvc.perform(post(BASE).contentType("application/json").content(noMode))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        final String id = create(STRUCTURED_BODY.replace("Chats by project", "Mode still required on replace"));
        mockMvc.perform(put(BASE + "/" + id).contentType("application/json").content(noMode))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        // The stored body is untouched by the refused replace.
        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query.mode").value("row"))
                .andExpect(jsonPath("$.generation").value(1));
    }

    @Test
    void rejectsMalformedTimeIntent() throws Exception {
        final String inverted = """
                {"name":"Inverted","query":{"entity":"dial_usage_log","mode":"row"},
                 "time":{"mode":"absolute","from":"2026-08-06T00:00:00Z","to":"2026-08-01T00:00:00Z"}}
                """;
        mockMvc.perform(post(BASE).contentType("application/json").content(inverted))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void rejectsUnbindableTimeIntentAsValidationError() throws Exception {
        // A shape the typed union cannot hold at all. It must still be this capability's 422: were `time`
        // bound as SavedQueryTime, Jackson would fail first and the answer would be a generic 400.
        final String unknownMode = """
                {"name":"Weekly","query":{"entity":"dial_usage_log","mode":"row"},
                 "time":{"mode":"weekly"}}
                """;
        mockMvc.perform(post(BASE).contentType("application/json").content(unknownMode))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));

        final String notAnObject = """
                {"name":"Yesterday","query":{"entity":"dial_usage_log","mode":"row"},
                 "time":"yesterday"}
                """;
        mockMvc.perform(post(BASE).contentType("application/json").content(notAnObject))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));

        final String badInstant = """
                {"name":"Fuzzy","query":{"entity":"dial_usage_log","mode":"row"},
                 "time":{"mode":"absolute","from":"the day before","to":"2026-08-06T00:00:00Z"}}
                """;
        mockMvc.perform(post(BASE).contentType("application/json").content(badInstant))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    /**
     * The motivating case for the whole relative-time change, end to end: a saved query whose SQL carries
     * its own moving window instead of the two literal instants it was authored with.
     *
     * <p>What is asserted is that the body is stored <b>exactly as authored</b> — the service does not fold
     * {@code now()} into an instant at save time or at read time, which would destroy the one property the
     * author was reaching for. Before this change the statement did not translate at all, so it could not
     * be saved.
     */
    @Test
    void relativeSqlBodyIsStoredAndReturnedUnchanged() throws Exception {
        final String sql = "SELECT deployment, sum(total_price) AS spend FROM dial_usage_log"
                + " WHERE request_time >= now() - INTERVAL 2 HOUR GROUP BY deployment";
        final String id = create("""
                {"name":"Spend, last 2h","scope":"personal","result_view":"chart",
                 "time":{"mode":"relative","period":"last_2h"},
                 "sql":"%s"}
                """.formatted(sql));

        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sql").value(sql))
                .andExpect(jsonPath("$.source").value(contains("dial_usage_log")))
                // The time-picker state is unchanged by the body and vice versa: the two are consistent
                // without the service mediating between them.
                .andExpect(jsonPath("$.time.mode").value("relative"))
                .andExpect(jsonPath("$.time.period").value("last_2h"))
                .andExpect(jsonPath("$.time.from").doesNotExist())
                .andExpect(jsonPath("$.time.to").doesNotExist());
    }

    /**
     * The same for a structured body — the question the change had to answer was whether the DSL needed
     * this too, and it does: a saved {@code query} body's time filter is baked-in literals for exactly the
     * reason a saved {@code sql} body's is, and `time` is interpreted by neither.
     */
    @Test
    void relativeStructuredBodyIsStoredAndReturnedUnchanged() throws Exception {
        final String id = create("""
                {"name":"Events, last 2h","scope":"personal","result_view":"table",
                 "query":{"entity":"dial_usage_log","mode":"row",
                   "select":[{"expr":{"type":"field","name":"event_id"}}],
                   "filter":{"op":"ge","args":[
                     {"type":"field","name":"request_time"},
                     {"type":"fn","name":"date_sub","args":[
                       {"type":"value","value_type":"string","value":"hour"},
                       {"type":"value","value_type":"integer","value":"2"},
                       {"type":"fn","name":"now","args":[]}]}]}}}
                """);

        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query.filter.args[1].name").value("date_sub"))
                .andExpect(jsonPath("$.query.filter.args[1].args[0].value").value("hour"))
                .andExpect(jsonPath("$.query.filter.args[1].args[1].value").value("2"))
                .andExpect(jsonPath("$.query.filter.args[1].args[2].name").value("now"))
                // No instant was recorded anywhere: the body is intent, not a resolved window.
                .andExpect(jsonPath("$.time").doesNotExist())
                .andExpect(jsonPath("$.source").value(contains("dial_usage_log")));
    }

    /**
     * A body the offset rules refuse is refused on save too, with the translation error the execute
     * endpoint gives for it — the "executable as stored" property, applied to the new functions.
     */
    @Test
    void bodyWhoseOffsetTheSubsetRefusesIsNotStorable() throws Exception {
        mockMvc.perform(post(BASE).contentType("application/json").content("""
                        {"name":"bad","scope":"personal","result_view":"table",
                         "sql":"SELECT event_id FROM dial_usage_log WHERE request_time >= now() - INTERVAL 2.5 HOUR"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    /** Creates a saved query and returns its server-assigned id. */
    private String create(String body) throws Exception {
        final String response = mockMvc.perform(post(BASE).contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(response, "$.id");
    }

    /**
     * The one entry named {@code name} from the default (personal) list, as a plain map. Parsed rather
     * than asserted with JSONPath expressions because a filter result is an array, so every nested
     * assertion would have to thread an index through and reads far worse than it checks.
     */
    private Map<String, Object> listEntry(String name) throws Exception {
        final String body = mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        final List<Map<String, Object>> matches =
                com.jayway.jsonpath.JsonPath.read(body, "$.saved_queries[?(@.name=='" + name + "')]");
        assertThat(matches).as("entries named '%s'", name).hasSize(1);
        return matches.getFirst();
    }

    /** Reads a nested path out of a parsed list entry. */
    private static Object read(Map<String, Object> entry, String path) {
        return com.jayway.jsonpath.JsonPath.read(entry, "$." + path);
    }
}
