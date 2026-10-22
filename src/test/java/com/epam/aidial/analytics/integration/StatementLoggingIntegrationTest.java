package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.clickhouse.ClickHouseInsertSelectExecutor;
import com.epam.aidial.analytics.observability.EventCapture;
import com.epam.aidial.analytics.observability.SqlLog;
import com.epam.aidial.analytics.service.enrichment.MaterializeService;
import com.epam.aidial.analytics.service.query.repository.StructuredQueryExecutor;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.epam.aidial.analytics.integration.RunnerCalls.runnerPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Statement text inlines its literals — filter values, cursor tuples, group keys — so it is logged only on the
 * {@code com.epam.aidial.analytics.sql} logger, which the XML pins at INFO. Tracing the package or an executor
 * class, which is exactly what an operator does to follow a call, must therefore still print no statement.
 *
 * <p>Runs a Class-1 materialize (probe plus {@code INSERT … SELECT}) and a filtered structured query with the
 * package and each executor class at TRACE, and asserts that nothing captured carries the statement or the
 * literals it inlined. Uses the shared enrichment property set so it reuses the other enrichment classes' context.
 * Requires Docker.
 */
@TestPropertySource(properties = {AbstractClickHouseIntegrationTest.SCAN_SAFETY_LAG_OFF,
        AbstractClickHouseIntegrationTest.GROUP_FETCH_MAX_BYTES_4KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_WINDOW_LIMIT_3,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_MEMORY_1KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_SPILL_OFF})
class StatementLoggingIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String SOURCE = "dial_usage_log";
    private static final String ENRICHMENT = "stmt_log_model";
    private static final String PIPELINE = "stmt-log-pipeline";
    private static final String BODY_LITERAL = "sentinel-model-7f3a";
    private static final String FILTER_LITERAL = "sentinel-project-9c1e";

    private static final List<String> TRACED = List.of(
            "com.epam.aidial.analytics",
            ClickHouseInsertSelectExecutor.class.getName(),
            StructuredQueryExecutor.class.getName(),
            MaterializeService.class.getName());

    private final LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());
    private final Map<String, LogLevel> before = new LinkedHashMap<>();
    private EventCapture capture;

    @BeforeEach
    void setUp() throws Exception {
        truncate();
        IntegrationTables.enrichment(mockMvc, ENRICHMENT, SOURCE, "event_id",
                "[{\"source_name\":\"body_model\",\"name\":\"body_model\",\"type\":\"string\"}]");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (capture != null) {
            capture.detach();
        }
        before.forEach(loggingSystem::setLogLevel);
        mockMvc.perform(delete("/v1/pipelines/" + PIPELINE));
        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT));
    }

    @Test
    void tracingThePackageAndTheExecutorsLogsNoStatement() throws Exception {
        seedRow("33333333-3333-3333-3333-333333333333", "2020-01-01 00:00:01.000");
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + PIPELINE + "\","
                                + Transforms.sql().output("body_model",
                                        "json_extract_string(\\\"dial_usage_log_payload.request_body\\\", 'model')").member()
                                + ",\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated());
        traceAll();

        mockMvc.perform(runnerPost("/v1/pipelines/" + PIPELINE + "/materialize")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows_written").value(1));
        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"dial_usage_log\",\"mode\":\"row\","
                                + "\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"event_id\"}}],"
                                + "\"filter\":{\"op\":\"ne\",\"args\":[{\"type\":\"field\",\"name\":\"project_id\"},"
                                + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"" + FILTER_LITERAL + "\"}]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1));

        // Tracing did take effect: the per-class DEBUG lines that carry no statement are still there.
        assertThat(capture.events()).anyMatch(event -> event.getLoggerName().equals(StructuredQueryExecutor.class.getName()));
        assertThat(capture.events()).noneMatch(event -> event.getLoggerName().startsWith(SqlLog.NAME));
        for (LogEvent event : capture.events()) {
            String rendered = EventCapture.rendered(event);
            assertThat(rendered).as(event.getLoggerName())
                    .doesNotContain(FILTER_LITERAL)
                    .doesNotContain(BODY_LITERAL)
                    .doesNotContainIgnoringCase("SELECT ")
                    .doesNotContainIgnoringCase("INSERT INTO");
        }
    }

    private void traceAll() {
        for (String name : TRACED) {
            LoggerConfiguration configuration = loggingSystem.getLoggerConfiguration(name);
            before.put(name, configuration == null ? null : configuration.getConfiguredLevel());
            loggingSystem.setLogLevel(name, LogLevel.TRACE);
        }
        capture = EventCapture.attach();
    }

    private void seedRow(String eventId, String ingestedAt) {
        final String requestTime = "'" + fixtureTime("00:00:00.000") + "'";
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id, _ingested_at) VALUES (toUUID('" + eventId + "'),'p','d', " + requestTime + ",'t','"
                + eventId.substring(0, 8) + "','" + ingestedAt + "')");
        execute("INSERT INTO dial_usage_log_payload (event_id, request_time, trace_id, core_span_id, request_body)"
                + " VALUES (toUUID('" + eventId + "'), " + requestTime + ", 't','" + eventId.substring(0, 8) + "','"
                + "{\"model\":\"" + BODY_LITERAL + "\"}')");
    }
}
