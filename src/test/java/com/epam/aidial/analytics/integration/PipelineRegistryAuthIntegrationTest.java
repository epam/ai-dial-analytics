package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.JwtTestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization of the pipeline <b>registry</b> surface in {@code oidc} mode — the split this capability
 * turns on: <b>reads</b> need only a mapped application role, while registering, patching, deleting and
 * the two operator actions stay {@code FULL_ADMIN}-only.
 *
 * <p>The proposition under test is that a read-only admin can answer "what populates this table, and is
 * it running" without being able to change it or to run it. So each read is asserted three ways — 401
 * unauthenticated, 403 for an authenticated caller mapping to no application role (proving the widening
 * did not land at bare authentication), 200 for a plain {@code READ_ONLY_ADMIN} — and each mutation and
 * operator action is asserted to stay 403 for that same role.
 *
 * <p>The other half is that a widened read discloses no {@code sensitive} <em>value</em>. That holds by
 * response shape rather than by filtering, so it is tested as role-invariance of the body
 * ({@link #theReadIsRoleInvariant()}) and again over the case that used to break it — a declaration whose
 * {@code filter} names a {@code sensitive} column ({@link #sensitiveFilterNeitherFailsNorNarrowsTheRead()}).
 *
 * <p>Requires Docker: registering a pipeline validates its declaration against a real catalog.
 */
class PipelineRegistryAuthIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final String PIPELINES = "/v1/pipelines";
    private static final String ROLLUP = "authz_reg_rollup";
    private static final String PIPELINE = "authz-reg-rollup";
    private static final String SENSITIVE_ROLLUP = "authz_reg_rated";
    private static final String SENSITIVE_PIPELINE = "authz-reg-rated";

    private static final String TARGET_COLUMNS = """
            [{"source_name":"deployment","name":"deployment","type":"string"},
             {"source_name":"day","name":"day","type":"timestamp"},
             {"source_name":"call_count","name":"call_count","type":"long"}]""";

    private static final String DECLARATION = """
            "target":"authz_reg_rollup",
            "inputs":["dial_usage_log"],
            "group_by":[{"column":"deployment"},
                        {"trunc":{"column":"request_time","unit":"day"},"as":"day"}],
            "measures":[{"name":"call_count","fn":"count"}],
            "trigger":{"kind":"schedule","cron":"0 0 * * * *"}""";

    /** The same rollup shape over `rate_analytics`, with a filter on its `sensitive` `comment` column. */
    private static final String SENSITIVE_TARGET_COLUMNS = """
            [{"source_name":"deployment","name":"deployment","type":"string"},
             {"source_name":"day","name":"day","type":"timestamp"},
             {"source_name":"rated_count","name":"rated_count","type":"long"}]""";

    private static final String SENSITIVE_DECLARATION = """
            "target":"authz_reg_rated",
            "inputs":["rate_analytics"],
            "filter":"comment != ''",
            "group_by":[{"column":"deployment"},
                        {"trunc":{"column":"request_time","unit":"day"},"as":"day"}],
            "measures":[{"name":"rated_count","fn":"count"}],
            "trigger":{"kind":"schedule","cron":"0 0 * * * *"}""";

    @BeforeEach
    void setUp() throws Exception {
        upsertRollup(ROLLUP, TARGET_COLUMNS);
        mockMvc.perform(asFullAdmin(post(PIPELINES))
                        .content("{\"name\":\"" + PIPELINE + "\",\"kind\":\"aggregate\"," + DECLARATION + "}"))
                .andExpect(status().isCreated());
    }

    @AfterEach
    void tearDown() throws Exception {
        mockMvc.perform(asFullAdmin(delete(PIPELINES + "/" + SENSITIVE_PIPELINE)));
        mockMvc.perform(asFullAdmin(delete("/v1/tables/" + SENSITIVE_ROLLUP)));
        mockMvc.perform(asFullAdmin(delete(PIPELINES + "/" + PIPELINE)));
        mockMvc.perform(asFullAdmin(delete("/v1/tables/" + ROLLUP)));
    }

    @Test
    void readOnlyAdminMayListTheRegistry() throws Exception {
        mockMvc.perform(get(PIPELINES)).andExpect(status().isUnauthorized());
        mockMvc.perform(asRoleless(get(PIPELINES))).andExpect(status().isForbidden());
        mockMvc.perform(asReadOnlyAdmin(get(PIPELINES)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[?(@.name=='" + PIPELINE + "')]").exists());
    }

    @Test
    void readOnlyAdminMayReadOnePipeline() throws Exception {
        final String path = PIPELINES + "/" + PIPELINE;

        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(asRoleless(get(path))).andExpect(status().isForbidden());
        mockMvc.perform(asReadOnlyAdmin(get(path)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(PIPELINE))
                // The declaration itself, which is the point of the widening.
                .andExpect(jsonPath("$.target").value(ROLLUP))
                .andExpect(jsonPath("$.measures[0].name").value("call_count"));
    }

    @Test
    void readOnlyAdminMayReadTheRevisionSnapshot() throws Exception {
        // Reaching the handler is what is under test, so an unknown revision's 404 is the success shape —
        // the same way the audit suite asserts the table snapshot route.
        final String path = PIPELINES + "/" + PIPELINE + "/revision/999999";

        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(asRoleless(get(path))).andExpect(status().isForbidden());
        mockMvc.perform(asReadOnlyAdmin(get(path))).andExpect(status().isNotFound());
    }

    @Test
    void readOnlyAdminMayNotMutateTheRegistry() throws Exception {
        mockMvc.perform(asReadOnlyAdmin(post(PIPELINES))
                        .content("{\"name\":\"authz-reg-nope\",\"kind\":\"aggregate\"," + DECLARATION + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(asReadOnlyAdmin(patch(PIPELINES + "/" + PIPELINE)).content("{\"enabled\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(asReadOnlyAdmin(delete(PIPELINES + "/" + PIPELINE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    /**
     * The sharpest line in this change: the registry read says what a pipeline <em>would</em> compute,
     * while preview executes the declaration over real rows and refresh writes them. Widening the read
     * must not carry either along.
     */
    @Test
    void readOnlyAdminMayNotPreviewOrRefresh() throws Exception {
        mockMvc.perform(asReadOnlyAdmin(post(PIPELINES + "/" + PIPELINE + "/preview")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(asReadOnlyAdmin(post(PIPELINES + "/" + PIPELINE + "/refresh")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    /**
     * No field of a pipeline read is redacted by role, so the two bodies are compared whole rather than
     * field by field — the assertion that fails if anyone later adds a per-caller branch to the mapper.
     * Holds because every field is declaration, a count or a timestamp bar the identity-valued state
     * fields, which copy the input's scan identity column, and a cursor column can never be
     * {@code sensitive} (refused by {@code ScanMetadataRules} at declaration and on patch).
     *
     * <p>This fixture's pipeline has never run, so its state members are absent from both bodies; what is
     * pinned here is the mapper's role-invariance, not the state fields' own values.
     */
    @Test
    void theReadIsRoleInvariant() throws Exception {
        final String path = PIPELINES + "/" + PIPELINE;
        final String asFull = mockMvc.perform(asFullAdmin(get(path)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        final String asReadOnly = mockMvc.perform(asReadOnlyAdmin(get(path)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(asReadOnly).isEqualTo(asFull);
    }

    /**
     * The case that used to break both halves of this capability, so it is the sharpest regression guard
     * here. Freshness diagnostics once resolved an {@code aggregate} declaration by re-running the SQL
     * front end over its {@code filter}, which analyses it behind the sensitive-column gate: a caller not
     * entitled to a column the filter names got a throw from a {@code @Transactional} resolver, which
     * marked the read's own transaction rollback-only and turned {@code PipelineFreshnessDiagnostics}'s
     * documented degradation into an {@code UnexpectedRollbackException} — HTTP 500. Both kinds now
     * resolve from the declaration's column tokens instead, which is gate-free and cannot throw.
     *
     * <p>So the assertion is that this declaration is not special: the read succeeds with the whole
     * declaration — including the filter text naming the sensitive column — and the two roles' bodies are
     * still identical, diagnostics included.
     */
    @Test
    void sensitiveFilterNeitherFailsNorNarrowsTheRead() throws Exception {
        upsertRollup(SENSITIVE_ROLLUP, SENSITIVE_TARGET_COLUMNS);
        mockMvc.perform(asFullAdmin(post(PIPELINES))
                        .content("{\"name\":\"" + SENSITIVE_PIPELINE + "\",\"kind\":\"aggregate\","
                                + SENSITIVE_DECLARATION + "}"))
                .andExpect(status().isCreated());

        final String path = PIPELINES + "/" + SENSITIVE_PIPELINE;
        // Neither 403 nor 500: the declaration's name for a sensitive column is not its value, and
        // resolving the diagnostics no longer routes through the entitlement gate.
        final String asReadOnly = mockMvc.perform(asReadOnlyAdmin(get(path)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(SENSITIVE_PIPELINE))
                .andExpect(jsonPath("$.filter").value("comment != ''"))
                .andReturn().getResponse().getContentAsString();
        final String asFull = mockMvc.perform(asFullAdmin(get(path)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(asReadOnly).isEqualTo(asFull);
    }

    /**
     * The rollup target, created through the API as a {@code FULL_ADMIN}. Inlined rather than reusing
     * {@code IntegrationTables.upsertSource}, which sends no {@code Authorization} header and so would
     * 401 against this class's {@code oidc} configuration.
     */
    private void upsertRollup(String name, String columns) throws Exception {
        mockMvc.perform(asFullAdmin(post("/v1/tables"))
                        .content("{\"name\":\"" + name + "\",\"type\":\"source\",\"write\":\"upsert_by_key\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(asFullAdmin(post("/v1/tables/" + name + "/schema"))
                        .content("{\"columns\":" + columns
                                + ",\"ordering_key\":[\"deployment\",\"day\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    private static MockHttpServletRequestBuilder asFullAdmin(
            MockHttpServletRequestBuilder request) {
        return authored(request, fullAdmin());
    }

    private static MockHttpServletRequestBuilder asReadOnlyAdmin(
            MockHttpServletRequestBuilder request) {
        return authored(request, readOnlyAdmin());
    }

    private static MockHttpServletRequestBuilder asRoleless(
            MockHttpServletRequestBuilder request) {
        return authored(request, roleless());
    }

    private static MockHttpServletRequestBuilder authored(
            MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON);
    }

    private static String fullAdmin() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "admin-user", "roles", List.of("admin"), "email", "admin@test"));
    }

    private static String readOnlyAdmin() {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE,
                Map.of("sub", "alice", "roles", List.of("viewer"), "email", "alice@test"));
    }

    /** A valid token whose roles map to no application role — authenticated, entitled to nothing. */
    private static String roleless() {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE,
                Map.of("sub", "nobody", "roles", List.of("some-unmapped-role"), "email", "nobody@test"));
    }
}
