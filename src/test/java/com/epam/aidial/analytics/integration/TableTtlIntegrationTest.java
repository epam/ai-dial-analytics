package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the table TTL lifecycle against real PostgreSQL + ClickHouse: declaring a TTL
 * at define-and-materialize time ({@code CREATE TABLE ... TTL ...}), setting/replacing/removing it via
 * the {@code ttl} member of {@code PATCH /v1/tables/{name}/schema} ({@code ALTER ... MODIFY/REMOVE
 * TTL}, one multi-command statement with any column operations), the catalog staying in sync with the
 * physical table (asserted via {@code SHOW CREATE TABLE}), and the validation/protection rules
 * (non-time or nullable columns, drop of the TTL column, PENDING tables, system tables, create-time
 * ttl).
 *
 * <p>Requires a running Docker daemon.
 */
class TableTtlIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String TABLE = "ttl_probe";
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private static final String COLUMNS =
            "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                    + "{\"source_name\":\"created_at\",\"name\":\"created_at\",\"type\":\"timestamp\"},"
                    + "{\"source_name\":\"event_date\",\"name\":\"event_date\",\"type\":\"date\"}]";
    private static final String ORDERING = "[\"widget_id\"]";

    @AfterEach
    void cleanUp() throws Exception {
        mockMvc.perform(delete("/v1/tables/" + TABLE));
    }

    private void createIdentity() throws Exception {
        mockMvc.perform(post("/v1/tables").contentType(JSON)
                        .content("{\"name\":\"" + TABLE + "\",\"type\":\"source\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"));
    }

    private void materialize(String extraSchemaJson) throws Exception {
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":" + COLUMNS + ",\"ordering_key\":" + ORDERING + extraSchemaJson + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void defineWithTimestampTtlRendersWrappedClause() throws Exception {
        createIdentity();
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":" + COLUMNS + ",\"ordering_key\":" + ORDERING
                                + ",\"ttl\":{\"column\":\"created_at\",\"interval\":90,\"unit\":\"day\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.ttl.column").value("created_at"))
                .andExpect(jsonPath("$.ttl.interval").value(90))
                .andExpect(jsonPath("$.ttl.unit").value("day"));

        // The physical table carries the clause; a timestamp (DateTime64) column is wrapped.
        assertThat(showCreateTable(TABLE)).contains("toDateTime(created_at)").contains("toIntervalDay(90)");

        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttl.column").value("created_at"));
    }

    @Test
    void defineWithDateTtlRendersBareColumn() throws Exception {
        createIdentity();
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":" + COLUMNS + ",\"ordering_key\":" + ORDERING
                                + ",\"ttl\":{\"column\":\"event_date\",\"interval\":6,\"unit\":\"month\"}}"))
                .andExpect(status().isOk());

        assertThat(showCreateTable(TABLE))
                .contains("event_date + toIntervalMonth(6)")
                .doesNotContain("toDateTime(event_date)");
    }

    @Test
    void invalidTtlAtDefineLeavesTablePending() throws Exception {
        createIdentity();
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":" + COLUMNS + ",\"ordering_key\":" + ORDERING
                                + ",\"ttl\":{\"column\":\"widget_id\",\"interval\":1,\"unit\":\"day\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));

        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void patchSetsReplacesAndRemovesTtl() throws Exception {
        createIdentity();
        materialize("");

        // Set: MODIFY TTL lands physically and in the catalog.
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"ttl\":{\"column\":\"created_at\",\"interval\":90,\"unit\":\"day\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttl.column").value("created_at"))
                .andExpect(jsonPath("$.ttl.interval").value(90))
                .andExpect(jsonPath("$.ttl.unit").value("day"));
        assertThat(showCreateTable(TABLE)).contains("toDateTime(created_at)").contains("toIntervalDay(90)");

        // Replace: a new full object overwrites both the clause and the catalog value.
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"ttl\":{\"column\":\"event_date\",\"interval\":1,\"unit\":\"year\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttl.column").value("event_date"))
                .andExpect(jsonPath("$.ttl.unit").value("year"));
        assertThat(showCreateTable(TABLE))
                .contains("event_date + toIntervalYear(1)")
                .doesNotContain("toDateTime(created_at)");

        // A column-only PATCH leaves the TTL untouched (absent member = unchanged).
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"add\":[{\"source_name\":\"tier\",\"name\":\"tier\",\"type\":\"string\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttl.column").value("event_date"));

        // Remove with {}: REMOVE TTL, and the read surface omits the member.
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"ttl\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttl").doesNotExist());
        assertThat(showCreateTable(TABLE)).doesNotContain("TTL");

        // Removing again is an idempotent no-op.
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"ttl\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttl").doesNotExist());
    }

    @Test
    void columnsAndTtlChangeInOneRequest() throws Exception {
        createIdentity();
        materialize("");

        // Add a timestamp column and point the TTL at it — one request, one ALTER statement.
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"add\":[{\"source_name\":\"seen_at\",\"name\":\"seen_at\",\"type\":\"timestamp\"}],"
                                + "\"ttl\":{\"column\":\"seen_at\",\"interval\":30,\"unit\":\"day\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns", hasSize(4)))
                .andExpect(jsonPath("$.ttl.column").value("seen_at"));
        assertThat(showCreateTable(TABLE)).contains("toDateTime(seen_at)").contains("toIntervalDay(30)");
    }

    @Test
    void invalidTtlLeavesWholePatchUnapplied() throws Exception {
        createIdentity();
        materialize("");

        // The ttl names a non-time column, so the request is rejected — and the valid add must not
        // have been applied either (the PATCH is all-or-nothing).
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"add\":[{\"source_name\":\"tier\",\"name\":\"tier\",\"type\":\"string\"}],"
                                + "\"ttl\":{\"column\":\"widget_id\",\"interval\":1,\"unit\":\"day\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));

        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns", hasSize(3)))
                .andExpect(jsonPath("$.ttl").doesNotExist());
        assertThat(showCreateTable(TABLE)).doesNotContain("tier");
    }

    @Test
    void partialTtlObjectIsRejected() throws Exception {
        createIdentity();
        materialize("");

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"ttl\":{\"column\":\"created_at\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void nonPositiveTtlIntervalIsRejectedWith422() throws Exception {
        createIdentity();

        // At define time: 422 from the semantic TTL validation (not a 400 binding failure), table
        // stays un-materialized.
        mockMvc.perform(post("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"columns\":" + COLUMNS + ",\"ordering_key\":" + ORDERING
                                + ",\"ttl\":{\"column\":\"created_at\",\"interval\":0,\"unit\":\"day\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));

        materialize("");

        // On the live PATCH: zero and negative intervals are both 422.
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"ttl\":{\"column\":\"created_at\",\"interval\":0,\"unit\":\"day\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"ttl\":{\"column\":\"created_at\",\"interval\":-5,\"unit\":\"day\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));

        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttl").doesNotExist());
        assertThat(showCreateTable(TABLE)).doesNotContain("TTL");
    }

    @Test
    void unknownTtlUnitFailsBindingWith400() throws Exception {
        createIdentity();
        materialize("");

        // A unit outside the enum does not bind — 400 bad_request per the malformed-body convention.
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"ttl\":{\"column\":\"created_at\",\"interval\":90,\"unit\":\"fortnight\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttl").doesNotExist());
    }

    @Test
    void nullableTtlColumnIsRejected() throws Exception {
        createIdentity();
        materialize("");

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"add\":[{\"source_name\":\"seen_at\",\"name\":\"seen_at\",\"type\":\"timestamp\","
                                + "\"nullable\":true}],"
                                + "\"ttl\":{\"column\":\"seen_at\",\"interval\":1,\"unit\":\"day\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void dropOfTtlColumnIsRejected() throws Exception {
        createIdentity();
        materialize(",\"ttl\":{\"column\":\"created_at\",\"interval\":90,\"unit\":\"day\"}");

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"drop\":[\"created_at\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                // Asserted explicitly: the TTL is the reason, not some other column guard.
                .andExpect(jsonPath("$.message", containsString("referenced by the table TTL")));

        // Rejected even when the same request also removes the TTL — two sequential PATCHes are required.
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"drop\":[\"created_at\"],\"ttl\":{}}"))
                .andExpect(status().isUnprocessableContent());

        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns", hasSize(3)))
                .andExpect(jsonPath("$.ttl.column").value("created_at"));
    }

    @Test
    void renameOfTtlColumnKeepsCatalogAndTableInSync() throws Exception {
        createIdentity();
        materialize(",\"ttl\":{\"column\":\"created_at\",\"interval\":90,\"unit\":\"day\"}");

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"rename\":[{\"from\":\"created_at\",\"to\":\"created_ts\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttl.column").value("created_ts"));

        // ClickHouse rewrote the physical TTL expression; the catalog followed.
        assertThat(showCreateTable(TABLE)).contains("toDateTime(created_ts)").contains("toIntervalDay(90)");
        mockMvc.perform(get("/v1/tables/" + TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttl.column").value("created_ts"));
    }

    @Test
    void ttlPatchOnPendingTableIsConflict() throws Exception {
        createIdentity();

        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(JSON)
                        .content("{\"ttl\":{\"column\":\"created_at\",\"interval\":90,\"unit\":\"day\"}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"));
    }

    @Test
    void ttlAtCreateIsRejected() throws Exception {
        mockMvc.perform(post("/v1/tables").contentType(JSON)
                        .content("{\"name\":\"" + TABLE + "\",\"type\":\"source\","
                                + "\"ttl\":{\"column\":\"created_at\",\"interval\":90,\"unit\":\"day\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void systemTableTtlPatchIsRejected() throws Exception {
        mockMvc.perform(patch("/v1/tables/dial_usage_log/schema").contentType(JSON)
                        .content("{\"ttl\":{\"column\":\"request_time\",\"interval\":2,\"unit\":\"year\"}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("table_is_system"));
    }
}
