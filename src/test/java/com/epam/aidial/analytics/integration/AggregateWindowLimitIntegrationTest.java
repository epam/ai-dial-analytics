package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The aggregate refresh's <b>bounded-window</b> path, which the rest of the suite never reaches: with the
 * shipped {@code aggregate.window-limit} of 100,000, no fixture fills a window, so {@code has_more}, the
 * multi-window drain, and the split-bucket recompute have no regression guard at all. This class lowers
 * the limit to 3 so a handful of rows exercises them.
 *
 * <p>The case worth pinning is the <b>split bucket</b>: one group key whose rows straddle the window
 * boundary must end at its <em>full</em> total, not at the last window's slice. That is the
 * recompute-in-full contract under the one condition that actually stresses it — the window bounds which
 * keys are dirty, never which rows are summed for them — and its failure mode is silent, since a partial
 * total is a plausible number that no error accompanies.
 *
 * <p>Requires a running Docker daemon.
 */
@TestPropertySource(properties = {AbstractClickHouseIntegrationTest.SCAN_SAFETY_LAG_OFF,
        AbstractClickHouseIntegrationTest.GROUP_FETCH_MAX_BYTES_4KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_WINDOW_LIMIT_3,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_MEMORY_1KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_SPILL_OFF})
class AggregateWindowLimitIntegrationTest extends AbstractClickHouseIntegrationTest {

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
        mockMvc.perform(patch(PIPELINES + "/" + PIPELINE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());
    }

    @AfterEach
    void dropAll() throws Exception {
        mockMvc.perform(delete(PIPELINES + "/" + PIPELINE));
        mockMvc.perform(delete("/v1/tables/" + ROLLUP));
    }

    /** Seeds one input row; {@code seq} keeps the identity and the cursor order unique per row. */
    private void seedEvent(String deployment, String day, long tokens, int seq) {
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id, total_tokens, _ingested_at) VALUES"
                + " (generateUUIDv4(), 'p', '" + deployment + "', '" + day + " 10:0" + seq + ":00.000',"
                + " 't" + seq + "', 's" + seq + "', " + tokens + ", '" + day + " 10:0" + seq + ":01.000')");
    }

    /** One refresh; returns {@code (rows_written, has_more)}. */
    private Refresh refresh() throws Exception {
        final String body = mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/refresh"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Refresh(
                Long.parseLong(body.replaceAll(".*\"rows_written\":(\\d+).*", "$1")),
                body.contains("\"has_more\":true"));
    }

    private record Refresh(long rowsWritten, boolean hasMore) {}

    private ResultActions queryRollup() throws Exception {
        return mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"entity":"usage_daily","mode":"row",
                         "select":[{"expr":{"type":"field","name":"deployment"}},
                                   {"expr":{"type":"field","name":"call_count"}},
                                   {"expr":{"type":"field","name":"tokens"}}],
                         "sort":[{"field":"deployment","dir":"asc"}],
                         "page":{"type":"offset","offset":0,"limit":25,"include_total":true}}"""));
    }

    @Test
    void filledWindowReportsMoreWorkRemainsAndTheNextRunContinues() throws Exception {
        // Five rows against a limit of three: the first probe fills its window and must say so, or the
        // scheduler stops with input still unread and the rollup silently stalls one window short.
        for (int i = 1; i <= 5; i++) {
            seedEvent("gpt-4", "2026-01-01", 10, i);
        }

        final Refresh first = refresh();
        assertThat(first.hasMore()).as("a window that filled its limit reports more work remains").isTrue();

        final Refresh second = refresh();
        assertThat(second.hasMore()).as("the remaining two rows do not fill the limit").isFalse();

        // Draining reaches the same rollup an unbounded run would: five calls, 50 tokens.
        queryRollup()
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].call_count").value(5))
                .andExpect(jsonPath("$.rows[0].tokens").value(50));
    }

    @Test
    void bucketSplitAcrossWindowsEndsAtItsFullTotal() throws Exception {
        // One group key, five rows, window limit three — so the key is dirtied by a window holding only
        // part of it. The recompute must still sum the whole bucket: the window bounds which keys are
        // dirty, never which rows are summed for them. A partial total would overwrite the key with the
        // window's slice and read as a real, smaller number.
        for (int i = 1; i <= 5; i++) {
            seedEvent("gpt-4", "2026-01-01", i, i);
        }

        final Refresh first = refresh();
        assertThat(first.hasMore()).isTrue();

        // Already the full total after the *first* window, because the recompute is not window-restricted.
        queryRollup()
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].call_count").value(5))
                .andExpect(jsonPath("$.rows[0].tokens").value(15));

        refresh();
        queryRollup()
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].call_count").value(5))
                .andExpect(jsonPath("$.rows[0].tokens").value(15));
    }

    @Test
    void drainingToTheEndLeavesAnEmptyWindowNoOp() throws Exception {
        // The empty-window case at the end of a drain — the state a scheduled pipeline sits in almost all
        // the time, and the one a probe rewrite is most likely to break by reporting "one row, count 0"
        // as a window rather than as nothing.
        for (int i = 1; i <= 4; i++) {
            seedEvent("gpt-4", "2026-01-01", 10, i);
        }
        while (refresh().hasMore()) {
            // drain
        }

        final Refresh empty = refresh();
        assertThat(empty.rowsWritten()).as("a probe matching nothing writes nothing").isZero();
        assertThat(empty.hasMore()).isFalse();
        queryRollup().andExpect(jsonPath("$.rows[0].tokens").value(40));
    }
}
