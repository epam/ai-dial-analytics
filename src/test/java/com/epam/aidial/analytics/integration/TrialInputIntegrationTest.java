package com.epam.aidial.analytics.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static com.epam.aidial.analytics.integration.RunnerCalls.runnerPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /v1/pipelines/{name}/trial-input} against real PostgreSQL + ClickHouse: the read the Enrichment Runner's
 * dry run is built on. What is checkable here is that it answers the preview unchanged beside the uncapped rows the
 * runner would receive — the preview's own newest rows for a row-grain pipeline, the runner's own group fetch for a
 * group one — that it refuses what the preview refuses plus a pipeline that calls no model, and that it writes
 * nothing.
 *
 * <p>Shares {@link EnrichPreviewIntegrationTest}'s property set, and so its Spring context. Requires Docker.
 */
@TestPropertySource(properties = {AbstractClickHouseIntegrationTest.SCAN_SAFETY_LAG_OFF,
        AbstractClickHouseIntegrationTest.GROUP_FETCH_MAX_BYTES_4KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_WINDOW_LIMIT_3,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_MEMORY_1KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_SPILL_OFF})
class TrialInputIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String SOURCE = "dial_usage_log";
    private static final String LLM_TARGET = "ti_llm_labels";
    private static final String GROUP_TARGET = "ti_group_labels";
    private static final String SQL_TARGET = "ti_sql_labels";
    private static final String LLM = "ti-llm-pipeline";
    private static final String GROUP = "ti-group-pipeline";
    private static final String SQL = "ti-sql-pipeline";

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        truncate();
        jdbc.update("DELETE FROM instance_setting");
        IntegrationTables.enrichment(mockMvc, LLM_TARGET, SOURCE, "event_id",
                "[{\"source_name\":\"title\",\"name\":\"title\",\"type\":\"string\",\"nullable\":true}]");
        IntegrationTables.enrichment(mockMvc, GROUP_TARGET, SOURCE, "chat_id",
                "[{\"source_name\":\"topic\",\"name\":\"topic\",\"type\":\"string\",\"nullable\":true}]");
        IntegrationTables.enrichment(mockMvc, SQL_TARGET, SOURCE, "event_id",
                "[{\"source_name\":\"deployment_lower\",\"name\":\"deployment_lower\",\"type\":\"string\",\"nullable\":true}]");
    }

    @AfterEach
    void tearDown() throws Exception {
        // Each table delete cascades the pipeline targeting it.
        mockMvc.perform(delete("/v1/tables/" + LLM_TARGET));
        mockMvc.perform(delete("/v1/tables/" + GROUP_TARGET));
        mockMvc.perform(delete("/v1/tables/" + SQL_TARGET));
    }

    /** One source row in chat {@code chatId}, ingested at {@code ingestedAt}, the version every sample orders by. */
    private void seedRow(String eventId, String chatId, String deployment, String ingestedAt) {
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id, chat_id, event_kind, _ingested_at) VALUES (toUUID('" + eventId
                + "'),'p','" + deployment + "', now64(3),'t','" + eventId.substring(0, 8) + "','" + chatId
                + "','chat','" + ingestedAt + "')");
    }

    private static String eventId(int n) {
        return String.format("00000000-0000-4000-8000-%012d", n);
    }

    private void createLlmPipeline(boolean enabled) throws Exception {
        final String transform = Transforms.llm()
                .model("test-model")
                .preset(null)
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"Deployment: {{deployment}}\"}]}")
                .input("deployment", "deployment")
                .output("title", "A short title for the call.")
                .member();
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + LLM + "\"," + transform + ",\"kind\":\"enrich\",\"target\":\"" + LLM_TARGET
                                + "\",\"trigger\":{\"kind\":\"schedule\"},\"enabled\":" + enabled + "}"))
                .andExpect(status().isCreated());
    }

    private void createGroupPipeline() throws Exception {
        final String transform = Transforms.llm()
                .model("test-model")
                .preset(null)
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"Members: {{members}}\"}]}")
                .input("deployment", "deployment")
                .output("topic", "What the conversation is about.")
                .member();
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + GROUP + "\"," + transform + ",\"kind\":\"enrich\",\"target\":\"" + GROUP_TARGET
                                + "\",\"trigger\":{\"kind\":\"group\",\"group_by\":\"chat_id\",\"ready_when\":{\"idle\":\"10m\"}},"
                                + "\"enabled\":false}"))
                .andExpect(status().isCreated());
    }

    private String trialInput(String pipeline, String body) throws Exception {
        return mockMvc.perform(runnerPost("/v1/pipelines/" + pipeline + "/trial-input")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void rowGrainTrialInputReturnsTheNewestSampledRowUncapped() throws Exception {
        createLlmPipeline(true);
        final String wide = "d".repeat(3000);
        seedRow(eventId(1), "c1", "older", "2026-01-01 00:00:00.000");
        seedRow(eventId(2), "c1", wide, "2026-01-02 00:00:00.000");

        final String body = trialInput(LLM, "{}");

        final List<Map<String, Object>> inputRows = JsonPath.read(body, "$.input_rows");
        assertThat(inputRows).hasSize(1);
        assertThat(inputRows.getFirst()).containsEntry("event_id", eventId(2)).containsEntry("deployment", wide);
        // The preview beside it is the capped one, describing the same newest row by its grain key.
        assertThat((String) JsonPath.read(body, "$.preview.rows[0].event_id")).isEqualTo(eventId(2));
        assertThat((String) JsonPath.read(body, "$.preview.rows[0].deployment")).hasSizeLessThan(wide.length());
        assertThat((List<String>) JsonPath.read(body, "$.preview.notes[*].code")).contains("values_truncated");
        // The compiled declaration the runner evaluates, with the schema the model is held to.
        assertThat((String) JsonPath.read(body, "$.pipeline.name")).isEqualTo(LLM);
        assertThat((Object) JsonPath.read(body, "$.pipeline.response_schema.properties.title")).isNotNull();
        assertThat((String) JsonPath.read(body, "$.pipeline.grain_key")).isEqualTo("event_id");
        assertThat(body).doesNotContain("\"truncated\"");
    }

    @Test
    void rowGrainLimitReturnsThatManyNewestRowsInPreviewOrder() throws Exception {
        createLlmPipeline(false);
        for (int i = 1; i <= 4; i++) {
            seedRow(eventId(i), "c1", "dep-" + i, "2026-01-0" + i + " 00:00:00.000");
        }

        final String body = trialInput(LLM, "{\"limit\":3}");

        assertThat((List<String>) JsonPath.read(body, "$.input_rows[*].event_id"))
                .containsExactly(eventId(4), eventId(3), eventId(2));
        assertThat((List<String>) JsonPath.read(body, "$.preview.rows[*].event_id"))
                .startsWith(eventId(4), eventId(3), eventId(2));
    }

    @Test
    void trialInputPreviewIsThePreviewForTheSameBody() throws Exception {
        createLlmPipeline(true);
        seedRow(eventId(1), "c1", "alpha", "2026-01-01 00:00:00.000");
        seedRow(eventId(2), "c1", "beta", "2026-01-02 00:00:00.000");

        final String preview = mockMvc.perform(post("/v1/pipelines/" + LLM + "/preview")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        final Map<String, Object> trialPreview = JsonPath.read(trialInput(LLM, "{}"), "$.preview");

        assertThat(trialPreview).isEqualTo(JsonPath.read(preview, "$"));
    }

    @Test
    void limitOutsideItsRangeOrOnGroupPipelineIsRefused() throws Exception {
        createLlmPipeline(true);
        createGroupPipeline();

        for (String body : List.of("{\"limit\":0}", "{\"limit\":6}")) {
            mockMvc.perform(runnerPost("/v1/pipelines/" + LLM + "/trial-input")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                    .andExpect(jsonPath("$.message").value(containsString("between 1 and 5")));
        }
        mockMvc.perform(runnerPost("/v1/pipelines/" + GROUP + "/trial-input")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("whole group")));
    }

    @Test
    void unknownBodyMemberIsRefused() throws Exception {
        createLlmPipeline(true);

        mockMvc.perform(runnerPost("/v1/pipelines/" + LLM + "/trial-input")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cursor\":\"abc\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void groupTrialInputReturnsTheWholeGroupAsTheRunnersFetchDoes() throws Exception {
        createGroupPipeline();
        for (int i = 1; i <= 7; i++) {
            seedRow(eventId(i), "chat-42", "dep-" + i, "2026-01-01 00:00:0" + i + ".000");
        }

        final String body = trialInput(GROUP, "{}");

        assertThat((String) JsonPath.read(body, "$.preview.group_key")).isEqualTo("chat-42");
        assertThat((List<?>) JsonPath.read(body, "$.preview.rows")).hasSize(5);
        assertThat((Boolean) JsonPath.read(body, "$.truncated")).isFalse();
        final List<Map<String, Object>> inputRows = JsonPath.read(body, "$.input_rows");
        final String fetched = mockMvc.perform(runnerPost("/v1/pipelines/" + GROUP + "/group-rows")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"group_key\":\"chat-42\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(inputRows).hasSize(7).isEqualTo(JsonPath.read(fetched, "$.rows"));
    }

    @Test
    void namedGroupIsUsedAsNamed() throws Exception {
        createGroupPipeline();
        seedRow(eventId(1), "chat-1", "dep-a", "2026-01-01 00:00:00.000");
        seedRow(eventId(2), "chat-2", "dep-b", "2026-01-02 00:00:00.000");

        final String body = trialInput(GROUP, "{\"group_key\":\"chat-1\"}");

        assertThat((String) JsonPath.read(body, "$.preview.group_key")).isEqualTo("chat-1");
        assertThat((List<String>) JsonPath.read(body, "$.input_rows[*].deployment")).containsExactly("dep-a");
    }

    @Test
    void emptyGroupSampleIsAnsweredWithItsNote() throws Exception {
        createGroupPipeline();

        mockMvc.perform(runnerPost("/v1/pipelines/" + GROUP + "/trial-input")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.input_rows", hasSize(0)))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.preview.notes[*].code", contains("no_group_found")));
    }

    @Test
    void pipelinesThatCallNoModelAreRefused() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + SQL + "\"," + Transforms.sql().output("deployment_lower", "lower(deployment)").member()
                                + ",\"kind\":\"enrich\",\"target\":\"" + SQL_TARGET + "\",\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":false}"))
                .andExpect(status().isCreated());

        mockMvc.perform(runnerPost("/v1/pipelines/" + SQL + "/trial-input")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("`sql` transform")));
    }

    @Test
    void unknownPipelineIsNotFound() throws Exception {
        mockMvc.perform(runnerPost("/v1/pipelines/ti-missing/trial-input")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("unknown_pipeline"));
    }

    @Test
    void routeTheAllowlistDoesNotNameIsRefusedAsArmingRefusesIt() throws Exception {
        final String transform = Transforms.llm().model("insights-app").preset("dial_app_route").route("insights")
                .requestTemplate("{\"deployment\":\"{{deployment}}\"}")
                .input("deployment", "deployment")
                .output("title", "A short title for the call.")
                .member();
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + LLM + "\"," + transform + ",\"kind\":\"enrich\",\"target\":\"" + LLM_TARGET
                                + "\",\"trigger\":{\"kind\":\"schedule\"},\"enabled\":false}"))
                .andExpect(status().isCreated());
        seedRow(eventId(1), "c1", "alpha", "2026-01-01 00:00:00.000");

        mockMvc.perform(runnerPost("/v1/pipelines/" + LLM + "/trial-input")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("app-routes")));
    }

    @Test
    void trialInputWithoutTheContractHeaderIsRefusedBeforeAnyLookup() throws Exception {
        mockMvc.perform(post("/v1/pipelines/ti-missing/trial-input").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("runner_contract_required"));
        mockMvc.perform(post("/v1/pipelines/ti-missing/trial-input").header("Adas-Contract-Version", "6")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("runner_contract_mismatch"));
    }

    @Test
    void trialInputWritesNothing() throws Exception {
        createLlmPipeline(true);
        seedRow(eventId(1), "c1", "alpha", "2026-01-01 00:00:00.000");
        final String before = mockMvc.perform(get("/v1/pipelines/" + LLM)).andReturn().getResponse().getContentAsString();

        trialInput(LLM, "{}");

        assertThat(queryScalar("SELECT count() AS c FROM " + LLM_TARGET, "c")).isEqualTo("0");
        final String after = mockMvc.perform(get("/v1/pipelines/" + LLM)).andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(after, "$.generation")).isEqualTo(JsonPath.read(before, "$.generation"));
        assertThat((Object) JsonPath.read(after, "$.updated_at")).isEqualTo(JsonPath.read(before, "$.updated_at"));
    }
}
