## ADDED Requirements

### Requirement: Define a draft table schema

The system SHALL expose `PUT /v1/tables/{name}/schema` to define or replace the **draft physical
schema** of a not-yet-materialized table. The endpoint SHALL be allowed only while the table's
`status` is `PENDING` or `FAILED`; on an `ACTIVE` (materialized) table it SHALL be rejected with HTTP
409 (a materialized schema evolves through `PATCH /v1/tables/{name}/schema`). Because `PENDING`/`FAILED`
are the un-materialized states (no confirmed ClickHouse table, and — since row writes require `ACTIVE`
— no user data), replacing the draft is non-destructive; a materialized table with data is never
reachable here (a failed live `ALTER` keeps a table `ACTIVE`, it never becomes `FAILED`). It SHALL be
catalog-only — it SHALL NOT issue any ClickHouse DDL — and SHALL be idempotent/repeatable (each call
replaces the stored draft schema in full).

The request body SHALL carry the table's physical schema: `columns` (the exposed columns, each
`{source_name, name, type, nullable?, description?, display_name?, tag?, sensitive?}`) plus the
physical keys for the table's kind — `ordering_key` and optional `partition_by` for a `source`;
`grain_key` and optional `cardinality` for an `enrichment`. The `name`, `type`, and `source_table`
(identity/relationship) SHALL NOT appear in this body (they are set and fixed by `POST /v1/tables`).
The system SHALL validate the draft for internal consistency (see "Schema validation") — identifier
grammar, exposed-name uniqueness, array-type rejection, `ordering_key`/`partition_by` referencing
declared `columns`, an enrichment's `grain_key` being a declared column of its `source_table`, and the
column cap — but SHALL NOT require completeness (a source MAY be drafted with columns but no
`ordering_key` yet); completeness is enforced at materialization. In `oidc` mode the endpoint SHALL be
authorized by `FULL_ADMIN` or a matching `modify` access role, and a `system`-owned table SHALL reject
it with HTTP 409 regardless of role.

#### Scenario: PUT schema stores a source draft

- **WHEN** `PUT /v1/tables/{name}/schema` is called on a `PENDING` source with `columns` and an
  `ordering_key` referencing declared columns
- **THEN** the response is 200, the draft `columns` and `ordering_key` are stored, `status` stays
  `PENDING`, and no ClickHouse DDL is issued

#### Scenario: PUT schema replaces the previous draft

- **WHEN** `PUT /v1/tables/{name}/schema` is called twice on a `PENDING` table with different `columns`
- **THEN** the stored draft reflects the second request in full (replace, not merge)

#### Scenario: PUT schema on a materialized table is rejected

- **WHEN** `PUT /v1/tables/{name}/schema` is called on an `ACTIVE` table
- **THEN** the request is rejected with HTTP 409 and no change is made (use `PATCH` to evolve a live
  table)

#### Scenario: PUT schema stores an enrichment draft with its grain key

- **WHEN** `PUT /v1/tables/{name}/schema` is called on a `PENDING` enrichment with a `grain_key` that
  is a declared column of its `source_table` (and zero or more `columns`)
- **THEN** the response is 200, the draft is stored with its grain, `status` stays `PENDING`, and no
  ClickHouse DDL is issued

#### Scenario: PUT schema with an inconsistent draft is rejected

- **WHEN** `PUT /v1/tables/{name}/schema` declares an `ordering_key` column not present in `columns`,
  or an enrichment `grain_key` that is not a column of its `source_table`
- **THEN** the request is rejected with HTTP 422 and no draft is stored

### Requirement: Materialize a table

The system SHALL expose `POST /v1/tables/{name}/materialize` to commit a draft schema to ClickHouse.
The endpoint SHALL be allowed only while the table's `status` is `PENDING` or `FAILED`; on an already
`ACTIVE` table it SHALL be rejected with HTTP 409. It SHALL validate that the stored draft is complete
and consistent for the table's kind — a `source` SHALL have at least one column and a non-empty
`ordering_key` whose columns (and `partition_by` column, if any) are present; an `enrichment` SHALL
have a `grain_key` that is a declared column of its `source_table` (its grain row is created if not
already present) and MAY have zero user columns — rejecting an incomplete or inconsistent draft with
HTTP 422 (no DDL issued, the definition remains `PENDING`). On a valid draft the system SHALL issue a
single ClickHouse `CREATE TABLE` (writer credential) — a `source` as `MergeTree ORDER BY
(ordering_key)` (with `PARTITION BY` when `partition_by` is set), an `enrichment` as
`ReplacingMergeTree(_updated_at)` with `ORDER BY (grain_key)` carrying its grain-key and system
columns — and flip the definition to `status=ACTIVE`, returning the materialized definition. If the
`CREATE TABLE` fails or its outcome is ambiguous (e.g. a timeout under which ClickHouse may still
create the table), the system SHALL issue a best-effort `DROP TABLE IF EXISTS`, set the definition to
`status=FAILED`, and return an error using the `ErrorView` contract without leaking ClickHouse
internals; a `FAILED` table MAY be re-drafted via `PUT /v1/tables/{name}/schema` and materialized
again. In `oidc` mode the endpoint SHALL be authorized by `FULL_ADMIN` or a matching `modify` access
role, and a `system`-owned table SHALL reject it with HTTP 409 regardless of role.

#### Scenario: Materialize a source

- **WHEN** `POST /v1/tables/{name}/materialize` is called on a `PENDING` source whose draft has columns
  and a non-empty `ordering_key`
- **THEN** a single `CREATE TABLE ... ENGINE = MergeTree ORDER BY (...)` is issued, the definition
  becomes `status=ACTIVE`, and the response reflects the materialized schema

#### Scenario: Materialize a source with partitioning

- **WHEN** the draft of a `PENDING` source includes a `partition_by` naming a declared column and a
  time granularity, and it is materialized
- **THEN** the rendered `CREATE TABLE` includes a matching `PARTITION BY` expression

#### Scenario: Materialize an enrichment

- **WHEN** `POST /v1/tables/{name}/materialize` is called on a `PENDING` enrichment whose draft has a
  `grain_key` that is a declared column of its `source_table`
- **THEN** a single `CREATE TABLE ... ENGINE = ReplacingMergeTree(_updated_at) ORDER BY (grain_key)`
  is issued carrying its grain-key and system columns (plus any drafted user columns), and the
  definition becomes `status=ACTIVE`

#### Scenario: Materialize an incomplete draft is rejected

- **WHEN** `POST /v1/tables/{name}/materialize` is called on a source with no columns or an empty
  `ordering_key`, or on an enrichment with no `grain_key`
- **THEN** the request is rejected with HTTP 422, no ClickHouse `CREATE TABLE` is issued, and the
  definition remains `status=PENDING`

#### Scenario: Materialize an already-materialized table is rejected

- **WHEN** `POST /v1/tables/{name}/materialize` is called on an `ACTIVE` table
- **THEN** the request is rejected with HTTP 409

#### Scenario: Materialization ClickHouse failure marks the table FAILED

- **WHEN** the ClickHouse `CREATE TABLE` fails during materialization
- **THEN** the system issues a best-effort `DROP TABLE IF EXISTS`, sets the definition to
  `status=FAILED`, and the response is an error using the `ErrorView` contract with no raw ClickHouse
  internals

## MODIFIED Requirements

### Requirement: Table definition catalog

The system SHALL maintain a Postgres-backed catalog of table definitions, each carrying a `name`
(the catalog key, equal to the ClickHouse table name), a `description`, a `type` ∈ {`source`,
`enrichment`, `aggregation`}, and a `status` ∈ {`PENDING`, `ACTIVE`, `FAILED`} (a create records the
definition as `PENDING` — identity recorded, **not yet materialized** in ClickHouse; the table is
materialized by an explicit `POST /v1/tables/{name}/materialize` that flips it to `ACTIVE`, or records
`FAILED` on a materialization error; `PENDING` is a durable state that persists while its draft schema
is edited, and only an `ACTIVE` definition is queryable or writable). `FAILED` SHALL be produced only
by a failed materialization and SHALL NOT be entered from `ACTIVE`; `PENDING` and `FAILED` are the
un-materialized states (no confirmed ClickHouse table) and — because row writes require `ACTIVE` — a
`FAILED` table holds no user data, making it safe to re-draft and re-materialize. The definition also
carries a `system` boolean flag
(default `false`) marking the definition as owned/managed by ADAS and therefore not modifiable through
the API (see "System-owned table protection"). A `source` definition SHALL additionally carry an
optional `ordering_key` and an optional `partition_by`; an `enrichment` definition SHALL additionally
carry the `source_table` it enriches (required at create and immutable), and an optional `grain_key`
and `cardinality`. The physical-schema fields (`columns`, and a source's `ordering_key`/`partition_by`
or an enrichment's `grain_key`/`cardinality`) MAY be unset on a `PENDING` definition and are populated
through the draft-schema resource (`PUT /v1/tables/{name}/schema`). A table addressed in the API path
`/v1/tables/{table_id}` SHALL be identified by its `name`. The catalog SHALL be the source of truth for
which tables exist and how their columns are exposed; it SHALL replace the hardcoded
`DialUsageLogSchema` and the throwaway `metadata` table.

#### Scenario: dial_usage_log is present after migration

- **WHEN** the catalog is read after the table-catalog migration runs
- **THEN** it contains `dial_usage_log` as a `source` with `status=ACTIVE` and its column mapping
  (the backfilled row carries its reserved `_`-prefixed system columns and its `array` column
  `execution_path` exempt from the create/PATCH validator), and the previous `metadata` table and
  `/v1/metadata` endpoints no longer exist

### Requirement: System-owned table protection

The system SHALL support a table-level `system` boolean flag on each table definition marking it as
owned and managed by ADAS itself (materialized out of band and, for the seeded sources, populated by
the `data-sync` ingest job writing directly to ClickHouse). The flag SHALL be server-controlled
catalog metadata: it SHALL be set only by database migration, SHALL default to `false` for every
definition, and SHALL NOT be settable, clearable, or otherwise mutable through any `/v1/tables`
request. `POST /v1/tables` SHALL ignore any client-supplied `system` value and always persist a
newly created table as `system=false`; no `/v1/tables` update or schema operation SHALL ever change
`system`. The two seeded source tables `dial_usage_log` and `rate_analytics` SHALL be `system=true`
after migration.

A `system` table SHALL be fully readable, listable, and queryable, but SHALL reject every modifying
`/v1/tables` operation — `PUT /v1/tables/{name}` (description/tag_order), `PUT /v1/tables/{name}/schema`
(draft schema), `POST /v1/tables/{name}/materialize`, `PATCH /v1/tables/{name}/schema`,
`DELETE /v1/tables/{name}`, and `POST /v1/tables/{name}/rows` — with HTTP 409 and the shared
`ErrorView` contract using a stable `error` code identifying the table as system-owned, without
performing the operation or issuing any ClickHouse DDL or write. The check SHALL apply regardless of
caller role (a `FULL_ADMIN` is refused just as any other caller is).

#### Scenario: Seeded tables are system after migration

- **WHEN** the catalog is read after the system-table-flag migration runs
- **THEN** `dial_usage_log` and `rate_analytics` carry `system=true`, and every other definition
  carries `system=false`

#### Scenario: Create never sets system

- **WHEN** `POST /v1/tables` is called, including with a body that attempts to supply `system=true`
- **THEN** the created definition is persisted with `system=false`

#### Scenario: Update of a system table is rejected

- **WHEN** `PUT /v1/tables/{name}` is called for a `system` table
- **THEN** the request is rejected with HTTP 409 using the `ErrorView` contract with the
  system-owned `error` code, and nothing is changed

#### Scenario: Draft-schema or materialize of a system table is rejected

- **WHEN** `PUT /v1/tables/{name}/schema` or `POST /v1/tables/{name}/materialize` is called for a
  `system` table
- **THEN** the request is rejected with HTTP 409, no draft is stored and no ClickHouse DDL is issued

#### Scenario: Schema PATCH of a system table is rejected

- **WHEN** `PATCH /v1/tables/{name}/schema` is called for a `system` table
- **THEN** the request is rejected with HTTP 409, no ClickHouse `ALTER` is issued, and the column
  mapping is unchanged

#### Scenario: Delete of a system table is rejected

- **WHEN** `DELETE /v1/tables/{name}` is called for a `system` table
- **THEN** the request is rejected with HTTP 409, no `DROP TABLE` is issued, and the catalog rows
  remain

#### Scenario: Row write to a system table is rejected

- **WHEN** `POST /v1/tables/{name}/rows` is called for a `system` table
- **THEN** the request is rejected with HTTP 409, no rows are inserted, and no ClickHouse write is
  issued

#### Scenario: System table stays readable and queryable

- **WHEN** `GET /v1/tables/{name}`, `GET /v1/tables`, or a query under `/v1/queries` targets a
  `system` table
- **THEN** the request succeeds exactly as for a non-system table (the flag restricts only the
  modifying `/v1/tables` operations)

### Requirement: Create a source table

The system SHALL create a `source` table at `POST /v1/tables` from `{name, type:"source",
description?, tag_order?}` by persisting the identity as `status=PENDING` — recorded, **not yet
materialized** in ClickHouse — and returning HTTP 201 with the created definition in `status=PENDING`.
Only `name` and `type` SHALL be required. Create SHALL be identity-only: the request SHALL NOT accept
`columns` or any physical key (`ordering_key`, `partition_by`) — a body carrying `columns` or a
physical key SHALL be rejected with HTTP 422 (the physical schema is defined via
`PUT /v1/tables/{name}/schema`). `POST /v1/tables` SHALL NOT issue any ClickHouse DDL.

#### Scenario: Create a source records identity only

- **WHEN** `POST /v1/tables` is called with `{name, type:"source"}` (optionally `description`/`tag_order`)
- **THEN** the response is 201 with `status=PENDING`, no column mapping is written, and no ClickHouse
  `CREATE TABLE` is issued

#### Scenario: Create with a physical schema field is rejected

- **WHEN** `POST /v1/tables` is called with a `columns`, `ordering_key`, or `partition_by` member
- **THEN** the request is rejected with HTTP 422 (physical schema is defined via `PUT .../schema`)

#### Scenario: Duplicate name is rejected

- **WHEN** `POST /v1/tables` is called with a `name` that already exists in the catalog
- **THEN** the request is rejected with HTTP 409

### Requirement: Create an enrichment table

The system SHALL create an `enrichment` table at `POST /v1/tables` from `{name, type:"enrichment",
source_table, description?, tag_order?}`. The `source_table` SHALL be REQUIRED at create, SHALL name an
existing `type=source` definition, and SHALL be immutable (it defines what the enrichment enriches and
is never modifiable). Create SHALL be identity-only: the request SHALL NOT accept `columns` or any
physical key (`grain_key`, `cardinality`) — a body carrying one SHALL be rejected with HTTP 422 (the
physical schema, including the grain key, is defined via `PUT /v1/tables/{name}/schema`). The system
SHALL persist the identity as `status=PENDING` and return HTTP 201; it SHALL NOT issue any ClickHouse
DDL at create.

#### Scenario: Create an enrichment records identity and source

- **WHEN** `POST /v1/tables` is called with `type:"enrichment"` and a `source_table` naming an existing
  source
- **THEN** the response is 201 with `status=PENDING`, and no ClickHouse `CREATE TABLE` is issued

#### Scenario: Enrichment without a valid source is rejected

- **WHEN** `POST /v1/tables` is called with `type:"enrichment"` and a missing or non-`source`
  `source_table`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Enrichment create with a physical schema field is rejected

- **WHEN** `POST /v1/tables` is called with `type:"enrichment"` and a `columns`, `grain_key`, or
  `cardinality` member
- **THEN** the request is rejected with HTTP 422 (physical schema is defined via `PUT .../schema`)

### Requirement: Per-table mutations honor role-based access

The per-table mutating endpoints SHALL be authorized by `FULL_ADMIN` or by a matching per-table access
role as defined by the `table-access-control` capability: row writes (`POST /v1/tables/{name}/rows`)
require a `write` role, and the schema/metadata mutations — draft schema (`PUT /v1/tables/{name}/schema`),
materialize (`POST /v1/tables/{name}/materialize`), live schema PATCH (`PATCH /v1/tables/{name}/schema`),
and definition update (`PUT /v1/tables/{name}`) — require a `modify` role. Table create
(`POST /v1/tables`) and delete (`DELETE /v1/tables/{name}`) SHALL remain `FULL_ADMIN`-only and SHALL
NOT be authorizable by any access role. `system`-owned table protection SHALL take precedence: a
`system` table SHALL reject every modifying operation regardless of access roles.

#### Scenario: Access role authorizes a row write

- **GIVEN** a non-system table whose `write` access list contains the caller's provider role
- **WHEN** the caller inserts rows
- **THEN** the write succeeds

#### Scenario: A modify role authorizes draft schema and materialize

- **GIVEN** a non-system `PENDING` table whose `modify` access list contains the caller's provider role
- **WHEN** the caller `PUT`s its draft schema and then `POST`s materialize
- **THEN** both are authorized and processed

#### Scenario: Delete is never authorized by an access role

- **WHEN** a non-`FULL_ADMIN` caller holding a `modify` access role deletes the table
- **THEN** the service responds `403 forbidden`

#### Scenario: System table rejects modification despite access roles

- **GIVEN** a `system` table with a `write` access role for the caller's provider role
- **WHEN** the caller inserts rows
- **THEN** the service rejects the modification (system-owned protection)

### Requirement: Update a table definition

The system SHALL allow updating a table definition's catalog metadata — its `description` and its
`tag_order` (per-table tag ordering) — via `PUT /v1/tables/{name}`, in any status (`PENDING` or
`ACTIVE`). The `name`, `type`, and all physical-schema fields (`ordering_key`, `partition_by`,
`source_table`, `grain_key`, `cardinality`, and `columns`) SHALL NOT be mutable through this endpoint;
the physical schema is changed via the schema resource (`PUT`/`PATCH .../schema`). `tag_order` SHALL
follow merge-patch semantics: **absent or null** leaves it unchanged, an **empty array** clears it, and
a **non-empty array** replaces it. In `oidc` mode this endpoint SHALL be authorized by `FULL_ADMIN` or
a matching `modify` access role on the table. Updating an unknown definition SHALL return 404.

#### Scenario: Description is updated

- **WHEN** an update request changes the `description` of an existing definition
- **THEN** the response is 200 and the `description` changes

#### Scenario: Tag order is updated

- **WHEN** an update request supplies a non-empty `tag_order`, then a later request supplies `[]`
- **THEN** the first sets the ordering and the second clears it, with no ClickHouse DDL issued

#### Scenario: Physical-schema fields are not mutable here

- **WHEN** an update request attempts to change `name`, `type`, or any physical-schema field
- **THEN** those fields are not changed (only `description`/`tag_order` are ever mutated here)

#### Scenario: A modify access role authorizes a non-admin update

- **WHEN** a non-`FULL_ADMIN` caller holding a matching `modify` access role on the table updates its
  description or tag order
- **THEN** the update is authorized and processed

### Requirement: Modify table schema via PATCH

The system SHALL evolve a **materialized** table's columns at `PATCH /v1/tables/{name}/schema` from a
body carrying `add`, `drop`, `rename`, and `update` operations. The endpoint SHALL be allowed only
while the table's `status` is `ACTIVE`; on a `PENDING` or `FAILED` (not-yet-materialized) table it
SHALL be rejected with HTTP 409 (the draft schema is edited via `PUT /v1/tables/{name}/schema` and
committed via `POST /v1/tables/{name}/materialize`). The physical keys (`ordering_key`, `partition_by`,
`grain_key`, `cardinality`) are frozen after materialization, and the table's tag ordering
(`tag_order`) is table catalog metadata set via `PUT /v1/tables/{name}`; none of these SHALL be members
of this body — a physical-key or `tag_order` member SHALL be rejected with HTTP 422 (this rejection
precedes the materialization-state check, so it applies regardless of the table's status).

The structural operations (`add`/`drop`/`rename`) SHALL each be applied as an immediate ClickHouse
`ALTER` (`ADD`/`DROP`/`RENAME COLUMN`) using the writer credential and update the Postgres column
mapping in the same operation. The `update` operation SHALL adjust an existing column's catalog-only
metadata — its `tag`, `display_name`, `sensitive` flag, and `description` — and SHALL NOT issue any
ClickHouse `ALTER`. Each `update` entry declares `{name, tag?, display_name?, sensitive?, description?}`,
keyed by the column's current exposed name, and SHALL follow merge-patch semantics per field: a
metadata field that is **absent or null** SHALL leave that column attribute unchanged; a string field
(`tag`, `display_name`, `description`) that is **present and blank** SHALL clear that attribute; a
string field that is **present and non-blank** SHALL set it (trimmed); and `sensitive`, when
**present**, SHALL set the flag to the supplied boolean. A single `update` entry MAY change several
metadata fields at once. Each `add` declares `{source_name, name, type, nullable?, description,
display_name?, tag?, sensitive?}` (`sensitive` defaults to false when omitted).

Operations SHALL be applied `drop` → `rename` → `add` → `update`, so an `update` entry MAY target a
just-added or just-renamed column by its resulting exposed name. A column's `display_name`, `tag`,
`sensitive` flag, and `description` SHALL all be preserved across a `rename`. Schema modification SHALL
be breaking and immediate (a drop or rename takes effect at once). A ClickHouse `ALTER` failure SHALL
leave the table `ACTIVE` (surfaced via the `ErrorView` contract, status unchanged) and SHALL NOT
transition it to `FAILED` — so a materialized table that holds data never falls into the draftable
`PUT .../schema` path; the caller retries via `PATCH`. `ALTER`s are applied per operation and are not
transactional, so a mid-sequence failure MAY leave earlier operations applied. In `oidc` mode this
endpoint SHALL be authorized by `FULL_ADMIN` or a matching `modify` access role on the table. The system SHALL reject:
a type-change of an existing column (422); a drop or rename of a system column (reserved `_`-prefix),
the grain key, or an ordering-key column (422); an `update` of a column that is not an exposed column of
the table after the request's own add/drop/rename operations are applied (422); a duplicate `update` of
the same column within one request (422); and any operation on a table not in the catalog (404).
Neither the `update` operation carries a column mutability restriction — system, grain, and
ordering-key columns MAY be tagged, given a display name, marked sensitive, or described.

#### Scenario: Schema PATCH before materialization is rejected

- **WHEN** `PATCH /v1/tables/{name}/schema` is called on a `PENDING` or `FAILED` table
- **THEN** the request is rejected with HTTP 409 (edit the draft via `PUT .../schema` and materialize
  first)

#### Scenario: A key member in a PATCH is rejected

- **WHEN** `PATCH /v1/tables/{name}/schema` carries `ordering_key`, `partition_by`, `grain_key`, or
  `cardinality`
- **THEN** the request is rejected with HTTP 422 (physical keys are frozen after materialization)

#### Scenario: A tag_order member in a PATCH is rejected

- **WHEN** `PATCH /v1/tables/{name}/schema` carries a `tag_order` member
- **THEN** the request is rejected with HTTP 422 (tag ordering is table metadata, set via
  `PUT /v1/tables/{name}`)

#### Scenario: A failed live ALTER leaves the table ACTIVE

- **WHEN** a `PATCH /v1/tables/{name}/schema` operation's ClickHouse `ALTER` fails on an `ACTIVE` table
  that holds data
- **THEN** the response is an `ErrorView` error, the table's `status` remains `ACTIVE` (it does not
  become `FAILED`), and `PUT /v1/tables/{name}/schema` / `POST /v1/tables/{name}/materialize` continue
  to reject it with HTTP 409

#### Scenario: Add columns to a materialized enrichment

- **WHEN** `PATCH /v1/tables/{name}/schema` adds valid columns to an `ACTIVE` enrichment
- **THEN** an `ALTER TABLE ... ADD COLUMN` is issued per column, the column mapping is updated, and
  the response reflects the new columns

#### Scenario: Drop and rename apply immediately

- **WHEN** `PATCH /v1/tables/{name}/schema` drops one column and renames another on an `ACTIVE` table
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

- **WHEN** a non-`FULL_ADMIN` caller holding a matching `modify` access role on an `ACTIVE` table
  `PATCH`es its schema
- **THEN** the modification is authorized and processed

### Requirement: Schema validation

The system SHALL validate every physical-schema definition before persisting or issuing DDL — the
draft `PUT /v1/tables/{name}/schema`, the live `PATCH /v1/tables/{name}/schema`, and materialization —
rejecting invalid requests with HTTP 422. Validation SHALL cover: table and column names match the
snake_case identifier grammar `^[a-z][a-z0-9_]*$` (≤ 64 chars) and do not use the reserved `_`-prefix
(which is for system columns); a source's `ordering_key`/`partition_by` reference declared columns (in
a draft `PUT`, checked against the draft's `columns`; a source's resolved `ordering_key` being non-empty
is enforced at materialization, since a draft MAY be incomplete); an enrichment's `grain_key` is a
declared column of its `source_table`; exposed column `name`s are unique within a single table
(cross-table collisions across a source and its enrichments are NOT rejected — enrichment columns are
surfaced under table-qualified flat names, so a source column and an enrichment column, or two
enrichments, may share a bare `name`); `type=array` is NOT accepted in a user-declared schema (the type
vocabulary excludes `array` in this phase; `object` maps to ClickHouse `JSON`); a column's optional
`tag` is a free-form label of at most 64 characters (no identifier grammar imposed) — a blank tag is
treated as absent, and tags need NOT be unique within a table; a column's optional `display_name` is
free-form text of at most 128 characters (no identifier grammar imposed) — a blank display name is
treated as absent, and display names need NOT be unique within a table; and a column's optional
`description` is free-form text of at most 1024 characters (no identifier grammar imposed) — a blank
description is treated as absent, and descriptions need NOT be unique within a table.

#### Scenario: Non-snake_case or reserved-prefix name is rejected

- **WHEN** a draft `PUT` or a `PATCH` declares a table or column name that is not snake_case or begins
  with `_`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Draft schema without an ordering key is accepted but not materializable

- **WHEN** `PUT /v1/tables/{name}/schema` declares `columns` for a source but no `ordering_key`
- **THEN** the draft is stored (200), and a later `POST .../materialize` is rejected with HTTP 422
  until a non-empty `ordering_key` is drafted

#### Scenario: User-declared array type is rejected

- **WHEN** a draft `PUT` or a `PATCH` declares a column of `type=array`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Column tag longer than the limit is rejected

- **WHEN** a draft `PUT`, `PATCH add`, or `PATCH update` declares a column `tag` longer than 64
  characters
- **THEN** the request is rejected with HTTP 422

#### Scenario: Column display name longer than the limit is rejected

- **WHEN** a draft `PUT`, `PATCH add`, or `PATCH update` declares a column `display_name` longer than
  128 characters
- **THEN** the request is rejected with HTTP 422

#### Scenario: Column description longer than the limit is rejected

- **WHEN** a draft `PUT`, `PATCH add`, or `PATCH update` declares a column `description` longer than
  1024 characters
- **THEN** the request is rejected with HTTP 422

#### Scenario: Duplicate tags within a table are accepted

- **WHEN** a draft `PUT` or `PATCH` results in two columns of the same table carrying the same `tag`
- **THEN** the request is accepted (tags are non-unique identifying labels)

#### Scenario: Duplicate display names within a table are accepted

- **WHEN** a draft `PUT` or `PATCH` results in two columns of the same table carrying the same
  `display_name`
- **THEN** the request is accepted (display names are non-unique)

#### Scenario: Duplicate exposed name within a single table is rejected

- **WHEN** a draft `PUT` or `PATCH` would result in two columns of the same table sharing an exposed
  `name`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Same exposed name across a source and its enrichments is accepted

- **WHEN** an enrichment schema operation exposes a `name` already exposed by its source or a sibling
  enrichment
- **THEN** the operation is accepted, and the columns are surfaced as distinct table-qualified flat
  fields in the source's flattened query schema

### Requirement: Per-table tag ordering

The system SHALL maintain an optional per-table **tag ordering**: an ordered list of tag names that
tells discovery consumers in what order to display tag groups. The ordering SHALL be catalog-only
metadata stored in PostgreSQL as a nullable array column (`tag_order TEXT[]`) on the table's
`table_definition` row (removed with the row on delete) and SHALL never issue ClickHouse DDL. A
table with no ordering set SHALL have a `NULL` column and behave exactly as before this capability
existed. Consistent with the service's `NON_NULL` serialization, "not set" SHALL be omitted (an
absent member, read as null by consumers) on the wire — never rendered as an empty array.

The ordering SHALL be an independent display hint over tag names, deliberately decoupled from the
tags currently carried by the table's columns:

- Entries MAY reference tag names not currently present on any column (pre-setting an order before
  a retag, or referencing enrichment tags, is allowed).
- Column operations (`add`, `drop`, `rename`, and metadata `update`) SHALL NOT mutate the stored
  ordering — a column retagged to an unlisted tag or a new column with a brand-new tag simply
  yields an unlisted tag, which the discovery surface sorts after the listed groups (see the
  `query-schema-discovery` capability's "Tag-ordered field sorting").
- When an ordering is set, both the `/v1/tables` read surface (the `columns` array) and the
  discovery surface (see the `query-schema-discovery` capability's "Tag-ordered field sorting")
  SHALL return their column/field list sorted by the same tag-group rules; when no ordering is set,
  both SHALL preserve the pre-existing declared (ordinal) order. The sort is presentation-only —
  the stored column `ordinal` (and thus physical ClickHouse column order) SHALL NOT change.

The tag ordering SHALL be table catalog metadata, set through the table resource — `POST /v1/tables`
SHALL accept an optional top-level `tag_order` array of tag names for the new table, and
`PUT /v1/tables/{name}` SHALL accept an optional `tag_order` member following the merge-patch
convention (absent or `null` leaves the ordering unchanged, an empty array `[]` clears it, and a
non-empty array replaces it). It SHALL NOT be a member of the schema endpoints
(`PUT`/`PATCH .../schema`), which carry physical schema and column operations only.
`GET /v1/tables/{name}` SHALL return the table's `tag_order` (the ordered array, or an absent/null
member when not set) and, when an ordering is set, its `columns` array sorted by the tag-group rules
(ordinal order preserved when not set).

Validation (rejections use HTTP 422 and the `ErrorView` contract): each entry SHALL be trimmed and
SHALL be non-blank after trimming; each entry SHALL be at most 64 characters (the tag length
limit); entries SHALL be unique within the list after trimming; the list SHALL contain at most 100
entries. Entries SHALL NOT be validated against the tags currently present on columns.

The catalog migration SHALL seed a taxonomy tag ordering for the seeded `system` tables
(`dial_usage_log`, `rate_analytics`), since system tables reject mutation and can only receive
their ordering via migration.

#### Scenario: Tag order declared at create is returned

- **WHEN** `POST /v1/tables` creates a source with `tag_order: ["identifier", "dimension"]`, then
  `GET /v1/tables/{name}` is called
- **THEN** the response carries `tag_order: ["identifier", "dimension"]`

#### Scenario: Tag order updated via the table resource

- **WHEN** `PUT /v1/tables/{name}` carries `tag_order: ["cost", "token-usage"]`, then a later
  `PUT /v1/tables/{name}` carries `tag_order: []`
- **THEN** after the first request the table's `tag_order` is exactly `["cost", "token-usage"]`
  (any previous ordering replaced), after the second it is cleared and reads omit `tag_order`
  (absent, read as null) — neither request issues a ClickHouse `ALTER`

#### Scenario: Table without an ordering omits tag_order

- **WHEN** `GET /v1/tables/{name}` is called for a table that never had a tag ordering set (or
  whose ordering was cleared)
- **THEN** the response omits the `tag_order` member (absent, read as null by consumers)

#### Scenario: Columns are sorted by the ordering on the read surface

- **WHEN** a table has `tag_order: ["identifier", "cost"]` and columns tagged `cost`,
  `identifier`, `token-usage` (unlisted), plus an untagged column, and `GET /v1/tables/{name}` is
  called
- **THEN** the `columns` array lists the `identifier` column(s) first, then `cost`, then the
  unlisted `token-usage`, then the untagged column — each group sorted alphabetically by exposed
  `name` — and the stored column `ordinal` values are unchanged

#### Scenario: Columns keep ordinal order when no ordering is set

- **WHEN** `GET /v1/tables/{name}` is called for a table with no `tag_order`
- **THEN** the `columns` array is in declared (ordinal) order, exactly as before this capability

#### Scenario: Ordering survives column-metadata and new-tag churn

- **WHEN** a table has `tag_order` set, and subsequent `PATCH` requests `update` a column's tag to
  one not in the list, `update` the only column of a listed tag to a different tag, and `add` a
  column with a brand-new tag
- **THEN** the stored `tag_order` is unchanged by all of these operations

#### Scenario: Ordering entry for a non-existent tag is accepted

- **WHEN** a tag ordering is set that includes a tag name carried by no current column
- **THEN** the request is accepted and the entry is stored and returned verbatim

#### Scenario: Blank or over-long ordering entry is rejected

- **WHEN** a tag ordering is declared (at create or via `PUT /v1/tables/{name}`) containing a blank
  entry or an entry longer than 64 characters
- **THEN** the request is rejected with HTTP 422

#### Scenario: Duplicate ordering entries are rejected

- **WHEN** a tag ordering is declared containing the same tag name twice (after trimming)
- **THEN** the request is rejected with HTTP 422

#### Scenario: Oversized ordering list is rejected

- **WHEN** a tag ordering is declared with more than 100 entries
- **THEN** the request is rejected with HTTP 422

#### Scenario: Seeded system tables carry a taxonomy ordering

- **WHEN** the catalog is read after the tag-order migration runs
- **THEN** `dial_usage_log` carries a non-null `tag_order` listing its taxonomy tags (e.g.
  `identifier` first) and `rate_analytics` carries a non-null `tag_order` over the tags it has

#### Scenario: Deleting a table removes its ordering

- **WHEN** `DELETE /v1/tables/{name}` removes a table that had a tag ordering
- **THEN** the ordering is removed with the table's catalog row
