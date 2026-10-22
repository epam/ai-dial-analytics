## MODIFIED Requirements

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
fields at once. Each `add` declares `{source_name, name, type, nullable?, description,
display_name?, tag?, sensitive?}` (`sensitive` defaults to false when omitted). Operations SHALL be
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
grammar imposed) — a blank tag is treated as absent, and tags need NOT be unique within a table; a
column's optional `display_name` is free-form text of at most 128 characters (no identifier grammar
imposed) — a blank display name is treated as absent, and display names need NOT be unique within a
table; and a column's optional `description` is free-form text of at most 1024 characters (no
identifier grammar imposed) — a blank description is treated as absent, and descriptions need NOT be
unique within a table.

#### Scenario: Non-snake_case or reserved-prefix name is rejected

- **WHEN** a create or `PATCH` declares a table or column name that is not snake_case or begins with
  `_`
- **THEN** the request is rejected with HTTP 422

#### Scenario: User-declared array type is rejected

- **WHEN** a create or `PATCH` declares a column of `type=array`
- **THEN** the request is rejected with HTTP 422

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
