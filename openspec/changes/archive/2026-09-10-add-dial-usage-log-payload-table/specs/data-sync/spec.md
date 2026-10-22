## ADDED Requirements

### Requirement: Body columns are ingested into a separate retention-scoped table

The three unbounded body columns — `request_body`, `response_body`, `assembled_response` — SHALL be
persisted to their own ClickHouse table, `dial_usage_log_payload`, and SHALL NOT be persisted to
`dial_usage_log`.

The table SHALL be provisioned by a versioned ClickHouse migration (never by the `/v1/tables` API) and
SHALL declare:

- the grain column `event_id UUID`, which is also its sole `ORDER BY` column and therefore its unique
  key;
- `request_time`, which drives both `PARTITION BY toYYYYMMDD(request_time)` and the retention `TTL`;
- `trace_id` and `core_span_id`, so the deduplication-key drop rule is decidable on a payload row;
- the three body columns as `Nullable(String)` under the `AES_256_GCM_SIV` codec (which requires a
  server-side encryption key, or `CREATE TABLE` fails);
- the claim and header documents `jwt_claims` and `request_tags` as `JSON`, carried across verbatim
  from `dial_usage_log` — same type, no codec. They are necessarily NON-nullable, because ClickHouse
  has no `Nullable(JSON)`;
- the `ReplacingMergeTree` engine versioned by a table-side `_updated_at` default, so a re-ingested
  record replaces its predecessor for the same `event_id` in the same daily partition.

`_updated_at` SHALL be the only column of this table the shipped mapping leaves unpopulated.

One parsed record SHALL produce a `dial_usage_log` row **and** a `dial_usage_log_payload` row, from two
mappings evaluated over the same record under the same match-all predicate, carrying the same
`event_id`. Because both targets apply the same deduplication-key drop rule to the same record, the two
rows SHALL be either both emitted or both dropped — so an ingested `dial_usage_log` row SHALL never
lack its payload row for a reason internal to ingest.

`jwt_claims` and `request_tags` SHALL move with the bodies. They are small, so storage is not the
argument; they can carry bearer tokens, cookies and forwarded client IPs, so disclosure is. Because
they are non-nullable, an unmatched grain join renders them as the empty document rather than as
`NULL` — which the `enrichment-runtime` capability's nullability rule permits for `object` columns
specifically, since no accessor can distinguish `{}` from a present document lacking the key.

#### Scenario: One record produces a row in both tables

- **WHEN** a run ingests a record carrying `request.body`, `response.body`, `assembled_response`,
  `claims` and `headers`
- **THEN** `dial_usage_log` receives a row with the analytic columns, and `dial_usage_log_payload`
  receives a row keyed on the same `event_id` carrying all five verbatim values

#### Scenario: The payload row keys on the parent's event id

- **WHEN** a payload row is written for a record
- **THEN** its `event_id` is the same deterministic hash of (`trace_id`, `core_span_id`) that the
  record's `dial_usage_log` row carries, so the two rows join one-to-one

#### Scenario: A re-ingested record replaces its payload row

- **WHEN** the same record is ingested more than once
- **THEN** a deduplicated read of `dial_usage_log_payload` returns a single row for that `event_id`,
  the latest-written copy

#### Scenario: A record without a dedup key is dropped from both tables

- **WHEN** a record's `trace_id` or `core_span_id` is absent or blank
- **THEN** neither a `dial_usage_log` row nor a `dial_usage_log_payload` row is inserted for it, and the
  drop is logged and counted for each target independently

#### Scenario: The body columns are absent from dial_usage_log

- **WHEN** the shipped mapping ingests a record
- **THEN** the inserted `dial_usage_log` row carries no `request_body`, `response_body`,
  `assembled_response`, `jwt_claims` or `request_tags` column, because the table no longer declares
  them

#### Scenario: The claim and header documents move with the bodies

- **WHEN** a record carrying `claims` and `headers` is ingested
- **THEN** the `dial_usage_log_payload` row carries `jwt_claims` and `request_tags`, and the
  `dial_usage_log` row carries neither

### Requirement: The payload table carries a shorter retention than its parent

`dial_usage_log_payload` SHALL declare `TTL toDateTime(request_time) + INTERVAL 90 DAY` together with
`ttl_only_drop_parts = 1`, while `dial_usage_log` retains its one-year `TTL`. Retention SHALL be
declared in the table's own `CREATE TABLE` so that Flyway and an out-of-band operator provision it
identically.

The payload retention SHALL NOT be configurable through the service: it is neither an environment
property nor a `/v1/tables` field, and an operator changes it with
`ALTER TABLE dial_usage_log_payload MODIFY TTL …`. The catalog SHALL NOT mirror it.

Expiry SHALL be quantized to a daily part exactly as the parent's is: a part is dropped once its newest
row has expired, so a payload row MAY outlive its `TTL` by at most the rest of its day.

A payload row SHALL expire independently of the `dial_usage_log` row it belongs to. A query naming a
body column for an event older than the payload retention SHALL return the parent's columns with the
body columns `NULL` — the outcome of a grain `LEFT JOIN` that finds no row — and SHALL NOT fail.

#### Scenario: Provisioned payload table declares the shorter retention

- **WHEN** `dial_usage_log_payload` is provisioned (by Flyway or by an operator applying the migrations
  in version order)
- **THEN** its declaration carries `TTL toDateTime(request_time) + INTERVAL 90 DAY` and
  `ttl_only_drop_parts = 1`, while `dial_usage_log` still carries its one-year `TTL`

#### Scenario: An expired payload leaves its parent row intact

- **WHEN** a day's payload partition expires and is dropped
- **THEN** the corresponding `dial_usage_log` rows remain, with every analytic column intact —
  including `request_body_bytes` and `response_body_bytes`, which outlive the bodies they measure

#### Scenario: Querying a body column past its retention yields null

- **WHEN** a query names a body column for an event whose payload partition has been dropped
- **THEN** the row is returned with that column `NULL`, and the query does not fail

### Requirement: The existing bodies are copied before the columns are dropped

The migration sequence SHALL be: create `dial_usage_log_payload`, copy into it, then drop the three
columns from `dial_usage_log`. All three SHALL be versioned ClickHouse migrations, so an installation
running with `clickhouse.migrations.enabled=false` applies the same three in the same order.

The copy SHALL be bounded to the payload table's retention window, expressed on `request_time`.
Copying rows older than that window would write rows the `TTL` immediately expires, so bodies older
than the window SHALL NOT be copied and SHALL be deleted with the columns — which is what imposing the
shorter retention means.

The copy SHALL preserve each row's own `request_time` rather than stamping the copy's: that column
drives the target's partitioning and its `TTL`, so restamping would restart the retention from the
migration date and collapse the daily partitions into one.

The copy SHALL be **idempotent**. Because the target collapses on `event_id` and reads run
deduplicated, re-running it SHALL insert rows that merge away rather than duplicating content. This is
required rather than incidental: ClickHouse offers the migration no transaction, so an interrupted
copy leaves rows behind and the recovery is to run it again.

Between the copy and the drop the body content SHALL exist in both tables; the drop SHALL be what
releases the duplicate. Operators SHALL be told the size of that transient duplicate and that the two
steps are not meant to be separated by a long interval.

#### Scenario: The retention window survives the drop

- **WHEN** the migrations run against a `dial_usage_log` holding bodies inside and outside the payload
  retention window
- **THEN** `dial_usage_log_payload` holds a row for each in-window event, carrying that event's own
  `request_time`, and the columns no longer exist on `dial_usage_log`

#### Scenario: Content older than the window is not carried over

- **WHEN** a `dial_usage_log` row older than the payload retention is present when the migrations run
- **THEN** no payload row is created for it, and its bodies are removed with the dropped columns

#### Scenario: Re-running the copy does not duplicate content

- **WHEN** the copy is applied a second time — the documented recovery from an interrupted run
- **THEN** a deduplicated read of `dial_usage_log_payload` still returns one row per `event_id`, and no
  value changes

#### Scenario: A fresh install copies nothing

- **WHEN** the migrations run against an empty `dial_usage_log`
- **THEN** the copy completes as a no-op and startup proceeds

## MODIFIED Requirements

### Requirement: The merged assembled response is persisted

The service SHALL persist the producer's merged streaming response, `assembled_response`, to a column
of `dial_usage_log_payload` when the configured mapping declares one, storing the producer's value
verbatim with no reassembly, parsing, or normalization of its own. The column SHALL be nullable, so a
record that carries no `assembled_response` stores `NULL` rather than an empty string and "the producer
sent nothing" stays distinguishable from "the producer sent an empty response".

The expected value is a **merged JSON response message**, for a streamed and a non-streamed call
alike — that is what makes the column readable as an answer where `response.body` on a streamed call is
a raw chunk transcript. The service SHALL nonetheless store the value as reported **whatever its
shape**, and SHALL NOT validate, parse, or reject it on shape: deciding what arrived would mean parsing
a payload the service otherwise never reads, and a row rejected or emptied on that basis would report
"no response" for a request that had one.

A stored value that does not parse as JSON therefore SHALL NOT be treated as an ingest failure, and a
consumer that needs a message SHALL parse the column defensively. A minority of records — including
current ones, not only historical ones — carry a value that does not parse, some of them an SSE
transcript in place of the merged message. The persisted shape is the producer's contract, not this
service's: ADAS neither guarantees JSON nor repairs a payload that is not, so a consumer that assumes
JSON will fail on that minority rather than on nothing.

Persisting the merged response SHALL NOT change how it feeds derived columns: it remains available as
a deriver source for the `dial_usage_log` mapping, and a column derived from it is unaffected by
whether it is also stored, or by which table stores it. `response_id` and `reasoning_tokens` therefore
remain columns of `dial_usage_log` while their source is stored on `dial_usage_log_payload`, because a
deriver reads the parsed record rather than a stored column.

#### Scenario: The assembled response is stored when the mapping declares it

- **WHEN** a record carries `assembled_response` and the payload mapping declares the corresponding
  column
- **THEN** the inserted `dial_usage_log_payload` row stores the producer's value verbatim

#### Scenario: A record without an assembled response stores null

- **WHEN** a record carries no `assembled_response` and the mapping declares the column as nullable
- **THEN** the inserted row stores `NULL` for it, and the record is ingested normally

#### Scenario: A value that is not JSON is stored unchanged

- **WHEN** a record's `assembled_response` is not a JSON document (for example an SSE transcript)
- **THEN** the inserted row stores that value verbatim, and ingest neither fails, skips, nor empties the
  column

#### Scenario: Deriving from the assembled response is unchanged

- **WHEN** a `dial_usage_log` column derives its value from `assembled_response` and the payload mapping
  also declares `assembled_response` as a stored column
- **THEN** the derived column's value is exactly what it would be were the column not stored, and it is
  unaffected by the payload column living in a different table

### Requirement: Persisted columns are governed by the mapping (allowlist)

The service SHALL persist only the columns declared in the configured mapping (allowlist semantics):
a field that is not declared MUST NOT be written, regardless of its presence in the source record.
The shipped **payload** mapping declares the heavy body columns `request_body`, `response_body`, and
`assembled_response`, so raw prompt content, raw response content, and the producer's merged response
all ARE persisted to ClickHouse. Whether heavy content is captured is therefore controlled entirely by
that mapping (removing those columns from it, or removing the payload target itself, stops capturing
them). When captured, access to those columns and their retention MUST be governed
operationally — column-level access controls plus a retention/TTL policy — because the service applies
no masking or redaction of its own.

`assembled_response` is captured **in addition to** `response_body` rather than instead of it, and the
two overlap: for a streamed call the raw body is a chunk transcript while the merged response is the
same content assembled. Capturing both is a deliberate cost — one more unbounded-size column of
verbatim user-visible content per row — accepted because the merged response is the only form in which
a consumer can read an answer without reassembling a stream, and because dropping the raw body would
lose the transport-level detail an operator needs to diagnose a malformed stream. An installation that
does not want the merged response captured removes its column from the payload mapping, exactly as for
the other two.

#### Scenario: Declared heavy payload columns are captured

- **WHEN** a record contains `request.body`, `response.body`, and/or `assembled_response` and the
  payload mapping declares the corresponding columns
- **THEN** the inserted `dial_usage_log_payload` row contains those fields' values

#### Scenario: An undeclared payload column is not captured

- **WHEN** a record contains `assembled_response` and no mapping declares a column for it
- **THEN** no inserted row contains an `assembled_response` column, though its value MAY still feed
  derived columns such as `response_id`

#### Scenario: Removing the payload target stops capturing content

- **WHEN** the payload target is removed from the configured target list
- **THEN** ingestion continues to write `dial_usage_log` rows and writes no verbatim body content
  anywhere, and the derived columns computed from the record's bodies are unaffected

#### Scenario: Only declared columns are written

- **WHEN** a record contains fields that are not declared as columns in the mapping
- **THEN** the inserted row contains only the declared columns and none of the undeclared fields

### Requirement: Target table provisioning and schema

By default the service SHALL provision the target ClickHouse tables (`dial_usage_log` and
`dial_usage_log_payload`) on startup by running Flyway migrations under `classpath:db/migration` over
the ClickHouse JDBC driver, using the writer credential (which must therefore additionally hold
`CREATE TABLE` grants). Provisioning SHALL be gated by `clickhouse.migrations.enabled` (default true)
and SHALL run before any sync run or startup schema validation; a migration failure SHALL abort
startup. When migrations are disabled, the service SHALL issue no DDL and the operator SHALL provision
the tables out of band by applying the same migration DDL, in version order: the `CREATE TABLE`
(`src/main/resources/db/migration/clickhouse/V1__create_dial_usage_log.sql`) followed by every later
migration that alters it or creates a sibling table. An operator who skips a later migration SHALL be
stopped by startup schema validation rather than ingesting against a stale table.

Whether applied by Flyway or by an operator, the `dial_usage_log` definition SHALL declare the `events`
column set: a synthetic `event_id UUID` and an `event_kind` classifier; the direct columns carried from
the source (timestamps, ids, `user_hash`, tokens — including the producer's reported cache-write count
in `cache_creation_tokens` — prices, the producer's reported `operation_duration_ms`, etc.); the
calculated columns (`response_id`, `reasoning_tokens`, `number_request_messages`, `success`,
`mcp_method`, `mcp_tool_call_name`, `request_body_bytes`, `response_body_bytes`). It SHALL NOT declare
any verbatim-content column — the three bodies and the two JSON documents all belong to
`dial_usage_log_payload`. On that table, the JSON columns use ClickHouse's `JSON` type, which is
generally available across the supported server range (ClickHouse **25.3 LTS or newer**, verified
through **26.3 LTS**) and SHALL require no experimental enablement setting; the body columns use the
`AES_256_GCM_SIV` codec, which requires a server-side encryption key or `CREATE TABLE` fails. For record-level deduplication to function, `dial_usage_log` SHALL use the
`ReplacingMergeTree` engine versioned by `_ingested_at`, with a sort key (`ORDER BY`) ending in the
deduplication key columns `trace_id` and `core_span_id`, partitioned by `toYYYYMMDD(request_time)`. The
definition SHALL also pin UTC on its timestamp columns, store monetary columns as `Decimal`, store
`response_status` as `UInt16`, declare the producer-optional columns as `Nullable` — including every
producer-reported token count — and apply a row-level retention `TTL`.

Each table SHALL leave exactly one column to a table-side `DEFAULT` and populate every other from its
mapping: `_ingested_at` on `dial_usage_log`, `_updated_at` on `dial_usage_log_payload`.

#### Scenario: Service provisions the table on startup

- **WHEN** migrations are enabled (the default) and the service starts
- **THEN** Flyway applies the `db/migration` DDL with the writer credential, creating `dial_usage_log`
  and `dial_usage_log_payload` if they do not exist, before any sync run or schema validation

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
- **THEN** the service issues no DDL and inserts into the tables the operator provisioned out of band
  from the same migration DDL

#### Scenario: Operator misses a later migration

- **WHEN** `clickhouse.migrations.enabled` is false and the operator provisioned `dial_usage_log` from
  the `CREATE TABLE` alone, omitting the later migration that creates `dial_usage_log_payload`
- **THEN** startup schema validation fails, because the payload mapping cannot be reconciled against a
  table that does not exist, and the service does not start

#### Scenario: Operator applies the split migrations out of order

- **WHEN** `clickhouse.migrations.enabled` is false and the operator applies the drop before the copy
- **THEN** the bodies are gone before they are read, and the copy that follows finds nothing — which is
  why the migrations are numbered and the headers state the order

#### Scenario: Table definition supports record-level dedup

- **WHEN** the tables are provisioned (by Flyway or by an operator) from the migration DDL
- **THEN** `dial_usage_log` is a `ReplacingMergeTree` whose sort key ends in `trace_id` and
  `core_span_id`, and `dial_usage_log_payload` is a `ReplacingMergeTree` ordered by `event_id`, so
  re-ingested rows for the same span collapse on merge in both

#### Scenario: Every user column is populated

- **WHEN** the tables are provisioned and ingestion runs with the shipped mappings
- **THEN** every column of each table except its table-side default (`_ingested_at`, `_updated_at`) is
  written by that table's mapping, so no user column can read as a type default that no producer value
  stands behind

### Requirement: Retention expiry drops whole daily partitions

Every ingest target's `TTL` expiry SHALL drop whole parts rather than rewrite them
(`ttl_only_drop_parts = 1`), so that deleting expired rows requires no free disk space. A row-level TTL
rewrite writes its output before releasing its input, so it needs headroom equal to the part it
rewrites — on a volume that has filled up, the mechanism meant to reclaim space is the one thing that
cannot run.

Because every such table is partitioned by `toYYYYMMDD(request_time)` and expresses its `TTL` on
`request_time`, a part holds one day's rows and they expire within the same day of each other. Expiry is
therefore quantized to a part rather than exact to the row: a part is dropped once its newest row has
expired, so a row MAY outlive its `TTL` by up to the span its part covers (at most one day). Against a
one-year retention, and against the payload table's ninety days, this is immaterial, and it is the
deliberate trade for expiry that can run on a full volume.

The setting SHALL be applied by a versioned ClickHouse migration — in the `CREATE TABLE` for a table
that declares it from the outset, or by a `MODIFY SETTING` migration for one provisioned before the rule
existed — so that it is provisioned identically whether Flyway applies it or an operator applies the DDL
out of band in version order.

#### Scenario: Provisioned table declares whole-part expiry

- **WHEN** the ingest target tables are provisioned (by Flyway or by an operator applying the migrations
  in version order)
- **THEN** each declaration carries `ttl_only_drop_parts = 1`, so a later `TTL` expiry drops expired
  daily parts instead of rewriting them

#### Scenario: Expiry needs no free space

- **WHEN** a retention `TTL` becomes due for a day's partition while the volume has no headroom for a
  part rewrite
- **THEN** the expired parts are dropped rather than rewritten, so expiry proceeds and returns space
  instead of failing for lack of it

### Requirement: Multiple ingest targets

The service SHALL support a configurable list of ingest **targets**, where each target pairs a
ClickHouse table name with its own column mapping (see "Configurable column mapping") and an optional
match predicate (see "Conditional row emission via a match predicate"). During a run, each parsed
record SHALL be offered to every configured target; a target that emits for the record (its predicate
matches, or it declares none) produces one row from that target's mapping, so a single record MAY
produce rows in more than one target table. Each target SHALL batch and insert into its own table via
the dedicated writer credential, and the fail-fast startup schema validation SHALL be applied to each
target independently. The default configuration SHALL include three targets — `dial_usage_log` and
`dial_usage_log_payload` with no match predicate, and `rate_analytics` with one — and the configuration
SHALL be environment-driven, each target's table name and mapping file independently overridable.

The deduplication-key drop rule (see "Records without a deduplication key are dropped") SHALL apply
per target: a record lacking the key columns a target relies on is dropped for that target and counted,
independently of other targets.

#### Scenario: A record fans out to multiple targets

- **WHEN** a run has two configured targets and a parsed record satisfies both targets' emission rules
- **THEN** the service writes one row into each target's table from that target's mapping

#### Scenario: Default single target preserves existing behavior

- **WHEN** only the `dial_usage_log` target is configured
- **THEN** every parsed record is mapped and written to `dial_usage_log` exactly as before, and no
  other table is written

#### Scenario: The shipped configuration writes the log and its payload

- **WHEN** the default target list is configured and a record is parsed
- **THEN** the service writes a `dial_usage_log` row and a `dial_usage_log_payload` row for it, and a
  `rate_analytics` row as well when the record matches that target's predicate

#### Scenario: Per-target startup validation

- **WHEN** the service starts with multiple targets configured
- **THEN** it validates each target's mapping against its own table schema and fails startup if any
  target's mapping diverges from its table
