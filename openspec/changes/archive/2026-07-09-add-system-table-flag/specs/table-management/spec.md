## ADDED Requirements

### Requirement: System-owned table protection

The system SHALL support a table-level `system` boolean flag on each table definition marking it as
owned and managed by ADAS itself (materialized out of band and, for the seeded sources, populated by
the `data-sync` ingest job writing directly to ClickHouse). The flag SHALL be server-controlled
catalog metadata: it SHALL be set only by database migration, SHALL default to `false` for every
definition, and SHALL NOT be settable, clearable, or otherwise mutable through any `/v1/tables`
request. `POST /v1/tables` SHALL ignore any client-supplied `system` value and always persist a
newly created table as `system=false`; `PUT /v1/tables/{name}` SHALL never change `system`. The two
seeded source tables `dial_usage_log` and `rate_analytics` SHALL be `system=true` after migration.

A `system` table SHALL be fully readable, listable, and queryable, but SHALL reject every modifying
`/v1/tables` operation — `PUT /v1/tables/{name}` (description), `PATCH /v1/tables/{name}/schema`,
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
  system-owned `error` code, and the description is not changed

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

## MODIFIED Requirements

### Requirement: Table definition catalog

The system SHALL maintain a Postgres-backed catalog of table definitions, each carrying a `name`
(the catalog key, equal to the ClickHouse table name), a `description`, a `type` ∈ {`source`,
`enrichment`, `aggregation`}, a `status` ∈ {`PENDING`, `ACTIVE`, `FAILED`} (a create records
`PENDING` intent, flips to `ACTIVE` once the ClickHouse table is confirmed, or `FAILED` on error; only
an `ACTIVE` definition is queryable or writable), and a `system` boolean flag (default `false`)
marking the definition as owned/managed by ADAS and therefore not modifiable through the API (see
"System-owned table protection"). A `source` definition SHALL additionally carry an `ordering_key`
and an optional `partition_by`; an `enrichment` definition SHALL additionally carry the
`source_table` it enriches, a `grain_key`, and a `cardinality`. A table addressed in the API path
`/v1/tables/{table_id}` SHALL be identified by its `name`. The catalog SHALL be the source of truth
for which tables exist and how their columns are exposed; it SHALL replace the hardcoded
`DialUsageLogSchema` and the throwaway `metadata` table.

#### Scenario: dial_usage_log is present after migration

- **WHEN** the catalog is read after the table-catalog migration runs
- **THEN** it contains `dial_usage_log` as a `source` with `status=ACTIVE` and its column mapping
  (the backfilled row carries its reserved `_`-prefixed system columns and its `array` column
  `execution_path` exempt from the create/PATCH validator), and the previous `metadata` table and
  `/v1/metadata` endpoints no longer exist

### Requirement: Read and list table definitions

The system SHALL expose `GET /v1/tables/{name}` returning a single definition including its current
`columns` (404 if absent) and `GET /v1/tables` returning all definitions — sources, enrichments, and
aggregations — as a response object (not a bare array). Listing SHALL use a stable order. System
columns (reserved `_`-prefix) and the hidden grain key SHALL NOT appear in the exposed `columns`. Each
exposed column SHALL carry its optional identifying `tag` (absent/null when unset) and its `sensitive`
flag (default `false` when unset). Each returned definition SHALL carry its `system` flag (default
`false` when unset) so operators can see which tables are protected from modification. The
table-management read surface is not column-role-filtered — it returns every column with its
`sensitive` flag so operators can audit and manage sensitivity; role-based hiding of sensitive columns
applies to the query/discovery surface (see
[query-schema-discovery](../query-schema-discovery/spec.md)), not to `/v1/tables`.

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
