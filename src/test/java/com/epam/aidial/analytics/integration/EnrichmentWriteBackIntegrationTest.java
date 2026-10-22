package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.enrichment.support.PartitionEcho;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static com.epam.aidial.analytics.integration.RunnerCalls.runnerPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of pipeline-scoped versioned write-back ({@code POST /v1/pipelines/{name}/rows}) against
 * real PostgreSQL + ClickHouse: a well-formed write lands and resolves via the grain join; two writes
 * for one grain collapse to the latest under {@code final=1} (the {@code _updated_at} DEFAULT); a
 * runner-style write whose provenance columns are absent from the target is written with those
 * columns silently skipped (and persisted when present); and an undeclared column or a caller-set
 * system column is rejected (422). Requires Docker.
 *
 * <p>Also pins the guard that makes a write-back <b>able to write a label</b>: a row naming only the grain
 * key and provenance columns is refused 422 {@code write_output_column_required}, because against the
 * target's {@code ReplacingMergeTree} it neither fails nor no-ops — it replaces a label already paid for
 * with nothing. That refusal is version-independent, so these cases send a matching contract header like
 * every other call here: it is a property of the write, not of the caller's build.
 *
 * <p>Also pins that this endpoint agrees with {@code POST /v1/tables/{name}/rows} about timestamps: it
 * shares the same write path, so a {@code timestamp}-typed provenance column takes ISO-8601 and rejects
 * the old space-separated form identically. A provenance column declared {@code string} keeps storing a
 * timestamp-shaped value verbatim — normalization is scoped to columns actually declared as timestamps.
 */
class EnrichmentWriteBackIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String SOURCE = "dial_usage_log";
    private static final String ENRICHMENT = "wb_tox";
    private static final String EVENT_ID = "11111111-1111-1111-1111-111111111111";

    private String pipelineName;
    /** The source row's request_time, ISO-8601: what the scan hands the runner and the runner echoes back. */
    private String partitionEcho;

    @BeforeEach
    void setUp() throws Exception {
        truncate();
        // One source row whose event_id the enrichment grain-joins against.
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id, _ingested_at, total_tokens) VALUES ("
                + "toUUID('" + EVENT_ID + "'),'p','d', now64(3),'t','s1','2020-01-01 00:00:01.000', 100)");
        partitionEcho = queryScalar("SELECT concat(replace(toString(request_time), ' ', 'T'), 'Z') AS v FROM "
                + "dial_usage_log", "v");

        IntegrationTables.enrichment(mockMvc, ENRICHMENT, SOURCE, "event_id",
                "[{\"source_name\":\"tox_score\",\"name\":\"tox_score\",\"type\":\"long\"}]");
        // The output is keyed by the target column it writes, which is what removes the separate
        // output_bindings declaration.
        String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"wb-pipeline\","
                                + Transforms.llm().output("tox_score", "How toxic the text is, 0-100.")
                                        .member() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        pipelineName = JsonPath.read(content, "$.name");
    }

    /** The echoed partition member, as the runner adds it to every write row. */
    private String echo() {
        return "\"" + PartitionEcho.MEMBER + "\":\"" + partitionEcho + "\",";
    }

    @AfterEach
    void tearDown() throws Exception {
        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT));
    }

    private void queryToxScore(Object expected) throws Exception {
        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"dial_usage_log\",\"mode\":\"row\","
                                + "\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"event_id\"}},"
                                + "{\"expr\":{\"type\":\"field\",\"name\":\"wb_tox.tox_score\"}}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0]['wb_tox.tox_score']").value(expected));
    }

    @Test
    void writeLandsAndResolvesViaGrainJoin() throws Exception {
        mockMvc.perform(runnerPost("/v1/pipelines/" + pipelineName + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"" + EVENT_ID + "\"," + echo() + "\"tox_score\":7}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(1));
        queryToxScore(7);
    }

    @Test
    void twoWritesForOneGrainCollapseToLatest() throws Exception {
        // Explicit _updated_at values make the winner deterministic under final=1 (the later version wins).
        execute("INSERT INTO " + ENRICHMENT + " (event_id, request_time, tox_score, _updated_at) SELECT event_id, "
                + "request_time, 1, '2020-01-01 00:00:01.000' FROM dial_usage_log WHERE event_id = toUUID('" + EVENT_ID + "')");
        execute("INSERT INTO " + ENRICHMENT + " (event_id, request_time, tox_score, _updated_at) SELECT event_id, "
                + "request_time, 2, '2020-01-01 00:00:02.000' FROM dial_usage_log WHERE event_id = toUUID('" + EVENT_ID + "')");
        queryToxScore(2);
    }

    @Test
    void writeWithProvenanceToTargetLackingItSkipsProvenance() throws Exception {
        // The target has to be made to lack them: defining an enrichment's schema now INJECTS all five
        // provenance columns, so an enrichment created through the API always has them. Dropping one is the
        // only way to reach the case this test exists for — and it is a case that still matters, because
        // provenance is optional on a target and one live target carries none at all.
        mockMvc.perform(patch("/v1/tables/" + ENRICHMENT + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"drop\":[\"model\",\"group_version\",\"truncated\"]}"))
                .andExpect(status().isOk());

        // A runner-style write naming a provenance column the target no longer carries must still succeed:
        // the absent columns are silently skipped rather than failing the write.
        mockMvc.perform(runnerPost("/v1/pipelines/" + pipelineName + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"" + EVENT_ID + "\"," + echo() + "\"tox_score\":5,"
                                + "\"pipeline_generation\":1,\"model\":\"gpt-4o\","
                                + "\"enriched_at\":\"2026-07-21T13:15:16.696466Z\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(1));
        queryToxScore(5);
    }

    @Test
    void writeWithProvenanceToTargetHavingItPersistsProvenance() throws Exception {
        // No PATCH: defining the enrichment's schema injected all five provenance columns, so the target
        // already has them. Adding them here would now be a duplicate-column 422 — which is the point of
        // injecting, since four authors previously gave four different answers for the same columns.
        mockMvc.perform(runnerPost("/v1/pipelines/" + pipelineName + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"" + EVENT_ID + "\"," + echo() + "\"tox_score\":9,"
                                + "\"pipeline_generation\":1,\"model\":\"gpt-4o\","
                                + "\"enriched_at\":\"2026-07-21T13:15:16.696466Z\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(1));
        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"dial_usage_log\",\"mode\":\"row\","
                                + "\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"event_id\"}},"
                                + "{\"expr\":{\"type\":\"field\",\"name\":\"wb_tox.model\"}}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0]['wb_tox.model']").value("gpt-4o"));
    }

    /**
     * The pipeline-scoped write reaches ClickHouse through the same {@code TableDataService.write} as
     * {@code POST /v1/tables/{name}/rows}, so a {@code timestamp}-typed provenance column must accept the
     * same ISO-8601 form there — the two write surfaces are not allowed to disagree about timestamps.
     */
    @Test
    void timestampProvenanceColumnAcceptsIsoOnThePipelineScopedWrite() throws Exception {
        // enriched_at is injected as `timestamp`, so there is nothing to add: the canonical type IS the one
        // this pair is about. That type is load-bearing rather than cosmetic — the write path switches on
        // the DECLARED type, stamping a bare version number into an integral column and `name:version`
        // into a string one, so injecting a different type would change what every value looks like.
        mockMvc.perform(runnerPost("/v1/pipelines/" + pipelineName + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"" + EVENT_ID + "\"," + echo() + "\"tox_score\":4,"
                                + "\"enriched_at\":\"2026-07-21T13:15:16.696466Z\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(1));
        queryToxScore(4);
    }

    /** ...and must reject the old space-separated form there with the same diagnosable 422. */
    @Test
    void timestampProvenanceColumnRejectsSpaceSeparatedFormOnThePipelineScopedWrite() throws Exception {
        mockMvc.perform(runnerPost("/v1/pipelines/" + pipelineName + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"" + EVENT_ID + "\"," + echo() + "\"tox_score\":4,"
                                + "\"enriched_at\":\"2026-07-21 13:15:16.696\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").value(containsString("space separator")))
                .andExpect(jsonPath("$.message").value(containsString("enriched_at")));
    }

    /**
     * The pipeline-scoped write reaches ClickHouse through {@code TableDataService.write}, so it shares
     * that method's non-nullable check: {@code EnrichWriteValidator} only bounds which columns a row may
     * name, it never requires the grain key to be one of them, so a row omitting it must still be caught
     * downstream.
     */
    @Test
    void writeMissingGrainKeyIsRejectedAsNonNullable() throws Exception {
        mockMvc.perform(runnerPost("/v1/pipelines/" + pipelineName + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{" + echo() + "\"tox_score\":7}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message", containsString("event_id")));
    }

    /**
     * The guard this pair of codes exists for. Such a row names only permitted columns, so the subset rule
     * passes it — and the target is a {@code ReplacingMergeTree} read at its grain, so accepting it would
     * overwrite an existing label with nothing after the model call that produced it had been charged.
     */
    @Test
    void grainAndProvenanceOnlyIsRefused() throws Exception {
        execute("INSERT INTO " + ENRICHMENT + " (event_id, request_time, tox_score, _updated_at) SELECT event_id, "
                + "request_time, 42, '2020-01-01 00:00:01.000' FROM dial_usage_log WHERE event_id = toUUID('" + EVENT_ID + "')");

        mockMvc.perform(runnerPost("/v1/pipelines/" + pipelineName + "/rows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"" + EVENT_ID + "\"," + echo() + "\"pipeline_generation\":1,"
                                + "\"enriched_at\":\"2026-07-21T13:15:16.696466Z\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("write_output_column_required"))
                .andExpect(jsonPath("$.message", containsString(pipelineName)))
                .andExpect(jsonPath("$.message", containsString("tox_score")));

        // The label already paid for is still there: the refusal happened before anything was written.
        queryToxScore(42);
    }

    /**
     * Refused at validation, which runs over the whole batch before any of it is sent — so one such row
     * costs the batch, not just itself. That is the right trade: the alternative is a partial write whose
     * successful half a retry would then duplicate.
     */
    @Test
    void oneUnwritableRowRefusesTheWholeBatch() throws Exception {
        mockMvc.perform(runnerPost("/v1/pipelines/" + pipelineName + "/rows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"" + EVENT_ID + "\"," + echo() + "\"tox_score\":7},"
                                + "{\"event_id\":\"" + EVENT_ID + "\"," + echo() + "\"pipeline_generation\":1}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("write_output_column_required"));

        assertThat(queryScalar("SELECT count() AS n FROM " + ENRICHMENT, "n")).isEqualTo("0");
    }

    /**
     * Presence, not value: a declared output column carrying {@code null} is a derived label of
     * {@code null}, which one live mapping produces deliberately by mapping a sentinel to it.
     */
    @Test
    void nullValuedOutputColumnIsAccepted() throws Exception {
        mockMvc.perform(runnerPost("/v1/pipelines/" + pipelineName + "/rows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"" + EVENT_ID + "\"," + echo() + "\"tox_score\":null}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(1));

        assertThat(queryScalar("SELECT if(isNull(tox_score), 'null', 'set') AS v FROM " + ENRICHMENT, "v"))
                .isEqualTo("null");
    }

    /**
     * The guard cannot lock a registered pipeline out, because a pipeline whose permitted-output set would
     * be empty never registers: the declaration gate requires every transform output to resolve to a column
     * on the target. Asserted rather than re-derived, since the write-back guard's safety rests on it.
     */
    @Test
    void pipelineWhoseOutputResolvesToNoTargetColumnDoesNotRegister() throws Exception {
        // A target takes one pipeline, so the fixture's has to go first — this is about the declaration
        // gate, not about the target-availability rule.
        mockMvc.perform(delete("/v1/pipelines/" + pipelineName)).andExpect(status().isNoContent());

        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"wb-unbindable\","
                                + Transforms.llm()
                                        .output("no_such_column", "Prose about a column the target lacks.")
                                        .member() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"));
    }

    @Test
    void undeclaredColumnIsRejected() throws Exception {
        mockMvc.perform(runnerPost("/v1/pipelines/" + pipelineName + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"" + EVENT_ID + "\"," + echo() + "\"sentiment\":\"pos\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("write_column_not_allowed"));
    }

    @Test
    void callerSetSystemColumnIsRejected() throws Exception {
        mockMvc.perform(runnerPost("/v1/pipelines/" + pipelineName + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"" + EVENT_ID + "\"," + echo() + "\"_updated_at\":123}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("write_column_not_allowed"));
    }
}
