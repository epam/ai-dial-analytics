## ADDED Requirements

### Requirement: Reported operation duration is persisted

The service SHALL persist a producer-reported operation duration, in milliseconds, to the
`operation_duration_ms` column of `dial_usage_log`, and SHALL NOT store `0` for a record that reports a
non-zero duration. The value SHALL be stored as reported, without re-derivation from the record's
timestamps: the producer measures the interval it alone can see (from accepting the request to having the
full response body for a live request; from accepting it to emitting the log entry for a background job,
which includes the producer's own detection lag), and a duration recomputed here from `request.time` and
ingest time would describe a different interval.

A reported duration of `0` SHALL be stored as `0`, because the producer clamps the measurement at zero and
an operation rejected before any upstream work legitimately completes inside a millisecond.

The column is not nullable. When a record carries no duration — which for this field means it was produced
by a DIAL Core older than the release that introduced it, since the producer emits it unconditionally
otherwise — the column SHALL take the non-nullable absent/blank fallback and store `0`. "Not reported" is
therefore indistinguishable from a measured zero on this column, by decision: the distinction identifies a
producer version rather than a property of the request, and the correction is to upgrade the producer.

This is a behavioral guarantee about the ingested value; the specific JSON source path remains an
implementation detail of the (overridable) default column mapping and is not pinned here.

#### Scenario: Reported operation duration is stored

- **WHEN** a record reports an operation duration of N milliseconds
- **THEN** the ingested row's `operation_duration_ms` equals N

#### Scenario: A reported zero duration stays zero

- **WHEN** a record reports an operation duration of `0`
- **THEN** the ingested row's `operation_duration_ms` is `0`, not treated as an absent value

#### Scenario: A record from a producer that does not report the duration

- **WHEN** a record carries no operation duration field
- **THEN** the ingested row's `operation_duration_ms` is `0` (the non-nullable fallback), and the record is
  ingested normally rather than dropped

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
`cache_creation_tokens` — prices, the producer's reported `operation_duration_ms`, request/response
bodies, etc.); the calculated columns (`response_id`, `reasoning_tokens`, `number_request_messages`,
`success`, `mcp_method`, `mcp_tool_call_name`, `request_body_bytes`, `response_body_bytes`); and the JSON
columns `jwt_claims` and `request_tags`, populated from the record's `claims` and `headers` objects. The
two JSON columns use ClickHouse's `JSON` type, which is generally available on the supported server
version (ClickHouse **25.3 LTS or newer**) and SHALL require no experimental enablement setting; the body
columns use the `AES_256_GCM_SIV` codec, which requires a server-side encryption key or `CREATE TABLE`
fails. For record-level deduplication to function, the table SHALL use the `ReplacingMergeTree` engine
versioned by `_ingested_at`, with a sort key (`ORDER BY`) ending in the deduplication key columns
`trace_id` and `core_span_id`, partitioned by `toYYYYMMDD(request_time)`. The definition SHALL also pin
UTC on its timestamp columns, store monetary columns as `Decimal`, store `response_status` as `UInt16`,
declare the producer-optional columns as `Nullable` — including every producer-reported token count —
and apply a row-level retention `TTL`.

`_ingested_at` (a table-side `DEFAULT`) SHALL be the only column of this table that the shipped mapping
leaves unpopulated.

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

#### Scenario: Every user column is populated

- **WHEN** the table is provisioned and ingestion runs with the shipped mapping
- **THEN** every column except `_ingested_at` is written by the mapping, so no user column of the table
  can read as a type default that no producer value stands behind

### Requirement: Fail-fast startup validation against the table schema

On startup, before any sync run can execute, the service SHALL introspect the target table's actual
columns and types (via `DESCRIBE TABLE`) and reconcile them with the configured mapping. Every
configured column MUST exist in the table, and each configured extraction kind MUST be compatible with
the table column's declared type, normalizing `Nullable(...)`, `LowCardinality(...)`, `Decimal`,
unsigned-integer, `UUID`, `Bool`, `JSON`, and timezone-qualified `DateTime` spellings before comparison.
A column declared `nullable` in the mapping MUST be backed by a `Nullable(...)` table column, and a
column not declared nullable MUST NOT be. If reconciliation fails, the service SHALL refuse to start
and SHALL report the offending column(s). Schema validation itself SHALL issue no DDL — it only
introspects via `DESCRIBE TABLE`; table provisioning, when enabled, has already run in the earlier
Flyway migration step. Table columns that the mapping does not populate — a column with a table-side
default (e.g. `_ingested_at`), or any column an operator's overridden mapping chooses to omit — MAY be
absent from the mapping.

#### Scenario: Matching mapping starts cleanly

- **WHEN** every configured column exists in the target table with a compatible type and matching
  nullability
- **THEN** the service starts and the scheduled sync job is allowed to run

#### Scenario: Mismatched mapping prevents startup

- **WHEN** a configured column is missing from the target table, its kind is incompatible with the
  table column's type, or its declared nullability disagrees with the table column
- **THEN** the service fails to start and reports the offending column(s)

#### Scenario: Unmapped table-default column is permitted

- **WHEN** the table declares a column populated by a table-side default (`_ingested_at`) and the
  mapping does not declare it
- **THEN** reconciliation succeeds, because the mapping need not populate every table column
