package com.epam.aidial.analytics.service.otel.otlp;

import com.epam.aidial.analytics.service.otel.OtelSignal;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Flattens an OTLP export into landing-table rows keyed by physical column name — the write side, so raw
 * ClickHouse column names rather than exposed query names.
 *
 * <p><b>Fan-out is the same shape for all three signals</b> ({@code resourceX[] → scopeX[] → records[]}),
 * so it lives once in {@link #flatten} and each signal contributes only its per-record mapping. Resource
 * and instrumentation-scope facts are resolved once per pair ({@link OtlpScope}) and copied onto every row
 * beneath them, which is what makes a landing table self-contained: a reader never has to join back to a
 * resource to know which service a record came from.
 *
 * <p><b>Timestamps are rendered as ISO-8601 at UTC</b> rather than as epoch millis, because the shared
 * row-write path ({@code WriteTimestampNormalizer}) accepts exactly that and rejects a bare epoch number
 * as ambiguous. A record whose time field is absent or zero — OTLP's "unset" — falls back, for the columns
 * that cannot be null, to the time the export was received.
 */
@Component
public class OtlpFlattener {

    /**
     * {@code SeverityNumber}, {@code SpanKind}, {@code Status.StatusCode} and {@code AggregationTemporality}
     * by proto number — the lookup that lets a name-encoded enum (proto3 JSON's default) be resolved.
     */
    private static final String[] SEVERITY_NUMBERS = {
        "SEVERITY_NUMBER_UNSPECIFIED",
        "SEVERITY_NUMBER_TRACE", "SEVERITY_NUMBER_TRACE2", "SEVERITY_NUMBER_TRACE3", "SEVERITY_NUMBER_TRACE4",
        "SEVERITY_NUMBER_DEBUG", "SEVERITY_NUMBER_DEBUG2", "SEVERITY_NUMBER_DEBUG3", "SEVERITY_NUMBER_DEBUG4",
        "SEVERITY_NUMBER_INFO", "SEVERITY_NUMBER_INFO2", "SEVERITY_NUMBER_INFO3", "SEVERITY_NUMBER_INFO4",
        "SEVERITY_NUMBER_WARN", "SEVERITY_NUMBER_WARN2", "SEVERITY_NUMBER_WARN3", "SEVERITY_NUMBER_WARN4",
        "SEVERITY_NUMBER_ERROR", "SEVERITY_NUMBER_ERROR2", "SEVERITY_NUMBER_ERROR3", "SEVERITY_NUMBER_ERROR4",
        "SEVERITY_NUMBER_FATAL", "SEVERITY_NUMBER_FATAL2", "SEVERITY_NUMBER_FATAL3", "SEVERITY_NUMBER_FATAL4"};
    private static final String[] SPAN_KINDS = {
        "SPAN_KIND_UNSPECIFIED", "SPAN_KIND_INTERNAL", "SPAN_KIND_SERVER", "SPAN_KIND_CLIENT",
        "SPAN_KIND_PRODUCER", "SPAN_KIND_CONSUMER"};
    private static final String[] STATUS_CODES = {"STATUS_CODE_UNSET", "STATUS_CODE_OK", "STATUS_CODE_ERROR"};
    private static final String[] AGGREGATION_TEMPORALITIES = {
        "AGGREGATION_TEMPORALITY_UNSPECIFIED", "AGGREGATION_TEMPORALITY_DELTA",
        "AGGREGATION_TEMPORALITY_CUMULATIVE"};

    /** The top-level array of each signal's export request, and the per-scope record array inside it. */
    private static final Map<OtelSignal, String[]> ENVELOPE = Map.of(
            OtelSignal.LOGS, new String[] {"resourceLogs", "scopeLogs", "logRecords"},
            OtelSignal.TRACES, new String[] {"resourceSpans", "scopeSpans", "spans"},
            OtelSignal.METRICS, new String[] {"resourceMetrics", "scopeMetrics", "metrics"});

    /**
     * Flattens {@code payload} into rows for {@code signal}'s landing table.
     *
     * @param receivedAt the instant the export was received, used wherever OTLP leaves a required time unset
     */
    public List<Map<String, Object>> flatten(OtelSignal signal, JsonNode payload, Instant receivedAt) {
        final String[] envelope = ENVELOPE.get(signal);
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (final JsonNode resourceEntry : OtlpValues.elements(payload, envelope[0])) {
            final JsonNode resource = OtlpValues.field(resourceEntry, "resource");
            for (final JsonNode scopeEntry : OtlpValues.elements(resourceEntry, envelope[1])) {
                final OtlpScope scope = OtlpScope.of(resource, OtlpValues.field(scopeEntry, "scope"));
                for (final JsonNode record : OtlpValues.elements(scopeEntry, envelope[2])) {
                    switch (signal) {
                        case LOGS -> rows.add(logRow(record, scope, receivedAt));
                        case TRACES -> rows.add(spanRow(record, scope, receivedAt));
                        case METRICS -> metricRows(record, scope, receivedAt, rows::add);
                        default -> throw new IllegalStateException("no flattener for signal " + signal);
                    }
                }
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ logs

    private static Map<String, Object> logRow(JsonNode record, OtlpScope scope, Instant receivedAt) {
        final Long observed = OtlpValues.epochMillis(record, "observedTimeUnixNano");
        final Long emitted = OtlpValues.epochMillis(record, "timeUnixNano");
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_time", isoOrDefault(emitted != null ? emitted : observed, receivedAt));
        row.put("observed_time", iso(observed));
        row.put("service_name", scope.serviceName());
        row.put("severity_number", OtlpValues.enumNumber(record, "severityNumber", SEVERITY_NUMBERS));
        row.put("severity_text", OtlpValues.text(record, "severityText"));
        row.put("body", OtlpValues.renderAnyValue(OtlpValues.field(record, "body")));
        row.put("trace_id", OtlpValues.hexId(record, "traceId"));
        row.put("span_id", OtlpValues.hexId(record, "spanId"));
        row.put("trace_flags", OtlpValues.intValue(record, "flags"));
        row.put("resource_attributes", scope.resourceAttributes());
        row.put("scope_name", scope.scopeName());
        row.put("scope_version", scope.scopeVersion());
        row.put("scope_attributes", scope.scopeAttributes());
        row.put("log_attributes", OtlpValues.attributes(record));
        row.put("dropped_attributes_count", OtlpValues.intValue(record, "droppedAttributesCount"));
        return row;
    }

    // ------------------------------------------------------------------ traces

    private static Map<String, Object> spanRow(JsonNode span, OtlpScope scope, Instant receivedAt) {
        final Long startNanos = OtlpValues.nanos(span, "startTimeUnixNano");
        final Long endNanos = OtlpValues.nanos(span, "endTimeUnixNano");
        final JsonNode status = OtlpValues.field(span, "status");
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_time", isoOrDefault(OtlpValues.timestampMillis(startNanos), receivedAt));
        row.put("end_time", iso(OtlpValues.timestampMillis(endNanos)));
        row.put("duration_nano", durationNanos(startNanos, endNanos));
        row.put("service_name", scope.serviceName());
        row.put("trace_id", OtlpValues.hexId(span, "traceId"));
        row.put("span_id", OtlpValues.hexId(span, "spanId"));
        row.put("parent_span_id", OtlpValues.hexId(span, "parentSpanId"));
        row.put("trace_state", OtlpValues.text(span, "traceState"));
        row.put("span_name", OtlpValues.text(span, "name"));
        row.put("span_kind", OtlpValues.enumName(span, "kind", SPAN_KINDS));
        row.put("status_code", OtlpValues.enumName(status, "code", STATUS_CODES));
        row.put("status_message", OtlpValues.text(status, "message"));
        row.put("resource_attributes", scope.resourceAttributes());
        row.put("scope_name", scope.scopeName());
        row.put("scope_version", scope.scopeVersion());
        row.put("scope_attributes", scope.scopeAttributes());
        row.put("span_attributes", OtlpValues.attributes(span));
        row.put("events", OtlpValues.jsonArrayText(span, "events"));
        row.put("links", OtlpValues.jsonArrayText(span, "links"));
        row.put("dropped_attributes_count", OtlpValues.intValue(span, "droppedAttributesCount"));
        return row;
    }

    // ------------------------------------------------------------------ metrics

    /**
     * Emits one row per data point of {@code metric}. The five OTLP metric shapes are read from whichever
     * member is present; a metric carrying none (or an unrecognised one from a newer OTLP version)
     * contributes no rows rather than a row with no value.
     */
    private static void metricRows(
            JsonNode metric, OtlpScope scope, Instant receivedAt, java.util.function.Consumer<Map<String, Object>> sink) {
        emitPoints(metric, "gauge", "gauge", scope, receivedAt, sink, OtlpFlattener::gaugeOrSumValue);
        emitPoints(metric, "sum", "sum", scope, receivedAt, sink, OtlpFlattener::gaugeOrSumValue);
        emitPoints(metric, "histogram", "histogram", scope, receivedAt, sink, OtlpFlattener::histogramValues);
        emitPoints(metric, "exponentialHistogram", "exponential_histogram", scope, receivedAt, sink,
                OtlpFlattener::exponentialHistogramValues);
        emitPoints(metric, "summary", "summary", scope, receivedAt, sink, OtlpFlattener::summaryValues);
    }

    private static void emitPoints(
            JsonNode metric,
            String member,
            String metricType,
            OtlpScope scope,
            Instant receivedAt,
            java.util.function.Consumer<Map<String, Object>> sink,
            BiConsumer<JsonNode, Map<String, Object>> values) {
        final JsonNode shape = OtlpValues.field(metric, member);
        if (shape == null) {
            return;
        }
        for (final JsonNode point : OtlpValues.elements(shape, "dataPoints")) {
            final Map<String, Object> row = metricRow(metric, shape, point, metricType, scope, receivedAt);
            values.accept(point, row);
            sink.accept(row);
        }
    }

    /** The columns every data point carries, whatever its metric shape; the type-specific ones start null. */
    private static Map<String, Object> metricRow(
            JsonNode metric, JsonNode shape, JsonNode point, String metricType, OtlpScope scope, Instant receivedAt) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_time", isoOrDefault(OtlpValues.epochMillis(point, "timeUnixNano"), receivedAt));
        row.put("start_time", iso(OtlpValues.epochMillis(point, "startTimeUnixNano")));
        row.put("service_name", scope.serviceName());
        row.put("metric_name", OtlpValues.text(metric, "name"));
        row.put("metric_description", OtlpValues.text(metric, "description"));
        row.put("metric_unit", OtlpValues.text(metric, "unit"));
        row.put("metric_type", metricType);
        row.put("aggregation_temporality",
                OtlpValues.enumName(shape, "aggregationTemporality", AGGREGATION_TEMPORALITIES));
        row.put("is_monotonic", OtlpValues.booleanValue(shape, "isMonotonic"));
        row.put("resource_attributes", scope.resourceAttributes());
        row.put("scope_name", scope.scopeName());
        row.put("scope_version", scope.scopeVersion());
        row.put("scope_attributes", scope.scopeAttributes());
        row.put("point_attributes", OtlpValues.attributes(point));
        row.put("point_value", null);
        row.put("point_count", null);
        row.put("point_sum", null);
        row.put("point_min", null);
        row.put("point_max", null);
        row.put("bucket_counts", List.of());
        row.put("explicit_bounds", List.of());
        row.put("exp_scale", null);
        row.put("exp_zero_count", null);
        row.put("exp_positive_offset", null);
        row.put("exp_positive_bucket_counts", List.of());
        row.put("exp_negative_offset", null);
        row.put("exp_negative_bucket_counts", List.of());
        row.put("quantiles", "[]");
        return row;
    }

    private static void gaugeOrSumValue(JsonNode point, Map<String, Object> row) {
        final BigDecimal asDouble = OtlpValues.decimal(point, "asDouble");
        row.put("point_value", asDouble != null ? asDouble : OtlpValues.decimal(point, "asInt"));
    }

    private static void histogramValues(JsonNode point, Map<String, Object> row) {
        row.put("point_count", OtlpValues.longValue(point, "count"));
        row.put("point_sum", OtlpValues.decimal(point, "sum"));
        row.put("point_min", OtlpValues.decimal(point, "min"));
        row.put("point_max", OtlpValues.decimal(point, "max"));
        row.put("bucket_counts", OtlpValues.longArray(point, "bucketCounts"));
        row.put("explicit_bounds", OtlpValues.decimalArray(point, "explicitBounds"));
    }

    private static void exponentialHistogramValues(JsonNode point, Map<String, Object> row) {
        row.put("point_count", OtlpValues.longValue(point, "count"));
        row.put("point_sum", OtlpValues.decimal(point, "sum"));
        row.put("point_min", OtlpValues.decimal(point, "min"));
        row.put("point_max", OtlpValues.decimal(point, "max"));
        row.put("exp_scale", OtlpValues.nullableInt(point, "scale"));
        row.put("exp_zero_count", OtlpValues.longValue(point, "zeroCount"));
        final JsonNode positive = OtlpValues.field(point, "positive");
        final JsonNode negative = OtlpValues.field(point, "negative");
        row.put("exp_positive_offset", OtlpValues.nullableInt(positive, "offset"));
        row.put("exp_positive_bucket_counts", OtlpValues.longArray(positive, "bucketCounts"));
        row.put("exp_negative_offset", OtlpValues.nullableInt(negative, "offset"));
        row.put("exp_negative_bucket_counts", OtlpValues.longArray(negative, "bucketCounts"));
    }

    private static void summaryValues(JsonNode point, Map<String, Object> row) {
        row.put("point_count", OtlpValues.longValue(point, "count"));
        row.put("point_sum", OtlpValues.decimal(point, "sum"));
        row.put("quantiles", OtlpValues.jsonArrayText(point, "quantileValues"));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The span's duration in nanoseconds, or {@code 0} when either end is unset or the difference overflows
     * a long — a malformed pair of timestamps is read as absent rather than wrapped into a bogus duration.
     */
    private static long durationNanos(Long startNanos, Long endNanos) {
        if (startNanos == null || endNanos == null) {
            return 0L;
        }
        try {
            return Math.subtractExact(endNanos, startNanos);
        } catch (ArithmeticException overflow) {
            return 0L;
        }
    }

    /** The ISO-8601 UTC rendering the row-write path accepts, or null when the instant is unset. */
    private static String iso(Long epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis).toString();
    }

    /** As {@link #iso}, falling back to {@code receivedAt} for a column that cannot be null. */
    private static String isoOrDefault(Long epochMillis, Instant receivedAt) {
        return epochMillis == null ? receivedAt.toString() : Instant.ofEpochMilli(epochMillis).toString();
    }
}
