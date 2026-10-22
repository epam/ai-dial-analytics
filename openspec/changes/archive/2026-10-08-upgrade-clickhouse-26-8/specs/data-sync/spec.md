## MODIFIED Requirements

### Requirement: Supported ClickHouse server version

The service SHALL run against ClickHouse **25.3 LTS or newer** and SHALL be verified through
**26.8 LTS**.

The floor is 25.3 because that is where the service's only hard server requirement sits — the `JSON`
type used by `jwt_claims`/`request_tags` becoming generally available. Nothing in the service requires
26.x, so the floor SHALL NOT be raised merely because a newer line has been verified. The upper bound
names the newest version the ClickHouse-backed integration suite is run against; a deployment inside
the range SHALL require no service configuration change, and no migration, to move between versions.

An optional capability MAY require a server newer than the floor without raising it, provided the
capability is off by default and its own spec names the version it needs, so that a deployment on the
floor loses nothing it has not turned on. The AI query functions are the one such capability: they
render onto ClickHouse's native AI functions, which the server ships from **26.8** and not at all on
26.3, so enabling them requires 26.8 while every other surface keeps the 25.3 floor.

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

The documentation SHALL also state the softer constraint 26.8 adds: from 26.8 the server writes a part's
skip indexes into one packed file (`packed_skip_index_max_bytes`, `0` before 26.8), which an older server
reads past rather than refuses, so after a rollback to 26.3 every index on a part written by 26.8 is
inert until that part is re-merged — a cost in read work, not in data. It SHALL name the two ways to keep
the classic layout while on 26.8 — the `compatibility` profile, or `packed_skip_index_max_bytes = 0` in
the server's `merge_tree` configuration — and SHALL warn against carrying that setting in a table's own
definition, which the older server cannot load. No serialization-format default moved between 26.3 and
26.8, so the 25.10 / 25.12 floors above are the only data-level doors in the supported range.

#### Scenario: Provisioning and querying on the newest verified version

- **WHEN** the service starts against ClickHouse 26.8 LTS with migrations enabled and a server-side
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

#### Scenario: An optional capability above the floor costs the floor nothing

- **WHEN** the service starts against ClickHouse 26.3 with the AI query functions left disabled
- **THEN** every other surface works as on 26.8, and nothing the service runs on that server refers to
  an AI function; enabling the capability is what requires 26.8

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

- **WHEN** an operator reads the ClickHouse server requirements before moving a deployment onto 26.x
- **THEN** it states that a part written by 25.11 or newer cannot be read below 25.10 (25.12 for the
  `JSON` columns), that the `compatibility` profile must name the rollback target and be set before the
  upgrade, what else that profile reverts, and that staging through an intermediate LTS does not avoid
  the constraint

#### Scenario: The packed-skip-index rollback cost is documented for operators

- **WHEN** an operator reads the ClickHouse server requirements before moving a deployment from 26.3 to
  26.8
- **THEN** it states that 26.3 reads parts written by 26.8 but ignores their packed skip indexes until the
  parts are re-merged, names the `compatibility` profile and the server-level
  `packed_skip_index_max_bytes = 0` as the two ways to keep the classic layout, and warns that the setting
  must not be written into a table definition that the older server would have to load

### Requirement: Target tables are provisioned by versioned ClickHouse migrations

By default the service SHALL provision the target ClickHouse tables (`dial_usage_log` and
`dial_usage_log_payload`) and the ingest ledger table (`_dial_internal_ingested_blob`) on startup by running Flyway migrations under `classpath:db/migration` over
the ClickHouse JDBC driver, using the writer credential (which must therefore additionally hold
`CREATE TABLE` grants, and `ALTER DROP COLUMN` on `dial_usage_log`). Provisioning SHALL be gated by
`clickhouse.migrations.enabled` (default true) and SHALL run before any sync run or startup schema
validation; a migration failure SHALL abort startup. When migrations are disabled, the service SHALL
issue no DDL and the operator SHALL provision the tables out of band by applying the same migration
DDL, in version order: the `CREATE TABLE`
(`src/main/resources/db/migration/clickhouse/V1__create_dial_usage_log.sql`) followed by every later
migration that alters it or creates a sibling table. An operator who skips a later migration SHALL be
stopped by startup schema validation rather than ingesting against a stale table. The ingest ledger table is
not an ingest target, but startup schema validation SHALL cover it as well, so a deployment that skipped its
migration does not start.

Whether applied by Flyway or by an operator, the `dial_usage_log` definition SHALL declare the `events`
column set: a synthetic `event_id UUID` and an `event_kind` classifier; the direct columns carried from
the source (timestamps, ids, `user_hash`, tokens — including the producer's reported cache-write count
in `cache_creation_tokens` — prices, the producer's reported `operation_duration_ms`, etc.); and the
calculated columns (`response_id`, `reasoning_tokens`, `number_request_messages`, `success`,
`mcp_method`, `mcp_tool_call_name`, `request_body_bytes`, `response_body_bytes`). It SHALL NOT declare
the five verbatim-content columns — the three bodies and the two JSON documents. They were created by
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
`dial_usage_log` SHALL use the `ReplacingMergeTree` engine versioned by `_ingested_at`, with a sort key
(`ORDER BY`) ending in the deduplication key columns `trace_id` and `core_span_id`, partitioned by
`toYYYYMMDD(request_time)`. The definition SHALL also pin UTC on its timestamp columns, store monetary
columns as `Decimal`, store `response_status` as `UInt16`, declare the producer-optional columns as
`Nullable` — including every producer-reported token count — and apply a row-level retention `TTL`.

Each table SHALL leave exactly one column to a table-side `DEFAULT` and populate every other column its
mapping declares: `_ingested_at` on `dial_usage_log`, `_updated_at` on `dial_usage_log_payload`.

#### Scenario: Service provisions the table on startup

- **WHEN** migrations are enabled (the default) and the service starts
- **THEN** Flyway applies the `db/migration` DDL with the writer credential, creating `dial_usage_log`,
  `dial_usage_log_payload` and `_dial_internal_ingested_blob` if they do not exist, before any sync run or schema
  validation

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
  from the same migration DDL

#### Scenario: Operator misses a later migration

- **WHEN** `clickhouse.migrations.enabled` is false and the operator provisioned `dial_usage_log` from
  the `CREATE TABLE` alone, omitting the later migration that creates `dial_usage_log_payload`
- **THEN** startup schema validation fails, because the payload mapping cannot be reconciled against a
  table that does not exist, and the service does not start

#### Scenario: Table definition supports record-level dedup

- **WHEN** the tables are provisioned (by Flyway or by an operator) from the migration DDL
- **THEN** `dial_usage_log` is a `ReplacingMergeTree` whose sort key ends in `trace_id` and
  `core_span_id`, and `dial_usage_log_payload` is a `ReplacingMergeTree` ordered by `event_id`, so
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
