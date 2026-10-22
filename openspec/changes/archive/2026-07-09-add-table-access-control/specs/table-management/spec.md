## MODIFIED Requirements

### Requirement: Delete a table definition

The system SHALL delete a table definition at `DELETE /v1/tables/{name}`, removing its catalog rows
and issuing `DROP TABLE` in ClickHouse (writer credential), returning HTTP 204. Deleting a table SHALL
also cascade-delete all per-table grants targeting it (see the `table-access-control` capability).
Deleting a `source` that still has enrichments referencing it SHALL be rejected with HTTP 409 until
those enrichments are deleted. Deleting an unknown definition SHALL return 404. This endpoint SHALL
require the `FULL_ADMIN` authority in `oidc` mode; per-table grants SHALL NOT authorize a delete.

#### Scenario: Delete drops the ClickHouse table

- **WHEN** `DELETE /v1/tables/{name}` is called for a table with no dependents
- **THEN** the catalog rows are removed, `DROP TABLE` is issued in ClickHouse, and the response is 204

#### Scenario: Delete of a source with enrichments is blocked

- **WHEN** `DELETE /v1/tables/{name}` is called for a `source` that has enrichments referencing it
- **THEN** the request is rejected with HTTP 409 and no table is dropped

#### Scenario: Delete cascades the table's grants

- **WHEN** `DELETE /v1/tables/{name}` is called for a table that has per-table grants
- **THEN** the table is deleted and all grants targeting it are removed

### Requirement: Write rows into a materialized table

The system SHALL accept row inserts at `POST /v1/tables/{name}/rows` from a body carrying a non-empty
`rows` array, each row a JSON object keyed by physical column name (the `source_name`s, and for an
enrichment its grain key). The system SHALL insert the rows into the table's ClickHouse table using
the writer credential and return the inserted count. In `oidc` mode this endpoint SHALL be authorized
by `FULL_ADMIN` or a matching `WRITE` grant on the table (see the `table-access-control` capability).
It SHALL reject a write to an unknown table (404), a write to a table that is not materialized (409),
and a row that is empty or references a column that is not a physical column of the table (422). This
is the minimal data-write surface for populating sources and enrichments; richer ingest/upsert
semantics are out of scope for this phase.

#### Scenario: Rows are inserted and become queryable

- **WHEN** `POST /v1/tables/{name}/rows` is called for a materialized table with rows keyed by physical
  column name
- **THEN** the rows are inserted via the writer credential, the response reports the inserted count,
  and a subsequent query returns them (enrichment rows resolve into their source via the grain join)

#### Scenario: Write to a non-materialized table is rejected

- **WHEN** `POST /v1/tables/{name}/rows` is called for a table that is not `ACTIVE` (still `PENDING`,
  or `FAILED`)
- **THEN** the request is rejected with HTTP 409

#### Scenario: Write with an unknown column is rejected

- **WHEN** a row references a column that is not a physical column of the table
- **THEN** the request is rejected with HTTP 422

#### Scenario: A WRITE grant authorizes a non-admin write

- **WHEN** a non-`FULL_ADMIN` caller holding a matching `WRITE` grant on the table `POST`s rows
- **THEN** the write is authorized and processed

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
drop or rename takes effect at once). In `oidc` mode this endpoint SHALL be authorized by
`FULL_ADMIN` or a matching `MODIFY` grant on the table (see the `table-access-control` capability).
The system SHALL reject: a type-change of an existing column (422); a drop or rename of a system
column (reserved `_`-prefix) or the grain key (422); a `retag` of an unknown column (422); and any
operation on a table not in the catalog (404). A `retag` carries no mutability restriction — system,
grain, and ordering-key columns MAY be tagged.

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

#### Scenario: A MODIFY grant authorizes a non-admin schema change

- **WHEN** a non-`FULL_ADMIN` caller holding a matching `MODIFY` grant on the table `PATCH`es its schema
- **THEN** the modification is authorized and processed

### Requirement: Update a table definition

The system SHALL allow updating only the `description` of a table definition via
`PUT /v1/tables/{name}`. The `name`, `type`, and all structural fields (`ordering_key`,
`partition_by`, `source_table`, `grain_key`, `cardinality`) SHALL be immutable; column changes go
through the schema endpoint. In `oidc` mode this endpoint SHALL be authorized by `FULL_ADMIN` or a
matching `MODIFY` grant on the table (see the `table-access-control` capability). Updating an unknown
definition SHALL return 404.

#### Scenario: Description is updated

- **WHEN** an update request changes the `description` of an existing definition
- **THEN** the response is 200 and only the `description` changes

#### Scenario: Structural fields are not mutable

- **WHEN** an update request attempts to change `name`, `type`, or any structural field
- **THEN** those fields are not changed and only `description` is ever mutated

#### Scenario: A MODIFY grant authorizes a non-admin description update

- **WHEN** a non-`FULL_ADMIN` caller holding a matching `MODIFY` grant on the table updates its description
- **THEN** the update is authorized and processed
