package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the fold leaves behind, exercised through the API: a declaration folded from a definition
 * registered before the {@code outputs} shape, the round trip that must not lose it, the
 * optional-{@code prose} default, and the storable-but-not-armable line.
 *
 * <p>Each of these is a case the migration tests cannot reach, because they assert what the fold
 * <em>wrote</em> and these assert what the API does with it afterwards. The legacy fixture is written
 * straight into {@code pipeline.spec} for the same reason it is in the migration test: {@code POST}
 * refuses that shape — a new declaration authors {@code outputs} — so the only way to reach it is the way
 * the fold produces it. Requires Docker.
 */
@TestPropertySource(properties = {AbstractClickHouseIntegrationTest.SCAN_SAFETY_LAG_OFF,
        AbstractClickHouseIntegrationTest.GROUP_FETCH_MAX_BYTES_4KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_WINDOW_LIMIT_3,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_MEMORY_1KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_SPILL_OFF})
class FoldedDeclarationIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String SOURCE = "dial_usage_log";
    private static final String TARGET = "fd_labels";
    private static final String DESCRIBED = "fd_described";
    private static final String NAME = "fd-pipeline";

    @Autowired
    private PipelineRepository pipelines;

    @BeforeEach
    void setUp() throws Exception {
        IntegrationTables.enrichment(mockMvc, TARGET, SOURCE, "event_id",
                "[{\"source_name\":\"title\",\"name\":\"title\",\"type\":\"string\",\"nullable\":true}]");
        // A second target whose column carries a DESCRIPTION, so composition has something to default
        // an output's prose from.
        IntegrationTables.enrichment(mockMvc, DESCRIBED, SOURCE, "event_id",
                "[{\"source_name\":\"title\",\"name\":\"title\",\"type\":\"string\",\"nullable\":true,"
                        + "\"description\":\"The column's own description.\"}]");
    }

    @AfterEach
    void tearDown() throws Exception {
        mockMvc.perform(delete("/v1/pipelines/" + NAME));
        mockMvc.perform(delete("/v1/pipelines/fd-described-pipeline"));
        mockMvc.perform(delete("/v1/tables/" + TARGET));
        mockMvc.perform(delete("/v1/tables/" + DESCRIBED));
    }

    /** A pipeline in the current shape, to be rewritten into the folded legacy one where needed. */
    private String createPipeline(String name, String target, String transform) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"," + transform + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + target + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.name");
    }

    private static String llmTransform() {
        return Transforms.llm()
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"Body: {{body}}\"}]}")
                .input("body", "chat_id")
                .output("title", "A short title.")
                .member();
    }

    /**
     * Rewrites the stored declaration into the shape the fold produces from a pre-{@code outputs}
     * definition: {@code output_vars} and a stored {@code response_schema} instead of {@code outputs}.
     */
    private void foldLegacyShape(String name) {
        final PipelineEntity pipeline = pipelines.findById(name).orElseThrow();
        pipeline.setSpec("{\"transform\":{\"type\":\"llm\",\"model\":\"gpt-4o\","
                + "\"preset\":\"chat_completion\","
                + "\"request_template\":\"{\\\"messages\\\":[{\\\"role\\\":\\\"user\\\","
                + "\\\"content\\\":\\\"Body: {{body}}\\\"}]}\","
                + "\"input_bindings\":[{\"var\":\"body\",\"column\":\"chat_id\"}],"
                + "\"output_vars\":[{\"name\":\"title\",\"type\":\"string\"}],"
                + "\"response_schema\":{\"type\":\"object\",\"required\":[\"title\"],"
                + "\"properties\":{\"title\":{\"type\":\"string\"}},"
                + "\"additionalProperties\":false}},"
                + "\"output_bindings\":[{\"column\":\"title\",\"var\":\"title\"}]}");
        pipelines.save(pipeline);
    }

    /**
     * Rewrites the stored declaration into what a write served by a <b>pre-fold</b> ADAS pod leaves
     * behind: the {@code transform} block gone, and the transform's input bindings gone with it, because
     * that build's {@code EnrichSpec} has neither member and tolerates unknown properties. The derived
     * {@code output_bindings} survive, since they never moved off the top level.
     */
    private void stripTransform(String name) {
        final PipelineEntity pipeline = pipelines.findById(name).orElseThrow();
        pipeline.setSpec("{\"output_bindings\":[{\"column\":\"title\",\"var\":\"title\"}]}");
        pipelines.save(pipeline);
    }

    // --- a folded legacy declaration stays usable -----------------------------------------------------

    /**
     * The regression the relocated shape check is about: the evaluator's check required at least one
     * {@code outputs} entry, and a folded legacy declaration has none. Moving it verbatim would refuse
     * every such pipeline at its first read, patch or enable — which is every existing pipeline whose
     * resolved version predates {@code simplify-pipeline-authoring}. What the gate requires is at
     * least one output <b>resolved through the accessor</b>.
     */
    @Test
    void foldedLegacyDeclarationIsReadablePatchableAndEnablable() throws Exception {
        createPipeline(NAME, TARGET, llmTransform());
        foldLegacyShape(NAME);

        // Readable, on both projections.
        mockMvc.perform(get("/v1/pipelines/" + NAME + "?view=source"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transform.output_vars[0].name").value("title"))
                .andExpect(jsonPath("$.transform.outputs").doesNotExist());
        mockMvc.perform(get("/v1/pipelines/" + NAME + "?view=compiled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outputs.length()").value(1))
                .andExpect(jsonPath("$.response_schema.required[0]").value("title"));

        // Enablable: the arm re-runs the gate over the stored declaration, which is where the relocated
        // check would refuse it if it had been moved verbatim.
        mockMvc.perform(patch("/v1/pipelines/" + NAME).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    /**
     * The round trip that keeps a read-modify-write client from deleting a live prompt: read
     * {@code view=source}, send the declaration back, and nothing changes.
     *
     * <p>Server-owned members are stripped first — {@code generation}, {@code updated_at},
     * {@code created_at} and {@code state} are not accepted on a write, so the absolute "send the whole
     * body back" form is a 400 at the binding rather than a round trip.
     */
    @Test
    void foldedLegacyDeclarationRoundTripsThroughSourceAndPatch() throws Exception {
        createPipeline(NAME, TARGET, llmTransform());
        foldLegacyShape(NAME);
        final String storedBefore = pipelines.findById(NAME).orElseThrow().getSpec();

        final String source = mockMvc.perform(get("/v1/pipelines/" + NAME + "?view=source"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        final Map<String, Object> body = JsonPath.parse(source).read("$");
        body.keySet().removeIf(key -> Map.of("generation", 0, "updated_at", 0, "created_at", 0,
                "state", 0, "notes", 0).containsKey(key));

        mockMvc.perform(patch("/v1/pipelines/" + NAME).contentType(MediaType.APPLICATION_JSON)
                        .content(JsonPath.parse(body).jsonString()))
                .andExpect(status().isOk());

        // Byte-identical where it matters: the stored transform, including both legacy members.
        final String storedAfter = pipelines.findById(NAME).orElseThrow().getSpec();
        assertThat(normalize(storedAfter))
                .as("a source read sent back changes nothing, which is what keeps a client from "
                        + "silently dropping output_vars and rewriting a live prompt")
                .isEqualTo(normalize(storedBefore));
    }

    /** A compiled body sent back is refused on its derived members rather than accepted. */
    @Test
    void compiledBodySentBackIsRefusedOnItsDerivedMembers() throws Exception {
        createPipeline(NAME, TARGET, llmTransform());

        final String compiled = mockMvc.perform(get("/v1/pipelines/" + NAME + "?view=compiled"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        final Map<String, Object> body = JsonPath.parse(compiled).read("$");
        body.keySet().removeIf(key -> Map.of("generation", 0, "updated_at", 0, "created_at", 0,
                "state", 0, "notes", 0).containsKey(key));

        // 400 at the binding, naming the field: `response_schema` and `outputs` are derived members and no
        // request record declares them, so a client that echoed the wrong projection is told which one.
        mockMvc.perform(patch("/v1/pipelines/" + NAME).contentType(MediaType.APPLICATION_JSON)
                        .content(JsonPath.parse(body).jsonString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    /**
     * Clearing the stored schema on a declaration patch switches the pipeline to composition — the only
     * migration route off the legacy shape, and a real change of meaning, so it is asserted rather than
     * assumed.
     *
     * <p>It follows from a rule that already existed rather than a new one: a declaration patch supplies
     * the whole declaration, so the patched {@code transform} replaces the stored one wholesale and an
     * omitted member is cleared, not merged.
     */
    @Test
    void patchOmittingTheStoredSchemaClearsItAndSwitchesToComposition() throws Exception {
        createPipeline(NAME, TARGET, llmTransform());
        foldLegacyShape(NAME);
        mockMvc.perform(get("/v1/pipelines/" + NAME + "?view=source"))
                .andExpect(jsonPath("$.transform.response_schema").exists());

        mockMvc.perform(patch("/v1/pipelines/" + NAME).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + NAME + "\"," + llmTransform() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + TARGET + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"}}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/pipelines/" + NAME + "?view=source"))
                .andExpect(jsonPath("$.transform.response_schema").doesNotExist())
                .andExpect(jsonPath("$.transform.output_vars").doesNotExist())
                .andExpect(jsonPath("$.transform.outputs.title").value("A short title."));
        // Composed from the target and the new `outputs`, which is what the model is now held to.
        mockMvc.perform(get("/v1/pipelines/" + NAME + "?view=compiled"))
                .andExpect(jsonPath("$.response_schema.properties.title.description")
                        .value("A short title."));
    }

    /** A declaration carrying both output shapes is refused: the accessor would run one and ignore the other. */
    @Test
    void declarationCarryingBothOutputShapesIsRefused() throws Exception {
        createPipeline(NAME, TARGET, llmTransform());

        final String both = Transforms.llm()
                .requestTemplate("{{body}}")
                .input("body", "chat_id")
                .output("title", "A short title.")
                .outputVars("[{\"name\":\"title\",\"type\":\"string\"}]")
                .member();
        // A patch that ARMS the pipeline, so the gate runs; one leaving it disabled is a draft save and
        // stores the declaration, which preview and the arming patch then refuse the same way.
        mockMvc.perform(patch("/v1/pipelines/" + NAME).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + NAME + "\"," + both + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + TARGET + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("one output shape")));
    }

    /** {@code output_vars} on a POST is an unknown member, so a 400 naming the field rather than a 422. */
    @Test
    void outputVarsOnPostIsRefusedAtTheBinding() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"fd-legacy-post\","
                                + Transforms.llm().requestTemplate("{{body}}").input("body", "chat_id")
                                        .outputVars("[{\"name\":\"title\",\"type\":\"string\"}]").member()
                                + ",\"kind\":\"enrich\",\"target\":\"" + TARGET + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("output_vars")));
    }

    // --- the optional prose default -------------------------------------------------------------------

    /** A declared prose wins over the column's description; the column never overrides an author. */
    @Test
    void declaredProseWinsOverTheColumnDescription() throws Exception {
        createPipeline("fd-described-pipeline", DESCRIBED, Transforms.llm()
                .requestTemplate("{{body}}").input("body", "chat_id")
                .output("title", "The author's own prose.").member());

        mockMvc.perform(get("/v1/pipelines/fd-described-pipeline?view=compiled"))
                .andExpect(jsonPath("$.response_schema.properties.title.description")
                        .value("The author's own prose."));
    }

    /**
     * {@code null}, an empty object and a blank string all mean "default it" — one fact, one spelling —
     * and the default is the target column's own {@code description}.
     *
     * <p>None of the three is stored as prose, and none is echoed on either projection: a derived default
     * echoed onto the authored surface would be sent back by the next read-modify-write patch, become
     * authored, and silently stop tracking the column.
     */
    @Test
    void outputWithNoProseComposesTheColumnDescription() throws Exception {
        for (final String form : new String[] {"null", "{}", "\"\""}) {
            mockMvc.perform(delete("/v1/pipelines/fd-described-pipeline"));
            mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"fd-described-pipeline\","
                                    + Transforms.llm().requestTemplate("{{body}}")
                                            .input("body", "chat_id")
                                            .outputObject("title", form).member()
                                    + ",\"kind\":\"enrich\",\"target\":\"" + DESCRIBED + "\","
                                    + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":false}"))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/v1/pipelines/fd-described-pipeline?view=compiled"))
                    .andExpect(jsonPath("$.response_schema.properties.title.description")
                            .value("The column's own description."))
                    // Composition only. The authored surface echoes exactly what was sent — absent for
                    // `null` and `{}`, and the blank string for `""` — and in no case the column's
                    // description: a derived default echoed here would be sent back by the next
                    // read-modify-write patch, become authored, and silently stop tracking the column.
                    .andExpect(jsonPath("$.transform.outputs.title")
                            .value(org.hamcrest.Matchers.not("The column's own description.")));
            mockMvc.perform(get("/v1/pipelines/fd-described-pipeline?view=source"))
                    .andExpect(jsonPath("$.transform.outputs.title")
                            .value(org.hamcrest.Matchers.not("The column's own description.")));
        }
    }

    /**
     * Both blank composes <b>no</b> {@code description}, which is valid JSON Schema and is exactly what a
     * folded legacy declaration already produces — so it is storable and armable, and reported as a
     * preview note rather than refused.
     */
    @Test
    void bothBlankComposesNoDescriptionAndIsStillArmable() throws Exception {
        createPipeline(NAME, TARGET, Transforms.llm().requestTemplate("{{body}}")
                .input("body", "chat_id").outputObject("title", "null").member());

        mockMvc.perform(get("/v1/pipelines/" + NAME + "?view=compiled"))
                .andExpect(jsonPath("$.response_schema.properties.title.type").value("string"))
                .andExpect(jsonPath("$.response_schema.properties.title.description").doesNotExist());
        mockMvc.perform(patch("/v1/pipelines/" + NAME).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        // A note, not a refusal: the declaration is degraded, not broken, and the preview is where an
        // author finds that out.
        mockMvc.perform(post("/v1/pipelines/" + NAME + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[?(@.code=='output_without_description')].subject",
                        org.hamcrest.Matchers.contains("title")))
                .andExpect(jsonPath("$.notes[?(@.code=='output_without_description')].severity",
                        org.hamcrest.Matchers.contains("info")));
    }

    /** A stored schema suppresses composition entirely, so no column description is read at all. */
    @Test
    void storedSchemaSuppressesTheColumnDescription() throws Exception {
        createPipeline("fd-described-pipeline", DESCRIBED, Transforms.llm()
                .requestTemplate("{{body}}").input("body", "chat_id")
                .output("title", "A short title.")
                .responseSchema("{\"type\":\"object\",\"required\":[\"title\"],"
                        + "\"properties\":{\"title\":{\"type\":\"string\"}},"
                        + "\"additionalProperties\":false}")
                .member());

        mockMvc.perform(get("/v1/pipelines/fd-described-pipeline?view=compiled"))
                .andExpect(jsonPath("$.response_schema.properties.title.description").doesNotExist());
    }

    // --- storable, not yet armable --------------------------------------------------------------------

    /**
     * The one referential check that moved to <b>arm and preview</b>: every {@code {{placeholder}}} in
     * {@code request_template} resolving against {@code transform.inputs}.
     *
     * <p>An author legitimately saves a pipeline whose template is written and whose inputs are not yet
     * bound; refusing that write would make the registry unusable as a place to compose a declaration —
     * the "create == publish" trap the fold exists to remove, reappearing one field lower down. Nothing
     * new is <em>storable</em> as a result: {@code enabled: false} already meant "not running".
     */
    @Test
    void unboundPlaceholderIsStorableButNeitherArmableNorPreviewable() throws Exception {
        // No `inputs` at all, and a template full of placeholders. Accepted, disabled.
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + NAME + "\","
                                + Transforms.llm().requestTemplate("{{body}} and {{other}}")
                                        .output("title", "A short title.").member()
                                + ",\"kind\":\"enrich\",\"target\":\"" + TARGET + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enabled").value(false));

        // Arming it is refused, naming the placeholder — and the pipeline stays disabled.
        mockMvc.perform(patch("/v1/pipelines/" + NAME).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("body")));
        mockMvc.perform(get("/v1/pipelines/" + NAME))
                .andExpect(jsonPath("$.enabled").value(false));

        // And previewing it is refused the same way, rather than rendering an unsubstituted template.
        mockMvc.perform(post("/v1/pipelines/" + NAME + "/preview"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"));
    }

    /** A registration that arms in the same call is held to the check, so nothing armed skips it. */
    @Test
    void registrationThatArmsIsRefusedForAnUnboundPlaceholder() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + NAME + "\","
                                + Transforms.llm().requestTemplate("{{body}}")
                                        .output("title", "A short title.").member()
                                + ",\"kind\":\"enrich\",\"target\":\"" + TARGET + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("body")));
    }

    // --- a declaration a pre-fold pod stripped of its transform ---------------------------------------

    /**
     * A stored declaration with <b>no transform</b> is refused where it would do damage, and still
     * readable everywhere else.
     *
     * <p>This state is not reachable through any write this build serves — the gate refuses one, and the
     * fold migration asserts none is left behind. It is reachable through a <em>rolling</em> ADAS deploy:
     * the outgoing pod has no {@code transform} member and ignores unknown properties, so a write it
     * serves (an enabled-only patch is enough) re-stores the declaration without the block, and with the
     * transform's {@code input_bindings} emptied for the same reason. Until this guard the new build read
     * that back, manufactured an empty transform on the spot and carried on: a pipeline declaring no
     * model, no prompt and no outputs, which parses, validates and runs while doing nothing.
     *
     * <p>Refused per pipeline rather than at the parse, which is why the read below must keep working:
     * listing the registry is how an operator finds the damaged pipeline in order to re-declare it, and a
     * refusal in the codec would fail that list for every pipeline and take the sync feed with it.
     */
    @Test
    void declarationStrippedOfItsTransformIsRefusedRatherThanRunEmpty() throws Exception {
        createPipeline(NAME, TARGET, llmTransform());
        stripTransform(NAME);

        mockMvc.perform(get("/v1/pipelines/" + NAME + "?view=source"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/pipelines/" + NAME + "/preview"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                // A disabled preview reports what arming would, so the gate's wording rather than the
                // data plane's shared guard.
                .andExpect(jsonPath("$.message",
                        containsString("transform is required")))
                // The message has to name the cause and the repair, because nothing else will: this is a
                // state no caller created and no later write clears on its own.
                .andExpect(jsonPath("$.message",
                        containsString("pre-fold ADAS pod")));

        // And arming it is refused rather than storing the empty transform a read used to manufacture.
        mockMvc.perform(patch("/v1/pipelines/" + NAME).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"));
        assertThat(pipelines.findById(NAME).orElseThrow().isEnabled()).isFalse();
        assertThat(pipelines.findById(NAME).orElseThrow().getSpec())
                .as("the refused enable stored nothing, so the damaged row is not made worse")
                .doesNotContain("transform");
    }

    // --- the metadata-bump inversion ------------------------------------------------------------------

    /**
     * A {@code description}-touching metadata patch on an {@code llm} target <b>bumps</b> the targeting
     * pipeline's {@code generation} and {@code updated_at} — which inverts the shipped rule that a
     * metadata-only patch bumps nothing.
     *
     * <p>The rule's own justification was "an output's prose comes from the declaration rather than the
     * column", and the optional-prose default falsifies it: the column description is a live input to the
     * composed prompt, so a patch that edits it changes what the model is told. A consumer that never
     * re-read the feed would keep sending the old prompt. Both members move, because the feed selects on
     * {@code updated_at} — a bumped generation the feed would never return is no signal at all.
     *
     * <p><b>These are the first assertions on this path</b>, which is also why the old rule could be
     * inverted without a failing build to catch it: nothing asserted the no-bump behaviour either.
     */
    @Test
    void descriptionPatchOnLlmTargetBumpsTheTargetingPipeline() throws Exception {
        createPipeline("fd-described-pipeline", DESCRIBED, Transforms.llm()
                .requestTemplate("{{body}}").input("body", "chat_id")
                .output("title", "A short title.").member());
        final PipelineEntity before = pipelines.findById("fd-described-pipeline").orElseThrow();
        final long generationBefore = before.getGeneration();
        final var updatedBefore = before.getUpdatedAt();

        mockMvc.perform(patch("/v1/tables/" + DESCRIBED + "/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"update\":[{\"name\":\"title\","
                                + "\"description\":\"A revised column description.\"}]}"))
                .andExpect(status().isOk());

        final PipelineEntity after = pipelines.findById("fd-described-pipeline").orElseThrow();
        assertThat(after.getGeneration()).isGreaterThan(generationBefore);
        assertThat(after.getUpdatedAt()).isAfter(updatedBefore);
    }

    /**
     * Re-sending the description a column <b>already carries</b> bumps nothing, because nothing changed.
     *
     * <p>{@code Change.present} means "the caller supplied this member", not "the value differs", so a
     * condition testing presence alone makes every run of an idempotent authoring or seed script — the
     * ordinary way a catalog is kept in version control — bump the target's pipeline. A bump is not free:
     * it re-syncs the runner, stalls the rollups reading this enrichment, and drops whatever that pipeline
     * has in flight, because the runner keys its work-item ids on {@code generation}. Paying that on every
     * replay of a no-op write is a recurring cost for no signal, which is why the condition compares
     * against the stored value rather than the caller's intent to send one.
     */
    @Test
    void repatchingTheSameDescriptionBumpsNothing() throws Exception {
        createPipeline("fd-described-pipeline", DESCRIBED, Transforms.llm()
                .requestTemplate("{{body}}").input("body", "chat_id")
                .output("title", "A short title.").member());
        final long generationBefore =
                pipelines.findById("fd-described-pipeline").orElseThrow().getGeneration();

        // Byte-for-byte what setUp declared, which is what an idempotent re-post of the schema sends.
        mockMvc.perform(patch("/v1/tables/" + DESCRIBED + "/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"update\":[{\"name\":\"title\","
                                + "\"description\":\"The column's own description.\"}]}"))
                .andExpect(status().isOk());

        assertThat(pipelines.findById("fd-described-pipeline").orElseThrow().getGeneration())
                .isEqualTo(generationBefore);

        // And a real edit still bumps — the narrowing must not have turned the inversion off.
        mockMvc.perform(patch("/v1/tables/" + DESCRIBED + "/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"update\":[{\"name\":\"title\","
                                + "\"description\":\"Actually revised.\"}]}"))
                .andExpect(status().isOk());

        assertThat(pipelines.findById("fd-described-pipeline").orElseThrow().getGeneration())
                .isGreaterThan(generationBefore);
    }

    /**
     * The rest of a metadata patch still bumps <b>nothing</b>: a {@code display_name}, {@code tag},
     * {@code sensitive} or {@code heavy} change reaches neither the composed schema nor the derived output
     * mapping, so there is nothing for a consumer to re-read.
     */
    @Test
    void displayNameOnlyPatchStillBumpsNothing() throws Exception {
        createPipeline("fd-described-pipeline", DESCRIBED, Transforms.llm()
                .requestTemplate("{{body}}").input("body", "chat_id")
                .output("title", "A short title.").member());
        final long generationBefore =
                pipelines.findById("fd-described-pipeline").orElseThrow().getGeneration();

        mockMvc.perform(patch("/v1/tables/" + DESCRIBED + "/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"update\":[{\"name\":\"title\","
                                + "\"display_name\":\"Title\",\"tag\":\"label\"}]}"))
                .andExpect(status().isOk());

        assertThat(pipelines.findById("fd-described-pipeline").orElseThrow().getGeneration())
                .isEqualTo(generationBefore);
    }

    /**
     * A {@code description} patch on a <b>Class-1</b> target bumps nothing and preserves the materialized
     * position — the bump is {@code llm}-scoped, so no Class-1 mark is discarded by the widening.
     *
     * <p>That scoping is what bounds the cost of the inversion: a {@code sql} output carries its own
     * expression, no prose is composed for it, and a bump would discard
     * {@code materialized_through_version} for a change that cannot reach it.
     */
    @Test
    void descriptionPatchOnClassOneTargetBumpsNothing() throws Exception {
        createPipeline("fd-described-pipeline", DESCRIBED,
                Transforms.sql().output("title", "lower(chat_id)").member());
        final long generationBefore =
                pipelines.findById("fd-described-pipeline").orElseThrow().getGeneration();

        mockMvc.perform(patch("/v1/tables/" + DESCRIBED + "/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"update\":[{\"name\":\"title\","
                                + "\"description\":\"A revised column description.\"}]}"))
                .andExpect(status().isOk());

        final PipelineEntity after = pipelines.findById("fd-described-pipeline").orElseThrow();
        assertThat(after.getGeneration()).isEqualTo(generationBefore);
        assertThat(after.getMaterializedThroughVersion()).isNull();
    }

    /** And a patch on an enrichment no pipeline targets bumps nothing, because there is nothing to bump. */
    @Test
    void descriptionPatchOnUntargetedEnrichmentBumpsNothing() throws Exception {
        createPipeline(NAME, TARGET, llmTransform());
        final long generationBefore = pipelines.findById(NAME).orElseThrow().getGeneration();

        // DESCRIBED carries no pipeline in this test.
        mockMvc.perform(patch("/v1/tables/" + DESCRIBED + "/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"update\":[{\"name\":\"title\","
                                + "\"description\":\"A revised column description.\"}]}"))
                .andExpect(status().isOk());

        assertThat(pipelines.findById(NAME).orElseThrow().getGeneration()).isEqualTo(generationBefore);
    }

    /** `spec` is `jsonb`, so PostgreSQL returns it re-rendered with its own spacing. */
    private static String normalize(String json) {
        return json.replace(" ", "").replace("\n", "");
    }
}
