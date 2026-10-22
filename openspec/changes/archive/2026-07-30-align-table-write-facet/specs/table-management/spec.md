## ADDED Requirements

### Requirement: Table write discipline

The system SHALL carry on every table definition a **write discipline** facet
`write` ∈ {`append`, `upsert_by_key`}, declared at `POST /v1/tables` alongside `type` and defaulting
to `append` when the member is absent. The facet SHALL be **immutable**: it selects the physical
ClickHouse engine, which is frozen at materialization and cannot be `ALTER`ed, so an attempt to
change it through `PUT /v1/tables/{name}` or `PATCH /v1/tables/{name}/schema` SHALL be rejected with
HTTP 422. The facet is a **table** facet, decoupled from `type`: it says how a write to an existing
key behaves, not what the table is.

The facet SHALL be declarable only on a `source`. An `enrichment`'s write discipline is implied by
its kind (it is always keyed and collapsed on its grain), so a `write` member on an `enrichment`
create SHALL be rejected with HTTP 422.

The facet SHALL select the engine at materialization:

- **`append`** — a plain `MergeTree ORDER BY (ordering_key)`, exactly today's behaviour. Repeated
  writes of the same key accumulate rows.
- **`upsert_by_key`** — a `ReplacingMergeTree(_updated_at) ORDER BY (ordering_key)` carrying the
  version column rendered as `_updated_at DateTime64(3) DEFAULT now64(3)`, so a re-write of an
  existing `ordering_key` collapses to the latest version. The `ordering_key` is therefore the
  table's unique key.

`_updated_at` on an `upsert_by_key` source SHALL be a hidden system column with exactly the standing
system-column semantics: it SHALL NOT appear in the exposed `columns` and SHALL NOT be counted by
`column_count`; it SHALL NOT be declarable in a schema (the reserved `_`-prefix rule in "Schema
validation"); and it SHALL NOT be caller-settable in a row write (the reserved-prefix rule in "Write
rows into a materialized table"), so ClickHouse stamps an increasing version on every write.

Reads of an `upsert_by_key` source SHALL return the latest row per key with no query-side opt-in,
because the query pipeline already applies `final = 1` to every read (see
[structured-query](../structured-query/spec.md) "Read result deduplication").

#### Scenario: Create a source with an explicit write discipline

- **WHEN** `POST /v1/tables` is called with `{name, type:"source", write:"upsert_by_key"}`
- **THEN** the response is 201 with `status=PENDING` and the definition carries
  `write: "upsert_by_key"`

#### Scenario: Write discipline defaults to append

- **WHEN** `POST /v1/tables` is called with `{name, type:"source"}` and no `write` member
- **THEN** the created definition carries `write: "append"` and behaves exactly as before this
  capability existed

#### Scenario: An upsert source materializes as a keyed, version-collapsing table

- **WHEN** `POST /v1/tables/{name}/schema` materializes an `upsert_by_key` source whose
  `ordering_key` is `(deployment, day)`
- **THEN** a single `CREATE TABLE … ENGINE = ReplacingMergeTree(_updated_at) ORDER BY (deployment,
  day)` is issued, declaring `_updated_at DateTime64(3) DEFAULT now64(3)` in addition to the declared
  columns

#### Scenario: An append source is unaffected

- **WHEN** `POST /v1/tables/{name}/schema` materializes a source whose `write` is `append` (declared
  or defaulted)
- **THEN** the rendered `CREATE TABLE` uses `ENGINE = MergeTree ORDER BY (ordering_key)` with no
  version column, identical to the behaviour before this capability existed

#### Scenario: Write discipline on an enrichment create is rejected

- **WHEN** `POST /v1/tables` is called with `type:"enrichment"` and a `write` member
- **THEN** the request is rejected with HTTP 422 (an enrichment's write discipline is implied by its
  kind)

#### Scenario: Write discipline is immutable

- **WHEN** `PUT /v1/tables/{name}` or `PATCH /v1/tables/{name}/schema` attempts to set or change
  `write` on an existing definition
- **THEN** the request is rejected with HTTP 422 and the stored facet is unchanged

#### Scenario: The version column stays hidden and platform-owned

- **WHEN** an `upsert_by_key` source is materialized and `GET /v1/tables/{name}`,
  `GET /v1/tables`, and a row write naming `_updated_at` are exercised
- **THEN** `_updated_at` appears in neither the exposed `columns` nor `column_count`, and the row
  write is rejected with HTTP 422

#### Scenario: An upsert source reads as the latest row per key

- **WHEN** the same `ordering_key` is written twice into an `upsert_by_key` source with different
  measure values and the parts have not merged, then the table is queried
- **THEN** the query returns one row for that key carrying the values from the later write

## MODIFIED Requirements

### Requirement: Table definition catalog

The system SHALL maintain a Postgres-backed catalog of table definitions, each carrying a `name`
(the catalog key, equal to the ClickHouse table name), a `description`, a `type` ∈ {`source`,
`enrichment`}, a `write` discipline ∈ {`append`, `upsert_by_key`} (see "Table write discipline"), and
a `status` ∈ {`PENDING`, `ACTIVE`, `FAILED`} (a create records the
definition as `PENDING` — identity recorded, **not yet materialized** in ClickHouse; the table is
materialized by an explicit `POST /v1/tables/{name}/schema` that defines its physical schema, issues
`CREATE TABLE`, and flips it to `ACTIVE`, or records `FAILED` on a materialization error; `PENDING` is
a durable "identity recorded, not materialized" state, and only an `ACTIVE` definition is queryable or
writable). `FAILED` SHALL be produced only by a failed materialization and SHALL NOT be entered from
`ACTIVE`; `PENDING` and `FAILED` are the un-materialized states (no confirmed ClickHouse table) and —
because row writes require `ACTIVE` — a `FAILED` table holds no user data, making it safe to re-define
its schema (retry). The definition also carries a `system` boolean flag
(default `false`) marking the definition as owned/managed by ADAS and therefore not modifiable through
the API (see "System-owned table protection"). A `source` definition SHALL additionally carry an
optional `ordering_key` and an optional `partition_by`; an `enrichment` definition SHALL additionally
carry the `source_table` it enriches (required at create and immutable), and an optional `grain_key`
and `cardinality`. The physical-schema fields (`columns`, and a source's `ordering_key`/`partition_by`
or an enrichment's `grain_key`/`cardinality`) MAY be unset on a `PENDING` definition and are populated
through the schema resource (`POST /v1/tables/{name}/schema`). A table addressed in the API path
`/v1/tables/{table_id}` SHALL be identified by its `name`. The catalog SHALL be the source of truth for
which tables exist and how their columns are exposed; it SHALL replace the hardcoded
`DialUsageLogSchema` and the throwaway `metadata` table.

#### Scenario: dial_usage_log is present after migration

- **WHEN** the catalog is read after the table-catalog migration runs
- **THEN** it contains `dial_usage_log` as a `source` with `status=ACTIVE`, `write=append`, and its
  column mapping (the backfilled row carries its reserved `_`-prefixed system columns and its `array`
  column `execution_path` with element type `string`), and the previous `metadata` table and
  `/v1/metadata` endpoints no longer exist

### Requirement: Read and list table definitions

The system SHALL expose `GET /v1/tables/{name}` returning a single definition including its current
`columns` (404 if absent) and `GET /v1/tables` returning all definitions — sources and enrichments —
as a response object (not a bare array). Listing SHALL use a stable order. Each list
item SHALL carry a `column_count` field: the number of exposed columns of that table, equal to the
length of the `columns` array `GET /v1/tables/{name}` returns for the same table (`0` for a
definition with no exposed columns yet); `column_count` SHALL NOT appear on the single-get response.
System columns (reserved `_`-prefix) and the hidden grain key SHALL NOT appear in the exposed
`columns` and SHALL NOT be counted by `column_count`. Each exposed column SHALL carry its optional
user-friendly `display_name` (absent/null when unset), its optional identifying `tag` (absent/null
when unset), its `sensitive` flag (default `false` when unset), and — for an `array`-typed column —
its `element_type` (absent/null for non-array columns). Each returned definition SHALL
carry its `system` flag (default `false` when unset) so operators can see which tables are protected
from modification, and its `write` discipline so operators can see how a write to an existing key
behaves. The table-management read surface is not column-role-filtered — it returns every
column with its `sensitive` flag so operators can audit and manage sensitivity; role-based hiding of
sensitive columns applies to the query/discovery surface (see
[query-schema-discovery](../query-schema-discovery/spec.md)), not to `/v1/tables`.

#### Scenario: Get a definition with its columns

- **WHEN** `GET /v1/tables/{name}` is called for an existing definition
- **THEN** the response is 200 with the definition's `name`, `description`, `type`, `write`, `status`,
  `system` flag, structural fields (`ordering_key`/`partition_by` or
  `source_table`/`grain_key`/`cardinality`), and its exposed `columns`

#### Scenario: Get an unknown definition

- **WHEN** `GET /v1/tables/{name}` is called for a name not in the catalog
- **THEN** the response is 404 with error code `not_found`

#### Scenario: System flag is returned

- **WHEN** `GET /v1/tables/{name}` is called for `dial_usage_log` (a seeded system table) and for a
  user-created table
- **THEN** the seeded table's response carries `system=true` and the user-created table's response
  carries `system=false`

#### Scenario: Array column element type is returned

- **WHEN** a table has a column of `type=array` with `element_type: "string"` (declared via
  `POST /v1/tables/{name}/schema` or via `PATCH add`, or the backfilled
  `dial_usage_log.execution_path`) and `GET /v1/tables/{name}` is called
- **THEN** that column in the response carries `type: "array"` and `element_type: "string"`, while
  non-array columns carry a null/absent `element_type`

#### Scenario: Column display name declared in the schema is returned

- **WHEN** a source's schema (`POST /v1/tables/{name}/schema`) declares a column carrying a
  `display_name` and the table is materialized, then `GET /v1/tables/{name}` is called
- **THEN** that column in the response carries the declared `display_name`, while columns that did not
  declare a display name carry a null `display_name`

#### Scenario: Column tag declared in the schema is returned

- **WHEN** a source's schema (`POST /v1/tables/{name}/schema`) declares a column carrying a `tag`
  and the table is materialized, then `GET /v1/tables/{name}` is called
- **THEN** that column in the response carries the declared `tag`

#### Scenario: Column sensitive flag declared in the schema is returned

- **WHEN** a source's schema (`POST /v1/tables/{name}/schema`) declares a column with
  `sensitive: true` and the table is materialized, then `GET /v1/tables/{name}` is called
- **THEN** that column in the response carries `sensitive: true`, while columns that did not declare
  the flag carry `sensitive: false`

#### Scenario: List includes both table types

- **WHEN** `GET /v1/tables` is called
- **THEN** the response object lists every definition, including enrichment definitions and
  `upsert_by_key` sources, in a stable order

#### Scenario: List items carry the exposed-column count

- **WHEN** a source's schema (`POST /v1/tables/{name}/schema`) declares 3 columns and the table
  is materialized, then `GET /v1/tables` is called
- **THEN** that source's list item carries `column_count: 3` and no `columns` array

#### Scenario: Column count is zero for a column-less definition

- **WHEN** an enrichment is created (a grain shell with no exposed columns yet) and `GET /v1/tables`
  is called
- **THEN** that enrichment's list item carries `column_count: 0`

#### Scenario: Column count tracks schema evolution

- **WHEN** a table's schema is patched to add one column and drop one column, then `GET /v1/tables`
  is called
- **THEN** the table's `column_count` equals the length of the `columns` array returned by
  `GET /v1/tables/{name}`

#### Scenario: Column count absent on single-get

- **WHEN** `GET /v1/tables/{name}` is called for an existing definition
- **THEN** the response carries the `columns` array and no `column_count` field

### Requirement: Define and materialize a table schema

The system SHALL expose `POST /v1/tables/{name}/schema` to define the complete physical schema of a
not-yet-materialized table AND materialize it (issue `CREATE TABLE`, flip to `ACTIVE`) in a single
call. The endpoint SHALL be allowed only while the table's `status` is `PENDING` or `FAILED`; on an
already `ACTIVE` (materialized) table it SHALL be rejected with HTTP 409 (a materialized schema evolves
through `PATCH /v1/tables/{name}/schema`).

The request body SHALL carry the table's physical schema: `columns` (the exposed columns, each
`{source_name, name, type, element_type?, nullable?, description?, display_name?, tag?, sensitive?}`)
plus the physical keys for the table's kind — `ordering_key` and optional `partition_by` for a
`source`; `grain_key` and optional `cardinality` for an `enrichment` — and an optional `ttl`
declaration `{column, interval, unit}` (see "Table TTL" for its validation and rendering rules).
The `name`, `type`, `write`, and `source_table` (identity/relationship) SHALL NOT appear in this body
(they are set and fixed by `POST /v1/tables`).

The system SHALL validate the schema — both completeness for the table's kind and internal consistency
(see "Schema validation") — before persisting anything or issuing any ClickHouse DDL: a `source` SHALL
have at least one column and a non-empty `ordering_key` whose columns (and `partition_by` column, if
any) are present; an `enrichment` SHALL have a `grain_key` that is a declared column of its
`source_table` and MAY have zero user columns; a `ttl`, when present, SHALL satisfy the "Table TTL"
constraints (declared non-nullable `date`/`timestamp` column, positive `interval`, valid `unit`). An
incomplete or inconsistent schema SHALL be rejected with HTTP 422, leaving the definition untouched
(still `PENDING`/`FAILED`, nothing persisted).

On a valid schema the system SHALL persist the columns + keys (and the `ttl`, when declared) and
issue a single ClickHouse `CREATE TABLE` (writer credential), with the engine selected from the
definition's `type` and `write` discipline (see "Table write discipline") — an `append` `source` as
`MergeTree ORDER BY (ordering_key)`, an `upsert_by_key` `source` as
`ReplacingMergeTree(_updated_at) ORDER BY (ordering_key)`, an `enrichment` as
`ReplacingMergeTree(_updated_at)` with `ORDER BY (grain_key)` carrying its grain-key and system
columns (with `PARTITION BY` when a source's `partition_by` is set) — every version-collapsing engine
rendering its version column as `_updated_at DateTime64(3) DEFAULT now64(3)` so that every
write lands with a strictly increasing RMT version and duplicate writes for the same key collapse
to the latest on a `final=1` read — with a `TTL` clause appended when a `ttl` is declared — and flip
the definition to `status=ACTIVE`, returning the materialized definition. A `type=array` column SHALL
materialize as ClickHouse `Array(<element>)`. If the `CREATE TABLE` fails or its outcome is ambiguous
(e.g. a timeout under which ClickHouse may still create the table), the system SHALL issue a
best-effort `DROP TABLE IF EXISTS`, set the definition to `status=FAILED`, and return an error using
the `ErrorView` contract without leaking ClickHouse internals; a `FAILED` table MAY be re-posted
(with its full schema) to retry. In `oidc` mode the endpoint SHALL be authorized by `FULL_ADMIN` or a
matching `modify` access role, and a `system`-owned table SHALL reject it with HTTP 409 regardless of
role.

#### Scenario: Define and materialize a source

- **WHEN** `POST /v1/tables/{name}/schema` is called on a `PENDING` `append` source with `columns` and
  a non-empty `ordering_key` referencing declared columns
- **THEN** a single `CREATE TABLE ... ENGINE = MergeTree ORDER BY (...)` is issued, the definition
  becomes `status=ACTIVE`, and the response reflects the materialized schema

#### Scenario: Define and materialize an upsert-by-key source

- **WHEN** `POST /v1/tables/{name}/schema` is called on a `PENDING` `upsert_by_key` source with
  `columns` and a non-empty `ordering_key`
- **THEN** a single `CREATE TABLE ... ENGINE = ReplacingMergeTree(_updated_at) ORDER BY
  (ordering_key)` is issued carrying the `_updated_at` version column, and the definition becomes
  `status=ACTIVE`

#### Scenario: Define a source with partitioning

- **WHEN** the request includes a `partition_by` naming a declared column and a time granularity
- **THEN** the rendered `CREATE TABLE` includes a matching `PARTITION BY` expression

#### Scenario: Define a source with a TTL

- **WHEN** `POST /v1/tables/{name}/schema` is called on a `PENDING` source with a valid `ttl`
  declaration `{column: "event_date", interval: 6, unit: "month"}` where `event_date` is a declared
  non-nullable `date` column
- **THEN** the rendered `CREATE TABLE` ends with `TTL event_date + INTERVAL 6 MONTH`, the catalog
  stores the TTL, and the materialized definition carries the `ttl` object

#### Scenario: Invalid TTL declaration keeps the table un-materialized

- **WHEN** `POST /v1/tables/{name}/schema` carries a `ttl` whose column is not declared, is
  nullable, or is not a `date`/`timestamp` column
- **THEN** the request is rejected with HTTP 422, no ClickHouse DDL is issued, and the definition
  remains `PENDING`/`FAILED`

#### Scenario: Define a source with an array column

- **WHEN** the request includes a column of `type=array` with `element_type: "string"`
- **THEN** the rendered `CREATE TABLE` declares that column as `Array(String)`

#### Scenario: Define and materialize an enrichment

- **WHEN** `POST /v1/tables/{name}/schema` is called on a `PENDING` enrichment with a `grain_key` that
  is a declared column of its `source_table` (and zero or more user `columns`)
- **THEN** a single `CREATE TABLE ... ENGINE = ReplacingMergeTree(_updated_at) ORDER BY (grain_key)`
  is issued carrying its grain-key and system columns plus any user columns, and the definition
  becomes `status=ACTIVE`

#### Scenario: Enrichment version column carries a default

- **WHEN** an enrichment table is materialized
- **THEN** its `_updated_at` column is rendered `DateTime64(3) DEFAULT now64(3)`, so a write that
  omits `_updated_at` still receives an increasing version stamped by ClickHouse

#### Scenario: Incomplete schema is rejected and stays pending

- **WHEN** `POST /v1/tables/{name}/schema` is called on a source with no columns or an empty
  `ordering_key`, or on an enrichment with no `grain_key`
- **THEN** the request is rejected with HTTP 422, no ClickHouse `CREATE TABLE` is issued, and the
  definition remains `status=PENDING`

#### Scenario: Inconsistent schema is rejected

- **WHEN** `POST /v1/tables/{name}/schema` declares an `ordering_key` column not present in `columns`,
  or an enrichment `grain_key` that is not a column of its `source_table`
- **THEN** the request is rejected with HTTP 422 and nothing is persisted

#### Scenario: Define on a materialized table is rejected

- **WHEN** `POST /v1/tables/{name}/schema` is called on an `ACTIVE` table
- **THEN** the request is rejected with HTTP 409 (use `PATCH` to evolve a live table)

#### Scenario: ClickHouse failure marks the table FAILED

- **WHEN** the ClickHouse `CREATE TABLE` fails during the define-and-materialize call
- **THEN** the system issues a best-effort `DROP TABLE IF EXISTS`, sets the definition to
  `status=FAILED`, and the response is an error using the `ErrorView` contract with no raw ClickHouse
  internals

## REMOVED Requirements

### Requirement: Aggregation type is accepted but inert

**Reason**: The tables-and-pipelines design settles that aggregation is not a table kind but a **pipeline** kind whose
output is an ordinary `source` table. The type was accepted for forward compatibility and
materialized, queried, and joined nothing; keeping it would invite callers to register rollups as a
type no code path will ever serve. A rollup is now a `source` with `write: upsert_by_key`, which is
queryable and enrichment-parentable with no query-path special case.

**Migration**: None required — the type produced only inert catalog rows and the API is unreleased.
`POST /v1/tables` with `type: "aggregation"` is now rejected as an unknown type; register the table as
`{type: "source", write: "upsert_by_key"}` instead, with its `ordering_key` set to the rollup's group
keys.
