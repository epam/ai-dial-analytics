package com.epam.aidial.analytics.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static com.epam.aidial.analytics.integration.RunnerCalls.runnerPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of group-grain enrichment against real PostgreSQL + ClickHouse. The fixture is the
 * capability's worked example: an enrichment on {@code dial_usage_log} keyed by {@code chat_id} — a
 * coarser grain than the event row, which the existing auto-join handles with no code change at all.
 * Created by this test, not seeded by a migration.
 *
 * <p>Covers registration governance (group_by must equal the grain; group_by/ready_when only on a group
 * trigger; the readiness signal parses under the bounded grammar; durations and the ceiling validate; at
 * least one readiness condition is required; a sql transform may not use the group trigger), the
 * dirty-mark scan's extended projection — including the computed {@code __signal} verdict that replaces
 * the raw columns the predicate reads — group assembly with its filter/ordering/caps, the group pair of
 * provenance columns ({@code group_version}/{@code truncated}) round-tripping through the write-back, and
 * that a query joins the group enrichment onto the source without inflating rows. Safety-lag is 0 so the
 * past-dated fixture rows are visible.
 *
 * <p>The property overrides are the shared set every enrichment integration class declares (see
 * {@link AbstractClickHouseIntegrationTest#SCAN_SAFETY_LAG_OFF}), so they all share one Spring context; the
 * lowered byte cap is what lets {@link #groupsFetchByteCapRetainsTheNewestMember()} trip it with body-carrying
 * members while every other test here — whose members project a handful of small columns — stays far below
 * it. Requires Docker.
 */
@TestPropertySource(properties = {AbstractClickHouseIntegrationTest.SCAN_SAFETY_LAG_OFF,
        AbstractClickHouseIntegrationTest.GROUP_FETCH_MAX_BYTES_4KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_WINDOW_LIMIT_3,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_MEMORY_1KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_SPILL_OFF})
class GroupEnrichmentIntegrationTest extends AbstractClickHouseIntegrationTest {

    /**
     * The one {@code request_time} every seeded row carries, parent and payload alike. A single shared
     * literal rather than {@code now64(3)} in each statement: the payload table is partition-aligned with
     * its parent in the catalog, so a keyed grain join bounds the payload side by the parent's value, and
     * two clock reads milliseconds apart would put the two rows in disagreement — losing the body to the
     * bound rather than failing visibly.
     */
    private static final String REQUEST_TIME = "'" + fixtureTime("00:00:00.000") + "'";

    private static final String SOURCE = "dial_usage_log";
    private static final String ENRICHMENT = "conv_label";

    /**
     * A GROUP-grain {@code llm} transform, optionally binding {@code transform.inputs}. The render inputs
     * at group grain are only the five {@code members_*} built-ins, and {{members}} is mandatory — without
     * it the model receives a prompt with no group data in it at all. A declared input is a field of each
     * member object inside {{members}}, so it appears in no placeholder, which is why this template names
     * none of them.
     *
     * @param inputs variable/column pairs, flattened: {@code "body", "col", "other", "col2"}
     */
    private static String groupTransform(String... inputs) {
        final Transforms.Builder transform = Transforms.llm()
                .requestTemplate("{\"messages\":[{\"role\":\"user\","
                        + "\"content\":\"Members ({{members_included}}). {{members}}\"}]}")
                .proseOutputs("topic");
        for (int i = 0; i < inputs.length; i += 2) {
            transform.input(inputs[i], inputs[i + 1]);
        }
        return transform.member();
    }

    /**
     * A <b>row-grain</b> transform, for the row pipelines here. The template rule is grain-specific — a
     * group template must reference {{members}} and may reference nothing else, while a row-grain template
     * must reference each declared input and no built-in — so one template cannot serve both grains. That
     * is inherent rather than incidental: the template <em>is</em> the grain-specific part.
     */
    private static String rowTransform() {
        return Transforms.llm()
                .requestTemplate("{\"messages\":[{\"role\":\"user\","
                        + "\"content\":\"Body: {{body}}\"}]}")
                .input("body", "dial_usage_log_payload.request_body")
                .proseOutputs("topic")
                .member();
    }

    @BeforeEach
    void setUp() throws Exception {
        truncate();
        // A group-grain enrichment: same 1:0..1 shape as a row enrichment, keyed on a coarser column.
        IntegrationTables.enrichment(mockMvc, ENRICHMENT, SOURCE, "chat_id",
                "[{\"source_name\":\"topic\",\"name\":\"topic\",\"type\":\"string\"}]");
    }

    @AfterEach
    void tearDown() throws Exception {
        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT));
    }

    /** POSTs a group pipeline body and returns the raw response for the caller to assert on. */
    private String pipelineBody(String groupBy, String readyWhen, String filter) {
        return pipelineBody(groupBy, readyWhen, filter, null);
    }

    /**
     * As above, with a member-selection policy. It goes <b>inside</b> {@code trigger}: member selection
     * qualifies the group trigger, so it moved there with the group key when the two registries merged.
     */
    private String pipelineBody(String groupBy, String readyWhen, String filter, String memberSelect) {
        return "{\"name\":\"conv-pipeline\"," + groupTransform() + ","
                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                + "\"trigger\":{\"kind\":\"group\","
                + "\"group_by\":\"" + groupBy + "\",\"ready_when\":" + readyWhen
                + (memberSelect == null ? "" : ",\"member_select\":" + memberSelect) + "},"
                + (filter == null ? "" : "\"filter\":\"" + filter + "\",")
                + "\"enabled\":true}";
    }

    private String createGroupPipeline(String readyWhen, String filter) throws Exception {
        String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("chat_id", readyWhen, filter)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(content, "$.name");
    }

    /** As {@link #seedRow}, plus a {@code request_body} so a projected member row carries real bulk. */
    private void seedRowWithBody(String eventId, String chatId, String ingestedAt, String requestBody) {
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id, chat_id, event_kind, _ingested_at) VALUES (toUUID('" + eventId
                + "'),'p','d', " + REQUEST_TIME + ",'t','" + eventId.substring(0, 8) + "','" + chatId + "','chat','"
                + ingestedAt + "')");
        // The body lives on dial_usage_log_payload since the split; same event_id and the SAME
        // request_time, which is what ingest writes. The two are partition-aligned in the catalog, so the
        // grain join bounds the payload side by the parent's value.
        execute("INSERT INTO dial_usage_log_payload (event_id, request_time, trace_id, core_span_id,"
                + " request_body) VALUES (toUUID('" + eventId + "'), " + REQUEST_TIME + ", 't','"
                + eventId.substring(0, 8) + "','" + requestBody + "')");
    }

    private void seedRow(String eventId, String chatId, String ingestedAt, long totalTokens, String eventKind) {
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id, chat_id, event_kind, _ingested_at, total_tokens) VALUES (toUUID('" + eventId
                + "'),'p','d', " + REQUEST_TIME + ",'t','" + eventId.substring(0, 8) + "','" + chatId + "','" + eventKind
                + "','" + ingestedAt + "'," + totalTokens + ")");
    }

    // ------------------------------------------------------------ A1: registry

    @Test
    void groupPipelineRegistersWithGroupByAndReadyWhen() throws Exception {
        String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("chat_id",
                                "{\"signal\":\"event_kind = 'conversation_end'\",\"idle\":\"10m\","
                                        + "\"max_staleness\":\"PT24H\",\"cost_ceiling\":5}", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trigger.kind").value("group"))
                .andExpect(jsonPath("$.trigger.group_by").value("chat_id"))
                .andExpect(jsonPath("$.trigger.cron").doesNotExist())
                .andExpect(jsonPath("$.trigger.ready_when.signal").value("event_kind = 'conversation_end'"))
                .andExpect(jsonPath("$.trigger.ready_when.idle").value("10m"))
                .andExpect(jsonPath("$.trigger.ready_when.max_staleness").value("PT24H"))
                .andExpect(jsonPath("$.trigger.ready_when.cost_ceiling").value(5))
                .andReturn().getResponse().getContentAsString();

        // The source's version column, so a caller reads the group's activity timestamp out of a scan
        // row by name instead of holding one configured name for every source it scans. Derived, so it
        // is served by the compiled projection and by nothing else.
        mockMvc.perform(get("/v1/pipelines/" + JsonPath.<String>read(content, "$.name") + "?view=compiled"))
                .andExpect(jsonPath("$.version_column").value("_ingested_at"))
                .andExpect(jsonPath("$.grain_key").value("chat_id"));

        // The sync feed inlines the group declaration so the caller can route without a second round-trip.
        mockMvc.perform(get("/v1/pipelines?kind=enrich&view=compiled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[?(@.name == '" + JsonPath.read(content, "$.name") + "')].trigger.group_by")
                        .value(hasSize(1)))
                .andExpect(jsonPath("$.pipelines[?(@.name == '" + JsonPath.read(content, "$.name") + "')].version_column")
                        .value(contains("_ingested_at")));
    }

    @Test
    void groupByNotEqualToTheGrainIsRejected() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("trace_id", "{\"idle\":\"10m\"}", null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("must resolve to the target enrichment's grain")));
    }

    @Test
    void groupByOnNonGroupTriggerIsRejected() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"conv-row-pipeline\"," + groupTransform() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                                + "\"trigger\":{\"kind\":\"schedule\",\"group_by\":\"chat_id\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("trigger.group_by is only valid for a group pipeline")));
    }

    @Test
    void unparseableReadinessSignalIsRejected() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("chat_id", "{\"signal\":\"no_such_column = 1\"}", null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("ready_when.signal is invalid")));
    }

    @Test
    void readinessWithNoConditionIsRejected() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("chat_id", "{\"cost_ceiling\":5}", null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("at least one of signal, idle")));
    }

    @Test
    void badReadinessDurationOrCeilingIsRejected() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("chat_id", "{\"idle\":\"soon\"}", null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("is not a duration")));
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("chat_id", "{\"idle\":\"10m\",\"cost_ceiling\":0}", null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("cost_ceiling must be a positive integer")));
    }

    @Test
    void sqlTransformCannotUseTheGroupTrigger() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"conv-sql-pipeline\","
                                + Transforms.sql().output("topic", "lower(deployment)").member() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                                + "\"trigger\":{\"kind\":\"group\",\"group_by\":\"chat_id\","
                                + "\"ready_when\":{\"idle\":\"10m\"}},"
                                + "\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("cannot use the group trigger")));
    }

    // ------------------------------------------------------------ A2a: dirty-mark scan

    @Test
    void groupPipelineScanProjectsGroupKeySignalVerdictAndActivity() throws Exception {
        seedRow("11111111-1111-1111-1111-111111111111", "chat-a", "2020-01-01 00:00:01.000", 100, "chat");
        seedRow("12222222-2222-2222-2222-222222222222", "chat-a", "2020-01-01 00:00:02.000", 100,
                "conversation_end");
        String id = createGroupPipeline("{\"signal\":\"event_kind = 'conversation_end'\",\"idle\":\"10m\"}", null);

        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].chat_id").value("chat-a"))
                // the readiness verdict, computed here: a boolean per row, so readiness is evaluable from
                // the page alone without the caller re-implementing the predicate
                .andExpect(jsonPath("$.rows[0].__signal").value(false))
                .andExpect(jsonPath("$.rows[1].__signal").value(true))
                // and the raw column the predicate reads is *not* shipped: the verdict replaces it
                .andExpect(jsonPath("$.rows[0].event_kind").doesNotExist())
                // the activity timestamp: the source's version column, already projected by name
                .andExpect(jsonPath("$.rows[0]._ingested_at").isNotEmpty())
                .andExpect(jsonPath("$.next_cursor").isNotEmpty());
    }

    /**
     * A group pipeline's page is a dirty-mark, so it carries which groups changed and not what they contain:
     * the bound input columns are read by group assembly, under the caps that make reading a body safe,
     * and projecting them here would put an unbounded volume on the one pipeline-scoped read with no byte cap.
     */
    @Test
    void groupPipelineScanOmitsBoundInputColumns() throws Exception {
        seedRowWithBody("14444444-4444-4444-4444-444444444444", "chat-a", "2020-01-01 00:00:01.000",
                "x".repeat(200));
        String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"conv-bound-pipeline\","
                                + groupTransform("body", "dial_usage_log_payload.request_body") + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                                + "\"trigger\":{\"kind\":\"group\",\"group_by\":\"chat_id\","
                                + "\"ready_when\":{\"idle\":\"10m\"}},"
                                + "\"filter\":\"length(chat_id) > 0\","
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(runnerPost("/v1/pipelines/" + JsonPath.read(content, "$.name") + "/scan")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                // the bound body is absent — it belongs to group assembly, not to the dirty-mark
                .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.request_body']").doesNotExist())
                // everything the dirty-mark actually needs is still there
                .andExpect(jsonPath("$.rows[0].chat_id").value("chat-a"))
                .andExpect(jsonPath("$.rows[0]._ingested_at").isNotEmpty())
                .andExpect(jsonPath("$.rows[0].event_id").isNotEmpty());
    }

    /**
     * The row lane keeps the bindings, because there the scanned row <em>is</em> the unit of work: its
     * chunk payload is mapped from exactly those columns, so omitting them would leave nothing to evaluate.
     */
    @Test
    void rowPipelineScanStillProjectsBoundInputColumns() throws Exception {
        seedRowWithBody("15555555-5555-5555-5555-555555555555", "chat-a", "2020-01-01 00:00:01.000", "hello");
        String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"conv-row-bound-pipeline\"," + rowTransform() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(runnerPost("/v1/pipelines/" + JsonPath.read(content, "$.name") + "/scan")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.request_body']").value("hello"));
    }

    /** A pipeline declaring no signal carries no verdict column at all, rather than a constant one. */
    @Test
    void groupPipelineWithoutSignalCarriesNoVerdict() throws Exception {
        seedRow("13333333-3333-3333-3333-333333333333", "chat-a", "2020-01-01 00:00:01.000", 100, "chat");
        String id = createGroupPipeline("{\"idle\":\"10m\"}", null);

        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].chat_id").value("chat-a"))
                .andExpect(jsonPath("$.rows[0].__signal").doesNotExist());
    }

    // ------------------------------------------------------------ A2b: group assembly

    @Test
    void groupsFetchReturnsMembersFilteredAndOrdered() throws Exception {
        seedRow("21111111-1111-1111-1111-111111111111", "chat-a", "2020-01-01 00:00:03.000", 300, "chat");
        seedRow("22222222-2222-2222-2222-222222222222", "chat-a", "2020-01-01 00:00:01.000", 100, "chat");
        seedRow("23333333-3333-3333-3333-333333333333", "chat-a", "2020-01-01 00:00:02.000", 0, "chat");
        seedRow("24444444-4444-4444-4444-444444444444", "chat-b", "2020-01-01 00:00:01.000", 500, "chat");
        // The membership filter excludes the zero-token member; chat-b belongs to another group.
        String id = createGroupPipeline("{\"idle\":\"10m\"}", "total_tokens > 0");

        String content = mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/group-rows")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"group_key\":\"chat-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.truncated").value(false))
                // ordered by the source's total order, so the caller assembles in ingest order
                .andExpect(jsonPath("$.rows[0].total_tokens").value(100))
                .andExpect(jsonPath("$.rows[1].total_tokens").value(300))
                .andReturn().getResponse().getContentAsString();
        List<String> chatIds = JsonPath.read(content, "$.rows[*].chat_id");
        assertThat(chatIds).containsOnly("chat-a");
    }

    /**
     * The row cap keeps the group's <b>most recent</b> members, and still returns them in the source's
     * total order. A group-grain label describes the group's current state, so retaining its opening would
     * answer a question about a conversation's outcome from its first turns — and the caller's own budget
     * trims from the front of what it receives, so both layers have to agree which end is expendable.
     */
    @Test
    void groupsFetchRowCapRetainsTheNewestMembersInOrder() throws Exception {
        seedRow("31111111-1111-1111-1111-111111111111", "chat-a", "2020-01-01 00:00:01.000", 100, "chat");
        seedRow("32222222-2222-2222-2222-222222222222", "chat-a", "2020-01-01 00:00:02.000", 200, "chat");
        seedRow("33333333-3333-3333-3333-333333333333", "chat-a", "2020-01-01 00:00:03.000", 300, "chat");
        // The filter projects total_tokens, which is what makes "which members survived" observable.
        String id = createGroupPipeline("{\"idle\":\"10m\"}", "total_tokens > 0");

        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/group-rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_key\":\"chat-a\",\"limit\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.truncated").value(true))
                // the newest two, ascending: the oldest member is the one dropped
                .andExpect(jsonPath("$.rows[0].total_tokens").value(200))
                .andExpect(jsonPath("$.rows[1].total_tokens").value(300));
    }

    /**
     * The byte cap, which is the bound that actually matters: a group of a few members carrying bodies can
     * be megabytes, so a row cap alone leaves payload size unbounded on both sides. Here two members whose
     * projected {@code request_body} each exceeds the (lowered) cap are trimmed to one — an empty group
     * would be indistinguishable from "this group has no members" and would strand the caller — and the row
     * cap is nowhere near reached. The member kept is the <b>newest</b>, agreeing with the row cap: for a
     * conversation that is the hop carrying the accumulated history.
     */
    @Test
    void groupsFetchByteCapRetainsTheNewestMember() throws Exception {
        seedRowWithBody("51111111-1111-1111-1111-111111111111", "chat-a", "2020-01-01 00:00:01.000",
                "older-" + "x".repeat(5000));
        seedRowWithBody("52222222-2222-2222-2222-222222222222", "chat-a", "2020-01-01 00:00:02.000",
                "newer-" + "x".repeat(5000));
        // The pipeline projects the body via a bound input binding, so it counts toward the response size.
        String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"conv-body-pipeline\","
                                + groupTransform("body", "dial_usage_log_payload.request_body") + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                                + "\"trigger\":{\"kind\":\"group\",\"group_by\":\"chat_id\","
                                + "\"ready_when\":{\"idle\":\"10m\"}},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(runnerPost("/v1/pipelines/" + JsonPath.read(content, "$.name") + "/group-rows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_key\":\"chat-a\",\"limit\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.request_body']").value(startsWith("newer-")));
    }

    // ------------------------------------------------------------ computed fields: the rebuilt request body

    private static final String FULL = "dial_usage_log_payload.request_body_full";
    private static final String STATUS = "dial_usage_log_payload.request_body_rebuild";
    private static final String ZERO = "00000000-0000-0000-0000-000000000000";

    /** A member stored in delta form: its payload row carries {@code body}, its chain path and its parent's. */
    private void seedDeltaMember(String eventId, String chatId, String ingestedAt, String path, String parent, String body) {
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id, chat_id, event_kind, _ingested_at) VALUES (toUUID('" + eventId
                + "'),'p','d', " + REQUEST_TIME + ",'t','" + eventId.substring(0, 8) + "','" + chatId + "','chat','"
                + ingestedAt + "')");
        execute("INSERT INTO dial_usage_log_payload (event_id, request_time, trace_id, core_span_id, request_hash,"
                + " request_parent_hash, request_ancestor_hashes, request_body) VALUES (toUUID('" + eventId + "'), " + REQUEST_TIME
                + ", 't','" + eventId.substring(0, 8) + "', toUUID('" + path + "'), toUUID('" + parent + "'), "
                + (ZERO.equals(parent) ? "[]" : "[toUUID('" + parent + "')]") + ", '" + body + "')");
    }

    private static String body(String... contents) {
        final List<String> messages = new java.util.ArrayList<>();
        for (final String content : contents) {
            messages.add("{\"content\":\"" + content + "\",\"role\":\"user\"}");
        }
        return "{\"messages\":[" + String.join(",", messages) + "]}";
    }

    private String createRebuildPipeline(String name, String memberSelect) throws Exception {
        return mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"," + groupTransform("body", FULL, "status", STATUS) + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                                + "\"trigger\":{\"kind\":\"group\",\"group_by\":\"chat_id\",\"ready_when\":{\"idle\":\"10m\"}"
                                + (memberSelect == null ? "" : ",\"member_select\":" + memberSelect) + "},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void groupMemberIsAssembledWithItsWholeRequest() throws Exception {
        seedDeltaMember("61111111-1111-1111-1111-111111111111", "chat-a", "2020-01-01 00:00:01.000",
                "00000000-0000-4000-8000-000000000201", ZERO, body("u1"));
        seedDeltaMember("62222222-2222-2222-2222-222222222222", "chat-a", "2020-01-01 00:00:02.000",
                "00000000-0000-4000-8000-000000000202", "00000000-0000-4000-8000-000000000201", body("u2"));
        final String name = JsonPath.read(createRebuildPipeline("conv-rebuild-pipeline", null), "$.name");

        mockMvc.perform(runnerPost("/v1/pipelines/" + name + "/group-rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_key\":\"chat-a\",\"limit\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.rows[0]['" + FULL + "']").value(body("u1")))
                .andExpect(jsonPath("$.rows[0]['" + STATUS + "']").value("stored"))
                .andExpect(jsonPath("$.rows[1]['" + FULL + "']").value(body("u1", "u2")))
                .andExpect(jsonPath("$.rows[1]['" + STATUS + "']").value("rebuilt"));
    }

    @Test
    void partialMemberIsFedAsStoredAndDoesNotSetTruncated() throws Exception {
        seedDeltaMember("63333333-3333-3333-3333-333333333333", "chat-a", "2020-01-01 00:00:01.000",
                "00000000-0000-4000-8000-000000000302", "00000000-0000-4000-8000-0000000003ff", body("u2"));
        final String name = JsonPath.read(createRebuildPipeline("conv-partial-pipeline", null), "$.name");

        mockMvc.perform(runnerPost("/v1/pipelines/" + name + "/group-rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_key\":\"chat-a\",\"limit\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.rows[0]['" + FULL + "']").value(body("u2")))
                .andExpect(jsonPath("$.rows[0]['" + STATUS + "']").value("partial"));
    }

    /**
     * The root is stored whole but is no chat request, so a walk over the chain columns alone reaches it while the
     * rebuild itself cannot use it. Bound together, the status must come from the same rebuild as the body: read apart,
     * the status read would say {@code rebuilt} beside a body the other read returned as the stored delta.
     */
    @Test
    void bothBoundFieldsAgreeWhenTheChainEndsAtUnusableRoot() throws Exception {
        seedDeltaMember("68888888-8888-8888-8888-888888888888", "chat-a", "2020-01-01 00:00:01.000",
                "00000000-0000-4000-8000-000000000601", ZERO, "{\"not\":\"chat\"}");
        seedDeltaMember("69999999-9999-9999-9999-999999999999", "chat-a", "2020-01-01 00:00:02.000",
                "00000000-0000-4000-8000-000000000602", "00000000-0000-4000-8000-000000000601", body("u2"));
        final String name = JsonPath.read(createRebuildPipeline("conv-invalid-root-pipeline", null), "$.name");

        mockMvc.perform(runnerPost("/v1/pipelines/" + name + "/group-rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_key\":\"chat-a\",\"limit\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0]['" + STATUS + "']").value("stored"))
                .andExpect(jsonPath("$.rows[1]['" + FULL + "']").value(body("u2")))
                .andExpect(jsonPath("$.rows[1]['" + STATUS + "']").value("partial"));
    }

    /** Stored, the two members fit the lowered 4 KB cap; rebuilt, the newer one carries the older's history too. */
    @Test
    void theByteCapCountsTheRebuiltBody() throws Exception {
        final String big = "x".repeat(2500);
        seedDeltaMember("64444444-4444-4444-4444-444444444444", "chat-a", "2020-01-01 00:00:01.000",
                "00000000-0000-4000-8000-000000000401", ZERO, body(big));
        seedDeltaMember("65555555-5555-5555-5555-555555555555", "chat-a", "2020-01-01 00:00:02.000",
                "00000000-0000-4000-8000-000000000402", "00000000-0000-4000-8000-000000000401", body("short"));
        final String name = JsonPath.read(createRebuildPipeline("conv-cap-pipeline", null), "$.name");

        mockMvc.perform(runnerPost("/v1/pipelines/" + name + "/group-rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_key\":\"chat-a\",\"limit\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.rows[0]['" + FULL + "']").value(body(big, "short")));
    }

    @Test
    void rowGrainScanPageCarriesTheRebuiltBody() throws Exception {
        seedDeltaMember("66666666-6666-6666-6666-666666666666", "chat-a", "2020-01-01 00:00:01.000",
                "00000000-0000-4000-8000-000000000501", ZERO, body("u1"));
        seedDeltaMember("67777777-7777-7777-7777-777777777777", "chat-a", "2020-01-01 00:00:02.000",
                "00000000-0000-4000-8000-000000000502", "00000000-0000-4000-8000-000000000501", body("u2"));
        final String transform = Transforms.llm()
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"Body: {{body}}\"}]}")
                .input("body", FULL)
                .proseOutputs("topic")
                .member();
        final String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"conv-row-rebuild-pipeline\"," + transform + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(runnerPost("/v1/pipelines/" + JsonPath.read(content, "$.name") + "/scan")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0]['" + FULL + "']").value(body("u1")))
                .andExpect(jsonPath("$.rows[1]['" + FULL + "']").value(body("u1", "u2")))
                .andExpect(jsonPath("$.next_cursor").isNotEmpty());
    }

    /**
     * The dry-run preview reads through the same choke point as the runner's reads, and types its columns from the
     * computed bindings: the row sample and the group's member sample both carry the completed value, never the stored
     * delta or the stand-in tuple.
     */
    @Test
    void previewShowsWhatTheRunnerWouldReceive() throws Exception {
        seedDeltaMember("6aaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "chat-a", "2020-01-01 00:00:01.000",
                "00000000-0000-4000-8000-000000000701", ZERO, body("u1"));
        seedDeltaMember("6bbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "chat-a", "2020-01-01 00:00:02.000",
                "00000000-0000-4000-8000-000000000702", "00000000-0000-4000-8000-000000000701", body("u2"));
        final String transform = Transforms.llm()
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"Body: {{body}} {{status}}\"}]}")
                .input("body", FULL)
                .input("status", STATUS)
                .proseOutputs("topic")
                .member();
        final String row = JsonPath.read(mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"conv-row-rebuild-preview\"," + transform + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.name");

        // The row sample is newest first.
        mockMvc.perform(post("/v1/pipelines/" + row + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[*].name", contains("chat_id", FULL, STATUS)))
                .andExpect(jsonPath("$.columns[1].type").value("string"))
                .andExpect(jsonPath("$.columns[2].type").value("enum"))
                .andExpect(jsonPath("$.rows[*]['" + FULL + "']", contains(body("u1", "u2"), body("u1"))))
                .andExpect(jsonPath("$.rows[*]['" + STATUS + "']", contains("rebuilt", "stored")));
        // One pipeline per target: the group declaration replaces the row one.
        mockMvc.perform(delete("/v1/pipelines/" + row)).andExpect(status().is2xxSuccessful());
        final String group = JsonPath.read(createRebuildPipeline("conv-group-rebuild-preview", null), "$.name");
        mockMvc.perform(post("/v1/pipelines/" + group + "/preview")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"group_key\":\"chat-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group_key").value("chat-a"))
                .andExpect(jsonPath("$.columns[*].name", contains(FULL, STATUS)))
                .andExpect(jsonPath("$.columns[0].type").value("string"))
                .andExpect(jsonPath("$.columns[1].type").value("enum"))
                .andExpect(jsonPath("$.rows[*]['" + FULL + "']", containsInAnyOrder(body("u1"), body("u1", "u2"))))
                .andExpect(jsonPath("$.rows[*]['" + STATUS + "']", containsInAnyOrder("stored", "rebuilt")));
    }

    @Test
    void computedFieldIsRefusedAsMemberRankingAndInFilter() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("chat_id", "{\"idle\":\"10m\"}", null,
                                "{\"order_by\":[{\"column\":\"" + FULL + "\"}],\"limit\":5}")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString(FULL)))
                .andExpect(jsonPath("$.message").value(containsString("bound as a transform input")));
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("chat_id", "{\"idle\":\"10m\"}", "\\\"" + STATUS + "\\\" = 'rebuilt'")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString(STATUS)));
    }

    /**
     * A draft is held to the same refusal: the data plane reads a disabled pipeline as stored, so a draft ranking its
     * members by a computed field would be sampled by the stand-in the read renders in the field's place. Refused on
     * a registration that leaves the pipeline disabled and on a patch that keeps it so, with the armed path's 422.
     */
    @Test
    void draftCannotStoreComputedFieldOutsideItsInputs() throws Exception {
        final String rankByBody = "{\"order_by\":[{\"column\":\"" + FULL + "\"}],\"limit\":5}";
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(draft(pipelineBody("chat_id", "{\"idle\":\"10m\"}", null, rankByBody))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("member_select.order_by references '" + FULL + "'")))
                .andExpect(jsonPath("$.message").value(containsString("bound as a transform input")));
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(draft(pipelineBody("chat_id", "{\"idle\":\"10m\"}", "\\\"" + STATUS + "\\\" = 'rebuilt'"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("filter references '" + STATUS + "'")));
        // A name quoted as a value is not a reference.
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(draft(pipelineBody("chat_id", "{\"idle\":\"10m\"}", "deployment = '" + FULL + "'"))))
                .andExpect(status().isCreated());
        mockMvc.perform(patch("/v1/pipelines/conv-pipeline").contentType(MediaType.APPLICATION_JSON)
                        .content(draft(pipelineBody("chat_id", "{\"idle\":\"10m\"}", null, rankByBody))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("member_select.order_by references '" + FULL + "'")));
        mockMvc.perform(get("/v1/pipelines/conv-pipeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trigger.member_select").doesNotExist());
    }

    private static String draft(String body) {
        return body.replace("\"enabled\":true", "\"enabled\":false");
    }

    /**
     * As {@link #seedRowWithBody}, with an {@code assembled_response} beside the request body, so a
     * declaration can bind two heavy columns of the same payload enrichment. A null {@code assembledResponse}
     * seeds the column's NULL, which is the shape the split projection must not mistake for a missing member.
     */
    private void seedRowWithBodies(String eventId, String chatId, String ingestedAt, String requestBody,
            String assembledResponse) {
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id, chat_id, event_kind, _ingested_at) VALUES (toUUID('" + eventId
                + "'),'p','d', " + REQUEST_TIME + ",'t','" + eventId.substring(0, 8) + "','" + chatId + "','chat','"
                + ingestedAt + "')");
        // Same event_id and the same request_time as the parent, for the alignment reason above.
        execute("INSERT INTO dial_usage_log_payload (event_id, request_time, trace_id, core_span_id,"
                + " request_body, assembled_response) VALUES (toUUID('" + eventId + "'), " + REQUEST_TIME
                + ", 't','" + eventId.substring(0, 8) + "','" + requestBody + "',"
                + (assembledResponse == null ? "NULL" : "'" + assembledResponse + "'") + ")");
    }

    /** POSTs a group pipeline binding both heavy payload columns plus one inexpensive source column. */
    private String createTwoBodyPipeline() throws Exception {
        String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"conv-bodies-pipeline\","
                                + groupTransform("request", "dial_usage_log_payload.request_body",
                                        "response", "dial_usage_log_payload.assembled_response",
                                        "handled_by", "deployment") + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                                + "\"trigger\":{\"kind\":\"group\",\"group_by\":\"chat_id\","
                                + "\"ready_when\":{\"idle\":\"10m\"}},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(content, "$.name");
    }

    /**
     * Two heavy columns bound by one declaration, read one statement each and merged back onto the same
     * member. The projection phase splits by heavy column because several in one statement cost the sum of
     * all of them — a pair can exceed a read budget that either half clears
     * alone — so what matters here is that the member arrives carrying BOTH values, which is only true if
     * the two reads were matched up by lookup key.
     */
    @Test
    void groupsFetchMergesTwoHeavyColumnsReadSeparately() throws Exception {
        seedRowWithBodies("53333333-3333-3333-3333-333333333333", "chat-a", "2020-01-01 00:00:01.000",
                "the-request", "the-response");

        mockMvc.perform(runnerPost("/v1/pipelines/" + createTwoBodyPipeline() + "/group-rows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_key\":\"chat-a\",\"limit\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.request_body']").value("the-request"))
                .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.assembled_response']")
                        .value("the-response"))
                // The inexpensive bound column rides along with the first heavy read rather than costing a
                // statement of its own; it must still arrive.
                .andExpect(jsonPath("$.rows[0].deployment").value("d"));
    }

    /**
     * A NULL heavy column keeps its member. Whether a read found a member cannot be inferred from the value
     * it returned — {@code assembled_response} is legitimately NULL for a hop whose producer reported none —
     * so the split projection tallies the reads that matched each member rather than testing for a value.
     * A member dropped here would come back as a truncated group and would silently narrow the prompt.
     */
    @Test
    void groupsFetchKeepsMemberWhoseSecondHeavyColumnIsNull() throws Exception {
        seedRowWithBodies("54444444-4444-4444-4444-444444444444", "chat-a", "2020-01-01 00:00:01.000",
                "only-a-request", null);

        mockMvc.perform(runnerPost("/v1/pipelines/" + createTwoBodyPipeline() + "/group-rows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_key\":\"chat-a\",\"limit\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.request_body']").value("only-a-request"))
                .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.assembled_response']")
                        .value(nullValue()));
    }

    // ------------------------------------------------------------ A2c: member selection

    /** POSTs a group pipeline that binds {@code request_body} and carries {@code member_select}. */
    private String createBodyPipeline(String memberSelect) throws Exception {
        String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"conv-select-pipeline\","
                                + groupTransform("body", "dial_usage_log_payload.request_body") + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                                + "\"trigger\":{\"kind\":\"group\",\"group_by\":\"chat_id\","
                                + "\"ready_when\":{\"idle\":\"10m\"}"
                                + (memberSelect == null ? "" : ",\"member_select\":" + memberSelect) + "},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(content, "$.name");
    }

    /** As {@link #seedRowWithBody}, plus {@code parent_deployment} and {@code number_request_messages}. */
    private void seedHop(String eventId, String chatId, String ingestedAt, String body,
            String parentDeployment, int messages) {
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id, chat_id, event_kind, _ingested_at, parent_deployment,"
                + " number_request_messages) VALUES (toUUID('" + eventId + "'),'p','d', " + REQUEST_TIME + ",'t','"
                + eventId.substring(0, 8) + "','" + chatId + "','chat','" + ingestedAt + "',"
                + (parentDeployment == null ? "NULL" : "'" + parentDeployment + "'") + "," + messages + ")");
        // Same event_id and the same request_time as the parent, for the alignment reason above.
        execute("INSERT INTO dial_usage_log_payload (event_id, request_time, trace_id, core_span_id,"
                + " request_body) VALUES (toUUID('" + eventId + "'), " + REQUEST_TIME + ", 't','"
                + eventId.substring(0, 8) + "','" + body + "')");
    }

    /**
     * The read a declared limit is for: three members carrying bodies, one of which is kept. The bound body
     * is what the two-phase read defers, so a populated {@code request_body} on the survivor is the evidence
     * that the second read found the member it selected — a mis-addressed point lookup would return the
     * member with a null body, or drop it entirely.
     *
     * <p>A declared limit lowers the effective cap rather than sitting beside it: the caller asks for 100,
     * the configured default is higher still, and the pipeline's 1 wins. That is what lets a pipeline bound its own
     * read without its caller cooperating.
     */
    @Test
    void memberSelectLimitLowersTheEffectiveRowCap() throws Exception {
        seedHop("61111111-1111-1111-1111-111111111111", "chat-a", "2020-01-01 00:00:01.000", "oldest", "up", 3);
        seedHop("62222222-2222-2222-2222-222222222222", "chat-a", "2020-01-01 00:00:02.000", "middle", "up", 4);
        seedHop("63333333-3333-3333-3333-333333333333", "chat-a", "2020-01-01 00:00:03.000", "newest", "up", 5);
        String id = createBodyPipeline("{\"limit\":1}");

        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/group-rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_key\":\"chat-a\",\"limit\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.truncated").value(true))
                // no order_by declared, so the policy is still the source's total order: the newest member
                .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.request_body']").value("newest"))
                // and the response still carries only what the pipeline references — never the helper columns
                // the selection phase read to address the member
                .andExpect(jsonPath("$.rows[0].chat_id").value("chat-a"))
                .andExpect(jsonPath("$.rows[0].__ok0").doesNotExist())
                .andExpect(jsonPath("$.rows[0].__prefer").doesNotExist())
                .andExpect(jsonPath("$.rows[0].request_time").doesNotExist());
    }

    /**
     * The case the whole policy exists for: the newest member is not the interesting one. Here the newest hop
     * is a sub-agent call and the member carrying the conversation is older, so "newest N" would hand the
     * transform the wrong body. {@code prefer_sql} names the chain entry and the non-matching members are
     * excluded even though the row cap was nowhere near reached — which is why that counts as truncation.
     */
    @Test
    void memberSelectPrefersTheDeclaredCandidatesOverTheNewest() throws Exception {
        seedHop("71111111-1111-1111-1111-111111111111", "chat-a", "2020-01-01 00:00:01.000", "entry", null, 40);
        seedHop("72222222-2222-2222-2222-222222222222", "chat-a", "2020-01-01 00:00:02.000", "sub-agent", "up", 2);
        seedHop("73333333-3333-3333-3333-333333333333", "chat-a", "2020-01-01 00:00:03.000", "sub-agent-2", "up", 2);
        String id = createBodyPipeline("{\"prefer_sql\":\"parent_deployment IS NULL\",\"limit\":2}");

        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/group-rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_key\":\"chat-a\"}"))
                .andExpect(status().isOk())
                // only one member satisfies the preference, so it is the whole candidate set — the limit of 2
                // is an upper bound on candidates, not a quota to fill from the members it excluded
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.request_body']").value("entry"));
    }

    /**
     * A preference no member satisfies must not empty the group. An empty group is indistinguishable from one
     * that has aged out of the source, so a pipeline preferring a chain entry still has to label a conversation
     * that has none — a common shape, not an edge case.
     */
    @Test
    void memberSelectFallsBackToEveryMemberWhenNonePreferred() throws Exception {
        seedHop("81111111-1111-1111-1111-111111111111", "chat-a", "2020-01-01 00:00:01.000", "older", "up", 9);
        seedHop("82222222-2222-2222-2222-222222222222", "chat-a", "2020-01-01 00:00:02.000", "newer", "up", 9);
        String id = createBodyPipeline("{\"prefer_sql\":\"parent_deployment IS NULL\",\"limit\":2}");

        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/group-rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_key\":\"chat-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                // every member returned, so nothing was narrowed and nothing was capped
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.request_body']").value("older"))
                .andExpect(jsonPath("$.rows[1]['dial_usage_log_payload.request_body']").value("newer"));
    }

    /**
     * The ranking, and its stability. {@code order_by} outranks the source's total order, so the member with
     * the most request messages wins over the newest; and because the total order is appended as a tiebreak,
     * re-assembling an unchanged group selects the same member rather than an arbitrary one of the tied
     * candidates — which is what stops a re-judged group from flipping its label for no reason.
     */
    @Test
    void memberSelectOrdersCandidatesAndBreaksTiesStably() throws Exception {
        seedHop("91111111-1111-1111-1111-111111111111", "chat-a", "2020-01-01 00:00:01.000", "richest", "up", 85);
        seedHop("92222222-2222-2222-2222-222222222222", "chat-a", "2020-01-01 00:00:02.000", "tied-older", "up", 7);
        seedHop("93333333-3333-3333-3333-333333333333", "chat-a", "2020-01-01 00:00:03.000", "tied-newer", "up", 7);
        String id = createBodyPipeline("{\"order_by\":[{\"column\":\"number_request_messages\","
                + "\"direction\":\"desc\"}],\"limit\":2}");

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/group-rows").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"group_key\":\"chat-a\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows", hasSize(2)))
                    .andExpect(jsonPath("$.truncated").value(true))
                    // ranked by messages, so the 85-message member is kept over the two newer 7-message ones;
                    // the tie between those two is broken by the total order, keeping the newer — and both
                    // are returned ascending, as every other path returns them
                    .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.request_body']").value("richest"))
                    .andExpect(jsonPath("$.rows[1]['dial_usage_log_payload.request_body']").value("tied-newer"));
        }
    }

    /**
     * Governance for the policy, at registration rather than at read time: the limit is mandatory and bounded,
     * the columns must resolve on the read source, and the whole object is meaningless off a group trigger.
     */
    @Test
    void memberSelectIsValidatedAtRegistration() throws Exception {
        final String idle = "{\"idle\":\"10m\"}";

        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("chat_id", idle, null, "{\"limit\":0}")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("member_select.limit must be between 1")));

        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("chat_id", idle, null,
                                "{\"prefer_sql\":\"total_tokens > 0\"}")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("member_select requires a limit")));

        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("chat_id", idle, null,
                                "{\"order_by\":[{\"column\":\"no_such_column\","
                                        + "\"direction\":\"desc\"}],\"limit\":1}")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("is not a declared column of source")));

        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("chat_id", idle, null,
                                "{\"prefer_sql\":\"total_tokens >\",\"limit\":1}")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"));
    }

    /** A policy is only meaningful where there are members to choose among. */
    @Test
    void memberSelectIsRejectedForRowGrainPipelines() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"row-pipeline\"," + groupTransform() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                                + "\"trigger\":{\"kind\":\"schedule\",\"member_select\":{\"limit\":1}},"
                                + "\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("trigger.member_select is only valid for a group pipeline")));
    }

    /** The policy round-trips through the registry and the sync feed, which is how the caller ever sees it. */
    @Test
    void memberSelectRoundTripsThroughTheRegistry() throws Exception {
        String id = createBodyPipeline("{\"prefer_sql\":\"parent_deployment IS NULL\","
                + "\"order_by\":[{\"column\":\"number_request_messages\",\"direction\":\"desc\"}],\"limit\":2}");

        mockMvc.perform(get("/v1/pipelines/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trigger.member_select.prefer_sql").value("parent_deployment IS NULL"))
                .andExpect(jsonPath("$.trigger.member_select.order_by[0].column").value("number_request_messages"))
                .andExpect(jsonPath("$.trigger.member_select.order_by[0].direction").value("desc"))
                .andExpect(jsonPath("$.trigger.member_select.limit").value(2));

        // A pipeline that declares none reports none, rather than an invented default the caller would have to
        // distinguish from its own declaration. Asserted by replacing this pipeline rather than adding a second,
        // since a target enrichment admits only one — and PUT being a full replacement is what makes an
        // omitted member_select clear the stored one.
        mockMvc.perform(patch("/v1/pipelines/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"conv-select-pipeline\","
                                + groupTransform("body", "dial_usage_log_payload.request_body") + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                                + "\"trigger\":{\"kind\":\"group\",\"group_by\":\"chat_id\","
                                + "\"ready_when\":{\"idle\":\"10m\"}},"
                                + "\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trigger.member_select").doesNotExist());
    }

    /**
     * The capability's other worked example, now expressible: the enrichment hangs off
     * {@code dial_usage_log} at the conversation grain, but its label is derived from the ratings in
     * {@code rate_analytics} — a different table, which carries the same {@code chat_id} key. Both
     * pipeline-scoped reads (the dirty-mark scan and the group assembly) follow the declared read source.
     */
    @Test
    void groupPipelineReadsTheSourceItDeclares() throws Exception {
        execute("TRUNCATE TABLE rate_analytics");
        execute("INSERT INTO rate_analytics (request_time, _ingested_at, project_id, trace_id, "
                + "core_span_id, deployment, user_hash, chat_id, response_id, rate, comment) VALUES "
                + "('2026-01-01 00:00:00.000','2020-01-01 00:00:01.000','p','t1','s1','d','u','chat-a','r1',1,NULL),"
                + "('2026-01-01 00:00:01.000','2020-01-01 00:00:02.000','p','t2','s2','d','u','chat-a','r2',1,NULL),"
                + "('2026-01-01 00:00:02.000','2020-01-01 00:00:03.000','p','t3','s3','d','u','chat-b','r3',1,NULL)");
        try {
            String id = JsonPath.read(mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"conv-rate-pipeline\"," + groupTransform() + ","
                                    + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                                    + "\"inputs\":[\"rate_analytics\"],"
                                    + "\"trigger\":{\"kind\":\"group\",\"group_by\":\"chat_id\","
                                    + "\"ready_when\":{\"idle\":\"10m\"}},"
                                    + "\"filter\":\"rate > 0\","
                                    + "\"enabled\":true}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.inputs[0]").value("rate_analytics"))
                    .andReturn().getResponse().getContentAsString(), "$.name");

            // The dirty-mark scan pages rate_analytics, carrying the group key and that table's version
            // column — not dial_usage_log's rows, which is what the derivation would have forced.
            mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"limit\":10}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows", hasSize(3)))
                    .andExpect(jsonPath("$.rows[0].chat_id").value("chat-a"))
                    .andExpect(jsonPath("$.rows[0].total_tokens").doesNotExist());

            // Group assembly resolves the same read source: chat-a's two ratings, chat-b's excluded.
            String members = mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/group-rows")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"group_key\":\"chat-a\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows", hasSize(2)))
                    .andExpect(jsonPath("$.truncated").value(false))
                    .andReturn().getResponse().getContentAsString();
            List<String> keys = JsonPath.read(members, "$.rows[*].chat_id");
            assertThat(keys).containsOnly("chat-a");

            // The enrichment's own attachment is untouched: it still hangs off dial_usage_log and joins on it.
            mockMvc.perform(get("/v1/tables/" + ENRICHMENT))
                    .andExpect(jsonPath("$.source_table").value(SOURCE));
        } finally {
            execute("TRUNCATE TABLE rate_analytics");
        }
    }

    @Test
    void unknownGroupReturnsAnEmptyPage() throws Exception {
        String id = createGroupPipeline("{\"idle\":\"10m\"}", null);
        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/group-rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_key\":\"no-such-chat\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(0)))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void groupsFetchOnNonGroupPipelineIsRejected() throws Exception {
        String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        // The row-grain template names one placeholder, so the transform must bind it: at
                        // row grain every input is a placeholder and every placeholder an input.
                        .content("{\"name\":\"conv-row-pipeline2\"," + rowTransform() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(content, "$.name");

        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/group-rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_key\":\"chat-a\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("is not a group pipeline")));
    }

    @Test
    void groupsFetchOnAnUnknownPipelineIsNotFound() throws Exception {
        mockMvc.perform(runnerPost("/v1/pipelines/no-such-pipeline/group-rows")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"group_key\":\"chat-a\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("unknown_pipeline"));
    }

    // ------------------------------------------------------------ group provenance

    /**
     * The group pair of provenance columns, which is what makes a group label's input identifiable after
     * the fact: {@code group_version} says which version of the group was judged, and {@code truncated}
     * says whether that input was complete. Without them a label computed from a trimmed group reads
     * exactly like one computed from a whole group.
     */
    @Test
    void groupProvenanceIsWrittenAndQueryable() throws Exception {
        seedRow("61111111-1111-1111-1111-111111111111", "chat-a", "2020-01-01 00:00:01.000", 100, "chat");
        String id = createGroupPipeline("{\"idle\":\"10m\"}", null);
        // No PATCH adding group_version/truncated: defining the enrichment's schema injects all five
        // provenance columns, so they are already there — and adding them would be a duplicate 422.

        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"chat_id\":\"chat-a\",\"topic\":\"billing\","
                                + "\"group_version\":1577836801000,\"truncated\":true}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(1));

        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"dial_usage_log\",\"mode\":\"row\","
                                + "\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"chat_id\"}},"
                                + "{\"expr\":{\"type\":\"field\",\"name\":\"conv_label.group_version\"}},"
                                + "{\"expr\":{\"type\":\"field\",\"name\":\"conv_label.truncated\"}}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0]['conv_label.group_version']").value(1577836801000L))
                .andExpect(jsonPath("$.rows[0]['conv_label.truncated']").value(true));
    }

    /** A target that declares neither group column still enriches: absent provenance is skipped, not fatal. */
    @Test
    void groupProvenanceAbsentFromTheTargetIsSkipped() throws Exception {
        String id = createGroupPipeline("{\"idle\":\"10m\"}", null);
        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"chat_id\":\"chat-a\",\"topic\":\"billing\","
                                + "\"group_version\":1577836801000,\"truncated\":false}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(1));
    }

    // ------------------------------------------------------------ the join

    @Test
    void groupEnrichmentJoinsOntoTheSourceWithoutInflatingRows() throws Exception {
        seedRow("41111111-1111-1111-1111-111111111111", "chat-a", "2020-01-01 00:00:01.000", 100, "chat");
        seedRow("42222222-2222-2222-2222-222222222222", "chat-a", "2020-01-01 00:00:02.000", 200, "chat");
        seedRow("43333333-3333-3333-3333-333333333333", "chat-b", "2020-01-01 00:00:03.000", 300, "chat");
        String id = createGroupPipeline("{\"idle\":\"10m\"}", null);

        // One enrichment row per group key, written through the unchanged pipeline-scoped write-back.
        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"chat_id\":\"chat-a\",\"topic\":\"billing\"}]}"))
                .andExpect(status().isOk());

        // The 0..1 keyed lookup must not inflate the three source rows, and the label resolves per group.
        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"dial_usage_log\",\"mode\":\"row\","
                                + "\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"event_id\"}},"
                                + "{\"expr\":{\"type\":\"field\",\"name\":\"chat_id\"}},"
                                + "{\"expr\":{\"type\":\"field\",\"name\":\"conv_label.topic\"}}],"
                                + "\"sort\":[{\"field\":\"event_id\",\"dir\":\"asc\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(3)))
                .andExpect(jsonPath("$.rows[0]['conv_label.topic']").value("billing"))
                .andExpect(jsonPath("$.rows[1]['conv_label.topic']").value("billing"))
                .andExpect(jsonPath("$.rows[2]['conv_label.topic']").value(nullValue()));
    }
}
