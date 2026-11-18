## ADDED Requirements

### Requirement: Reported spending limits are persisted

The service SHALL persist the spending-limits check a producer reports for a request, verbatim, in five columns of
`dial_usage_log`: `spending_limits_mode`, `budget_cost_object`, `budget_outcome`, `budget_policy` and
`budget_assignment`. It SHALL NOT reject a mode or an outcome it does not know.

#### Scenario: A request paid by an assigned budget

- **WHEN** a record reports `spending_limits` with `mode` `enforce`, `cost_object` `project-a`, `outcome` `paid`,
  `policy` `engineering-pro` and `assignment` `project-a/a1`
- **THEN** the ingested row's `spending_limits_mode` is `enforce`, its `budget_cost_object` is `project-a`, its
  `budget_outcome` is `paid`, its `budget_policy` is `engineering-pro` and its `budget_assignment` is `project-a/a1`

#### Scenario: An unrecognized outcome

- **WHEN** a record reports an `outcome` other than `paid`, `no_budget` or `exhausted`
- **THEN** the ingested row stores that value as reported, and the record is not rejected

### Requirement: Absent spending-limit fields read NULL

All five spending-limit columns are `Nullable`. A record that carries no spending-limits object, which the producer
omits on every row but a token caller's model call whose limits it checked (an API key caller, a row that is not
a model's, a pass whose limits it does not check), SHALL store NULL in all five
and SHALL be ingested normally. A record that reports some fields SHALL store those and NULL for the rest, and a
blank value SHALL be stored as NULL, like an absent one.

#### Scenario: A request paid by a policy that applies by itself

- **WHEN** a record reports `spending_limits` with `cost_object` `ORG`, `outcome` `paid` and `policy`
  `ORG/personal` and no `assignment`
- **THEN** the ingested row's `budget_policy` is `ORG/personal` and its `budget_assignment` is NULL

#### Scenario: Every budget used up in shadow mode

- **WHEN** a record reports `spending_limits` with `mode` `shadow`, `cost_object` `project-a` and `outcome`
  `exhausted`
- **THEN** the ingested row's `budget_outcome` is `exhausted`, and its `budget_policy` and `budget_assignment` are NULL

#### Scenario: Spending limits off

- **WHEN** a record reports `spending_limits` with `mode` `off` only
- **THEN** the ingested row's `spending_limits_mode` is `off` and the other four columns are NULL

#### Scenario: A request budgets did not apply to

- **WHEN** a record carries no `spending_limits`
- **THEN** the ingested row's five spending-limit columns are NULL, and the row is ingested normally

#### Scenario: A blank field reads as absent

- **WHEN** a record reports `spending_limits` with an empty `policy`
- **THEN** the ingested row's `budget_policy` is NULL

#### Scenario: Rows ingested before the columns existed

- **WHEN** the spending-limit columns are added to a table that already holds rows
- **THEN** those rows read NULL in the five columns until their source blobs are re-ingested

### Requirement: The budget cost object is the producer's

`budget_cost_object` SHALL hold the cost object the producer reports and SHALL NOT be derived from `project_id`. The
two can differ: when the caller's token carries no project, `project_id` is empty while the producer charges a
default cost object.

#### Scenario: The cost object is not taken from the project

- **WHEN** a record reports an empty `project.id` and `spending_limits` with `cost_object` `ORG`
- **THEN** the ingested row's `project_id` is empty and its `budget_cost_object` is `ORG`


## MODIFIED Requirements

### Requirement: Target tables are provisioned by versioned ClickHouse migrations

By default the service SHALL provision the target ClickHouse tables (`dial_usage_log` and
`dial_usage_log_payload`) and the ingest ledger table (`_dial_internal_ingested_blob`) on startup by running Flyway migrations under `classpath:db/migration` over
the ClickHouse JDBC driver, using the writer credential (which must therefore additionally hold
`CREATE TABLE` grants, and `ALTER DROP COLUMN` on `dial_usage_log`). Provisioning SHALL be gated by
`clickhouse.migrations.enabled` (default true) and SHALL run before any sync run or startup schema
validation; a migration failure SHALL abort startup.

The migrations SHALL provision **replicated** tables in the session database, which is a `Replicated` database
(see the `clickhouse-replication` spec). Every table a migration creates SHALL use the `Replicated` counterpart
of the engine the migration declares (`MergeTree` → `ReplicatedMergeTree`, `ReplacingMergeTree(v)` →
`ReplicatedReplacingMergeTree(v)`), with no Keeper path or replica arguments, and SHALL set
`replicated_deduplication_window = 0` and `replicated_deduplication_window_for_async_inserts = 0`.

Applied migrations are checksummed, so their files SHALL NOT be edited to achieve this. Instead the service SHALL
act immediately before Flyway applies a pending migration that declares
`CREATE TABLE IF NOT EXISTS <table> … ENGINE = <engine>`. At that point the service SHALL create `<table>` itself
from that statement, with the engine replaced by its `Replicated` counterpart and the two deduplication settings
added to the statement's own settings. The migration's statement then finds the table and does nothing. The
Flyway history table SHALL likewise be created as a `ReplicatedMergeTree` with the same settings before Flyway
first reads it, so every replica sees one migration history. It SHALL be pre-created only into a database that is
empty or already holds it: a database holding other tables but no history table keeps Flyway's own refusal to
migrate a non-empty schema, instead of replaying every migration over tables provisioned some other way.

A migration written after this requirement SHALL declare its `Replicated*MergeTree` engine and the two
deduplication settings itself. The migrations SHALL run once for the database: a replica that joins later
receives every table, and their current definitions, from the `Replicated` database rather than by running
migrations.

When migrations are disabled, the service SHALL issue no DDL and the operator SHALL provision the tables out of
band by applying the replicated form of the same migration DDL, in version order: the `CREATE TABLE`
(`src/main/resources/db/migration/clickhouse/V1__create_dial_usage_log.sql`) followed by every later
migration that alters it or creates a sibling table. An operator who skips a later migration SHALL be
stopped by startup schema validation rather than ingesting against a stale table. The ingest ledger table is
not an ingest target, but startup schema validation SHALL cover it as well, so a deployment that skipped its
migration does not start.

Whether applied by Flyway or by an operator, the `dial_usage_log` definition SHALL declare the `events`
column set: a synthetic `event_id UUID` and an `event_kind` classifier; the direct columns carried from
the source (timestamps, ids, `user_hash`, tokens — including the producer's reported cache-write count
in `cache_creation_tokens` — prices, the producer's reported `operation_duration_ms`, the producer's
reported route in `route_name` and `route_type`, the producer's reported spending-limits check in
`spending_limits_mode`, `budget_cost_object`, `budget_outcome`, `budget_policy` and `budget_assignment`, etc.); and the calculated columns (`response_id`,
`reasoning_tokens`, `number_request_messages`, `success`, `mcp_method`, `mcp_tool_call_name`,
`request_body_bytes`, `response_body_bytes`). It SHALL NOT declare the five verbatim-content columns — the three bodies and the two JSON documents. They were created by
`V1`/`V7`, moved to `dial_usage_log_payload` by `V10` and the mapping cutover, and dropped from
`dial_usage_log` by `V11`; the migration set as a whole therefore leaves them on the payload table
only. The two `*_bytes` columns SHALL survive that drop, because they size a payload without holding
one and must remain answerable past the payload table's retention.

On `dial_usage_log_payload`, the JSON columns use ClickHouse's `JSON` type, which is generally
available across the supported server range (ClickHouse **25.3 LTS or newer**, verified through
**26.8 LTS**) and SHALL require no experimental enablement setting; the body columns use the
`AES_256_GCM_SIV` codec, which requires a server-side encryption key or `CREATE TABLE` fails. That key
SHALL also be required to provision `dial_usage_log` from scratch, because `V1` creates its body
columns under the same codec before `V11` drops them. For record-level deduplication to function,
`dial_usage_log` SHALL use the `ReplicatedReplacingMergeTree` engine versioned by `_ingested_at`, with a sort key
(`ORDER BY`) ending in the deduplication key columns `trace_id` and `core_span_id`, partitioned by
`toYYYYMMDD(request_time)`. The definition SHALL also pin UTC on its timestamp columns, store monetary
columns as `Decimal`, store `response_status` as `UInt16`, declare the producer-optional columns as
`Nullable` — including every producer-reported token count, the two route columns and the five spending-limit columns — and apply a
row-level retention `TTL`.

Each table SHALL leave exactly one column to a table-side `DEFAULT` and populate every other column its
mapping declares: `_ingested_at` on `dial_usage_log`, `_updated_at` on `dial_usage_log_payload`.

#### Scenario: Service provisions the table on startup

- **WHEN** migrations are enabled (the default) and the service starts
- **THEN** Flyway applies the `db/migration` DDL with the writer credential, creating `dial_usage_log`,
  `dial_usage_log_payload` and `_dial_internal_ingested_blob` if they do not exist, before any sync run or schema
  validation
- **AND** each of them is a `Replicated*MergeTree` table with insert deduplication disabled

#### Scenario: JSON columns provision without an experimental flag

- **WHEN** the table is provisioned on a supported server version (ClickHouse 25.3 LTS or newer,
  verified through 26.8 LTS) with no `allow_experimental_json_type` setting configured
- **THEN** the `CREATE TABLE` for the `jwt_claims`/`request_tags` `JSON` columns succeeds, because the
  `JSON` type is generally available on that version

#### Scenario: Migration failure aborts startup

- **WHEN** a startup migration fails (e.g. the writer credential lacks `CREATE TABLE` or a required
  server-side feature is unavailable)
- **THEN** the service fails to start rather than proceeding to run sync against an unprovisioned table

#### Scenario: Migrations disabled — operator provisions out of band

- **WHEN** `clickhouse.migrations.enabled` is false
- **THEN** the service issues no DDL and inserts into the tables the operator provisioned out of band
  from the replicated form of the same migration DDL

#### Scenario: Operator misses a later migration

- **WHEN** `clickhouse.migrations.enabled` is false and the operator provisioned `dial_usage_log` from
  the `CREATE TABLE` alone, omitting the later migration that creates `dial_usage_log_payload`
- **THEN** startup schema validation fails, because the payload mapping cannot be reconciled against a
  table that does not exist, and the service does not start

#### Scenario: Table definition supports record-level dedup

- **WHEN** the tables are provisioned (by Flyway or by an operator) from the migration DDL
- **THEN** `dial_usage_log` is a `ReplicatedReplacingMergeTree` whose sort key ends in `trace_id` and
  `core_span_id`, and `dial_usage_log_payload` is a `ReplicatedReplacingMergeTree` ordered by `event_id`, so
  re-ingested rows for the same span collapse on merge in both

#### Scenario: Every user column is populated

- **WHEN** the tables are provisioned and ingestion runs with the shipped mappings
- **THEN** every column of each table that its mapping declares is populated by that mapping, and the
  only columns left to a value the mapping did not supply are the two table-side defaults
  (`_ingested_at`, `_updated_at`)

#### Scenario: Operator misses the ledger migration

- **WHEN** `clickhouse.migrations.enabled` is false, schema validation is enabled, and the operator
  provisioned every ingest table but not `_dial_internal_ingested_blob`
- **THEN** startup schema validation fails and the service does not start

#### Scenario: A pending CREATE migration provisions a replicated table

- **GIVEN** an empty `Replicated` session database
- **WHEN** the service starts and Flyway reaches a pending migration that declares
  `CREATE TABLE IF NOT EXISTS _dial_internal_ingested_blob … ENGINE = ReplacingMergeTree(ingested_at)`
- **THEN** `_dial_internal_ingested_blob` exists as `ReplicatedReplacingMergeTree(ingested_at)` with insert
  deduplication disabled, and the migration is recorded as applied with its unchanged checksum

#### Scenario: Every replica sees one migration history

- **GIVEN** a `Replicated` database with two replicas, migrated through the first
- **WHEN** the service later starts with `clickhouse.host` pointing at the second replica
- **THEN** Flyway finds every migration already applied and runs none of them again

#### Scenario: A replica that joins later needs no migration run

- **GIVEN** a migrated `Replicated` database with one replica
- **WHEN** a second replica joins the database
- **THEN** it holds every provisioned table, at its current definition, without the service running a migration
