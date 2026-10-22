package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.enrichment.support.RunnerContract;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.jayway.jsonpath.JsonPath;
import net.minidev.json.JSONArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import static com.epam.aidial.analytics.integration.RunnerCalls.runnerPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End to end, the lifecycle of a pipeline whose declaration is authored over several requests (capability
 * {@code pipeline-registry}, "The declaration gate runs when a write arms the pipeline, not when it is
 * stored") against real PostgreSQL and ClickHouse.
 *
 * <p>What it pins, in the order an author meets it: a registration of identity and target alone is stored
 * disabled; every read of that draft answers, except a compiled read, which refuses naming what is missing;
 * a draft save may omit {@code target} and keeps it; preview and arming refuse with the next missing
 * member, one at a time; the four runner data-plane endpoints refuse an incomplete declaration after the
 * contract and existence checks and before any statement, and the two writing ones refuse any disabled
 * pipeline with 409; materialize re-checks the row-grain rule over an enabled but invalid declaration; a derived output mapping survives a draft save that cannot re-derive
 * it, and still protects the columns it names; and a draft is cascaded away with its table.
 *
 * <p>Uses {@code dial_usage_log}, a seeded scannable source, as every Class-1 test here does, and shares their
 * property set so the Spring context is shared too. Requires Docker.
 */
@TestPropertySource(properties = {AbstractClickHouseIntegrationTest.SCAN_SAFETY_LAG_OFF,
        AbstractClickHouseIntegrationTest.GROUP_FETCH_MAX_BYTES_4KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_WINDOW_LIMIT_3,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_MEMORY_1KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_SPILL_OFF})
class DraftPipelineIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String PIPELINES = "/v1/pipelines";
    private static final String SOURCE = "dial_usage_log";

    /** Keyed on the source's identity column: a Class-1 target. */
    private static final String LABELS = "dr_label";
    /** Keyed on {@code chat_id}, coarser than the source's rows: a group target, never a Class-1 one. */
    private static final String COARSE = "dr_coarse";
    /** A rollup target, written {@code upsert_by_key} and ordered by {@code deployment}. */
    private static final String ROLLUP = "dr_rollup";
    private static final String STALE_MAPPING = "stored output mapping was derived from a transform it no longer declares";

    @Autowired
    private PipelineRepository pipelineRepository;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        // The shared property set caps an aggregate preview at 1 KB, and the sample aggregates the whole
        // input — so rows another test left in dial_usage_log would make the previews here order-dependent.
        truncate();
        IntegrationTables.enrichment(mockMvc, LABELS, SOURCE, "event_id",
                "[{\"source_name\":\"body_model\",\"name\":\"body_model\",\"type\":\"string\"}]");
        IntegrationTables.enrichment(mockMvc, COARSE, SOURCE, "chat_id",
                "[{\"source_name\":\"body_model\",\"name\":\"body_model\",\"type\":\"string\"}]");
        IntegrationTables.upsertSource(mockMvc, ROLLUP,
                "[{\"source_name\":\"deployment\",\"name\":\"deployment\",\"type\":\"string\"},"
                        + "{\"source_name\":\"call_count\",\"name\":\"call_count\",\"type\":\"long\"}]",
                "[\"deployment\"]");
    }

    @AfterEach
    void tearDown() throws Exception {
        // Each table delete cascades the pipeline targeting it, drafts included.
        mockMvc.perform(delete("/v1/tables/" + LABELS));
        mockMvc.perform(delete("/v1/tables/" + COARSE));
        mockMvc.perform(delete("/v1/tables/" + ROLLUP));
    }

    private static String sqlTransform(String column) {
        return Transforms.sql().output(column, "deployment").member();
    }

    private ResultActions register(String body) throws Exception {
        return mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions patchPipeline(String name, String body) throws Exception {
        return mockMvc.perform(patch(PIPELINES + "/" + name).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void registerDraft(String name, String kind, String target) throws Exception {
        register("{\"name\":\"" + name + "\",\"kind\":\"" + kind + "\",\"target\":\"" + target + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    // --- the authoring loop ---------------------------------------------------------------------------

    @Test
    void anEnrichPipelineIsAuthoredInStagesAndRefusedAtEachStepByWhatIsMissing() throws Exception {
        register("{\"name\":\"dr-loop\",\"kind\":\"enrich\",\"target\":\"" + LABELS + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.transform").doesNotExist())
                .andExpect(jsonPath("$.trigger").doesNotExist());

        // Every read of a draft answers — the authored one singly and listed — except a compiled read,
        // which refuses rather than serving empty members, and a compiled listing, which omits it.
        mockMvc.perform(get(PIPELINES + "/dr-loop")).andExpect(status().isOk());
        mockMvc.perform(get(PIPELINES + "?kind=enrich"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[?(@.name=='dr-loop')]", hasSize(1)));
        mockMvc.perform(get(PIPELINES)).andExpect(status().isOk());
        mockMvc.perform(get(PIPELINES + "/dr-loop?view=compiled"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("transform")));
        mockMvc.perform(get(PIPELINES + "?kind=enrich&view=compiled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[?(@.name=='dr-loop')]", hasSize(0)));

        // A draft save omitting `target` keeps it; the author does not restate it on every save.
        patchPipeline("dr-loop", "{" + sqlTransform("body_model") + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target").value(LABELS))
                .andExpect(jsonPath("$.enabled").value(false));

        // Arming is refused naming the next missing member, and preview says the same thing first.
        mockMvc.perform(post(PIPELINES + "/dr-loop/preview"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("trigger.kind is required")));
        patchPipeline("dr-loop", "{\"enabled\":true}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("trigger.kind is required")));
        assertThat(pipelineRepository.findById("dr-loop").orElseThrow().isEnabled()).isFalse();

        // Replace semantics: this save restates the transform, or it would be cleared.
        patchPipeline("dr-loop", "{" + sqlTransform("body_model") + ",\"trigger\":{\"kind\":\"schedule\"}}")
                .andExpect(status().isOk());
        mockMvc.perform(post(PIPELINES + "/dr-loop/preview")).andExpect(status().isOk());
        patchPipeline("dr-loop", "{\"enabled\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        mockMvc.perform(get(PIPELINES + "/dr-loop?view=compiled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outputs.length()").value(1));
    }

    @Test
    void anAggregatePipelineIsAuthoredInStagesAndItsReadRoundTrips() throws Exception {
        register("{\"name\":\"dr-rollup\",\"kind\":\"aggregate\",\"target\":\"" + ROLLUP + "\"}")
                .andExpect(status().isCreated())
                // Absent, not `[]`: a read sent back as a patch must not declare "none".
                .andExpect(jsonPath("$.measures").doesNotExist())
                .andExpect(jsonPath("$.group_by").doesNotExist())
                .andExpect(jsonPath("$.inputs").doesNotExist())
                .andExpect(jsonPath("$.trigger.kind").value("schedule"));

        // A draft that picked `schedule` and has not typed the cron: arming it is a named 422 — it used to
        // reach the scheduler's cron parse with a null and answer 500 — and preview says so first.
        patchPipeline("dr-rollup", "{\"enabled\":true}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("trigger.cron is required")));
        mockMvc.perform(post(PIPELINES + "/dr-rollup/preview"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("trigger.cron is required")));

        // An ungated write still derives: group_by is expanded from the target's ordering_key.
        patchPipeline("dr-rollup", "{\"inputs\":[\"" + SOURCE + "\"],"
                + "\"measures\":[{\"name\":\"call_count\",\"fn\":\"count\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group_by[0].column").value("deployment"))
                .andExpect(jsonPath("$.target").value(ROLLUP));

        // Round trip: the authored members read back, sent back unchanged, change nothing and invent nothing.
        final String read = mockMvc.perform(get(PIPELINES + "/dr-rollup"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        final String inputs = JsonPath.<JSONArray>read(read, "$.inputs").toJSONString();
        final String measures = JsonPath.<JSONArray>read(read, "$.measures").toJSONString();
        final String groupBy = JsonPath.<JSONArray>read(read, "$.group_by").toJSONString();
        patchPipeline("dr-rollup", "{\"inputs\":" + inputs + ",\"measures\":" + measures
                + ",\"group_by\":" + groupBy + ",\"trigger\":{\"kind\":\"schedule\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measures", hasSize(1)))
                .andExpect(jsonPath("$.group_by", hasSize(1)))
                .andExpect(jsonPath("$.trigger.cron").doesNotExist());

        patchPipeline("dr-rollup", "{\"inputs\":[\"" + SOURCE + "\"],"
                + "\"measures\":[{\"name\":\"call_count\",\"fn\":\"count\"}],"
                + "\"trigger\":{\"kind\":\"schedule\",\"cron\":\"0 0 * * * *\"}}")
                .andExpect(status().isOk());
        mockMvc.perform(post(PIPELINES + "/dr-rollup/preview")).andExpect(status().isOk());
        patchPipeline("dr-rollup", "{\"enabled\":true}").andExpect(status().isOk());
    }

    @Test
    void completeButInvalidAggregateRegistrationIsStoredAndRefusedAtPreviewAndArming() throws Exception {
        // group_by does not equal the target's ordering_key — refused at registration before, stored now.
        register("{\"name\":\"dr-bad-rollup\",\"kind\":\"aggregate\",\"target\":\"" + ROLLUP + "\","
                + "\"inputs\":[\"" + SOURCE + "\"],\"group_by\":[{\"column\":\"chat_id\"}],"
                + "\"measures\":[{\"name\":\"call_count\",\"fn\":\"count\"}],"
                + "\"trigger\":{\"kind\":\"schedule\",\"cron\":\"0 0 * * * *\"}}")
                .andExpect(status().isCreated());

        mockMvc.perform(post(PIPELINES + "/dr-bad-rollup/preview"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("ordered by [deployment] but group_by yields [chat_id]")));
        patchPipeline("dr-bad-rollup", "{\"enabled\":true}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("ordered by [deployment] but group_by yields [chat_id]")));
        assertThat(pipelineRepository.findById("dr-bad-rollup").orElseThrow().isEnabled()).isFalse();
    }

    @Test
    void anEnabledAggregatePreviewStillRunsTheWholeGate() throws Exception {
        // There is no lighter enabled path for an aggregate preview: a declaration that stops passing the
        // gate after it was armed is still refused there, where an enrich preview would preview it as stored.
        register("{\"name\":\"dr-armed-rollup\",\"kind\":\"aggregate\",\"target\":\"" + ROLLUP + "\","
                + "\"inputs\":[\"" + SOURCE + "\"],\"measures\":[{\"name\":\"call_count\",\"fn\":\"count\"}],"
                + "\"trigger\":{\"kind\":\"schedule\",\"cron\":\"0 0 * * * *\"}}")
                .andExpect(status().isCreated());
        patchPipeline("dr-armed-rollup", "{\"enabled\":true}").andExpect(status().isOk());
        jdbc.update("UPDATE pipeline SET spec = jsonb_set(spec, '{measures,0,fn}', '\"no_such_fn\"')"
                + " WHERE name = 'dr-armed-rollup'");

        mockMvc.perform(post(PIPELINES + "/dr-armed-rollup/preview"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("measure fn 'no_such_fn' is not a supported")));
    }

    @Test
    void draftSaveOmittingDeclaredReadSourceIsRefusedAndEmptyListDropsIt() throws Exception {
        register("{\"name\":\"dr-src\",\"kind\":\"enrich\",\"target\":\"" + LABELS + "\","
                + "\"inputs\":[\"" + SOURCE + "\"]," + sqlTransform("body_model") + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.inputs[0]").value(SOURCE));
        // A declared source other than the target's parent. Written past the API, since what is refused below
        // is decided before the declaration is validated, and no fixture table here would also pass the gate.
        jdbc.update("UPDATE pipeline SET input_table = ? WHERE name = 'dr-src'", ROLLUP);

        // Not a completeness rule, so it holds on a draft too: omitting inputs would silently repoint the
        // pipeline at its target's parent source.
        patchPipeline("dr-src", "{" + sqlTransform("body_model") + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("must restate 'inputs'")))
                .andExpect(jsonPath("$.message").value(containsString("parent source '" + SOURCE + "'")));
        assertThat(pipelineRepository.findById("dr-src").orElseThrow().getInputTable()).isEqualTo(ROLLUP);

        // Saying so explicitly is how a draft drops it.
        patchPipeline("dr-src", "{\"inputs\":[]," + sqlTransform("body_model") + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputs").doesNotExist());
        assertThat(pipelineRepository.findById("dr-src").orElseThrow().getInputTable()).isNull();
    }

    @Test
    void omittingDeclaredReadSourceThatIsTheTargetParentKeepsIt() throws Exception {
        register("{\"name\":\"dr-src-parent\",\"kind\":\"enrich\",\"target\":\"" + LABELS + "\","
                + "\"inputs\":[\"" + SOURCE + "\"]," + sqlTransform("body_model") + "}")
                .andExpect(status().isCreated());

        // What a client reading the compiled projection sends: there `inputs` is always resolved, so a source
        // equal to the target's parent looks followed, and the client leaves it out. Nothing is repointed, so
        // the declared source is kept rather than cleared, and the authored projection still names it.
        patchPipeline("dr-src-parent", "{\"filter\":\"deployment != ''\"," + sqlTransform("body_model") + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputs[0]").value(SOURCE));
        assertThat(pipelineRepository.findById("dr-src-parent").orElseThrow().getInputTable()).isEqualTo(SOURCE);

        // Repointing to a target with the same parent changes nothing about what the pipeline reads either.
        patchPipeline("dr-src-parent", "{\"target\":\"" + COARSE + "\"," + sqlTransform("body_model") + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target").value(COARSE))
                .andExpect(jsonPath("$.inputs[0]").value(SOURCE));
    }

    @Test
    void patchEchoingOnlyTheStoredTargetLeavesTheDraftDeclarationIntact() throws Exception {
        register("{\"name\":\"dr-echo\",\"kind\":\"enrich\",\"target\":\"" + LABELS + "\","
                + sqlTransform("body_model") + "}")
                .andExpect(status().isCreated())
                // A draft that has authored no trigger serves none, rather than `{}` a patch would send back.
                .andExpect(jsonPath("$.trigger").doesNotExist());
        final long generation = pipelineRepository.findById("dr-echo").orElseThrow().getGeneration();

        // An echo, like name and kind: not a declaration, so it clears nothing and changes nothing.
        patchPipeline("dr-echo", "{\"name\":\"dr-echo\",\"kind\":\"enrich\",\"target\":\"" + LABELS + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transform.outputs.body_model").exists());
        assertThat(pipelineRepository.findById("dr-echo").orElseThrow().getGeneration()).isEqualTo(generation);
    }

    @Test
    void targetEchoWithEnabledArmsCompleteDraftThroughTheEnableOnlyPath() throws Exception {
        register("{\"name\":\"dr-echo-arm\",\"kind\":\"enrich\",\"target\":\"" + LABELS + "\","
                + sqlTransform("body_model") + ",\"trigger\":{\"kind\":\"schedule\"}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enabled").value(false));

        // The echoed target is not a declaration, so this arms the stored declaration rather than replacing
        // it with one that has only a target — which would clear the transform and be refused at the gate.
        patchPipeline("dr-echo-arm", "{\"target\":\"" + LABELS + "\",\"enabled\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.target").value(LABELS))
                .andExpect(jsonPath("$.transform.outputs.body_model").exists())
                .andExpect(jsonPath("$.trigger.kind").value("schedule"));
    }

    @Test
    void partialDeclarationPatchOfAnEnabledEnrichPipelineIsRefused() throws Exception {
        register("{\"name\":\"dr-armed\",\"kind\":\"enrich\",\"target\":\"" + LABELS + "\","
                + sqlTransform("body_model") + ",\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enabled").value(true));

        // No `enabled` in the patch, so it leaves the pipeline armed and must carry the whole declaration.
        patchPipeline("dr-armed", "{\"advanced\":{\"rate_rpm\":5}}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("must supply transform and trigger")));
        mockMvc.perform(get(PIPELINES + "/dr-armed"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.trigger.kind").value("schedule"));
    }

    @Test
    void partialPatchOfPausedPipelineIsRefusedRatherThanErasingItsDeclaration() throws Exception {
        register("{\"name\":\"dr-paused\",\"kind\":\"enrich\",\"target\":\"" + LABELS + "\","
                + sqlTransform("body_model") + ",\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}")
                .andExpect(status().isCreated());
        patchPipeline("dr-paused", "{\"enabled\":false}").andExpect(status().isOk());
        final long generation = pipelineRepository.findById("dr-paused").orElseThrow().getGeneration();

        // Nothing stored tells a paused pipeline from a draft, so without the refusal this 200s and clears
        // the transform's outputs and the trigger the pipeline ran with.
        patchPipeline("dr-paused", "{\"advanced\":{\"rate_rpm\":5}}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("would leave its complete declaration"
                        + " incomplete, missing transform.type, transform.outputs, trigger.kind")));
        mockMvc.perform(get(PIPELINES + "/dr-paused"))
                .andExpect(jsonPath("$.transform.outputs.body_model").exists())
                .andExpect(jsonPath("$.trigger.kind").value("schedule"));
        assertThat(pipelineRepository.findById("dr-paused").orElseThrow().getGeneration()).isEqualTo(generation);
    }

    // --- the data plane -------------------------------------------------------------------------------

    @Test
    void theFourDataPlaneEndpointsRefuseDraftAfterTheContractAndExistenceChecks() throws Exception {
        registerDraft("dr-dp", "enrich", LABELS);

        mockMvc.perform(runnerPost(PIPELINES + "/dr-dp/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("has no 'transform' in its stored declaration")));
        mockMvc.perform(runnerPost(PIPELINES + "/dr-dp/group-rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_key\":\"c1\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("has no 'transform' in its stored declaration")));
        // The two WRITING endpoints refuse any disabled pipeline, draft or not: a disabled pipeline writes
        // to its target by no route. A state conflict, so 409, as a disabled aggregate's refresh answers.
        mockMvc.perform(runnerPost(PIPELINES + "/dr-dp/materialize").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("pipeline_conflict"));
        mockMvc.perform(runnerPost(PIPELINES + "/dr-dp/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"00000000-0000-0000-0000-000000000001\","
                                + "\"body_model\":\"m\"}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("pipeline_conflict"));

        // A wrong contract version is still answered first, and an unknown name is still a 404.
        mockMvc.perform(post(PIPELINES + "/dr-dp/scan").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("runner_contract_required"));
        mockMvc.perform(post(PIPELINES + "/dr-dp/scan").header(RunnerContract.HEADER, "0")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
        mockMvc.perform(runnerPost(PIPELINES + "/dr-nope/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("unknown_pipeline"));
    }

    @Test
    void groupDraftWithNoGroupKeyIsRefusedNamingItRatherThanCrashing() throws Exception {
        register("{\"name\":\"dr-group\",\"kind\":\"enrich\",\"target\":\"" + COARSE + "\","
                + Transforms.llm().output("body_model", "The model.").member() + ","
                + "\"trigger\":{\"kind\":\"group\"}}")
                .andExpect(status().isCreated());

        mockMvc.perform(runnerPost(PIPELINES + "/dr-group/group-rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_key\":\"c1\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("trigger.group_by")));
        mockMvc.perform(runnerPost(PIPELINES + "/dr-group/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("trigger.group_by")));
    }

    @Test
    void materializeRefusesCompleteDraftKeyedMoreCoarselyThanItsSource() throws Exception {
        // Complete — every member its kind requires is present — but the target is keyed on chat_id while
        // the source's rows are keyed on event_id. Storable as a draft since the gate is deferred; without
        // the row-grain re-check the target's ReplacingMergeTree would collapse rows to arbitrary survivors.
        register("{\"name\":\"dr-coarse\",\"kind\":\"enrich\",\"target\":\"" + COARSE + "\","
                + sqlTransform("body_model") + ",\"trigger\":{\"kind\":\"schedule\"}}")
                .andExpect(status().isCreated());
        // Disabled, it is refused as disabled; and it cannot be armed through the API, because arming runs the
        // gate. So the row-grain re-check guards an ENABLED row that no longer passes the gate — reached
        // here by flipping the flag underneath the API, the way drift or a bypassing write would.
        mockMvc.perform(runnerPost(PIPELINES + "/dr-coarse/materialize").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
        patchPipeline("dr-coarse", "{\"enabled\":true}").andExpect(status().isUnprocessableContent());
        jdbc.update("UPDATE pipeline SET enabled = true WHERE name = 'dr-coarse'");

        mockMvc.perform(runnerPost(PIPELINES + "/dr-coarse/materialize").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("identity column 'event_id'")));
    }

    @Test
    void draftSaveThatCannotReDeriveKeepsTheMappingAndItsColumnsStayProtected() throws Exception {
        register("{\"name\":\"dr-keep\",\"kind\":\"enrich\",\"target\":\"" + LABELS + "\","
                + sqlTransform("body_model") + ",\"trigger\":{\"kind\":\"schedule\"}}")
                .andExpect(status().isCreated());
        assertThat(pipelineRepository.findById("dr-keep").orElseThrow().getSpec()).contains("body_model");

        // An output naming no column of the target beside the one still declared: the mapping cannot be
        // re-derived, so the stored binding for the output the transform still declares is kept.
        patchPipeline("dr-keep", "{" + Transforms.sql().output("body_model", "deployment")
                        .output("not_a_column", "deployment").member() + ",\"trigger\":{\"kind\":\"schedule\"}}")
                .andExpect(status().isOk());
        assertThat(pipelineRepository.findById("dr-keep").orElseThrow().getSpec())
                .as("the derived output_bindings survive the draft save")
                .contains("\"output_bindings\"").contains("\"column\": \"body_model\"");

        // The catalog therefore still sees the pipeline as a writer of the column.
        mockMvc.perform(patch("/v1/tables/" + LABELS + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"drop\":[\"body_model\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("dr-keep")));

        // And materialize treats the kept mapping as stale rather than guessing: it binds body_model, which
        // the transform still declares, but not the output it added — so which column that one writes is not
        // stated. Asked of an enabled row, since a disabled one is refused first.
        jdbc.update("UPDATE pipeline SET enabled = true WHERE name = 'dr-keep'");
        mockMvc.perform(runnerPost(PIPELINES + "/dr-keep/materialize").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString(STALE_MAPPING)));
    }

    @Test
    void draftSaveDropsTheBindingOfAnOutputTheTransformNoLongerDeclares() throws Exception {
        register("{\"name\":\"dr-rename\",\"kind\":\"enrich\",\"target\":\"" + LABELS + "\","
                + sqlTransform("body_model") + ",\"trigger\":{\"kind\":\"schedule\"}}")
                .andExpect(status().isCreated());

        // The only output now names no column, so nothing re-derives — and the body_model binding is not
        // carried, because no declared output writes that column any more.
        patchPipeline("dr-rename", "{" + sqlTransform("not_a_column") + ",\"trigger\":{\"kind\":\"schedule\"}}")
                .andExpect(status().isOk());
        assertThat(pipelineRepository.findById("dr-rename").orElseThrow().getSpec())
                .doesNotContain("\"column\": \"body_model\"");
    }

    /**
     * The deadlock this rule used to cause: a paused pipeline whose only output writes a column could neither
     * lose that output (a complete declaration may not become incomplete) nor let the column go (the
     * catalog refuses to drop a column a pipeline writes). Restating the transform with no outputs is a
     * deliberate edit, not an omission, so both now succeed in order.
     */
    @Test
    void pausedPipelineMayLoseItsLastOutputSoTheColumnCanBeDropped() throws Exception {
        register("{\"name\":\"dr-last\",\"kind\":\"enrich\",\"target\":\"" + LABELS + "\","
                + sqlTransform("body_model") + ",\"trigger\":{\"kind\":\"schedule\"}}")
                .andExpect(status().isCreated());
        mockMvc.perform(patch("/v1/tables/" + LABELS + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"add\":[{\"source_name\":\"note\",\"name\":\"note\",\"type\":\"string\","
                                + "\"nullable\":true}]}"))
                .andExpect(status().isOk());

        patchPipeline("dr-last", "{" + Transforms.sql().member() + ",\"trigger\":{\"kind\":\"schedule\"}}")
                .andExpect(status().isOk());

        mockMvc.perform(patch("/v1/tables/" + LABELS + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"drop\":[\"body_model\"]}"))
                .andExpect(status().isOk());
        // Arming still holds the declaration to completeness.
        patchPipeline("dr-last", "{\"enabled\":true}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"));
    }

    @Test
    void materializeRefusesEnabledPipelineWhoseBoundColumnNoLongerFitsItsExpression() throws Exception {
        // Armed through the API, so it passed the whole gate; the catalog row is then retyped underneath it,
        // the way drift after arming would leave it. Without the result-type re-check a string expression
        // would be inserted into a column the catalog now declares long.
        register("{\"name\":\"dr-retyped\",\"kind\":\"enrich\",\"target\":\"" + LABELS + "\","
                + sqlTransform("body_model") + ",\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enabled").value(true));
        jdbc.update("UPDATE column_mapping SET type = 'long' WHERE table_name = ? AND source_name = 'body_model'", LABELS);

        mockMvc.perform(runnerPost(PIPELINES + "/dr-retyped/materialize").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("yields string but target column 'body_model' is long")));
    }

    @Test
    void previewOfBareDraftReportsTheFirstProblemArmingReports() throws Exception {
        // Identity and target alone: no transform and no trigger. Arming asks the trigger first, so preview —
        // the author's diagnostic for what arming would say — must not lead with the missing transform.
        registerDraft("dr-bare", "enrich", LABELS);
        mockMvc.perform(post(PIPELINES + "/dr-bare/preview"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("trigger.kind is required")));
        patchPipeline("dr-bare", "{\"enabled\":true}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("trigger.kind is required")));
    }

    @Test
    void schemaPatchOfDraftTargetSucceedsWhenTheDraftCannotReDerive() throws Exception {
        register("{\"name\":\"dr-schema\",\"kind\":\"enrich\",\"target\":\"" + LABELS + "\","
                + sqlTransform("body_model") + ",\"trigger\":{\"kind\":\"schedule\"}}")
                .andExpect(status().isCreated());
        patchPipeline("dr-schema", "{" + Transforms.sql().output("body_model", "deployment")
                        .output("not_a_column", "deployment").member() + ",\"trigger\":{\"kind\":\"schedule\"}}")
                .andExpect(status().isOk());
        final long generation = pipelineRepository.findById("dr-schema").orElseThrow().getGeneration();

        // An unrelated column added to the draft's target. The draft's outputs do not resolve against it, and
        // the re-derivation that follows the ALTER must not refuse the patch after ClickHouse has applied it.
        mockMvc.perform(patch("/v1/tables/" + LABELS + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"add\":[{\"source_name\":\"note\",\"name\":\"note\",\"type\":\"string\","
                                + "\"nullable\":true}]}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM column_mapping WHERE table_name = ? AND source_name = ?",
                Long.class, LABELS, "note")).as("the catalog declares the column").isEqualTo(1L);
        assertThat(queryScalar("SELECT count() AS c FROM system.columns WHERE table = '" + LABELS
                + "' AND name = 'note'", "c")).as("ClickHouse has the column").isEqualTo("1");
        assertThat(pipelineRepository.findById("dr-schema").orElseThrow())
                .as("the stored mapping stands and nothing is bumped")
                .satisfies(stored -> assertThat(stored.getSpec()).contains("\"column\": \"body_model\""))
                .satisfies(stored -> assertThat(stored.getGeneration()).isEqualTo(generation));
    }

    // --- a target defined after its draft -------------------------------------------------------------

    /**
     * Registers a draft on a PENDING enrichment — no columns, so its output mapping derives to nothing and
     * is stored empty — then defines the enrichment's schema. Defining a schema re-derives no pipeline, so
     * the stored mapping stays empty while the declaration now resolves.
     */
    private void registerDraftBeforeItsTargetHasColumns(String table, String name, String transform)
            throws Exception {
        mockMvc.perform(post("/v1/tables").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + table + "\",\"type\":\"enrichment\",\"source_table\":\"" + SOURCE + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"));
        register("{\"name\":\"" + name + "\",\"kind\":\"enrich\",\"target\":\"" + table + "\","
                + transform + ",\"trigger\":{\"kind\":\"schedule\"}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(post("/v1/tables/" + table + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grain_key\":\"event_id\",\"columns\":"
                                + "[{\"source_name\":\"body_model\",\"name\":\"body_model\",\"type\":\"string\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
        assertThat(pipelineRepository.findById(name).orElseThrow().getSpec())
                .as("the stored mapping is still the empty one derived against no columns")
                .doesNotContain("\"column\": \"body_model\"");
    }

    @Test
    void sqlDraftPreviewSamplesTheMappingItsGateDerivedRatherThanTheStoredOne() throws Exception {
        final String table = "dr_late_sql";
        try {
            registerDraftBeforeItsTargetHasColumns(table, "dr-late-sql", sqlTransform("body_model"));

            // The gate derives the mapping in memory; the Class-1 sample must run over that, not reload the
            // stored empty one and refuse "must bind at least one output column" for what arming accepts.
            mockMvc.perform(post(PIPELINES + "/dr-late-sql/preview"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.columns[?(@.role=='output')].name", contains("body_model")))
                    .andExpect(jsonPath("$.rows").isArray());
            patchPipeline("dr-late-sql", "{\"enabled\":true}").andExpect(status().isOk());
        } finally {
            mockMvc.perform(delete("/v1/tables/" + table));
        }
    }

    @Test
    void llmDraftPreviewScansThroughTheMappingItsGateDerivedRatherThanTheStoredOne() throws Exception {
        final String table = "dr_late_llm";
        try {
            registerDraftBeforeItsTargetHasColumns(table, "dr-late-llm",
                    Transforms.llm().output("body_model", "The model.").member());

            // Reloading by name, the scan would refuse the stored empty mapping as "incomplete, missing output
            // mapping" — while arming the same declaration succeeds.
            // The mapping itself is the compiled read's to show; the preview's success is the claim here.
            mockMvc.perform(post(PIPELINES + "/dr-late-llm/preview"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.columns").isArray())
                    .andExpect(jsonPath("$.rows").isArray());
            patchPipeline("dr-late-llm", "{\"enabled\":true}").andExpect(status().isOk());
        } finally {
            mockMvc.perform(delete("/v1/tables/" + table));
        }
    }

    @Test
    void schemaPatchReDerivesDisabledLlmDraftWhoseOutputsNowResolveAndBumpsItsGeneration() throws Exception {
        // One output resolves and one names a column the target does not have yet: nothing derives.
        register("{\"name\":\"dr-rederive\",\"kind\":\"enrich\",\"target\":\"" + LABELS + "\","
                + Transforms.llm().output("body_model", "The model.").output("note", "A note.").member()
                + ",\"trigger\":{\"kind\":\"schedule\"}}")
                .andExpect(status().isCreated());
        final long generation = pipelineRepository.findById("dr-rederive").orElseThrow().getGeneration();
        assertThat(pipelineRepository.findById("dr-rederive").orElseThrow().getSpec()).doesNotContain("\"column\": \"note\"");

        // Adding the missing column lets the draft's mapping derive: it is rewritten, and — an llm pipeline —
        // its generation bumped, exactly as for an armed one.
        mockMvc.perform(patch("/v1/tables/" + LABELS + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"add\":[{\"source_name\":\"note\",\"name\":\"note\",\"type\":\"string\","
                                + "\"nullable\":true}]}"))
                .andExpect(status().isOk());

        assertThat(pipelineRepository.findById("dr-rederive").orElseThrow())
                .satisfies(stored -> assertThat(stored.getSpec())
                        .contains("\"column\": \"body_model\"").contains("\"column\": \"note\""))
                .satisfies(stored -> assertThat(stored.isEnabled()).isFalse())
                .satisfies(stored -> assertThat(stored.getGeneration()).isEqualTo(generation + 1));
    }

    // --- deletion -------------------------------------------------------------------------------------

    @Test
    void draftIsDeletedDirectlyAndCascadedAwayWithItsTable() throws Exception {
        registerDraft("dr-del", "enrich", LABELS);
        mockMvc.perform(delete(PIPELINES + "/dr-del")).andExpect(status().isNoContent());
        mockMvc.perform(get(PIPELINES + "/dr-del")).andExpect(status().isNotFound());

        registerDraft("dr-cascade", "aggregate", ROLLUP);
        mockMvc.perform(delete("/v1/tables/" + ROLLUP)).andExpect(status().isNoContent());
        mockMvc.perform(get(PIPELINES + "/dr-cascade")).andExpect(status().isNotFound());
    }
}
