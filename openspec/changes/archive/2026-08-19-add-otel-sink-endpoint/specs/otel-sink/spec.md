## Purpose

This capability lets an operator provision a named **OTLP sink** — a landing zone for OpenTelemetry
logs, traces and metrics — and exposes it as an OTLP/HTTP endpoint that any OpenTelemetry exporter can
be pointed at. Provisioning a sink creates one ordinary catalog source table per opted-in signal, so
everything the sink ingests is immediately discoverable and queryable through the same governed read
path as every other ADAS table.

## ADDED Requirements

### Requirement: OTLP sink catalog

The system SHALL maintain a Postgres-backed catalog of OTLP sinks. A sink SHALL carry a `name` (its
key), an optional `description`, a `created_at` timestamp, and one or more **signals**, each signal
being one of `logs`, `traces`, `metrics`. Each enabled signal SHALL record the catalog table that backs
it, named `otel_<sink_name>_<signal>`. A sink's set of signals SHALL be fixed at creation: it is not
mutable, and a sink whose signal set must change SHALL be deleted and recreated.

A sink `name` SHALL obey the catalog identifier grammar (snake_case `^[a-z][a-z0-9_]*$`, no leading
`_`) and SHALL be at most 51 characters, so that the longest derived table name
(`otel_<name>_metrics`) stays within the 64-character catalog identifier limit.

#### Scenario: Sink records its signals and their tables

- **WHEN** a sink `app` is created with signals `logs` and `traces`
- **THEN** the sink catalog holds `app` with exactly two signals, backed by `otel_app_logs` and
  `otel_app_traces`, and no `otel_app_metrics` table exists

#### Scenario: Sink name too long for a derived table name is rejected

- **WHEN** `POST /v1/otel/sinks` names a sink of 52 or more characters
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error`, and no sink and no
  table are created

### Requirement: Create an OTLP sink

`POST /v1/otel/sinks` SHALL create a sink and materialize one catalog **source** table per opted-in
signal. The request body SHALL carry `name` (required), `signals` (required, a non-empty array of
distinct values drawn from `logs`, `traces`, `metrics`), an optional `description`, and an optional
`retention_days` (an integer ≥ 1). The endpoint SHALL require `FULL_ADMIN`.

Every rule about that body — a missing or ungrammatical `name`, an empty, duplicated or unrecognised
`signals` entry, a `retention_days` below 1 — SHALL be reported as HTTP 422 with `error`
`validation_error`, the same status the rest of the catalog surface uses, rather than as a request-binding
400.

Each created table SHALL be an ordinary, non-`system` catalog source with `write` discipline `append`,
the fixed per-signal schema defined by "OTLP landing table schemas", a `PARTITION BY` its `event_time`
column at `day` granularity, and — when a retention is in effect — a row-expiry TTL of that many days on
`event_time`. The retention in effect SHALL be `retention_days` when supplied, otherwise the configured
default retention; a configured default of `0` with no `retention_days` SHALL create the tables with no
TTL.

Creation SHALL be all-or-nothing as observed through the API: if any signal's table cannot be created or
materialized, the system SHALL remove the sink and every table it created for that request before
returning the failure, so a failed create leaves neither a sink nor a partial set of tables.

On success the endpoint SHALL return HTTP 201 and the sink view: an object carrying `name`,
`description`, `created_at`, `endpoint` (the sink's OTLP base path `/v1/otel/sink/<name>`), and
`signals` — an array of objects, one per enabled signal, each carrying `signal`, `table` and the backing
table's `status`.

#### Scenario: Sink with all three signals is created

- **WHEN** `POST /v1/otel/sinks` requests `{"name": "app", "signals": ["logs", "traces", "metrics"]}`
- **THEN** the response is HTTP 201 carrying `endpoint` `/v1/otel/sink/app` and three signal entries,
  and `otel_app_logs`, `otel_app_traces` and `otel_app_metrics` each exist as an `ACTIVE` source table
  listed by `GET /v1/tables`

#### Scenario: Single-signal sink creates only that table

- **WHEN** `POST /v1/otel/sinks` requests `{"name": "m", "signals": ["metrics"]}`
- **THEN** only `otel_m_metrics` is created, and `GET /v1/otel/sinks/m` reports exactly one signal

#### Scenario: Empty or unknown signal list is rejected

- **WHEN** `POST /v1/otel/sinks` requests an empty `signals` array, a duplicate entry, or a value other
  than `logs`/`traces`/`metrics`
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error`, and nothing is created

#### Scenario: Blank name is rejected as a validation error

- **WHEN** `POST /v1/otel/sinks` omits `name` or sends it blank
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error`, not HTTP 400

#### Scenario: Duplicate sink name is rejected

- **WHEN** `POST /v1/otel/sinks` names a sink that already exists
- **THEN** the request is rejected with HTTP 409 and `error` `conflict`

#### Scenario: Derived table name already taken is rejected

- **WHEN** `POST /v1/otel/sinks` would derive a table name that a catalog table already uses
- **THEN** the request is rejected with HTTP 409 and `error` `conflict`, and no sink and no table are
  created

#### Scenario: Non-admin cannot create a sink

- **WHEN** a caller without `FULL_ADMIN` calls `POST /v1/otel/sinks` in `oidc` mode
- **THEN** the request is rejected with HTTP 403 and `error` `forbidden`

### Requirement: Read, list and delete OTLP sinks

`GET /v1/otel/sinks` SHALL return `{"sinks": [...]}` — every sink as a sink view, ordered by name.
`GET /v1/otel/sinks/{name}` SHALL return one sink view, or HTTP 404 with `error` `not_found` when no such
sink exists. Both reads SHALL require an application role, like every other catalog read.

`DELETE /v1/otel/sinks/{name}` SHALL require `FULL_ADMIN`, SHALL drop every catalog table backing the
sink's signals (dropping the ClickHouse table and removing the catalog rows, exactly as
`DELETE /v1/tables/{name}` does), SHALL remove the sink, and SHALL return HTTP 204. A signal whose
backing table is already absent from the catalog SHALL be skipped rather than failing the delete.
Deleting an unknown sink SHALL return HTTP 404 with `error` `not_found`.

#### Scenario: Deleting a sink drops its tables

- **WHEN** `DELETE /v1/otel/sinks/app` is called for a sink with `logs` and `metrics`
- **THEN** the response is HTTP 204, `GET /v1/otel/sinks/app` answers 404, and neither `otel_app_logs`
  nor `otel_app_metrics` is listed by `GET /v1/tables`

#### Scenario: Reading an unknown sink

- **WHEN** `GET /v1/otel/sinks/nope` is called and no such sink exists
- **THEN** the response is HTTP 404 with `error` `not_found`

### Requirement: OTLP/HTTP JSON ingest endpoints

A sink SHALL expose the OTLP/HTTP data plane rooted at `/v1/otel/sink/{sink_name}`, so that an
OpenTelemetry exporter configured with that base URL reaches it by appending the standard signal paths:

- `POST /v1/otel/sink/{sink_name}/v1/logs`
- `POST /v1/otel/sink/{sink_name}/v1/traces`
- `POST /v1/otel/sink/{sink_name}/v1/metrics`

Each endpoint SHALL accept an OTLP `Export<Signal>ServiceRequest` encoded as **OTLP/HTTP JSON**
(`Content-Type: application/json`), decode it into rows of the signal's landing table per "OTLP payload
mapping", and insert them through the same governed row-write path as `POST /v1/tables/{name}/rows`
(writer credential, physical column names).

A successful export SHALL return HTTP 200 with the body `{"partial_success": {}}` — an empty
`partial_success` being the OTLP encoding of "everything was accepted". Partial success SHALL NOT be
reported: a payload is accepted whole or rejected whole.

An export whose payload carries no data points at all (no resource entries, or resource entries with no
records) SHALL be accepted as a no-op with HTTP 200 and the same body.

The endpoints SHALL require authentication at the URL layer, and SHALL additionally require the caller to
hold the `write` permission on the signal's backing table — the same per-table role-based grant that
governs `POST /v1/tables/{name}/rows`, so a `FULL_ADMIN` or a caller with a matching table `write` role
may export and no other caller may.

#### Scenario: Logs export lands rows

- **WHEN** an OTLP/HTTP JSON `ExportLogsServiceRequest` carrying two log records is posted to
  `/v1/otel/sink/app/v1/logs` for a sink with the `logs` signal
- **THEN** the response is HTTP 200 with `{"partial_success": {}}` and `otel_app_logs` holds two rows

#### Scenario: Signal not enabled on the sink

- **WHEN** a payload is posted to `/v1/otel/sink/app/v1/metrics` for a sink that enabled only `logs`
- **THEN** the response is HTTP 404 with `error` `not_found`

#### Scenario: Unknown sink

- **WHEN** a payload is posted to `/v1/otel/sink/nope/v1/logs` and no such sink exists
- **THEN** the response is HTTP 404 with `error` `not_found`

#### Scenario: Empty export is a no-op

- **WHEN** `{"resourceLogs": []}` is posted to `/v1/otel/sink/app/v1/logs`
- **THEN** the response is HTTP 200 with `{"partial_success": {}}` and no row is written

#### Scenario: Caller without a write grant is refused

- **WHEN** an authenticated caller with neither `FULL_ADMIN` nor a `write` role on `otel_app_logs` posts
  to `/v1/otel/sink/app/v1/logs` in `oidc` mode
- **THEN** the response is HTTP 403 with `error` `forbidden` and no row is written

### Requirement: OTLP ingest error contract

The ingest endpoints SHALL report failures using the standard `ErrorView` contract rather than the OTLP
`Status` message, with these codes:

| Condition | Status | `error` |
|---|---|---|
| `Content-Type` other than `application/json` (including `application/x-protobuf`) | 415 | `unsupported_media_type` |
| Body is not valid JSON | 400 | `bad_request` |
| Payload flattens to more rows than the configured per-request cap | 422 | `validation_error` |
| Unknown sink, or a signal the sink did not enable | 404 | `not_found` |
| The signal's backing table is missing from the catalog or is not `ACTIVE` | 409 | `conflict` |
| ClickHouse cannot be reached | 503 | `clickhouse_unavailable` |
| The OTLP surface is disabled by configuration | 503 | `otel_disabled` |

`HttpMediaTypeNotSupportedException` SHALL map to HTTP 415 with `error` `unsupported_media_type` for
every endpoint, not only the OTLP ones.

An OTLP field carrying a value of the wrong JSON shape SHALL NOT fail the export and SHALL NOT be
reported as a server error: it is read as absent, per "Malformed values" in "OTLP payload mapping".

#### Scenario: Protobuf payload is refused

- **WHEN** a payload is posted to `/v1/otel/sink/app/v1/traces` with `Content-Type: application/x-protobuf`
- **THEN** the response is HTTP 415 with `error` `unsupported_media_type` and no row is written

#### Scenario: Malformed JSON is refused

- **WHEN** a body that is not valid JSON is posted to `/v1/otel/sink/app/v1/logs`
- **THEN** the response is HTTP 400 with `error` `bad_request`

#### Scenario: Oversized payload is refused whole

- **WHEN** a payload flattening to more rows than the configured per-request cap is posted
- **THEN** the response is HTTP 422 with `error` `validation_error` and no row is written

#### Scenario: Backing table was deleted underneath the sink

- **WHEN** `otel_app_logs` has been deleted through `DELETE /v1/tables/otel_app_logs` and a payload is
  posted to `/v1/otel/sink/app/v1/logs`
- **THEN** the response is HTTP 409 with `error` `conflict`

### Requirement: OTLP surface can be disabled

The system SHALL expose configuration that disables the whole OTLP surface. When disabled, every
`/v1/otel/**` endpoint — control plane and data plane alike — SHALL answer HTTP 503 with `error`
`otel_disabled` and SHALL neither create nor write anything. The surface SHALL be enabled by default,
including when the property is absent.

#### Scenario: Disabled surface refuses ingest

- **WHEN** the OTLP surface is disabled and a payload is posted to `/v1/otel/sink/app/v1/logs`
- **THEN** the response is HTTP 503 with `error` `otel_disabled` and no row is written

#### Scenario: Disabled surface refuses provisioning

- **WHEN** the OTLP surface is disabled and `POST /v1/otel/sinks` is called
- **THEN** the response is HTTP 503 with `error` `otel_disabled` and no sink is created

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
| `point_attributes` | map | no | data point `attributes` |
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

### Requirement: OTLP payload mapping

The system SHALL flatten an OTLP export into rows as follows.

**Fan-out.** One row per log record, per span, and per metric data point. Resource attributes and
instrumentation-scope identity/attributes SHALL be copied onto every row derived from them.

**Field names.** Both the OTLP JSON lowerCamelCase spelling (`timeUnixNano`) and the underlying proto
field name (`time_unix_nano`) SHALL be accepted for every field.

**Timestamps.** OTLP nanosecond timestamps SHALL be accepted as either a JSON string or a JSON number and
converted to millisecond precision by truncation. A timestamp that is absent or zero SHALL be treated as
absent.

**Identifiers.** `traceId`, `spanId` and `parentSpanId` SHALL be accepted as case-insensitive hex strings
and stored lowercase; an absent identifier stores the empty string.

**AnyValue rendering.** Every attribute value, and a log record's `body`, SHALL be rendered to a string:
a `stringValue` as-is; a `boolValue` as `true`/`false`; an `intValue` as its decimal digits; a
`doubleValue` as its shortest round-tripping decimal form (`NaN`, `Infinity`, `-Infinity` for the
non-finite values); a `bytesValue` as the base64 text as received; an `arrayValue` or `kvlistValue` as
compact JSON of the rendered structure; an absent or empty value as the empty string.

**Attribute maps.** A key repeated within one attribute list SHALL keep the last occurrence.

**Non-finite numbers.** A `decimal`-typed metric field whose OTLP value is `NaN` or infinite SHALL be
stored as null rather than rejected.

**Enums.** An OTLP enum field (a log record's `severityNumber`, `span.kind`, `span.status.code`, a
metric shape's `aggregationTemporality`) SHALL be accepted in either proto3 JSON encoding — the enum name
(`"SPAN_KIND_SERVER"`, `"SEVERITY_NUMBER_INFO"`) or its integer value (`2`, `9`). An enum landing in a
string column SHALL be stored as the enum name, and one landing in an integer column (`severity_number`)
as the enum number. A value that matches no known member SHALL be stored as received when the column is a
string, so an enum member added by a newer OTLP version still lands, and as `0` — the enum's
"unspecified" member — when the column is an integer.

**Unknown fields.** A field the mapping does not recognise SHALL be ignored, so a payload from a newer
OTLP version is still accepted.

**Malformed values.** A field carrying a value of the wrong JSON shape (an object or array where a scalar
belongs, a fractional or out-of-range number where a 64-bit integer belongs) SHALL be read as absent — the
empty string, a null number, or the column's zero — and SHALL NOT fail the record, the export, or the
request. A producer's shape mistake is never reported as HTTP 500.

#### Scenario: Attributes land as a map

- **WHEN** a log record carries the attributes `{"http.method": "GET"}` and the resource carries
  `{"service.name": "api", "host.id": 7}`
- **THEN** the row's `log_attributes` is `{"http.method": "GET"}`, its `resource_attributes` is
  `{"service.name": "api", "host.id": "7"}`, and its `service_name` is `api`

#### Scenario: Structured attribute value is rendered as JSON

- **WHEN** an attribute value is `{"arrayValue": {"values": [{"stringValue": "a"}, {"intValue": "2"}]}}`
- **THEN** the map entry's value is the text `["a","2"]`

#### Scenario: Both field spellings are accepted

- **WHEN** a log record uses `time_unix_nano` instead of `timeUnixNano`
- **THEN** the row's `event_time` carries that instant

#### Scenario: Non-finite metric value is stored as null

- **WHEN** a gauge data point carries `asDouble` of `NaN`
- **THEN** the row is written with a null `point_value` and the export succeeds

#### Scenario: Numerically encoded enum lands as its name

- **WHEN** a span carries `"kind": 2` and `"status": {"code": 2}`, and a sum carries
  `"aggregationTemporality": 2`
- **THEN** the rows carry `span_kind` `SPAN_KIND_SERVER`, `status_code` `STATUS_CODE_ERROR` and
  `aggregation_temporality` `AGGREGATION_TEMPORALITY_CUMULATIVE`

#### Scenario: Name-encoded severity lands as its number

- **WHEN** a log record carries `"severityNumber": "SEVERITY_NUMBER_ERROR"` — proto3 JSON's default enum
  encoding — instead of `17`
- **THEN** the row's `severity_number` is `17`, so an "errors only" filter on the column still matches

#### Scenario: Wrongly shaped field does not fail the export

- **WHEN** a log record carries `"severityText": {}` and `"traceId": []`
- **THEN** the export succeeds and the row lands with an empty `severity_text` and `trace_id`

#### Scenario: One row per data point

- **WHEN** a metrics payload carries one metric with three data points
- **THEN** three rows are written, each carrying the same metric identity and its own `point_attributes`

### Requirement: Sink tables are ordinary catalog tables

A sink's landing tables SHALL be ordinary catalog source tables: they are listed by `GET /v1/tables`,
are queryable through `/v1/queries`, `POST /v1/queries/execute-sql` and Flight SQL, accept per-table
access grants, and are not marked `system`. The OTLP control plane SHALL be the intended way to manage
them, but the table endpoints SHALL NOT be blocked from operating on them; a sink whose backing table has
been removed through `/v1/tables` SHALL report that signal's `status` as absent in the sink view and
SHALL refuse ingest for it with HTTP 409 `conflict`.

#### Scenario: Sink table is queryable

- **WHEN** rows have been exported to `otel_app_logs` and a structured query selects
  `service_name` and `log_attributes` from it
- **THEN** the query returns those rows, with `log_attributes` rendered as a JSON object

#### Scenario: Sink view reports a removed backing table

- **WHEN** `otel_app_logs` is deleted through `/v1/tables` and `GET /v1/otel/sinks/app` is read
- **THEN** the `logs` signal entry reports its table as absent, and the other signals are unaffected
