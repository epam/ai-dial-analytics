package com.epam.aidial.analytics.web.handler;

import com.clickhouse.client.api.ServerException;
import com.epam.aidial.analytics.exception.ClickHouseQueryException;
import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import com.epam.aidial.analytics.exception.PipelineConflictException;
import com.epam.aidial.analytics.exception.PipelinePreviewBudgetExceededException;
import com.epam.aidial.analytics.exception.PipelineReadBudgetExceededException;
import com.epam.aidial.analytics.exception.QueryBudgetExceededException;
import com.epam.aidial.analytics.exception.RunnerContractException;
import com.epam.aidial.analytics.observability.LogCapture;
import com.epam.aidial.analytics.observability.SqlLog;
import com.epam.aidial.analytics.service.enrichment.support.RunnerContract;
import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * What {@link DefaultExceptionHandler} logs for a refusal: no stack at WARN, at most one line per key per
 * window with the suppressed count on the next, the caller on the contract refusal, INFO for a pipeline
 * conflict and ERROR with the stack for the catch-all — and, throughout, the exact {@link ErrorView} it
 * answered before this logging changed.
 */
class DefaultExceptionHandlerLoggingTest {

    private static final String SCAN_PATTERN = "/v1/pipelines/{name}/scan";

    private final AtomicLong clock = new AtomicLong(1_000L);
    private final SecurityClaimsExtractor extractor = mock(SecurityClaimsExtractor.class);
    private DefaultExceptionHandler handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        final ObjectProvider<SecurityClaimsExtractor> provider = mock(ObjectProvider.class);
        given(provider.getIfAvailable()).willReturn(extractor);
        handler = new DefaultExceptionHandler(provider, new RefusalLogLimiter(clock::get, Duration.ofSeconds(60)));
    }

    private static MockHttpServletRequest request(String path, String pattern) {
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        if (pattern != null) {
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        }
        return request;
    }

    /**
     * Every pipeline shares the materialize pattern, so a read-budget refusal is also keyed by the pipeline: one
     * pipeline refused on every call must not hide another's first refusal.
     */
    @Test
    void readBudgetRefusalsAreRateLimitedPerPipeline() {
        final String pattern = "/v1/pipelines/{name}/materialize";
        try (LogCapture logs = LogCapture.of(DefaultExceptionHandler.class)) {
            for (String pipeline : List.of("heavy", "heavy", "heavy", "other")) {
                MDC.put("pipeline_name", pipeline);
                try {
                    handler.handlePipelineReadBudget(request("/v1/pipelines/" + pipeline + "/materialize", pattern),
                            new PipelineReadBudgetExceededException("this materialize write exceeded the memory ceiling", null));
                } finally {
                    MDC.remove("pipeline_name");
                }
            }

            assertThat(logs.messagesAt(Level.WARN)).hasSize(2).allSatisfy(line -> assertThat(line)
                    .contains("refused by the pipeline read budget"));
            assertThat(logs.messagesAt(Level.WARN).get(1)).contains("/v1/pipelines/other/materialize");
        }
    }

    @Test
    void contractRefusalsInOneMinuteGiveOneLineThenTheSuppressedCount() {
        given(extractor.isAuthenticated()).willReturn(true);
        given(extractor.getAuthor()).willReturn("enrichment-runner");

        try (LogCapture logs = LogCapture.of(DefaultExceptionHandler.class); LogCapture stacks = LogCapture.of(SqlLog.NAME)) {
            for (int i = 0; i < 5; i++) {
                handler.handleRunnerContract(request("/v1/pipelines/p" + i + "/scan", SCAN_PATTERN),
                        RunnerContractException.mismatch("999"));
            }
            assertThat(logs.messagesAt(Level.WARN)).hasSize(1);
            final String first = logs.messagesAt(Level.WARN).getFirst();
            assertThat(first).contains("runner_contract_mismatch", "enrichment-runner", "999",
                    RunnerContract.VERSION_VALUE).doesNotContain("suppressed");

            clock.addAndGet(Duration.ofSeconds(61).toNanos());
            handler.handleRunnerContract(request("/v1/pipelines/p0/scan", SCAN_PATTERN),
                    RunnerContractException.mismatch("999"));

            assertThat(logs.messagesAt(Level.WARN)).hasSize(2);
            assertThat(logs.messagesAt(Level.WARN).get(1)).contains("4 more suppressed");
            // The stack is still there for whoever turns the SQL logger to DEBUG — once per refusal, not rate-limited.
            assertThat(stacks.messagesAt(Level.DEBUG)).hasSize(6);
        }
    }

    /**
     * The driver message can quote the statement, literals included, so the class logger — which the runtime
     * level file can turn to DEBUG — never carries it: the WARN line names the server's code, and the stack goes
     * to the SQL logger.
     */
    @Test
    void refusalStacksGoToTheSqlLoggerOnly() {
        try (LogCapture logs = LogCapture.of(DefaultExceptionHandler.class); LogCapture stacks = LogCapture.of(SqlLog.NAME)) {
            handler.handleRunnerContract(request("/v1/pipelines/p/scan", SCAN_PATTERN),
                    RunnerContractException.required());
            handler.handleClickHouseQuery(request("/v1/queries/execute", "/v1/queries/execute"),
                    new ClickHouseQueryException("rejected", new ServerException(62, "Syntax error near 'secret_literal'", 400, "q")));

            assertThat(logs.eventsAt(Level.WARN)).hasSize(2).allSatisfy(event -> assertThat(event.getThrown()).isNull());
            assertThat(logs.eventsAt(Level.DEBUG)).isEmpty();
            assertThat(logs.messagesAt(Level.WARN).get(1)).contains("ClickHouse code 62").doesNotContain("secret_literal");
            assertThat(stacks.eventsAt(Level.DEBUG)).hasSize(2)
                    .allSatisfy(event -> assertThat(event.getThrown()).isNotNull());
        }
    }

    /** With no server answer to name, the line gives the innermost cause's type — never its message. */
    @Test
    void unavailableRefusalNamesTheInnermostCauseType() {
        try (LogCapture logs = LogCapture.of(DefaultExceptionHandler.class)) {
            handler.handleClickHouseUnavailable(request("/v1/queries/execute", "/v1/queries/execute"),
                    new ClickHouseUnavailableException("x", new RuntimeException("wrapped", new ConnectException("refused host"))));

            assertThat(logs.messagesAt(Level.WARN)).singleElement()
                    .satisfies(line -> assertThat(line).contains("(ConnectException)").doesNotContain("refused host"));
        }
    }

    @Test
    void theKeyIsTheHandlerPatternNotTheRawPath() {
        try (LogCapture logs = LogCapture.of(DefaultExceptionHandler.class)) {
            handler.handleRunnerContract(request("/v1/pipelines/a/scan", SCAN_PATTERN), RunnerContractException.required());
            handler.handleRunnerContract(request("/v1/pipelines/b/scan", SCAN_PATTERN), RunnerContractException.required());
            handler.handleRunnerContract(request("/v1/pipelines/a/rows", "/v1/pipelines/{name}/rows"),
                    RunnerContractException.required());
            // Same path pattern, other code: its own key.
            handler.handleRunnerContract(request("/v1/pipelines/a/scan", SCAN_PATTERN), RunnerContractException.mismatch("7"));

            assertThat(logs.messagesAt(Level.WARN)).hasSize(3);
        }
    }

    @Test
    void contractRefusalWithoutPrincipalNamesTheMdcCallerOrDash() {
        try (LogCapture logs = LogCapture.of(DefaultExceptionHandler.class)) {
            handler.handleRunnerContract(request("/v1/pipelines/p/scan", SCAN_PATTERN), RunnerContractException.required());
            MDC.put("caller", "from-access-log");
            try {
                handler.handleRunnerContract(request("/v1/pipelines/p/rows", "/v1/pipelines/{name}/rows"),
                        RunnerContractException.required());
            } finally {
                MDC.remove("caller");
            }

            assertThat(logs.messagesAt(Level.WARN).get(0)).contains("for caller '-'");
            assertThat(logs.messagesAt(Level.WARN).get(1)).contains("for caller 'from-access-log'");
        }
    }

    @Test
    void theCatchAllLogsAtErrorWithTheStack() {
        try (LogCapture logs = LogCapture.of(DefaultExceptionHandler.class)) {
            final ErrorView view = handler.handleGeneralError(request("/v1/tables", null), new IllegalStateException("boom"));

            assertThat(logs.eventsAt(Level.ERROR)).singleElement()
                    .satisfies(event -> assertThat(event.getThrown()).isInstanceOf(IllegalStateException.class));
            assertThat(logs.eventsAt(Level.WARN)).isEmpty();
            assertView(view, 500, "internal_error", "Internal server error");
        }
    }

    @Test
    void pipelineConflictIsLoggedAtInfoWithoutStack() {
        try (LogCapture logs = LogCapture.of(DefaultExceptionHandler.class)) {
            final ErrorView view = handler.handlePipelineConflict(request("/v1/pipelines/p/rows", null),
                    new PipelineConflictException("pipeline 'p' is disabled"));

            assertThat(logs.eventsAt(Level.INFO)).singleElement()
                    .satisfies(event -> assertThat(event.getThrown()).isNull());
            assertView(view, 409, "pipeline_conflict", "pipeline 'p' is disabled");
        }
    }

    /** The logging moved; the responses did not. Every rate-limited handler still answers what it did. */
    @Test
    void errorViewBodiesAreUnchanged() {
        final List<ErrorView> views = new ArrayList<>();
        final Function<String, MockHttpServletRequest> req = path -> request(path, path);
        final RuntimeException cause = new RuntimeException("driver detail");

        views.add(handler.handleClickHouseQuery(req.apply("/a"), new ClickHouseQueryException("x", cause)));
        views.add(handler.handleClickHouseUnavailable(req.apply("/b"), new ClickHouseUnavailableException("x", cause)));
        views.add(handler.handlePostgresUnavailable(req.apply("/c"), new DataAccessResourceFailureException("x")));
        views.add(handler.handlePipelinePreviewBudget(req.apply("/d"),
                new PipelinePreviewBudgetExceededException("preview budget", cause)));
        views.add(handler.handleQueryBudget(req.apply("/e"), new QueryBudgetExceededException("query budget", cause)));
        views.add(handler.handlePipelineReadBudget(req.apply("/f"),
                new PipelineReadBudgetExceededException("read budget", cause)));
        views.add(handler.handleRunnerContract(req.apply("/g"), RunnerContractException.required()));

        assertView(views.get(0), 400, "bad_request",
                "The query could not be executed against ClickHouse; check field/function/type compatibility");
        assertView(views.get(1), 503, "clickhouse_unavailable", "ClickHouse is currently unavailable");
        assertView(views.get(2), 503, "postgres_unavailable", "The catalog database is currently unavailable");
        assertView(views.get(3), 422, "pipeline_preview_budget_exceeded", "preview budget");
        assertView(views.get(4), 422, "query_budget_exceeded", "query budget");
        assertView(views.get(5), 422, "pipeline_read_budget_exceeded", "read budget");
        assertView(views.get(6), 409, "runner_contract_required", RunnerContractException.required().getMessage());
        assertThat(views).extracting(ErrorView::getMethod).containsOnly("POST");
        assertThat(views.get(6).getPath()).isEqualTo("/g");
    }

    private static void assertView(ErrorView view, int status, String error, String message) {
        assertThat(view.getStatus()).isEqualTo(status);
        assertThat(view.getError()).isEqualTo(error);
        assertThat(view.getMessage()).isEqualTo(message);
    }
}
