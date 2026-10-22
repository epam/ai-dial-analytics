package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.enrichment.MaterializeService;
import com.epam.aidial.analytics.service.enrichment.support.PartitionEcho;
import com.epam.aidial.analytics.service.table.relayout.RelayoutStateStore;
import com.epam.aidial.analytics.service.table.relayout.RelayoutStep;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.epam.aidial.analytics.integration.RunnerCalls.runnerPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Which pipelines may write an aligned enrichment, and how its writers behave around a relayout (capabilities
 * enrichment-runtime, "The scan carries an aligned target's partition value and the write-back returns it" and "The
 * pipeline write-back refuses during a target's relayout hold"; class1-enrichment, "Materialize refuses during the
 * target's relayout hold"), against a real ClickHouse.
 */
class AlignedEnrichmentWritersIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String SOURCE = "dial_usage_log";
    private static final String TARGET = "aw_ident";
    private static final String PIPELINE = "aw-pipeline";
    /** An unpartitioned source, so an event_id-keyed enrichment of it is not aligned. */
    private static final String PLAIN_SOURCE = "aw_events";
    private static final String PLAIN_TARGET = "aw_plain";

    @Autowired
    private RelayoutStateStore states;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MaterializeService materialize;

    @BeforeEach
    void setUp() throws Exception {
        truncate();
        IntegrationTables.enrichment(mockMvc, TARGET, SOURCE, "event_id",
                "[{\"source_name\":\"score\",\"name\":\"score\",\"type\":\"long\",\"nullable\":true},"
                        + "{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\",\"nullable\":true}]");
    }

    @AfterEach
    void tearDown() throws Exception {
        mockMvc.perform(delete("/v1/pipelines/" + PIPELINE));
        jdbc.update("DELETE FROM enrichment_relayout WHERE table_name IN (?, ?)", TARGET, PLAIN_TARGET);
        for (final String table : List.of(TARGET, PLAIN_TARGET, PLAIN_SOURCE)) {
            mockMvc.perform(delete("/v1/tables/" + table));
        }
    }

    private String seedSource() {
        final String eventId = UUID.randomUUID().toString();
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id, core_span_id,"
                + " _ingested_at) VALUES (toUUID('" + eventId + "'), 'p', 'd', '" + FIXTURE_DATE + " 10:00:00.000', 't1',"
                + " 's', '2020-01-01 00:00:01.000')");
        return eventId;
    }

    private static String llmBody(String target, String inputs) {
        return "{\"name\":\"" + PIPELINE + "\"," + Transforms.llm().output("score", "A score.").member()
                + ",\"kind\":\"enrich\",\"target\":\"" + target + "\"" + inputs
                + ",\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}";
    }

    private static String sqlBody(String inputs) {
        return "{\"name\":\"" + PIPELINE + "\",\"kind\":\"enrich\",\"target\":\"" + TARGET + "\"" + inputs
                + ",\"transform\":{\"type\":\"sql\",\"outputs\":{\"label\":\"lower(response_id)\"}},"
                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}";
    }

    private void register(String body) throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    /** Puts {@code table}'s relayout at {@code step}, with the writer hold as given. */
    private void relayout(String table, RelayoutStep step, boolean hold) {
        states.start(table, step, table + "__relayout", null);
        states.save(new RelayoutStateStore.State(table, step, table + "__relayout", null, 0L, 0L, hold, null));
    }

    // ------------------------------------------------------------------------------------------- registration

    @Test
    void llmPipelineReadingAnotherTableCannotTargetAnAlignedEnrichment() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(llmBody(TARGET, ",\"inputs\":[\"rate_analytics\"]")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message", containsString("must read '" + SOURCE + "'")));
    }

    /**
     * {@code rate_analytics} carries a {@code request_time} of its own, so computing the alignment against the read
     * source would have written another table's timestamps; the declaration is refused instead.
     */
    @Test
    void sqlPipelineReadingAnotherTableCannotTargetAnAlignedEnrichment() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(sqlBody(",\"inputs\":[\"rate_analytics\"]")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message", containsString("must read '" + SOURCE + "'")));
    }

    @Test
    void groupTriggerPipelineCannotTargetAnAlignedEnrichment() throws Exception {
        final String transform = Transforms.llm()
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"Members: {{members}}\"}]}")
                .proseOutputs("label")
                .member();
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + PIPELINE + "\"," + transform + ",\"kind\":\"enrich\",\"target\":\"" + TARGET
                                + "\",\"trigger\":{\"kind\":\"group\",\"group_by\":\"event_id\",\"ready_when\":{\"idle\":\"10m\"}},"
                                + "\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message", containsString("group-trigger")));
    }

    /**
     * A target being relaid out is judged as the aligned table it is about to become, and an llm pipeline onto it
     * waits for the relayout: its scans would carry no partition value until the catalog flips.
     */
    @Test
    void llmPipelineOntoTargetBeingRelaidOutIsRefusedUntilItFinishes() throws Exception {
        jdbc.update("UPDATE table_definition SET partition_column = NULL, partition_granularity = NULL WHERE name = ?", TARGET);
        relayout(TARGET, RelayoutStep.STARTED, false);

        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON).content(llmBody(TARGET, "")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("table_relayout_in_progress"));
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(sqlBody(",\"inputs\":[\"rate_analytics\"]")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"));
        // A Class-1 pipeline of the target's own source writes the alignment column itself once it is aligned.
        register(sqlBody(""));
    }

    // ------------------------------------------------------------------------------------------- write-back

    @Test
    void writeBackRowsMustCarryTheEchoedMemberRatherThanTheColumn() throws Exception {
        final String eventId = seedSource();
        register(llmBody(TARGET, ""));

        mockMvc.perform(runnerPost("/v1/pipelines/" + PIPELINE + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"" + eventId + "\",\"request_time\":\"" + FIXTURE_DATE
                                + "T10:00:00.000Z\",\"score\":7}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("write_column_not_allowed"));
        mockMvc.perform(runnerPost("/v1/pipelines/" + PIPELINE + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"" + eventId + "\",\"score\":7}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message", containsString(PartitionEcho.MEMBER)));
        mockMvc.perform(runnerPost("/v1/pipelines/" + PIPELINE + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"" + eventId + "\",\"" + PartitionEcho.MEMBER + "\":null,\"score\":7}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message", containsString(PartitionEcho.MEMBER)));
        assertThat(queryScalar("SELECT toString(count()) AS c FROM " + TARGET, "c")).isEqualTo("0");
    }

    @Test
    void unalignedTargetRefusesTheMember() throws Exception {
        IntegrationTables.source(mockMvc, PLAIN_SOURCE,
                "[{\"source_name\":\"event_id\",\"name\":\"event_id\",\"type\":\"uuid\"},"
                        + "{\"source_name\":\"ts\",\"name\":\"ts\",\"type\":\"timestamp\"}]",
                "[\"event_id\"]", "event_id", "ts");
        IntegrationTables.enrichment(mockMvc, PLAIN_TARGET, PLAIN_SOURCE, "event_id",
                "[{\"source_name\":\"score\",\"name\":\"score\",\"type\":\"long\",\"nullable\":true}]");
        register(llmBody(PLAIN_TARGET, ""));

        mockMvc.perform(runnerPost("/v1/pipelines/" + PIPELINE + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"" + UUID.randomUUID() + "\",\"" + PartitionEcho.MEMBER
                                + "\":\"" + FIXTURE_DATE + "T10:00:00.000Z\",\"score\":7}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("write_column_not_allowed"));
    }

    // ------------------------------------------------------------------------------------------- relayout hold

    @Test
    void writeBackDuringTheHoldIsRefusedWith503() throws Exception {
        final String eventId = seedSource();
        register(llmBody(TARGET, ""));
        relayout(TARGET, RelayoutStep.CAUGHT_UP, true);

        mockMvc.perform(runnerPost("/v1/pipelines/" + PIPELINE + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"" + eventId + "\",\"" + PartitionEcho.MEMBER + "\":\""
                                + FIXTURE_DATE + "T10:00:00.000Z\",\"score\":7}]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("table_relayout_in_progress"));
        assertThat(queryScalar("SELECT toString(count()) AS c FROM " + TARGET, "c")).isEqualTo("0");
    }

    @Test
    void materializeDuringTheHoldIsRefusedWith503AndRecordsNoPosition() throws Exception {
        seedSource();
        register(sqlBody(""));
        relayout(TARGET, RelayoutStep.CAUGHT_UP, true);

        mockMvc.perform(runnerPost("/v1/pipelines/" + PIPELINE + "/materialize")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":100}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("table_relayout_in_progress"));

        assertThat(queryScalar("SELECT toString(count()) AS c FROM " + TARGET, "c")).isEqualTo("0");
        assertThat(jdbc.queryForList("SELECT materialized_through_version FROM pipeline WHERE name = ?", Long.class, PIPELINE))
                .containsExactly((Long) null);
        assertThat(jdbc.queryForList("SELECT drained_at FROM pipeline WHERE name = ?", Object.class, PIPELINE))
                .containsExactly((Object) null);

        // Released: the next materialize writes from the source, its alignment column included.
        relayout(TARGET, RelayoutStep.DONE, false);
        final String page = mockMvc.perform(runnerPost("/v1/pipelines/" + PIPELINE + "/materialize")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":100}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(page, "$.rows_written")).isEqualTo(1);
    }

    /**
     * A materialize that resolved its target unaligned and reaches its INSERT after a relayout aligned it is refused,
     * not written: its rows would carry no alignment value and land in the epoch partition, past the relayout's last
     * repair of it. The relayout is simulated by aligning the catalog at the write's own hold check.
     */
    @Test
    void materializeThatSawTheTargetUnalignedIsRefusedOnceRelayoutAlignsIt() throws Exception {
        seedSource();
        final Map<String, Object> layout = jdbc.queryForMap(
                "SELECT partition_column, partition_granularity FROM table_definition WHERE name = ?", TARGET);
        jdbc.update("UPDATE table_definition SET partition_column = NULL, partition_granularity = NULL WHERE name = ?", TARGET);
        register(sqlBody(""));
        final Object service = AopTestUtils.getTargetObject(materialize);
        final Object held = ReflectionTestUtils.getField(service, "relayouts");
        ReflectionTestUtils.setField(service, "relayouts", new RelayoutStateStore(jdbc) {
            private int checks;

            @Override
            public void requireNotHeld(String table) {
                super.requireNotHeld(table);
                if (TARGET.equals(table) && ++checks == 2) {
                    jdbc.update("UPDATE table_definition SET partition_column = ?, partition_granularity = ? WHERE name = ?",
                            layout.get("partition_column"), layout.get("partition_granularity"), TARGET);
                }
            }
        });
        try {
            mockMvc.perform(runnerPost("/v1/pipelines/" + PIPELINE + "/materialize")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":100}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value("table_relayout_in_progress"));
        } finally {
            ReflectionTestUtils.setField(service, "relayouts", held);
        }
        assertThat(queryScalar("SELECT toString(count()) AS c FROM " + TARGET, "c")).isEqualTo("0");

        // Retried: the materialize resolves the target aligned and writes the source row's partition value.
        mockMvc.perform(runnerPost("/v1/pipelines/" + PIPELINE + "/materialize")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":100}"))
                .andExpect(status().isOk());
        assertThat(queryScalar("SELECT toString(count()) AS c FROM " + TARGET + " WHERE toDate(request_time) = '"
                + FIXTURE_DATE + "'", "c")).isEqualTo("1");
    }
}
