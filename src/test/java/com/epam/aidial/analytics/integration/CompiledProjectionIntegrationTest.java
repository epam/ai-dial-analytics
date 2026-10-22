package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The load-bearing assertion of this change: what {@code view=compiled} serves an Enrichment Runner.
 *
 * <p><b>This is not a formatting test.</b> The runner's output mapper iterates the pipeline's output
 * bindings, so a pipeline served an <em>empty</em> binding list yields an empty column map — and the
 * group worker then still writes the grain key plus the five provenance columns and still stamps the
 * group as computed. Against a {@code ReplacingMergeTree} keyed on the grain that is an upsert of a row
 * whose label columns are all NULL <b>over an existing, already-paid-for label</b>, never retried
 * because the stamp landed, with the model call made and charged first. Neither ADAS nor the runner
 * rejects such a row: the write allow-list is {@code {bindings} ∪ {grain} ∪ {provenance}}, so a row
 * carrying only grain and provenance is legal by construction. So a failure here is data loss averted,
 * and the binding count is asserted explicitly rather than only diffed — a fixture that regressed to
 * an empty list on both sides at once would pass a diff.
 *
 * <p>The two stored output shapes are exercised side by side, because they coexist indefinitely: a
 * declaration folded from a definition registered before the {@code outputs} shape keeps its
 * {@code output_vars} and its stored {@code response_schema}, and one authored since carries
 * {@code outputs}. What the feed serves is the same either way — the resolved response schema, and one
 * {@code outputs} entry per mapped column with the transform folded in — which is the point of resolving
 * both shapes behind one accessor rather than teaching consumers about the split. <b>The legacy shape is
 * covered by fixture and nothing else:</b> a deployment whose declarations have all been re-authored in
 * the {@code outputs} shape would never exercise a broken {@code outputs}-else-{@code output_vars}
 * accessor.
 *
 * <p><b>The feed carries one vocabulary, and it is the authored one.</b> There is no inlined
 * {@code evaluator} object: every fact it carried is on the entry already — the pipeline's own
 * {@code name}, {@code generation} and {@code updated_at}, plus the {@code transform}'s members — except
 * the resolved schema, which is the derived top-level {@code response_schema}. Requires Docker.
 */
class CompiledProjectionIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String SOURCE = "cp_events";
    private static final String TARGET = "cp_labels";

    @Autowired
    private PipelineRepository pipelines;

    @BeforeEach
    void setUp() throws Exception {
        IntegrationTables.source(mockMvc, SOURCE,
                "[{\"source_name\":\"event_id\",\"name\":\"event_id\",\"type\":\"string\",\"nullable\":false},"
                        + "{\"source_name\":\"seen_at\",\"name\":\"seen_at\",\"type\":\"timestamp\",\"nullable\":false},"
                        + "{\"source_name\":\"body\",\"name\":\"body\",\"type\":\"string\"}]",
                "[\"event_id\"]", "event_id", "seen_at");
        IntegrationTables.enrichment(mockMvc, TARGET, SOURCE, "event_id",
                "[{\"source_name\":\"title\",\"name\":\"title\",\"type\":\"string\",\"nullable\":true},"
                        + "{\"source_name\":\"mood\",\"name\":\"mood\",\"type\":\"enum\","
                        + "\"enum_values\":[\"low\",\"mid\",\"high\"],\"nullable\":true}]");
    }

    @AfterEach
    void tearDown() throws Exception {
        mockMvc.perform(delete("/v1/pipelines/cp-pipeline"));
        mockMvc.perform(delete("/v1/tables/" + TARGET));
        mockMvc.perform(delete("/v1/tables/" + SOURCE));
    }

    /**
     * The <b>current</b> {@code llm} transform: prose outputs, including the one form that carries a real
     * response transform. Declared title-then-mood, which is what the composed schema must carry — and the
     * reverse of the folded legacy fixture's stored order.
     */
    private static String transform() {
        return Transforms.llm()
                .model("test-model")
                .preset(null)
                .requestTemplate("{\"messages\":[{\"role\":\"user\","
                        + "\"content\":\"Body: {{body}}\"}]}")
                .input("body", "body")
                .output("title", "A short title for the event.")
                .outputObject("mood", "{\"prose\":\"How the event reads.\","
                        + "\"jsonata\":\"(mood = \\\"none\\\") ? null : mood\"}")
                .member();
    }

    /**
     * Rewrites the pipeline's stored {@code transform} into the <b>legacy</b> output shape the fold
     * migration produces from a definition registered before {@code outputs} existed: no {@code outputs},
     * {@code output_vars} populated with per-output logical types, and a stored {@code response_schema}
     * whose property order is the one {@code jsonb} imposed.
     *
     * <p>Written through the repository rather than the API, and that is the point twice over. It is the
     * shape a {@code POST} refuses — a new declaration authors {@code outputs} — so the API cannot create
     * it; and it is the shape the migration carries verbatim rather than converting, because the row with
     * {@code output_vars} and no stored schema calls the model unconstrained today and giving it an
     * {@code outputs} array would send a {@code response_format} where none was sent before.
     *
     * <p>Stored with {@code mood} before {@code title} and carrying its own enum order — deliberately NOT
     * what composition would produce. A stored schema keeps winning while it is present, so this is what
     * must come back.
     */
    private void foldLegacyOutputShape(String pipelineName) {
        final PipelineEntity pipeline = pipelines.findById(pipelineName).orElseThrow();
        pipeline.setSpec("{\"transform\":{\"type\":\"llm\",\"model\":\"test-model\","
                + "\"preset\":\"chat_completion\",\"params\":{\"temperature\":0},"
                + "\"request_template\":\"{\\\"messages\\\":[{\\\"role\\\":\\\"user\\\","
                + "\\\"content\\\":\\\"Body: {{body}}\\\"}]}\","
                + "\"input_bindings\":[{\"var\":\"body\",\"column\":\"body\"}],"
                + "\"output_vars\":[{\"name\":\"title\",\"type\":\"string\","
                + "\"jsonata\":\"title\"},"
                + "{\"name\":\"mood\",\"type\":\"string\",\"jsonata\":\"mood\"}],"
                + "\"response_schema\":{\"type\":\"object\","
                + "\"required\":[\"mood\",\"title\"],"
                + "\"properties\":{\"mood\":{\"type\":\"string\"},"
                + "\"title\":{\"type\":\"string\"}},"
                + "\"additionalProperties\":false}},"
                + "\"output_bindings\":[{\"column\":\"title\",\"var\":\"title\"},"
                + "{\"column\":\"mood\",\"var\":\"mood\"}],"
                + "\"rows_per_scan\":1000,\"rows_per_call\":1,"
                + "\"rate_rpm\":75,\"sample_fraction\":0.5}");
        pipelines.save(pipeline);
    }

    private String createPipeline() throws Exception {
        return JsonPath.read(mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cp-pipeline\"," + transform() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + TARGET + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"advanced\":{\"rows_per_scan\":1000,"
                                + "\"rows_per_call\":1,\"rate_rpm\":75,\"sample_fraction\":0.5},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.name");
    }

    // --- the label-protection assertion ---------------------------------------------------------------

    @Test
    void compiledProjectionCarriesOneBindingPerOutputAssertedAsCount() throws Exception {
        final String id = createPipeline();

        // The count, explicitly. An empty list is what makes a consumer overwrite paid-for labels with
        // NULLs and stamp the work as done, so "how many" is the assertion, not "some".
        mockMvc.perform(get("/v1/pipelines/" + id + "?view=compiled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outputs.length()").value(2))
                .andExpect(jsonPath("$.outputs[*].column",
                        org.hamcrest.Matchers.containsInAnyOrder("title", "mood")));

        // And on the feed the runner actually reads, which is a different code path.
        mockMvc.perform(get("/v1/pipelines?kind=enrich&enabled=true&view=compiled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[?(@.name=='" + id + "')].outputs.length()")
                        .value(org.hamcrest.Matchers.contains(2)));
    }

    /**
     * Dropping the columns a pipeline writes is <b>refused before any DDL is rendered</b>, naming the
     * pipeline — 409 {@code conflict}, exactly as dropping a column another pipeline <em>reads</em> is.
     *
     * <p>The ordering is the whole point, and getting it wrong is worse than the failure it prevents.
     * {@code PATCH /v1/tables/{name}/schema} issues one ClickHouse {@code ALTER} first and commits the
     * catalog second, so a refusal raised inside the catalog transaction — which is where
     * {@code onTargetColumnsChanged}'s own check lives — rolls the catalog back over a ClickHouse table
     * that has already lost the column. The two stores then disagree about which columns exist, with no
     * operation that repairs the pair. Asking the registry in the request path, before the statement is
     * rendered, refuses the same change with nothing having happened.
     *
     * <p>The catalog-side check stays as defence in depth: it still covers a mapping made unresolvable by
     * some other route — a stored spec that drifted, or a derivation bug — which is exactly the case no
     * request-path check can see.
     */
    @Test
    void droppingTheColumnsThePipelineWritesIsRefusedNamingIt() throws Exception {
        final String id = createPipeline();
        mockMvc.perform(get("/v1/pipelines?kind=enrich&enabled=true"))
                .andExpect(jsonPath("$.pipelines[?(@.name=='" + id + "')]",
                        org.hamcrest.Matchers.hasSize(1)));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/v1/tables/" + TARGET + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"drop\":[\"title\",\"mood\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("cp-pipeline")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("is written by pipeline(s)")));

        // Nothing moved: the pipeline still resolves and is still served.
        mockMvc.perform(get("/v1/pipelines/" + id + "?view=compiled"))
                .andExpect(jsonPath("$.outputs.length()").value(2));
        mockMvc.perform(get("/v1/pipelines?kind=enrich&enabled=true"))
                .andExpect(jsonPath("$.pipelines[?(@.name=='" + id + "')]",
                        org.hamcrest.Matchers.hasSize(1)));
    }

    /**
     * Dropping <b>one</b> of the columns is likewise refused, for the same reason and before the mapping
     * is empty: the transform still declares an output for the dropped column, so the mapping could not
     * be re-derived at all.
     */
    @Test
    void droppingOneWrittenColumnIsAlsoRefused() throws Exception {
        createPipeline();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/v1/tables/" + TARGET + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"drop\":[\"mood\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("cp-pipeline")));
    }

    /**
     * The refusal above left <b>ClickHouse untouched</b> — and this is the assertion that proves it,
     * because nothing in the catalog can.
     *
     * <p>Delete the pipeline and drop the same column again: the second attempt has no dependent left, so
     * it renders {@code ALTER TABLE … DROP COLUMN `mood`} for real. That statement carries no
     * {@code IF EXISTS}, so it succeeds only if the column is still there. Had the first refusal fired
     * after the {@code ALTER} — the ordering this guard exists to correct — the column would already be
     * gone from ClickHouse while the catalog still listed it, and this drop would fail against a column
     * that does not exist.
     */
    @Test
    void refusedDropLeavesTheClickHouseColumnInPlace() throws Exception {
        createPipeline();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/v1/tables/" + TARGET + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"drop\":[\"mood\"]}"))
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/v1/pipelines/cp-pipeline")).andExpect(status().isNoContent());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/v1/tables/" + TARGET + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"drop\":[\"mood\"]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/tables/" + TARGET))
                .andExpect(jsonPath("$.columns[?(@.name=='mood')]", org.hamcrest.Matchers.hasSize(0)));
    }

    /**
     * Renaming a written column is refused on the same grounds and at the same point: an output resolves
     * to the target column of the same name, so the rename would leave the transform's {@code mood}
     * output naming nothing.
     */
    @Test
    void renamingWrittenColumnIsRefused() throws Exception {
        createPipeline();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/v1/tables/" + TARGET + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rename\":[{\"from\":\"mood\",\"to\":\"tone\"}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("cp-pipeline")));
    }

    /**
     * ADDING a column to the target is accepted, and bumps the pipeline so the new column reaches the
     * runner — which is the whole reason the catalog notifies the registry. Before this change the
     * addition required a pipeline patch to declare its output binding, and that patch carried the bump.
     */
    @Test
    void addingColumnToTheTargetBumpsGenerationAndUpdatedAt() throws Exception {
        final String id = createPipeline();
        final String before = mockMvc.perform(get("/v1/pipelines/" + id))
                .andReturn().getResponse().getContentAsString();
        final long generationBefore = ((Number) JsonPath.read(before, "$.generation")).longValue();
        final String updatedBefore = JsonPath.read(before, "$.updated_at");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/v1/tables/" + TARGET + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"add\":[{\"source_name\":\"note\",\"name\":\"note\","
                                + "\"type\":\"string\",\"nullable\":true}]}"))
                .andExpect(status().isOk());

        // Both members move. updated_at as well as generation, because the sync feed selects on
        // updated_at — a bumped generation the feed would never return is no signal at all.
        mockMvc.perform(get("/v1/pipelines/" + id + "?view=compiled"))
                .andExpect(jsonPath("$.generation").value(
                        org.hamcrest.Matchers.greaterThan((int) generationBefore)))
                .andExpect(jsonPath("$.updated_at").value(
                        org.hamcrest.Matchers.not(updatedBefore)))
                // The new column is not written until the transform declares an output for it, so the
                // mapping is unchanged — a target column no output writes is simply never written.
                .andExpect(jsonPath("$.outputs.length()").value(2));
    }

    /**
     * A pipeline whose transform declares {@code outputs} that <b>compose to no schema</b> is omitted from
     * the feed, for the same reason an empty output mapping is.
     *
     * <p>A declaration in the {@code outputs} shape stores no {@code response_schema}, so composition is
     * the only source of one —
     * and the resolver deliberately swallows a composition failure, so that a single drifted target cannot
     * fail the listing for every other pipeline in it. Served that way the entry looks perfectly healthy:
     * a full {@code outputs} mapping and no schema. The runner then sends no {@code response_format}, the
     * model answers free-form, and every row is rejected at validation — with the call made and charged
     * first. Silent and paid for, which is the same bargain the empty-mapping guard refuses.
     *
     * <p>The drift is induced through the repository on purpose: the declaration gate refuses this state
     * at the front door, so the only way to reach it is the way production would — a stored declaration
     * that stopped matching its target after the fact.
     */
    @Test
    void pipelineWhoseOutputsComposeToNoSchemaIsOmittedFromTheFeed() throws Exception {
        final String id = createPipeline();
        mockMvc.perform(get("/v1/pipelines?kind=enrich&enabled=true&view=compiled"))
                .andExpect(jsonPath("$.pipelines[?(@.name=='" + id + "')]",
                        org.hamcrest.Matchers.hasSize(1)));

        final PipelineEntity stored = pipelines.findById(id).orElseThrow();
        // Rename the output the target receives onto a column it does not carry. The stored output
        // MAPPING is left alone and is still non-empty, so the empty-mapping guard does not fire.
        //
        // Matched with a whitespace-tolerant pattern: `spec` is `jsonb`, so PostgreSQL hands it back
        // re-rendered with its own spacing rather than the bytes the codec wrote.
        stored.setSpec(stored.getSpec().replaceAll(
                "\"name\"\\s*:\\s*\"title\"", "\"name\": \"no_such_column\""));
        pipelines.save(stored);

        // The stored output mapping is untouched and still non-empty, so the empty-mapping guard does not
        // fire — this is the second guard, and without it the entry would be served schema-less.
        mockMvc.perform(get("/v1/pipelines?kind=enrich&enabled=true&view=compiled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[?(@.name=='" + id + "')]",
                        org.hamcrest.Matchers.hasSize(0)));
    }

    // --- the two stored shapes, side by side ---------------------------------------------------------

    @Test
    void storedResponseSchemaIsServedVerbatimRatherThanRecomposed() throws Exception {
        final String id = createPipeline();
        foldLegacyOutputShape(id);

        // The stored schema's own order and content, NOT what composition would produce from the target
        // (which would order title before mood and give `mood` the column's enum). Serving a composed
        // schema for a declaration that stores one would silently change a live prompt, because under
        // strict schema enforcement property order is generation order.
        mockMvc.perform(get("/v1/pipelines/" + id + "?view=compiled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response_schema.required",
                        org.hamcrest.Matchers.contains("mood", "title")))
                .andExpect(jsonPath("$.response_schema.properties.mood.enum").doesNotExist())
                // The output mapping still resolves, from `output_vars` through the accessor — which is
                // the silent-DDL-data-loss case if it ever stops.
                .andExpect(jsonPath("$.outputs.length()").value(2))
                // ...and there is no inlined evaluator object on either projection.
                .andExpect(jsonPath("$.evaluator").doesNotExist())
                // The AUTHORED copy of the same schema is omitted here, and this is the one pipeline
                // shape that can tell: it stores a schema, so serving `transform.response_schema` beside
                // the resolved top-level one would ship the identical multi-kilobyte object twice in
                // every entry of a feed the runner polls — the duplication that deleting the inlined
                // evaluator object removed, reintroduced one member lower down. The runner reads the
                // top-level member and does not model this one at all, and the round trip that needs the
                // authored copy happens on `view=source`, asserted below.
                .andExpect(jsonPath("$.transform.response_schema").doesNotExist());

        // The authored surface echoes both legacy members, so a read-modify-write client round-trips them
        // rather than dropping them — which would leave a declaration with no outputs at all.
        mockMvc.perform(get("/v1/pipelines/" + id + "?view=source"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transform.output_vars.length()").value(2))
                .andExpect(jsonPath("$.transform.output_vars[0].name").value("title"))
                .andExpect(jsonPath("$.transform.output_vars[0].type").value("string"))
                .andExpect(jsonPath("$.transform.response_schema.required",
                        org.hamcrest.Matchers.contains("mood", "title")))
                .andExpect(jsonPath("$.transform.outputs").doesNotExist())
                // The resolved schema is NOT served inside the authored block — serving it there would let
                // a compiled read-modify-write client post a composed schema back as a stored one.
                .andExpect(jsonPath("$.response_schema").doesNotExist());
    }

    @Test
    void outputsShapeServesComposedSchemaInDeclaredOrder() throws Exception {
        final String id = createPipeline();

        mockMvc.perform(get("/v1/pipelines/" + id + "?view=compiled"))
                .andExpect(status().isOk())
                // Declared order, title first — and `mood`'s domain composed from the COLUMN, in the
                // column's own order, which is what makes the two unable to drift apart.
                .andExpect(jsonPath("$.response_schema.required",
                        org.hamcrest.Matchers.contains("title", "mood")))
                .andExpect(jsonPath("$.response_schema.properties.mood.enum",
                        org.hamcrest.Matchers.contains("low", "mid", "high")))
                .andExpect(jsonPath("$.response_schema.properties.title.description")
                        .value("A short title for the event."))
                .andExpect(jsonPath("$.response_schema.additionalProperties").value(false))
                // Composed, so it is absent from the AUTHORED block: `transform.response_schema` means
                // "the schema this declaration stores", and this one stores none.
                .andExpect(jsonPath("$.transform.response_schema").doesNotExist());
    }

    /**
     * The <b>sync feed</b> composes the schema too — the regression this test exists for.
     *
     * <p>The list path and the single-pipeline path resolve through different code
     * ({@code EnrichResolver.resolveAll} batches; {@code resolve} does one), and only the latter used to
     * compose. Everything looked correct through {@code GET /v1/pipelines/&#123;name&#125;?view=compiled}
     * while {@code GET /v1/pipelines?kind=enrich&enabled=true&view=compiled} — the feed the Enrichment
     * Runner actually reads — served the same pipeline with <b>no</b> {@code response_schema}.
     *
     * <p>The consequence was silent and expensive rather than loud. A declaration in the {@code outputs}
     * shape stores no schema, so an absent composed one reaches the runner as null,
     * {@code response_format} is never sent, the model answers free-form, and every row lands in the DLQ
     * at {@code validate} — with the call made and charged, the feed looking healthy, and no label ever
     * written. Declarations carrying a stored schema kept working throughout, so it presented as one bad
     * prompt rather than as a broken feed.
     *
     * <p>Asserted as the two paths <b>agreeing</b>, not as the feed being non-empty: the defect was
     * precisely a disagreement between them, and either one alone would have passed while it existed.
     */
    @Test
    void theSyncFeedComposesTheSchemaJustAsTheSinglePipelineReadDoes() throws Exception {
        final String id = createPipeline();

        final String single = mockMvc.perform(get("/v1/pipelines/" + id + "?view=compiled"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        final String feed = mockMvc.perform(get("/v1/pipelines?kind=enrich&view=compiled"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        final Map<String, Object> fromSingle = JsonPath.read(single, "$.response_schema");
        final List<Map<String, Object>> fromFeed = JsonPath.read(
                feed, "$.pipelines[?(@.name == '" + id + "')].response_schema");

        assertThat(fromSingle)
                .as("the single-pipeline read composes a schema for an `outputs`-shape declaration")
                .isNotNull();
        assertThat(fromFeed)
                .as("the sync feed composes it too - the runner reads THIS path, and a null schema here "
                        + "means no response_format, a free-form answer and a DLQ row per input")
                .hasSize(1);
        assertThat(fromFeed.getFirst()).isEqualTo(fromSingle);
    }

    /**
     * The fold, with teeth. Each output's transform reaches the consumer on the output itself rather than
     * on a parallel list to be joined by name — and the transform is the half that cannot be guessed: a
     * consumer that loses it falls back to a direct lookup by name, which an identity mapping survives
     * and a real one does not. The sentinel here maps a mandatory {@code "none"} to NULL, so losing it
     * stores the literal string in a column that should hold NULL, which is precisely the class of quiet
     * wrongness this change exists to remove.
     */
    @Test
    void eachOutputCarriesItsOwnTransformInline() throws Exception {
        final String id = createPipeline();

        mockMvc.perform(get("/v1/pipelines/" + id + "?view=compiled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outputs.length()").value(2))
                .andExpect(jsonPath("$.outputs[0].name").value("title"))
                .andExpect(jsonPath("$.outputs[0].column").value("title"))
                // No jsonata on the identity case: absent already means a direct lookup by name, and the
                // identity expression is refused at registration precisely so it is not stored twice.
                .andExpect(jsonPath("$.outputs[0].jsonata").doesNotExist())
                .andExpect(jsonPath("$.outputs[1].name").value("mood"))
                .andExpect(jsonPath("$.outputs[1].jsonata")
                        .value("(mood = \"none\") ? null : mood"))
                // The authored `transform.outputs` IS served alongside — that is what makes a
                // read-modify-write round trip faithful — but it is a map keyed by target column rather
                // than a second copy of the derived array, so there is no wrong pair to join: the two are
                // different JSON types under different member names.
                .andExpect(jsonPath("$.response_schema").exists())
                .andExpect(jsonPath("$.transform.outputs.title")
                        .value("A short title for the event."))
                .andExpect(jsonPath("$.evaluator").doesNotExist());
    }

    // --- the two projections -------------------------------------------------------------------------

    /**
     * The assertion that replaces byte-identity, and it is the stronger of the two: not "the compiled
     * projection still says what it used to", but "there is exactly one name for each fact, and it is
     * the one an author writes". Every removed spelling is listed by name, because a member that
     * reappeared would not fail any positive assertion.
     */
    @Test
    void compiledProjectionSpeaksTheAuthoredVocabularyAndNoOtherOne() throws Exception {
        final String id = createPipeline();

        mockMvc.perform(get("/v1/pipelines/" + id + "?view=compiled"))
                .andExpect(status().isOk())
                // The knobs, under the names they were authored with — the same names `view=source`
                // uses, and now the same names the stored spec uses.
                .andExpect(jsonPath("$.advanced.scan_every").doesNotExist())
                .andExpect(jsonPath("$.advanced.rows_per_scan").value(1000))
                .andExpect(jsonPath("$.advanced.rows_per_call").value(1))
                .andExpect(jsonPath("$.advanced.rate_rpm").value(75))
                .andExpect(jsonPath("$.advanced.sample_fraction").value(0.5))
                // The transform's inputs, nested inside it — the flat `vars` is a member of no kind now,
                // so sending it is a binding 400 on either one.
                .andExpect(jsonPath("$.transform.inputs.body.column").value("body"))
                .andExpect(jsonPath("$.vars").doesNotExist())
                // Not one of the spellings the runner used to read. These are the second vocabulary,
                // enumerated so that reintroducing any of them fails here rather than in the runner.
                .andExpect(jsonPath("$.cadence").doesNotExist())
                .andExpect(jsonPath("$.batch_scan_limit").doesNotExist())
                .andExpect(jsonPath("$.batch_chunk").doesNotExist())
                .andExpect(jsonPath("$.rate_rpm").doesNotExist())
                .andExpect(jsonPath("$.sampling").doesNotExist())
                .andExpect(jsonPath("$.input_bindings").doesNotExist())
                .andExpect(jsonPath("$.output_bindings").doesNotExist())
                // And the members that stopped existing altogether, on both projections — including the
                // evaluator pointer and the object it used to resolve to.
                .andExpect(jsonPath("$.priority").doesNotExist())
                .andExpect(jsonPath("$.freshness").doesNotExist())
                .andExpect(jsonPath("$.evaluator_name").doesNotExist())
                .andExpect(jsonPath("$.evaluator_version").doesNotExist())
                .andExpect(jsonPath("$.evaluator").doesNotExist());
    }

    /**
     * What separates the projections is resolution alone. Asserted as a set difference over the response's
     * own property names rather than field by field, so a member added to one projection and forgotten in
     * the other shows up here instead of at a consumer.
     */
    @Test
    void theProjectionsDifferOnlyByTheDerivedMembers() throws Exception {
        final String id = createPipeline();

        final Map<String, Object> compiled = JsonPath.read(mockMvc.perform(
                get("/v1/pipelines/" + id + "?view=compiled")).andReturn().getResponse()
                .getContentAsString(), "$");
        final Map<String, Object> source = JsonPath.read(mockMvc.perform(
                get("/v1/pipelines/" + id + "?view=source")).andReturn().getResponse()
                .getContentAsString(), "$");

        assertThat(source.keySet()).isSubsetOf(compiled.keySet());
        // Exactly the derived members, and nothing renamed: `inputs` is here because this pipeline
        // inherits its target's parent rather than declaring one, so the authored answer omits it.
        final Set<String> expected = new LinkedHashSet<>(source.keySet());
        // One member swapped for one member: the inlined `evaluator` object is gone and the derived
        // `response_schema` takes its place. The set is otherwise unchanged, which is what keeps the
        // difference between the projections a statement about resolution rather than about vocabulary.
        expected.addAll(List.of("response_schema", "grain_key", "version_column", "outputs", "inputs"));
        assertThat(compiled.keySet()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void authoredProjectionServesTheDeclarationAndOmitsTheCompiledForm() throws Exception {
        final String id = createPipeline();

        mockMvc.perform(get("/v1/pipelines/" + id + "?view=source"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transform.inputs.body.column").value("body"))
                .andExpect(jsonPath("$.advanced.scan_every").doesNotExist())
                .andExpect(jsonPath("$.advanced.rows_per_scan").value(1000))
                .andExpect(jsonPath("$.advanced.rows_per_call").value(1))
                .andExpect(jsonPath("$.advanced.rate_rpm").value(75))
                .andExpect(jsonPath("$.advanced.sample_fraction").value(0.5))
                // The derived members are absent: one stored pipeline, two projections differing by
                // resolution. `transform` is on BOTH, because it is authored rather than derived.
                .andExpect(jsonPath("$.response_schema").doesNotExist())
                .andExpect(jsonPath("$.outputs").doesNotExist())
                .andExpect(jsonPath("$.cadence").doesNotExist())
                .andExpect(jsonPath("$.batch_scan_limit").doesNotExist())
                .andExpect(jsonPath("$.input_bindings").doesNotExist())
                .andExpect(jsonPath("$.output_bindings").doesNotExist())
                // This pipeline declared no `inputs`, so the authored projection OMITS it rather than
                // filling in the resolved value — otherwise a GET -> PATCH round trip would silently
                // declare what the pipeline had been inheriting.
                .andExpect(jsonPath("$.inputs").doesNotExist());
    }

    @Test
    void authoredProjectionEchoesTheDeclaredReadSource() throws Exception {
        final String id = JsonPath.read(mockMvc.perform(post("/v1/pipelines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cp-pipeline\"," + transform() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + TARGET + "\","
                                + "\"inputs\":[\"" + SOURCE + "\"],"
                                + "\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.name");

        // Declared, so both projections carry it — but only the authored one is reporting the declaration
        // rather than the resolution.
        mockMvc.perform(get("/v1/pipelines/" + id + "?view=source"))
                .andExpect(jsonPath("$.inputs[0]").value(SOURCE));
        mockMvc.perform(get("/v1/pipelines/" + id))
                .andExpect(jsonPath("$.inputs[0]").value(SOURCE));
    }

    // --- the dependency guard, and the staged-registration gap -------------------------------------

    /**
     * A {@code sql} transform's expressions must reach the catalog's <b>drop and rename guard</b>, and
     * this is the behavioural half of that claim (the grep-level half is that no consumer reads
     * {@code getOutputs()} or {@code getOutputVars()} directly).
     *
     * <p>It is a data-integrity case rather than plumbing. Such a declaration keeps its expressions in
     * {@code outputs} and carries an empty {@code output_vars}, so a guard reading the raw field would
     * contribute <b>zero</b> column references — and an operator could then drop or rename an enrichment
     * column the pipeline's {@code INSERT … SELECT} joins, with the failure arriving at the next
     * materialize rather than at the DDL.
     */
    @Test
    void newShapeSqlExpressionsReachTheCatalogDropGuard() throws Exception {
        // A Class-1 pipeline whose expression READS a column of a sibling enrichment.
        IntegrationTables.enrichment(mockMvc, "cp_read", SOURCE, "event_id",
                "[{\"source_name\":\"tag\",\"name\":\"tag\",\"type\":\"string\"}]");
        try {
            mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"cp-pipeline\","
                                    + Transforms.sql()
                                            .output("title", "lower(\\\"cp_read.tag\\\")").member() + ","
                                    + "\"kind\":\"enrich\",\"target\":\"" + TARGET + "\","
                                    + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}"))
                    .andExpect(status().isCreated());

            // Dropping the column the expression reads is refused, naming the pipeline that reads it —
            // which only works if the guard saw an expression living in `outputs`.
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .patch("/v1/tables/cp_read/schema").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"drop\":[\"tag\"]}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("cp-pipeline")));

            // And deleting the whole enrichment is refused for the same reason.
            mockMvc.perform(delete("/v1/tables/cp_read"))
                    .andExpect(status().isConflict());
        } finally {
            mockMvc.perform(delete("/v1/pipelines/cp-pipeline"));
            mockMvc.perform(delete("/v1/tables/cp_read"));
        }
    }

    /**
     * A prompt change is a declaration {@code PATCH}, and it <b>re-validates and bumps in one call</b>.
     *
     * <p>This is what replaced the two-step "register a new evaluator version, then repoint the pin". The
     * net is not obviously worse — on an unpinned pipeline a registration was already instantly live, and
     * it was unattributed — but the two-step option is gone, so the checkpoint has to be the preview
     * rather than the registration.
     */
    @Test
    void transformPatchRevalidatesAndBumps() throws Exception {
        final String id = createPipeline();
        final long generationBefore = ((Number) JsonPath.read(
                mockMvc.perform(get("/v1/pipelines/" + id)).andReturn().getResponse().getContentAsString(),
                "$.generation")).longValue();

        final String revised = Transforms.llm()
                .model("test-model")
                .preset(null)
                .requestTemplate("{\"messages\":[{\"role\":\"user\","
                        + "\"content\":\"Body: {{body}}\"}]}")
                .input("body", "body")
                .output("title", "A revised title instruction.")
                .member();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/v1/pipelines/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cp-pipeline\"," + revised + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + TARGET + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/pipelines/" + id + "?view=compiled"))
                .andExpect(jsonPath("$.generation").value(
                        org.hamcrest.Matchers.greaterThan((int) generationBefore)))
                // Re-derived against the new output set: one output now, so one mapped column.
                .andExpect(jsonPath("$.outputs.length()").value(1))
                .andExpect(jsonPath("$.response_schema.properties.title.description")
                        .value("A revised title instruction."));
    }

    /**
     * A declaration patch whose outputs resolve to <b>no column</b> of the target is refused, and the
     * refusal <b>leaves the pipeline exactly as it was</b> — the gate runs before anything is stored, so
     * no position is discarded and no generation moves.
     *
     * <p>Before the fold this was two failure modes at two objects: an evaluator version accepted
     * unvalidated where every dependent pinned one, and a re-validation pass that had to be re-run for the
     * unpinned case. The target is in scope wherever the declaration is written now, so there is one gate
     * and one answer.
     */
    @Test
    void transformPatchResolvingToNoTargetColumnIsRefusedAndChangesNothing() throws Exception {
        final String id = createPipeline();
        final long generationBefore = ((Number) JsonPath.read(
                mockMvc.perform(get("/v1/pipelines/" + id)).andReturn().getResponse().getContentAsString(),
                "$.generation")).longValue();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/v1/pipelines/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cp-pipeline\","
                                + Transforms.llm().model("test-model").preset(null)
                                        .output("no_such_column", "Nothing receives this.").member() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + TARGET + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("no_such_column")));

        mockMvc.perform(get("/v1/pipelines/" + id + "?view=compiled"))
                .andExpect(jsonPath("$.generation").value((int) generationBefore))
                .andExpect(jsonPath("$.outputs.length()").value(2))
                .andExpect(jsonPath("$.transform.outputs.title")
                        .value("A short title for the event."));
    }

    @Test
    void unknownViewValueIsRejectedNamingTheAcceptedOnes() throws Exception {
        final String id = createPipeline();

        // Bound strictly, like the registry's other filters: a value that will not convert must be a 400
        // naming the accepted set, never a silent fall-back to a default — a silently-ignored projection
        // selector answers with a different SHAPE than the caller asked for.
        //
        // The message is asserted, not just the status. It reaches the caller through the ONE carve-out
        // `InvalidRequestParameterException` exists for: the handler never echoes a binding failure's own
        // text, because Spring's and Jackson's leak type names and fragments of the input, so a binder that
        // knows what it accepts has to mark its message as authored here before it may be shown.
        mockMvc.perform(get("/v1/pipelines/" + id + "?view=raw"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("view must be one of"),
                        org.hamcrest.Matchers.containsString("compiled"),
                        org.hamcrest.Matchers.containsString("source"))))
                // and never the value the caller sent - naming the accepted set is the point, echoing the
                // input is the half the no-echo policy forbids.
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("raw"))));
        mockMvc.perform(get("/v1/pipelines?view=raw"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("view must be one of")));
        // The same for the other two hand-written binders, so the three stay consistent.
        mockMvc.perform(get("/v1/pipelines?kind=bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("kind must be one of")));
        mockMvc.perform(get("/v1/pipelines?enabled=maybe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("enabled must be exactly")));
    }

    @Test
    void bothProjectionsAgreeOnEveryMemberThatIsNotProjectionSpecific() throws Exception {
        final String id = createPipeline();

        final String compiled = mockMvc.perform(get("/v1/pipelines/" + id))
                .andReturn().getResponse().getContentAsString();
        final String source = mockMvc.perform(get("/v1/pipelines/" + id + "?view=source"))
                .andReturn().getResponse().getContentAsString();

        // A projection selects which members are populated, never what the shared ones say — otherwise a
        // reader would have to know which view they were looking at to trust the identity of the row.
        for (final String path : List.of("$.name", "$.kind", "$.target", "$.enabled", "$.generation",
                "$.trigger.kind", "$.transform.model")) {
            assertThat(JsonPath.read(compiled, path).toString())
                    .as("member %s must not depend on the projection", path)
                    .isEqualTo(JsonPath.read(source, path).toString());
        }
    }
}
