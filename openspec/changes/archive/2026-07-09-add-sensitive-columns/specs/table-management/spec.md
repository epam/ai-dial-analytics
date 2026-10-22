## MODIFIED Requirements

### Requirement: Read and list table definitions

The system SHALL expose `GET /v1/tables/{name}` returning a single definition including its current
`columns` (404 if absent) and `GET /v1/tables` returning all definitions — sources, enrichments, and
aggregations — as a response object (not a bare array). Listing SHALL use a stable order. System
columns (reserved `_`-prefix) and the hidden grain key SHALL NOT appear in the exposed `columns`. Each
exposed column SHALL carry its optional identifying `tag` (absent/null when unset) and its `sensitive`
flag (default `false` when unset). The table-management read surface is not column-role-filtered — it
returns every column with its `sensitive` flag so operators can audit and manage sensitivity;
role-based hiding of sensitive columns applies to the query/discovery surface (see
[query-schema-discovery](../query-schema-discovery/spec.md)), not to `/v1/tables`.

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
`add`, `drop`, `rename`, `retag`, and `set_sensitive` operations. The structural operations
(`add`/`drop`/`rename`) SHALL each be applied as an immediate ClickHouse `ALTER`
(`ADD`/`DROP`/`RENAME COLUMN`) using the writer credential and update the Postgres column mapping in
the same operation. The `retag` operation SHALL set (or clear, when its `tag` is null or blank) the
identifying `tag` of an existing column; it is catalog-only metadata and SHALL NOT issue any
ClickHouse `ALTER`. The `set_sensitive` operation SHALL set the `sensitive` flag of an existing column
to the supplied boolean; like `retag` it is catalog-only metadata and SHALL NOT issue any ClickHouse
`ALTER`. Each `add` declares `{source_name, name, type, nullable?, description, tag?, sensitive?}`
(`sensitive` defaults to false when omitted); each `retag` declares `{name, tag}` and each
`set_sensitive` declares `{name, sensitive}`, both keyed by the column's current exposed name.
Operations SHALL be applied `drop` → `rename` → `add` → `retag` → `set_sensitive`, so a `retag` or
`set_sensitive` MAY target a just-added or just-renamed column by its resulting exposed name. A
column's `tag` and `sensitive` flag SHALL both be preserved across a `rename`. Schema modification
SHALL be breaking and immediate (a drop or rename takes effect at once). The system SHALL reject: a
type-change of an existing column (422); a drop or rename of a system column (reserved `_`-prefix) or
the grain key (422); a `retag` or `set_sensitive` of an unknown column (422); and any operation on a
table not in the catalog (404). Neither `retag` nor `set_sensitive` carries a mutability restriction —
system, grain, and ordering-key columns MAY be tagged or marked sensitive.

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
