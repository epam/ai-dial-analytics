package com.epam.aidial.analytics.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static com.epam.aidial.analytics.integration.RunnerCalls.runnerPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The {@code enrich} arm of {@code POST /v1/pipelines/{name}/preview} against real PostgreSQL +
 * ClickHouse — the checkpoint an author has between declaring a pipeline and letting the Enrichment
 * Runner spend money on it.
 *
 * <p><b>Why these assertions and not a rendered prompt.</b> Preview answers in the shared shape with what a
 * read alone can tell — the newest sampled values, their types, and coded notes — and echoes nothing the
 * pipeline read serves; it stops short of rendering the request, because the template renderer and its
 * escaping live in the runner. So what is checkable here is that the sample really came through the
 * governed read (its membership filter and its clamp apply), that the notes name what an author has to know
 * before arming, and that nothing was written.
 *
 * <p>Every arm is covered because each is different code: a Class-1 ({@code sql}) preview samples computed
 * values through the materialize path's analysis, a row-grain {@code llm} preview reads through the runner
 * scan's plan, and a group preview chooses a group as the runner's marking would and reads its members
 * through the group assembly.
 * The property set is the shared one every enrichment integration class declares (see
 * {@link AbstractClickHouseIntegrationTest#SCAN_SAFETY_LAG_OFF}), so they share one Spring context.
 * Requires Docker.
 */
@TestPropertySource(properties = {AbstractClickHouseIntegrationTest.SCAN_SAFETY_LAG_OFF,
        AbstractClickHouseIntegrationTest.GROUP_FETCH_MAX_BYTES_4KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_WINDOW_LIMIT_3,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_MEMORY_1KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_SPILL_OFF})
class EnrichPreviewIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String SOURCE = "dial_usage_log";
    private static final String LLM_TARGET = "ep_llm_labels";
    private static final String SQL_TARGET = "ep_sql_labels";
    private static final String GROUP_TARGET = "ep_group_labels";
    /** A Class-1 enrichment of the source the clamp cases read through, filled by its own pipeline. */
    private static final String UPSTREAM = "ep_upstream";

    @BeforeEach
    void setUp() throws Exception {
        truncate();
        // A row-grain target with a closed domain on one column, so the composed schema has an enum to
        // resolve and an order to preserve.
        IntegrationTables.enrichment(mockMvc, LLM_TARGET, SOURCE, "event_id",
                "[{\"source_name\":\"title\",\"name\":\"title\",\"type\":\"string\",\"nullable\":true},"
                        + "{\"source_name\":\"mood\",\"name\":\"mood\",\"type\":\"enum\","
                        + "\"enum_values\":[\"low\",\"mid\",\"high\"],\"nullable\":true}]");
        IntegrationTables.enrichment(mockMvc, SQL_TARGET, SOURCE, "event_id",
                "[{\"source_name\":\"deployment_lower\",\"name\":\"deployment_lower\","
                        + "\"type\":\"string\",\"nullable\":true}]");
        IntegrationTables.enrichment(mockMvc, GROUP_TARGET, SOURCE, "chat_id",
                "[{\"source_name\":\"topic\",\"name\":\"topic\",\"type\":\"string\",\"nullable\":true}]");
    }

    @AfterEach
    void tearDown() throws Exception {
        mockMvc.perform(delete("/v1/pipelines/ep-llm-pipeline"));
        mockMvc.perform(delete("/v1/pipelines/ep-sql-pipeline"));
        mockMvc.perform(delete("/v1/pipelines/ep-group-pipeline"));
        mockMvc.perform(delete("/v1/tables/" + LLM_TARGET));
        mockMvc.perform(delete("/v1/tables/" + SQL_TARGET));
        mockMvc.perform(delete("/v1/tables/" + GROUP_TARGET));
        mockMvc.perform(delete("/v1/pipelines/ep-up-pipeline"));
        mockMvc.perform(delete("/v1/tables/" + UPSTREAM));
    }

    private void seedRow(String eventId, String chatId, String deployment) {
        seedRow(eventId, chatId, deployment, "2026-01-01 00:00:00.000");
    }

    /** One source row ingested at {@code ingestedAt}, which is the version every sample orders by. */
    private void seedRow(String eventId, String chatId, String deployment, String ingestedAt) {
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id, chat_id, event_kind, _ingested_at) VALUES (toUUID('" + eventId
                + "'),'p','" + deployment + "', now64(3),'t','" + eventId.substring(0, 8) + "','" + chatId
                + "','chat','" + ingestedAt + "')");
    }

    /**
     * A row-grain {@code llm} transform declaring {@code title} then {@code mood}, the second carrying a
     * real (non-identity) response transform. The declaration order is the one the composed schema must
     * show, because under {@code response_format: {type: json_schema, strict: true}} property order is
     * generation order.
     */
    private static String llmTransform() {
        return Transforms.llm()
                .model("test-model")
                .preset(null)
                .requestTemplate("{\"messages\":[{\"role\":\"user\","
                        + "\"content\":\"Deployment: {{deployment}}\"}]}")
                .input("deployment", "deployment")
                .output("title", "A short title for the call.")
                .outputObject("mood", "{\"prose\":\"How the call reads.\","
                        + "\"jsonata\":\"(mood = \\\"none\\\") ? null : mood\"}")
                .member();
    }

    private String createLlmPipeline(String filter) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ep-llm-pipeline\"," + llmTransform()
                                + ",\"kind\":\"enrich\",\"target\":\"" + LLM_TARGET + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},"
                                + (filter == null ? "" : "\"filter\":\"" + filter + "\",")
                                + "\"enabled\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.name");
    }

    private String createSqlPipeline() throws Exception {
        return JsonPath.read(mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ep-sql-pipeline\","
                                + Transforms.sql().output("deployment_lower", "lower(deployment)").member()
                                + ",\"kind\":\"enrich\",\"target\":\"" + SQL_TARGET + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.name");
    }

    /**
     * A group-grain pipeline. Its transform needs a second, group-shaped template: a group template must
     * reference {{members}} and may reference nothing else, so one template cannot serve both grains.
     */
    private String createGroupPipeline() throws Exception {
        return createGroupPipeline("deployment", null);
    }

    /** A group pipeline binding {@code column}, with an optional {@code member_select} object. */
    private String createGroupPipeline(String column, String memberSelect) throws Exception {
        final String transform = Transforms.llm()
                .model("test-model")
                .preset(null)
                .requestTemplate("{\"messages\":[{\"role\":\"user\","
                        + "\"content\":\"Members: {{members}}\"}]}")
                .input("deployment", column)
                .output("topic", "What the conversation is about.")
                .member();
        return JsonPath.read(mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ep-group-pipeline\"," + transform
                                + ",\"kind\":\"enrich\",\"target\":\"" + GROUP_TARGET + "\","
                                + "\"trigger\":{\"kind\":\"group\",\"group_by\":\"chat_id\","
                                + "\"ready_when\":{\"idle\":\"10m\"}"
                                + (memberSelect == null ? "" : ",\"member_select\":" + memberSelect) + "},"
                                + "\"enabled\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.name");
    }

    /**
     * An enabled Class-1 pipeline filling {@link #UPSTREAM}'s {@code tag} from the source's deployment, so a
     * pipeline reading {@code ep_upstream.tag} is clamped to what it has materialized: nothing at all until its
     * first materialize, then the rows that call wrote.
     */
    private void createUpstream() throws Exception {
        IntegrationTables.enrichment(mockMvc, UPSTREAM, SOURCE, "event_id",
                "[{\"source_name\":\"tag\",\"name\":\"tag\",\"type\":\"string\",\"nullable\":true}]");
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ep-up-pipeline\","
                                + Transforms.sql().output("tag", "lower(deployment)").member()
                                + ",\"kind\":\"enrich\",\"target\":\"" + UPSTREAM + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}"))
                .andExpect(status().isCreated());
    }

    /** The upstream's runner materializes {@code limit} rows, which moves its freshness bound to the last. */
    private void materializeUpstream(int limit) throws Exception {
        mockMvc.perform(runnerPost("/v1/pipelines/ep-up-pipeline/materialize")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":" + limit + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows_written").value(limit));
    }

    /** A disabled row-grain llm pipeline whose one input is the upstream's {@code tag}. */
    private String createLlmReaderOfUpstream() throws Exception {
        final String transform = Transforms.llm()
                .model("test-model")
                .preset(null)
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"Tag: {{tag}}\"}]}")
                .input("tag", UPSTREAM + ".tag")
                .output("title", "A short title for the call.")
                .output("mood", "How the call reads.")
                .member();
        return JsonPath.read(mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ep-llm-pipeline\"," + transform
                                + ",\"kind\":\"enrich\",\"target\":\"" + LLM_TARGET + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.name");
    }

    private long targetRowCount(String table) {
        return Long.parseLong(queryScalar("SELECT count() AS c FROM " + table, "c"));
    }

    // --- the shared shape ----------------------------------------------------------------------------

    /**
     * The request carries no {@code cursor}: the sample is newest first, so a seek-past cursor has no
     * meaning, and the strict request mapper refuses it as the unknown member it now is.
     */
    @Test
    void cursorInThePreviewBodyIsBadRequest() throws Exception {
        final String id = createLlmPipeline(null);

        mockMvc.perform(post("/v1/pipelines/" + id + "/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cursor\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    // --- the Class-1 arm -----------------------------------------------------------------------------

    /**
     * A Class-1 preview answers with the values it computes, typed and with their target's type, and the
     * statement that computed them — and writes nothing. The mapping and the translated expressions are the
     * compiled read's; the statement shows each output's translated SQL.
     */
    @Test
    void class1PreviewReturnsTypedValuesAndTheirStatement() throws Exception {
        final String id = createSqlPipeline();
        seedRow("11111111-1111-1111-1111-111111111111", "chat-1", "GPT-4o");

        // A window the row lies inside, so the statement carries the window's predicates too.
        mockMvc.perform(post("/v1/pipelines/" + id + "/preview")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"from\":\"2025-01-01T00:00:00Z\"}"))
                .andExpect(status().isOk())
                // The source's version column is qualified wherever the sample names it — select list, order,
                // safety lag and window — since its joins are unkeyed and an enrichment may share the name.
                .andExpect(jsonPath("$.statement", containsString("\"dial_usage_log\".\"_ingested_at\" <= now64(3)")))
                .andExpect(jsonPath("$.statement", containsString("\"dial_usage_log\".\"_ingested_at\" > fromUnixTimestamp64Milli(")))
                .andExpect(jsonPath("$.statement", containsString("desc, toString(\"dial_usage_log\".\"event_id\") desc")))
                // The value itself, computed by ClickHouse over a real row.
                .andExpect(jsonPath("$.rows[0].deployment_lower").value("gpt-4o"))
                .andExpect(jsonPath("$.columns[*].name", contains("event_id", "deployment_lower")))
                .andExpect(jsonPath("$.columns[*].role", contains("grain_key", "output")))
                .andExpect(jsonPath("$.columns[1].type").value("string"))
                .andExpect(jsonPath("$.columns[1].target_type").value("string"))
                .andExpect(jsonPath("$.statement", startsWith("select")))
                .andExpect(jsonPath("$.statement", containsString("lower(")))
                .andExpect(jsonPath("$.statement", containsString(" SETTINGS ")))
                // No helper column and no declaration echo, and no count on this kind.
                .andExpect(jsonPath("$.rows[0].__preview_v").doesNotExist())
                .andExpect(jsonPath("$.rows[0].__preview_id").doesNotExist())
                .andExpect(jsonPath("$.total_rows").doesNotExist())
                .andExpect(jsonPath("$.enrich").doesNotExist())
                .andExpect(jsonPath("$.outputs").doesNotExist())
                .andExpect(jsonPath("$.notes", hasSize(0)));

        // Nothing was written. The pipeline is disabled and the preview computed values from real rows,
        // which is exactly the combination that would be a silent write if the arm shared the
        // materialize path rather than only its analysis.
        assertThat(targetRowCount(SQL_TARGET)).isZero();
    }

    @Test
    void class1SampleIsTheNewestRowsFirst() throws Exception {
        final String id = createSqlPipeline();
        seedRow("a1111111-1111-1111-1111-111111111111", "chat-1", "OLD", "2026-01-01 00:00:01.000");
        seedRow("b1111111-1111-1111-1111-111111111111", "chat-1", "NEWEST", "2026-01-01 00:00:03.000");
        seedRow("c1111111-1111-1111-1111-111111111111", "chat-1", "MIDDLE", "2026-01-01 00:00:02.000");

        mockMvc.perform(post("/v1/pipelines/" + id + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].deployment_lower", contains("newest", "middle", "old")));
    }

    /**
     * The sample really comes through the pipeline's own membership filter, rather than being a plain
     * read of the source. Asserted with a filter that excludes the seeded row: an author whose filter is
     * wrong has to be able to see that from the preview, since it is the mistake the preview exists to
     * catch and the one an empty sample is most likely to be blamed on.
     */
    @Test
    void class1PreviewAppliesThePipelineFilterAndSaysWhyTheSampleIsEmpty() throws Exception {
        final String id = JsonPath.read(mockMvc.perform(post("/v1/pipelines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ep-sql-pipeline\","
                                + Transforms.sql().output("deployment_lower", "lower(deployment)").member()
                                + ",\"kind\":\"enrich\",\"target\":\"" + SQL_TARGET + "\","
                                + "\"filter\":\"event_kind = 'no_such_kind'\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.name");
        seedRow("22222222-2222-2222-2222-222222222222", "chat-1", "GPT-4o");

        mockMvc.perform(post("/v1/pipelines/" + id + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(0)))
                .andExpect(jsonPath("$.columns", hasSize(2)))
                .andExpect(jsonPath("$.notes[*].code", contains("no_matching_rows")));
    }

    /**
     * A Class-1 sample is unclamped — a preview stores nothing — so rows past an enrichment's position are
     * sampled with its columns unmatched, and the note says so rather than leaving the defaults to be read
     * as values.
     */
    @Test
    void class1SamplePastAnEnrichmentsPositionSaysSo() throws Exception {
        createUpstream();
        seedRow("a2222222-2222-2222-2222-222222222222", "chat-1", "first", "2026-01-01 00:00:01.000");
        seedRow("b2222222-2222-2222-2222-222222222222", "chat-1", "second", "2026-01-01 00:00:02.000");
        materializeUpstream(1);
        final String id = JsonPath.read(mockMvc.perform(post("/v1/pipelines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ep-sql-pipeline\","
                                + Transforms.sql().output("deployment_lower", "\\\"" + UPSTREAM + ".tag\\\"").member()
                                + ",\"kind\":\"enrich\",\"target\":\"" + SQL_TARGET + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.name");

        mockMvc.perform(post("/v1/pipelines/" + id + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.notes[*].code", contains("enrichment_behind")))
                .andExpect(jsonPath("$.notes[0].subject").value(UPSTREAM))
                .andExpect(jsonPath("$.notes[0].message", containsString("1s past ")));
    }

    // --- the row-grain llm arm -----------------------------------------------------------------------

    /**
     * An {@code llm} preview samples the input the transform would receive — the grain key and the bound
     * inputs only, typed — with no model called, no statement and no schema echo. The filter's column is not
     * returned, though the filter still applies.
     */
    @Test
    void llmPreviewReturnsTheInputValuesOnly() throws Exception {
        final String id = createLlmPipeline("event_kind = 'chat'");
        seedRow("33333333-3333-3333-3333-333333333333", "chat-1", "gpt-4o");

        mockMvc.perform(post("/v1/pipelines/" + id + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[*].name", contains("event_id", "deployment")))
                .andExpect(jsonPath("$.columns[*].role", contains("grain_key", "input")))
                .andExpect(jsonPath("$.columns[1].type").value("string"))
                .andExpect(jsonPath("$.columns[1].target_type").doesNotExist())
                .andExpect(jsonPath("$.columns[1].max_bytes").value(6))
                // The input the transform would receive, under the bound column's own name.
                .andExpect(jsonPath("$.rows[0].deployment").value("gpt-4o"))
                .andExpect(jsonPath("$.rows[0].event_kind").doesNotExist())
                .andExpect(jsonPath("$.rows[0]._ingested_at").doesNotExist())
                .andExpect(jsonPath("$.statement").doesNotExist())
                .andExpect(jsonPath("$.response_schema").doesNotExist())
                .andExpect(jsonPath("$.inputs").doesNotExist())
                .andExpect(jsonPath("$.outputs").doesNotExist());

        assertThat(targetRowCount(LLM_TARGET)).isZero();
    }

    @Test
    void rowGrainSampleIsTheNewestRowsFirst() throws Exception {
        final String id = createLlmPipeline(null);
        seedRow("a3333333-3333-3333-3333-333333333333", "chat-1", "old", "2026-01-01 00:00:01.000");
        seedRow("b3333333-3333-3333-3333-333333333333", "chat-1", "newest", "2026-01-01 00:00:03.000");
        seedRow("c3333333-3333-3333-3333-333333333333", "chat-1", "middle", "2026-01-01 00:00:02.000");

        mockMvc.perform(post("/v1/pipelines/" + id + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].deployment", contains("newest", "middle", "old")));
    }

    /**
     * A sample emptied by the clamp blames the clamp: the enrichment it reads has materialized nothing yet, so
     * an enabled run would write nothing, which is not "no row matches".
     */
    @Test
    void anEmptySampleEmptiedByTheClampBlamesTheClamp() throws Exception {
        createUpstream();
        seedRow("a4444444-4444-4444-4444-444444444444", "chat-1", "first", "2026-01-01 00:00:01.000");
        final String id = createLlmReaderOfUpstream();

        mockMvc.perform(post("/v1/pipelines/" + id + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(0)))
                .andExpect(jsonPath("$.notes[*].code", contains("held_by_enrichment")))
                .andExpect(jsonPath("$.notes[0].subject").value(UPSTREAM));
    }

    @Test
    void rowGrainSampleCutShortByTheClampSaysSo() throws Exception {
        createUpstream();
        seedRow("a5555555-5555-5555-5555-555555555555", "chat-1", "first", "2026-01-01 00:00:01.000");
        seedRow("b5555555-5555-5555-5555-555555555555", "chat-1", "second", "2026-01-01 00:00:02.000");
        materializeUpstream(1);
        final String id = createLlmReaderOfUpstream();

        mockMvc.perform(post("/v1/pipelines/" + id + "/preview"))
                .andExpect(status().isOk())
                // Clamped as the runner's scan is: only the row at or below the upstream's position.
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0]['" + UPSTREAM + ".tag']").value("first"))
                .andExpect(jsonPath("$.notes[*].code", contains("enrichment_behind")))
                .andExpect(jsonPath("$.notes[0].message", containsString("1s past ")));
    }

    // --- the group arm -------------------------------------------------------------------------------

    @Test
    void groupPreviewSamplesTheNamedGroupAndReportsTheKey() throws Exception {
        final String id = createGroupPipeline();
        seedRow("44444444-4444-4444-4444-444444444444", "chat-7", "gpt-4o");
        seedRow("55555555-5555-5555-5555-555555555555", "chat-9", "other-model");

        mockMvc.perform(post("/v1/pipelines/" + id + "/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_key\":\"chat-7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group_key").value("chat-7"))
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].deployment").value("gpt-4o"))
                // Only the inputs: the group key is reported once, not repeated per member.
                .andExpect(jsonPath("$.rows[0].chat_id").doesNotExist())
                .andExpect(jsonPath("$.columns[*].role", contains("input")));
    }

    /**
     * Without a key the preview samples the group of the newest matching row that has one — a keyless row
     * belongs to no group — and reports the key it chose.
     */
    @Test
    void groupPreviewWithNoKeySamplesTheNewestGroupThatHasOne() throws Exception {
        final String id = createGroupPipeline();
        seedRow("66666666-6666-6666-6666-666666666666", "chat-old", "gpt-4o", "2026-01-01 00:00:01.000");
        seedRow("77777777-7777-7777-7777-777777777777", "chat-new", "gpt-5", "2026-01-01 00:00:02.000");
        seedRow("88888888-8888-8888-8888-888888888888", "", "keyless", "2026-01-01 00:00:03.000");

        mockMvc.perform(post("/v1/pipelines/" + id + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group_key").value("chat-new"))
                .andExpect(jsonPath("$.rows[*].deployment", contains("gpt-5")));
    }

    @Test
    void groupPreviewWithNoKeyAndNoMatchingRowSaysNoGroupWasFound() throws Exception {
        final String id = createGroupPipeline();

        mockMvc.perform(post("/v1/pipelines/" + id + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group_key").doesNotExist())
                .andExpect(jsonPath("$.rows", hasSize(0)))
                .andExpect(jsonPath("$.columns[*].name", contains("deployment")))
                .andExpect(jsonPath("$.notes[*].code", contains("no_group_found")));
    }

    @Test
    void groupChoiceHeldByAnEnrichmentSaysSo() throws Exception {
        createUpstream();
        seedRow("99999999-9999-9999-9999-999999999999", "chat-1", "first", "2026-01-01 00:00:01.000");
        final String id = createGroupPipeline(UPSTREAM + ".tag", null);

        mockMvc.perform(post("/v1/pipelines/" + id + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(0)))
                .andExpect(jsonPath("$.notes[*].code", contains("held_by_enrichment")))
                .andExpect(jsonPath("$.notes[0].subject").value(UPSTREAM));
    }

    /**
     * A named group is sampled — the member read is unclamped, as the runner's group fetch is — and the enrichment
     * with no position at all is still reported, since an enabled run would write nothing until it has one.
     */
    @Test
    void namedGroupReadingAnEnrichmentWithNoPositionIsSampledAndSaysSo() throws Exception {
        createUpstream();
        seedRow("98999999-9999-9999-9999-999999999999", "chat-1", "first", "2026-01-01 00:00:01.000");
        final String id = createGroupPipeline(UPSTREAM + ".tag", null);

        mockMvc.perform(post("/v1/pipelines/" + id + "/preview")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"group_key\":\"chat-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group_key").value("chat-1"))
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.notes[*].code", contains("held_by_enrichment")))
                .andExpect(jsonPath("$.notes[0].subject").value(UPSTREAM));
    }

    /**
     * A group larger than the runner's fetch delivers is reported against the configured cap — and a
     * {@code member_select.limit} the declaration chose is its own narrowing, not a cut, unless it asks for
     * more than the fetch will deliver anyway.
     */
    @Test
    void groupLargerThanTheRunnerWouldFetchIsReported() throws Exception {
        // 201 members: one past the shipped 200-row default. request_time varies so none collapses.
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id, chat_id, event_kind, _ingested_at) SELECT generateUUIDv4(), 'p', 'm',"
                + " now64(3) - toIntervalMillisecond(number), 't', toString(number), 'chat-big', 'chat',"
                + " toDateTime64('2026-01-01 00:00:00', 3) + toIntervalMillisecond(number) FROM numbers(201)");

        String id = createGroupPipeline();
        mockMvc.perform(post("/v1/pipelines/" + id + "/preview")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"group_key\":\"chat-big\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(5)))
                .andExpect(jsonPath("$.notes[*].code", contains("group_cut_by_fetch_limit")))
                .andExpect(jsonPath("$.notes[0].message", containsString("delivers 200 of 201+")));
        mockMvc.perform(delete("/v1/pipelines/" + id));

        id = createGroupPipeline("deployment", "{\"limit\":10}");
        mockMvc.perform(post("/v1/pipelines/" + id + "/preview")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"group_key\":\"chat-big\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes", hasSize(0)));
        mockMvc.perform(delete("/v1/pipelines/" + id));

        id = createGroupPipeline("deployment", "{\"limit\":500}");
        mockMvc.perform(post("/v1/pipelines/" + id + "/preview")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"group_key\":\"chat-big\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[*].code", contains("group_cut_by_fetch_limit")))
                .andExpect(jsonPath("$.notes[0].message", containsString("at most 200")));
    }

    // --- members refused on the wrong grain ----------------------------------------------------------

    /**
     * A body member belonging to the other grain is <b>refused</b>, not ignored — the same posture the
     * declaration surface holds for a field belonging to the other kind. A caller who sent one has a
     * different pipeline in mind than the one they named, and silently dropping it would answer a
     * question nobody asked.
     */
    @Test
    void groupKeyOnRowGrainPipelineIsRefused() throws Exception {
        final String id = createLlmPipeline(null);

        mockMvc.perform(post("/v1/pipelines/" + id + "/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_key\":\"chat-7\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("group_key")));
    }

    @Test
    void rowWindowOnGroupPipelineIsRefused() throws Exception {
        final String id = createGroupPipeline();

        mockMvc.perform(post("/v1/pipelines/" + id + "/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-01-01T00:00:00Z\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("group_key")));
    }
}
