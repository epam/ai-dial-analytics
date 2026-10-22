# table-management Delta

## MODIFIED Requirements

### Requirement: Table definition catalog

The system SHALL maintain a Postgres-backed catalog of table definitions, each carrying a `name`
(the catalog key, equal to the ClickHouse table name), a `description`, a `type` ∈ {`source`,
`enrichment`, `aggregation`}, and a `status` ∈ {`PENDING`, `ACTIVE`, `FAILED`} (a create records
`PENDING` intent, flips to `ACTIVE` once the ClickHouse table is confirmed, or `FAILED` on error; only
an `ACTIVE` definition is queryable or writable), and a `system` boolean flag (default `false`)
marking the definition as owned/managed by ADAS and therefore not modifiable through the API (see
"System-owned table protection"). A `source` definition SHALL
additionally carry an `ordering_key` and an optional `partition_by`; an `enrichment` definition SHALL
additionally carry the `source_table` it enriches, a `grain_key`, and a `cardinality`. A table
addressed in the API path `/v1/tables/{table_id}` SHALL be identified by its `name`. The catalog
SHALL be the source of truth for which tables exist and how their columns are exposed; it SHALL
replace the hardcoded `DialUsageLogSchema` and the throwaway `metadata` table.

#### Scenario: dial_usage_log is present after migration

- **WHEN** the catalog is read after the table-catalog migration runs
- **THEN** it contains `dial_usage_log` as a `source` with `status=ACTIVE` and its column mapping
  (the backfilled row carries its reserved `_`-prefixed system columns and its `array` column
  `execution_path` with element type `string`), and the previous `metadata` table and
  `/v1/metadata` endpoints no longer exist

### Requirement: Schema validation

The system SHALL validate every create and `PATCH` before persisting or issuing DDL, rejecting
invalid requests with HTTP 422. Validation SHALL cover: table and column names match the snake_case
identifier grammar `^[a-z][a-z0-9_]*$` (≤ 64 chars) and do not use the reserved `_`-prefix (which is
for system columns); a source's `ordering_key` and `partition_by` reference declared columns; an
enrichment's `grain_key` is a declared column of its source; exposed column `name`s are
unique within a single table (cross-table collisions across a source and its enrichments are NOT
rejected — enrichment columns are surfaced under table-qualified flat names, so a source column and an
enrichment column, or two enrichments, may share a bare `name`); a `type=array` column declares a
required `element_type` naming a scalar element type ∈ {`string`, `integer`, `long`, `decimal`,
`boolean`, `date`, `timestamp`, `uuid`} — an `array` column without an `element_type`, an
`element_type` on a non-`array` column, and an `element_type` of `array` (nested arrays) or `object`
SHALL each be rejected; an `array` column SHALL always be non-nullable (ClickHouse has no
`Nullable(Array(...))`): an explicit `nullable: true` on an `array` column SHALL be rejected, and an
omitted `nullable` SHALL resolve to `false` for an `array` column even on an enrichment (whose other
columns default nullable); `object` maps to ClickHouse `JSON`; a column's optional `tag` is a
free-form label of at most 64 characters (no identifier grammar imposed) — a blank tag is treated as
absent, and tags need NOT be unique within a table; a column's optional `display_name` is free-form
text of at most 128 characters (no identifier grammar imposed) — a blank display name is treated as
absent, and display names need NOT be unique within a table; and a column's optional `description`
is free-form text of at most 1024 characters (no identifier grammar imposed) — a blank description
is treated as absent, and descriptions need NOT be unique within a table.

#### Scenario: Non-snake_case or reserved-prefix name is rejected

- **WHEN** a create or `PATCH` declares a table or column name that is not snake_case or begins with
  `_`
- **THEN** the request is rejected with HTTP 422

#### Scenario: User-declared array column is accepted

- **WHEN** a create or `PATCH add` declares a column of `type=array` with `element_type: "string"`
- **THEN** the request is accepted and the column materializes as ClickHouse `Array(String)`

#### Scenario: Array column without an element type is rejected

- **WHEN** a create or `PATCH add` declares a column of `type=array` with no `element_type`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Element type on a non-array column is rejected

- **WHEN** a create or `PATCH add` declares a non-`array` column carrying an `element_type`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Nested or object array element is rejected

- **WHEN** a create or `PATCH add` declares a column of `type=array` with `element_type` of `array`
  or `object`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Explicitly nullable array column is rejected

- **WHEN** a create or `PATCH add` declares a column of `type=array` with `nullable: true`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Array column on an enrichment resolves non-nullable

- **WHEN** a `PATCH add` adds a `type=array` column with an omitted `nullable` to an enrichment
- **THEN** the column is persisted and materialized as non-nullable `Array(<element>)`, even though
  omitted `nullable` defaults to nullable for the enrichment's other columns

#### Scenario: Column tag longer than the limit is rejected

- **WHEN** a create, `PATCH add`, or `PATCH update` declares a column `tag` longer than 64 characters
- **THEN** the request is rejected with HTTP 422

#### Scenario: Column display name longer than the limit is rejected

- **WHEN** a create, `PATCH add`, or `PATCH update` declares a column `display_name` longer than 128
  characters
- **THEN** the request is rejected with HTTP 422

#### Scenario: Column description longer than the limit is rejected

- **WHEN** a create, `PATCH add`, or `PATCH update` declares a column `description` longer than 1024
  characters
- **THEN** the request is rejected with HTTP 422

#### Scenario: Duplicate tags within a table are accepted

- **WHEN** a create or `PATCH` results in two columns of the same table carrying the same `tag`
- **THEN** the request is accepted (tags are non-unique identifying labels)

#### Scenario: Duplicate display names within a table are accepted

- **WHEN** a create or `PATCH` results in two columns of the same table carrying the same `display_name`
- **THEN** the request is accepted (display names are non-unique)

#### Scenario: Duplicate exposed name within a single table is rejected

- **WHEN** a create or `PATCH` would result in two columns of the same table sharing an exposed `name`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Same exposed name across a source and its enrichments is accepted

- **WHEN** an enrichment schema operation exposes a `name` already exposed by its source or a sibling
  enrichment
- **THEN** the operation is accepted, and the columns are surfaced as distinct table-qualified flat
  fields in the source's flattened query schema

### Requirement: Create a source table

The system SHALL create a `source` table at `POST /v1/tables` from `{name, description,
type:"source", columns, ordering_key, partition_by?}` by first persisting the definition as
`status=PENDING` (intent recorded, not yet queryable), then issuing `CREATE TABLE ... ENGINE =
MergeTree ORDER BY (ordering_key)` (with `PARTITION BY` when `partition_by` is given) in ClickHouse
using the writer credential, flipping the definition to `status=ACTIVE` once the table is confirmed,
and returning HTTP 201 with the created definition in `status=ACTIVE`. A source SHALL declare its
columns and `ordering_key` at create time because ClickHouse cannot redefine `ORDER BY` after
creation. A declared column of `type=array` (with its required scalar `element_type`, see "Schema
validation") SHALL materialize as ClickHouse `Array(<element>)`. If the ClickHouse `CREATE TABLE`
fails or its outcome is ambiguous (e.g. a timeout under
which ClickHouse may still create the table), the system SHALL issue a best-effort
`DROP TABLE IF EXISTS` to undo any partially-created table, set the definition to `status=FAILED`, and
return an error without leaking ClickHouse internals; if the status cannot be persisted the definition
SHALL remain `PENDING` (likewise not queryable), so a partial failure never leaves it `ACTIVE` without
a confirmed ClickHouse table.

#### Scenario: Create a source materializes immediately

- **WHEN** `POST /v1/tables` is called with `type:"source"`, valid `columns`, and an `ordering_key`
  referencing declared columns
- **THEN** the response is 201, a `CREATE TABLE ... ENGINE = MergeTree ORDER BY (...)` is issued, and
  the definition is `status=ACTIVE`

#### Scenario: Source create with an array column

- **WHEN** `POST /v1/tables` is called with `type:"source"` and a column of `type:"array"`,
  `element_type:"string"`
- **THEN** the response is 201 and the rendered DDL declares that column as `Array(String)`

#### Scenario: Source create with partitioning

- **WHEN** `POST /v1/tables` is called for a source with `partition_by` naming a declared column and a
  time granularity
- **THEN** the rendered DDL includes a matching `PARTITION BY` expression

#### Scenario: ClickHouse failure marks the table FAILED

- **WHEN** the Postgres definition is written (as `PENDING`) but the ClickHouse `CREATE TABLE` fails
- **THEN** the system issues a best-effort `DROP TABLE IF EXISTS`, sets the definition to
  `status=FAILED`, and the response is an error using the `ErrorView` contract with no raw ClickHouse
  internals

#### Scenario: Duplicate name is rejected

- **WHEN** `POST /v1/tables` is called with a `name` that already exists in the catalog
- **THEN** the request is rejected with HTTP 409

### Requirement: Modify table schema via PATCH

The system SHALL evolve a table's columns at `PATCH /v1/tables/{name}/schema` from a body carrying
`add`, `drop`, `rename`, and `update` operations. The structural operations (`add`/`drop`/`rename`)
SHALL each be applied as an immediate ClickHouse `ALTER` (`ADD`/`DROP`/`RENAME COLUMN`) using the
writer credential and update the Postgres column mapping in the same operation. The `update`
operation SHALL adjust an existing column's catalog-only metadata — its `tag`, `display_name`,
`sensitive` flag, and `description` — and SHALL NOT issue any ClickHouse `ALTER`. Each `update`
entry declares `{name, tag?, display_name?, sensitive?, description?}`, keyed by the column's
current exposed name, and SHALL follow merge-patch semantics per field: a metadata field that is
**absent or null** SHALL leave that column attribute unchanged; a string field (`tag`,
`display_name`, `description`) that is **present and blank** SHALL clear that attribute; a string
field that is **present and non-blank** SHALL set it (trimmed); and `sensitive`, when **present**,
SHALL set the flag to the supplied boolean. A single `update` entry MAY change several metadata
fields at once. Each `add` declares `{source_name, name, type, element_type?, nullable?,
description, display_name?, tag?, sensitive?}` (`sensitive` defaults to false when omitted;
`element_type` is required iff `type=array`, see "Schema validation"), and an added `type=array`
column SHALL materialize as ClickHouse `Array(<element>)`. Operations SHALL be
applied `drop` → `rename` → `add` → `update`, so an `update` entry MAY target a just-added or
just-renamed column by its resulting exposed name. A column's `display_name`, `tag`, `sensitive`
flag, and `description` SHALL all be preserved across a `rename`. Schema modification SHALL be
breaking and immediate (a drop or rename takes effect at once). In `oidc` mode this endpoint SHALL
be authorized by `FULL_ADMIN` or a matching `modify` access role on the table (see the
`table-access-control` capability). The system SHALL reject: a type-change of an existing column
(422); a drop or rename of a system column (reserved `_`-prefix) or the grain key (422); an
`update` of a column that is not an exposed column of the table after the request's own
add/drop/rename operations are applied (422); a duplicate `update` of the same column within one
request (422); and any operation on a table not in the catalog (404). The `update` operation SHALL
carry no mutability restriction — system, grain, and ordering-key columns MAY be tagged, given a
display name, marked sensitive, or described.

#### Scenario: Add columns to an enrichment

- **WHEN** `PATCH /v1/tables/{name}/schema` adds valid columns to an `ACTIVE` enrichment
- **THEN** an `ALTER TABLE ... ADD COLUMN` is issued per column, the column mapping is updated, and
  the response reflects the new columns

#### Scenario: Add an array column

- **WHEN** `PATCH /v1/tables/{name}/schema` adds a column of `type:"array"`, `element_type:"long"`
  to an `ACTIVE` table
- **THEN** an `ALTER TABLE ... ADD COLUMN ... Array(Int64)` is issued and the column mapping records
  the `array` type with element type `long`

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

#### Scenario: Update changes several metadata fields at once

- **WHEN** a single `update` entry supplies a `tag`, a `display_name`, and `sensitive: true` for an
  existing column
- **THEN** all three attributes are set on that column and no ClickHouse `ALTER` is issued

#### Scenario: Absent metadata field leaves an attribute unchanged

- **WHEN** an `update` entry supplies only a `tag` for a column that already has a `display_name`
- **THEN** the column's `tag` is set and its existing `display_name`, `sensitive` flag, and
  `description` are unchanged

#### Scenario: Blank metadata field clears an attribute

- **WHEN** an `update` entry supplies a blank (`""`) `tag`, `display_name`, or `description` for a
  column that currently has that attribute set
- **THEN** that attribute is cleared, and no ClickHouse `ALTER` is issued

#### Scenario: Metadata survives a rename

- **WHEN** a `PATCH` `update`s an existing column's `tag`, `display_name`, `sensitive` flag, and
  `description`, then a later `PATCH` renames that column
- **THEN** all four attributes are preserved under the rename, none of which issues a ClickHouse
  `ALTER`

#### Scenario: Update of an unknown column is rejected

- **WHEN** an `update` entry names a column that is not an exposed column of the table (after the
  request's own add/drop/rename operations are applied)
- **THEN** the request is rejected with HTTP 422

#### Scenario: Duplicate update of the same column is rejected

- **WHEN** two `update` entries in one request name the same column
- **THEN** the request is rejected with HTTP 422

#### Scenario: A modify access role authorizes a non-admin schema change

- **WHEN** a non-`FULL_ADMIN` caller holding a matching `modify` access role on the table `PATCH`es its schema
- **THEN** the modification is authorized and processed

### Requirement: Read and list table definitions

The system SHALL expose `GET /v1/tables/{name}` returning a single definition including its current
`columns` (404 if absent) and `GET /v1/tables` returning all definitions — sources, enrichments, and
aggregations — as a response object (not a bare array). Listing SHALL use a stable order. System
columns (reserved `_`-prefix) and the hidden grain key SHALL NOT appear in the exposed `columns`. Each
exposed column SHALL carry its optional user-friendly `display_name` (absent/null when unset), its
optional identifying `tag` (absent/null when unset), its `sensitive` flag (default `false` when
unset), and — for an `array`-typed column — its `element_type` (absent/null for non-array columns).
Each returned definition SHALL carry its `system` flag (default `false` when unset) so
operators can see which tables are protected from modification. The table-management read surface is
not column-role-filtered — it returns every column with its `sensitive` flag so operators can audit
and manage sensitivity; role-based hiding of sensitive columns applies to the query/discovery surface
(see [query-schema-discovery](../query-schema-discovery/spec.md)), not to `/v1/tables`.

#### Scenario: Get a definition with its columns

- **WHEN** `GET /v1/tables/{name}` is called for an existing definition
- **THEN** the response is 200 with the definition's `name`, `description`, `type`, `status`,
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

- **WHEN** a table has a column of `type=array` with `element_type: "string"` (declared at create or
  via `PATCH add`, or the backfilled `dial_usage_log.execution_path`) and `GET /v1/tables/{name}` is
  called
- **THEN** that column in the response carries `type: "array"` and `element_type: "string"`, while
  non-array columns carry a null/absent `element_type`

#### Scenario: Column display name declared at create is returned

- **WHEN** a source is created with a column carrying a `display_name`, then `GET /v1/tables/{name}` is
  called
- **THEN** that column in the response carries the declared `display_name`, while columns that did not
  declare a display name carry a null `display_name`

#### Scenario: Column tag declared at create is returned

- **WHEN** a source is created with a column carrying a `tag`, then `GET /v1/tables/{name}` is called
- **THEN** that column in the response carries the declared `tag`

#### Scenario: Column sensitive flag declared at create is returned

- **WHEN** a source is created with a column declared `sensitive: true`, then `GET /v1/tables/{name}`
  is called
- **THEN** that column in the response carries `sensitive: true`, while columns that did not declare
  the flag carry `sensitive: false`

#### Scenario: List includes all types

- **WHEN** `GET /v1/tables` is called
- **THEN** the response object lists every definition, including enrichment and aggregation
  definitions, in a stable order

### Requirement: Write rows into a materialized table

The system SHALL accept row inserts at `POST /v1/tables/{name}/rows` from a body carrying a non-empty
`rows` array, each row a JSON object keyed by physical column name (the `source_name`s, and for an
enrichment its grain key). A value for an `array`-typed column SHALL be supplied as a JSON array of
its element type. The system SHALL insert the rows into the table's ClickHouse table using
the writer credential and return the inserted count. In `oidc` mode this endpoint SHALL be authorized
by `FULL_ADMIN` or a matching `write` access role on the table (see the `table-access-control`
capability). It SHALL reject a write to an unknown table (404), a write to a table that is not materialized (409),
and a row that is empty or references a column that is not a physical column of the table (422). This
is the minimal data-write surface for populating sources and enrichments; richer ingest/upsert
semantics are out of scope for this phase.

#### Scenario: Rows are inserted and become queryable

- **WHEN** `POST /v1/tables/{name}/rows` is called for a materialized table with rows keyed by physical
  column name
- **THEN** the rows are inserted via the writer credential, the response reports the inserted count,
  and a subsequent query returns them (enrichment rows resolve into their source via the grain join)

#### Scenario: Array values round-trip through write and query

- **WHEN** rows carrying a JSON array value for an `array`-typed column are written to a materialized
  table and a structured row query later projects that column
- **THEN** the write succeeds and the query returns the value as a JSON array with the elements
  preserved in order

#### Scenario: Write to a non-materialized table is rejected

- **WHEN** `POST /v1/tables/{name}/rows` is called for a table that is not `ACTIVE` (still `PENDING`,
  or `FAILED`)
- **THEN** the request is rejected with HTTP 409

#### Scenario: Write with an unknown column is rejected

- **WHEN** a row references a column that is not a physical column of the table
- **THEN** the request is rejected with HTTP 422

#### Scenario: A write access role authorizes a non-admin write

- **WHEN** a non-`FULL_ADMIN` caller holding a matching `write` access role on the table `POST`s rows
- **THEN** the write is authorized and processed
