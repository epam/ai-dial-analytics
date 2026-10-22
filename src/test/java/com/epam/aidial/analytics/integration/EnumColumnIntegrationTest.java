package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the {@code enum} column type against real PostgreSQL + ClickHouse: declaring a
 * domain at define-and-materialize time ({@code Enum8}/{@code Enum16} in the physical DDL, asserted via
 * {@code SHOW CREATE TABLE}), reading it back on the table and discovery surfaces, adding and renaming
 * an enum column through {@code PATCH}, the immutability rule, and the round trip of values through
 * writes and both query surfaces.
 *
 * <p>The physical assertions are the point of doing this against a real server: the DDL text is caller
 * data embedded as SQL literals, and "ClickHouse accepted the statement we built" is not something a
 * mocked type mapper can tell us.
 *
 * <p>Requires a running Docker daemon.
 */
class EnumColumnIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String TABLE = "enum_probe";
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private static final String COLUMNS =
            "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                    + "{\"source_name\":\"status\",\"name\":\"status\",\"type\":\"enum\","
                    + "\"enum_values\":[\"pending\",\"running\",\"failed\"]}]";
    private static final String ORDERING = "[\"widget_id\"]";

    @AfterEach
    void cleanUp() throws Exception {
        mockMvc.perform(delete("/v1/tables/" + TABLE));
    }

    private void createIdentity() throws Exception {
        mockMvc.perform(post("/v1/tables").contentType(JSON)
                        .content("{\"name\":\"" + TABLE + "\",\"type\":\"source\"}"))
                .andExpect(status().isCreated());
    }

    private void materialize() throws Exception {
        materialize(COLUMNS);
    }

    private void materialize(String columns) throws Exception {
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":" + columns + ",\"ordering_key\":" + ORDERING + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void definesEnumColumnAndMaterializesEnum8() throws Exception {
        createIdentity();
        materialize();

        // Ids follow declared position, which is also what makes the column sort in declared order.
        assertThat(showCreateTable(TABLE))
                .contains("Enum8('pending' = 1, 'running' = 2, 'failed' = 3)");

        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[1].name").value("status"))
                .andExpect(jsonPath("$.columns[1].type").value("enum"))
                .andExpect(jsonPath("$.columns[1].enum_values[0]").value("pending"))
                .andExpect(jsonPath("$.columns[1].enum_values[1]").value("running"))
                .andExpect(jsonPath("$.columns[1].enum_values[2]").value("failed"))
                .andExpect(jsonPath("$.columns[0].enum_values").doesNotExist());
    }

    /** ClickHouse allows Nullable over an Enum, unlike Array and Map. */
    @Test
    void nullableEnumColumnMaterializesWrapped() throws Exception {
        createIdentity();
        materialize("[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                + "{\"source_name\":\"status\",\"name\":\"status\",\"type\":\"enum\","
                + "\"enum_values\":[\"a\",\"b\"],\"nullable\":true}]");

        assertThat(showCreateTable(TABLE)).contains("Nullable(Enum8('a' = 1, 'b' = 2))");
    }

    /**
     * A value carrying a quote is the escaping case: it has to survive into the physical type as data
     * rather than closing the literal and changing the statement.
     */
    @Test
    void enumValueWithQuoteIsEscapedIntoDdl() throws Exception {
        createIdentity();
        materialize("[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                + "{\"source_name\":\"status\",\"name\":\"status\",\"type\":\"enum\","
                + "\"enum_values\":[\"it's\",\"plain\"]}]");

        assertThat(showCreateTable(TABLE)).contains("Enum8('it\\'s' = 1, 'plain' = 2)");
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[1].enum_values[0]").value("it's"));
    }

    @Test
    void discoverySchemaCarriesTheDomain() throws Exception {
        createIdentity();
        materialize();

        mockMvc.perform(get("/v1/queries/entities/schema/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[?(@.name=='status')].type").value("enum"))
                .andExpect(jsonPath("$.fields[?(@.name=='status')].enum_values[0]").value("pending"))
                .andExpect(jsonPath("$.fields[?(@.name=='widget_id')].enum_values[0]").doesNotExist());
    }

    @Test
    void enumColumnWithoutValuesIsRejected() throws Exception {
        createIdentity();
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":[{\"source_name\":\"widget_id\",\"name\":\"widget_id\","
                                + "\"type\":\"string\"},{\"source_name\":\"status\",\"name\":\"status\","
                                + "\"type\":\"enum\"}],\"ordering_key\":" + ORDERING + "}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));

        // Nothing was persisted: the definition is still the identity-only PENDING row.
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void enumValuesOnNonEnumColumnAreRejected() throws Exception {
        createIdentity();
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":[{\"source_name\":\"widget_id\",\"name\":\"widget_id\","
                                + "\"type\":\"string\",\"enum_values\":[\"a\"]}],\"ordering_key\":"
                                + ORDERING + "}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void duplicateEnumValuesAreRejected() throws Exception {
        createIdentity();
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":[{\"source_name\":\"widget_id\",\"name\":\"widget_id\","
                                + "\"type\":\"string\"},{\"source_name\":\"status\",\"name\":\"status\","
                                + "\"type\":\"enum\",\"enum_values\":[\"a\",\"a\"]}],\"ordering_key\":"
                                + ORDERING + "}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void addsEnumColumnToLiveTable() throws Exception {
        createIdentity();
        materialize("[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"}]");

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"add\":[{\"source_name\":\"severity\",\"name\":\"severity\","
                                + "\"type\":\"enum\",\"enum_values\":[\"low\",\"medium\",\"high\"]}]}"))
                .andExpect(status().isOk());

        assertThat(showCreateTable(TABLE))
                .contains("Enum8('low' = 1, 'medium' = 2, 'high' = 3)");
    }

    @Test
    void changingTheDomainThroughUpdateIsRejected() throws Exception {
        createIdentity();
        materialize();

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"status\",\"enum_values\":[\"pending\",\"running\","
                                + "\"failed\",\"cancelled\"]}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));

        // The stored domain is untouched — the rejection is not a partial application.
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[1].enum_values.length()").value(3));
        assertThat(showCreateTable(TABLE)).contains("Enum8('pending' = 1, 'running' = 2, 'failed' = 3)");
    }

    /** Rename is a delete-then-insert that copies each field by hand, so the domain is easy to drop. */
    @Test
    void renamePreservesTheDomain() throws Exception {
        createIdentity();
        materialize();

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"rename\":[{\"from\":\"status\",\"to\":\"state\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[1].name").value("state"))
                .andExpect(jsonPath("$.columns[1].type").value("enum"))
                .andExpect(jsonPath("$.columns[1].enum_values[1]").value("running"));
    }

    @Test
    void rowsRoundTripEnumValuesAsStrings() throws Exception {
        createIdentity();
        materialize();

        mockMvc.perform(post("/v1/tables/" + TABLE + "/rows").contentType(JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"status\":\"running\"},"
                                + "{\"widget_id\":\"w2\",\"status\":\"failed\"}]}"))
                .andExpect(status().isOk());

        // The filter names the value with a plain string literal — the DSL has no enum literal, so this
        // is the only spelling there is, and it works only because enum unifies with string.
        mockMvc.perform(post("/v1/queries/execute").contentType(JSON).content("""
                {
                  "entity": "%s",
                  "mode": "row",
                  "select": [ { "expr": { "type": "field", "name": "status" } } ],
                  "filter": { "op": "eq", "args": [
                      { "type": "field", "name": "status" },
                      { "type": "value", "value_type": "string", "value": "running" }
                  ]}
                }
                """.formatted(TABLE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].status").value("running"));
    }

    @Test
    void sqlSurfaceTreatsAnEnumColumnAsText() throws Exception {
        createIdentity();
        materialize();
        mockMvc.perform(post("/v1/tables/" + TABLE + "/rows").contentType(JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"status\":\"running\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/queries/execute-sql").contentType(JSON)
                        .content("{\"sql\":\"SELECT status FROM " + TABLE
                                + " WHERE status = 'running'\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].status").value("running"));

        mockMvc.perform(post("/v1/queries/execute-sql").contentType(JSON)
                        .content("{\"sql\":\"SELECT DATA_TYPE FROM information_schema.columns "
                                + "WHERE TABLE_NAME = '" + TABLE + "' AND COLUMN_NAME = 'status'\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].DATA_TYPE").value("CHARACTER VARYING"));
    }

    /**
     * The driver hands an {@code Enum8} cell back as {@code BinaryStreamReader.EnumValue}, which extends
     * {@link Number} — so Jackson serializes it as a JSON <em>number</em> and writes the value's name
     * unquoted: {@code {"status":running}}, which is malformed JSON no strict client can read.
     *
     * <p>This asserts the raw body parses, deliberately, because the ordinary {@code jsonPath} assertions
     * elsewhere in this class do <b>not</b> catch it: JsonPath's default provider is lenient and reads the
     * bare token as a string, so every other test here passed while the endpoint returned invalid JSON.
     */
    @Test
    void enumProjectionIsValidJson() throws Exception {
        createIdentity();
        materialize();
        mockMvc.perform(post("/v1/tables/" + TABLE + "/rows").contentType(JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"status\":\"running\"}]}"))
                .andExpect(status().isOk());

        for (final String body : List.of(
                mockMvc.perform(post("/v1/queries/execute").contentType(JSON).content("""
                        {
                          "entity": "%s",
                          "mode": "row",
                          "select": [ { "expr": { "type": "field", "name": "status" } } ]
                        }
                        """.formatted(TABLE)))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                mockMvc.perform(post("/v1/queries/execute-sql").contentType(JSON)
                                .content("{\"sql\":\"SELECT status FROM " + TABLE + "\"}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())) {
            assertThat(body).contains("\"status\":\"running\"");
            assertThatNoException()
                    .as("response must parse under a strict JSON reader: %s", body)
                    .isThrownBy(() -> JsonMapper.builder().build().readTree(body));
        }
    }

    /**
     * ClickHouse defines comparison over an enum but <em>not</em> the string functions:
     * {@code startsWith(status, 'run')} raises rather than evaluating. The DSL therefore refuses an enum
     * as a text operand up front, so the caller gets a message naming the type rather than the generic
     * "could not be executed against ClickHouse" a server-side failure produces. Both are 400 on this
     * endpoint; the difference is whether the message says what to fix. This test is the evidence for the
     * rule — it is a property of ClickHouse, not of ADAS, so only a real server can assert it.
     */
    @Test
    void stringFunctionsRejectAnEnumOperand() throws Exception {
        createIdentity();
        materialize();

        mockMvc.perform(post("/v1/queries/execute").contentType(JSON).content("""
                {
                  "entity": "%s",
                  "mode": "row",
                  "select": [ { "expr": { "type": "field", "name": "status" } } ],
                  "filter": { "op": "eq", "args": [
                      { "type": "fn", "name": "starts_with", "args": [
                          { "type": "field", "name": "status" },
                          { "type": "value", "value_type": "string", "value": "run" }
                      ]},
                      { "type": "value", "value_type": "boolean", "value": "true" }
                  ]}
                }
                """.formatted(TABLE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("requires a text")))
                .andExpect(jsonPath("$.message").value(containsString("enum")));
    }

    /**
     * {@code column_mapping} is audited, so the domain has to exist on the {@code column_mapping_aud}
     * mirror too — a column added to one table and not the other stores the value but drops it from every
     * point-in-time read. Reading it back through the snapshot is what proves the migration touched both.
     */
    @Test
    void revisionSnapshotCarriesTheDomain() throws Exception {
        createIdentity();
        materialize();

        final String feed = mockMvc.perform(post("/v1/activities").contentType(JSON)
                        .content("{\"filters\":[{\"column\":\"resourceId\",\"operator\":\"eq\","
                                + "\"value\":\"" + TABLE + ":status\"}],"
                                + "\"sorts\":[{\"column\":\"epochTimestampMs\",\"direction\":\"DESC\"}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        final int revision = Integer.parseInt(
                com.jayway.jsonpath.JsonPath.read(feed, "$.data[0].revision").toString());

        mockMvc.perform(get("/v1/tables/" + TABLE + "/revision/" + revision))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[1].type").value("enum"))
                .andExpect(jsonPath("$.columns[1].enum_values[0]").value("pending"))
                .andExpect(jsonPath("$.columns[1].enum_values[2]").value("failed"));
    }

    /** A value outside the domain is ClickHouse's to refuse; ADAS surfaces it, it does not pre-check. */
    @Test
    void valueOutsideTheDomainIsRejected() throws Exception {
        createIdentity();
        materialize();

        mockMvc.perform(post("/v1/tables/" + TABLE + "/rows").contentType(JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"status\":\"cancelled\"}]}"))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(post("/v1/queries/execute").contentType(JSON).content("""
                {
                  "entity": "%s",
                  "mode": "row",
                  "select": [ { "expr": { "type": "field", "name": "widget_id" } } ]
                }
                """.formatted(TABLE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(0)));
    }

    /**
     * The physical consequence of positional ids, and the reason declared order is stored rather than
     * normalized: an enum sorts by the order it was declared in, not alphabetically. Sorted
     * alphabetically these would come back failed, pending, running.
     */
    @Test
    void orderingFollowsDeclaredOrderNotAlphabeticalOrder() throws Exception {
        createIdentity();
        materialize();
        mockMvc.perform(post("/v1/tables/" + TABLE + "/rows").contentType(JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"status\":\"failed\"},"
                                + "{\"widget_id\":\"w2\",\"status\":\"pending\"},"
                                + "{\"widget_id\":\"w3\",\"status\":\"running\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/queries/execute").contentType(JSON).content("""
                {
                  "entity": "%s",
                  "mode": "row",
                  "select": [ { "expr": { "type": "field", "name": "status" } } ],
                  "sort": [ { "field": "status", "dir": "asc" } ]
                }
                """.formatted(TABLE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].status").value("pending"))
                .andExpect(jsonPath("$.rows[1].status").value("running"))
                .andExpect(jsonPath("$.rows[2].status").value("failed"));
    }
}
