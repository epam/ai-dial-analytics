package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the table-management lifecycle against real PostgreSQL + ClickHouse:
 * {@code POST /v1/tables} records identity only ({@code PENDING}, no ClickHouse DDL);
 * {@code POST /{name}/schema} defines the complete physical schema and materializes the table (issues
 * {@code CREATE TABLE}, flips to {@code ACTIVE}); {@code PATCH /{name}/schema} evolves a live table via
 * {@code ALTER}; {@code PUT /{name}} updates catalog metadata (description, tag ordering); and delete
 * drops the table. Cleans up created tables after each test so the shared catalog stays pristine.
 *
 * <p>Requires a running Docker daemon.
 */
class TableManagementIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String TABLE = "widget_metrics";
    private static final String ENRICHMENT = "widget_labels";
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private static final String COLUMNS =
            "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\",\"tag\":\"widget_key\"},"
                    + "{\"source_name\":\"score\",\"name\":\"score\",\"type\":\"long\"}]";
    private static final String ORDERING = "[\"widget_id\"]";

    @AfterEach
    void cleanUp() throws Exception {
        // Enrichment first (deleting a source with dependents is blocked); a table a test never created
        // is a harmless 404.
        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT));
        mockMvc.perform(delete("/v1/tables/" + TABLE));
    }

    /** Creates the {@code widget_metrics} identity as PENDING (no schema, no DDL). */
    private void createIdentity() throws Exception {
        mockMvc.perform(post("/v1/tables").contentType(JSON)
                        .content("{\"name\":\"" + TABLE + "\",\"description\":\"widget scores\",\"type\":\"source\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"));
    }

    /** Creates, drafts, and materializes {@code widget_metrics} with the given columns/ordering key. */
    private void activeSource(String columnsJson, String orderingJson) throws Exception {
        IntegrationTables.source(mockMvc, TABLE, columnsJson, orderingJson);
    }

    @Test
    void createDefineSchemaQueryPatchAndDeleteLifecycle() throws Exception {
        // 1. Create records identity only — PENDING, no ClickHouse table yet.
        mockMvc.perform(post("/v1/tables").contentType(JSON)
                        .content("{\"name\":\"" + TABLE + "\",\"description\":\"widget scores\",\"type\":\"source\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(TABLE))
                .andExpect(jsonPath("$.type").value("source"))
                .andExpect(jsonPath("$.status").value("pending"));

        // 2. Define the physical schema and materialize it in one call (CREATE TABLE, → ACTIVE).
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":" + COLUMNS + ",\"ordering_key\":" + ORDERING + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.ordering_key[0]").value("widget_id"))
                // No scan metadata declared, so neither member is reported (this table is not scannable).
                .andExpect(jsonPath("$.identity_column").doesNotExist())
                .andExpect(jsonPath("$.version_column").doesNotExist());

        // 3. GET returns the definition with its exposed columns, including the column tag.
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns", hasSize(2)))
                .andExpect(jsonPath("$.columns[0].name").value("widget_id"))
                .andExpect(jsonPath("$.columns[0].tag").value("widget_key"))
                .andExpect(jsonPath("$.columns[1].name").value("score"));

        // 4. It is a queryable entity with the flat schema; the tag surfaces in discovery too.
        mockMvc.perform(get("/v1/queries/entities/schema/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entity").value(TABLE))
                .andExpect(jsonPath("$.fields", hasSize(2)))
                .andExpect(jsonPath("$.fields[0].tag").value("widget_key"));

        // 5. The (empty) table executes a row query without error.
        mockMvc.perform(post("/v1/queries/execute").contentType(JSON)
                        .content("{\"entity\":\"" + TABLE + "\",\"mode\":\"row\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(0)));

        // 6. PATCH adds a column via ALTER (live); GET reflects it, and the list's column_count tracks it.
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"add\":[{\"source_name\":\"tier\",\"name\":\"tier\",\"type\":\"string\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns", hasSize(3)));
        mockMvc.perform(get("/v1/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name=='" + TABLE + "')].column_count").value(3));

        // 7. PATCH renames then drops a column; the list's column_count follows the drop.
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"rename\":[{\"from\":\"tier\",\"to\":\"segment\"}],\"drop\":[\"score\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns", hasSize(2)));
        mockMvc.perform(get("/v1/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name=='" + TABLE + "')].column_count").value(2));

        // 8. Delete removes the catalog row and drops the ClickHouse table.
        mockMvc.perform(delete("/v1/tables/" + TABLE)).andExpect(status().isNoContent());
        mockMvc.perform(get("/v1/tables/" + TABLE)).andExpect(status().isNotFound());
    }

    @Test
    void createWithColumnsOrPhysicalKeysIsRejected() throws Exception {
        // Create is identity-only: a columns member (or any physical key) is rejected with 422.
        mockMvc.perform(post("/v1/tables").contentType(JSON)
                        .content("{\"name\":\"" + TABLE + "\",\"type\":\"source\",\"columns\":" + COLUMNS + ","
                                + "\"ordering_key\":" + ORDERING + "}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    /**
     * A table a rule DECLARES as its read source is a dependency the enrichment check cannot see: the rule
     * reads {@code wm_rates} while its target hangs off {@code widget_metrics}, so {@code wm_rates} has no
     * enrichment of its own. Dropping it would leave the rule reading nothing and failing on its next scan,
     * so the delete is refused with the same 409 a pipeline input gets.
     */
    @Test
    void deleteOfTheDeclaredReadSourceIsBlocked() throws Exception {
        activeSource(COLUMNS, ORDERING);
        IntegrationTables.enrichment(mockMvc, ENRICHMENT, TABLE, "widget_id",
                "[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"}]");
        IntegrationTables.source(mockMvc, "wm_rates",
                "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\",\"nullable\":false},"
                        + "{\"source_name\":\"event_id\",\"name\":\"event_id\",\"type\":\"string\",\"nullable\":false},"
                        + "{\"source_name\":\"rated_at\",\"name\":\"rated_at\",\"type\":\"timestamp\",\"nullable\":false}]",
                "[\"event_id\"]", "event_id", "rated_at");
        try {
            mockMvc.perform(post("/v1/pipelines").contentType(JSON)
                            .content("{\"name\":\"wm-rule\","
                                    + Transforms.llm().proseOutputs("label").member() + ","
                                    + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"inputs\":[\"wm_rates\"],"
                                    + "\"trigger\":{\"kind\":\"schedule\"},"
                                    + "\"enabled\":true}"))
                    .andExpect(status().isCreated());

            mockMvc.perform(delete("/v1/tables/wm_rates"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("conflict"))
                    .andExpect(jsonPath("$.message").value(containsString("wm-rule")));
            // Refused, not partly applied: the table is still there to read.
            mockMvc.perform(get("/v1/tables/wm_rates")).andExpect(status().isOk());

            // Once the rule is gone with its target, the source deletes normally.
            mockMvc.perform(delete("/v1/tables/" + ENRICHMENT)).andExpect(status().isNoContent());
            mockMvc.perform(delete("/v1/tables/wm_rates")).andExpect(status().isNoContent());
        } finally {
            mockMvc.perform(delete("/v1/tables/" + ENRICHMENT));
            mockMvc.perform(delete("/v1/tables/wm_rates"));
        }
    }

    @Test
    void schemaPatchBeforeMaterializationIsRejected() throws Exception {
        createIdentity();
        // PATCH is the live surface — on a PENDING table it is a 409 pointing to POST /{name}/schema.
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"add\":[{\"source_name\":\"x\",\"name\":\"x\",\"type\":\"long\"}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"));
    }

    @Test
    void defineSchemaOnMaterializedTableIsRejected() throws Exception {
        activeSource(COLUMNS, ORDERING);
        // POST /{name}/schema defines + materializes — on an ACTIVE table it is a 409 (use PATCH to evolve it).
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":" + COLUMNS + ",\"ordering_key\":" + ORDERING + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"));
    }

    @Test
    void defineIncompleteSourceIsRejectedAndStaysPending() throws Exception {
        createIdentity();
        // Columns but no ordering_key is an incomplete schema (422); nothing is materialized, stays PENDING.
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":" + COLUMNS + "}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"));
    }

    /**
     * Safety net for the rule above against the tables ADAS ships: the seeded built-in sources are
     * materialized by migration rather than through {@code POST .../schema}, so they never pass the
     * validation. Asserting they satisfy it anyway is what catches a future key column (or a future key
     * field) that is nullable — the ClickHouse {@code CREATE} would refuse it, but only at deploy time.
     */
    @Test
    void seededBuiltInSourcesSatisfyTheNonNullableKeyRule() throws Exception {
        for (final String builtIn : List.of("dial_usage_log", "rate_analytics")) {
            final String body = mockMvc.perform(get("/v1/tables/" + builtIn))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            final JsonNode table = JsonMapper.builder().build().readTree(body);

            final Map<String, Boolean> nullableBySourceName = new LinkedHashMap<>();
            table.path("columns").forEach(col ->
                    nullableBySourceName.put(col.path("source_name").asString(), col.path("nullable").asBoolean()));
            assertThat(nullableBySourceName).as("%s exposes columns", builtIn).isNotEmpty();

            final List<String> keys = new ArrayList<>();
            table.path("ordering_key").forEach(key -> keys.add(key.asString()));
            if (table.hasNonNull("partition_by")) {
                keys.add(table.path("partition_by").path("column").asString());
            }
            assertThat(keys).as("%s declares physical keys", builtIn).isNotEmpty();

            // A key may name a physical column the catalog does not expose (rate_analytics keys on
            // trace_id/core_span_id, which are not exposed columns); only the exposed ones are checkable
            // here, and those are the ones the validation rule sees.
            keys.stream().filter(nullableBySourceName::containsKey).forEach(key ->
                    assertThat(nullableBySourceName.get(key))
                            .as("%s key column '%s' must be non-nullable", builtIn, key)
                            .isFalse());
        }
    }

    /**
     * A nullable column cannot key a table: ClickHouse refuses both a nullable sorting key and a nullable
     * partition key while {@code allow_nullable_key} is disabled (ADAS never enables it). Like the
     * {@code map} case below, the refusal has to be ours — the catalog columns and keys are committed
     * before the DDL is issued, so letting the {@code CREATE} fail would surface a raw ClickHouse error and
     * leave a {@code FAILED} definition carrying a schema that can never materialize.
     */
    @Test
    void nullableColumnCannotKeyTheTableAndNothingIsPersisted() throws Exception {
        final String nullableKey =
                "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\",\"nullable\":true},"
                        + "{\"source_name\":\"event_time\",\"name\":\"event_time\",\"type\":\"timestamp\","
                        + "\"nullable\":true}]";

        createIdentity();
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":" + nullableKey + ",\"ordering_key\":" + ORDERING + "}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").value(containsString("ordering_key column 'widget_id'")))
                .andExpect(jsonPath("$.message").value(containsString("nullable sorting key")));

        // Same table, non-nullable ordering key but a nullable partition column.
        final String nullablePartition =
                "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                        + "{\"source_name\":\"event_time\",\"name\":\"event_time\",\"type\":\"timestamp\","
                        + "\"nullable\":true}]";
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":" + nullablePartition + ",\"ordering_key\":" + ORDERING
                                + ",\"partition_by\":{\"column\":\"event_time\",\"granularity\":\"day\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").value(containsString("partition_by column 'event_time'")))
                .andExpect(jsonPath("$.message").value(containsString("nullable partition key")));

        // Nothing persisted by either attempt: no columns, still PENDING (never FAILED).
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.columns").isEmpty());

        // The rule is about nullability, not about these columns: declaring the partition column
        // non-nullable materializes the same schema.
        final String nonNullable =
                "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                        + "{\"source_name\":\"event_time\",\"name\":\"event_time\",\"type\":\"timestamp\","
                        + "\"nullable\":false}]";
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":" + nonNullable + ",\"ordering_key\":" + ORDERING
                                + ",\"partition_by\":{\"column\":\"event_time\",\"granularity\":\"day\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.partition_by.column").value("event_time"));
    }

    /**
     * A {@code map} column cannot key a table: ClickHouse has no comparison over {@code Map}, so a sorting
     * key or partition expression over one is refused by the {@code CREATE}. The rejection has to happen at
     * validation time — the catalog columns and keys are committed before the DDL is issued, so letting
     * ClickHouse be the one to refuse would surface its raw error and leave a {@code FAILED} definition
     * behind instead of persisting nothing.
     */
    @Test
    void mapColumnCannotKeyTheTableAndNothingIsPersisted() throws Exception {
        final String withMap = "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                + "{\"source_name\":\"attrs\",\"name\":\"attrs\",\"type\":\"map\"}]";

        createIdentity();
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":" + withMap + ",\"ordering_key\":[\"attrs\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").value(containsString("a map cannot key a table")));

        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":" + withMap + ",\"ordering_key\":" + ORDERING
                                + ",\"partition_by\":{\"column\":\"attrs\",\"granularity\":\"day\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").value(containsString("a map cannot key a table")));

        // Nothing persisted: no columns, still PENDING (never FAILED).
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.columns").isEmpty());
    }

    /**
     * The grain and the ordering key are columns of {@code table_definition} rather than rows of their own
     * tables, and {@code cardinality} is no longer stored at all — it is derived from the presence of a
     * grain key. None of that is visible on the wire: the response carries the same members, and
     * {@code cardinality} still reads {@code zero_or_one}.
     */
    @Test
    void grainAndOrderingKeyRoundTripUnchangedAfterDenormalization() throws Exception {
        // Deliberately a two-column key rather than the shared single-column ORDERING: order preservation
        // through the array column has to be observable at the API surface, not only in the migration test.
        activeSource(COLUMNS, "[\"widget_id\",\"score\"]");
        IntegrationTables.enrichment(mockMvc, ENRICHMENT, TABLE, "widget_id",
                "[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"}]");

        mockMvc.perform(get("/v1/tables/" + ENRICHMENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grain.grain_key").value("widget_id"))
                .andExpect(jsonPath("$.grain.cardinality").value("zero_or_one"))
                // An enrichment's ordering key is its grain key, and the grain column stays hidden from
                // the exposed columns.
                .andExpect(jsonPath("$.ordering_key", contains("widget_id")))
                // One declared column plus the five injected provenance columns.
                .andExpect(jsonPath("$.columns", hasSize(6)))
                .andExpect(jsonPath("$.columns[?(@.name=='label')].type", contains("string")))
                .andExpect(jsonPath("$.columns[?(@.name=='pipeline_generation')].type", contains("long")))
                .andExpect(jsonPath("$.columns[?(@.name=='enriched_at')].type", contains("timestamp")));

        // A source carries no grain at all, and its multi-column ordering key survives in declared order.
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grain").doesNotExist())
                .andExpect(jsonPath("$.ordering_key", contains("widget_id", "score")));
    }

    /**
     * {@code cardinality} is accepted on the way in exactly as before — {@code zero_or_one} is the one
     * legal value, and anything else is refused rather than quietly stored or ignored. The refusal is a
     * 400 from enum binding (the enum has a single constant), ahead of the service-layer guard that would
     * catch a second constant if one were ever added.
     */
    @Test
    void explicitCardinalityIsAcceptedOnlyAsZeroOrOne() throws Exception {
        activeSource(COLUMNS, ORDERING);
        mockMvc.perform(post("/v1/tables").contentType(JSON)
                        .content("{\"name\":\"" + ENRICHMENT + "\",\"type\":\"enrichment\","
                                + "\"source_table\":\"" + TABLE + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/v1/tables/" + ENRICHMENT + "/schema").contentType(JSON)
                        .content("{\"grain_key\":\"widget_id\",\"cardinality\":\"many\",\"columns\":[]}"))
                .andExpect(status().is4xxClientError());

        // Nothing was persisted by the rejected request; the explicit legal value then materializes.
        mockMvc.perform(get("/v1/tables/" + ENRICHMENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.grain").doesNotExist());

        mockMvc.perform(post("/v1/tables/" + ENRICHMENT + "/schema").contentType(JSON)
                        .content("{\"grain_key\":\"widget_id\",\"cardinality\":\"zero_or_one\",\"columns\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.grain.cardinality").value("zero_or_one"));
    }

    @Test
    void listCarriesColumnCountButNotColumns() throws Exception {
        activeSource(COLUMNS, ORDERING);
        // An enrichment declaring no user columns still carries the FIVE provenance columns, which
        // defining its schema injects. The grain key stays hidden plumbing.
        IntegrationTables.enrichment(mockMvc, ENRICHMENT, TABLE, "widget_id", null);

        mockMvc.perform(get("/v1/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name=='" + TABLE + "')].column_count").value(2))
                .andExpect(jsonPath("$.tables[?(@.name=='" + ENRICHMENT + "')].column_count").value(5))
                .andExpect(jsonPath("$.tables[?(@.name=='" + TABLE + "')].columns").doesNotExist());

        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns", hasSize(2)))
                .andExpect(jsonPath("$.column_count").doesNotExist());
    }

    @Test
    void permissionsAreOpenInNoneModeExceptSystemTables() throws Exception {
        mockMvc.perform(post("/v1/tables").contentType(JSON)
                        .content("{\"name\":\"" + TABLE + "\",\"description\":\"widget scores\",\"type\":\"source\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permissions.write").value(true))
                .andExpect(jsonPath("$.permissions.modify").value(true));

        // With security disabled nothing is enforced, so a user table reports full permissions...
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.write").value(true))
                .andExpect(jsonPath("$.permissions.modify").value(true));
        mockMvc.perform(get("/v1/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name=='" + TABLE + "')].permissions.write").value(true))
                .andExpect(jsonPath("$.tables[?(@.name=='" + TABLE + "')].permissions.modify").value(true));

        // ...while a system table still refuses every modifying operation, for every caller.
        mockMvc.perform(get("/v1/tables/dial_usage_log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.write").value(false))
                .andExpect(jsonPath("$.permissions.modify").value(false));
    }

    @Test
    void retagSetsClearsAndIsPreservedAcrossRename() throws Exception {
        activeSource(COLUMNS, ORDERING);

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"score\",\"tag\":\"quality_score\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[1].name").value("score"))
                .andExpect(jsonPath("$.columns[1].tag").value("quality_score"));

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"rename\":[{\"from\":\"score\",\"to\":\"points\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[1].name").value("points"))
                .andExpect(jsonPath("$.columns[1].tag").value("quality_score"));

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"points\",\"tag\":\"\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[1].tag").doesNotExist());
    }

    @Test
    void retagOfUnknownColumnIsRejected() throws Exception {
        activeSource(COLUMNS, ORDERING);
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"ghost\",\"tag\":\"x\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void physicalKeyMemberOnPatchIsRejected() throws Exception {
        activeSource(COLUMNS, ORDERING);
        // Physical keys are frozen after materialization — a key member on PATCH is a 422, not a no-op.
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"ordering_key\":[\"score\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"grain_key\":\"widget_id\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void tagOrderMemberOnPatchIsRejected() throws Exception {
        activeSource(COLUMNS, ORDERING);
        // tag_order moved to table metadata (PUT /v1/tables/{name}); sending it on PATCH is a 422.
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"tag_order\":[\"widget_key\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void updateChangesSeveralMetadataFieldsInOneEntry() throws Exception {
        activeSource(COLUMNS, ORDERING);
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"score\",\"tag\":\"quality_score\","
                                + "\"display_name\":\"Score\",\"description\":\"Quality score\",\"sensitive\":true}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[1].name").value("score"))
                .andExpect(jsonPath("$.columns[1].tag").value("quality_score"))
                .andExpect(jsonPath("$.columns[1].display_name").value("Score"))
                .andExpect(jsonPath("$.columns[1].description").value("Quality score"))
                .andExpect(jsonPath("$.columns[1].sensitive").value(true));
    }

    @Test
    void updateLeavesAbsentMetadataFieldsUnchanged() throws Exception {
        activeSource(COLUMNS, ORDERING);
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"score\",\"tag\":\"quality_score\",\"display_name\":\"Score\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"score\",\"sensitive\":true}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[1].tag").value("quality_score"))
                .andExpect(jsonPath("$.columns[1].display_name").value("Score"))
                .andExpect(jsonPath("$.columns[1].sensitive").value(true));
    }

    @Test
    void duplicateUpdateOfSameColumnIsRejected() throws Exception {
        activeSource(COLUMNS, ORDERING);
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"score\",\"tag\":\"a\"},{\"name\":\"score\",\"tag\":\"b\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void sensitiveFlagIsDeclaredReturnedTogglesAndSurvivesRename() throws Exception {
        // A column declared sensitive in the draft schema.
        activeSource("[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                + "{\"source_name\":\"secret\",\"name\":\"secret\",\"type\":\"string\",\"sensitive\":true}]", ORDERING);

        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[0].sensitive").value(false))
                .andExpect(jsonPath("$.columns[1].name").value("secret"))
                .andExpect(jsonPath("$.columns[1].sensitive").value(true));

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"secret\",\"sensitive\":false}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[1].sensitive").value(false));

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"rename\":[{\"from\":\"secret\",\"to\":\"hidden\"}],"
                                + "\"update\":[{\"name\":\"hidden\",\"sensitive\":true}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[1].name").value("hidden"))
                .andExpect(jsonPath("$.columns[1].sensitive").value(true));
    }

    @Test
    void heavyFlagIsDeclaredReturnedTogglesAndSurvivesRename() throws Exception {
        // Same lifecycle as `sensitive`, on the same rails: declared in the draft schema, returned
        // unfiltered by the read surface, toggled by the metadata merge-patch, preserved by a rename.
        activeSource("[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                + "{\"source_name\":\"payload\",\"name\":\"payload\",\"type\":\"string\",\"heavy\":true}]", ORDERING);

        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[0].heavy").value(false))
                .andExpect(jsonPath("$.columns[1].name").value("payload"))
                .andExpect(jsonPath("$.columns[1].heavy").value(true))
                // Heaviness is independent of sensitivity.
                .andExpect(jsonPath("$.columns[1].sensitive").value(false));

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"payload\",\"heavy\":false}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[1].heavy").value(false));

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"rename\":[{\"from\":\"payload\",\"to\":\"blob\"}],"
                                + "\"update\":[{\"name\":\"blob\",\"heavy\":true}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[1].name").value("blob"))
                .andExpect(jsonPath("$.columns[1].heavy").value(true));
    }

    @Test
    void heavyFlagIsSettableOnAnOrderingKeyColumn() throws Exception {
        // Metadata carries no mutability restriction: an ordering-key column may be marked heavy (odd
        // but harmless), exactly as it may be tagged or marked sensitive.
        activeSource(COLUMNS, ORDERING);
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"widget_id\",\"heavy\":true}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].heavy").value(true));
    }

    @Test
    void seededPayloadColumnsAreHeavyOnTheReadSurface() throws Exception {
        // The migration's seed, visible unfiltered to an operator (V18, extended by V29 and re-keyed
        // onto the payload table by V38). This surface reports a table's OWN columns under their
        // physical names, so the payload columns are read from the payload table — not under the
        // qualified flat name the query surface publishes them as.
        //
        // heavy and sensitive are independent, and the payload table is where that shows: the three
        // bodies are both, the two documents are sensitive but NOT heavy. Both flags rode across the
        // re-key untouched, which is the property V38 chose to move the rows rather than restate them.
        mockMvc.perform(get("/v1/tables/dial_usage_log_payload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[?(@.name == 'request_body')].heavy").value(true))
                .andExpect(jsonPath("$.columns[?(@.name == 'response_body')].heavy").value(true))
                .andExpect(jsonPath("$.columns[?(@.name == 'assembled_response')].heavy").value(true))
                .andExpect(jsonPath("$.columns[?(@.name == 'assembled_response')].sensitive").value(true))
                .andExpect(jsonPath("$.columns[?(@.name == 'jwt_claims')].heavy").value(false))
                .andExpect(jsonPath("$.columns[?(@.name == 'jwt_claims')].sensitive").value(true))
                .andExpect(jsonPath("$.columns[?(@.name == 'request_tags')].sensitive").value(true))
                // The three request-delta columns (V50) hold a chat request's content in parts: heavy and sensitive like it.
                .andExpect(jsonPath("$.columns[?(@.name == 'request_envelope')].heavy").value(true))
                .andExpect(jsonPath("$.columns[?(@.name == 'request_delta')].heavy").value(true))
                .andExpect(jsonPath("$.columns[?(@.name == 'request_cache_markers')].sensitive").value(true))
                .andExpect(jsonPath("$.columns", hasSize(8)))
                // The catalog mirrors the physical shape: ORDER BY (event_id), recorded by V38 exactly as
                // CatalogSchemaWriter records it for an API-created enrichment. Left at the column default
                // this would read [] — the catalog-versus-physical divergence V22 had to retrofit for write_mode.
                .andExpect(jsonPath("$.ordering_key", hasSize(1)))
                .andExpect(jsonPath("$.ordering_key[0]").value("event_id"))
                .andExpect(jsonPath("$.grain.grain_key").value("event_id"));

        // The size columns stayed behind on the parent: they size a payload without holding one.
        mockMvc.perform(get("/v1/tables/dial_usage_log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[?(@.name == 'request_body_bytes')].heavy").value(false))
                .andExpect(jsonPath("$.columns[?(@.name == 'user_hash')].heavy").value(false));
    }

    @Test
    void setSensitiveOfUnknownColumnIsRejected() throws Exception {
        activeSource(COLUMNS, ORDERING);
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"ghost\",\"sensitive\":true}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void tagLongerThanLimitIsRejected() throws Exception {
        createIdentity();
        final String longTag = "x".repeat(65);
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":[{\"source_name\":\"a\",\"name\":\"a\",\"type\":\"string\","
                                + "\"tag\":\"" + longTag + "\"}],\"ordering_key\":[\"a\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message", containsString("tag must be at most 64 characters")));
    }

    @Test
    void displayNameIsDeclaredReturnedInDiscoverySetAndPreservedAcrossRename() throws Exception {
        activeSource("[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                + "{\"source_name\":\"total_money\",\"name\":\"total_money\",\"type\":\"long\","
                + "\"display_name\":\"Total money spend\"}]", ORDERING);

        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[0].display_name").doesNotExist())
                .andExpect(jsonPath("$.columns[1].name").value("total_money"))
                .andExpect(jsonPath("$.columns[1].display_name").value("Total money spend"));

        mockMvc.perform(get("/v1/queries/entities/schema/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[1].name").value("total_money"))
                .andExpect(jsonPath("$.fields[1].display_name").value("Total money spend"));

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"total_money\",\"display_name\":\"Total spend\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[1].display_name").value("Total spend"));

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"rename\":[{\"from\":\"total_money\",\"to\":\"spend\"}],"
                                + "\"update\":[{\"name\":\"spend\",\"display_name\":\"Money spent\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[1].name").value("spend"))
                .andExpect(jsonPath("$.columns[1].display_name").value("Money spent"));

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"spend\",\"display_name\":\"\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[1].display_name").doesNotExist());
    }

    @Test
    void setDisplayNameOfUnknownColumnIsRejected() throws Exception {
        activeSource(COLUMNS, ORDERING);
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"ghost\",\"display_name\":\"x\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void displayNameLongerThanLimitIsRejected() throws Exception {
        createIdentity();
        final String longName = "x".repeat(129);
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":[{\"source_name\":\"a\",\"name\":\"a\",\"type\":\"string\","
                                + "\"display_name\":\"" + longName + "\"}],\"ordering_key\":[\"a\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message", containsString("display_name must be at most")));
    }

    @Test
    void descriptionIsDeclaredReturnedInDiscoverySetAndPreservedAcrossRename() throws Exception {
        activeSource("[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                + "{\"source_name\":\"total_money\",\"name\":\"total_money\",\"type\":\"long\","
                + "\"description\":\"Total money spent\"}]", ORDERING);

        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[0].description").doesNotExist())
                .andExpect(jsonPath("$.columns[1].name").value("total_money"))
                .andExpect(jsonPath("$.columns[1].description").value("Total money spent"));

        mockMvc.perform(get("/v1/queries/entities/schema/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[1].name").value("total_money"))
                .andExpect(jsonPath("$.fields[1].description").value("Total money spent"));

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"total_money\",\"description\":\"Total spend\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[1].description").value("Total spend"));

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"rename\":[{\"from\":\"total_money\",\"to\":\"spend\"}],"
                                + "\"update\":[{\"name\":\"spend\",\"description\":\"Money spent\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[1].name").value("spend"))
                .andExpect(jsonPath("$.columns[1].description").value("Money spent"));

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"spend\",\"description\":\"\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns[1].description").doesNotExist());
    }

    @Test
    void setDescriptionOfUnknownColumnIsRejected() throws Exception {
        activeSource(COLUMNS, ORDERING);
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"ghost\",\"description\":\"x\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void descriptionLongerThanLimitIsRejected() throws Exception {
        createIdentity();
        final String longDescription = "x".repeat(1025);
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":[{\"source_name\":\"a\",\"name\":\"a\",\"type\":\"string\","
                                + "\"description\":\"" + longDescription + "\"}],\"ordering_key\":[\"a\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message", containsString("description must be at most")));
    }

    @Test
    void enrichmentRequiresValidSource() throws Exception {
        // type=enrichment with a non-existent source_table is a 422 validation error at create.
        mockMvc.perform(post("/v1/tables").contentType(JSON)
                        .content("{\"name\":\"" + ENRICHMENT + "\",\"type\":\"enrichment\",\"source_table\":\"ghost\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void nonSnakeCaseNameIsRejected() throws Exception {
        mockMvc.perform(post("/v1/tables").contentType(JSON)
                        .content("{\"name\":\"WidgetMetrics\",\"type\":\"source\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void arrayColumnLifecycle() throws Exception {
        // Draft + materialize a source with an array<string> column — Array(String), element_type on GET.
        activeSource(
                "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                        + "{\"source_name\":\"tags\",\"name\":\"tags\",\"type\":\"array\",\"element_type\":\"string\"}]",
                "[\"widget_id\"]");
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[1].name").value("tags"))
                .andExpect(jsonPath("$.columns[1].type").value("array"))
                .andExpect(jsonPath("$.columns[1].element_type").value("string"))
                .andExpect(jsonPath("$.columns[1].nullable").value(false))
                .andExpect(jsonPath("$.columns[0].element_type").doesNotExist());

        // PATCH-add a second array column via ALTER on the live table.
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"add\":[{\"source_name\":\"codes\",\"name\":\"codes\","
                                + "\"type\":\"array\",\"element_type\":\"long\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns", hasSize(3)))
                .andExpect(jsonPath("$.columns[2].element_type").value("long"));

        // Discovery surfaces the element type on array fields only.
        mockMvc.perform(get("/v1/queries/entities/schema/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[0].element_type").doesNotExist())
                .andExpect(jsonPath("$.fields[1].element_type").value("string"))
                .andExpect(jsonPath("$.fields[2].element_type").value("long"));

        // JSON array values round-trip through the row-write API and a structured row query.
        mockMvc.perform(post("/v1/tables/" + TABLE + "/rows").contentType(JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"tags\":[\"a\",\"b\"],\"codes\":[1,2,3]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(1));
        mockMvc.perform(post("/v1/queries/execute").contentType(JSON)
                        .content("{\"entity\":\"" + TABLE + "\",\"mode\":\"row\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].tags[0]").value("a"))
                .andExpect(jsonPath("$.rows[0].tags[1]").value("b"))
                .andExpect(jsonPath("$.rows[0].codes[2]").value(3));
    }

    @Test
    void writeRejectsMissingOrNullNonNullableColumn() throws Exception {
        activeSource(
                "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                        + "{\"source_name\":\"score\",\"name\":\"score\",\"type\":\"long\"},"
                        + "{\"source_name\":\"note\",\"name\":\"note\",\"type\":\"string\",\"nullable\":true}]",
                "[\"widget_id\"]");

        // Omitting a declared non-nullable column is rejected before any row is inserted.
        mockMvc.perform(post("/v1/tables/" + TABLE + "/rows").contentType(JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message", containsString("score")));

        // Explicitly writing null for a non-nullable column is rejected the same way.
        mockMvc.perform(post("/v1/tables/" + TABLE + "/rows").contentType(JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"score\":null}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message", containsString("score")));

        // A nullable column may still be omitted, or explicitly nulled, exactly as before this change.
        mockMvc.perform(post("/v1/tables/" + TABLE + "/rows").contentType(JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"score\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(1));
        mockMvc.perform(post("/v1/tables/" + TABLE + "/rows").contentType(JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w2\",\"score\":2,\"note\":null}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(1));
    }

    @Test
    void writeRejectsMissingOrNullGrainKeyEvenWhenSourceDeclaresItNullable() throws Exception {
        activeSource(
                "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                        + "{\"source_name\":\"category\",\"name\":\"category\",\"type\":\"string\",\"nullable\":true}]",
                "[\"widget_id\"]");
        IntegrationTables.enrichment(mockMvc, ENRICHMENT, TABLE, "category",
                "[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"}]");

        // The grain key is always non-nullable on the enrichment, regardless of the source's own
        // "category" column being declared nullable.
        mockMvc.perform(post("/v1/tables/" + ENRICHMENT + "/rows").contentType(JSON)
                        .content("{\"rows\":[{\"label\":\"x\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message", containsString("category")));
        mockMvc.perform(post("/v1/tables/" + ENRICHMENT + "/rows").contentType(JSON)
                        .content("{\"rows\":[{\"category\":null,\"label\":\"x\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message", containsString("category")));

        mockMvc.perform(post("/v1/tables/" + ENRICHMENT + "/rows").contentType(JSON)
                        .content("{\"rows\":[{\"category\":\"c1\",\"label\":\"x\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(1));
    }

    @Test
    void arrayColumnWithoutElementTypeIsRejected() throws Exception {
        createIdentity();
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":[{\"source_name\":\"tags\",\"name\":\"tags\",\"type\":\"array\"}],"
                                + "\"ordering_key\":[\"tags\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message", containsString("requires an element_type")));
    }

    @Test
    void nullableArrayColumnIsRejected() throws Exception {
        createIdentity();
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":[{\"source_name\":\"tags\",\"name\":\"tags\",\"type\":\"array\","
                                + "\"element_type\":\"string\",\"nullable\":true}],"
                                + "\"ordering_key\":[\"tags\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message", containsString("cannot be nullable")));
    }

    @Test
    void elementTypeOnNonArrayColumnIsRejected() throws Exception {
        createIdentity();
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":[{\"source_name\":\"a\",\"name\":\"a\",\"type\":\"string\","
                                + "\"element_type\":\"string\"}],"
                                + "\"ordering_key\":[\"a\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message",
                        containsString("element_type is only valid for array columns")));
    }

    @Test
    void systemFlagIsSeededOnDefaultTablesAndFalseForUserTables() throws Exception {
        mockMvc.perform(get("/v1/tables/dial_usage_log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.system").value(true));
        mockMvc.perform(get("/v1/tables/rate_analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.system").value(true));
        mockMvc.perform(get("/v1/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name=='dial_usage_log')].system").value(true));

        mockMvc.perform(post("/v1/tables").contentType(JSON)
                        .content("{\"name\":\"" + TABLE + "\",\"description\":\"widget scores\",\"type\":\"source\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.system").value(false));
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.system").value(false));
    }

    @Test
    void seededSystemTablesExposeColumnDescriptions() throws Exception {
        // 34 after V38 re-keyed the five verbatim-content columns onto dial_usage_log_payload, plus the two
        // route columns V49 registered.
        mockMvc.perform(get("/v1/tables/dial_usage_log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns", hasSize(36)))
                .andExpect(jsonPath("$.columns[?(@.description != null && @.description != '')]", hasSize(36)))
                .andExpect(jsonPath("$.columns[?(@.name=='event_id')].description",
                        contains("Unique identifier of this usage-log event.")));
        // The moved rows kept their descriptions: V38 re-keys them rather than re-declaring them.
        mockMvc.perform(get("/v1/tables/dial_usage_log_payload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns", hasSize(8)))
                .andExpect(jsonPath("$.columns[?(@.description != null && @.description != '')]", hasSize(8)))
                .andExpect(jsonPath("$.columns[?(@.name=='request_body')].description",
                        contains("Raw request body payload.")));
        mockMvc.perform(get("/v1/tables/rate_analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns", hasSize(10)))
                .andExpect(jsonPath("$.columns[?(@.description != null && @.description != '')]", hasSize(10)))
                .andExpect(jsonPath("$.columns[?(@.name=='event_id')].description",
                        contains("Unique identifier of this rate event.")));
    }

    @Test
    void seededRouteColumnsAreNullableDeploymentAttributes() throws Exception {
        // V49 registers the two columns V15 appended. Nullable matches LowCardinality(Nullable(String)) —
        // NULL is "no route served the request" — and the tag groups them with deployment.
        mockMvc.perform(get("/v1/tables/dial_usage_log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[?(@.name=='route_name' || @.name=='route_type')].type",
                        contains("string", "string")))
                .andExpect(jsonPath("$.columns[?(@.name=='route_name' || @.name=='route_type')].nullable",
                        contains(true, true)))
                .andExpect(jsonPath("$.columns[?(@.name=='route_name' || @.name=='route_type')].tag",
                        contains("deployment", "deployment")))
                .andExpect(jsonPath("$.columns[?(@.name=='route_name' || @.name=='route_type')].sensitive",
                        contains(false, false)))
                .andExpect(jsonPath("$.columns[?(@.name=='route_name' || @.name=='route_type')].heavy",
                        contains(false, false)))
                .andExpect(jsonPath("$.columns[?(@.name=='route_name')].display_name", contains("Route name")))
                .andExpect(jsonPath("$.columns[?(@.name=='route_type')].display_name", contains("Route type")));
    }

    @Test
    void seededSystemTablesExposeColumnDisplayNames() throws Exception {
        mockMvc.perform(get("/v1/tables/dial_usage_log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns", hasSize(36)))
                .andExpect(jsonPath("$.columns[?(@.display_name != null && @.display_name != '')]", hasSize(36)))
                .andExpect(jsonPath("$.columns[?(@.name=='event_id')].display_name", contains("Event ID")));
        mockMvc.perform(get("/v1/tables/dial_usage_log_payload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns", hasSize(8)))
                .andExpect(jsonPath("$.columns[?(@.display_name != null && @.display_name != '')]", hasSize(8)))
                .andExpect(jsonPath("$.columns[?(@.name=='request_body')].display_name",
                        contains("Request body")));
        mockMvc.perform(get("/v1/tables/rate_analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns", hasSize(10)))
                .andExpect(jsonPath("$.columns[?(@.display_name != null && @.display_name != '')]", hasSize(10)))
                .andExpect(jsonPath("$.columns[?(@.name=='event_id')].display_name", contains("Event ID")))
                .andExpect(jsonPath("$.columns[?(@.name=='rate_raw')].display_name",
                        contains("Rate (as submitted)")));
    }

    @Test
    void createIgnoresClientSuppliedSystemFlag() throws Exception {
        // system is server-controlled: a body attempting to set it is ignored, not honored.
        mockMvc.perform(post("/v1/tables").contentType(JSON)
                        .content("{\"name\":\"" + TABLE + "\",\"description\":\"widget scores\",\"type\":\"source\","
                                + "\"system\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.system").value(false));
    }

    // Tagged columns (declared out of tag/alpha order) plus one untagged, to observe tag-order grouping
    // and within-group alphabetical sort.
    private static final String TAGGED_COLUMNS = """
            [{"source_name":"widget_id","name":"widget_id","type":"string","tag":"identifier"},
             {"source_name":"price","name":"price","type":"long","tag":"cost"},
             {"source_name":"amount","name":"amount","type":"long","tag":"cost"},
             {"source_name":"beta_col","name":"beta_col","type":"string","tag":"beta"},
             {"source_name":"alpha_col","name":"alpha_col","type":"string","tag":"alpha"},
             {"source_name":"note","name":"note","type":"string"}]""";

    @Test
    void tagOrderIsSetSortsColumnsClearsAndReturnedOnGet() throws Exception {
        activeSource(TAGGED_COLUMNS, ORDERING);

        // Tag ordering is table catalog metadata — set via PUT /v1/tables/{name}, not the schema endpoint.
        mockMvc.perform(put("/v1/tables/" + TABLE).contentType(JSON)
                        .content("{\"tag_order\":[\"identifier\",\"cost\"]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tag_order[0]").value("identifier"))
                .andExpect(jsonPath("$.tag_order[1]").value("cost"))
                .andExpect(jsonPath("$.columns", hasSize(6)))
                .andExpect(jsonPath("$.columns[0].name").value("widget_id"))
                .andExpect(jsonPath("$.columns[1].name").value("amount"))
                .andExpect(jsonPath("$.columns[2].name").value("price"))
                .andExpect(jsonPath("$.columns[3].name").value("alpha_col"))
                .andExpect(jsonPath("$.columns[4].name").value("beta_col"))
                .andExpect(jsonPath("$.columns[5].name").value("note"));

        mockMvc.perform(put("/v1/tables/" + TABLE).contentType(JSON).content("{\"tag_order\":[]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.tag_order").doesNotExist())
                .andExpect(jsonPath("$.columns[0].name").value("widget_id"))
                .andExpect(jsonPath("$.columns[1].name").value("price"))
                .andExpect(jsonPath("$.columns[2].name").value("amount"));
    }

    @Test
    void tagOrderDeclaredAtCreateSurvivesColumnChurnAndAbsentPatch() throws Exception {
        // Declare the ordering at create time (catalog metadata carried on the identity).
        mockMvc.perform(post("/v1/tables").contentType(JSON)
                        .content("{\"name\":\"" + TABLE + "\",\"type\":\"source\","
                                + "\"tag_order\":[\"identifier\",\"cost\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tag_order[0]").value("identifier"));
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":[{\"source_name\":\"widget_id\",\"name\":\"widget_id\","
                                + "\"type\":\"string\",\"tag\":\"identifier\"},"
                                + "{\"source_name\":\"price\",\"name\":\"price\",\"type\":\"long\",\"tag\":\"cost\"}],"
                                + "\"ordering_key\":[\"widget_id\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));

        // Churn via live PATCH: retag a column and add a new-tagged column. tag_order stays untouched.
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"price\",\"tag\":\"money\"}],"
                                + "\"add\":[{\"source_name\":\"kind\",\"name\":\"kind\",\"type\":\"string\",\"tag\":\"dimension\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.tag_order", hasSize(2)))
                .andExpect(jsonPath("$.tag_order[0]").value("identifier"))
                .andExpect(jsonPath("$.tag_order[1]").value("cost"));
    }

    @Test
    void tagOrderInvalidEntriesAreRejected() throws Exception {
        createIdentity();

        mockMvc.perform(put("/v1/tables/" + TABLE).contentType(JSON)
                        .content("{\"tag_order\":[\"identifier\",\"  \"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));

        mockMvc.perform(put("/v1/tables/" + TABLE).contentType(JSON)
                        .content("{\"tag_order\":[\"cost\",\"cost\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));

        mockMvc.perform(put("/v1/tables/" + TABLE).contentType(JSON)
                        .content("{\"tag_order\":[\"" + "x".repeat(65) + "\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void tagOrderOnSystemTableIsRejected() throws Exception {
        // system-owned protection precedes tag_order handling: the update is refused with 409.
        mockMvc.perform(put("/v1/tables/dial_usage_log").contentType(JSON)
                        .content("{\"tag_order\":[\"cost\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("table_is_system"));
    }

    @Test
    void seededSystemTablesCarryTagOrder() throws Exception {
        mockMvc.perform(get("/v1/tables/dial_usage_log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tag_order[0]").value("identifier"))
                .andExpect(jsonPath("$.tag_order[1]").value("dimension"));
        mockMvc.perform(get("/v1/tables/rate_analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tag_order[0]").value("identifier"));
    }

    @Test
    void systemTableRejectsEveryModificationButStaysReadableAndQueryable() throws Exception {
        // Update (description) is rejected with 409 and the stable table_is_system code.
        mockMvc.perform(put("/v1/tables/dial_usage_log").contentType(JSON)
                        .content("{\"description\":\"tampered\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("table_is_system"));

        // Defining/materializing the schema is rejected (no schema stored, no DDL).
        mockMvc.perform(post("/v1/tables/dial_usage_log/schema").contentType(JSON)
                        .content("{\"columns\":[{\"source_name\":\"x\",\"name\":\"x\",\"type\":\"long\"}],"
                                + "\"ordering_key\":[\"x\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("table_is_system"));

        // Live schema PATCH is rejected (no ALTER issued).
        mockMvc.perform(patch("/v1/tables/dial_usage_log/schema").contentType(JSON)
                        .content("{\"drop\":[\"user_hash\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("table_is_system"));

        // Row write is rejected (no insert issued).
        mockMvc.perform(post("/v1/tables/dial_usage_log/rows").contentType(JSON)
                        .content("{\"rows\":[{\"project_id\":\"p\"}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("table_is_system"));

        // Delete is rejected (no DROP issued) — the table is still present afterwards.
        mockMvc.perform(delete("/v1/tables/dial_usage_log"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("table_is_system"));

        // Reads and queries are unaffected.
        mockMvc.perform(get("/v1/tables/dial_usage_log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("DIAL usage log events ingested from blob storage."));
        mockMvc.perform(post("/v1/queries/execute").contentType(JSON)
                        // `limit` is a member of `page`, not of the query itself. Spelled at the top level it
                        // is now a 400 rather than a silently defaulted page — which is what it was before
                        // request bodies started refusing fields they do not declare.
                        .content("{\"entity\":\"dial_usage_log\",\"mode\":\"row\","
                                + "\"page\":{\"type\":\"offset\",\"offset\":0,\"limit\":1}}"))
                .andExpect(status().isOk());
    }

    // --- scan metadata on the schema and read surfaces ------------------------------------------

    /**
     * The pair a caller declares at schema-define is persisted and read back on both read surfaces — the
     * fact that was previously settable only by an operator migration and invisible on the API.
     */
    @Test
    void declaredScanMetadataIsPersistedAndReturned() throws Exception {
        createIdentity();
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":[{\"source_name\":\"widget_id\",\"name\":\"widget_id\","
                                + "\"type\":\"string\"},{\"source_name\":\"seen_at\",\"name\":\"seen_at\","
                                + "\"type\":\"timestamp\"}],\"ordering_key\":" + ORDERING + ","
                                + "\"identity_column\":\"widget_id\",\"version_column\":\"seen_at\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.identity_column").value("widget_id"))
                .andExpect(jsonPath("$.version_column").value("seen_at"));

        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity_column").value("widget_id"))
                .andExpect(jsonPath("$.version_column").value("seen_at"));
        mockMvc.perform(get("/v1/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name=='" + TABLE + "')].identity_column")
                        .value(contains("widget_id")))
                .andExpect(jsonPath("$.tables[?(@.name=='" + TABLE + "')].version_column")
                        .value(contains("seen_at")));
    }

    /**
     * Materializes {@code widget_metrics} with a scan-metadata pair naming non-ordering-key columns, so the
     * ordering-key guard (which fires first) cannot be what a scan-metadata rejection is really testing.
     */
    private void activeScannableSource() throws Exception {
        createIdentity();
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":[{\"source_name\":\"widget_id\",\"name\":\"widget_id\","
                                + "\"type\":\"string\"},{\"source_name\":\"row_id\",\"name\":\"row_id\","
                                + "\"type\":\"string\"},{\"source_name\":\"seen_at\",\"name\":\"seen_at\","
                                + "\"type\":\"timestamp\"},{\"source_name\":\"score\",\"name\":\"score\","
                                + "\"type\":\"long\"}],\"ordering_key\":" + ORDERING + ","
                                + "\"identity_column\":\"row_id\",\"version_column\":\"seen_at\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    /**
     * A column scan metadata names cannot be <b>dropped</b>: nothing repoints the pair to a different
     * column, so the table would advertise a scan column that no longer exists, unrepairably through the
     * API. A column the pair does not name stays freely droppable.
     */
    @Test
    void scanMetadataColumnsCannotBeDropped() throws Exception {
        activeScannableSource();

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"drop\":[\"row_id\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message",
                        containsString("the identity column 'row_id' cannot be dropped")));
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"drop\":[\"seen_at\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message",
                        containsString("the version column 'seen_at' cannot be dropped")));

        // The rejections are pre-DDL, so all four columns are intact; an unnamed column still drops.
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"drop\":[\"score\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns.length()").value(3))
                .andExpect(jsonPath("$.identity_column").value("row_id"))
                .andExpect(jsonPath("$.version_column").value("seen_at"));
    }

    /**
     * A rename, unlike a drop, has an answer: the physical column survives with its data, so the stored
     * name follows it in the same transaction — the treatment the TTL column already gets. Both members
     * repoint, and the renamed columns keep every property the pair was verified for at declaration.
     */
    @Test
    void renamingScanMetadataColumnRepointsTheStoredPair() throws Exception {
        activeScannableSource();

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"rename\":[{\"from\":\"row_id\",\"to\":\"rid\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity_column").value("rid"))
                .andExpect(jsonPath("$.version_column").value("seen_at"));

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"rename\":[{\"from\":\"seen_at\",\"to\":\"ingested_at\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version_column").value("ingested_at"));

        // Both repoints survive a re-read, and the renamed columns are the ones the table now carries.
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity_column").value("rid"))
                .andExpect(jsonPath("$.version_column").value("ingested_at"))
                .andExpect(jsonPath("$.columns[?(@.name=='rid')]").exists())
                .andExpect(jsonPath("$.columns[?(@.name=='ingested_at')]").exists())
                .andExpect(jsonPath("$.columns[?(@.name=='row_id')]").doesNotExist())
                .andExpect(jsonPath("$.columns[?(@.name=='seen_at')]").doesNotExist());
    }

    /**
     * The one column attribute a metadata {@code update} may not set on a cursor column. The scan projects
     * both cursor columns through its sensitive guard and carries the identity in the page cursor, so a
     * sensitive member would make every rule over this source answer 403 to any caller without the
     * entitlement — exactly what the schema-define contract refuses. Clearing the flag, and every other
     * attribute, stays permitted.
     *
     * <p><b>A second capability now depends on this refusal.</b> A pipeline read is open to any mapped
     * application role, and it reports the scan position's {@code cursor_identity} — a literal value of
     * the identity column — to every such caller, with no per-role redaction. That is only safe because
     * this rule makes a sensitive identity column unrepresentable. If this test is ever relaxed, the
     * pipeline read needs redaction of its identity-valued state fields in the same change; see the
     * {@code pipeline-registry} spec, "A pipeline read carries no row values".
     */
    @Test
    void markingScanMetadataColumnSensitiveIsRejected() throws Exception {
        activeScannableSource();

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"seen_at\",\"sensitive\":true}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message",
                        containsString("the version column 'seen_at' cannot be marked sensitive")));
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"row_id\",\"sensitive\":true}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message",
                        containsString("the identity column 'row_id' cannot be marked sensitive")));
        // Renaming the version column in the same request does not launder the update: the check is
        // against the pair as the request leaves it.
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"rename\":[{\"from\":\"seen_at\",\"to\":\"ingested_at\"}],"
                                + "\"update\":[{\"name\":\"ingested_at\",\"sensitive\":true}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message",
                        containsString("the version column 'ingested_at' cannot be marked sensitive")));

        // Other metadata on a cursor column, and `sensitive` on any other column, are unaffected.
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"update\":[{\"name\":\"seen_at\",\"sensitive\":false,"
                                + "\"display_name\":\"Seen at\",\"heavy\":true},"
                                + "{\"name\":\"score\",\"sensitive\":true}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[?(@.name=='seen_at')].display_name").value(contains("Seen at")))
                .andExpect(jsonPath("$.version_column").value("seen_at"));
    }

    /**
     * The auto-join fires on the shared physical name, and the enrichment's half of that name is its
     * ordering key — which neither this endpoint nor ClickHouse will rename. So the source's half is
     * immutable too, whether or not the pair also names it: a break would have no repair on either side.
     */
    @Test
    void sourceColumnKeyedOnByEnrichmentCannotBeDroppedOrRenamed() throws Exception {
        activeScannableSource();
        IntegrationTables.enrichment(mockMvc, ENRICHMENT, TABLE, "row_id",
                "[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"}]");

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"rename\":[{\"from\":\"row_id\",\"to\":\"rid\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message",
                        containsString("grain key of enrichment '" + ENRICHMENT + "'")));
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"drop\":[\"row_id\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message",
                        containsString("grain key of enrichment '" + ENRICHMENT + "'")));

        // Pre-DDL, so the join still resolves: a query naming the enrichment's column succeeds.
        mockMvc.perform(post("/v1/queries/execute").contentType(JSON)
                        .content("{\"entity\":\"" + TABLE + "\",\"mode\":\"row\",\"select\":["
                                + "{\"expr\":{\"type\":\"field\",\"name\":\"row_id\"}},"
                                + "{\"expr\":{\"type\":\"field\",\"name\":\"" + ENRICHMENT + ".label\"}}]}"))
                .andExpect(status().isOk());

        // A column no enrichment is keyed on renames as before — including the version column.
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"rename\":[{\"from\":\"seen_at\",\"to\":\"ingested_at\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version_column").value("ingested_at"));
    }

    /**
     * An {@code upsert_by_key} source MAY name the engine's own version column: {@code _updated_at} is a
     * system column, absent from {@code column_mapping}, but physically stamped on the
     * {@code ReplacingMergeTree} — and typically the only monotonic column such a table has, since an
     * upsert rewrites a key's row in place.
     */
    @Test
    void upsertSourceMayDeclareUpdatedAtAsItsVersion() throws Exception {
        mockMvc.perform(post("/v1/tables").contentType(JSON)
                        .content("{\"name\":\"" + TABLE + "\",\"type\":\"source\","
                                + "\"write\":\"upsert_by_key\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":" + COLUMNS + ",\"ordering_key\":" + ORDERING + ","
                                + "\"identity_column\":\"widget_id\",\"version_column\":\"_updated_at\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.version_column").value("_updated_at"));
    }

    /** An append source carries no {@code _updated_at}, so naming it is the ordinary membership 422. */
    @Test
    void appendSourceCannotDeclareUpdatedAtAsItsVersion() throws Exception {
        createIdentity();
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":" + COLUMNS + ",\"ordering_key\":" + ORDERING + ","
                                + "\"identity_column\":\"widget_id\",\"version_column\":\"_updated_at\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message",
                        containsString("only an 'upsert_by_key' table carries")));
    }

    /**
     * The pair is optional, and half of it is not a pair: a source declaring only one member materializes,
     * reports what it declared, and is not scannable — the scan is what refuses it, naming the missing
     * configuration. Nothing here forces a caller to invent an identity column it cannot vouch for.
     */
    @Test
    void sourceMayDeclareNoScanMetadataOrOnlyOneMember() throws Exception {
        createIdentity();
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":" + COLUMNS + ",\"ordering_key\":" + ORDERING + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.identity_column").doesNotExist())
                .andExpect(jsonPath("$.version_column").doesNotExist());
        mockMvc.perform(delete("/v1/tables/" + TABLE)).andExpect(status().isNoContent());

        createIdentity();
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":" + COLUMNS + ",\"ordering_key\":" + ORDERING + ","
                                + "\"identity_column\":\"widget_id\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity_column").value("widget_id"))
                .andExpect(jsonPath("$.version_column").doesNotExist());
    }

    /**
     * The physical rules on a declared pair, all answered at define time rather than by ClickHouse on the
     * first scan: the scan renders the cursor as
     * {@code (toUnixTimestamp64Milli(version), toString(identity))}, lags it against {@code now64(3)},
     * projects both columns, and carries the identity in its cursor.
     */
    @Test
    void declaredScanMetadataMustBeUsableByTheScan() throws Exception {
        createIdentity();
        // A non-temporal version column: toUnixTimestamp64Milli has nothing to read it as.
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":" + COLUMNS + ",\"ordering_key\":" + ORDERING + ","
                                + "\"identity_column\":\"widget_id\",\"version_column\":\"score\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message", containsString("version_column 'score' is declared 'long'")));

        // A nullable member: NULL fails every cursor comparison, so those rows would never be paged.
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":[{\"source_name\":\"widget_id\",\"name\":\"widget_id\","
                                + "\"type\":\"string\"},{\"source_name\":\"seen_at\",\"name\":\"seen_at\","
                                + "\"type\":\"timestamp\",\"nullable\":true}],\"ordering_key\":" + ORDERING + ","
                                + "\"identity_column\":\"widget_id\",\"version_column\":\"seen_at\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message", containsString("version_column 'seen_at' is nullable")));

        // A sensitive member: every un-entitled caller's scan of a rule over this source would be a 403.
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":[{\"source_name\":\"widget_id\",\"name\":\"widget_id\","
                                + "\"type\":\"string\",\"sensitive\":true},{\"source_name\":\"seen_at\","
                                + "\"name\":\"seen_at\",\"type\":\"timestamp\"}],\"ordering_key\":" + ORDERING + ","
                                + "\"identity_column\":\"widget_id\",\"version_column\":\"seen_at\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message", containsString("identity_column 'widget_id' is sensitive")));

        // None of the three materialized anything.
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void scanMetadataNamingAnUndeclaredColumnIsRejected() throws Exception {
        createIdentity();
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":" + COLUMNS + ",\"ordering_key\":" + ORDERING + ","
                                + "\"identity_column\":\"widget_id\",\"version_column\":\"nope\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message",
                        containsString("version_column 'nope' is not a declared column")));

        // Nothing was persisted: the table is still un-materialized.
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"));
    }

    /**
     * An enrichment declares neither member: it is keyed on its {@code grain_key}, and its row version is
     * the ReplacingMergeTree column the platform stamps. Either is a 422 rather than a 200 that quietly
     * ignored the field, and nothing is persisted.
     */
    @Test
    void eitherScanColumnOnAnEnrichmentIsRejected() throws Exception {
        activeSource(COLUMNS, ORDERING);
        mockMvc.perform(post("/v1/tables").contentType(JSON)
                        .content("{\"name\":\"" + ENRICHMENT + "\",\"type\":\"enrichment\","
                                + "\"source_table\":\"" + TABLE + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/v1/tables/" + ENRICHMENT + "/schema").contentType(JSON)
                        .content("{\"grain_key\":\"widget_id\",\"columns\":[{\"source_name\":\"label\","
                                + "\"name\":\"label\",\"type\":\"string\"}],"
                                + "\"identity_column\":\"widget_id\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message",
                        containsString("identity_column is not accepted for an enrichment")));

        mockMvc.perform(post("/v1/tables/" + ENRICHMENT + "/schema").contentType(JSON)
                        .content("{\"grain_key\":\"widget_id\",\"columns\":[{\"source_name\":\"label\","
                                + "\"name\":\"label\",\"type\":\"string\"}],"
                                + "\"version_column\":\"_updated_at\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message",
                        containsString("version_column is not accepted for an enrichment")));

        mockMvc.perform(get("/v1/tables/" + ENRICHMENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.identity_column").doesNotExist())
                .andExpect(jsonPath("$.version_column").doesNotExist());
    }

    /** The seeded sources keep the values their migrations set, now visible on the read surface. */
    @Test
    void seededSourceReportsItsMigrationSetScanMetadata() throws Exception {
        mockMvc.perform(get("/v1/tables/dial_usage_log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity_column").value("event_id"))
                .andExpect(jsonPath("$.version_column").value("_ingested_at"));
    }

    // ------------------------------------------------------------------ provenance injection

    /**
     * All three provenance shapes that exist live, asserted against the one rule that makes injection
     * safe: <b>injection can only ever add</b>. A provenance name the caller declared is left exactly as
     * declared — never duplicated, never overwritten, never reordered — and only the missing ones are
     * appended, in canonical order.
     *
     * <p>Without that rule injection would be unusable: two live targets declare all five themselves,
     * with their own wording, tag and position, and any existing authoring script replayed against a new
     * table does the same — so injecting unconditionally would turn every such script into a
     * duplicate-column 422.
     */
    @Test
    void enrichmentDeclaringEveryProvenanceColumnKeepsItsOwn() throws Exception {
        activeSource(COLUMNS, ORDERING);
        mockMvc.perform(post("/v1/tables").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"prov_all\",\"type\":\"enrichment\","
                                + "\"source_table\":\"" + TABLE + "\"}"))
                .andExpect(status().isCreated());
        try {
            mockMvc.perform(post("/v1/tables/prov_all/schema").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"grain_key\":\"widget_id\",\"columns\":["
                                    + "{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"},"
                                    // Declared FIRST, with the caller's own description and tag — both of
                                    // which must survive verbatim.
                                    + "{\"source_name\":\"model\",\"name\":\"model\",\"type\":\"string\","
                                    + "\"description\":\"Mine, not the canonical text.\",\"tag\":\"lineage\"},"
                                    + "{\"source_name\":\"evaluator_version\",\"name\":\"evaluator_version\","
                                    + "\"type\":\"integer\"},"
                                    + "{\"source_name\":\"pipeline_generation\","
                                    + "\"name\":\"pipeline_generation\",\"type\":\"long\"},"
                                    + "{\"source_name\":\"enriched_at\",\"name\":\"enriched_at\","
                                    + "\"type\":\"timestamp\"},"
                                    + "{\"source_name\":\"group_version\",\"name\":\"group_version\","
                                    + "\"type\":\"long\"},"
                                    + "{\"source_name\":\"truncated\",\"name\":\"truncated\","
                                    + "\"type\":\"boolean\"}]}"))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/v1/tables/prov_all"))
                    // Seven columns, not thirteen: nothing was duplicated.
                    .andExpect(jsonPath("$.columns", hasSize(7)))
                    .andExpect(jsonPath("$.columns[?(@.name=='model')].description",
                            contains("Mine, not the canonical text.")))
                    .andExpect(jsonPath("$.columns[?(@.name=='model')].tag", contains("lineage")));
        } finally {
            mockMvc.perform(delete("/v1/tables/prov_all"));
        }
    }

    /** Two declared and four appended — the shape one live Class-1 target actually has. */
    @Test
    void enrichmentDeclaringTwoProvenanceColumnsGetsTheRest() throws Exception {
        activeSource(COLUMNS, ORDERING);
        mockMvc.perform(post("/v1/tables").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"prov_two\",\"type\":\"enrichment\","
                                + "\"source_table\":\"" + TABLE + "\"}"))
                .andExpect(status().isCreated());
        try {
            mockMvc.perform(post("/v1/tables/prov_two/schema").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"grain_key\":\"widget_id\",\"columns\":["
                                    + "{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"},"
                                    + "{\"source_name\":\"enriched_at\",\"name\":\"enriched_at\","
                                    + "\"type\":\"timestamp\",\"description\":\"Mine.\"},"
                                    + "{\"source_name\":\"evaluator_version\",\"name\":\"evaluator_version\","
                                    + "\"type\":\"integer\"}]}"))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/v1/tables/prov_two"))
                    .andExpect(jsonPath("$.columns", hasSize(7)))
                    .andExpect(jsonPath("$.columns[?(@.name=='enriched_at')].description",
                            contains("Mine.")))
                    // The four that were missing, appended with the canonical tag.
                    .andExpect(jsonPath("$.columns[?(@.name=='pipeline_generation')].tag",
                            contains("system")))
                    .andExpect(jsonPath("$.columns[?(@.name=='model')].tag", contains("system")))
                    .andExpect(jsonPath("$.columns[?(@.name=='group_version')].tag", contains("system")))
                    .andExpect(jsonPath("$.columns[?(@.name=='truncated')].tag", contains("system")));
        } finally {
            mockMvc.perform(delete("/v1/tables/prov_two"));
        }
    }

    /**
     * None declared — the shape a Class-1 target actually has live, and the case injection exists for.
     * The canonical types are load-bearing: the write path switches on the DECLARED type, so
     * {@code pipeline_generation} has to be {@code long} (a generation is an unbounded counter). The
     * retired {@code evaluator_version} is not injected: nothing writes it, so it could only ever be empty.
     */
    @Test
    void enrichmentDeclaringNoProvenanceGetsThemAllInCanonicalOrder() throws Exception {
        activeSource(COLUMNS, ORDERING);
        IntegrationTables.enrichment(mockMvc, "prov_none", TABLE, "widget_id",
                "[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"}]");
        try {
            mockMvc.perform(get("/v1/tables/prov_none"))
                    .andExpect(jsonPath("$.columns", hasSize(6)))
                    .andExpect(jsonPath("$.columns[?(@.name=='evaluator_version')]", hasSize(0)))
                    .andExpect(jsonPath("$.columns[?(@.name=='pipeline_generation')].type",
                            contains("long")))
                    .andExpect(jsonPath("$.columns[?(@.name=='model')].type", contains("string")))
                    .andExpect(jsonPath("$.columns[?(@.name=='enriched_at')].type", contains("timestamp")))
                    .andExpect(jsonPath("$.columns[?(@.name=='group_version')].type", contains("long")))
                    .andExpect(jsonPath("$.columns[?(@.name=='truncated')].type", contains("boolean")))
                    // The `truncated` description must keep saying it is NOT a quality signal: a
                    // member-selection policy of one row sets it on every multi-member group by design.
                    .andExpect(jsonPath("$.columns[?(@.name=='truncated')].description",
                            contains(containsString("NOT a quality signal"))));
        } finally {
            mockMvc.perform(delete("/v1/tables/prov_none"));
        }
    }

    /** A {@code source} table receives none: provenance belongs to an enrichment's rows. */
    @Test
    void sourceTableReceivesNoProvenance() throws Exception {
        activeSource(COLUMNS, ORDERING);
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(jsonPath("$.columns", hasSize(2)));
    }
}
