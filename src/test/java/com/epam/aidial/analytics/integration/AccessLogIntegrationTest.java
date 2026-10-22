package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.configuration.logging.AccessLogFilter;
import com.epam.aidial.analytics.observability.LogCapture;
import com.epam.aidial.analytics.service.enrichment.support.RunnerContract;
import com.epam.aidial.analytics.web.security.JwtTestTokens;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The pipeline access line and the request log context, through the <b>full filter chain in {@code oidc}
 * mode</b> — the {@code 401} case is written inside the security chain and reaches no handler, so a slice
 * without filters could not show it is logged.
 *
 * <p>Requires Docker.
 */
class AccessLogIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final String MATERIALIZE = "/v1/pipelines/p1/materialize";
    private static final String BODY_MARKER = "body-marker-never-logged";
    private static final String QUERY_MARKER = "query-marker-never-logged";

    @Test
    void materializeCallLogsOneAccessLineWithItsContext() throws Exception {
        final int status;
        final List<LogEvent> lines;
        try (LogCapture capture = LogCapture.of(AccessLogFilter.class)) {
            status = mockMvc.perform(post(MATERIALIZE)
                            .queryParam("marker", QUERY_MARKER)
                            .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                            .header(RunnerContract.HEADER, RunnerContract.VERSION_VALUE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cursor\":\"" + BODY_MARKER + "\"}"))
                    .andReturn().getResponse().getStatus();
            lines = capture.eventsAt(Level.INFO);
        }

        assertThat(lines).hasSize(1);
        final LogEvent line = lines.getFirst();
        assertThat(line.getMessage().getFormattedMessage())
                .startsWith("http POST " + MATERIALIZE + " ")
                .contains("pipeline=p1", "status=" + status, "ms=", "caller=admin-user",
                        "contract=" + RunnerContract.VERSION_VALUE)
                .doesNotContain(QUERY_MARKER, BODY_MARKER, "?");
        assertThat(line.getContextData().<String>getValue(AccessLogFilter.PIPELINE_NAME)).isEqualTo("p1");
        assertThat(line.getContextData().<String>getValue(AccessLogFilter.REQUEST_ID)).isNotBlank();
        assertThat(line.getContextData().<String>getValue(AccessLogFilter.CALLER)).isEqualTo("admin-user");
    }

    @Test
    void refusalBySecurityChainIsLogged() throws Exception {
        final List<String> lines;
        try (LogCapture capture = LogCapture.of(AccessLogFilter.class)) {
            mockMvc.perform(post(MATERIALIZE)
                            .header(RunnerContract.HEADER, RunnerContract.VERSION_VALUE)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
            lines = capture.messagesAt(Level.INFO);
        }

        assertThat(lines).singleElement().asString()
                .contains("pipeline=p1", "status=401", "caller=-", "contract=" + RunnerContract.VERSION_VALUE);
    }

    @Test
    void logContextIsClearedAfterTheRequest() throws Exception {
        // MockMvc runs the request on this thread, so whatever the filter left behind would be visible here.
        mockMvc.perform(post(MATERIALIZE)
                .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                .header(RunnerContract.HEADER, RunnerContract.VERSION_VALUE)
                .contentType(MediaType.APPLICATION_JSON).content("{}"));

        assertThat(MDC.get(AccessLogFilter.PIPELINE_NAME)).isNull();
        assertThat(MDC.get(AccessLogFilter.REQUEST_ID)).isNull();
        assertThat(MDC.get(AccessLogFilter.CALLER)).isNull();
    }

    @Test
    void actuatorIsNotLogged() throws Exception {
        final List<LogEvent> lines;
        try (LogCapture capture = LogCapture.of(AccessLogFilter.class)) {
            mockMvc.perform(get("/actuator/health"));
            lines = capture.eventsAt(Level.DEBUG);
            assertThat(capture.messagesAt(Level.INFO)).isEmpty();
        }
        assertThat(lines).isEmpty();
    }

    private static String fullAdmin() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "admin-user", "roles", List.of("admin"), "email", "admin@test"));
    }
}
