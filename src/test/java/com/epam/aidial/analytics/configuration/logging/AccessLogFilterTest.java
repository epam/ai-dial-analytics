package com.epam.aidial.analytics.configuration.logging;

import com.epam.aidial.analytics.clickhouse.StatementTag;
import com.epam.aidial.analytics.observability.LogCapture;
import jakarta.servlet.FilterChain;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessLogFilterTest {

    private final AccessLogFilter filter = new AccessLogFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void pipelineNameIsTheSegmentAfterThePrefix() {
        assertThat(AccessLogFilter.pipelineName("/v1/pipelines/p1/materialize")).isEqualTo("p1");
        assertThat(AccessLogFilter.pipelineName("/v1/pipelines/p1")).isEqualTo("p1");
    }

    @Test
    void collectionAndOtherPathsHaveNoPipelineName() {
        assertThat(AccessLogFilter.pipelineName("/v1/pipelines")).isNull();
        assertThat(AccessLogFilter.pipelineName("/v1/pipelines/")).isNull();
        assertThat(AccessLogFilter.pipelineName("/v1/queries/execute")).isNull();
        assertThat(AccessLogFilter.pipelineName("/v1/pipelinesx/p1")).isNull();
    }

    @Test
    void callerChosenNameIsTruncated() {
        final String longName = "n".repeat(AccessLogFilter.MAX_PIPELINE_NAME_LENGTH + 50);
        assertThat(AccessLogFilter.pipelineName("/v1/pipelines/" + longName + "/scan"))
                .hasSize(AccessLogFilter.MAX_PIPELINE_NAME_LENGTH);
    }

    @Test
    void callerChosenPathIsTruncatedOnTheAccessLine() throws Exception {
        final String longName = "n".repeat(AccessLogFilter.MAX_PATH_LENGTH * 2);
        final List<String> lines;
        try (LogCapture capture = LogCapture.of(AccessLogFilter.class)) {
            filter.doFilter(request("/v1/pipelines/" + longName), new MockHttpServletResponse(), (req, res) -> { });
            lines = capture.messagesAt(Level.INFO);
        }

        assertThat(lines).singleElement().asString()
                .contains("n".repeat(AccessLogFilter.MAX_PATH_LENGTH - "/v1/pipelines/".length()))
                .doesNotContain("n".repeat(AccessLogFilter.MAX_PATH_LENGTH));
    }

    @Test
    void pathsOutsideThePipelinesPrefixAreLoggedAtDebugOnly() throws Exception {
        try (LogCapture capture = LogCapture.of(AccessLogFilter.class)) {
            filter.doFilter(request("/v1/queries/execute"), new MockHttpServletResponse(), (req, res) -> { });

            assertThat(capture.messagesAt(Level.INFO)).isEmpty();
            assertThat(capture.messagesAt(Level.DEBUG)).singleElement().asString()
                    .startsWith("http POST /v1/queries/execute ").contains("pipeline=-");
        }
    }

    @Test
    void escapedExceptionIsReportedAsTheContainersFiveHundred() {
        final FilterChain failing = (req, res) -> {
            throw new IllegalStateException("boom");
        };
        try (LogCapture capture = LogCapture.of(AccessLogFilter.class)) {
            assertThatThrownBy(() -> filter.doFilter(request("/v1/pipelines/p1/scan"), new MockHttpServletResponse(), failing))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(capture.messagesAt(Level.INFO)).singleElement().asString().contains("status=500");
        }
    }

    @Test
    void contextIsSetForTheRequestAndTheThreadsPreviousContextRestored() throws Exception {
        MDC.put(StatementTag.MDC_KEY, "outer-query");
        final String[] seen = new String[3];
        filter.doFilter(request("/v1/pipelines/p1/materialize"), new MockHttpServletResponse(), (req, res) -> {
            seen[0] = MDC.get(AccessLogFilter.PIPELINE_NAME);
            seen[1] = MDC.get(AccessLogFilter.REQUEST_ID);
            seen[2] = MDC.get(AccessLogFilter.CALLER);
            MDC.put(StatementTag.MDC_KEY, "inner-query");
        });

        assertThat(seen[0]).isEqualTo("p1");
        assertThat(seen[1]).isNotBlank();
        assertThat(seen[2]).isEqualTo(AccessLogFilter.NONE);
        assertThat(MDC.get(AccessLogFilter.PIPELINE_NAME)).isNull();
        assertThat(MDC.get(AccessLogFilter.REQUEST_ID)).isNull();
        assertThat(MDC.get(AccessLogFilter.CALLER)).isNull();
        assertThat(MDC.get(StatementTag.MDC_KEY)).isEqualTo("outer-query");
    }

    private static MockHttpServletRequest request(String uri) {
        return new MockHttpServletRequest("POST", uri);
    }
}
