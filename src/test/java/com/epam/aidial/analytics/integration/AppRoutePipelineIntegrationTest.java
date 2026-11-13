package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.enrichment.support.RunnerContract;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An {@code llm} transform calling a DIAL application route (preset {@code dial_app_route}) and the {@code app-routes}
 * instance setting that allows it (capabilities {@code enrichment-registry} and {@code instance-settings}): the
 * transform's route and response mapping round-trip and reach the feed; their shape is refused when the pipeline is
 * armed; arming needs the pair allowed; and the setting cannot drop a pair an enabled pipeline calls. Requires
 * Docker.
 */
class AppRoutePipelineIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String SOURCE = "ar_metrics";
    private static final String ENRICHMENT = "ar_tox";
    private static final String PIPELINE = "ar-pipeline";
    private static final String SETTING = "/v1/settings/app-routes";
    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String ALLOW_INSIGHTS = "{\"routes\":[{\"deployment\":\"insights-app\",\"route\":\"insights\"}]}";

    /** The lock {@code AppRoutesSettingService} takes around both checks; a test holds it to force an overlap. */
    private static final String APP_ROUTES_LOCK =
            "SELECT pg_advisory_xact_lock(hashtext('instance_setting'), hashtext('app-routes'))";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM instance_setting");
        IntegrationTables.source(mockMvc, SOURCE,
                "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                        + "{\"source_name\":\"score\",\"name\":\"score\",\"type\":\"long\"}]",
                "[\"widget_id\"]");
        IntegrationTables.enrichment(mockMvc, ENRICHMENT, SOURCE, "widget_id",
                "[{\"source_name\":\"tox_score\",\"name\":\"tox_score\",\"type\":\"long\"}]");
    }

    @AfterEach
    void tearDown() throws Exception {
        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT));
        mockMvc.perform(delete("/v1/tables/" + SOURCE));
        jdbc.update("DELETE FROM instance_setting");
    }

    private static Transforms.Builder route(String route) {
        return Transforms.llm().model("insights-app").preset("dial_app_route").route(route)
                .output("tox_score", "How toxic the text is, 0-100.");
    }

    private static String body(Transforms.Builder transform, boolean enabled) {
        return "{\"name\":\"" + PIPELINE + "\"," + transform.member() + ","
                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                + "\"trigger\":{\"kind\":\"schedule\"},\"filter\":\"score > 5\","
                + "\"enabled\":" + enabled + "}";
    }

    private void allowInsights() throws Exception {
        mockMvc.perform(put(SETTING).contentType(JSON).content(ALLOW_INSIGHTS)).andExpect(status().isOk());
    }

    /** A route transform whose single output reads its value from {@code outputJson}, an output object. */
    private static Transforms.Builder routeWithOutput(String outputJson) {
        return Transforms.llm().model("insights-app").preset("dial_app_route").route("insights")
                .outputObject("tox_score", outputJson);
    }

    @Test
    void allowedRoutePipelineIsArmedAndTheFeedCarriesItsCallAndOutputSources() throws Exception {
        allowInsights();
        mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(
                        routeWithOutput("{\"prose\":\"How toxic.\",\"source\":\"scores.toxicity\"}"), true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transform.preset").value("dial_app_route"))
                .andExpect(jsonPath("$.transform.route").value("insights"))
                .andExpect(jsonPath("$.transform.outputs.tox_score.source").value("scores.toxicity"))
                .andExpect(jsonPath("$.transform.outputs.tox_score.prose").value("How toxic."));

        final String entry = "$.pipelines[?(@.name=='" + PIPELINE + "')]";
        mockMvc.perform(get("/v1/pipelines?kind=enrich&view=compiled"))
                .andExpect(status().isOk())
                .andExpect(header().string(RunnerContract.HEADER, "5"))
                .andExpect(jsonPath(entry + ".transform.route").value(hasItem("insights")))
                .andExpect(jsonPath(entry + ".transform.model").value(hasItem("insights-app")))
                .andExpect(jsonPath(entry + ".outputs[0].source").value(hasItem("scores.toxicity")));
    }

    /** An output with a {@code source} carries it on its compiled entry; one without carries no {@code source} member. */
    @Test
    void compiledOutputCarriesSourceOnlyWhereDeclared() throws Exception {
        final String target = "ar_nested";
        IntegrationTables.enrichment(mockMvc, target, SOURCE, "widget_id",
                "[{\"source_name\":\"title\",\"name\":\"title\",\"type\":\"string\"},"
                        + "{\"source_name\":\"intent_counts__check\",\"name\":\"intent_counts__check\",\"type\":\"long\"}]");
        try {
            allowInsights();
            final Transforms.Builder transform = Transforms.llm().model("insights-app").preset("dial_app_route")
                    .route("insights").outputObject("title", "{}")
                    .outputObject("intent_counts__check", "{\"source\":\"intent_counts.check\"}");
            mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(transform, true)
                            .replace("\"target\":\"" + ENRICHMENT + "\"", "\"target\":\"" + target + "\"")))
                    .andExpect(status().isCreated());

            final String compiled = mockMvc.perform(get("/v1/pipelines/" + PIPELINE + "?view=compiled"))
                    .andReturn().getResponse().getContentAsString();
            final List<Map<String, Object>> outputs = JsonPath.read(compiled, "$.outputs");
            assertThat(outputs).filteredOn(entry -> "title".equals(entry.get("name"))).singleElement()
                    .satisfies(entry -> assertThat(entry).doesNotContainKey("source"));
            assertThat(outputs).filteredOn(entry -> "intent_counts__check".equals(entry.get("name"))).singleElement()
                    .satisfies(entry -> assertThat(entry).containsEntry("source", "intent_counts.check"));
        } finally {
            mockMvc.perform(delete("/v1/pipelines/" + PIPELINE));
            mockMvc.perform(delete("/v1/tables/" + target));
        }
    }

    /** The route schema follows the column: {@code tox_score} is nullable, so it may be null or absent. */
    @Test
    @SuppressWarnings("unchecked")
    void routeSchemaFollowsColumnNullability() throws Exception {
        allowInsights();
        mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(route("insights"), true)))
                .andExpect(status().isCreated());
        final String compiled = mockMvc.perform(get("/v1/pipelines/" + PIPELINE + "?view=compiled"))
                .andReturn().getResponse().getContentAsString();

        assertThat((List<String>) JsonPath.read(compiled, "$.response_schema.required")).isEmpty();
        assertThat((List<String>) JsonPath.read(compiled, "$.response_schema.properties.tox_score.type"))
                .containsExactly("integer", "null");
    }

    @Test
    void outputSourceOnChatTransformAndJsonataOnRouteOutputAreRefused() throws Exception {
        allowInsights();
        mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(Transforms.llm()
                        .outputObject("tox_score", "{\"source\":\"scores.toxicity\"}"), true)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("source")));

        mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(
                        routeWithOutput("{\"jsonata\":\"scores.toxicity\"}"), true)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("jsonata")));
    }

    @Test
    void unparseableOrIdentityOutputSourceIsRefused() throws Exception {
        allowInsights();
        for (String source : List.of("{title: ", "tox_score")) {
            mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(
                            routeWithOutput("{\"source\":\"" + source + "\"}"), true)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                    .andExpect(jsonPath("$.message").value(containsString("tox_score")));
        }
    }

    @Test
    void chatCompletionTransformCarriesNoRouteMember() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(
                        Transforms.llm().output("tox_score", "How toxic the text is, 0-100."), true)))
                .andExpect(status().isCreated());
        final String source = mockMvc.perform(get("/v1/pipelines/" + PIPELINE))
                .andReturn().getResponse().getContentAsString();
        final String compiled = mockMvc.perform(get("/v1/pipelines/" + PIPELINE + "?view=compiled"))
                .andReturn().getResponse().getContentAsString();
        for (String read : List.of(source, compiled)) {
            final Map<String, Object> transform = JsonPath.read(read, "$.transform");
            assertThat(transform).doesNotContainKey("route");
        }
    }

    @Test
    void armingWithNoSettingStoredIsRefused() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(route("insights"), true)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("app-routes")));
    }

    @Test
    void armingUnlistedRouteIsRefusedAndTheComparisonIsCaseSensitive() throws Exception {
        allowInsights();
        for (String route : List.of("summaries", "Insights")) {
            mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(route(route), true)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                    .andExpect(jsonPath("$.message").value(containsString("insights-app")))
                    .andExpect(jsonPath("$.message").value(containsString(route)));
        }
    }

    @Test
    void disabledDraftIsStoredBeforeItsRouteIsAllowedAndArmedAfter() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(route("insights"), false)))
                .andExpect(status().isCreated());
        mockMvc.perform(patch("/v1/pipelines/" + PIPELINE).contentType(JSON).content("{\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"));

        allowInsights();
        mockMvc.perform(patch("/v1/pipelines/" + PIPELINE).contentType(JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../admin", "a/./b", "/insights", "insights/", "a//b", "insights?x=1", "%2e%2e/admin"})
    void malformedRouteIsRefusedWhenArmed(String bad) throws Exception {
        allowInsights();
        mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(route(bad), true)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("transform.route")));
    }

    @Test
    void appRouteTransformWithoutRouteIsRefusedWhenArmed() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(route(null), true)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("transform.route")));
    }

    @Test
    void routeOnChatTransformIsRefused() throws Exception {
        final Transforms.Builder withRoute = Transforms.llm().route("insights")
                .output("tox_score", "How toxic the text is, 0-100.");
        mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(withRoute, true)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("transform.route")));
    }

    @Test
    void sqlTransformCarryingRouteIsRefused() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(
                        Transforms.sql().route("insights").output("tox_score", "score"), true)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("transform.route")));
    }

    @Test
    void unknownPresetIsBadRequest() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(
                        Transforms.llm().preset("embeddings").output("tox_score", "x"), true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void settingCannotDropPairThatEnabledPipelineCalls() throws Exception {
        allowInsights();
        mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(route("insights"), true)))
                .andExpect(status().isCreated());

        mockMvc.perform(put(SETTING).contentType(JSON).content("{\"routes\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("app_route_in_use"))
                .andExpect(jsonPath("$.message").value(containsString(PIPELINE)));
        mockMvc.perform(get(SETTING)).andExpect(jsonPath("$.routes[0].route").value("insights"));

        mockMvc.perform(patch("/v1/pipelines/" + PIPELINE).contentType(JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(put(SETTING).contentType(JSON).content("{\"routes\":[]}")).andExpect(status().isOk());
        mockMvc.perform(patch("/v1/pipelines/" + PIPELINE).contentType(JSON).content("{\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"));
    }

    /**
     * An arm and a removal of the same pair overlap for real: a separate transaction holds the lock both take, both
     * requests are seen waiting on it, and only then is it released. Exactly one succeeds, the other is refused by
     * its own check, and the invariant holds afterwards: the pipeline is enabled only if the setting still lists its
     * pair.
     */
    @Test
    void concurrentArmAndRemovalNeverBothSucceed() throws Exception {
        allowInsights();
        mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(route("insights"), false)))
                .andExpect(status().isCreated());

        final Callable<Integer> arm = () -> mockMvc.perform(patch("/v1/pipelines/" + PIPELINE).contentType(JSON)
                .content("{\"enabled\":true}")).andReturn().getResponse().getStatus();
        final Callable<Integer> drop = () -> mockMvc.perform(put(SETTING).contentType(JSON)
                .content("{\"routes\":[]}")).andReturn().getResponse().getStatus();
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        final List<Integer> statuses;
        try {
            final Future<Integer> armed;
            final Future<Integer> dropped;
            try (Connection holder = dataSource.getConnection()) {
                holder.setAutoCommit(false);
                try {
                    try (Statement lock = holder.createStatement()) {
                        lock.execute(APP_ROUTES_LOCK);
                    }
                    armed = pool.submit(arm);
                    dropped = pool.submit(drop);
                    awaitAdvisoryLockWaiters(2);
                } finally {
                    // Ending the transaction releases its lock, and both waiting requests contend for it.
                    holder.rollback();
                    holder.setAutoCommit(true);
                }
            }
            statuses = List.of(armed.get(30, TimeUnit.SECONDS), dropped.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).filteredOn(code -> code == 200).hasSize(1);
        assertThat(statuses).filteredOn(code -> code != 200).singleElement().isIn(409, 422);
        final boolean enabled = JsonPath.read(mockMvc.perform(get("/v1/pipelines/" + PIPELINE))
                .andReturn().getResponse().getContentAsString(), "$.enabled");
        final List<Object> routes = JsonPath.read(mockMvc.perform(get(SETTING))
                .andReturn().getResponse().getContentAsString(), "$.routes");
        assertThat(enabled).isEqualTo(!routes.isEmpty());
    }

    /** Waits, bounded, until {@code count} transactions are blocked on a two-key advisory lock. */
    private void awaitAdvisoryLockWaiters(int count) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            final Long waiting = jdbc.queryForObject("SELECT count(*) FROM pg_locks"
                    + " WHERE locktype = 'advisory' AND objsubid = 2 AND NOT granted", Long.class);
            if (waiting != null && waiting >= count) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("expected " + count + " requests waiting on the app-routes lock, saw "
                        + waiting);
            }
            Thread.sleep(20);
        }
    }

    /**
     * The allowlist is an instance setting, so a bundle does not carry it: an imported route pipeline whose pair
     * the target does not allow is reported not armable, naming the setting, until the pair is allowed there.
     */
    @Test
    void importedRoutePipelineIsArmableOnlyOnceItsPairIsAllowed() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(route("insights"), false)))
                .andExpect(status().isCreated());
        final String bundle = mockMvc.perform(post("/v1/catalog/export").contentType(JSON)
                        .content("{\"components\":[{\"type\":\"pipeline\",\"name\":\"" + PIPELINE + "\"}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(bundle).doesNotContain("app-routes");
        mockMvc.perform(delete("/v1/pipelines/" + PIPELINE)).andExpect(status().isNoContent());
        jdbc.update("DELETE FROM pipeline_aud WHERE name = ?", PIPELINE);

        final String entry = "$.pipelines[?(@.name == '" + PIPELINE + "')]";
        previewImport(bundle)
                .andExpect(status().isOk())
                .andExpect(jsonPath(entry + ".import_action").value(hasItem("CREATE")))
                .andExpect(jsonPath(entry + ".armable").value(hasItem(false)))
                .andExpect(jsonPath(entry + ".arm_problems[*]").value(hasItem(containsString("app-routes"))));

        allowInsights();
        previewImport(bundle)
                .andExpect(status().isOk())
                .andExpect(jsonPath(entry + ".armable").value(hasItem(true)));
    }

    private ResultActions previewImport(String bundle) throws Exception {
        return mockMvc.perform(multipart("/v1/catalog/import/preview")
                .file(new MockMultipartFile("file", "bundle.json", MediaType.APPLICATION_JSON_VALUE,
                        bundle.getBytes(StandardCharsets.UTF_8)))
                .param("resolution_policy", "SKIP_IF_EXISTS"));
    }
}
