## MODIFIED Requirements

### Requirement: Read and list table definitions

The system SHALL expose `GET /v1/tables/{name}` returning a single definition including its current
`columns` (404 if absent) and `GET /v1/tables` returning all definitions — sources, enrichments, and
aggregations — as a response object (not a bare array). Listing SHALL use a stable order. System
columns (reserved `_`-prefix) and the hidden grain key SHALL NOT appear in the exposed `columns`. Each
exposed column SHALL carry its optional user-friendly `display_name` (absent/null when unset), its
optional identifying `tag` (absent/null when unset), and its `sensitive` flag (default `false` when
unset). Each returned definition SHALL carry its `system` flag (default `false` when unset) so
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

### Requirement: Modify table schema via PATCH

The system SHALL evolve a table's columns at `PATCH /v1/tables/{name}/schema` from a body carrying
`add`, `drop`, `rename`, `retag`, `set_display_name`, and `set_sensitive` operations. The structural
operations (`add`/`drop`/`rename`) SHALL each be applied as an immediate ClickHouse `ALTER`
(`ADD`/`DROP`/`RENAME COLUMN`) using the writer credential and update the Postgres column mapping in
the same operation. The `retag` operation SHALL set (or clear, when its `tag` is null or blank) the
identifying `tag` of an existing column; it is catalog-only metadata and SHALL NOT issue any
ClickHouse `ALTER`. The `set_display_name` operation SHALL set (or clear, when its `display_name` is
null or blank) the user-friendly `display_name` of an existing column; like `retag` it is catalog-only
metadata and SHALL NOT issue any ClickHouse `ALTER`. The `set_sensitive` operation SHALL set the
`sensitive` flag of an existing column to the supplied boolean; like `retag` it is catalog-only
metadata and SHALL NOT issue any ClickHouse `ALTER`. Each `add` declares
`{source_name, name, type, nullable?, description, display_name?, tag?, sensitive?}`
(`sensitive` defaults to false when omitted); each `retag` declares `{name, tag}`, each
`set_display_name` declares `{name, display_name}`, and each `set_sensitive` declares
`{name, sensitive}`, all keyed by the column's current exposed name. Operations SHALL be applied
`drop` → `rename` → `add` → `retag` → `set_display_name` → `set_sensitive`, so a `retag`,
`set_display_name`, or `set_sensitive` MAY target a just-added or just-renamed column by its resulting
exposed name. A column's `display_name`, `tag`, and `sensitive` flag SHALL all be preserved across a
`rename`. Schema
modification SHALL be breaking and immediate (a drop or rename takes effect at once). In `oidc` mode
this endpoint SHALL be authorized by `FULL_ADMIN` or a matching `modify` access role on the table (see
the `table-access-control` capability). The system SHALL reject: a type-change of an existing column
(422); a drop or rename of a system column (reserved `_`-prefix) or the grain key (422); a `retag`,
`set_display_name`, or `set_sensitive` of an unknown column (422); and any operation on a table not in
the catalog (404). None of `retag`, `set_display_name`, or `set_sensitive` carries a mutability
restriction — system, grain, and ordering-key columns MAY be tagged, given a display name, or marked
sensitive.

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

#### Scenario: Set-display-name sets, clears, and survives a rename

- **WHEN** a `PATCH` sets an existing column's display name, then a later `PATCH` renames that column,
  then a final `PATCH` sets it with a blank display name
- **THEN** the column's exposed `display_name` is set, is preserved under the rename, and is then cleared —
  none of which issues a ClickHouse `ALTER`

#### Scenario: Set-sensitive toggles the flag and survives a rename

- **WHEN** a `PATCH` marks an existing column `sensitive: true`, then a later `PATCH` renames that
  column, then a final `PATCH` sets it `sensitive: false`
- **THEN** the column's `sensitive` flag is set, is preserved under the rename, and is then cleared —
  none of which issues a ClickHouse `ALTER`

#### Scenario: Set-sensitive of an unknown column is rejected

- **WHEN** a `PATCH` sets the `sensitive` flag of a `name` that is not an exposed column of the table
  (after the request's own add/drop/rename operations are applied)
- **THEN** the request is rejected with HTTP 422

#### Scenario: Retag of an unknown column is rejected

- **WHEN** a `PATCH` retags a `name` that is not an exposed column of the table (after the request's
  own add/drop/rename operations are applied)
- **THEN** the request is rejected with HTTP 422

#### Scenario: Set-display-name of an unknown column is rejected

- **WHEN** a `PATCH` sets the display name of a `name` that is not an exposed column of the table (after the request's
  own add/drop/rename operations are applied)
- **THEN** the request is rejected with HTTP 422

#### Scenario: A modify access role authorizes a non-admin schema change

- **WHEN** a non-`FULL_ADMIN` caller holding a matching `modify` access role on the table `PATCH`es its schema
- **THEN** the modification is authorized and processed

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
`JSON`); a column's optional `tag` is a free-form label of at most 64 characters (no identifier
grammar imposed) — a blank tag is treated as absent, and tags need NOT be unique within a table; and a
column's optional `display_name` is free-form text of at most 128 characters (no identifier grammar
imposed) — a blank display name is treated as absent, and display names need NOT be unique within a table.

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

#### Scenario: Column display name longer than the limit is rejected

- **WHEN** a create, `PATCH add`, or `PATCH set_display_name` declares a column `display_name` longer
  than 128 characters
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
