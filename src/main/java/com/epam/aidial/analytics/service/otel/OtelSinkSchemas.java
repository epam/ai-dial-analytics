package com.epam.aidial.analytics.service.otel;

import com.epam.aidial.analytics.service.table.ColumnInput;
import com.epam.aidial.analytics.service.table.DefineSchemaInput;
import com.epam.aidial.analytics.service.table.PartitionGranularity;
import com.epam.aidial.analytics.service.table.TtlInput;
import com.epam.aidial.analytics.service.table.TtlUnit;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;

import java.util.List;

/**
 * The fixed physical schema of each signal's landing table. This is the single normative source for
 * the column set: the flatteners key their rows by exactly these physical names, and the spec's
 * "OTLP landing table schemas" requirement is the contract these lists implement.
 *
 * <p>Three shape decisions worth knowing before reading the lists:
 *
 * <ul>
 *   <li><b>No column is named after a SQL reserved word.</b> The obvious OTLP spellings — {@code
 *       timestamp}, {@code value}, {@code count}, {@code sum}, {@code min}, {@code max} — are all
 *       keywords in the Calcite dialect the SQL surface parses, so a column carrying one could not be
 *       selected, filtered or ordered by its bare name; the caller would have to know to quote it.
 *       Since ADAS chooses these names rather than the user, they are chosen to be queryable:
 *       {@code event_time}, {@code point_value}, {@code point_count}, and so on.</li>
 *   <li><b>Attribute bags are {@code map}</b> ({@code Map(String, String)}), one column per OTLP scope —
 *       resource, instrumentation scope, and the record's own. They are never nullable: ClickHouse has
 *       no {@code Nullable(Map)}, and an absent bag is the empty map.</li>
 *   <li><b>Metrics are one table, one row per data point</b>, discriminated by {@code metric_type}, with
 *       the type-specific columns nullable. The reference ClickHouse exporter uses five tables; a single
 *       discriminated table keeps a query over a metric name working without a union, at the cost of
 *       columns that are null for most rows — which a {@code Nullable} column of nulls compresses to
 *       almost nothing.</li>
 * </ul>
 *
 * <p>{@code service_name} leads every ordering key because it is the coarsest, most selective filter a
 * telemetry reader applies, and {@code event_time} closes it because reads are time-ranged. Every table
 * partitions by day on {@code event_time}, so a retention TTL drops whole parts.
 */
public final class OtelSinkSchemas {

    /** The physical time column every signal orders and partitions by. */
    public static final String TIME_COLUMN = "event_time";

    private OtelSinkSchemas() {
    }

    /**
     * The complete schema for {@code signal}'s landing table.
     *
     * @param retentionDays row-expiry TTL in days, or 0 for no TTL
     */
    public static DefineSchemaInput schemaFor(OtelSignal signal, int retentionDays) {
        return new DefineSchemaInput(
                columnsFor(signal),
                orderingKeyFor(signal),
                TIME_COLUMN,
                PartitionGranularity.DAY,
                null,
                null,
                retentionDays > 0 ? new TtlInput(TIME_COLUMN, retentionDays, TtlUnit.DAY) : null);
    }

    /** The exposed columns of {@code signal}'s landing table, in declaration order. */
    public static List<ColumnInput> columnsFor(OtelSignal signal) {
        return switch (signal) {
            case LOGS -> logColumns();
            case TRACES -> traceColumns();
            case METRICS -> metricColumns();
        };
    }

    /** The ordering key of {@code signal}'s landing table. */
    public static List<String> orderingKeyFor(OtelSignal signal) {
        return switch (signal) {
            case LOGS -> List.of("service_name", TIME_COLUMN);
            case TRACES -> List.of("service_name", "span_name", TIME_COLUMN);
            case METRICS -> List.of("service_name", "metric_name", TIME_COLUMN);
        };
    }

    private static List<ColumnInput> logColumns() {
        return List.of(
                column(TIME_COLUMN, QueryFieldType.TIMESTAMP, false,
                        "Record time (timeUnixNano, falling back to observedTimeUnixNano, then receive time)"),
                column("observed_time", QueryFieldType.TIMESTAMP, true,
                        "Time the collector observed the record (observedTimeUnixNano)"),
                column("service_name", QueryFieldType.STRING, false,
                        "The resource's service.name attribute, empty when it declares none"),
                column("severity_number", QueryFieldType.INTEGER, false, "OTLP severity number (1-24)"),
                column("severity_text", QueryFieldType.STRING, false, "OTLP severity text (e.g. INFO)"),
                column("body", QueryFieldType.STRING, false, "The record body, rendered to text"),
                column("trace_id", QueryFieldType.STRING, false, "Lowercase hex trace id, empty when absent"),
                column("span_id", QueryFieldType.STRING, false, "Lowercase hex span id, empty when absent"),
                column("trace_flags", QueryFieldType.INTEGER, false, "W3C trace flags"),
                map("resource_attributes", "Attributes of the resource that produced the record"),
                column("scope_name", QueryFieldType.STRING, false, "Instrumentation scope name"),
                column("scope_version", QueryFieldType.STRING, false, "Instrumentation scope version"),
                map("scope_attributes", "Attributes of the instrumentation scope"),
                map("log_attributes", "Attributes of the log record itself"),
                column("dropped_attributes_count", QueryFieldType.INTEGER, false,
                        "Attributes the producer dropped before export"));
    }

    private static List<ColumnInput> traceColumns() {
        return List.of(
                column(TIME_COLUMN, QueryFieldType.TIMESTAMP, false,
                        "Span start time (startTimeUnixNano, falling back to receive time)"),
                column("end_time", QueryFieldType.TIMESTAMP, true, "Span end time (endTimeUnixNano)"),
                column("duration_nano", QueryFieldType.LONG, false, "Span duration in nanoseconds"),
                column("service_name", QueryFieldType.STRING, false,
                        "The resource's service.name attribute, empty when it declares none"),
                column("trace_id", QueryFieldType.STRING, false, "Lowercase hex trace id"),
                column("span_id", QueryFieldType.STRING, false, "Lowercase hex span id"),
                column("parent_span_id", QueryFieldType.STRING, false,
                        "Lowercase hex parent span id, empty for a root span"),
                column("trace_state", QueryFieldType.STRING, false, "W3C tracestate header value"),
                column("span_name", QueryFieldType.STRING, false, "Span name"),
                column("span_kind", QueryFieldType.STRING, false, "OTLP span kind name (e.g. SPAN_KIND_SERVER)"),
                column("status_code", QueryFieldType.STRING, false, "OTLP status code name (e.g. STATUS_CODE_OK)"),
                column("status_message", QueryFieldType.STRING, false, "Status description"),
                map("resource_attributes", "Attributes of the resource that produced the span"),
                column("scope_name", QueryFieldType.STRING, false, "Instrumentation scope name"),
                column("scope_version", QueryFieldType.STRING, false, "Instrumentation scope version"),
                map("scope_attributes", "Attributes of the instrumentation scope"),
                map("span_attributes", "Attributes of the span itself"),
                column("events", QueryFieldType.STRING, false, "The span's events as a compact JSON array"),
                column("links", QueryFieldType.STRING, false, "The span's links as a compact JSON array"),
                column("dropped_attributes_count", QueryFieldType.INTEGER, false,
                        "Attributes the producer dropped before export"));
    }

    private static List<ColumnInput> metricColumns() {
        return List.of(
                column(TIME_COLUMN, QueryFieldType.TIMESTAMP, false,
                        "Data point time (timeUnixNano, falling back to receive time)"),
                column("start_time", QueryFieldType.TIMESTAMP, true,
                        "Start of the data point's aggregation window (startTimeUnixNano)"),
                column("service_name", QueryFieldType.STRING, false,
                        "The resource's service.name attribute, empty when it declares none"),
                column("metric_name", QueryFieldType.STRING, false, "Metric name"),
                column("metric_description", QueryFieldType.STRING, false, "Metric description"),
                column("metric_unit", QueryFieldType.STRING, false, "Metric unit"),
                column("metric_type", QueryFieldType.STRING, false,
                        "gauge, sum, histogram, exponential_histogram or summary"),
                column("aggregation_temporality", QueryFieldType.STRING, false,
                        "OTLP aggregation temporality name, empty for a gauge or summary"),
                column("is_monotonic", QueryFieldType.BOOLEAN, false, "Whether a sum is monotonic"),
                map("resource_attributes", "Attributes of the resource that produced the data point"),
                column("scope_name", QueryFieldType.STRING, false, "Instrumentation scope name"),
                column("scope_version", QueryFieldType.STRING, false, "Instrumentation scope version"),
                map("scope_attributes", "Attributes of the instrumentation scope"),
                sensitiveMap("point_attributes", "Attributes of the data point itself"),
                column("point_value", QueryFieldType.DECIMAL, true,
                        "Gauge or sum value; null for other metric types"),
                column("point_count", QueryFieldType.LONG, true, "Histogram or summary observation count"),
                column("point_sum", QueryFieldType.DECIMAL, true, "Histogram or summary sum of observations"),
                column("point_min", QueryFieldType.DECIMAL, true, "Histogram minimum observation"),
                column("point_max", QueryFieldType.DECIMAL, true, "Histogram maximum observation"),
                array("bucket_counts", QueryFieldType.LONG, "Explicit-bucket histogram counts"),
                array("explicit_bounds", QueryFieldType.DECIMAL, "Explicit-bucket histogram bounds"),
                column("exp_scale", QueryFieldType.INTEGER, true, "Exponential histogram scale"),
                column("exp_zero_count", QueryFieldType.LONG, true, "Exponential histogram zero-bucket count"),
                column("exp_positive_offset", QueryFieldType.INTEGER, true,
                        "Exponential histogram positive-bucket offset"),
                array("exp_positive_bucket_counts", QueryFieldType.LONG,
                        "Exponential histogram positive-bucket counts"),
                column("exp_negative_offset", QueryFieldType.INTEGER, true,
                        "Exponential histogram negative-bucket offset"),
                array("exp_negative_bucket_counts", QueryFieldType.LONG,
                        "Exponential histogram negative-bucket counts"),
                column("quantiles", QueryFieldType.STRING, false,
                        "Summary quantile values as a compact JSON array"));
    }

    private static ColumnInput column(String name, QueryFieldType type, boolean nullable, String description) {
        return new ColumnInput(name, name, type, null, null, nullable, description, null, null, false, false);
    }

    private static ColumnInput map(String name, String description) {
        // Never nullable: ClickHouse has no Nullable(Map), and an absent bag is the empty map.
        return new ColumnInput(name, name, QueryFieldType.MAP, null, null, false, description, null, null, false, false);
    }

    /**
     * A map column carrying end-user identity, so declared {@code sensitive} — it resolves only for
     * a holder of {@code TABLE_READ} and {@code TABLE_SENSITIVE} on the landing table, through the one per-column
     * chokepoint in {@code CatalogQueryService}.
     *
     * <p>Used for the metrics table's {@code point_attributes}. OTLP data-point attributes are
     * producer-defined and routinely carry identity: the Claude Code feed puts {@code user.email},
     * {@code user.id} and {@code user.account_id} there beside {@code model}, {@code effort} and
     * {@code query_source}. Projecting the column was already enough to read those; {@code map_value} makes
     * them <em>aggregatable</em>, which turns one query into a named per-person cost ranking.
     *
     * <p><b>Sensitivity is whole-column, and that is the accepted cost.</b> A principal that cannot read
     * {@code point_attributes} cannot read any key of it, the non-identifying ones included, so aggregate
     * reporting over data-point attributes now requires {@code TABLE_READ} and {@code TABLE_SENSITIVE}. Per-key
     * sensitivity is not
     * modelled; the supported way to expose one key more widely is to lift it into an enrichment column with
     * {@code map_value}, where it carries its own flag.
     *
     * <p><b>This applies to sinks created after it ships.</b> {@code OtelSinkService} defines a sink's schema
     * once, at creation, and does not reconcile column metadata at startup — so an already-materialized
     * metrics table keeps its stored flags until they are updated by
     * {@code PATCH /v1/tables/{name}/schema} carrying a column metadata update that sets
     * {@code sensitive: true} on {@code point_attributes}. That is the only endpoint that changes the flag:
     * {@code PUT /v1/tables/{name}} updates the description and tag ordering and nothing else, so calling it
     * would report success while leaving the column readable.
     * {@code resource_attributes} and {@code scope_attributes} stay unflagged: they describe the producing
     * resource and instrumentation scope (host, OS, service name and version), not the end user.
     */
    private static ColumnInput sensitiveMap(String name, String description) {
        return new ColumnInput(name, name, QueryFieldType.MAP, null, null, false, description, null, null, true, false);
    }

    private static ColumnInput array(String name, QueryFieldType elementType, String description) {
        return new ColumnInput(name, name, QueryFieldType.ARRAY, elementType, null, false, description,
                null, null, false, false);
    }
}
