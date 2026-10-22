## MODIFIED Requirements

### Requirement: OTLP landing table schemas

Each signal's landing table SHALL carry the fixed schema below. Every column is exposed under its own
physical name (no aliasing). `map` columns hold ClickHouse `Map(String, String)` and are non-nullable;
`array` columns are non-nullable; a column marked nullable below is declared `nullable: true`.

**No landing column SHALL be named after a word the SQL surface reserves.** The obvious OTLP spellings
(`timestamp`, `value`, `count`, `sum`, `min`, `max`) are keywords in the SQL dialect ADAS parses, so a
column carrying one could not be selected, filtered or ordered by its bare name. ADAS chooses these names
rather than the caller, so every one of them SHALL be usable unquoted in
`POST /v1/queries/execute-sql`.

**`otel_<sink>_logs`** — ordering key `(service_name, event_time)`, partitioned by `event_time` (`day`):

| Column | Type | Nullable | Source |
|---|---|---|---|
| `event_time` | timestamp | no | `timeUnixNano`, else `observedTimeUnixNano`, else receive time |
| `observed_time` | timestamp | yes | `observedTimeUnixNano` |
| `service_name` | string | no | resource attribute `service.name`, else empty |
| `severity_number` | integer | no | `severityNumber` as its OTLP enum number (name or integer accepted) |
| `severity_text` | string | no | `severityText` |
| `body` | string | no | `body` rendered by the AnyValue rendering below |
| `trace_id` | string | no | `traceId` (lowercase hex) |
| `span_id` | string | no | `spanId` (lowercase hex) |
| `trace_flags` | integer | no | `flags` |
| `resource_attributes` | map | no | resource `attributes` |
| `scope_name` | string | no | instrumentation scope `name` |
| `scope_version` | string | no | instrumentation scope `version` |
| `scope_attributes` | map | no | instrumentation scope `attributes` |
| `log_attributes` | map | no | log record `attributes` |
| `dropped_attributes_count` | integer | no | log record `droppedAttributesCount` |

**`otel_<sink>_traces`** — ordering key `(service_name, span_name, event_time)`, partitioned by
`event_time` (`day`):

| Column | Type | Nullable | Source |
|---|---|---|---|
| `event_time` | timestamp | no | `startTimeUnixNano`, else receive time |
| `end_time` | timestamp | yes | `endTimeUnixNano` |
| `duration_nano` | long | no | `endTimeUnixNano - startTimeUnixNano`, else 0 |
| `service_name` | string | no | resource attribute `service.name`, else empty |
| `trace_id` | string | no | `traceId` (lowercase hex) |
| `span_id` | string | no | `spanId` (lowercase hex) |
| `parent_span_id` | string | no | `parentSpanId` (lowercase hex) |
| `trace_state` | string | no | `traceState` |
| `span_name` | string | no | `name` |
| `span_kind` | string | no | `kind` as its OTLP enum name (e.g. `SPAN_KIND_SERVER`) |
| `status_code` | string | no | `status.code` as its OTLP enum name |
| `status_message` | string | no | `status.message` |
| `resource_attributes` | map | no | resource `attributes` |
| `scope_name` | string | no | instrumentation scope `name` |
| `scope_version` | string | no | instrumentation scope `version` |
| `scope_attributes` | map | no | instrumentation scope `attributes` |
| `span_attributes` | map | no | span `attributes` |
| `events` | string | no | the span's `events` array as compact JSON text (`[]` when absent) |
| `links` | string | no | the span's `links` array as compact JSON text (`[]` when absent) |
| `dropped_attributes_count` | integer | no | span `droppedAttributesCount` |

**`otel_<sink>_metrics`** — one row per data point; ordering key
`(service_name, metric_name, event_time)`, partitioned by `event_time` (`day`):

| Column | Type | Nullable | Source |
|---|---|---|---|
| `event_time` | timestamp | no | data point `timeUnixNano`, else receive time |
| `start_time` | timestamp | yes | data point `startTimeUnixNano` |
| `service_name` | string | no | resource attribute `service.name`, else empty |
| `metric_name` | string | no | metric `name` |
| `metric_description` | string | no | metric `description` |
| `metric_unit` | string | no | metric `unit` |
| `metric_type` | string | no | one of `gauge`, `sum`, `histogram`, `exponential_histogram`, `summary` |
| `aggregation_temporality` | string | no | the OTLP enum name, empty for a gauge or summary |
| `is_monotonic` | boolean | no | `sum.isMonotonic`, else false |
| `resource_attributes` | map | no | resource `attributes` |
| `scope_name` | string | no | instrumentation scope `name` |
| `scope_version` | string | no | instrumentation scope `version` |
| `scope_attributes` | map | no | instrumentation scope `attributes` |
| `point_attributes` | map | no | data point `attributes` — **sensitive** |
| `point_value` | decimal | yes | gauge/sum `asDouble` or `asInt`; null for other types |
| `point_count` | long | yes | histogram/summary `count` |
| `point_sum` | decimal | yes | histogram/summary `sum` |
| `point_min` | decimal | yes | histogram `min` |
| `point_max` | decimal | yes | histogram `max` |
| `bucket_counts` | array of long | no | histogram `bucketCounts` |
| `explicit_bounds` | array of decimal | no | histogram `explicitBounds` |
| `exp_scale` | integer | yes | exponential histogram `scale` |
| `exp_zero_count` | long | yes | exponential histogram `zeroCount` |
| `exp_positive_offset` | integer | yes | exponential histogram `positive.offset` |
| `exp_positive_bucket_counts` | array of long | no | exponential histogram `positive.bucketCounts` |
| `exp_negative_offset` | integer | yes | exponential histogram `negative.offset` |
| `exp_negative_bucket_counts` | array of long | no | exponential histogram `negative.bucketCounts` |
| `quantiles` | string | no | summary `quantileValues` as compact JSON text (`[]` when absent) |

Metric exemplars SHALL NOT be stored.

**`point_attributes` on the metrics table SHALL be declared `sensitive`.** OTLP data-point attributes
are producer-defined and routinely carry end-user identity — the Claude Code metrics feed puts
`user.email`, `user.id` and `user.account_id` there alongside `model`, `effort` and `query_source` —
so the column SHALL resolve only for `FULL_ADMIN`, through the same per-column read exposure every
other sensitive column uses. The declaration SHALL apply to newly created sinks; a sink materialized
before this requirement retains its stored column metadata until that metadata is updated, since a
sink's schema is applied once at creation and is not reconciled at startup.

Sensitivity SHALL be whole-column, as it is everywhere else: a client that cannot read
`point_attributes` cannot read any key of it, including the non-identifying ones. The accepted
consequence is that aggregate reporting over data-point attributes requires `FULL_ADMIN`. Per-key
sensitivity is not modelled.

`resource_attributes` and `scope_attributes` SHALL NOT be declared sensitive: they describe the
producing resource and instrumentation scope (host, OS, service name and version), not the end user.

#### Scenario: Metrics point attributes are declared sensitive

- **WHEN** a metrics sink is created and `GET /v1/tables/{name}` is called for its metrics table
- **THEN** `point_attributes` carries `sensitive: true`, while `resource_attributes` and
  `scope_attributes` carry `sensitive: false`

#### Scenario: Point attributes are hidden from a non-full-admin reader

- **WHEN** a principal that is not `FULL_ADMIN` lists the metrics entity's schema, or queries it
- **THEN** `point_attributes` is not exposed, and a query naming it — including one calling `map_value`
  over it — is refused by the same per-column rule that governs every other sensitive column

#### Scenario: Logs table exposes the OTLP columns

- **WHEN** a sink with the `logs` signal is created and `GET /v1/tables/otel_app_logs` is read
- **THEN** it reports the columns above with those declared types, `resource_attributes`,
  `scope_attributes` and `log_attributes` typed `map`

#### Scenario: Every landing column is selectable by its bare name

- **WHEN** a SQL statement selects every column of a landing table, unquoted, through
  `POST /v1/queries/execute-sql`
- **THEN** the statement is accepted — no column name is a reserved word the caller would have to quote

#### Scenario: Histogram data point populates the histogram columns

- **WHEN** a metrics payload carrying one explicit-bucket histogram data point is exported
- **THEN** the written row has `metric_type` `histogram`, a null `point_value`, and populated
  `point_count`, `point_sum`, `bucket_counts` and `explicit_bounds`
