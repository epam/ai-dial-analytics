package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.pipeline.DefaultSchedule;
import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the pipeline registry (capability {@code enrichment-registry}) against real
 * PostgreSQL + ClickHouse: pipeline create derives source/grain from the target enrichment and inlines the
 * authored transform; the governance gate rejects a second pipeline on the same target (409) and, with 422,
 * an unsupported trigger, a group trigger missing its declaration, a bad or duplicated output binding, an
 * unparseable filter, and a filter reaching outside its own source. A request omitting the required
 * {@code enabled} flag is rejected with 400 before the gate runs.
 *
 * <p>Also covers a pipeline that declares its own <b>read source</b>: the values come from a table other than
 * the one its target enrichment hangs off, source-scoping follows the declared table, and the gate refuses
 * a declared source that is unknown, is not a source, is not ACTIVE, lacks the target's grain key (or
 * carries it at another type), or is not scannable. Requires Docker.
 */
class EnrichPipelineRegistryIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String SOURCE = "rr_metrics";
    private static final String ENRICHMENT = "rr_tox";

    /** A second, scannable source carrying the same grain key — the table a declared read source names. */
    private static final String RATES = "rr_rates";
    /** Carries the grain key at the wrong type. */
    private static final String RATES_NUM = "rr_rates_num";
    /** Scannable, but does not carry the grain key at all. */
    private static final String NO_KEY = "rr_no_key";
    /** A second scannable source carrying the grain key compatibly — a read source for a second pipeline. */
    private static final String RATES_ALT = "rr_rates_alt";
    /** An enrichment hanging off {@link #RATES}, for the pipeline that must NOT be bumped by its repoint. */
    private static final String ENRICHMENT_ON_RATES = "rr_tox2";
    /** Identity recorded, schema never defined — a source with no materialized table behind it. */
    private static final String PENDING_SOURCE = "rr_pending";

    @BeforeEach
    void setUp() throws Exception {
        IntegrationTables.source(mockMvc, SOURCE,
                "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                        + "{\"source_name\":\"score\",\"name\":\"score\",\"type\":\"long\"}]",
                "[\"widget_id\"]");
        IntegrationTables.enrichment(mockMvc, ENRICHMENT, SOURCE, "widget_id",
                "[{\"source_name\":\"tox_score\",\"name\":\"tox_score\",\"type\":\"long\"}]");
        IntegrationTables.source(mockMvc, RATES,
                "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\",\"nullable\":false},"
                        + "{\"source_name\":\"rating\",\"name\":\"rating\",\"type\":\"long\"},"
                        + "{\"source_name\":\"event_id\",\"name\":\"event_id\",\"type\":\"string\",\"nullable\":false},"
                        + "{\"source_name\":\"rated_at\",\"name\":\"rated_at\",\"type\":\"timestamp\",\"nullable\":false}]",
                "[\"event_id\"]", "event_id", "rated_at");
        IntegrationTables.source(mockMvc, RATES_NUM,
                "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"long\",\"nullable\":false},"
                        + "{\"source_name\":\"event_id\",\"name\":\"event_id\",\"type\":\"string\",\"nullable\":false},"
                        + "{\"source_name\":\"rated_at\",\"name\":\"rated_at\",\"type\":\"timestamp\",\"nullable\":false}]",
                "[\"event_id\"]", "event_id", "rated_at");
        IntegrationTables.source(mockMvc, RATES_ALT,
                "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\",\"nullable\":false},"
                        + "{\"source_name\":\"rating\",\"name\":\"rating\",\"type\":\"long\"},"
                        + "{\"source_name\":\"event_id\",\"name\":\"event_id\",\"type\":\"string\",\"nullable\":false},"
                        + "{\"source_name\":\"rated_at\",\"name\":\"rated_at\",\"type\":\"timestamp\",\"nullable\":false}]",
                "[\"event_id\"]", "event_id", "rated_at");
        IntegrationTables.source(mockMvc, NO_KEY,
                "[{\"source_name\":\"thing\",\"name\":\"thing\",\"type\":\"string\",\"nullable\":false},"
                        + "{\"source_name\":\"event_id\",\"name\":\"event_id\",\"type\":\"string\",\"nullable\":false},"
                        + "{\"source_name\":\"rated_at\",\"name\":\"rated_at\",\"type\":\"timestamp\",\"nullable\":false}]",
                "[\"event_id\"]", "event_id", "rated_at");
    }

    /**
     * The transform every pipeline here declares. {@code outputs} is keyed by the target column that
     * receives each output, so an output and its column share a name — which is why both enrichment
     * targets in this test declare {@code tox_score}.
     */
    private static String transform() {
        return Transforms.llm().output("tox_score", "How toxic the text is, 0-100.").member();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Deleting the enrichment cascades its pipeline (FK ON DELETE CASCADE); then the sources. The pipeline has
        // to go first here for a second reason too: a source a pipeline DECLARES as its read source refuses to
        // be deleted while that pipeline exists.
        // Enrichments first (each cascades its pipeline), then the sources — a source a pipeline declares as its
        // read source refuses to be deleted while that pipeline exists. Unconditional rather than in the test
        // that creates it: a leaked table turns one failure into a 409 in every later test's setUp.
        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT));
        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT_ON_RATES));
        mockMvc.perform(delete("/v1/tables/" + SOURCE));
        mockMvc.perform(delete("/v1/tables/" + RATES));
        mockMvc.perform(delete("/v1/tables/" + RATES_ALT));
        mockMvc.perform(delete("/v1/tables/" + RATES_NUM));
        mockMvc.perform(delete("/v1/tables/" + NO_KEY));
        mockMvc.perform(delete("/v1/tables/" + PENDING_SOURCE));
    }

    private static String pipelineBody(String name, String triggerKind, String filterSql) {
        return pipelineBody(name, triggerKind, filterSql, null);
    }

    /** As above, declaring {@code source} as the pipeline's read source; null omits it (derive from target). */
    private static String pipelineBody(String name, String triggerKind, String filterSql,
                                   String source) {
        return "{\"name\":\"" + name + "\"," + transform() + ","
                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                + "\"trigger\":{\"kind\":\"" + triggerKind + "\"},"
                + (source == null ? "" : "\"inputs\":[\"" + source + "\"],")
                + "\"filter\":\"" + filterSql + "\","
                + "\"enabled\":true}";
    }

    @Test
    void scheduleTriggerWithoutCronIsStoredWithTheNameDerivedDefault() throws Exception {
        final String expected = DefaultSchedule.cronFor("rr-pipeline-cron");

        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-cron", "schedule", "score > 5")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trigger.cron").value(expected));
        mockMvc.perform(get("/v1/pipelines/rr-pipeline-cron"))
                .andExpect(jsonPath("$.trigger.cron").value(expected));

        // A function of the name alone: deleting and re-registering lands on the same second.
        mockMvc.perform(delete("/v1/pipelines/rr-pipeline-cron")).andExpect(status().isNoContent());
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-cron", "schedule", "score > 5")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trigger.cron").value(expected));
    }

    @Test
    void scheduleDraftWithBlankCronIsStoredWithTheDefault() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-cron-draft", "schedule", "score > 5")
                                .replace("{\"kind\":\"schedule\"}", "{\"kind\":\"schedule\",\"cron\":\"  \"}")
                                .replace("\"enabled\":true", "\"enabled\":false")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trigger.cron").value(DefaultSchedule.cronFor("rr-pipeline-cron-draft")));

        // Arming it asks for no cron: the draft already carries one.
        mockMvc.perform(patch("/v1/pipelines/rr-pipeline-cron-draft").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    void declaredCronIsStoredAsSent() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-cron-set", "schedule", "score > 5")
                                .replace("{\"kind\":\"schedule\"}", "{\"kind\":\"schedule\",\"cron\":\"0 */5 * * * *\"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trigger.cron").value("0 */5 * * * *"));

        // A re-declaration replaces the trigger whole: one that omits the cron falls back to the default
        // rather than keeping the cron the previous declaration stated.
        mockMvc.perform(patch("/v1/pipelines/rr-pipeline-cron-set").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-cron-set", "schedule", "score > 5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trigger.cron").value(DefaultSchedule.cronFor("rr-pipeline-cron-set")));
    }

    @Test
    void retiredOnIngestTriggerIsRefusedNamingSchedule() throws Exception {
        for (String enabled : List.of("true", "false")) {
            mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                            .content(pipelineBody("rr-pipeline-retired", "on_ingest", "score > 5")
                                    .replace("\"enabled\":true", "\"enabled\":" + enabled)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                    .andExpect(jsonPath("$.message").value(Matchers.containsString("'on_ingest' is retired")))
                    .andExpect(jsonPath("$.message").value(Matchers.containsString("'schedule'")));
        }
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-retired", "schedule", "score > 5")))
                .andExpect(status().isCreated());
        mockMvc.perform(patch("/v1/pipelines/rr-pipeline-retired").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-retired", "on_ingest", "score > 5")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("'on_ingest' is retired")));
    }

    @Test
    void retiredScanEveryIsRefusedAtTheBindingOnEitherKind() throws Exception {
        final String enrich = pipelineBody("rr-pipeline-scan-every", "schedule", "score > 5")
                .replace("\"enabled\":true", "\"advanced\":{\"scan_every\":\"60s\"},\"enabled\":true");
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON).content(enrich))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("scan_every")));
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"rr_agg_scan_every\",\"kind\":\"aggregate\",\"target\":\"x\","
                                + "\"advanced\":{\"scan_every\":\"60s\"},\"enabled\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("scan_every")));

        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-scan-every", "schedule", "score > 5")))
                .andExpect(status().isCreated());
        mockMvc.perform(patch("/v1/pipelines/rr-pipeline-scan-every").contentType(MediaType.APPLICATION_JSON)
                        .content(enrich))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("scan_every")));
    }

    @Test
    void createDerivesSourceAndGrainAndServesTheAuthoredTransform() throws Exception {
        // A write answers with what was written — the SOURCE projection — so the declaration comes back
        // and nothing derived does. That is deliberate: a create response a client can echo straight back
        // into a PATCH must not have laundered a derived source into a declared one.
        String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-a", "schedule", "score > 5")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.inputs").doesNotExist())
                .andExpect(jsonPath("$.grain_key").doesNotExist())
                .andExpect(jsonPath("$.evaluator").doesNotExist())
                // The transform IS echoed, on both projections: that is what lets a read-modify-write
                // client patch it back unchanged, which nothing protected while the prompt lived on an
                // immutable evaluator version.
                .andExpect(jsonPath("$.transform.model").value("gpt-4o"))
                .andExpect(jsonPath("$.transform.outputs.tox_score")
                        .value("How toxic the text is, 0-100."))
                .andExpect(jsonPath("$.generation").value(1))
                .andReturn().getResponse().getContentAsString();

        String id = JsonPath.read(content, "$.name");
        // The derivation itself is asserted where derived values live: the compiled projection.
        mockMvc.perform(get("/v1/pipelines/" + id + "?view=compiled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputs[0]").value(SOURCE))
                .andExpect(jsonPath("$.grain_key").value("widget_id"))
                // rr_metrics declares no scan metadata, so version_column is simply absent. A pipeline read
                // must not fail because its source is not scannable — the scan path is where that becomes a
                // 422. (EnrichmentScanIntegrationTest covers a source that does declare the pair.)
                .andExpect(jsonPath("$.version_column").doesNotExist())
                // No inlined evaluator object: every fact it carried is on the entry already — the
                // pipeline's own name, generation and updated_at, plus the transform's members — except
                // the resolved schema, which is the derived top-level member below.
                .andExpect(jsonPath("$.evaluator").doesNotExist())
                .andExpect(jsonPath("$.transform.model").value("gpt-4o"))
                .andExpect(jsonPath("$.response_schema.properties.tox_score.description")
                        .value("How toxic the text is, 0-100."));
        // Update bumps the generation and is visible on read.
        mockMvc.perform(patch("/v1/pipelines/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-a", "schedule", "score > 10")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(2));
        mockMvc.perform(get("/v1/pipelines/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filter").value("score > 10"));
    }

    @Test
    void enabledPipelineAppearsInSyncFeed() throws Exception {
        String created = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-sync", "schedule", "score > 5")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // The updated_at a write reports is usable verbatim as the next watermark: the feed's filter is
        // inclusive, so a reported value finer than the stored one would place the watermark past the pipeline.
        String reportedUpdatedAt = JsonPath.read(created, "$.updated_at");
        mockMvc.perform(get("/v1/pipelines?kind=enrich&updated_since=" + reportedUpdatedAt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[?(@.name=='rr-pipeline-sync')].name").value(hasItem("rr-pipeline-sync")));
        mockMvc.perform(get("/v1/pipelines/" + JsonPath.<String>read(created, "$.name")))
                .andExpect(jsonPath("$.updated_at").value(reportedUpdatedAt));

        // The sync feed is `view=compiled`, and only it: the resolved schema and the resolved input are
        // derived values, so an authored listing carries neither. Asserting them without naming the
        // projection is what an old runner did, and is exactly the call this change stops answering.
        mockMvc.perform(get("/v1/pipelines?kind=enrich&view=compiled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[?(@.name=='rr-pipeline-sync')].response_schema").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.empty())))
                .andExpect(jsonPath("$.pipelines[?(@.name=='rr-pipeline-sync')].inputs[0]").value(
                        org.hamcrest.Matchers.hasItem(SOURCE)));

        // ...and the default listing is the authored declaration: same entry, no derived members. The
        // transform is on BOTH, because it is authored rather than derived.
        mockMvc.perform(get("/v1/pipelines?kind=enrich"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[?(@.name=='rr-pipeline-sync')].name").value(
                        org.hamcrest.Matchers.hasItem("rr-pipeline-sync")))
                .andExpect(jsonPath("$.pipelines[?(@.name=='rr-pipeline-sync')].response_schema").value(
                        org.hamcrest.Matchers.empty()))
                .andExpect(jsonPath("$.pipelines[?(@.name=='rr-pipeline-sync')].outputs").value(
                        org.hamcrest.Matchers.empty()))
                .andExpect(jsonPath("$.pipelines[?(@.name=='rr-pipeline-sync')].transform.model").value(
                        org.hamcrest.Matchers.hasItem("gpt-4o")));

        // A future updated_since excludes it.
        mockMvc.perform(get("/v1/pipelines?kind=enrich&updated_since=2999-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[?(@.name=='rr-pipeline-sync')]").value(
                        org.hamcrest.Matchers.empty()));
    }

    /**
     * The listing reports the registry as it stands: unfiltered it carries a disabled pipeline too, which is
     * the only way to see that such a pipeline is registered at all. {@code enabled} narrows it to one flag
     * or the other, and a consumer that executes pipelines (the Enrichment Runner) passes
     * {@code enabled=true} rather than relying on the default.
     */
    @Test
    void listingCarriesDisabledPipelinesAndNarrowsOnTheEnabledFlag() throws Exception {
        // Two pipelines, one per target enrichment (one pipeline per enrichment), differing only in `enabled`.
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-on", "schedule", "score > 5")))
                .andExpect(status().isCreated());
        IntegrationTables.enrichment(mockMvc, ENRICHMENT_ON_RATES, RATES, "widget_id",
                "[{\"source_name\":\"tox_score\",\"name\":\"tox_score\",\"type\":\"long\"}]");
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"rr-pipeline-off\"," + transform() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT_ON_RATES + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"filter\":\"rating > 5\","
                                + "\"enabled\":false}"))
                .andExpect(status().isCreated());

        // Unfiltered: the whole registry. The disabled pipeline resolves like any other — schema composed,
        // source and grain derived — since it is registered but not running, not a degraded record. Asked
        // in the compiled projection, which is where derived members live at all.
        mockMvc.perform(get("/v1/pipelines?kind=enrich&view=compiled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[?(@.name=='rr-pipeline-on')].enabled").value(hasItem(true)))
                .andExpect(jsonPath("$.pipelines[?(@.name=='rr-pipeline-off')].enabled").value(hasItem(false)))
                .andExpect(jsonPath("$.pipelines[?(@.name=='rr-pipeline-off')].transform.model")
                        .value(hasItem("gpt-4o")))
                .andExpect(jsonPath("$.pipelines[?(@.name=='rr-pipeline-off')].inputs[*]").value(hasItem(RATES)))
                .andExpect(jsonPath("$.pipelines[?(@.name=='rr-pipeline-off')].grain_key").value(hasItem("widget_id")));

        // enabled=true: what a runner syncs and executes.
        mockMvc.perform(get("/v1/pipelines?kind=enrich&enabled=true&view=compiled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[?(@.name=='rr-pipeline-on')].name").value(hasItem("rr-pipeline-on")))
                .andExpect(jsonPath("$.pipelines[?(@.name=='rr-pipeline-off')]").value(empty()));

        // enabled=false: the switched-off ones alone.
        mockMvc.perform(get("/v1/pipelines?kind=enrich&enabled=false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[?(@.name=='rr-pipeline-off')].name").value(hasItem("rr-pipeline-off")))
                .andExpect(jsonPath("$.pipelines[?(@.name=='rr-pipeline-on')]").value(empty()));

        // The filter composes with the incremental watermark rather than replacing it.
        mockMvc.perform(get("/v1/pipelines?kind=enrich&enabled=true&updated_since=2999-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[?(@.name=='rr-pipeline-on')]").value(empty()));

        // Only `true` and `false` are accepted. Spring's own boolean conversion is lenient — it takes
        // 1/yes/on and their negatives, and maps an EMPTY value to null — so `?enabled=` from a caller
        // interpolating an unset variable would otherwise be served as the whole registry under what
        // looks like a filter. Every one of these is refused instead, including the repeated parameter,
        // where lenient binding would silently let the first value win.
        for (String value : new String[]{"", "maybe", "1", "0", "yes", "no", "on", "off", "TRUE", "True",
                "true,false", " true"}) {
            mockMvc.perform(get("/v1/pipelines?kind=enrich&enabled=" + value))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("bad_request"));
        }
        mockMvc.perform(get("/v1/pipelines?kind=enrich&enabled=true&enabled=false"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    /**
     * A pipeline's {@code version_column} is derived from its source, so it can change without the pipeline being
     * touched — and {@code generation}, the runner's change signal, would otherwise not move. Repointing the
     * source's version column therefore bumps every pipeline reading that source, in the same transaction as the
     * rename, and advances {@code updated_at} as well: the sync feed selects on {@code updated_at}, so a
     * bumped generation the feed would never return is no signal at all. A pipeline on another source is
     * untouched, and a rename of a column the pair does not name bumps nothing.
     */
    @Test
    void repointingTheSourceVersionColumnBumpsItsPipelines() throws Exception {
        // The fixture source declares no pair; this one does, with the version on a non-ordering-key column
        // so the rename is not refused by the ordering-key guard.
        mockMvc.perform(post("/v1/tables").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"rr_scannable\",\"type\":\"source\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/tables/rr_scannable/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columns\":[{\"source_name\":\"widget_id\",\"name\":\"widget_id\","
                                + "\"type\":\"string\"},{\"source_name\":\"seen_at\",\"name\":\"seen_at\","
                                + "\"type\":\"timestamp\",\"nullable\":false},{\"source_name\":\"score\","
                                + "\"name\":\"score\",\"type\":\"long\"}],\"ordering_key\":[\"widget_id\"],"
                                + "\"identity_column\":\"widget_id\",\"version_column\":\"seen_at\"}"))
                .andExpect(status().isOk());
        IntegrationTables.enrichment(mockMvc, "rr_scannable_enr", "rr_scannable", "widget_id",
                "[{\"source_name\":\"tox_score\",\"name\":\"tox_score\",\"type\":\"long\"}]");
        // A second pipeline, on the fixture's own source, which the repoint must leave alone.
        String otherId = JsonPath.read(mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-other", "schedule", "score > 5")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.name");

        try {
            String created = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"rr-pipeline-scannable\"," + transform() + ","
                                    + "\"kind\":\"enrich\",\"target\":\"rr_scannable_enr\","
                                    + "\"trigger\":{\"kind\":\"schedule\"},"
                                    + "\"enabled\":true}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.generation").value(1))
                    .andReturn().getResponse().getContentAsString();
            String id = JsonPath.read(created, "$.name");
            String updatedAtBefore = JsonPath.read(created, "$.updated_at");
            mockMvc.perform(get("/v1/pipelines/" + id + "?view=compiled"))
                    .andExpect(jsonPath("$.version_column").value("seen_at"));

            // A rename that leaves the pair alone is not a pipeline change.
            mockMvc.perform(patch("/v1/tables/rr_scannable/schema").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rename\":[{\"from\":\"score\",\"to\":\"rating\"}]}"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/v1/pipelines/" + id))
                    .andExpect(jsonPath("$.generation").value(1))
                    .andExpect(jsonPath("$.updated_at").value(updatedAtBefore));

            mockMvc.perform(patch("/v1/tables/rr_scannable/schema").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rename\":[{\"from\":\"seen_at\",\"to\":\"ingested_at\"}]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version_column").value("ingested_at"));

            String after = mockMvc.perform(get("/v1/pipelines/" + id + "?view=compiled"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.generation").value(2))
                    .andExpect(jsonPath("$.version_column").value("ingested_at"))
                    .andReturn().getResponse().getContentAsString();
            // updated_at moved with it, which is what puts the pipeline back in an incremental sync's window.
            assertThat(JsonPath.<String>read(after, "$.updated_at")).isNotEqualTo(updatedAtBefore);

            // And an *incremental* sync sees it: a watermark just past the pipeline's pre-rename updated_at
            // would have excluded it before the repoint, and returns it after.
            String watermark = Instant.parse(updatedAtBefore).plusNanos(1_000).toString();
            mockMvc.perform(get("/v1/pipelines?kind=enrich&view=compiled&updated_since=" + watermark))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pipelines[?(@.name=='rr-pipeline-scannable')].version_column")
                            .value(hasItem("ingested_at")))
                    .andExpect(jsonPath("$.pipelines[?(@.name=='rr-pipeline-scannable')].generation").value(hasItem(2)));

            // The pipeline on the other source never moved.
            mockMvc.perform(get("/v1/pipelines/" + otherId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.generation").value(1));
        } finally {
            mockMvc.perform(delete("/v1/tables/rr_scannable_enr"));
            mockMvc.perform(delete("/v1/tables/rr_scannable"));
        }
    }

    @Test
    void secondPipelineOnSameTargetIsRejected() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-1", "schedule", "score > 5")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-2", "schedule", "score > 5")))
                .andExpect(status().isConflict())
                // A state conflict, not a validation failure — the code the aggregate path already used,
                // now shared. The enrichment registry reported a *validation* code at 409, which told a
                // client to fix a declaration that had nothing wrong with it.
                .andExpect(jsonPath("$.error").value("pipeline_conflict"));
    }

    @Test
    void missingEnabledRegistersTheEnrichPipelineDisabled() throws Exception {
        // `enabled` is no longer required at registration: registering is now the act of beginning to
        // author a declaration, so dormancy is the expected initial state rather than an accident, and an
        // absent (or explicitly null) flag means false — exactly the intent the old refusal protected.
        String withoutEnabled = "{\"name\":\"rr-pipeline-noflag\"," + transform() + ","
                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"trigger\":{\"kind\":\"schedule\"}}";
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON).content(withoutEnabled))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(delete("/v1/pipelines/rr-pipeline-noflag")).andExpect(status().isNoContent());

        String nullEnabled = withoutEnabled.substring(0, withoutEnabled.length() - 1) + ",\"enabled\":null}";
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON).content(nullEnabled))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void unsupportedTriggerKindIsRejected() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-push", "push", "score > 5")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"));
    }

    @Test
    void groupTriggerWithoutItsDeclarationIsRejected() throws Exception {
        // group is a supported trigger, but only with the declaration that makes it operable: a group key
        // equal to the target's grain, and a readiness condition. Without them the pipeline would mark groups
        // dirty and never evaluate them, so it is refused rather than stored.
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-grp", "group", "score > 5")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"));
    }

    /**
     * An output resolving to no column on the target is refused naming it — the derived-mapping equivalent
     * of the bad output binding a caller used to be able to declare.
     */
    @Test
    void transformOutputResolvingToNoTargetColumnIsRejected() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"rr-pipeline-badcol\","
                                + Transforms.llm()
                                        .output("ghost_column", "Nothing on the target receives this.")
                                        .member() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("ghost_column")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("resolves to no column")));
    }

    /**
     * One column takes exactly one output, and that is now true <b>by construction</b> rather than by a
     * check: {@code outputs} is a map, so no two entries can share a name, and an output resolves to the
     * column of that name. The duplicate this test used to send has no wire form any more.
     *
     * <p>The guard itself is kept in the derivation (a column receiving two outputs is still refused),
     * because it protects against a resolution bug rather than a caller — both write paths key their
     * derived values by target column, so a duplicate would silently drop one instead of failing.
     */
    @Test
    void oneColumnTakesExactlyOneOutputByConstruction() throws Exception {
        final String duplicated = "{\"name\":\"rr-pipeline-dupcol\"," + transform() + ","
                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"trigger\":{\"kind\":\"schedule\"},"
                + "\"output_bindings\":[{\"column\":\"tox_score\",\"var\":\"tox_score\"},"
                + "{\"column\":\"tox_score\",\"var\":\"tox_score\"}],"
                + "\"enabled\":true}";
        // Not a 422 about duplicates: `output_bindings` is not a member of the request at all, so it is
        // refused at the binding with a 400 naming the field.
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON).content(duplicated))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("output_bindings")));
    }

    @Test
    void unparseableFilterIsRejected() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-badfilter", "schedule", "score >>> 5")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"));
    }

    @Test
    void pipelineReadsTheSourceItDeclaresRatherThanItsTargetsSourceTable() throws Exception {
        // The enrichment hangs off rr_metrics (grain widget_id), but its values are derived from rr_rates,
        // which carries widget_id too. This is the shape the derivation used to forbid.
        String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-declared", "schedule", "rating > 3", RATES)))
                .andExpect(status().isCreated())
                // A DECLARED input is echoed by the authored projection — that is the whole difference
                // between declaring one and inheriting the target's parent, and it is why a write answers
                // with `inputs` here and with nothing at all in the inheriting case.
                .andExpect(jsonPath("$.inputs[0]").value(RATES))
                .andReturn().getResponse().getContentAsString();

        String id = JsonPath.read(content, "$.name");
        mockMvc.perform(get("/v1/pipelines/" + id + "?view=compiled"))
                .andExpect(jsonPath("$.grain_key").value("widget_id"))
                // version_column follows the READ source: rr_rates declares scan metadata, rr_metrics does not.
                .andExpect(jsonPath("$.version_column").value("rated_at"));
        mockMvc.perform(get("/v1/pipelines/" + id))
                .andExpect(jsonPath("$.inputs[0]").value(RATES));
        mockMvc.perform(get("/v1/pipelines?kind=enrich"))
                .andExpect(jsonPath("$.pipelines[?(@.name=='rr-pipeline-declared')].inputs[*]").value(hasItem(RATES)));
        // The enrichment's own attachment is untouched: it still hangs off rr_metrics and joins on it.
        mockMvc.perform(get("/v1/tables/" + ENRICHMENT))
                .andExpect(jsonPath("$.source_table").value(SOURCE));
    }

    @Test
    void declarationPatchOmittingItsDeclaredSourceIsRefused() throws Exception {
        String id = JsonPath.read(mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-clear", "schedule", "rating > 3", RATES)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.name");

        // Omitting `inputs` on a DECLARATION patch used to silently repoint the pipeline back at its
        // target's parent source. It is now refused naming the member, because that is a change to what
        // the pipeline reads which the patch does not state — and source-scoping, the grain-key check and
        // scannability all follow the read source, so the repoint changes what every later check means.
        mockMvc.perform(patch("/v1/pipelines/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-clear", "schedule", "score > 5")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("inputs")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("repoint the pipeline")));

        // The pipeline still reads what it declared.
        mockMvc.perform(get("/v1/pipelines/" + id))
                .andExpect(jsonPath("$.inputs[0]").value(RATES));
    }

    /** A pipeline that never declared a source stays patchable without {@code inputs}. */
    @Test
    void declarationPatchOfAnInheritingPipelineNeedNotRestateInputs() throws Exception {
        String id = JsonPath.read(mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-inherit", "schedule", "score > 5")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.name");

        mockMvc.perform(patch("/v1/pipelines/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-inherit", "schedule", "score > 10")))
                .andExpect(status().isOk())
                // The patch is accepted without restating `inputs`, and the authored answer keeps saying
                // "I declared none" rather than reporting the inherited one — which is the property that
                // makes this response safe to send straight back as the next patch.
                .andExpect(jsonPath("$.inputs").doesNotExist());
        mockMvc.perform(get("/v1/pipelines/" + id + "?view=compiled"))
                .andExpect(jsonPath("$.inputs[0]").value(SOURCE));
    }

    @Test
    void governedSqlIsScopedToTheDeclaredReadSource() throws Exception {
        // `score` is a column of the target's source table, not of the declared read source. Source-scoping
        // follows the table the pipeline reads, so this is a 422 rather than a scan-time ClickHouse error.
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-scoped", "schedule", "score > 5", RATES)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"));
    }

    @Test
    void unknownOrNonSourceReadSourceIsRejected() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-nosrc", "schedule", "rating > 3", "rr_absent")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("does not exist")));

        // An enrichment is reached through its source's grain join; it is not a table a pipeline reads.
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-enrsrc", "schedule", "tox_score > 3", ENRICHMENT)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("is not a source table")));
    }

    @Test
    void nonActiveDeclaredReadSourceIsRejected() throws Exception {
        // Identity recorded, schema never defined: there is no ClickHouse table to read. Refused for the
        // same reason a non-ACTIVE target enrichment is, rather than at the runner's first scan.
        mockMvc.perform(post("/v1/tables").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + PENDING_SOURCE + "\",\"type\":\"source\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-pending", "schedule", "score > 5", PENDING_SOURCE)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("is not ACTIVE")));
    }

    @Test
    void readSourceMissingTheGrainKeyIsRejected() throws Exception {
        // Without the grain key the pipeline could derive values it has no way to address on the target.
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-nokey", "schedule", "thing = 'x'", NO_KEY)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("grain key 'widget_id'")));
    }

    @Test
    void readSourceCarryingTheGrainKeyAtAnotherTypeIsRejected() throws Exception {
        // Same name, different type: the grain join would never match, so the enrichment would fill with
        // rows nothing reads. Caught here rather than showing up as a silently empty enrichment.
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-badtype", "schedule", "widget_id > 3", RATES_NUM)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("is keyed on it as string")));
    }

    @Test
    void unscannableDeclaredReadSourceIsRejectedAtRegistration() throws Exception {
        // rr_metrics declares no scan metadata. Omitting `source` and inheriting it is still accepted (see
        // createDerivesSourceAndGrainAndServesTheAuthoredTransform) — that pipeline is refused on the scan path instead, and
        // must keep registering. DECLARING an unscannable table is a new statement of intent the gate can
        // answer immediately, so the two differ deliberately.
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-unscannable", "schedule", "score > 5", SOURCE)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("scan metadata")));
    }

    @Test
    void repointingTheVersionColumnBumpsOnlyPipelinesThatReadThatTable() throws Exception {
        String reader = JsonPath.read(mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-reader", "schedule", "rating > 3", RATES)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.name");

        // A second pipeline whose TARGET hangs off rr_rates but which reads somewhere else entirely. It must
        // not be bumped: the renamed column never appears on its scan pages.
        IntegrationTables.enrichment(mockMvc, ENRICHMENT_ON_RATES, RATES, "widget_id",
                "[{\"source_name\":\"tox_score\",\"name\":\"tox_score\",\"type\":\"long\"}]");
        String bystander = JsonPath.read(mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"rr-pipeline-bystander\"," + transform() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT_ON_RATES + "\","
                                + "\"inputs\":[\"" + RATES_ALT + "\"],"
                                + "\"trigger\":{\"kind\":\"schedule\"},\"filter\":\"rating > 3\","
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.name");

        long bystanderBefore = ((Number) JsonPath.read(
                mockMvc.perform(get("/v1/pipelines/" + bystander)).andReturn().getResponse().getContentAsString(),
                "$.generation")).longValue();

        mockMvc.perform(patch("/v1/tables/" + RATES + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rename\":[{\"from\":\"rated_at\",\"to\":\"rated_at_v2\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version_column").value("rated_at_v2"));

        mockMvc.perform(get("/v1/pipelines/" + reader + "?view=compiled"))
                .andExpect(jsonPath("$.version_column").value("rated_at_v2"))
                .andExpect(jsonPath("$.generation").value(2));
        mockMvc.perform(get("/v1/pipelines/" + bystander))
                .andExpect(jsonPath("$.generation").value((int) bystanderBefore));
    }

    /**
     * A template-less {@code llm} transform is refused at <b>every</b> write, armed or not.
     *
     * <p>The relaxation this change makes is referential — whether the template's placeholders resolve
     * against {@code transform.inputs} — and it is deliberately not a licence to store a transform with no
     * prompt. Nothing else caught this: the placeholder rule returns immediately on a blank template, so
     * the declaration was stored, armed and left enabled, and the model would have been sent a request
     * with no instructions in it.
     */
    @Test
    void llmTransformWithNoRequestTemplateIsRejected() throws Exception {
        // Registered ARMED, so the write runs the gate; a disabled registration is a draft and is stored.
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"rr-pipeline-notemplate\","
                                + "\"transform\":{\"type\":\"llm\",\"model\":\"gpt-4o\","
                                + "\"outputs\":{\"tox_score\":\"How toxic the text is, 0-100.\"}},"
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("requires a request_template")));
    }

    /**
     * Dropping {@code request_template} from a live pipeline by re-declaring without it is the path that
     * mattered: a declaration patch supplies the whole declaration, so an omitted member is a cleared one.
     */
    @Test
    void patchThatDropsTheRequestTemplateIsRejected() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-armed", "schedule", "score > 5")))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/v1/pipelines/rr-pipeline-armed").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"" + ENRICHMENT + "\","
                                + "\"transform\":{\"type\":\"llm\",\"model\":\"gpt-4o\","
                                + "\"outputs\":{\"tox_score\":\"How toxic the text is, 0-100.\"}},"
                                + "\"trigger\":{\"kind\":\"schedule\"},\"filter\":\"score > 5\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("requires a request_template")));

        mockMvc.perform(delete("/v1/pipelines/rr-pipeline-armed"));
    }

    /**
     * {@code response_schema} is the fifth {@code llm}-only member, and it is refused on a {@code sql}
     * transform like the other four.
     *
     * <p>It is the one whose omission was visible on the wire rather than merely untidy: a stored schema
     * becomes the compiled projection's derived {@code response_schema}, which the pipeline read documents
     * as absent for a {@code sql} transform "which constrains no model response". A Class-1 derivation
     * asks no model anything, so serving one would tell a consumer the opposite of the truth.
     */
    @Test
    void sqlTransformCarryingResponseSchemaIsRejected() throws Exception {
        // Registered ARMED, so the write runs the gate; a disabled registration is a draft and is stored.
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"rr-pipeline-sqlschema\","
                                + Transforms.sql().output("tox_score", "score * 2")
                                        .responseSchema("{\"type\":\"object\",\"properties\":{}}").member() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("'transform.response_schema' (llm-only)")));
    }

    @Test
    void filterReferencingAnEnrichmentColumnIsRejected() throws Exception {
        // The source's query model publishes its materialized enrichments' columns, so this parses; but a
        // pipeline-scoped read drops the enrichment joins, so the rendered scan would reference a table absent
        // from its own FROM clause. Rejected here rather than by ClickHouse at scan time.
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("rr-pipeline-enrich", "schedule",
                                "\\\"" + ENRICHMENT + ".tox_score\\\" > 0", "tox_score")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"));
    }
}
