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

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Autowired
    private JdbcTemplate jdbc;

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

    @Test
    void allowedRoutePipelineIsArmedAndTheFeedCarriesItsCall() throws Exception {
        allowInsights();
        mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(
                        route("insights").responseJsonata("{\"tox_score\": scores.toxicity}"), true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transform.preset").value("dial_app_route"))
                .andExpect(jsonPath("$.transform.route").value("insights"))
                .andExpect(jsonPath("$.transform.response_jsonata").value("{\"tox_score\": scores.toxicity}"));

        final String entry = "$.pipelines[?(@.name=='" + PIPELINE + "')]";
        mockMvc.perform(get("/v1/pipelines?kind=enrich&view=compiled"))
                .andExpect(status().isOk())
                .andExpect(header().string(RunnerContract.HEADER, "5"))
                .andExpect(jsonPath(entry + ".transform.route").value(hasItem("insights")))
                .andExpect(jsonPath(entry + ".transform.model").value(hasItem("insights-app")))
                .andExpect(jsonPath(entry + ".transform.response_jsonata")
                        .value(hasItem("{\"tox_score\": scores.toxicity}")));
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
            assertThat(transform).doesNotContainKeys("route", "response_jsonata");
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
    void routeOrResponseMappingOnChatTransformIsRefused() throws Exception {
        final Transforms.Builder withRoute = Transforms.llm().route("insights")
                .output("tox_score", "How toxic the text is, 0-100.");
        mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(withRoute, true)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("transform.route")));

        final Transforms.Builder withMapping = Transforms.llm().responseJsonata("{\"tox_score\": score}")
                .output("tox_score", "How toxic the text is, 0-100.");
        mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(withMapping, true)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("transform.response_jsonata")));
    }

    @Test
    void anUnparseableResponseMappingIsRefused() throws Exception {
        allowInsights();
        mockMvc.perform(post("/v1/pipelines").contentType(JSON)
                        .content(body(route("insights").responseJsonata("{title: "), true)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("transform.response_jsonata")));
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
     * An arm and a removal of the same pair race. Whichever wins, the invariant holds afterwards: the pipeline is
     * enabled only if the setting still lists its pair.
     */
    @Test
    void concurrentArmAndRemovalNeverBothSucceed() throws Exception {
        allowInsights();
        mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body(route("insights"), false)))
                .andExpect(status().isCreated());

        final CountDownLatch start = new CountDownLatch(1);
        final Callable<Integer> arm = () -> {
            start.await();
            return mockMvc.perform(patch("/v1/pipelines/" + PIPELINE).contentType(JSON).content("{\"enabled\":true}"))
                    .andReturn().getResponse().getStatus();
        };
        final Callable<Integer> drop = () -> {
            start.await();
            return mockMvc.perform(put(SETTING).contentType(JSON).content("{\"routes\":[]}"))
                    .andReturn().getResponse().getStatus();
        };
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            final Future<Integer> armed = pool.submit(arm);
            final Future<Integer> dropped = pool.submit(drop);
            start.countDown();
            assertThat(List.of(armed.get(), dropped.get())).containsAnyOf(200);
        } finally {
            pool.shutdownNow();
        }

        final boolean enabled = JsonPath.read(mockMvc.perform(get("/v1/pipelines/" + PIPELINE))
                .andReturn().getResponse().getContentAsString(), "$.enabled");
        final List<Object> routes = JsonPath.read(mockMvc.perform(get(SETTING))
                .andReturn().getResponse().getContentAsString(), "$.routes");
        assertThat(enabled).isEqualTo(!routes.isEmpty());
    }
}
