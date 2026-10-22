package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The preview resource budget, with the memory ceiling set low enough that any real aggregation trips it.
 *
 * <p>What this pins is the <b>shape of the refusal</b>, which is the part that has to be right: a budget
 * refusal is deterministic, so it is 422 with its own stable code — never the 503 an outage produces,
 * which a client would correctly respond to by retrying, and which would therefore loop forever. It also
 * pins that the budget reaches the preview sample <em>only</em>: a refresh whose recompute costs more than
 * the preview budget allows must still run, or a capacity ceiling meant for a dry run would silently
 * become one for the write path.
 *
 * <p>Requires a running Docker daemon.
 */
@TestPropertySource(properties = {AbstractClickHouseIntegrationTest.SCAN_SAFETY_LAG_OFF,
        AbstractClickHouseIntegrationTest.GROUP_FETCH_MAX_BYTES_4KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_WINDOW_LIMIT_3,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_MEMORY_1KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_SPILL_OFF})
class AggregatePreviewBudgetIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String PIPELINES = "/v1/pipelines";
    private static final String ROLLUP = "usage_daily";
    private static final String PIPELINE = "usage_daily_rollup";

    private static final String TARGET_COLUMNS = """
            [{"source_name":"deployment","name":"deployment","type":"string"},
             {"source_name":"day","name":"day","type":"timestamp"},
             {"source_name":"call_count","name":"call_count","type":"long"},
             {"source_name":"tokens","name":"tokens","type":"long","nullable":true}]""";

    private static final String DECLARATION = """
            "target":"usage_daily",
            "inputs":["dial_usage_log"],
            "group_by":[{"column":"deployment"},
                        {"trunc":{"column":"request_time","unit":"day"},"as":"day"}],
            "measures":[{"name":"call_count","fn":"count"},
                        {"name":"tokens","fn":"sum","column":"total_tokens"}],
            "trigger":{"kind":"schedule","cron":"0 0 * * * *"}""";

    @BeforeEach
    void cleanSlate() throws Exception {
        truncate();
        mockMvc.perform(delete(PIPELINES + "/" + PIPELINE));
        mockMvc.perform(delete("/v1/tables/" + ROLLUP));
        IntegrationTables.upsertSource(mockMvc, ROLLUP, TARGET_COLUMNS, "[\"deployment\",\"day\"]");
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + PIPELINE + "\",\"kind\":\"aggregate\"," + DECLARATION + "}"))
                .andExpect(status().isCreated());
    }

    @AfterEach
    void dropAll() throws Exception {
        mockMvc.perform(delete(PIPELINES + "/" + PIPELINE));
        mockMvc.perform(delete("/v1/tables/" + ROLLUP));
    }

    private void seedEvent(int seq) {
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id, total_tokens, _ingested_at) VALUES"
                + " (generateUUIDv4(), 'p', 'gpt-4', '2026-01-01 10:0" + seq + ":00.000',"
                + " 't" + seq + "', 's" + seq + "', 10, '2026-01-01 10:0" + seq + ":01.000')");
    }

    @Test
    void previewExceedingItsBudgetIsRefusedWithItsOwnCode() throws Exception {
        seedEvent(1);
        seedEvent(2);

        mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/preview"))
                .andExpect(status().isUnprocessableContent())
                // Its own code, distinct from a spec violation (pipeline_validation_failed) and from an
                // outage (503 clickhouse_unavailable) — the whole point is that an operator can tell "this
                // declaration is too expensive to preview" from "ClickHouse is down".
                .andExpect(jsonPath("$.error").value("pipeline_preview_budget_exceeded"))
                .andExpect(jsonPath("$.status").value(422))
                // Names the budget that was exceeded and the remedy, echoing no driver text.
                .andExpect(jsonPath("$.message").value(containsString("memory ceiling")))
                .andExpect(jsonPath("$.message").value(containsString("coarsen the group keys")))
                // The window count holds every group in memory, so a refusal points at the group key first.
                .andExpect(jsonPath("$.message").value(containsString("higher cardinality than meant")))
                // No partial or reduced-input sample: the budget bounds the cost, never the question.
                .andExpect(jsonPath("$.rows").doesNotExist());
    }

    @Test
    void refreshIsNotSubjectToThePreviewBudget() throws Exception {
        // The budget must not leak onto the write path. Refresh is bounded by its window, and a capacity
        // ceiling there would make a legitimate large window fail as though it were malformed.
        seedEvent(1);
        seedEvent(2);
        mockMvc.perform(patch(PIPELINES + "/" + PIPELINE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows_written").value(1));

        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"usage_daily","mode":"row",
                                 "select":[{"expr":{"type":"field","name":"tokens"}}],
                                 "page":{"type":"offset","offset":0,"limit":25}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].tokens").value(20));
    }
}
