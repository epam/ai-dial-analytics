package com.epam.aidial.analytics.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static com.epam.aidial.analytics.integration.RunnerCalls.runnerPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of an aggregate pipeline that reads its input's <em>enrichment</em> columns rather
 * than only the input table's own: register → preview → enable → refresh a rollup grouped by a column
 * reached through the grain join, then read the rollup back.
 *
 * <p>The case is worth an end-to-end test rather than a rendered-SQL one because the failure it guards is
 * not a compile error. A refresh composes five independent statements over the input, each with its own
 * {@code FROM}, and bindings render as two-part physical references — so a statement that resolves the
 * qualified name without emitting the join is well-formed SQL naming a table it never joined. That fails
 * at execution, against a real engine, which is the only place this can be observed.
 *
 * <p>A dedicated source is used rather than {@code dial_usage_log}: an enrichment registered on the seeded
 * source would change its flat schema for every other integration class that queries it.
 *
 * <p>Requires a running Docker daemon.
 */
class AggregateEnrichmentIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String PIPELINES = "/v1/pipelines";
    private static final String SOURCE = "gadget_events";
    private static final String ENRICHMENT = "gadget_owner";
    private static final String OWNER = ENRICHMENT + ".owner";
    private static final String ROLLUP = "owner_totals";
    private static final String PIPELINE = "owner_totals_rollup";

    /**
     * The declaration under test: the group key is an enrichment column, and the filter names the same
     * column. Both are needed — an enrichment-sourced group key reads as nullable whatever it declares
     * (the outer join decides, not the flag), and a nullable group key must carry a filter.
     */
    private static final String DECLARATION = """
            {"name":"%s","kind":"aggregate",
             "target":"%s",
             "inputs":["%s"],
             "filter":"length(\\"%s\\") > 0",
             "group_by":[{"column":"%s","as":"owner"}],
             "measures":[{"name":"total","fn":"sum","column":"score"},
                         {"name":"events","fn":"count"}],
             "trigger":{"kind":"schedule","cron":"0 0 * * * *"}}"""
            .formatted(PIPELINE, ROLLUP, SOURCE, OWNER, OWNER);

    @BeforeEach
    void createTables() throws Exception {
        dropAll();
        // The input is scannable: `at` is the non-nullable timestamp the window pages by, `event_id` the
        // column that identifies a row.
        IntegrationTables.source(mockMvc, SOURCE,
                """
                [{"source_name":"event_id","name":"event_id","type":"string"},
                 {"source_name":"score","name":"score","type":"long"},
                 {"source_name":"at","name":"at","type":"timestamp"}]""",
                "[\"event_id\"]", "event_id", "at");
        IntegrationTables.enrichment(mockMvc, ENRICHMENT, SOURCE, "event_id",
                "[{\"source_name\":\"owner\",\"name\":\"owner\",\"type\":\"string\"}]");
        IntegrationTables.upsertSource(mockMvc, ROLLUP, """
                [{"source_name":"owner","name":"owner","type":"string"},
                 {"source_name":"total","name":"total","type":"long","nullable":true},
                 {"source_name":"events","name":"events","type":"long"}]""",
                "[\"owner\"]");
    }

    // ------------------------------------------------- the enrichment an aggregate pipeline reads is protected

    /**
     * Dropping an enrichment a rollup groups on is refused, and this is the gap the change closes.
     *
     * <p>Before it, the catalog consulted <em>enrichment</em> declarations only, so the delete was blocked
     * only when one of those happened to name the same enrichment — a rollup was protected purely because
     * some enrichment pipeline read the same enrichment too. Retire that one declaration and an enrichment
     * several rollups read becomes droppable, with the failure arriving at the next refresh
     * rather than at the delete.
     *
     * <p>An input lookup cannot answer it either, which is why the guard has to parse declarations: those
     * rollups read `dial_usage_log`, not the enrichment.
     */
    @Test
    void deletingAnEnrichmentAnAggregatePipelineGroupsOnIsRefused() throws Exception {
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON).content(DECLARATION))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString(PIPELINE)))
                .andExpect(jsonPath("$.message").value(containsString("grain join")));

        // Still there, and still readable — a refused delete drops nothing.
        mockMvc.perform(get("/v1/tables/" + ENRICHMENT)).andExpect(status().isOk());
    }

    /** The guard is a dependency, not a lock: it releases as soon as nothing reads the enrichment. */
    @Test
    void theEnrichmentBecomesDeletableOnceNoPipelineReadsIt() throws Exception {
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON).content(DECLARATION))
                .andExpect(status().isCreated());
        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT)).andExpect(status().isConflict());

        mockMvc.perform(delete(PIPELINES + "/" + PIPELINE)).andExpect(status().isNoContent());

        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT)).andExpect(status().isNoContent());
    }

    /**
     * Dropping or renaming the enrichment <em>column</em> a rollup names is refused on the same basis.
     *
     * <p>A separate check from the table delete because it asks a narrower question — which declarations
     * name <em>this column</em> by its qualified flat name — and nothing repoints that name, so either
     * operation would leave the declaration referencing a column its statements can no longer resolve.
     */
    @Test
    void droppingOrRenamingTheEnrichmentColumnTheRollupNamesIsRefused() throws Exception {
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON).content(DECLARATION))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/v1/tables/" + ENRICHMENT + "/schema")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"drop\":[\"owner\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString(PIPELINE)));

        mockMvc.perform(patch("/v1/tables/" + ENRICHMENT + "/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rename\":[{\"from\":\"owner\",\"to\":\"holder\"}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString(PIPELINE)));
    }

    /**
     * A reference from a measure's own {@code where} counts, not only from the grain.
     *
     * <p>Worth its own case because the extraction reads four separate positions — group keys, measure
     * columns, the pipeline filter, and each measure's predicate — and an implementation that covered the
     * grain and quietly missed the predicate would pass every other test here. This declaration groups on
     * the source's own column and touches the enrichment <b>only</b> from a measure predicate.
     */
    @Test
    void referenceFromOnlyOneMeasurePredicateStillProtectsTheEnrichment() throws Exception {
        IntegrationTables.upsertSource(mockMvc, "event_totals", """
                [{"source_name":"event_id","name":"event_id","type":"string"},
                 {"source_name":"owned","name":"owned","type":"long","nullable":true}]""",
                "[\"event_id\"]");
        try {
            final String byEvent = """
                    {"name":"event_totals_rollup","kind":"aggregate",
                     "target":"event_totals",
                     "inputs":["%s"],
                     "group_by":[{"column":"event_id"}],
                     "measures":[{"name":"owned","fn":"count","where":"length(\\"%s\\") > 0"}],
                     "trigger":{"kind":"schedule","cron":"0 0 * * * *"}}"""
                    .formatted(SOURCE, OWNER);
            mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON).content(byEvent))
                    .andExpect(status().isCreated());

            mockMvc.perform(delete("/v1/tables/" + ENRICHMENT))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(containsString("event_totals_rollup")));
        } finally {
            mockMvc.perform(delete(PIPELINES + "/event_totals_rollup"));
            mockMvc.perform(delete("/v1/tables/event_totals"));
        }
    }

    /**
     * A column of the same enrichment that the declaration does <b>not</b> name stays droppable.
     *
     * <p>The guard has to be per-column rather than per-table, or it would freeze an enrichment's whole
     * schema the moment one of its columns was read — which is the failure mode of a guard that is too
     * broad, and just as real as the one that is too narrow.
     */
    @Test
    void anUnreferencedColumnOfTheSameEnrichmentStaysDroppable() throws Exception {
        mockMvc.perform(patch("/v1/tables/" + ENRICHMENT + "/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"add":[{"source_name":"region","name":"region","type":"string",
                                         "nullable":true}]}"""))
                .andExpect(status().isOk());
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON).content(DECLARATION))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/v1/tables/" + ENRICHMENT + "/schema")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"drop\":[\"region\"]}"))
                .andExpect(status().isOk());
    }

    @AfterEach
    void dropAll() throws Exception {
        mockMvc.perform(delete(PIPELINES + "/" + PIPELINE));
        mockMvc.perform(delete("/v1/tables/" + ROLLUP));
        // The pipeline before its target enrichment: a pipeline is what makes the enrichment clampable, and leaving
        // one behind would change how the NEXT test in this class classifies the same enrichment.
        if (ownerPipelineName != null) {
            mockMvc.perform(delete("/v1/pipelines/" + ownerPipelineName));
            ownerPipelineName = null;
        }
        // Enrichments before their source: deleting a source with dependents is refused with 409.
        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT));
        mockMvc.perform(delete("/v1/tables/" + SOURCE));
    }

    /** One input row, dated well into the past so the refresh's safety lag does not hold it back. */
    private void seedEvent(String eventId, long score, int minute) {
        execute("INSERT INTO " + SOURCE + " (event_id, score, at) VALUES ('" + eventId + "', " + score
                + ", '2026-01-01 10:" + String.format("%02d", minute) + ":00.000')");
    }

    private void seedOwner(String eventId, String owner) {
        execute("INSERT INTO " + ENRICHMENT + " (event_id, owner) VALUES ('" + eventId + "', '" + owner + "')");
    }

    private void register() throws Exception {
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON).content(DECLARATION))
                .andExpect(status().isCreated());
    }

    /**
     * Registers {@code body} — stored ungated, since an aggregate registration is a draft — then previews
     * it, deletes it, and returns the preview's result: where the declaration gate now refuses it, with the
     * status and code the registration used to answer.
     */
    private ResultActions previewOf(String body) throws Exception {
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        final ResultActions preview = mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/preview"));
        mockMvc.perform(delete(PIPELINES + "/" + PIPELINE)).andExpect(status().isNoContent());
        return preview;
    }

    private void enable() throws Exception {
        mockMvc.perform(patch(PIPELINES + "/" + PIPELINE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    void rollupGroupedByEnrichmentColumnLandsKeyedByItsValues() throws Exception {
        register();

        // Preview first: it is the gate an operator reads before enabling, so it has to run the join too.
        // A preview compiled without it would fail here rather than at runtime, which is the point.
        seedEvent("e1", 10, 1);
        seedEvent("e2", 20, 2);
        seedEvent("e3", 5, 3);
        seedOwner("e1", "ada");
        seedOwner("e2", "ada");
        seedOwner("e3", "grace");

        // Its statement names the enrichment column as the entity exposes it and leaves the join to the query path,
        // which answers the same groups.
        final String preview = mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statement", containsString("\"gadget_owner.")))
                .andExpect(jsonPath("$.statement", not(containsStringIgnoringCase("join"))))
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        assertThat(PreviewStatements.runStatement(mockMvc, preview))
                .containsExactlyInAnyOrderElementsOf(PreviewStatements.body(preview).get("rows"));

        enable();
        assertThat(mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/refresh"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("\"rows_written\":2");

        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"%s","mode":"row",
                                 "select":[{"expr":{"type":"field","name":"owner"}},
                                           {"expr":{"type":"field","name":"total"}},
                                           {"expr":{"type":"field","name":"events"}}],
                                 "sort":[{"field":"owner","dir":"asc"}]}""".formatted(ROLLUP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].owner").value("ada"))
                .andExpect(jsonPath("$.rows[0].total").value(30))
                .andExpect(jsonPath("$.rows[0].events").value(2))
                .andExpect(jsonPath("$.rows[1].owner").value("grace"))
                .andExpect(jsonPath("$.rows[1].total").value(5));
    }

    @Test
    void pipelinePathAndQueryPathReportTheSameValues() throws Exception {
        // The one assertion that pins the two implementations of "which enrichment does this statement
        // join" against each other. They share the emission helper; the referenced-field derivation is
        // per-path, and that is the remaining surface where they could drift.
        register();
        enable();
        seedEvent("e1", 10, 1);
        seedEvent("e2", 20, 2);
        seedOwner("e1", "ada");
        seedOwner("e2", "ada");
        mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/refresh")).andExpect(status().isOk());

        // The same aggregate, expressed as a query over the input entity rather than as a rollup.
        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"%s","mode":"aggregate",
                                 "group_by":["%s"],
                                 "select":[{"expr":{"type":"field","name":"%s"},"as":"owner"},
                                           {"expr":{"type":"fn","name":"sum",
                                                    "args":[{"type":"field","name":"score"}]},"as":"total"}]}"""
                                .formatted(SOURCE, OWNER, OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].owner").value("ada"))
                .andExpect(jsonPath("$.rows[0].total").value(30));

        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"%s","mode":"row",
                                 "select":[{"expr":{"type":"field","name":"owner"}},
                                           {"expr":{"type":"field","name":"total"}}]}""".formatted(ROLLUP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].owner").value("ada"))
                .andExpect(jsonPath("$.rows[0].total").value(30));
    }

    @Test
    void inputRowTheEnrichmentHasNotReachedIsExcludedByTheFilter() throws Exception {
        // The outer join is what makes an enrichment column nullable regardless of its declared flag. The
        // filter the nullable-group-key rule insists on is what keeps the unmatched rows out of the rollup
        // instead of collapsing them onto an empty-string key.
        //
        // NOTE what this pins and what it does not. It asserts CURRENT behaviour, which is not yet the
        // desired behaviour. Here e2 is the newest row, so the window ends at e1 — the last row that
        // MATCHED the filter — and e2 stays ahead of the cursor: enrich it later and a subsequent refresh
        // does pick it up. What is NOT safe is the reverse order, verified against a running ClickHouse:
        // an unreached row followed by a reached one is carried past by the window end and is
        // then never aggregated, because nothing revisits a source row whose own cursor did not move. That
        // is the freshness gap the delta spec carries as NOT YET IMPLEMENTED ("A refresh window is clamped
        // to its referenced enrichments' cursors"), which gets a test of its own.
        register();
        enable();
        seedEvent("e1", 10, 1);
        seedEvent("e2", 20, 2);
        seedOwner("e1", "ada");

        mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/refresh")).andExpect(status().isOk());

        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"%s","mode":"row",
                                 "select":[{"expr":{"type":"field","name":"owner"}},
                                           {"expr":{"type":"field","name":"total"}}]}""".formatted(ROLLUP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].owner").value("ada"))
                .andExpect(jsonPath("$.rows[0].total").value(10));
    }

    /**
     * The failure this capability exists to close, end to end — and the one that was verified as a real,
     * permanent data loss against a running ClickHouse rather than reasoned about.
     *
     * <p>Coverage is deliberately <b>non-contiguous</b>: e1 and e3 are enriched, e2 is not. That ordering is
     * what makes the loss permanent, because the pipeline's window ends at the last row that <em>matched</em>
     * its filter — e3 — carrying the cursor straight past e2, and nothing ever revisits a source row whose
     * own cursor did not move. Enriching e2 afterwards would then change nothing: the refresh reports rows
     * written, advances, and looks exactly like a healthy run. With the clamp the window stops at e1, which
     * is where the pipeline has actually been materialized through, so e2 is still ahead of the cursor when its
     * value arrives.
     */
    @Test
    void windowClampsToThePipelinesPositionSoAnUnreachedRowIsNotCarriedPast() throws Exception {
        createOwnerPipeline();
        register();
        enable();
        seedEvent("e1", 10, 1);
        seedEvent("e2", 20, 2);
        seedEvent("e3", 30, 3);
        seedOwner("e1", "ada");
        seedOwner("e3", "ada");

        // The pipeline has been materialized through e1 only, so that is as far as the refresh may read.
        materialize("{\"limit\":1}");
        mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/refresh")).andExpect(status().isOk());

        // 40, not 10 — and that is the contract working rather than the clamp failing. The window dirtied
        // key "ada" via e1, and the recompute half then aggregates EVERY row of a dirty key, e3 included.
        // So the total after this refresh is the same with or without a clamp; what differs is where the
        // cursor stopped, which is what decides whether e2 can ever be counted.
        expectTotal(40);

        // State says why it stopped short, naming the enrichment that bounded it — the difference between
        // "held back on purpose" and "quiet input", which lag alone cannot express.
        mockMvc.perform(get(PIPELINES + "/" + PIPELINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.clamp.enrichment").value(ENRICHMENT))
                .andExpect(jsonPath("$.state.clamp.cursor_version").isNumber())
                .andExpect(jsonPath("$.state.rebuild_required").doesNotExist());

        // e2's value arrives, and the pipeline catches up to the tail.
        seedOwner("e2", "ada");
        materialize("{\"limit\":10}");
        mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/refresh")).andExpect(status().isOk());

        // 60: the decisive assertion. e2 was still above the cursor when its enrichment value arrived, so
        // this refresh's window covers it, dirties "ada" again, and recomputes the key in full. Unclamped,
        // the first refresh would have parked the cursor at e3 — past e2 — and this refresh would probe
        // nothing, leaving 40 forever with nothing recording the missing 20.
        expectTotal(60);
        mockMvc.perform(get(PIPELINES + "/" + PIPELINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.clamp").doesNotExist());
    }

    @Test
    void pipelineReadingAnUnwrittenEnrichmentIsNotClamped() throws Exception {
        // No pipeline targets gadget_owner here, so its absent values are final rather than late: clamping would
        // freeze this rollup permanently with no operator action that released it.
        register();
        enable();
        seedEvent("e1", 10, 1);
        seedOwner("e1", "ada");

        mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/refresh")).andExpect(status().isOk());

        expectTotal(10);
        mockMvc.perform(get(PIPELINES + "/" + PIPELINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.clamp").doesNotExist())
                .andExpect(jsonPath("$.state.unclamped_reads[0].enrichment").value(ENRICHMENT))
                .andExpect(jsonPath("$.state.unclamped_reads[0].reason").value("no_pipeline"));
    }

    /** The pipeline created by {@link #createOwnerPipeline}, so materialize addresses it by id rather than by guess. */
    private String ownerPipelineName;

    /** A Class-1 pipeline filling the enrichment's owner column, so the enrichment has a position to clamp to. */
    private void createOwnerPipeline() throws Exception {
        // The transform is authored on the pipeline, keyed by the target column it writes — which is what
        // removes both the separate evaluator object and the output_bindings declaration. The expression
        // yields the same value the fixture seeds directly, so materializing a row does not change what it
        // is grouped under: this test is about the recorded position, not about what the pipeline computes.
        // A comparison cannot appear in a scalar expression (the DSL keeps those to filters), so it is a
        // plain function over a literal.
        final String created = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"gadget-owner-live\","
                                + Transforms.sql().output("owner", "lower('ADA')").member() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        ownerPipelineName = JsonPath.read(created, "$.name");
    }

    private void materialize(String body) throws Exception {
        mockMvc.perform(runnerPost("/v1/pipelines/" + ownerPipelineName + "/materialize")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    /** The rollup's single group total, read back through the governed query surface. */
    private void expectTotal(long total) throws Exception {
        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"%s","mode":"row",
                                 "select":[{"expr":{"type":"field","name":"owner"}},
                                           {"expr":{"type":"field","name":"total"}}]}""".formatted(ROLLUP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].owner").value("ada"))
                .andExpect(jsonPath("$.rows[0].total").value(total));
    }

    @Test
    void enrichmentSourcedGroupKeyWithNoFilterIsRefused() throws Exception {
        previewOf(DECLARATION.replace("\"filter\":\"length(\\\"" + OWNER + "\\\") > 0\",", ""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message", containsString(OWNER)))
                .andExpect(jsonPath("$.message", containsString("filter")));
    }

    @Test
    void enrichmentMayNotBeDeclaredAsInput() throws Exception {
        // The entity is one source plus its own enrichments; an enrichment is reached through the grain
        // join, never named as a second input.
        previewOf(DECLARATION.replace("\"inputs\":[\"" + SOURCE + "\"]",
                                "\"inputs\":[\"" + ENRICHMENT + "\"]"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void reRegisteredPipelineStartsWithNoCursor() throws Exception {
        // The mutation obligation, satisfied with existing API and no new code: an enrichment re-run over
        // history changes values the rollup already read, and nothing can detect that — so the contract is
        // that the operator rebuilds. Delete-then-re-register IS the cursor reset, because a pipeline is
        // stored with no cursor and DELETE leaves the target's rows intact.
        register();
        enable();
        seedEvent("e1", 10, 1);
        seedOwner("e1", "ada");
        mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/refresh")).andExpect(status().isOk());
        mockMvc.perform(get(PIPELINES + "/" + PIPELINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.cursor_version").value(notNullValue()));

        mockMvc.perform(delete(PIPELINES + "/" + PIPELINE)).andExpect(status().isNoContent());
        register();

        mockMvc.perform(get(PIPELINES + "/" + PIPELINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.cursor_version").doesNotExist());
        // And the target's rows survived the delete, which is what makes this a rebuild rather than a
        // truncate: the next refresh re-reads history and replaces each key it recomputes.
        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"%s","mode":"row",
                                 "select":[{"expr":{"type":"field","name":"owner"}}]}""".formatted(ROLLUP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)));
    }

    // ------------------------------------------------- the recompute's join restricted to the dirty keys

    /** The fixture's dirty key: the only owner the second refresh's window touches. */
    private static final String DIRTY = "grace";

    /**
     * The rollup an unrestricted recompute would write for {@code owners}, computed directly — the
     * brute-force reference the restricted refresh must equal.
     */
    private String bruteForce(String owners) {
        return queryScalar("SELECT toString(groupArray((owner, total, events))) AS r FROM ("
                + "SELECT gadget_owner.owner AS owner, sum(score) AS total, count() AS events FROM " + SOURCE
                + " LEFT JOIN " + ENRICHMENT + " FINAL ON " + SOURCE + ".event_id = " + ENRICHMENT + ".event_id"
                + " WHERE length(gadget_owner.owner) > 0 AND gadget_owner.owner IN (" + owners + ")"
                + " GROUP BY owner ORDER BY owner)", "r");
    }

    /** What the rollup holds for {@code owners}, in the same rendering as {@link #bruteForce}. */
    private String target(String owners) {
        return queryScalar("SELECT toString(groupArray((owner, total, events))) AS r FROM ("
                + "SELECT owner, total, events FROM " + ROLLUP + " FINAL WHERE owner IN (" + owners + ")"
                + " ORDER BY owner)", "r");
    }

    /** An enrichment row with an explicit version, so two writes of one grain never tie on the millisecond. */
    private void seedOwnerVersion(String eventId, String owner, String version) {
        execute("INSERT INTO " + ENRICHMENT + " (event_id, owner, _updated_at) VALUES ('" + eventId + "', "
                + (owner == null ? "NULL" : "'" + owner + "'") + ", '" + version + "')");
    }

    /**
     * Seeds the history every restriction case starts from, refreshes once so the cursor sits past it, then
     * adds one new event for {@link #DIRTY} — so the second refresh's window dirties that key alone.
     *
     * <p>The history holds every row shape the restriction must not move: rows of other keys (ada), a row
     * with no enrichment row (e4), one whose owner is NULL (e5), and one whose superseded version carries the
     * dirty key while its latest version carries another (e6: grace, then linus). Merges are stopped so the
     * superseded version stays a separate part, which only {@code final = 1} collapses.
     */
    private void seedRestrictionHistory() throws Exception {
        execute("SYSTEM STOP MERGES " + ENRICHMENT);
        seedEvent("e1", 10, 1);
        seedEvent("e2", 20, 2);
        seedEvent("e3", 5, 3);
        seedEvent("e4", 7, 4);
        seedEvent("e5", 9, 5);
        seedEvent("e6", 11, 6);
        seedOwnerVersion("e1", "ada", "2026-01-01 00:00:00.000");
        seedOwnerVersion("e2", "ada", "2026-01-01 00:00:00.000");
        seedOwnerVersion("e3", DIRTY, "2026-01-01 00:00:00.000");
        seedOwnerVersion("e5", null, "2026-01-01 00:00:00.000");
        seedOwnerVersion("e6", DIRTY, "2026-01-01 00:00:00.000");
        seedOwnerVersion("e6", "linus", "2026-01-02 00:00:00.000");
        mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/refresh")).andExpect(status().isOk());
        seedEvent("e7", 100, 7);
        seedOwnerVersion("e7", DIRTY, "2026-01-01 00:00:00.000");
    }

    @Test
    void restrictedRecomputeMatchesBruteForceRecompute() throws Exception {
        register();
        enable();
        seedRestrictionHistory();

        assertThat(mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/refresh"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("\"rows_written\":1");

        // grace = e3 + e7: e6 stays out, because its latest version is linus, and the restriction runs after
        // final = 1 has collapsed the versions. Every row of a key the window did not touch is untouched.
        assertThat(target("'" + DIRTY + "'")).isEqualTo(bruteForce("'" + DIRTY + "'")).contains("105");
        assertThat(target("'ada', 'linus'")).isEqualTo(bruteForce("'ada', 'linus'"));
    }

    @Test
    void restrictedRecomputeBuildsItsJoinFromTheDirtyKeysOnly() throws Exception {
        register();
        enable();
        seedRestrictionHistory();
        // A large tail of another key: an unrestricted recompute would hash every one of these rows.
        execute("INSERT INTO " + ENRICHMENT + " (event_id, owner) SELECT concat('bulk', toString(number)), 'bulk'"
                + " FROM numbers(5000)");

        mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/refresh")).andExpect(status().isOk());
        execute("SYSTEM FLUSH LOGS");

        // Summed over every join in the statement, the dirty-key sub-selects' own window-keyed joins
        // included, so it is bounded rather than equal to the dirty key's row count. An unrestricted recompute
        // builds from the whole enrichment, which is at least the 5,000 bulk rows.
        final long built = Long.parseLong(queryScalar("SELECT ProfileEvents['JoinBuildTableRowCount'] AS n"
                + " FROM system.query_log WHERE type = 'QueryFinish'"
                + " AND query_id LIKE 'adas-" + PIPELINE + "-refresh-write-%'"
                + " ORDER BY event_time_microseconds DESC LIMIT 1", "n"));
        assertThat(built).isPositive().isLessThan(100);
    }

    /**
     * The {@code transform_null_in = 0} pin is load-bearing, shown on the statement ADAS compiled. A
     * declaration whose filter admits NULL keys puts NULL in the dirty set (e8 has no enrichment row). Under
     * {@code transform_null_in = 1}, a NULL then matches it, and every row whose enrichment row the
     * restriction filtered out — ada's two and e6 (linus) — reads NULL and joins the NULL group, beside the
     * three that genuinely have none (e4, e5, e8).
     */
    @Test
    void restrictedRecomputeIsExactOnlyWhileNullNeverSatisfiesIn() throws Exception {
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                        .content(DECLARATION.replace("length(\\\"" + OWNER + "\\\") > 0", "score > 0")))
                .andExpect(status().isCreated());
        enable();
        seedRestrictionHistory();
        seedEvent("e8", 1, 8);

        // The recompute a refresh actually ran over this window, restricted, read back from the query log by
        // its tagged id: preview no longer prints a refresh statement, and this is the one that executed.
        mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/refresh")).andExpect(status().isOk());
        execute("SYSTEM FLUSH LOGS");
        final String statement = queryScalar("SELECT query AS q FROM system.query_log WHERE type = 'QueryFinish'"
                + " AND query_id LIKE 'adas-" + PIPELINE + "-refresh-write-%'"
                + " ORDER BY event_time_microseconds DESC LIMIT 1", "q");
        assertThat(statement).contains("IN (select distinct");
        final String select = statement.substring(statement.indexOf(") select ") + 2);
        final String nullGroup = "SELECT toString(count()) AS n FROM (" + select + ") WHERE owner IS NULL"
                + " SETTINGS final = 1, transform_null_in = ";

        // The hazard is real for this fixture: the NULL group holds six rows, not the three it should.
        assertThat(queryScalar(nullGroup + "1", "n")).isEqualTo("1");
        assertThat(queryScalar("SELECT toString(events) AS n FROM (" + select + ") WHERE owner IS NULL"
                + " SETTINGS final = 1, transform_null_in = 1", "n")).isEqualTo("6");
        // Under the pinned setting the NULL group is never recomputed, exactly as without the restriction.
        assertThat(queryScalar(nullGroup + "0", "n")).isEqualTo("0");
    }
}
