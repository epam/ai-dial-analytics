## ADDED Requirements

### Requirement: Authentication claims and request headers are captured

The service SHALL populate the `jwt_claims` column from the record's top-level `claims` object and the
`request_tags` column from the record's top-level `headers` object, storing each as a JSON object that
preserves the source subtree verbatim. `request_tags` SHALL capture the headers as emitted by the
producer without any allowlist or denylist filtering in this service; excluding transport/SDK-noise
headers is a producer-side (DIAL Core) responsibility. When the corresponding source object is absent
from the record, the column SHALL store the empty JSON object (the ClickHouse `JSON` column's default),
because these columns are not nullable.

#### Scenario: JWT claims are captured verbatim

- **WHEN** a record carries a top-level `claims` object
- **THEN** the ingested row's `jwt_claims` is a JSON object equal to that `claims` subtree

#### Scenario: Request headers are captured verbatim

- **WHEN** a record carries a top-level `headers` object
- **THEN** the ingested row's `request_tags` is a JSON object equal to that `headers` subtree, with no
  headers dropped or filtered by the service

#### Scenario: Absent claims or headers store an empty object

- **WHEN** a record has no top-level `claims` (or no `headers`) object
- **THEN** the corresponding column stores the empty JSON object `{}`

### Requirement: Object extraction kind for JSON columns

The mapping engine SHALL provide an **object** extraction kind that resolves a source JSON object node
and carries it through unchanged as the column value, so it serializes into a ClickHouse `JSON` column.
For a column of this kind whose source is absent or blank, the service SHALL apply the standard
absent/blank fallback: the configured default if present; otherwise `null` if the column is declared
`nullable`; otherwise the kind's zero value, which for the object kind SHALL be the empty JSON object.

#### Scenario: Object source is carried through to a JSON column

- **WHEN** a column declares the object kind over a source path that resolves to a JSON object
- **THEN** the inserted value is that object, serialized as JSON into the target `JSON` column

#### Scenario: Object kind reconciles with a JSON table column

- **WHEN** a column declared with the object kind is validated against a table column of ClickHouse
  type `JSON`
- **THEN** startup schema validation treats the types as compatible

## MODIFIED Requirements

### Requirement: Target table provisioning and schema

By default the service SHALL provision the target ClickHouse table (`dial_usage_log`) on startup by
running Flyway migrations under `classpath:db/migration` over the ClickHouse JDBC driver, using the
writer credential (which must therefore additionally hold `CREATE TABLE` grants). Provisioning SHALL be
gated by `clickhouse.migrations.enabled` (default true) and SHALL run before any sync run or startup
schema validation; a migration failure SHALL abort startup. When migrations are disabled, the service
SHALL issue no DDL and the operator SHALL provision the table out of band by applying the same migration
DDL (`src/main/resources/db/migration/clickhouse/V1__create_dial_usage_log.sql`).

Whether applied by Flyway or by an operator, the table definition SHALL declare the `events` column set:
a synthetic `event_id UUID` and an `event_kind` classifier; the direct columns carried from the source
(timestamps, ids, `user_hash`, tokens, prices, request/response bodies, etc.); the calculated columns
(`response_id`, `cache_creation_tokens`, `reasoning_tokens`, `number_request_messages`, `success`,
`mcp_method`, `mcp_tool_call_name`, `request_body_bytes`, `response_body_bytes`); the JSON columns
`jwt_claims` and `request_tags`, populated from the record's `claims` and `headers` objects; and the
blocked column (`operation_duration_ms`), which exists in the table but is not yet populated. The two
JSON columns use ClickHouse's `JSON` type, which is generally available on the supported server version
(ClickHouse **25.3 LTS or newer**) and SHALL require no experimental enablement setting; the body
columns use the `AES_256_GCM_SIV` codec, which requires a server-side encryption key or `CREATE TABLE`
fails. For record-level deduplication to function, the table SHALL use the `ReplacingMergeTree` engine
versioned by `_ingested_at`, with a sort key (`ORDER BY`) ending in the deduplication key columns
`trace_id` and `core_span_id`, partitioned by `toYYYYMMDD(request_time)`. The definition SHALL also pin
UTC on its timestamp columns, store monetary columns as `Decimal`, store `response_status` as `UInt16`,
declare the producer-optional columns as `Nullable`, and apply a row-level retention `TTL`.

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

#### Scenario: Table definition supports record-level dedup

- **WHEN** the table is provisioned (by Flyway or by an operator) from the migration DDL
- **THEN** the table is a `ReplacingMergeTree` whose sort key ends in `trace_id` and `core_span_id`, so
  re-ingested rows for the same span collapse on merge

#### Scenario: Blocked column exists but is unpopulated

- **WHEN** the table is provisioned and ingestion runs
- **THEN** the `operation_duration_ms` column exists in the table and is left at its column default,
  because no mapping entry populates it

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
Flyway migration step. Table columns that the mapping does not populate — including columns with a
table-side default (e.g. `_ingested_at`) and the not-yet-populated blocked column
(`operation_duration_ms`) — MAY be absent from the mapping.

#### Scenario: Matching mapping starts cleanly

- **WHEN** every configured column exists in the target table with a compatible type and matching
  nullability
- **THEN** the service starts and the scheduled sync job is allowed to run

#### Scenario: Mismatched mapping prevents startup

- **WHEN** a configured column is missing from the target table, its kind is incompatible with the
  table column's type, or its declared nullability disagrees with the table column
- **THEN** the service fails to start and reports the offending column(s)

#### Scenario: Unmapped blocked column is permitted

- **WHEN** the table declares the blocked column (`operation_duration_ms`) and the mapping does not
  declare it
- **THEN** reconciliation succeeds, because the mapping need not populate every table column
