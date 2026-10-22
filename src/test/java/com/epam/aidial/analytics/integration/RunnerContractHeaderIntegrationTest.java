package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.enrichment.support.RunnerContract;
import com.epam.aidial.analytics.web.security.JwtTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The contract <b>echo</b>: every response under {@code /v1/pipelines} carries
 * {@code Adas-Contract-Version}, whatever its method and whatever its status.
 *
 * <p>Run through the <b>full filter chain in {@code oidc} mode</b>, not an MVC slice, because the cases
 * that matter are the ones no handler ever sees. To a client, an absent echo means "this ADAS predates the
 * contract" — so a {@code 401} from a wrong or expired key answering without one would report the most
 * ordinary operational error there is as a version skew, which is the single distinction this header
 * exists to make. A slice with {@code addFilters = false} would pass while that was broken.
 *
 * <p>The bare {@code /v1/pipelines} case is load-bearing for a different reason: the filter is mapped with
 * the servlet path-prefix pattern {@code /v1/pipelines/*}, which covers the collection's own path — the
 * feed, and the first call a runner makes after a deploy. Spelling it {@code /**} there would be a literal
 * that matches nothing and would fail silently, so this asserts the mapping as much as the header.
 *
 * <p>Requires Docker.
 */
class RunnerContractHeaderIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final String PIPELINES = "/v1/pipelines";

    @Test
    void unauthenticatedCallStillCarriesTheEcho() throws Exception {
        mockMvc.perform(get(PIPELINES))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(RunnerContract.HEADER, RunnerContract.VERSION_VALUE));
    }

    /** The URL-level 403 the access-denied handler writes, also inside the security chain. */
    @Test
    void rolelessCallStillCarriesTheEcho() throws Exception {
        mockMvc.perform(get(PIPELINES).header(HttpHeaders.AUTHORIZATION, bearer(roleless())))
                .andExpect(status().isForbidden())
                .andExpect(header().string(RunnerContract.HEADER, RunnerContract.VERSION_VALUE));
    }

    /** The feed's own path — what {@code /v1/pipelines/*} must match, and what a runner calls first. */
    @Test
    void bareListingCarriesTheEcho() throws Exception {
        mockMvc.perform(get(PIPELINES).header(HttpHeaders.AUTHORIZATION, bearer(readOnlyAdmin())))
                .andExpect(status().isOk())
                .andExpect(header().string(RunnerContract.HEADER, RunnerContract.VERSION_VALUE));
    }

    /** The runner's own feed, with every parameter it sends. */
    @Test
    void compiledFeedCarriesTheEcho() throws Exception {
        mockMvc.perform(get(PIPELINES)
                        .param("kind", "enrich").param("enabled", "true").param("view", "compiled")
                        .header(HttpHeaders.AUTHORIZATION, bearer(readOnlyAdmin())))
                .andExpect(status().isOk())
                .andExpect(header().string(RunnerContract.HEADER, RunnerContract.VERSION_VALUE));
    }

    /** A single read, on the path a controller does handle — here answering 404 for an unknown name. */
    @Test
    void singleReadCarriesTheEcho() throws Exception {
        mockMvc.perform(get(PIPELINES + "/no-such-pipeline")
                        .header(HttpHeaders.AUTHORIZATION, bearer(readOnlyAdmin())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("unknown_pipeline"))
                .andExpect(header().string(RunnerContract.HEADER, RunnerContract.VERSION_VALUE));
    }

    /**
     * The contract refusal itself carries it, which is what lets a runner tell "refused by a current build"
     * from "answered by a build that has no contract" — the two states with opposite remedies.
     *
     * <p>The name is unknown on purpose: it also pins the ordering the requirement states. Authentication is
     * settled first (a 401 above, not a 409), then the contract, and only then the pipeline lookup — so an
     * authenticated caller with no header gets 409 rather than the 404 the name would earn.
     */
    @Test
    void contractRefusalCarriesTheEcho() throws Exception {
        mockMvc.perform(post(PIPELINES + "/no-such-pipeline/scan")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("runner_contract_required"))
                .andExpect(header().string(RunnerContract.HEADER, RunnerContract.VERSION_VALUE));
    }

    /** An unmapped path under the prefix resolves to no handler at all, and must still carry it. */
    @Test
    void unmappedPathUnderPrefixCarriesTheEcho() throws Exception {
        mockMvc.perform(get(PIPELINES + "/some-name/not-an-endpoint")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(header().string(RunnerContract.HEADER, RunnerContract.VERSION_VALUE));
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
