## ADDED Requirements

### Requirement: Supported ClickHouse server version

The service SHALL run against ClickHouse **25.3 LTS or newer** and SHALL be verified through
**26.3 LTS**.

The floor is 25.3 because that is where the service's only hard server requirement sits — the `JSON`
type used by `jwt_claims`/`request_tags` becoming generally available. Nothing in the service requires
26.x, so the floor SHALL NOT be raised merely because a newer line has been verified. The upper bound
names the newest version the ClickHouse-backed integration suite is run against; a deployment inside
the range SHALL require no service configuration change, and no migration, to move between versions.

The service SHALL NOT inspect or gate on the server version. There is no version check and none is to
be added: a server that lacks a feature the service needs SHALL fail at that feature — a `CREATE TABLE`
that cannot be provisioned aborts startup by the existing migration-failure path — rather than at a
version comparison, which would refuse a working server for a version string.

Where a server default changes inside the supported range, the service SHALL pin the setting when a
shipped guarantee depends on its value, and SHALL otherwise follow the server rather than tracking
ClickHouse defaults. `async_insert` — which the server defaults to `1` from 26.2 — SHALL be pinned to
`0` on every client that inserts rows, because the deduplication version columns
(`dial_usage_log._ingested_at`, and `_updated_at` on a catalog table) are server-evaluated
`DEFAULT now64(3)` values that no client supplies, and the async buffer evaluates a column default once
per flush rather than once per insert. Writes coalesced into one flush would therefore share a single
version stamp, leaving `ReplacingMergeTree` no ordering to collapse by, whereas one insert per request
orders duplicate writes of a key by the millisecond they arrived in. The ordering the pin restores is
that of the version column's own resolution and no finer: writes of the same key inside one millisecond
tie whether or not the buffer is in play, and on a tie the surviving row is unspecified — so
`table-management`'s version contract holds per writer at millisecond granularity, not for arbitrarily
concurrent writers. Pinning has no effect on a server older than 26.2, so ingest semantics SHALL be
identical across the supported range. `INSERT … SELECT` and DDL are not routed through the buffer and
SHALL need no pin.

The documentation SHALL state the rollback constraint: a part written by 25.11 or newer cannot be read
by a server older than 25.10, and a `JSON` column written by 25.12 or newer cannot be read below 25.12,
so an operator who must preserve the ability to roll back SHALL set the server's `compatibility`
profile — naming the version to roll back to — before starting the newer version. These are MergeTree
settings rather than query settings, so the service cannot set them per statement, and nothing can
retrofit a part already written; that is why it is a documented operator obligation and not a runtime
check. The documentation SHALL also state that the profile reverts every server default changed after
the version it names, not only the part format, so that the operator sees what else the choice costs.

#### Scenario: Provisioning and querying on the newest verified version

- **WHEN** the service starts against ClickHouse 26.3 LTS with migrations enabled and a server-side
  encryption key configured
- **THEN** Flyway provisions `dial_usage_log` — including the `AES_256_GCM_SIV` body columns and the
  `jwt_claims`/`request_tags` `JSON` columns — and the query, ingest and enrichment paths serve
  requests, with no service configuration that names a server version

#### Scenario: Provisioning and querying on the floor version

- **WHEN** the service starts against ClickHouse 25.3 LTS
- **THEN** provisioning and the query, ingest and enrichment paths return the same results, because the
  supported contract is a range and not a single pinned version; what the range does carry is
  server-side planning differences that change cost rather than answers — the server does not consult
  skip indexes under `FINAL` before 25.6 — and the floor is not covered by the integration suite, which
  runs against the newest verified version only

#### Scenario: No version gate refuses a working server

- **WHEN** the service starts against any ClickHouse server in the supported range
- **THEN** the service performs no server-version comparison, and a server missing a required feature
  fails at the DDL or query that needs it rather than at a version check

#### Scenario: A row insert is one server-side insert whatever the server default

- **WHEN** the server defaults `async_insert` to `1` and the service inserts a `JSONEachRow` batch
- **THEN** the batch is not routed through the server's async-insert buffer, so it carries its own
  evaluation of the target's version-column default rather than sharing one with every other batch in
  the same flush window, and a re-ingested batch written a millisecond or more later collapses to the
  newer version

#### Scenario: The rollback constraint is documented for operators

- **WHEN** an operator reads the ClickHouse schema-migration configuration reference before moving a
  deployment onto 26.x
- **THEN** it states that a part written by 25.11 or newer cannot be read below 25.10 (25.12 for the
  `JSON` columns), that the `compatibility` profile must name the rollback target and be set before the
  upgrade, what else that profile reverts, and that staging through an intermediate LTS does not avoid
  the constraint

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
two JSON columns use ClickHouse's `JSON` type, which is generally available across the supported server
range (ClickHouse **25.3 LTS or newer**, verified through **26.3 LTS**) and SHALL require no experimental
enablement setting; the body columns use the `AES_256_GCM_SIV` codec, which requires a server-side
encryption key or `CREATE TABLE` fails. For record-level deduplication to function, the table SHALL use
the `ReplacingMergeTree` engine versioned by `_ingested_at`, with a sort key (`ORDER BY`) ending in the
deduplication key columns `trace_id` and `core_span_id`, partitioned by `toYYYYMMDD(request_time)`. The
definition SHALL also pin UTC on its timestamp columns, store monetary columns as `Decimal`, store
`response_status` as `UInt16`, declare the producer-optional columns as `Nullable` — including every
producer-reported token count — and apply a row-level retention `TTL`.

`_ingested_at` (a table-side `DEFAULT`) SHALL be the only column of this table that the shipped mapping
leaves unpopulated.

#### Scenario: Service provisions the table on startup

- **WHEN** migrations are enabled (the default) and the service starts
- **THEN** Flyway applies the `db/migration` DDL with the writer credential, creating `dial_usage_log`
  if it does not exist, before any sync run or schema validation

#### Scenario: JSON columns provision without an experimental flag

- **WHEN** the table is provisioned on a supported server version (ClickHouse 25.3 LTS or newer,
  verified through 26.3 LTS) with no `allow_experimental_json_type` setting configured
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
