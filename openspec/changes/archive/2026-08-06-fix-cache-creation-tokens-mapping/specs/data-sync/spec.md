## ADDED Requirements

### Requirement: Reported cache write tokens are persisted

The service SHALL persist a producer-reported cache-write token count (nested under `token_usage` as
the provider's prompt-tokens-details cache-write count) to the `cache_creation_tokens` column, and
SHALL NOT store `0` for a record that reports a non-zero count. When the producer does not report the
count, the column SHALL be NULL per the nullable-column absent/blank fallback, so "not reported" stays
distinguishable from a reported zero.

The guarantee SHALL hold independently of the response's transport and upstream API shape — a streamed
response, whose body and merged response are an SSE transcript rather than a JSON document, SHALL
persist the count exactly as a non-streamed one does. Deriving the value from the response payload
cannot meet this, since an SSE transcript does not parse as JSON and normalized/native payloads spell
the count differently; the count is therefore read from the record's own `token_usage` accounting,
which the producer populates for every shape.

This is a behavioral guarantee about the ingested value; the specific JSON source path remains an
implementation detail of the (overridable) default column mapping and is not pinned here.

#### Scenario: Reported cache-write token count is stored

- **WHEN** a record's `token_usage` reports a cache-write token count of N (including 0)
- **THEN** the ingested row's `cache_creation_tokens` equals N

#### Scenario: Streamed response reports a cache write

- **WHEN** a record for a streamed response carries an SSE transcript in `response.body` and
  `assembled_response`, and its `token_usage` reports a cache-write token count of N
- **THEN** the ingested row's `cache_creation_tokens` equals N, not `0`

#### Scenario: Absent cache-write token count is null

- **WHEN** a record's `token_usage` does not report a cache-write token count (including when it
  carries no prompt-tokens-details object at all)
- **THEN** the ingested row's `cache_creation_tokens` is NULL

## MODIFIED Requirements

### Requirement: Target table provisioning and schema

By default the service SHALL provision the target ClickHouse table (`dial_usage_log`) on startup by
running Flyway migrations under `classpath:db/migration` over the ClickHouse JDBC driver, using the
writer credential (which must therefore additionally hold `CREATE TABLE` grants). Provisioning SHALL be
gated by `clickhouse.migrations.enabled` (default true) and SHALL run before any sync run or startup
schema validation; a migration failure SHALL abort startup. When migrations are disabled, the service
SHALL issue no DDL and the operator SHALL provision the table out of band by applying the same migration
DDL, in version order: the `CREATE TABLE`
(`src/main/resources/db/migration/clickhouse/V1__create_dial_usage_log.sql`) followed by every later
migration that alters it. An operator who skips a later migration SHALL be stopped by startup schema
validation rather than ingesting against a stale table.

Whether applied by Flyway or by an operator, the table definition SHALL declare the `events` column set:
a synthetic `event_id UUID` and an `event_kind` classifier; the direct columns carried from the source
(timestamps, ids, `user_hash`, tokens — including the producer's reported cache-write count in
`cache_creation_tokens` — prices, request/response bodies, etc.); the calculated columns
(`response_id`, `reasoning_tokens`, `number_request_messages`, `success`, `mcp_method`,
`mcp_tool_call_name`, `request_body_bytes`, `response_body_bytes`); the JSON columns
`jwt_claims` and `request_tags`, populated from the record's `claims` and `headers` objects; and the
blocked column (`operation_duration_ms`), which exists in the table but is not yet populated. The two
JSON columns use ClickHouse's `JSON` type, which is generally available on the supported server version
(ClickHouse **25.3 LTS or newer**) and SHALL require no experimental enablement setting; the body
columns use the `AES_256_GCM_SIV` codec, which requires a server-side encryption key or `CREATE TABLE`
fails. For record-level deduplication to function, the table SHALL use the `ReplacingMergeTree` engine
versioned by `_ingested_at`, with a sort key (`ORDER BY`) ending in the deduplication key columns
`trace_id` and `core_span_id`, partitioned by `toYYYYMMDD(request_time)`. The definition SHALL also pin
UTC on its timestamp columns, store monetary columns as `Decimal`, store `response_status` as `UInt16`,
declare the producer-optional columns as `Nullable` — including every producer-reported token count —
and apply a row-level retention `TTL`.

#### Scenario: Service provisions the table on startup

- **WHEN** migrations are enabled (the default) and the service starts
- **THEN** Flyway applies the `db/migration` DDL with the writer credential, creating `dial_usage_log`
  if it does not exist, before any sync run or schema validation

#### Scenario: JSON columns provision without an experimental flag

- **WHEN** the table is provisioned on a supported server version (ClickHouse 25.3 LTS or newer) with
  no `allow_experimental_json_type` setting configured
- **THEN** the `CREATE TABLE` for the `jwt_claims`/`request_tags` `JSON` columns succeeds, because the
  `JSON` type is generally available on that version

#### Scenario: Migration failure aborts startup

- **WHEN** a startup migration fails (e.g. the writer credential lacks `CREATE TABLE` or a required
  server-side feature is unavailable)
- **THEN** the service fails to start rather than proceeding to run sync against an unprovisioned table

#### Scenario: Migrations disabled — operator provisions out of band

- **WHEN** `clickhouse.migrations.enabled` is false
- **THEN** the service issues no DDL and inserts into the table the operator provisioned out of band
  from the same migration DDL

#### Scenario: Operator misses a later migration

- **WHEN** `clickhouse.migrations.enabled` is false and the operator provisioned the table from the
  `CREATE TABLE` alone, omitting a later migration that alters a column's nullability
- **THEN** startup schema validation fails on the mismatch between the mapping and the live column,
  and the service does not start

#### Scenario: Table definition supports record-level dedup

- **WHEN** the table is provisioned (by Flyway or by an operator) from the migration DDL
- **THEN** the table is a `ReplacingMergeTree` whose sort key ends in `trace_id` and `core_span_id`, so
  re-ingested rows for the same span collapse on merge

#### Scenario: Blocked column exists but is unpopulated

- **WHEN** the table is provisioned and ingestion runs
- **THEN** the `operation_duration_ms` column exists in the table and is left at its column default,
  because no mapping entry populates it

### Requirement: Closed named deriver registry

The service SHALL support a fixed, named set of **derivers** — derivation functions that compute a
column value from one or more source nodes of the parsed record — referenced by columns in the
mapping, and SHALL NOT support arbitrary user-supplied expressions or code. A derived column entry
SHALL declare a `derive` name, an ordered non-empty `sources` list of dotted JSON paths, and MAY
declare an `args` map of static parameters; it MUST NOT also declare a `source`. The registry SHALL
include derivers sufficient to populate the calculated `events` columns: a deterministic UUID hash
over its concatenated sources (for `event_id`); a request-URI classifier (for `event_kind`); JSON
sub-document field extractors that re-parse a source value that is itself a JSON string (or read it
directly when already an object) and read a dotted `args.path` as a string or as an unsigned integer
(for `response_id`, `reasoning_tokens`, `mcp_method`, `mcp_tool_call_name`);
a JSON array length (for `number_request_messages`); a byte length of a source string (for
`request_body_bytes`, `response_body_bytes`); an HTTP-success predicate over `response.status`
(for `success`); and a **rate normalizer** that reads a JSON `rate` value at `args.path` and
normalizes it to a signed integer (for `rate_analytics.rate`) — a numeric value passes through as its
integer value, boolean `true` maps to `1` and `false` maps to `0`, and an absent or blank value yields
null (subject to the column's nullability fallback). A column referencing a deriver name that is not
in the registry, or a derived column missing its `sources` list, SHALL cause a fatal configuration
error at load time.

A JSON-extraction deriver reads only sources that parse as a JSON document; a source that does not
(such as an SSE transcript) SHALL be skipped as if absent. A value the producer also reports outside
the response payload SHALL therefore be mapped from that report rather than derived from the payload,
so that its column does not depend on the response being a parseable document.

#### Scenario: Derived column is computed by its named deriver

- **WHEN** a column declares a `derive` name with a `sources` list (and optional `args`)
- **THEN** the inserted value is computed by the named deriver from the resolved source nodes, then
  subjected to the same absent/blank fallback (default, else null if nullable, else the kind's zero
  value) as a mapped column

#### Scenario: event_id is deterministic across re-ingests

- **WHEN** two records share the same `trace_id` and `core_span_id`
- **THEN** the configured `event_id` deriver produces the identical UUID for both, so it is a stable
  proxy for the natural `(trace_id, core_span_id)` deduplication key

#### Scenario: Embedded-JSON extraction reads a nested field

- **WHEN** a JSON-extraction deriver is configured with ordered `sources` and an `args.path`, and the
  first source whose value is present parses as a JSON document
- **THEN** the deriver returns that document's value at `args.path`, falling through to the next source
  when an earlier source is absent or lacks the path

#### Scenario: Unparseable source is skipped

- **WHEN** every source of a JSON-extraction deriver holds text that is not a JSON document (such as an
  SSE event stream)
- **THEN** the deriver yields no value and the column takes its absent/blank fallback, rather than the
  ingest failing

#### Scenario: rate normalizer maps booleans and numbers

- **WHEN** the rate-normalizer deriver reads a `rate` value at its `args.path` that is `true`, `false`,
  or a number
- **THEN** it yields `1`, `0`, or the number's integer value respectively, and null when the value is
  absent or blank

#### Scenario: Unknown or malformed deriver entry is rejected

- **WHEN** a column references a deriver name that is not in the registry, or declares `derive` without
  a non-empty `sources` list, or declares both `source` and `derive`
- **THEN** the service fails to load the mapping with a configuration error
