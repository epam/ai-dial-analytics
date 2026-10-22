## ADDED Requirements

### Requirement: Table definition catalog

The system SHALL maintain a Postgres-backed catalog of table definitions, each carrying a `name`
(the catalog key, equal to the ClickHouse table name), a `description`, a `type` ∈ {`source`,
`enrichment`, `aggregation`}, and a `status` ∈ {`ACTIVE`, `FAILED`}. A `source` definition SHALL
additionally carry an `ordering_key` and an optional `partition_by`; an `enrichment` definition SHALL
additionally carry the `source_table` it enriches, a `grain_key`, and a `cardinality`. A table
addressed in the API path `/v1/tables/{table_id}` SHALL be identified by its `name`. The catalog
SHALL be the source of truth for which tables exist and how their columns are exposed; it SHALL
replace the hardcoded `DialUsageLogSchema` and the throwaway `metadata` table.

#### Scenario: dial_usage_log is present after migration

- **WHEN** the catalog is read after the table-catalog migration runs
- **THEN** it contains `dial_usage_log` as a `source` with `status=ACTIVE` and its column mapping, and
  the previous `metadata` table and `/v1/metadata` endpoints no longer exist

### Requirement: Create a source table

The system SHALL create a `source` table at `POST /v1/tables` from `{name, description,
type:"source", columns, ordering_key, partition_by?}`, issue `CREATE TABLE ... ENGINE = MergeTree
ORDER BY (ordering_key)` (with `PARTITION BY` when `partition_by` is given) in ClickHouse using the
writer credential, and return HTTP 201 with the created definition in `status=ACTIVE`. A source
SHALL declare its columns and `ordering_key` at create time because ClickHouse cannot redefine
`ORDER BY` after creation. If the ClickHouse `CREATE TABLE` fails, the definition SHALL be set to
`status=FAILED` and an error returned without leaking ClickHouse internals.

#### Scenario: Create a source materializes immediately

- **WHEN** `POST /v1/tables` is called with `type:"source"`, valid `columns`, and an `ordering_key`
  referencing declared columns
- **THEN** the response is 201, a `CREATE TABLE ... ENGINE = MergeTree ORDER BY (...)` is issued, and
  the definition is `status=ACTIVE`

#### Scenario: Source create with partitioning

- **WHEN** `POST /v1/tables` is called for a source with `partition_by` naming a declared column and a
  time granularity
- **THEN** the rendered DDL includes a matching `PARTITION BY` expression

#### Scenario: ClickHouse failure marks the table FAILED

- **WHEN** the Postgres definition is written but the ClickHouse `CREATE TABLE` fails
- **THEN** the definition is set to `status=FAILED` and the response is an error using the
  `ErrorView` contract with no raw ClickHouse internals

#### Scenario: Duplicate name is rejected

- **WHEN** `POST /v1/tables` is called with a `name` that already exists in the catalog
- **THEN** the request is rejected with HTTP 409

### Requirement: Create an enrichment table

The system SHALL create an `enrichment` table at `POST /v1/tables` from `{name, description,
type:"enrichment", source_table, grain_key, cardinality}`. The `source_table` SHALL name an existing
`type=source` definition; the `grain_key` SHALL be a declared column of that source (any column, not
necessarily one of its `ordering_key` columns); `cardinality` SHALL be `ZERO_OR_ONE` in this phase. On
success the system SHALL create the enrichment in ClickHouse as `ReplacingMergeTree(_updated_at)` with
`ORDER BY (grain_key)` carrying only its system columns (no user columns yet), and return HTTP 201
with `status=ACTIVE`. User columns are added afterward via `PATCH /v1/tables/{name}/schema`. A
`grain_key` outside the source's `ordering_key` is permitted but is not index-seekable on the source
side of the join (full-scan); this trade-off is the caller's choice.

#### Scenario: Create an enrichment names its source and grain

- **WHEN** `POST /v1/tables` is called with `type:"enrichment"`, a `source_table` naming an existing
  source, and a `grain_key` that is any declared column of that source
- **THEN** the response is 201, a `ReplacingMergeTree(_updated_at)` shell ordered by `grain_key` is
  created, and `status=ACTIVE`

#### Scenario: Enrichment without a valid source is rejected

- **WHEN** `POST /v1/tables` is called with `type:"enrichment"` and a missing or non-`source`
  `source_table`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Grain key outside the source ordering key is accepted

- **WHEN** `POST /v1/tables` is called for an enrichment whose `grain_key` is a declared column of the
  source that is not part of the source's `ordering_key`
- **THEN** the response is 201 and the enrichment shell is created

#### Scenario: Grain key that is not a declared column of the source is rejected

- **WHEN** `POST /v1/tables` is called for an enrichment whose `grain_key` is not a declared column of
  the source
- **THEN** the request is rejected with HTTP 422

### Requirement: Read and list table definitions

The system SHALL expose `GET /v1/tables/{name}` returning a single definition including its current
`columns` (404 if absent) and `GET /v1/tables` returning all definitions — sources, enrichments, and
aggregations — as a response object (not a bare array). Listing SHALL use a stable order. System
columns (reserved `_`-prefix) and the hidden grain key SHALL NOT appear in the exposed `columns`. Each
exposed column SHALL carry its optional identifying `tag` (absent/null when unset).

#### Scenario: Get a definition with its columns

- **WHEN** `GET /v1/tables/{name}` is called for an existing definition
- **THEN** the response is 200 with the definition's `name`, `description`, `type`, `status`,
  structural fields (`ordering_key`/`partition_by` or `source_table`/`grain_key`/`cardinality`), and
  its exposed `columns`

#### Scenario: Get an unknown definition

- **WHEN** `GET /v1/tables/{name}` is called for a name not in the catalog
- **THEN** the response is 404 with error code `not_found`

#### Scenario: Column tag declared at create is returned

- **WHEN** a source is created with a column carrying a `tag`, then `GET /v1/tables/{name}` is called
- **THEN** that column in the response carries the declared `tag`

#### Scenario: List includes all types

- **WHEN** `GET /v1/tables` is called
- **THEN** the response object lists every definition, including enrichment and aggregation
  definitions, in a stable order

### Requirement: Update a table definition

The system SHALL allow updating only the `description` of a table definition via
`PUT /v1/tables/{name}`. The `name`, `type`, and all structural fields (`ordering_key`,
`partition_by`, `source_table`, `grain_key`, `cardinality`) SHALL be immutable; column changes go
through the schema endpoint. Updating an unknown definition SHALL return 404.

#### Scenario: Description is updated

- **WHEN** an update request changes the `description` of an existing definition
- **THEN** the response is 200 and only the `description` changes

#### Scenario: Structural fields are not mutable

- **WHEN** an update request attempts to change `name`, `type`, or any structural field
- **THEN** those fields are not changed and only `description` is ever mutated

### Requirement: Modify table schema via PATCH

The system SHALL evolve a table's columns at `PATCH /v1/tables/{name}/schema` from a body carrying
`add`, `drop`, `rename`, and `retag` operations. The structural operations (`add`/`drop`/`rename`)
SHALL each be applied as an immediate ClickHouse `ALTER` (`ADD`/`DROP`/`RENAME COLUMN`) using the
writer credential and update the Postgres column mapping in the same operation. The `retag` operation
SHALL set (or clear, when its `tag` is null or blank) the identifying `tag` of an existing column; it
is catalog-only metadata and SHALL NOT issue any ClickHouse `ALTER`. Each `add` declares
`{source_name, name, type, nullable?, description, tag?}`; each `retag` declares `{name, tag}` keyed
by the column's current exposed name. Operations SHALL be applied `drop` → `rename` → `add` → `retag`,
so a `retag` MAY target a just-added or just-renamed column by its resulting exposed name. A column's
`tag` SHALL be preserved across a `rename`. Schema modification SHALL be breaking and immediate (a
drop or rename takes effect at once). The system SHALL reject: a type-change of an existing column
(422); a drop or rename of a system column (reserved `_`-prefix) or the grain key (422); a `retag` of
an unknown column (422); and any operation on a table not in the catalog (404). A `retag` carries no
mutability restriction — system, grain, and ordering-key columns MAY be tagged.

#### Scenario: Add columns to an enrichment

- **WHEN** `PATCH /v1/tables/{name}/schema` adds valid columns to an `ACTIVE` enrichment
- **THEN** an `ALTER TABLE ... ADD COLUMN` is issued per column, the column mapping is updated, and
  the response reflects the new columns

#### Scenario: Drop and rename apply immediately

- **WHEN** `PATCH /v1/tables/{name}/schema` drops one column and renames another
- **THEN** the corresponding `ALTER TABLE ... DROP COLUMN` / `RENAME COLUMN` are issued and the
  column mapping is updated

#### Scenario: Type change is rejected

- **WHEN** a `PATCH` attempts to change the `type` of an existing column
- **THEN** the request is rejected with HTTP 422

#### Scenario: System and grain columns are protected

- **WHEN** a `PATCH` attempts to drop or rename a `_`-prefixed system column or an enrichment's grain
  key
- **THEN** the request is rejected with HTTP 422

#### Scenario: Retag sets, clears, and survives a rename

- **WHEN** a `PATCH` retags an existing column, then a later `PATCH` renames that column, then a final
  `PATCH` retags it with a blank tag
- **THEN** the column's exposed `tag` is set, is preserved under the rename, and is then cleared — none
  of which issues a ClickHouse `ALTER`

#### Scenario: Retag of an unknown column is rejected

- **WHEN** a `PATCH` retags a `name` that is not an exposed column of the table (after the request's
  own add/drop/rename operations are applied)
- **THEN** the request is rejected with HTTP 422

### Requirement: Grain-based enrichment join with enforced cardinality

The system SHALL resolve enrichment columns into a source's flat schema by a single-key
`LEFT JOIN` on the shared `grain_key` (`source.grain_key = enrichment.grain_key`). Enrichment tables
SHALL be stored as `ReplacingMergeTree(_updated_at)` ordered by the grain key so that at most one row
exists per grain key, making the declared `ZERO_OR_ONE` cardinality physically true and preventing
the join from inflating source rows. The join SHALL be a single-key keyed lookup on `grain_key`
alone. Joins SHALL be emitted only for enrichments a query actually
references. Enrichment columns SHALL be opt-in: a row query with an omitted projection (`select`
absent) SHALL return the source's own columns only — enrichment columns must be named explicitly to
be projected (and to trigger their join), so a bare projection never fans out into enrichment joins.

#### Scenario: Enrichment column resolves via grain join

- **WHEN** a query references an enrichment column of a source
- **THEN** the builder emits a `LEFT JOIN` of that enrichment on the shared `grain_key` and no join
  for enrichments the query does not reference

#### Scenario: Cardinality is enforced by storage

- **WHEN** two rows are written to an enrichment for the same `grain_key`
- **THEN** the `ReplacingMergeTree` keeps one row per key (latest `_updated_at` wins) and the source
  row count is not inflated by the join

#### Scenario: Omitted projection returns source columns only

- **WHEN** a row query against a source that has enrichments is executed with no `select`
- **THEN** only the source's own columns are returned, no enrichment is joined, and the query
  succeeds (enrichment columns appear only when explicitly selected/referenced)

### Requirement: Schema validation

The system SHALL validate every create and `PATCH` before persisting or issuing DDL, rejecting
invalid requests with HTTP 422. Validation SHALL cover: table and column names match the snake_case
identifier grammar `^[a-z][a-z0-9_]*$` (≤ 64 chars) and do not use the reserved `_`-prefix (which is
for system columns); a source's `ordering_key` and `partition_by` reference declared columns; an
enrichment's `grain_key` is a declared column of its source; exposed column `name`s are
unique within a single table (cross-table collisions across a source and its enrichments are NOT
rejected — enrichment columns are surfaced under table-qualified flat names, so a source column and an
enrichment column, or two enrichments, may share a bare `name`); `type=array` is NOT accepted in a
user-declared schema (the type vocabulary excludes `array` in this phase; `object` maps to ClickHouse
`JSON`); and a column's optional `tag` is a free-form label of at most 64 characters (no identifier
grammar imposed) — a blank tag is treated as absent, and tags need NOT be unique within a table.

#### Scenario: Non-snake_case or reserved-prefix name is rejected

- **WHEN** a create or `PATCH` declares a table or column name that is not snake_case or begins with
  `_`
- **THEN** the request is rejected with HTTP 422

#### Scenario: User-declared array type is rejected

- **WHEN** a create or `PATCH` declares a column of `type=array`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Column tag longer than the limit is rejected

- **WHEN** a create, `PATCH add`, or `PATCH retag` declares a column `tag` longer than 64 characters
- **THEN** the request is rejected with HTTP 422

#### Scenario: Duplicate tags within a table are accepted

- **WHEN** a create or `PATCH` results in two columns of the same table carrying the same `tag`
- **THEN** the request is accepted (tags are non-unique identifying labels)

#### Scenario: Duplicate exposed name within a single table is rejected

- **WHEN** a create or `PATCH` would result in two columns of the same table sharing an exposed `name`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Same exposed name across a source and its enrichments is accepted

- **WHEN** an enrichment schema operation exposes a `name` already exposed by its source or a sibling
  enrichment
- **THEN** the operation is accepted, and the columns are surfaced as distinct table-qualified flat
  fields in the source's flattened query schema

### Requirement: Catalog limits

The system SHALL enforce configurable lightweight caps, rejecting violations with HTTP 422: a maximum
number of columns per table (default 256), enforced when creating a source and when a `PATCH` adds
columns; and a maximum number of tables in the catalog (default 1000), enforced at create. A maximum
write-batch size (default 10000 rows) SHALL be configurable for forward compatibility but is inert
until a generic per-table write API exists. Limits SHALL be env-overridable.

#### Scenario: Exceeding the column cap is rejected

- **WHEN** a source create or a `PATCH add` would take a table beyond the configured
  `max-columns-per-table`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Exceeding the table cap is rejected

- **WHEN** `POST /v1/tables` would take the catalog beyond the configured `max-tables`
- **THEN** the request is rejected with HTTP 422

### Requirement: Write rows into a materialized table

The system SHALL accept row inserts at `POST /v1/tables/{name}/rows` from a body carrying a non-empty
`rows` array, each row a JSON object keyed by physical column name (the `source_name`s, and for an
enrichment its grain key). The system SHALL insert the rows into the table's ClickHouse table using
the writer credential and return the inserted count. It SHALL reject a write to an unknown table
(404), a write to a table that is not materialized (409), and a row that is empty or references a
column that is not a physical column of the table (422). This is the minimal data-write surface for
populating sources and enrichments; richer ingest/upsert semantics are out of scope for this phase.

#### Scenario: Rows are inserted and become queryable

- **WHEN** `POST /v1/tables/{name}/rows` is called for a materialized table with rows keyed by physical
  column name
- **THEN** the rows are inserted via the writer credential, the response reports the inserted count,
  and a subsequent query returns them (enrichment rows resolve into their source via the grain join)

#### Scenario: Write to a non-materialized table is rejected

- **WHEN** `POST /v1/tables/{name}/rows` is called for a `DECLARED` (not materialized) table
- **THEN** the request is rejected with HTTP 409

#### Scenario: Write with an unknown column is rejected

- **WHEN** a row references a column that is not a physical column of the table
- **THEN** the request is rejected with HTTP 422

### Requirement: Delete a table definition

The system SHALL delete a table definition at `DELETE /v1/tables/{name}`, removing its catalog rows
and issuing `DROP TABLE` in ClickHouse (writer credential), returning HTTP 204. Deleting a `source`
that still has enrichments referencing it SHALL be rejected with HTTP 409 until those enrichments are
deleted. Deleting an unknown definition SHALL return 404.

#### Scenario: Delete drops the ClickHouse table

- **WHEN** `DELETE /v1/tables/{name}` is called for a table with no dependents
- **THEN** the catalog rows are removed, `DROP TABLE` is issued in ClickHouse, and the response is 204

#### Scenario: Delete of a source with enrichments is blocked

- **WHEN** `DELETE /v1/tables/{name}` is called for a `source` that has enrichments referencing it
- **THEN** the request is rejected with HTTP 409 and no table is dropped

### Requirement: Catalog PostgreSQL availability is mapped to the error contract

The system SHALL respond with HTTP 503 and the shared `ErrorView` contract using a stable `error`
code (`postgres_unavailable`) when the PostgreSQL catalog database is unreachable or a data-access
error occurs on a table-management endpoint, without leaking stack traces or raw driver internals.

#### Scenario: Catalog database unavailable

- **WHEN** a `/v1/tables` endpoint is invoked while PostgreSQL is unreachable
- **THEN** the service responds 503 with an `ErrorView` whose `error` code identifies a PostgreSQL
  availability failure

### Requirement: Aggregation type is accepted but inert

The system SHALL accept `aggregation` as a valid `type` value for forward compatibility but SHALL
NOT implement any aggregation-specific materialization or query behavior in this phase. An
`aggregation` table SHALL NOT receive special rollup/materialized-view handling.

#### Scenario: Aggregation type is a valid value

- **WHEN** `POST /v1/tables` is called with `type:"aggregation"`
- **THEN** the definition is created, with no aggregation-specific materialization or query
  semantics applied
