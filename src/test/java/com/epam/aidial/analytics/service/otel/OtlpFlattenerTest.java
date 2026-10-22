package com.epam.aidial.analytics.service.otel;

import com.epam.aidial.analytics.service.otel.otlp.OtlpFlattener;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the OTLP → landing-row mapping: the fan-out counts, that resource and scope facts are
 * copied onto every row beneath them, and that each metric shape populates its own columns and leaves the
 * others null.
 */
class OtlpFlattenerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Instant RECEIVED = Instant.parse("2026-08-19T12:00:00Z");

    private final OtlpFlattener flattener = new OtlpFlattener();

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }

    private List<Map<String, Object>> flatten(OtelSignal signal, String payload) {
        return flattener.flatten(signal, json(payload), RECEIVED);
    }

    @Test
    void emptyExportProducesNoRows() {
        assertThat(flatten(OtelSignal.LOGS, "{\"resourceLogs\":[]}")).isEmpty();
        assertThat(flatten(OtelSignal.LOGS, "{}")).isEmpty();
        assertThat(flatten(OtelSignal.TRACES, "{\"resourceSpans\":[{\"scopeSpans\":[]}]}")).isEmpty();
    }

    @Test
    void oneRowPerLogRecordCarryingResourceAndScopeFacts() {
        final List<Map<String, Object>> rows = flatten(OtelSignal.LOGS, """
                {"resourceLogs":[{
                  "resource":{"attributes":[
                    {"key":"service.name","value":{"stringValue":"api"}},
                    {"key":"host.id","value":{"intValue":"7"}}]},
                  "scopeLogs":[{
                    "scope":{"name":"lib","version":"1.2.3",
                             "attributes":[{"key":"s","value":{"stringValue":"v"}}]},
                    "logRecords":[
                      {"timeUnixNano":"1700000000000000000","severityText":"INFO","severityNumber":9,
                       "body":{"stringValue":"hello"},
                       "traceId":"5B8EFFF798038103D269B633813FC60C",
                       "attributes":[{"key":"http.method","value":{"stringValue":"GET"}}]},
                      {"timeUnixNano":"1700000001000000000","body":{"stringValue":"second"}}]}]}]}""");

        assertThat(rows).hasSize(2);
        final Map<String, Object> first = rows.getFirst();
        assertThat(first).containsEntry("service_name", "api")
                .containsEntry("severity_text", "INFO")
                .containsEntry("severity_number", 9)
                .containsEntry("body", "hello")
                .containsEntry("trace_id", "5b8efff798038103d269b633813fc60c")
                .containsEntry("scope_name", "lib")
                .containsEntry("scope_version", "1.2.3")
                .containsEntry("event_time", "2023-11-14T22:13:20Z");
        assertThat(first).extracting("resource_attributes")
                .isEqualTo(Map.of("service.name", "api", "host.id", "7"));
        assertThat(first).extracting("log_attributes").isEqualTo(Map.of("http.method", "GET"));
        assertThat(first).extracting("scope_attributes").isEqualTo(Map.of("s", "v"));
        // Every row beneath one resource/scope pair carries the same facts.
        assertThat(rows.get(1)).containsEntry("service_name", "api").containsEntry("scope_name", "lib");
    }

    @Test
    void logWithoutTimeFallsBackToObservedThenToReceiveTime() {
        final Map<String, Object> observed = flatten(OtelSignal.LOGS, """
                {"resourceLogs":[{"scopeLogs":[{"logRecords":[
                  {"observedTimeUnixNano":"1700000000000000000"}]}]}]}""").getFirst();
        assertThat(observed).containsEntry("event_time", "2023-11-14T22:13:20Z")
                .containsEntry("observed_time", "2023-11-14T22:13:20Z");

        final Map<String, Object> neither = flatten(OtelSignal.LOGS,
                "{\"resourceLogs\":[{\"scopeLogs\":[{\"logRecords\":[{}]}]}]}").getFirst();
        assertThat(neither).containsEntry("event_time", RECEIVED.toString());
        assertThat(neither.get("observed_time")).isNull();
    }

    @Test
    void spanCarriesItsDurationStatusAndJsonTextEventsAndLinks() {
        final Map<String, Object> row = flatten(OtelSignal.TRACES, """
                {"resourceSpans":[{
                  "resource":{"attributes":[{"key":"service.name","value":{"stringValue":"api"}}]},
                  "scopeSpans":[{"spans":[{
                    "name":"GET /x","kind":"SPAN_KIND_SERVER",
                    "startTimeUnixNano":"1700000000000000000",
                    "endTimeUnixNano":"1700000000500000000",
                    "traceId":"AABB","spanId":"CCDD","parentSpanId":"EEFF",
                    "status":{"code":"STATUS_CODE_OK","message":"fine"},
                    "events":[{"name":"e"}],
                    "attributes":[{"key":"http.status_code","value":{"intValue":"200"}}]}]}]}]}""").getFirst();

        assertThat(row).containsEntry("span_name", "GET /x")
                .containsEntry("span_kind", "SPAN_KIND_SERVER")
                .containsEntry("duration_nano", 500_000_000L)
                .containsEntry("status_code", "STATUS_CODE_OK")
                .containsEntry("status_message", "fine")
                .containsEntry("trace_id", "aabb")
                .containsEntry("parent_span_id", "eeff")
                .containsEntry("events", "[{\"name\":\"e\"}]")
                .containsEntry("links", "[]");
        assertThat(row).extracting("span_attributes").isEqualTo(Map.of("http.status_code", "200"));
    }

    @Test
    void spanDurationThatOverflowsLandsAsZeroRatherThanWrapping() {
        final Map<String, Object> row = flatten(OtelSignal.TRACES, """
                {"resourceSpans":[{"scopeSpans":[{"spans":[{
                  "startTimeUnixNano":"-9000000000000000000",
                  "endTimeUnixNano":"9000000000000000000"}]}]}]}""").getFirst();

        assertThat(row).containsEntry("duration_nano", 0L);
    }

    @Test
    void spanTimestampBeforeNineteenHundredIsAbsentButDurationStillLands() {
        final Map<String, Object> row = flatten(OtelSignal.TRACES, """
                {"resourceSpans":[{"scopeSpans":[{"spans":[{
                  "startTimeUnixNano":"-8000000000000000000",
                  "endTimeUnixNano":"-7999999999000000000"}]}]}]}""").getFirst();

        assertThat(row).containsEntry("event_time", RECEIVED.toString())
                .containsEntry("duration_nano", 1_000_000_000L);
        assertThat(row.get("end_time")).isNull();
    }

    @Test
    void oneRowPerMetricDataPointEachWithItsOwnAttributes() {
        final List<Map<String, Object>> rows = flatten(OtelSignal.METRICS, """
                {"resourceMetrics":[{"scopeMetrics":[{"metrics":[
                  {"name":"requests","unit":"1","sum":{"isMonotonic":true,
                   "aggregationTemporality":"AGGREGATION_TEMPORALITY_CUMULATIVE",
                   "dataPoints":[
                     {"timeUnixNano":"1700000000000000000","asInt":"1",
                      "attributes":[{"key":"route","value":{"stringValue":"a"}}]},
                     {"timeUnixNano":"1700000000000000000","asInt":"2",
                      "attributes":[{"key":"route","value":{"stringValue":"b"}}]},
                     {"timeUnixNano":"1700000000000000000","asDouble":3.5}]}}]}]}]}""");

        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> assertThat(row)
                .containsEntry("metric_name", "requests")
                .containsEntry("metric_type", "sum")
                .containsEntry("is_monotonic", true)
                .containsEntry("aggregation_temporality", "AGGREGATION_TEMPORALITY_CUMULATIVE"));
        assertThat(rows.get(0)).extracting("point_attributes").isEqualTo(Map.of("route", "a"));
        assertThat(rows.get(1)).extracting("point_attributes").isEqualTo(Map.of("route", "b"));
        assertThat(rows.get(2)).extracting("point_attributes").isEqualTo(Map.of());
        assertThat(String.valueOf(rows.get(2).get("point_value"))).isEqualTo("3.5");
    }

    @Test
    void histogramPopulatesItsOwnColumnsAndLeavesValueNull() {
        final Map<String, Object> row = flatten(OtelSignal.METRICS, """
                {"resourceMetrics":[{"scopeMetrics":[{"metrics":[
                  {"name":"latency","histogram":{"dataPoints":[
                    {"timeUnixNano":"1700000000000000000","count":"3","sum":6.0,"min":1.0,"max":3.0,
                     "bucketCounts":["1","2"],"explicitBounds":[2.0]}]}}]}]}]}""").getFirst();

        assertThat(row).containsEntry("metric_type", "histogram")
                .containsEntry("point_count", 3L);
        assertThat(row.get("point_value")).isNull();
        assertThat(row).extracting("bucket_counts").isEqualTo(List.of(1L, 2L));
        assertThat((List<?>) row.get("explicit_bounds")).hasSize(1);
    }

    @Test
    void summaryStoresItsQuantilesAsJsonText() {
        final Map<String, Object> row = flatten(OtelSignal.METRICS, """
                {"resourceMetrics":[{"scopeMetrics":[{"metrics":[
                  {"name":"latency","summary":{"dataPoints":[
                    {"timeUnixNano":"1700000000000000000","count":"2","sum":4.0,
                     "quantileValues":[{"quantile":0.5,"value":2.0}]}]}}]}]}]}""").getFirst();

        assertThat(row).containsEntry("metric_type", "summary").containsEntry("point_count", 2L);
        assertThat(String.valueOf(row.get("quantiles"))).contains("\"quantile\":0.5");
    }

    @Test
    void exponentialHistogramPopulatesItsBucketStructure() {
        final Map<String, Object> row = flatten(OtelSignal.METRICS, """
                {"resourceMetrics":[{"scopeMetrics":[{"metrics":[
                  {"name":"latency","exponentialHistogram":{"dataPoints":[
                    {"timeUnixNano":"1700000000000000000","count":"5","sum":10.0,"scale":2,
                     "zeroCount":"1","positive":{"offset":3,"bucketCounts":["1","2"]}}]}}]}]}]}""").getFirst();

        assertThat(row).containsEntry("metric_type", "exponential_histogram")
                .containsEntry("exp_scale", 2)
                .containsEntry("exp_zero_count", 1L)
                .containsEntry("exp_positive_offset", 3);
        assertThat(row).extracting("exp_positive_bucket_counts").isEqualTo(List.of(1L, 2L));
        assertThat(row).extracting("exp_negative_bucket_counts").isEqualTo(List.of());
    }

    @Test
    void nonFiniteGaugeValueLandsNullRatherThanFailingTheRow() {
        final Map<String, Object> row = flatten(OtelSignal.METRICS, """
                {"resourceMetrics":[{"scopeMetrics":[{"metrics":[
                  {"name":"temp","gauge":{"dataPoints":[
                    {"timeUnixNano":"1700000000000000000","asDouble":"NaN"}]}}]}]}]}""").getFirst();

        assertThat(row).containsEntry("metric_type", "gauge");
        assertThat(row.get("point_value")).isNull();
    }

    @Test
    void metricCarryingNoRecognisedShapeContributesNoRows() {
        // A metric type from a newer OTLP version must not produce a row with no value.
        assertThat(flatten(OtelSignal.METRICS,
                "{\"resourceMetrics\":[{\"scopeMetrics\":[{\"metrics\":[{\"name\":\"x\"}]}]}]}")).isEmpty();
    }

    @Test
    void numericallyEncodedEnumsLandAsTheirOtlpNames() {
        // proto3 JSON lets an enum be encoded as its name or as its integer value, and several OTLP
        // producers emit the integer form; the landing columns are specified as the name either way.
        final Map<String, Object> span = flatten(OtelSignal.TRACES, """
                {"resourceSpans":[{"scopeSpans":[{"spans":[
                  {"name":"GET /x","kind":2,"status":{"code":2}}]}]}]}""").getFirst();

        assertThat(span).containsEntry("span_kind", "SPAN_KIND_SERVER")
                .containsEntry("status_code", "STATUS_CODE_ERROR");

        final Map<String, Object> point = flatten(OtelSignal.METRICS, """
                {"resourceMetrics":[{"scopeMetrics":[{"metrics":[
                  {"name":"requests","sum":{"aggregationTemporality":2,"dataPoints":[
                    {"timeUnixNano":"1700000000000000000","asInt":"1"}]}}]}]}]}""").getFirst();

        assertThat(point).containsEntry("aggregation_temporality", "AGGREGATION_TEMPORALITY_CUMULATIVE");
    }

    @Test
    void nameEncodedSeverityLandsAsItsOtlpNumber() {
        // severityNumber is an enum too, and proto3 JSON's *default* encoding is the name — so a standard
        // protojson exporter sends "SEVERITY_NUMBER_ERROR", which must still land as 17 for the usual
        // "severity_number >= 17" filter to match.
        final Map<String, Object> named = flatten(OtelSignal.LOGS, """
                {"resourceLogs":[{"scopeLogs":[{"logRecords":[
                  {"severityNumber":"SEVERITY_NUMBER_ERROR","severityText":"ERROR"}]}]}]}""").getFirst();

        assertThat(named).containsEntry("severity_number", 17);

        final Map<String, Object> numeric = flatten(OtelSignal.LOGS, """
                {"resourceLogs":[{"scopeLogs":[{"logRecords":[{"severityNumber":17}]}]}]}""").getFirst();
        assertThat(numeric).containsEntry("severity_number", 17);

        // An unknown member cannot be stored as received in an integer column; it reads as "unspecified".
        final Map<String, Object> unknown = flatten(OtelSignal.LOGS, """
                {"resourceLogs":[{"scopeLogs":[{"logRecords":[
                  {"severityNumber":"SEVERITY_NUMBER_FUTURE"}]}]}]}""").getFirst();
        assertThat(unknown).containsEntry("severity_number", 0);
    }

    @Test
    void enumValueFromNewerOtlpVersionIsStoredAsReceived() {
        final Map<String, Object> row = flatten(OtelSignal.TRACES, """
                {"resourceSpans":[{"scopeSpans":[{"spans":[
                  {"name":"GET /x","kind":99,"status":{"code":"STATUS_CODE_FUTURE"}}]}]}]}""").getFirst();

        assertThat(row).containsEntry("span_kind", "99")
                .containsEntry("status_code", "STATUS_CODE_FUTURE");
    }

    @Test
    void wronglyShapedFieldLandsEmptyInsteadOfFailingTheExport() {
        // Jackson 3's plain coercions throw on a container where a scalar belongs; a producer's shape
        // mistake must not turn into a 500, so the field reads as absent and the record still lands.
        final Map<String, Object> log = flatten(OtelSignal.LOGS, """
                {"resourceLogs":[{"scopeLogs":[{"logRecords":[
                  {"severityText":{},"traceId":[],"timeUnixNano":{"a":1},
                   "attributes":[{"key":"k","value":{"stringValue":{"nested":true}}}]}]}]}]}""").getFirst();

        assertThat(log).containsEntry("severity_text", "")
                .containsEntry("trace_id", "")
                .containsEntry("event_time", RECEIVED.toString());
        assertThat(log).extracting("log_attributes").isEqualTo(Map.of("k", ""));

        final Map<String, Object> point = flatten(OtelSignal.METRICS, """
                {"resourceMetrics":[{"scopeMetrics":[{"metrics":[
                  {"name":"latency","histogram":{"dataPoints":[
                    {"timeUnixNano":"1700000000000000000","count":"1",
                     "bucketCounts":[{}],"explicitBounds":[[]]}]}}]}]}]}""").getFirst();

        assertThat(point).containsEntry("bucket_counts", List.of(0L))
                .containsEntry("explicit_bounds", List.of(java.math.BigDecimal.ZERO));
    }

    @Test
    void bothFieldSpellingsFanOutIdentically() {
        assertThat(flatten(OtelSignal.LOGS, """
                {"resource_logs":[{"scope_logs":[{"log_records":[{"body":{"stringValue":"a"}}]}]}]}"""))
                .hasSize(1);
    }
}
