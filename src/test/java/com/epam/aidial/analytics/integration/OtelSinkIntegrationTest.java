package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.otel.OtelSignal;
import com.epam.aidial.analytics.service.otel.OtelSinkSchemas;
import com.epam.aidial.analytics.service.table.ColumnInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the OTLP sink capability against a real ClickHouse and PostgreSQL: provisioning
 * creates and materializes the landing tables, an OTLP/HTTP JSON export lands rows with its attributes in
 * {@code Map(String, String)} columns, those rows are queryable through the ordinary read path, and
 * deleting the sink takes its tables with it.
 *
 * <p>Requires a running Docker daemon.
 */
class OtelSinkIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String SINKS = "/v1/otel/sinks";
    private static final String SINK = "app";
    private static final String EXECUTE_SQL = "/v1/queries/execute-sql";

    @AfterEach
    void removeSink() throws Exception {
        mockMvc.perform(delete(SINKS + "/" + SINK));
    }

    private void createSink(String signalsJson) throws Exception {
        mockMvc.perform(post(SINKS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + SINK + "\",\"signals\":" + signalsJson + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.endpoint").value("/v1/otel/sink/" + SINK));
    }

    private void export(String signal, String payload, int expectedStatus) throws Exception {
        mockMvc.perform(post("/v1/otel/sink/" + SINK + "/v1/" + signal)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().is(expectedStatus));
    }

    /**
     * An OTLP nanosecond timestamp {@code millisAgo} before now. The landing tables carry a 30-day
     * retention TTL, so a hard-coded past instant would be expired on arrival and the assertions would
     * read an empty table — which is also exactly what a real exporter avoids by sending recent data.
     */
    private static String nanosAgo(long millisAgo) {
        return Long.toString((java.time.Instant.now().toEpochMilli() - millisAgo) * 1_000_000L);
    }

    private void sql(String statement, int expectedStatus) throws Exception {
        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"" + statement + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void threeSignalSinkMaterializesThreeTablesThatTheCatalogListsAsActiveSources() throws Exception {
        createSink("[\"logs\",\"traces\",\"metrics\"]");

        mockMvc.perform(get(SINKS + "/" + SINK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signals.length()").value(3))
                .andExpect(jsonPath("$.signals[0].signal").value("logs"))
                .andExpect(jsonPath("$.signals[0].table").value("otel_app_logs"))
                .andExpect(jsonPath("$.signals[0].status").value("active"));

        for (final String table : new String[] {"otel_app_logs", "otel_app_traces", "otel_app_metrics"}) {
            mockMvc.perform(get("/v1/tables/" + table))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.type").value("source"))
                    .andExpect(jsonPath("$.status").value("active"))
                    // Ordinary catalog tables, deliberately not system-owned (design D3).
                    .andExpect(jsonPath("$.system").value(false));
        }
    }

    @Test
    void singleSignalSinkCreatesOnlyThatTable() throws Exception {
        createSink("[\"metrics\"]");

        mockMvc.perform(get(SINKS + "/" + SINK))
                .andExpect(jsonPath("$.signals.length()").value(1))
                .andExpect(jsonPath("$.signals[0].signal").value("metrics"));
        mockMvc.perform(get("/v1/tables/otel_app_logs")).andExpect(status().isNotFound());
    }

    @Test
    void attributeColumnsAreMapsAndTheLandingTableIsPartitionedAndTtled() throws Exception {
        createSink("[\"logs\"]");

        mockMvc.perform(get("/v1/tables/otel_app_logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[?(@.name == 'log_attributes')].type").value("map"))
                .andExpect(jsonPath("$.columns[?(@.name == 'resource_attributes')].type").value("map"))
                .andExpect(jsonPath("$.columns[?(@.name == 'scope_attributes')].type").value("map"));

        assertThat(showCreateTable("otel_app_logs"))
                .contains("`log_attributes` Map(String, String)")
                .contains("PARTITION BY toYYYYMMDD(event_time)")
                .contains("TTL toDateTime(event_time) + toIntervalDay(30)");
    }

    @Test
    void metricsPointAttributesAreDeclaredSensitiveAndTheOtherMapsAreNot() throws Exception {
        createSink("[\"metrics\"]");

        // point_attributes carries producer-defined end-user identity (user.email, user.id,
        // user.account_id on the Claude Code feed), and the map accessors make it aggregatable rather
        // than merely projectable. resource_attributes and scope_attributes describe the producing
        // resource and scope — host, OS, service name and version — so flagging them would hide
        // dimensions for no benefit.
        mockMvc.perform(get("/v1/tables/otel_app_metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[?(@.name == 'point_attributes')].sensitive").value(true))
                .andExpect(jsonPath("$.columns[?(@.name == 'resource_attributes')].sensitive").value(false))
                .andExpect(jsonPath("$.columns[?(@.name == 'scope_attributes')].sensitive").value(false));
    }

    /**
     * The reason the landing columns are not spelled {@code timestamp}/{@code value}/{@code count}: those
     * are reserved words in the SQL dialect the read surface parses, so a caller would have to know to
     * quote them. ADAS chooses these names, so every one of them must be selectable bare.
     */
    @Test
    void everyLandingColumnIsSelectableByItsBareName() throws Exception {
        createSink("[\"logs\",\"traces\",\"metrics\"]");

        for (final OtelSignal signal : OtelSignal.values()) {
            final String columns = OtelSinkSchemas.columnsFor(signal).stream()
                    .map(ColumnInput::name)
                    .collect(Collectors.joining(", "));
            sql("SELECT " + columns + " FROM otel_app_" + signal.code(), 200);
        }
    }

    @Test
    void logsExportLandsRowsWhoseAttributesReadBackAsJsonObjects() throws Exception {
        createSink("[\"logs\"]");

        export("logs", """
                {"resourceLogs":[{
                  "resource":{"attributes":[{"key":"service.name","value":{"stringValue":"api"}}]},
                  "scopeLogs":[{"scope":{"name":"lib","version":"1.0"},"logRecords":[
                    {"timeUnixNano":"%s","severityText":"INFO","severityNumber":9,
                     "body":{"stringValue":"hello"},
                     "traceId":"5B8EFFF798038103D269B633813FC60C",
                     "attributes":[{"key":"http.method","value":{"stringValue":"GET"}},
                                   {"key":"http.status","value":{"intValue":"200"}}]},
                    {"timeUnixNano":"%s","body":{"stringValue":"second"}}]}]}]}""".formatted(nanosAgo(2000), nanosAgo(1000)), 200);

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT service_name, body, trace_id, log_attributes "
                                + "FROM otel_app_logs ORDER BY event_time\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].service_name").value("api"))
                .andExpect(jsonPath("$.rows[0].body").value("hello"))
                .andExpect(jsonPath("$.rows[0].trace_id").value("5b8efff798038103d269b633813fc60c"))
                // The map cell is a JSON object of strings, not a quoted JSON string.
                .andExpect(jsonPath("$.rows[0].log_attributes['http.method']").value("GET"))
                .andExpect(jsonPath("$.rows[0].log_attributes['http.status']").value("200"))
                .andExpect(jsonPath("$.rows[1].log_attributes").isMap());
    }

    @Test
    void metricsExportLandsOneRowPerDataPointDiscriminatedByType() throws Exception {
        createSink("[\"metrics\"]");

        export("metrics", """
                {"resourceMetrics":[{
                  "resource":{"attributes":[{"key":"service.name","value":{"stringValue":"api"}}]},
                  "scopeMetrics":[{"metrics":[
                    {"name":"requests","unit":"1","sum":{"isMonotonic":true,
                     "aggregationTemporality":"AGGREGATION_TEMPORALITY_CUMULATIVE","dataPoints":[
                       {"timeUnixNano":"%s","asInt":"5",
                        "attributes":[{"key":"route","value":{"stringValue":"/x"}}]}]}},
                    {"name":"latency","histogram":{"dataPoints":[
                       {"timeUnixNano":"%s","count":"3","sum":6.0,
                        "bucketCounts":["1","2"],"explicitBounds":[2.0]}]}}]}]}]}""".formatted(nanosAgo(2000), nanosAgo(1000)), 200);

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT metric_name, metric_type, point_value, point_count, "
                                + "point_attributes FROM otel_app_metrics ORDER BY metric_name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].metric_name").value("latency"))
                .andExpect(jsonPath("$.rows[0].metric_type").value("histogram"))
                .andExpect(jsonPath("$.rows[0].point_value").doesNotExist())
                .andExpect(jsonPath("$.rows[0].point_count").value(3))
                .andExpect(jsonPath("$.rows[1].metric_name").value("requests"))
                .andExpect(jsonPath("$.rows[1].metric_type").value("sum"))
                .andExpect(jsonPath("$.rows[1].point_attributes['route']").value("/x"));
    }

    @Test
    void tracesExportLandsSpansWithEventsAsJsonText() throws Exception {
        createSink("[\"traces\"]");

        export("traces", """
                {"resourceSpans":[{
                  "resource":{"attributes":[{"key":"service.name","value":{"stringValue":"api"}}]},
                  "scopeSpans":[{"spans":[
                    {"name":"GET /x","kind":"SPAN_KIND_SERVER","traceId":"AABB","spanId":"CCDD",
                     "startTimeUnixNano":"%s","endTimeUnixNano":"%s",
                     "status":{"code":"STATUS_CODE_OK"},
                     "events":[{"name":"cache.miss"}],
                     "attributes":[{"key":"http.route","value":{"stringValue":"/x"}}]}]}]}]}"""
                .formatted(nanosAgo(2000), nanosAgo(1500)), 200);

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT span_name, span_kind, duration_nano, status_code, "
                                + "events, span_attributes FROM otel_app_traces\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].span_name").value("GET /x"))
                .andExpect(jsonPath("$.rows[0].span_kind").value("SPAN_KIND_SERVER"))
                .andExpect(jsonPath("$.rows[0].duration_nano").value(500000000L))
                .andExpect(jsonPath("$.rows[0].status_code").value("STATUS_CODE_OK"))
                .andExpect(jsonPath("$.rows[0].events").value(containsString("cache.miss")))
                .andExpect(jsonPath("$.rows[0].span_attributes['http.route']").value("/x"));
    }

    @Test
    void emptyExportIsAcceptedAsNoOp() throws Exception {
        createSink("[\"logs\"]");
        export("logs", "{\"resourceLogs\":[]}", 200);

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT service_name FROM otel_app_logs\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(0));
    }

    @Test
    void malformedCreateBodyIsAlways422ValidationError() throws Exception {
        // Nothing about this body is bean-validated: an empty list, an unknown code, a duplicate, a blank
        // name and a sub-1 retention all reach the service and report one status, never a binding 400.
        final String[] bodies = {
                "{\"name\":\"app\",\"signals\":[]}",
                "{\"name\":\"app\"}",
                "{\"name\":\"app\",\"signals\":[\"profiles\"]}",
                "{\"name\":\"app\",\"signals\":[\"logs\",\"logs\"]}",
                "{\"name\":\"\",\"signals\":[\"logs\"]}",
                "{\"signals\":[\"logs\"]}",
                "{\"name\":\"app\",\"signals\":[\"logs\"],\"retention_days\":0}"};
        for (final String body : bodies) {
            mockMvc.perform(post(SINKS).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("validation_error"));
        }
        mockMvc.perform(get(SINKS + "/" + SINK)).andExpect(status().isNotFound());
    }

    @Test
    void wronglyShapedFieldStillLandsTheRecord() throws Exception {
        createSink("[\"logs\"]");

        export("logs", """
                {"resourceLogs":[{"scopeLogs":[{"logRecords":[
                  {"timeUnixNano":"%s","severityText":{},"traceId":[],
                   "body":{"stringValue":"still lands"}}]}]}]}""".formatted(nanosAgo(1000)), 200);

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT body, severity_text, trace_id FROM otel_app_logs\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].body").value("still lands"))
                .andExpect(jsonPath("$.rows[0].severity_text").value(""))
                .andExpect(jsonPath("$.rows[0].trace_id").value(""));
    }

    @Test
    void exportingSignalTheSinkDidNotEnableIs404() throws Exception {
        createSink("[\"logs\"]");
        export("metrics", "{\"resourceMetrics\":[]}", 404);
    }

    @Test
    void exportingToUnknownSinkIs404() throws Exception {
        mockMvc.perform(post("/v1/otel/sink/nope/v1/logs")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resourceLogs\":[]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void exportAfterBackingTableDeletedIs409AndSinkReportsItAbsent() throws Exception {
        createSink("[\"logs\"]");
        mockMvc.perform(delete("/v1/tables/otel_app_logs")).andExpect(status().isNoContent());

        mockMvc.perform(get(SINKS + "/" + SINK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signals[0].status").doesNotExist());
        export("logs", """
                {"resourceLogs":[{"scopeLogs":[{"logRecords":[{"body":{"stringValue":"x"}}]}]}]}""", 409);
    }

    @Test
    void sinkWhoseDerivedTableNameIsTakenIsRefusedAndCreatesNothing() throws Exception {
        IntegrationTables.source(mockMvc, "otel_app_traces",
                "[{\"source_name\":\"a\",\"name\":\"a\",\"type\":\"string\"}]", "[\"a\"]");
        try {
            mockMvc.perform(post(SINKS).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"app\",\"signals\":[\"logs\",\"traces\"]}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("conflict"));

            // Nothing partial was left behind: the logs table was never created and no sink was recorded.
            mockMvc.perform(get("/v1/tables/otel_app_logs")).andExpect(status().isNotFound());
            mockMvc.perform(get(SINKS + "/" + SINK)).andExpect(status().isNotFound());
        } finally {
            mockMvc.perform(delete("/v1/tables/otel_app_traces"));
        }
    }

    @Test
    void duplicateSinkIsRefused() throws Exception {
        createSink("[\"logs\"]");
        mockMvc.perform(post(SINKS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + SINK + "\",\"signals\":[\"traces\"]}"))
                .andExpect(status().isConflict());
        // The refused create must not have added a traces table to the existing sink.
        mockMvc.perform(get("/v1/tables/otel_app_traces")).andExpect(status().isNotFound());
    }

    @Test
    void deletingTheSinkDropsEveryLandingTable() throws Exception {
        createSink("[\"logs\",\"metrics\"]");

        mockMvc.perform(delete(SINKS + "/" + SINK)).andExpect(status().isNoContent());

        mockMvc.perform(get(SINKS + "/" + SINK)).andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/tables/otel_app_logs")).andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/tables/otel_app_metrics")).andExpect(status().isNotFound());
    }

    @Test
    void mapFieldsAreDiscoverableAndProjectionOnlyInSql() throws Exception {
        createSink("[\"logs\"]");

        mockMvc.perform(get("/v1/queries/entities/schema/otel_app_logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[?(@.name == 'log_attributes')].type").value("map"));

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT service_name FROM otel_app_logs "
                                + "ORDER BY log_attributes\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("log_attributes")))
                .andExpect(jsonPath("$.message").value(containsString("can only be projected")));
    }

    /**
     * The {@code JSON_EXTRACT_*} carve-out is {@code object}-only. A ClickHouse
     * {@code Map(String, String)} is not a JSON document and the DSL has no rendering that extracts a
     * path from one, so a map field must be refused there like any other function argument — not fall
     * through to a raw ClickHouse type error.
     */
    @Test
    void jsonExtractOverMapFieldIsRefused() throws Exception {
        createSink("[\"logs\"]");

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT JSON_EXTRACT_STRING(log_attributes, 'k') "
                                + "FROM otel_app_logs\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("log_attributes")))
                .andExpect(jsonPath("$.message").value(containsString("can only be projected")));
    }
}
