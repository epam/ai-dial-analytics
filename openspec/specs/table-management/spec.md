# table-management Specification

## Purpose

This capability defines the Postgres-backed catalog of table definitions that ADAS exposes and
materializes in ClickHouse. The catalog is the source of truth for which tables exist, how their
columns are exposed, and how enrichment tables attach to their sources. It supersedes the throwaway
`metadata` store and the hardcoded `DialUsageLogSchema`, and it feeds queryable-entity discovery and
structured-query translation (see [query-schema-discovery](../query-schema-discovery/spec.md) and
[structured-query](../structured-query/spec.md)).

The surface lives under `/v1/tables`: create source and enrichment tables, read and list definitions,
update descriptions, evolve column schema via PATCH, write rows, and delete definitions. Enrichment
columns resolve into a source's flat schema through a single-key grain join with enforced 0..1
cardinality.
## Requirements
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

### Requirement: System-owned table protection

The system SHALL support a table-level `system` boolean flag on each table definition marking it as
owned and managed by ADAS itself (materialized out of band and, for the seeded tables, populated by
the `data-sync` ingest job writing directly to ClickHouse). The flag SHALL be server-controlled
catalog metadata: it SHALL be set only by database migration, SHALL default to `false` for every
definition, and SHALL NOT be settable, clearable, or otherwise mutable through any `/v1/tables`
request. `POST /v1/tables` SHALL ignore any client-supplied `system` value and always persist a
newly created table as `system=false`; no `/v1/tables` update or schema operation SHALL ever change
`system`. "Ignore" SHALL mean *accepted and not honoured*, not *refused*: the flag is echoed by every
table read, so a caller creating a table from one it read back sends it without choosing to, and a
request body that declines fields it does not declare would otherwise reject that body over a value the
server was always going to overwrite. The three seeded tables — the sources `dial_usage_log` and
`rate_analytics`, and the enrichment `dial_usage_log_payload` — SHALL be `system=true` after migration.
The flag is a table facet independent of `type`: it says who owns the definition, not what the
definition is, so a seeded enrichment is protected on exactly the same terms as a seeded source.

A `system` table SHALL be fully readable, listable, and queryable, but SHALL reject every modifying
`/v1/tables` operation — `PUT /v1/tables/{name}` (description/tag_order), `POST /v1/tables/{name}/schema`
(define + materialize), `PATCH /v1/tables/{name}/schema`, `DELETE /v1/tables/{name}`, and
`POST /v1/tables/{name}/rows` — with HTTP 409 and the shared
`ErrorView` contract using a stable `error` code identifying the table as system-owned, without
performing the operation or issuing any ClickHouse DDL or write. The check SHALL apply regardless of
what the caller holds: a caller holding every privilege is refused just as any other caller is. Grant
lists on a `system` table are restricted by **key** rather than refused wholesale — the read-side keys
are storable, `write` and `modify` must stay empty — because the read side is not a modifying operation
(see `table-access-control`).

#### Scenario: Seeded tables are system after migration

- **WHEN** the catalog is read after the system-table-flag migration runs
- **THEN** `dial_usage_log`, `rate_analytics` and `dial_usage_log_payload` carry `system=true`, and
  every other definition carries `system=false`

#### Scenario: Create never sets system

- **WHEN** `POST /v1/tables` is called, including with a body that attempts to supply `system=true`
- **THEN** the created definition is persisted with `system=false`

#### Scenario: Update of a system table is rejected

- **WHEN** `PUT /v1/tables/{name}` is called for a `system` table
- **THEN** the request is rejected with HTTP 409 using the `ErrorView` contract with the
  system-owned `error` code, and nothing is changed

#### Scenario: Defining the schema of a system table is rejected

- **WHEN** `POST /v1/tables/{name}/schema` is called for a `system` table
- **THEN** the request is rejected with HTTP 409, and no schema is stored and no ClickHouse DDL is issued

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
`POST /v1/tables/{name}/schema`). `POST /v1/tables` SHALL NOT issue any ClickHouse DDL.

#### Scenario: Create a source records identity only

- **WHEN** `POST /v1/tables` is called with `{name, type:"source"}` (optionally `description`/`tag_order`)
- **THEN** the response is 201 with `status=PENDING`, no column mapping is written, and no ClickHouse
  `CREATE TABLE` is issued

#### Scenario: Create with a physical schema field is rejected

- **WHEN** `POST /v1/tables` is called with a `columns`, `ordering_key`, or `partition_by` member
- **THEN** the request is rejected with HTTP 422 (physical schema is defined via `POST /v1/tables/{name}/schema`)

#### Scenario: Duplicate name is rejected

- **WHEN** `POST /v1/tables` is called with a `name` that already exists in the catalog
- **THEN** the request is rejected with HTTP 409

### Requirement: Create an enrichment table

The system SHALL create an `enrichment` table at `POST /v1/tables` from `{name, type:"enrichment",
source_table, description?, tag_order?}`. The `source_table` SHALL be REQUIRED at create, SHALL name an
existing `type=source` definition, and SHALL be immutable (it defines what the enrichment enriches and
is never modifiable). Create SHALL be identity-only: the request SHALL NOT accept `columns` or any
physical key (`grain_key`, `cardinality`) — a body carrying one SHALL be rejected with HTTP 422 (the
physical schema, including the grain key, is defined via `POST /v1/tables/{name}/schema`). The system
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
- **THEN** the request is rejected with HTTP 422 (physical schema is defined via `POST /v1/tables/{name}/schema`)

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
when unset), its `sensitive` flag (default `false` when unset), its `heavy` flag (default `false`
when unset — see "Heavy column metadata"), and — for an `array`-typed column —
its `element_type` (absent/null for non-array columns). Each returned definition SHALL
carry its `system` flag (default `false` when unset) so operators can see which tables are protected
from modification, and its `write` discipline so operators can see how a write to an existing key
behaves. Each returned definition SHALL also carry its `identity_column` and
`version_column` — the pair defining a source's total order for the governed incremental scan — each
absent when the definition has none, so a consumer can tell which column identifies a row, which column
carries its version, and therefore whether the table is scannable at all.

The table-management read surface SHALL be filtered at column granularity by the same per-table
clearance as the query surface (see `authentication`). A `sensitive` column SHALL appear in `columns`,
and SHALL be counted by `column_count`, only for a caller holding `TABLE_SENSITIVE` on that table,
globally or by grant, or in `none` security mode. For every other caller it SHALL be omitted as if it
did not exist. There is no redacted placeholder, and `column_count` SHALL still equal the length of the
`columns` array the same caller receives. An operator auditing or managing sensitivity SHALL therefore
hold the clearance. The definition's structural fields (`ordering_key`, `partition_by`, `grain_key`,
`identity_column`, `version_column`, `ttl`) SHALL NOT be filtered: they are returned as stored, even
where they name a column the caller cannot see.

#### Scenario: Get a definition with its columns

- **WHEN** `GET /v1/tables/{name}` is called for an existing definition
- **THEN** the response is 200 with the definition's `name`, `description`, `type`, `write`, `status`,
  `system` flag, structural fields (`ordering_key`/`partition_by` or
  `source_table`/`grain_key`/`cardinality`), its `identity_column`/`version_column` when it has them,
  and its exposed `columns`

#### Scenario: A seeded source reports its scan metadata

- **WHEN** `GET /v1/tables/dial_usage_log` is called
- **THEN** the response carries `identity_column: "event_id"` and `version_column: "_ingested_at"`, the
  values its migration seeded

#### Scenario: A definition without scan metadata omits both fields

- **WHEN** `GET /v1/tables/{name}` is called for a definition that declares neither column
- **THEN** `identity_column` and `version_column` are absent from the response, and the read succeeds

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
- **THEN** for a caller holding `TABLE_SENSITIVE` on the table, that column in the response carries
  `sensitive: true`, while columns that did not declare the flag carry `sensitive: false`

#### Scenario: A caller without the clearance does not see the sensitive column

- **GIVEN** a table with three columns, one of them `sensitive`
- **WHEN** a caller holding `TABLE_GET` but not `TABLE_SENSITIVE` on it calls `GET /v1/tables/{name}`
  and `GET /v1/tables`
- **THEN** the single-get `columns` array lists the two non-sensitive columns only, and the list item
  carries `column_count: 2`
- **AND WHEN** a caller holding both calls the same endpoints
- **THEN** it sees all three columns and `column_count: 3`

#### Scenario: Column heavy flag declared in the schema is returned

- **WHEN** a source's schema (`POST /v1/tables/{name}/schema`) declares a column with `heavy: true`
  and the table is materialized, then `GET /v1/tables/{name}` is called
- **THEN** that column in the response carries `heavy: true`, while columns that did not declare the
  flag carry `heavy: false`

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

### Requirement: Per-table mutations honor role-based access

The per-table mutating endpoints SHALL be authorized by the privilege each requires, held globally or
granted on that table as defined by the `table-access-control` capability: row writes
(`POST /v1/tables/{name}/rows`) require `TABLE_WRITE`, and the schema/metadata mutations — define +
materialize (`POST /v1/tables/{name}/schema`), live schema PATCH
(`PATCH /v1/tables/{name}/schema`), and definition update (`PUT /v1/tables/{name}`) — require
`TABLE_MODIFY`. Both schema endpoints SHALL require the same privilege; they SHALL NOT differ in
reachability as they do today.

Anything that touches sensitivity SHALL additionally require the clearance `TABLE_SENSITIVE` on the
table, globally or by grant (see `authentication`). That covers changing a column's `sensitive` flag
in either direction, declaring a column `sensitive` on `add` or `POST .../schema`,
and renaming, dropping or updating a column that is already `sensitive`. `TABLE_GRANT` SHALL NOT be
required for any of it. A mutation that touches no sensitive column and no `sensitive` flag SHALL
remain authorized by `TABLE_MODIFY` alone.

Table create (`POST /v1/tables`) SHALL require `TABLE_CREATE` and delete
(`DELETE /v1/tables/{name}`) SHALL require `TABLE_DELETE`; both are global-only privileges and SHALL
NOT be authorizable by any per-table grant. `system`-owned table protection SHALL take precedence: a
`system` table SHALL reject every modifying operation regardless of any privilege or grant.

#### Scenario: Access role authorizes a row write

- **GIVEN** a non-system table whose `write` access list contains the caller's provider role
- **WHEN** the caller inserts rows
- **THEN** the write succeeds

#### Scenario: A modify role authorizes defining the schema

- **GIVEN** a non-system `PENDING` table whose `modify` access list contains the caller's provider
  role
- **WHEN** the caller `POST`s its schema to `POST /v1/tables/{name}/schema`
- **THEN** the request is authorized and the table is materialized

#### Scenario: A modify grant does not authorize changing sensitivity

- **GIVEN** a non-system table with a `sensitive` column `employee_name` and a non-sensitive column
  `amount`, whose `modify` access list contains the caller's provider role and whose `sensitive` list
  does not
- **WHEN** the caller sends `PATCH /v1/tables/{name}/schema` with
  `{"update":[{"name":"employee_name","sensitive":false}]}`
- **THEN** the response is `422` naming an unknown column `employee_name`, and the column remains
  sensitive
- **AND WHEN** the same caller sends `{"update":[{"name":"amount","sensitive":true}]}`
- **THEN** the response is `403 forbidden` and `amount` stays non-sensitive
- **AND WHEN** the same caller patches only `amount`'s `description`
- **THEN** the request succeeds

#### Scenario: A modify grant with the clearance changes sensitivity

- **GIVEN** the same table, with the caller's provider role also in its `sensitive` list
- **WHEN** the caller clears `sensitive` on `employee_name` and sets it on `amount`
- **THEN** both changes are applied

#### Scenario: Delete is never authorized by an access role

- **WHEN** a caller holding a `modify` grant but not `TABLE_DELETE` deletes the table
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
a **non-empty array** replaces it. In `oidc` mode this endpoint SHALL require `TABLE_MODIFY`
on the table, held globally or granted on it. Updating an unknown definition SHALL return 404.

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

- **WHEN** a caller holding no global privilege but a matching `modify` grant on the table updates
  its
  description or tag order
- **THEN** the update is authorized and processed

### Requirement: Modify table schema via PATCH

The system SHALL evolve a **materialized** table's schema at `PATCH /v1/tables/{name}/schema` from a
body carrying `add`, `drop`, `rename`, and `update` column operations and an optional `ttl` member.
The endpoint SHALL be allowed only while the table's `status` is `ACTIVE`; on a `PENDING` or
`FAILED` (not-yet-materialized) table it SHALL be rejected with HTTP 409 (its schema is defined and
materialized via `POST /v1/tables/{name}/schema`). The frozen physical keys (`ordering_key`,
`partition_by`, `grain_key`, `cardinality`) are fixed after materialization, and the table's tag
ordering (`tag_order`) is table catalog metadata set via `PUT /v1/tables/{name}`; none of these
SHALL be members of this body — a frozen-key or `tag_order` member SHALL be rejected with HTTP 422
(this rejection precedes the materialization-state check, so it applies regardless of the table's
status). The `ttl` member is the one mutable physical member of this body.

The `ttl` member SHALL follow merge-patch semantics: **absent or null** SHALL leave the table's
TTL unchanged; a **full object** `{column, interval, unit}` SHALL set or replace it (a
`MODIFY TTL` command); an **empty object `{}`** SHALL remove it (a `REMOVE TTL` command). A
partial `ttl` object (some but not all of `column`/`interval`/`unit`) SHALL be rejected with HTTP
422. Removing when no TTL is stored SHALL be an idempotent no-op: HTTP 200 with no `REMOVE TTL`
command emitted. The TTL `column` SHALL be resolved against the table's columns **after** the
request's own `drop`/`rename`/`add` operations are applied, so a just-added or just-renamed column
MAY be referenced; validation follows the "Table TTL" requirement.

All physical commands of one request SHALL be rendered in the canonical order `drop` → `rename` →
`add` → `ttl` and issued as a **single multi-command ClickHouse `ALTER TABLE` statement** (writer
credential), which ClickHouse validates as a whole: the statement is rejected or applied in its
entirety, so a request with one invalid command SHALL leave the physical table completely
unchanged. On success the Postgres catalog (column mapping and TTL) SHALL be updated in one
transaction. The `update` operation SHALL adjust an existing column's catalog-only metadata — its
`tag`, `display_name`, `sensitive` flag, `heavy` flag, and `description` — and SHALL NOT contribute any
ClickHouse `ALTER` command; a request carrying only `update`s (or only an idempotent TTL removal)
SHALL issue no `ALTER` at all. Each `update` entry declares `{name, tag?, display_name?,
sensitive?, heavy?, description?}`, keyed by the column's current exposed name, and SHALL follow
merge-patch semantics per field: a metadata field that is **absent or null** SHALL leave that
column attribute unchanged; a string field (`tag`, `display_name`, `description`) that is
**present and blank** SHALL clear that attribute; a string field that is **present and non-blank**
SHALL set it (trimmed); and `sensitive`/`heavy`, when **present**, SHALL set that flag to the supplied
boolean. A single `update` entry MAY change several metadata fields at once. Each `add` declares
`{source_name, name, type, element_type?, nullable?, description, display_name?, tag?,
sensitive?, heavy?}` (`sensitive` and `heavy` default to false when omitted; `element_type` is
required iff `type=array`, see "Schema validation"), and an added `type=array` column SHALL
materialize as ClickHouse `Array(<element>)`.

An `update` entry MAY target a just-added or just-renamed column by its resulting exposed name. A
column's `display_name`, `tag`, `sensitive` flag, `heavy` flag, and `description` SHALL all be
preserved across a `rename`. Schema modification SHALL be breaking and immediate (a drop or rename takes effect at
once). A ClickHouse `ALTER` failure SHALL leave the table `ACTIVE` (surfaced via the `ErrorView`
contract, status unchanged) and SHALL NOT transition it to `FAILED` — so a materialized table that
holds data is never reachable by the define-and-materialize `POST .../schema` path (which is
`ACTIVE` → 409); because the statement is single and validated as a whole, a failure leaves no
partially-applied operations, and the catalog is written only after the `ALTER` succeeds. In
`oidc` mode this endpoint SHALL require `TABLE_MODIFY` on the table, held globally or granted on it.

For a caller that does not hold `TABLE_SENSITIVE` on the table, the request SHALL be resolved against
the table's **visible** columns only, the same set `GET /v1/tables/{name}` returns to that caller. A
`sensitive` column SHALL be treated as not existing: a `drop`, a `rename.from` or an `update` naming it
SHALL be rejected as naming an unknown column (422), with the same message as for a column that does
not exist at all. Such a caller SHALL also be refused with `403 forbidden` any `update` that supplies a
`sensitive` member (either value), and any `add` that declares `sensitive: true`. An `add` or
`rename.to` whose name collides with a hidden column SHALL still be rejected (422, already exists), whether it
collides on the exposed name or on the physical one, and before any `ALTER` is issued. That discloses that
the name is taken, and it is the accepted limit of hiding (see `authentication`). The per-table column
limit SHALL count hidden columns too. A `ttl.column` SHALL resolve against the visible columns only, so
naming a hidden column fails as naming an undeclared one. Such a caller SHALL be refused
`403 forbidden` for any `ttl` member (set, replace or remove) while the stored TTL is on a hidden column. A
caller holding the clearance resolves against every column and MAY make any of these changes. Marking a
column sensitive — by patching one or adding one already flagged — on an enrichment SHALL be subject to
the laundering subset rule (see `enrichment-runtime`). The system SHALL reject: a type-change of an existing column (422); a drop or rename
of a system column (reserved `_`-prefix), the grain key, or an ordering-key column (422); a `drop`
of the column the table's stored TTL references (422 — remove or repoint the TTL in a prior
request first, even if the same request also carries a `ttl` member); an `update` of a column that
is not an exposed column of the table after the request's own add/drop/rename operations are
applied (422); a duplicate `update` of the same column within one request (422); and any operation
on a table not in the catalog (404).

The system SHALL also reject a `drop` or a `rename` of a **source** column that any `ACTIVE`
enrichment of that source names as its `grain_key` (422, naming the enrichment). The enrichment join
fires on the shared physical name (`source.grain_key = enrichment.grain_key`, see "Grain-based
enrichment join with enforced cardinality"), the enrichment side of that name is that table's ordering
key and therefore immutable, and a Class-1 pipeline's target is keyed on it — so the operation would leave
the join referencing a column that no longer exists, with no repair available on either side.

The system SHALL reject a `rename` of an **enrichment's provenance column** — a column whose physical
name is one of `pipeline_generation`, `model`, `enriched_at`, `group_version`, `truncated` or
`evaluator_version` (see "Provenance columns are injected into an enrichment's schema definition") —
with HTTP 422. The write path stamps provenance by its canonical name and silently skips a name the
target does not carry, so a rename would move the stamped values under the new name while every later
row went unstamped, with no error anywhere. The converse is rejected the same way: a `rename` whose `to`
is a provenance name, which a `drop` in the same or an earlier request may have freed, would have the
pipeline's stamps written over that column's own values. A `drop` of a provenance column SHALL remain allowed: it is
an explicit opt-out of that lineage value (the only way to remove the three columns a Class-1 target
can never populate), and the startup reconciler restores `pipeline_generation`, empty, on a dropped
enrich target. The rule is enrichment-only: a source column that happens to share a provenance name
renames like any other.

**The pipeline dependency guard SHALL resolve readers and writers from stored declarations, and
therefore covers complete declarations only.** Before any DDL is rendered, the endpoint refuses a
`drop` or a `rename` of a column that a pipeline **reads** through its input's grain join, or that
receives an output of the pipeline **writing** the enrichment, with HTTP 409 `conflict` naming those
pipelines (the two refusals are owned by
[enrichment-registry](../enrichment-registry/spec.md) and
[aggregate-pipeline](../aggregate-pipeline/spec.md)). Both sides answer the question by reading what
is stored — the declarations' governed fragments and bound inputs for reads, the derived
`output_bindings` for writes — so a declaration that does not yet name a column does not protect it.
Since a declaration may now be stored before it is complete, **an incomplete declaration
under-declares its reads, and a column it will eventually read stays droppable.**

This is stated as a bound rather than closed, because closing it would mean protecting a column on
the strength of a declaration that does not resolve — a fragment that may not parse, against a read
source that may not be declared yet — which is a guard with no way to compute its own answer. The
consequence is bounded on the other side instead: completing such a declaration afterwards **fails at
the declaration gate**, with HTTP 422 naming the column the read source no longer has, rather than
arming a pipeline that silently reads a column that is gone. The cost of the gap is therefore lost
authoring work on a pipeline that never ran — the author repoints or re-declares — and never a wrong
row. A pipeline that was ever armed had a complete declaration, and from that moment its reads are
stored and the guard holds for it as before. The **same bound applies to `DELETE /v1/tables/{name}`**,
whose input and enrichment dependency checks read the same stored declarations (see "Delete a table
definition"); it is stated on both verbs because an under-declared read is reached by either.

**The write side of the guard stays armed throughout authoring**, and that is not incidental: the
declaration gate's derivation half runs on **every** declaration write, armed or not, and never
replaces a derived `output_bindings` it could not re-derive (see
[enrichment-registry](../enrichment-registry/spec.md), "Static governance gate on arming and
execution"). Were it otherwise, an intermediate save of an edit would erase the record of which
columns the pipeline writes, this guard would stop refusing them, and a `drop` would then be applied
to ClickHouse *before* the catalog transaction that re-derives the mapping — leaving the column gone
from the table and present in the catalog, with no operation that repairs the pair.

**The post-DDL re-derivation of a target's output mapping SHALL follow the pipeline's state.** When a
schema patch adds, drops or renames a column of an enrichment, the pipeline targeting it has its
output mapping re-derived in the catalog transaction that follows the `ALTER`. For a **disabled**
pipeline — a draft, stored without the gate — a mapping that no longer re-derives SHALL NOT refuse the
schema patch: the stored `output_bindings` SHALL be kept, and neither `generation` nor `updated_at`
SHALL move, exactly as a draft save derives best-effort. A disabled pipeline's mapping that **does**
re-derive SHALL be rewritten exactly as an enabled pipeline's is: an `llm` pipeline's `generation` and
`updated_at` SHALL be advanced, and a `sql` pipeline's SHALL NOT. For an **enabled** pipeline the refusal SHALL
stay as the backstop it is: a change that leaves an armed pipeline's mapping unresolvable is refused
with HTTP 422 `pipeline_validation_failed` naming the pipeline, since the pre-DDL guard should already
have refused it and committing a schema the armed pipeline cannot write to is the worse outcome.

The system SHALL reject a `drop` of the column named by the table's scan metadata — its
`identity_column` or its `version_column` (422) — since nothing repoints the pair afterwards, leaving
the table advertising a scan column that no longer exists. A `rename` of either column SHALL be
**allowed**, and the catalog's stored `identity_column`/`version_column` SHALL be updated to the renamed
name in the same transaction as the column mapping — the treatment the TTL column already receives.
The rename SHALL NOT invalidate any scan cursor: a cursor carries the tuple's *values* and the source
entity, not the column names, and `RENAME COLUMN` preserves the data. When a source's `version_column`
is repointed this way, every pipeline whose target enrichment reads that source SHALL have its `generation`
and `updated_at` advanced in the same transaction (see
[enrichment-registry](../enrichment-registry/spec.md)), so a runner detecting change by `generation`
refetches the name its scan pages now carry.

A `rename` of the TTL-referenced column SHALL be allowed: ClickHouse rewrites the physical TTL
expression itself, and the catalog's stored TTL column SHALL be updated to the renamed name in the same
transaction. The `update` operation SHALL carry no general column mutability restriction — system,
grain, and ordering-key columns MAY be tagged, given a display name, marked sensitive, marked heavy, or
described — with one exception: an `update` setting `sensitive: true` on the column named by the table's
`identity_column` or `version_column` SHALL be rejected (422). A sensitive cursor column is what the
schema-define contract already refuses (see "Define and materialize a table schema"), because the scan
projects both cursor columns and carries the identity inside its cursor, so every caller without the
sensitive entitlement would be refused the pipeline; permitting it through `update` would undo after
materialization what declaration enforces.

#### Scenario: Schema PATCH before materialization is rejected

- **WHEN** `PATCH /v1/tables/{name}/schema` is called on a `PENDING` or `FAILED` table
- **THEN** the request is rejected with HTTP 409 (define and materialize the schema first via
  `POST .../schema`)

#### Scenario: A frozen-key member in a PATCH is rejected

- **WHEN** `PATCH /v1/tables/{name}/schema` carries `ordering_key`, `partition_by`, `grain_key`, or
  `cardinality`
- **THEN** the request is rejected with HTTP 422 (physical keys are frozen after materialization)

#### Scenario: Dropping a scan-metadata column is rejected

- **WHEN** `PATCH /v1/tables/{name}/schema` carries a `drop` of the column named by the table's
  `identity_column` or `version_column`
- **THEN** the request is rejected with HTTP 422 before any `ALTER` is issued, and the table's columns
  and stored scan metadata are unchanged

#### Scenario: Renaming a scan-metadata column repoints the stored pair

- **WHEN** `PATCH /v1/tables/{name}/schema` renames the column named by the table's `identity_column`
  or `version_column`, and no `ACTIVE` enrichment of that source is keyed on it
- **THEN** the rename is applied and the stored `identity_column`/`version_column` reflects the new
  name, so `GET /v1/tables/{name}` returns the renamed column and a subsequent scan of a pipeline over that
  source pages on the renamed column
- **AND** a cursor issued before the rename continues to page correctly, because the cursor carries the
  tuple's values and the source entity rather than the column names

#### Scenario: A column an incomplete declaration will eventually read stays droppable

- **WHEN** `PATCH /v1/tables/{name}/schema` drops an enrichment column while the only pipeline that
  will read it is disabled and its declaration does not yet name it
- **THEN** the drop is accepted, because the guard resolves readers from stored declarations and that
  declaration declares no such read

#### Scenario: Completing that declaration afterwards fails at the gate rather than reading a dropped column

- **WHEN** the author then declares the fragment naming the dropped column and arms or previews the
  pipeline
- **THEN** the request is rejected with HTTP 422 naming the column the read source no longer has, so
  the cost of the gap is the authoring work and never a row derived from a column that is gone

#### Scenario: A complete declaration protects its reads even while disabled

- **WHEN** `PATCH /v1/tables/{name}/schema` drops or renames an enrichment column that a **complete**
  declaration reads through its input's grain join, and that pipeline is disabled
- **THEN** the request is refused with HTTP 409 `conflict` naming that pipeline, and no `ALTER` is
  issued — completeness, not enablement, is what arms this guard

#### Scenario: An intermediate save keeps the written columns protected

- **WHEN** a disabled pipeline whose `output_bindings` were derived is patched with a declaration
  whose outputs do not resolve, and a schema patch then drops one of those bound columns
- **THEN** the drop is refused with HTTP 409 `conflict`, because the declaration write kept the
  derived mapping rather than erasing it

#### Scenario: A schema patch does not fail on a draft whose mapping stops re-deriving

- **GIVEN** a disabled `enrich` pipeline whose outputs do not resolve against its target after a schema
  patch of that target applies
- **WHEN** the catalog transaction re-derives the pipeline's output mapping
- **THEN** the schema patch succeeds, the pipeline's stored `output_bindings` are kept as they were, and
  its `generation` and `updated_at` are unchanged

#### Scenario: A draft whose mapping does re-derive is rewritten as an enabled pipeline's is

- **GIVEN** a disabled `enrich` pipeline whose outputs still resolve against its target after a schema
  patch of that target applies
- **WHEN** the catalog transaction re-derives the pipeline's output mapping
- **THEN** the stored `output_bindings` are replaced by the re-derived mapping
- **AND** an `llm` pipeline's `generation` and `updated_at` are advanced, while a `sql` pipeline's are
  unchanged

#### Scenario: The re-derivation backstop still refuses for an enabled pipeline

- **GIVEN** an enabled `enrich` pipeline whose output mapping a schema patch of its target would leave
  unresolvable, and which the pre-DDL write guard did not catch
- **WHEN** the catalog transaction re-derives the pipeline's output mapping
- **THEN** the schema patch is refused with HTTP 422 `pipeline_validation_failed` naming the pipeline

#### Scenario: A drop or rename of a column an enrichment is keyed on is rejected

- **WHEN** `PATCH /v1/tables/{name}/schema` carries a `drop` or a `rename` of a source column that an
  `ACTIVE` enrichment of that source names as its `grain_key`
- **THEN** the request is rejected with HTTP 422 naming that enrichment, before any `ALTER` is issued,
  and the source's columns are unchanged
- **AND** the rejection applies whether or not that column is also the source's `identity_column`

#### Scenario: Marking a scan-metadata column sensitive is rejected

- **WHEN** an `update` entry supplies `sensitive: true` for the column named by the table's
  `identity_column` or `version_column`
- **THEN** the request is rejected with HTTP 422, and the column's `sensitive` flag is unchanged
- **AND** the same `update` supplying a `tag`, `display_name`, `description`, `heavy`, or
  `sensitive: false` for that column is accepted

#### Scenario: A tag_order member in a PATCH is rejected

- **WHEN** `PATCH /v1/tables/{name}/schema` carries a `tag_order` member
- **THEN** the request is rejected with HTTP 422 (tag ordering is table metadata, set via
  `PUT /v1/tables/{name}`)

#### Scenario: Set a TTL on a materialized table

- **WHEN** `PATCH /v1/tables/{name}/schema` carries
  `ttl: {column: "created_at", interval: 90, unit: "day"}` for an `ACTIVE` table where
  `created_at` is a declared non-nullable `timestamp` column
- **THEN** the issued `ALTER` contains `MODIFY TTL toDateTime(created_at) + INTERVAL 90 DAY`, the
  catalog stores the TTL, and the response carries `ttl: {column: "created_at", interval: 90,
  unit: "day"}`

#### Scenario: Replace an existing TTL

- **WHEN** a `PATCH` carries a full `ttl` object for a table that already has a TTL
- **THEN** a `MODIFY TTL` reflecting the new value is issued and the catalog value is replaced

#### Scenario: Remove a TTL with an empty object

- **WHEN** a `PATCH` carries `ttl: {}` for an `ACTIVE` table that has a TTL
- **THEN** the issued `ALTER` contains `REMOVE TTL`, the catalog value is cleared, and a
  subsequent `GET /v1/tables/{name}` omits the `ttl` member

#### Scenario: Removing an unset TTL is an idempotent no-op

- **WHEN** a `PATCH` carries `ttl: {}` for a table that has no stored TTL (and no other physical
  operations)
- **THEN** the response is 200 and no ClickHouse `ALTER` is issued

#### Scenario: Absent ttl member leaves the TTL unchanged

- **WHEN** a `PATCH` carrying only column operations (no `ttl` member) is applied to a table with
  a TTL
- **THEN** the stored TTL and the physical TTL are unchanged

#### Scenario: Partial ttl object is rejected

- **WHEN** a `PATCH` carries a `ttl` object with some but not all of `column`, `interval`, `unit`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Columns and TTL change in one statement

- **WHEN** a `PATCH` carries an `add` of a non-nullable `timestamp` column `event_time` and
  `ttl: {column: "event_time", interval: 30, unit: "day"}`
- **THEN** a single `ALTER` statement carrying both the `ADD COLUMN` and the `MODIFY TTL`
  commands is issued, and the TTL references the just-added column

#### Scenario: One invalid command leaves the whole PATCH unapplied

- **WHEN** a `PATCH` carries a valid `add` and an invalid `ttl` (or any command ClickHouse
  rejects), so the single `ALTER` statement fails
- **THEN** the response is an `ErrorView` error, no command of the statement is applied (the
  column is not added), the table stays `ACTIVE`, and the catalog is unchanged

#### Scenario: Drop of the TTL-referenced column is rejected

- **WHEN** a `PATCH` carries a `drop` of the column the table's stored TTL references
- **THEN** the request is rejected with HTTP 422 (remove or repoint the TTL first), regardless of
  any `ttl` member in the same request

#### Scenario: Rename of the TTL-referenced column keeps catalog and table in sync

- **WHEN** a `PATCH` renames the column the table's stored TTL references
- **THEN** the rename is applied (ClickHouse rewrites the TTL expression), and the catalog's
  stored TTL column reflects the new name, so `GET /v1/tables/{name}` returns the renamed column
  in its `ttl` object

#### Scenario: A failed live ALTER leaves the table ACTIVE

- **WHEN** a `PATCH /v1/tables/{name}/schema` request's single ClickHouse `ALTER` fails on an
  `ACTIVE` table that holds data
- **THEN** the response is an `ErrorView` error, the table's `status` remains `ACTIVE` (it does not
  become `FAILED`), and `POST /v1/tables/{name}/schema` continues to reject it with HTTP 409

#### Scenario: Add columns to a materialized enrichment

- **WHEN** `PATCH /v1/tables/{name}/schema` adds valid columns to an `ACTIVE` enrichment
- **THEN** an `ALTER` statement with an `ADD COLUMN` command per column is issued, the column
  mapping is updated, and the response reflects the new columns

#### Scenario: Add an array column

- **WHEN** `PATCH /v1/tables/{name}/schema` adds a column of `type:"array"`, `element_type:"long"`
  to an `ACTIVE` table
- **THEN** an `ALTER TABLE ... ADD COLUMN ... Array(Int64)` is issued and the column mapping records
  the `array` type with element type `long`

#### Scenario: Drop and rename apply immediately

- **WHEN** `PATCH /v1/tables/{name}/schema` drops one column and renames another on an `ACTIVE` table
- **THEN** a single `ALTER` statement carrying the `DROP COLUMN` and `RENAME COLUMN` commands is
  issued and the column mapping is updated

#### Scenario: Type change is rejected

- **WHEN** a `PATCH` attempts to change the `type` of an existing column
- **THEN** the request is rejected with HTTP 422

#### Scenario: System and grain columns are protected

- **WHEN** a `PATCH` attempts to drop or rename a `_`-prefixed system column or an enrichment's grain
  key
- **THEN** the request is rejected with HTTP 422

#### Scenario: An enrichment's provenance column cannot be renamed

- **WHEN** a `PATCH` on an `ACTIVE` enrichment renames a provenance column such as `enriched_at`
- **THEN** the request is rejected with HTTP 422 naming the column, and no ClickHouse `ALTER` is issued

#### Scenario: A column cannot be renamed onto a provenance name

- **WHEN** a `PATCH` on an `ACTIVE` enrichment drops `enriched_at` and renames another column to `enriched_at`
- **THEN** the request is rejected with HTTP 422, and no ClickHouse `ALTER` is issued

#### Scenario: An enrichment's provenance column can be dropped

- **WHEN** a `PATCH` on an `ACTIVE` enrichment drops a provenance column that no pipeline output binds
- **THEN** the column is dropped, and later writes skip that provenance value

#### Scenario: Update changes several metadata fields at once

- **WHEN** a single `update` entry supplies a `tag`, a `display_name`, and `sensitive: true` for an
  existing column
- **THEN** all three attributes are set on that column and no ClickHouse `ALTER` is issued

#### Scenario: Absent metadata field leaves an attribute unchanged

- **WHEN** an `update` entry supplies only a `tag` for a column that already has a `display_name`
- **THEN** the column's `tag` is set and its existing `display_name`, `sensitive` flag, `heavy` flag,
  and `description` are unchanged

#### Scenario: Update toggles the heavy flag without an ALTER

- **WHEN** an `update` entry supplies `heavy: true` for an existing column, and a later request
  supplies `heavy: false` for the same column
- **THEN** the flag is set and then cleared, no ClickHouse `ALTER` is issued by either request, and
  wildcard projections on the query surface follow the current value

#### Scenario: Blank metadata field clears an attribute

- **WHEN** an `update` entry supplies a blank (`""`) `tag`, `display_name`, or `description` for a
  column that currently has that attribute set
- **THEN** that attribute is cleared, and no ClickHouse `ALTER` is issued

#### Scenario: Metadata survives a rename

- **WHEN** a `PATCH` `update`s an existing column's `tag`, `display_name`, `sensitive` flag, `heavy`
  flag, and `description`, then a later `PATCH` renames that column
- **THEN** all five attributes are preserved under the rename, none of which issues a ClickHouse
  `ALTER`

#### Scenario: Update of an unknown column is rejected

- **WHEN** an `update` entry names a column that is not an exposed column of the table (after the
  request's own add/drop/rename operations are applied)
- **THEN** the request is rejected with HTTP 422

#### Scenario: Duplicate update of the same column is rejected

- **WHEN** two `update` entries in one request name the same column
- **THEN** the request is rejected with HTTP 422

#### Scenario: A modify access role authorizes a non-admin schema change

- **WHEN** a caller holding no global privilege but a matching `modify` grant on an `ACTIVE` table
  `PATCH`es its schema
- **THEN** the modification is authorized and processed

#### Scenario: A hidden sensitive column is unknown to a caller without the clearance

- **GIVEN** an `ACTIVE` table with a `sensitive` column `employee_name`, and a caller holding
  `TABLE_MODIFY` but not `TABLE_SENSITIVE` on it
- **WHEN** the caller `PATCH`es `{"drop":["employee_name"]}`, and separately
  `{"rename":[{"from":"employee_name","to":"full_name"}]}`
- **THEN** each request is rejected with 422 naming an unknown column, and the table is unchanged

#### Scenario: Adding a sensitive column needs the clearance

- **WHEN** a caller holding `TABLE_MODIFY` but not `TABLE_SENSITIVE` `PATCH`es an `add` declaring a
  column with `sensitive: true`
- **THEN** the request is rejected with `403 forbidden` and no `ALTER` is issued
- **AND WHEN** a caller holding both sends the same request
- **THEN** the column is added and flagged sensitive

#### Scenario: A name collision with a hidden column is still refused

- **WHEN** a caller without the clearance `add`s a column whose exposed name equals a hidden
  sensitive column's, where the two physical names differ, or `rename`s a visible column onto that name
- **THEN** the request is rejected with 422 as a name that already exists, no `ALTER` is issued, and the
  table is unchanged

#### Scenario: A TTL cannot reach a hidden column

- **GIVEN** an `ACTIVE` table with a hidden sensitive `timestamp` column `birth_date`, and a caller without
  the clearance
- **WHEN** the caller `PATCH`es `{"ttl":{"column":"birth_date","interval":1,"unit":"day"}}`
- **THEN** the request is rejected with 422 as naming an undeclared column, and no `ALTER` is issued
- **AND WHEN** the table's stored TTL is on `birth_date` and the same caller sends `{"ttl":{}}`
- **THEN** the response is `403 forbidden` and the TTL is unchanged

### Requirement: Grain-based enrichment join with enforced cardinality

The system SHALL resolve enrichment columns into a source's flat schema by a `LEFT JOIN` on the shared
`grain_key` (`source.grain_key = enrichment.grain_key`), extended to the tuple
`(grain_key, partition column)` for an **aligned** enrichment (see "An eligible row-grain enrichment is
laid out aligned with its source"). Enrichment tables
SHALL be stored as `ReplacingMergeTree(_updated_at)` ordered by the grain key so that at most one row
exists per grain key — per grain key and partition value for an aligned enrichment — making the declared
`ZERO_OR_ONE` cardinality physically true and preventing
the join from inflating source rows. The join of a non-aligned enrichment SHALL be a single-key join on
`grain_key` alone; the join of an aligned enrichment SHALL be
`source.grain_key = enrichment.grain_key AND source.<partition column> = enrichment.<partition column>`.
Either SHALL be emitted **keyed** — the enrichment side restricted to the grain keys the statement's own
source-only predicates select, see the structured-query capability — whenever the statement has such
predicates, so a lookup does not hash the whole enrichment table. A `grain_key` MAY NOT name a `map`
or an `object` column, nor a nullable one: ClickHouse refuses each in a sorting key, and the refusal
belongs at declaration time (422) rather than as a raw `CREATE TABLE` failure leaving a `FAILED`
definition. Joins SHALL be emitted only for enrichments a query actually
references. Enrichment columns SHALL be opt-in: a row query with an omitted projection (`select`
absent) SHALL return the source's own columns only — enrichment columns must be named explicitly to
be projected (and to trigger their join), so a bare projection never fans out into enrichment joins.

The tuple join is what makes the aligned layout safe without any catalog claim that the source's identity
fixes its partition: each source row can match only the enrichment row written from it, so two source rows
sharing an identity value across partitions never fan out, and a row written with a wrong partition value
is merely unmatched — it can never attach to another source row.

Because the join is by shared physical name, that name SHALL be immutable on **both** sides for as long
as the enrichment is `ACTIVE`: the enrichment's own grain-key column is its ordering key and already
rejected for drop and rename, and the source column it matches SHALL likewise be rejected for drop and
rename (see "Modify table schema via PATCH"). Neither side can be repaired by renaming the other —
ClickHouse refuses to rename a column that is part of a table's key expression — so a rename permitted on
the source side would break every query referencing that enrichment, and every Class-1 materialize over
it, with no way back except restoring the old name.

#### Scenario: Enrichment column resolves via grain join

- **WHEN** a query references an enrichment column of a source
- **THEN** the builder emits a `LEFT JOIN` of that enrichment on the shared `grain_key` and no join
  for enrichments the query does not reference

#### Scenario: An aligned enrichment joins on the grain key and the partition column

- **GIVEN** an aligned enrichment of `dial_usage_log`
- **WHEN** a query references one of its columns
- **THEN** the join condition is `event_id = event_id AND request_time = request_time`

#### Scenario: Two source rows sharing an identity across days do not fan out

- **GIVEN** an aligned enrichment and two source rows with the same `event_id` on different days, each
  with its own enrichment row
- **WHEN** a query projects an enrichment column over both
- **THEN** two rows are returned, each carrying the value written from its own source row

#### Scenario: Cardinality is enforced by storage

- **WHEN** two rows are written to an enrichment for the same `grain_key`
- **THEN** the `ReplacingMergeTree` keeps one row per key (latest `_updated_at` wins) and the source
  row count is not inflated by the join

#### Scenario: Omitted projection returns source columns only

- **WHEN** a row query against a source that has enrichments is executed with no `select`
- **THEN** only the source's own columns are returned, no enrichment is joined, and the query
  succeeds (enrichment columns appear only when explicitly selected/referenced)

#### Scenario: The joined name stays valid on both sides

- **WHEN** a source column that an `ACTIVE` enrichment names as its `grain_key` is drop- or
  rename-attempted through `PATCH /v1/tables/{name}/schema`
- **THEN** the operation is rejected with HTTP 422 and the join keeps resolving, so a query
  referencing that enrichment's columns continues to succeed

### Requirement: Schema validation

The system SHALL validate every physical-schema definition before persisting or issuing DDL — the
define-and-materialize `POST /v1/tables/{name}/schema` and the live `PATCH /v1/tables/{name}/schema` —
rejecting invalid requests with HTTP 422. Validation SHALL cover: table and column names match the
snake_case identifier grammar `^[a-z][a-z0-9_]*$` (≤ 64 chars) and do not use the reserved `_`-prefix
(which is for system columns); a table name is not one of the schema names the SQL surface uses —
`adas` and `information_schema` — since SQL resolves a one-part name against schemas before tables, so
such an entity could not be reached as `FROM <name>` while discovery went on listing it
(see sql-query-translation, "Entity names resolve schema-qualified as well as bare"); a source declares at least one column and a non-empty `ordering_key`,
and its `ordering_key`/`partition_by` reference declared columns and every column so named is
**non-nullable** — ClickHouse refuses both a nullable sorting key and a nullable partition key while
its `allow_nullable_key` setting is off (ADAS never enables it), so a schema naming a `nullable: true`
column in either place could not materialize; a source's optional `identity_column`
and `version_column`, when declared, each reference a non-nullable, non-`sensitive` column of that table
and the `version_column` is declared `timestamp` — except that an `upsert_by_key` source MAY name the
ReplacingMergeTree version `_updated_at` as its `version_column`, the engine's own version column, which
such a table physically carries and an `append` source does not; an
enrichment declares neither member; an enrichment's `grain_key` is
required and is a declared column of its `source_table`; exposed column `name`s are unique within a single table
(cross-table collisions across a source and its enrichments are NOT rejected — enrichment columns are
surfaced under table-qualified flat names, so a source column and an enrichment column, or two
enrichments, may share a bare `name`); a `type=array` column declares a required `element_type` naming
a scalar element type ∈ {`string`, `integer`, `long`, `decimal`, `boolean`, `date`, `timestamp`,
`uuid`} — an `array` column without an `element_type`, an `element_type` on a non-`array` column, and
an `element_type` of `array` (nested arrays) or `object` SHALL each be rejected; an `array` column
SHALL always be non-nullable (ClickHouse has no `Nullable(Array(...))`): an explicit `nullable: true`
on an `array` column SHALL be rejected, and an omitted `nullable` SHALL resolve to `false` for an
`array` column even on an enrichment (whose other columns default nullable); `object` maps to
ClickHouse `JSON`; a column's optional `tag` is a free-form label of at most 64 characters (no
identifier grammar imposed) — a blank tag is treated as absent, and tags need NOT be unique within a
table; a column's optional `display_name` is free-form text of at most 128 characters (no identifier
grammar imposed) — a blank display name is treated as absent, and display names need NOT be unique
within a table; and a column's optional `description` is free-form text of at most 1024 characters (no
identifier grammar imposed) — a blank description is treated as absent, and descriptions need NOT be
unique within a table.

A source's `partition_by` MAY name a `sensitive` column: `sensitive` gates who may read a column's
values through ADAS, and ADAS returns only the partition column's name (in `partition_by`), never the
partition values, so partitioning on a sensitive column discloses nothing the column's own gate does not
already protect — unlike `identity_column` and `version_column`, which the scan projects into every page
and cursor and which are therefore refused as sensitive.

#### Scenario: Non-snake_case or reserved-prefix name is rejected

- **WHEN** a `POST .../schema` or a `PATCH` declares a table or column name that is not snake_case or begins
  with `_`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Schema without an ordering key is rejected

- **WHEN** `POST /v1/tables/{name}/schema` declares `columns` for a source but no `ordering_key`
- **THEN** the request is rejected with HTTP 422 and nothing is persisted (the table stays `PENDING`)

#### Scenario: Nullable ordering key column is rejected

- **WHEN** `POST /v1/tables/{name}/schema` declares a source whose `ordering_key` names a column
  declared `nullable: true`
- **THEN** the request is rejected with HTTP 422 naming the field and the column, and nothing is
  persisted and no ClickHouse DDL is issued (the table stays `PENDING`/`FAILED`)

#### Scenario: Nullable partition_by column is rejected

- **WHEN** `POST /v1/tables/{name}/schema` declares a source whose `partition_by` names a column
  declared `nullable: true`
- **THEN** the request is rejected with HTTP 422 naming the field and the column, and nothing is
  persisted and no ClickHouse DDL is issued (the table stays `PENDING`/`FAILED`)

#### Scenario: Sensitive partition_by column is accepted

- **WHEN** `POST /v1/tables/{name}/schema` declares a source whose `partition_by` names a non-nullable
  `date` or `timestamp` column declared `sensitive: true`, by a caller holding `TABLE_SENSITIVE`
- **THEN** the request is accepted and the table materializes partitioned by that column, and the
  column stays sensitive on every read surface

#### Scenario: An enrichment's grain key over a nullable source column is accepted

- **WHEN** `POST /v1/tables/{name}/schema` declares an enrichment whose `grain_key` names a column of
  its `source_table` that the source declares `nullable: true`
- **THEN** the request is accepted and the enrichment materializes with its grain column non-nullable —
  the enrichment's own physical grain column is always non-nullable regardless of the source column's
  flag, so ClickHouse has no nullable key to refuse

#### Scenario: User-declared array column is accepted

- **WHEN** a `POST .../schema` or a `PATCH add` declares a column of `type=array` with `element_type: "string"`
- **THEN** the request is accepted and the column materializes as ClickHouse `Array(String)`

#### Scenario: Array column without an element type is rejected

- **WHEN** a `POST .../schema` or a `PATCH add` declares a column of `type=array` with no `element_type`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Element type on a non-array column is rejected

- **WHEN** a `POST .../schema` or a `PATCH add` declares a non-`array` column carrying an `element_type`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Nested or object array element is rejected

- **WHEN** a `POST .../schema` or a `PATCH add` declares a column of `type=array` with `element_type` of `array`
  or `object`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Explicitly nullable array column is rejected

- **WHEN** a `POST .../schema` or a `PATCH add` declares a column of `type=array` with `nullable: true`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Array column on an enrichment resolves non-nullable

- **WHEN** a `PATCH add` adds a `type=array` column with an omitted `nullable` to an enrichment
- **THEN** the column is persisted and materialized as non-nullable `Array(<element>)`, even though
  omitted `nullable` defaults to nullable for the enrichment's other columns

#### Scenario: Column tag longer than the limit is rejected

- **WHEN** a `POST .../schema`, `PATCH add`, or `PATCH update` declares a column `tag` longer than 64
  characters
- **THEN** the request is rejected with HTTP 422

#### Scenario: Column display name longer than the limit is rejected

- **WHEN** a `POST .../schema`, `PATCH add`, or `PATCH update` declares a column `display_name` longer than
  128 characters
- **THEN** the request is rejected with HTTP 422

#### Scenario: Column description longer than the limit is rejected

- **WHEN** a `POST .../schema`, `PATCH add`, or `PATCH update` declares a column `description` longer than
  1024 characters
- **THEN** the request is rejected with HTTP 422

#### Scenario: Duplicate tags within a table are accepted

- **WHEN** a `POST .../schema` or `PATCH` results in two columns of the same table carrying the same `tag`
- **THEN** the request is accepted (tags are non-unique identifying labels)

#### Scenario: Duplicate display names within a table are accepted

- **WHEN** a `POST .../schema` or `PATCH` results in two columns of the same table carrying the same
  `display_name`
- **THEN** the request is accepted (display names are non-unique)

#### Scenario: Duplicate exposed name within a single table is rejected

- **WHEN** a `POST .../schema` or `PATCH` would result in two columns of the same table sharing an exposed
  `name`
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
write-batch size (default 10000 rows) SHALL be configurable and is enforced by the row-write API
(`POST /v1/tables/{name}/rows`). Limits SHALL be env-overridable.

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
enrichment its grain key). A value for an `array`-typed column SHALL be supplied as a JSON array of
its element type. The system SHALL insert the rows into the table's ClickHouse table using
the writer credential and return the inserted count. In `oidc` mode this endpoint SHALL require `TABLE_WRITE` on
the table, held globally or granted on it (see the `table-access-control` capability). `TABLE_WRITE`
confers no read, so an ingest-only identity entitled to this table stays absent from catalog listings
and cannot read back what it wrote. System-owned columns (the `_`-prefixed columns, including the enrichment version column
`_updated_at`) SHALL NOT be caller-settable: a row that names one SHALL be rejected with HTTP 422, and
these columns SHALL be omitted from the insert so ClickHouse stamps their defaults. It SHALL reject a
write to an unknown table (404), a write to a table that is not materialized (409),
and a row that is empty or references a column that is not a physical column of the table (422). This
is the minimal data-write surface for populating sources and enrichments; richer ingest/upsert
semantics are out of scope for this phase.

**Every physical column declared non-nullable (`nullable: false`, and an enrichment's grain key, which
is always non-nullable) SHALL be required on every row of a write**: a row that omits such a column,
or that names it with a JSON `null` value, SHALL be rejected with HTTP 422 and error code
`validation_error` before any row of the batch is inserted. The message SHALL name the offending row's
1-based index within the batch and the column. This check applies identically to every endpoint that
writes rows through this path, including the pipeline-scoped write-back (see the `enrichment-runtime`
capability), and runs independently of timestamp validation — a row can be rejected for a missing
non-nullable column even when it carries no `timestamp`-typed column at all.

A value for a **`timestamp`-typed column** SHALL be an ISO-8601 date-time string carrying an
**explicit zone**, in exactly one of two forms:

- a `Z` zone designator — `2026-08-11T10:00:00Z`
- an explicit UTC offset, resolved to the instant it denotes — `2026-08-11T13:00:00+03:00`

The date and time SHALL be separated by `T`. The seconds field is optional (`2026-08-11T10:00Z`).
Fractional seconds are optional and accepted at any precision up to nanoseconds (nine digits),
truncated toward the past to the column's stored millisecond precision. This is the format the query path already returns for a
`timestamp` column, so a value read from the system SHALL be writable back to it unchanged.

Every accepted value SHALL carry an explicit zone: the system SHALL NOT infer a zone for a zone-less
value, so no input can be silently stored as an instant other than the one the caller meant.

For the same reason the date SHALL name a day that exists on the ISO calendar: a value whose
day-of-month is out of range for its month (`2026-02-29` in a non-leap year, `2026-04-31`) SHALL be
rejected and SHALL NOT be adjusted to the month's last day.

A value for a `timestamp`-typed column that is not of the above form SHALL be rejected with HTTP 422
and error code `validation_error`, and no row of the batch SHALL be inserted. The message SHALL name
the offending row's 1-based index within the batch, the column, and the rejected value, and SHALL
identify the specific defect with its correction rather than reporting only that the value is invalid.
It SHALL distinguish at least these cases:

- a value with no zone (`2026-08-11T10:00:00`) — reported as a missing zone, directing the caller to
  `Z` or an explicit offset
- a value using a space instead of `T` (`2026-08-11 10:00:00.000`) — reported as the wrong separator
- a date with no time (`2026-08-11`) — reported as a date rather than a timestamp
- a day that does not exist on the calendar (`2026-02-29` in a non-leap year) — reported as such,
  naming the offending date, rather than as an unrecognized shape
- a non-string JSON value, including a numeric epoch (`1786514400000`) — reported as requiring a
  string, the units of a bare number being ambiguous
- an instant before `1900-01-01T00:00:00Z` or after `2299-12-31T23:59:59.999Z` (compared after
  conversion to UTC) — reported as out of range, naming the range. ClickHouse 26.3 clamps a
  `DateTime64` outside that range with no error, so a value past it would store a different instant
- any other unparseable value — reported as not an ISO-8601 timestamp, with an example of the form

A `null` value for a nullable `timestamp` column SHALL remain accepted and SHALL be stored as null.

An element of an `array` of `timestamp` SHALL be validated and rendered exactly as a `timestamp` value
is, with the element index named in a rejection. ClickHouse would otherwise read a zone-less element as
UTC and a bare number as epoch seconds.

Values of every other declared type SHALL be passed through unchanged once accepted by the checks below,
and SHALL NOT be inspected for timestamp shape. In particular a `decimal`-typed value SHALL retain a
caller-supplied string spelling so its digits are not degraded through a JSON float, and a
`string`-typed column SHALL accept a timestamp-shaped value untouched.

A value for an `integer`-typed column (`Int32`), a `long`-typed column (`Int64`), or an element of an
`array` of either SHALL be rejected with HTTP 422 and error code `validation_error` when it lies outside
the column type's range, and no row of the batch SHALL be inserted. ClickHouse does not refuse such a
value on insert: it stores the low bits (`3000000000` in an `Int32` reads back as `-1294967296`), so
without this check the write succeeds and stores a different number. The check SHALL apply to a JSON
integer and to a string spelling a plain integer (`"3000000000"`, which ClickHouse accepts for an integer
column and wraps the same way). A JSON number with a fraction or exponent (`1.5`, `3.0e9`) SHALL be
rejected as not a whole number. The message SHALL name the row's 1-based index, the column (with the
element index for an array), the rejected value, and the type's range. An accepted value SHALL be passed
to the store as supplied.

A value for a `decimal`-typed column (`Decimal(38, 12)`) or an element of an `array` of `decimal` SHALL
likewise be rejected with HTTP 422 and error code `validation_error` when its magnitude needs more than 26
digits before the decimal point (`|value| >= 10^26`), with the same message contents. ClickHouse itself
refuses such a value, but fails the whole insert with an error naming neither row nor column. The check
SHALL apply to a JSON number and to a string spelling a plain number, exponent included (`"94.25"`,
`"1e30"`). Fractional digits beyond the scale of 12 SHALL NOT be rejected by this check; ClickHouse
truncates them on insert.

A value for an `integer`, `long` or `decimal` column (or an element of an array of one) that is neither a
JSON number nor a string spelling one SHALL be rejected with HTTP 422 and error code `validation_error`,
naming the row, the column and the value. This covers a boolean (`true` stores `1` in an integer column),
the empty string (stores `0`, a `Nullable` column included), any other non-numeric or whitespace-padded
string, and an array or object. A numeric string longer than 1000 characters (the bound the JSON parser
already puts on a JSON number) SHALL be rejected without being parsed, since parsing it costs time
quadratic in its length.

A value for a `date`-typed column, or an element of an `array` of `date`, SHALL be a string `YYYY-MM-DD`
naming a day on the ISO calendar between `1970-01-01` and `2149-06-06`, the range of ClickHouse `Date`;
any other value SHALL be rejected with HTTP 422 and error code `validation_error`, naming the row, the
column and the value. ClickHouse accepts all of these silently: it clamps an out-of-range day to the
range's end, rolls a day not on the calendar into the next month (`2026-02-30` stores `2026-03-02`),
stores `1970-01-01` for month `13`, and reads a bare number as a day count.

A value for an `enum`-typed column SHALL be one of the column's declared values, as a string; any other
value, including a number, SHALL be rejected with HTTP 422 and error code `validation_error`, naming the
row, the column, the value and the declared values. ClickHouse maps a number to the element at that
internal id, which is not part of the column's contract.

An element of an `array` SHALL NOT be null, and a value in a `map` SHALL NOT be null: either SHALL be
rejected with HTTP 422 and error code `validation_error`, naming the row and the column (with the element
index or the key). Array elements and map values are never `Nullable`, so ClickHouse stores the type's
default in place of a null (`0`, `''`, `false`, the nil UUID) with no error.

An enrichment's grain key is checked from its source-declared type, and every value check applies
identically to the pipeline-scoped write-back and to the OTel sink. A write too large for one batch (the
OTel sink's) SHALL be validated whole before any of it is inserted, so a rejected value writes nothing,
and a rejection SHALL name the row's index within the whole write.

The column-set validation SHALL be applied before value validation, so a row naming an unknown or
system column SHALL still be rejected for that reason regardless of its values.

An enrichment's **grain key** is a physical column a row may key but is not part of the enrichment's own
declared columns; its declared type SHALL be taken from the **source** table, where the same column name
is declared. A grain key declared `timestamp` on the source is therefore validated as a timestamp on a
write to the enrichment, exactly as any other `timestamp` column is.

Because a timestamp is validated against the column's declared type, the accepted format is a property
of the write path itself and SHALL apply identically to every endpoint that writes rows through it,
including the pipeline-scoped write-back (see the `enrichment-runtime` capability).

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

#### Scenario: A timestamp read from the query path can be written back unchanged

- **WHEN** a row is written, read back through the query path (which returns a `timestamp` as ISO-8601
  with a `Z` designator), and that returned value is submitted verbatim in a new write
- **THEN** the write is accepted and stores the same instant

#### Scenario: An ISO-8601 timestamp with an explicit offset is stored as the instant it denotes

- **WHEN** a row supplies `2026-08-11T13:00:00+03:00` for a `timestamp`-typed column
- **THEN** the write is accepted and a subsequent query returns `2026-08-11T10:00:00Z` — the same
  instant, expressed in the column's UTC zone

#### Scenario: Optional seconds and sub-millisecond precision are accepted

- **WHEN** rows supply `2026-08-11T10:00Z` and `2026-08-11T10:00:00.123456Z` for a `timestamp`-typed
  column
- **THEN** both writes are accepted, the first storing zero seconds and the second truncated toward the
  past to millisecond precision

#### Scenario: A timestamp without a zone is rejected as ambiguous

- **WHEN** a row supplies `2026-08-11T10:00:00` for a `timestamp`-typed column
- **THEN** the request is rejected with HTTP 422 and error code `validation_error`, and the message
  reports the missing zone and directs the caller to `Z` or an explicit offset rather than assuming one

#### Scenario: The space-separated form is rejected with a message naming the separator

- **WHEN** a row supplies `2026-08-11 10:00:00.000` for a `timestamp`-typed column
- **THEN** the request is rejected with HTTP 422 and error code `validation_error`, and the message
  reports the wrong separator and gives the `T`-separated equivalent

#### Scenario: A day that does not exist on the calendar is rejected, not shifted

- **WHEN** a row supplies `2026-02-29T10:00:00Z` for a `timestamp`-typed column (2026 is not a leap year)
- **THEN** the request is rejected with HTTP 422 and error code `validation_error` naming the offending
  date, nothing is inserted, and no row is stored as `2026-02-28T10:00:00Z`
- **AND WHEN** a row supplies `2024-02-29T10:00:00Z`, a day that does exist
- **THEN** the write is accepted and stores that instant

#### Scenario: A date-only value and a numeric epoch are rejected

- **WHEN** one row supplies `2026-08-11` and another supplies the number `1786514400000` for a
  `timestamp`-typed column
- **THEN** each request is rejected with HTTP 422 and error code `validation_error`, the first reported
  as a date rather than a timestamp and the second as requiring a string value

#### Scenario: An invalid timestamp anywhere in a batch inserts nothing

- **WHEN** a batch of rows is written in which one row carries an invalid value for a
  `timestamp`-typed column
- **THEN** the request is rejected with HTTP 422, the message names that row's 1-based index within the
  batch, and no row of the batch is inserted

#### Scenario: An enrichment's timestamp grain key is validated from the source's declared type

- **WHEN** rows are written to an enrichment whose grain key is a column declared `timestamp` on its source
- **THEN** an ISO-8601 value with an explicit zone for that grain key is accepted, and a space-separated
  value for it is rejected with HTTP 422 `validation_error` naming the grain-key column

#### Scenario: An integer outside its column type's range is rejected rather than wrapped

- **WHEN** a batch is written in which one row supplies `3000000000` for an `integer`-typed column
- **THEN** the request is rejected with HTTP 422 and error code `validation_error`, the message names that
  row's 1-based index, the column, the value and the `Int32` range, and no row of the batch is inserted
- **AND WHEN** the same value is supplied as the string `"3000000000"`, as an element of an `array` of
  `integer`, or a value past the `Int64` range is supplied for a `long`-typed column
- **THEN** each request is rejected the same way

#### Scenario: Integer boundary values are stored exactly

- **WHEN** a row supplies `2147483647` for an `integer`-typed column and `3000000000` for a `long`-typed
  column
- **THEN** the write is accepted and a subsequent query returns both values unchanged

#### Scenario: A fractional value for an integer column is rejected as not whole

- **WHEN** a row supplies `1.5` for an `integer`-typed column
- **THEN** the request is rejected with HTTP 422 and error code `validation_error`, and the message names
  the column and reports the value as not a whole number

#### Scenario: A decimal past its integer digits is rejected with the row and column named

- **WHEN** a row supplies `"100000000000000000000000000"` (27 integer digits) or `1e30` for a
  `decimal`-typed column
- **THEN** the request is rejected with HTTP 422 and error code `validation_error` naming the row, the
  column and the type's limit, and no row of the batch is inserted
- **AND WHEN** a row supplies `"99999999999999999999999999.5"` for that column
- **THEN** the write is accepted and a subsequent query returns that value

#### Scenario: A non-number for a numeric column is rejected rather than coerced

- **WHEN** a row supplies `true` or `""` for an `integer`-typed column, or `""` for a nullable
  `decimal`-typed column
- **THEN** the request is rejected with HTTP 422 and error code `validation_error` naming the row and the
  column, and nothing is stored as `1` or `0`

#### Scenario: A date the Date type would shift is rejected

- **WHEN** a row supplies `"2026-02-30"`, `"2200-01-01"`, `"2026-13-01"` or the number `20000` for a
  `date`-typed column
- **THEN** each request is rejected with HTTP 422 and error code `validation_error` naming the row and
  the column, and no row of the batch is inserted
- **AND WHEN** a row supplies `"2026-08-11"`
- **THEN** the write is accepted and a subsequent query returns that day

#### Scenario: A timestamp outside the storable range is rejected

- **WHEN** a row supplies `"1899-12-31T23:59:59Z"` or `"2300-01-01T00:00:00Z"` for a `timestamp`-typed
  column
- **THEN** the request is rejected with HTTP 422 and error code `validation_error`, and the message names
  the range

#### Scenario: A timestamp array element is normalized like a timestamp value

- **WHEN** a row supplies `["2026-08-11T13:00:00+03:00"]` for an `array` of `timestamp`
- **THEN** the write is accepted and a subsequent query returns the element as `2026-08-11T10:00:00Z`
- **AND WHEN** an element has no zone or is a bare number
- **THEN** the request is rejected with HTTP 422 naming the column and the element index

#### Scenario: A null array element or map value is rejected

- **WHEN** a row supplies `[1, null]` for an `array` of `integer`, or `{"k": null}` for a `map` column
- **THEN** the request is rejected with HTTP 422 and error code `validation_error` naming the row and the
  column with the element index or key, and nothing is stored as the type's default

#### Scenario: An enum value must be one of the declared values

- **WHEN** a row supplies `"c"` or `1` for an `enum` column declared with values `a` and `b`
- **THEN** the request is rejected with HTTP 422 and error code `validation_error`, and the message lists
  the declared values
- **AND WHEN** a row supplies `"b"`
- **THEN** the write is accepted

#### Scenario: A decimal value keeps its string spelling

- **WHEN** a row supplies a `decimal`-typed column as a string such as `"94.25"`
- **THEN** the value is passed to the store as supplied, without being coerced through a JSON float

#### Scenario: A timestamp-shaped value in a string column is not inspected

- **WHEN** a row supplies an ISO-8601 or space-separated timestamp-shaped value for a `string`-typed
  column
- **THEN** the value is stored verbatim as a string and is neither normalized nor rejected

#### Scenario: An unknown column is rejected for that reason regardless of its values

- **WHEN** a row names a column that is not a physical column of the table and also carries an invalid
  value for a `timestamp`-typed column
- **THEN** the request is rejected with HTTP 422 for the unknown column

#### Scenario: Write to a non-materialized table is rejected

- **WHEN** `POST /v1/tables/{name}/rows` is called for a table that is not `ACTIVE` (still `PENDING`,
  or `FAILED`)
- **THEN** the request is rejected with HTTP 409

#### Scenario: Write with an unknown column is rejected

- **WHEN** a row references a column that is not a physical column of the table
- **THEN** the request is rejected with HTTP 422

#### Scenario: Write attempting to set a system column is rejected

- **WHEN** a row attempts to set a `_`-prefixed system column such as `_updated_at`
- **THEN** the request is rejected with HTTP 422 and ClickHouse stamps the column default on the (otherwise valid) inserts

#### Scenario: A write access role authorizes a non-admin write

- **WHEN** a caller holding no global privilege but a matching `write` grant on the table `POST`s
  rows
- **THEN** the write is authorized and processed

#### Scenario: A row omitting a non-nullable column is rejected

- **WHEN** a row is written to a table that declares one of its physical columns `nullable: false`, and
  that row does not name the column at all
- **THEN** the request is rejected with HTTP 422 and error code `validation_error`, the message names
  the offending row's 1-based index and the column, and no row of the batch is inserted

#### Scenario: A row explicitly nulling a non-nullable column is rejected

- **WHEN** a row is written to a table that declares one of its physical columns `nullable: false`, and
  that row names the column with a JSON `null` value
- **THEN** the request is rejected with HTTP 422 and error code `validation_error`, the message names
  the offending row's 1-based index and the column, and no row of the batch is inserted

#### Scenario: A row omitting or nulling an enrichment's grain key is rejected

- **WHEN** a row is written to an enrichment table and either omits the grain key column or names it
  with a JSON `null` value
- **THEN** the request is rejected with HTTP 422 and error code `validation_error` naming the grain-key
  column, since the grain key is always non-nullable regardless of the source column's own
  nullability

#### Scenario: A nullable column may still be omitted or explicitly nulled

- **WHEN** a row is written to a table and omits, or explicitly nulls, a physical column declared
  `nullable: true`
- **THEN** the write is accepted and the column is stored as null (omitted) or explicitly null, exactly
  as before this change

### Requirement: Delete a table definition

The system SHALL delete a table definition at `DELETE /v1/tables/{name}`, removing its catalog rows
and issuing `DROP TABLE` in ClickHouse (writer credential), returning HTTP 204. Deleting a table SHALL
also remove its access lists (see the `table-access-control` capability).
Deleting a `source` that still has enrichments referencing it SHALL be rejected with HTTP 409 until
those enrichments are deleted. Deleting a table that a **pipeline reads as its input** SHALL likewise
be rejected with HTTP 409, naming the pipelines, until each is deleted or repointed — otherwise a pipeline
would survive pointing at a table that no longer exists and fail on its next run instead of at the moment
the dependency was broken.

This SHALL be **one check across both kinds**, where the two registries needed two. The enrichment registry
asked it as "which rules declare this as their read source" and the aggregate registry as "which pipelines
read this as their input", and after the merge those are the same question of the same column — a declared
read source *is* a pipeline's input. The reason it was stated twice remains true of neither: it is not the
same protection the enrichment check gives a source read through a target enrichment, because an input need
have no enrichment of its own.

A second, distinct check SHALL protect an **enrichment** whose columns a declaration reads through the
grain join without targeting it. That one cannot be answered by the input lookup — it asks which
*declarations* name a column of this enrichment, and the reader's own input is a different table entirely —
so it survives the merge as its own query (see the
[enrichment-registry](../enrichment-registry/spec.md) capability).

That second check SHALL consult **every stored declaration, whatever its kind**. An `aggregate`
declaration reads an enrichment through the same grain join and is broken by the drop in the same way, so
scoping the guard to one kind protected an enrichment only when a declaration of that kind happened to
read it too — which is coincidence, not a guard.

Because the two kinds name their columns in different places, the check SHALL resolve each declaration
through the gate its own kind selects: an `enrich` declaration names them in its bindings, filter,
readiness signal and its transform's output expressions, an `aggregate` one in its group keys, its
measures, its filter and each measure's own `where`. Deleting a table that is a pipeline's **target** SHALL be permitted and SHALL remove the
pipeline with it: the pipeline exists only to fill that table.
Deleting an unknown definition SHALL return 404. This endpoint SHALL
require `TABLE_DELETE` in `oidc` mode. That privilege is global-only and SHALL NOT be authorizable by
any per-table grant: delegated destruction of a table is deliberately out of scope.

**Both dependency checks resolve their answer from stored declarations, and therefore cover complete
declarations only** — the same bound this capability states for `PATCH /v1/tables/{name}/schema`,
carried here because the same under-declaration reaches the same guards through a different verb. Since
a declaration may now be stored before it is complete (see
[pipeline-registry](../pipeline-registry/spec.md), "The declaration gate runs when a write arms the
pipeline, not when it is stored"), an incomplete declaration under-declares both its input and its
enrichment reads: a pipeline registered with a target and nothing else names no input at all, so the
table it will eventually read stays deletable, and one whose governed fragments are half written names
only the enrichment columns already in them.

The bound is stated rather than closed for the reason the schema-patch side gives: closing it would mean
refusing a delete on the strength of a declaration that does not resolve, which is a guard with no way to
compute its own answer. The consequence is bounded on the other side instead — completing such a
declaration afterwards **fails at the declaration gate**, with HTTP 422 naming the table or column that
no longer exists, so the cost is lost authoring work on a pipeline that never ran and never a run against
a table that is gone. A pipeline that was ever armed had a complete declaration, so its input and its
enrichment reads are stored and both guards hold for it exactly as before.

The **target** side is unaffected by any of this, and deliberately so: `target` is required at
registration, so every stored pipeline — draft or armed — names one, and deleting that table still
removes the pipeline with it rather than leaving a draft pointing at nothing.

#### Scenario: Delete drops the ClickHouse table

- **WHEN** `DELETE /v1/tables/{name}` is called for a table with no dependents
- **THEN** the catalog rows are removed, `DROP TABLE` is issued in ClickHouse, and the response is 204

#### Scenario: Delete of a source with enrichments is blocked

- **WHEN** `DELETE /v1/tables/{name}` is called for a `source` that has enrichments referencing it
- **THEN** the request is rejected with HTTP 409 and no table is dropped

#### Scenario: Delete of a pipeline's declared read source is blocked

- **WHEN** `DELETE /v1/tables/{name}` is called for a `source` that a pipeline declares as its input, and
  no enrichment references that source
- **THEN** the request is rejected with HTTP 409 naming the pipeline, and no table is dropped

#### Scenario: Delete of an enrichment only an aggregate pipeline reads is blocked

- **WHEN** `DELETE /v1/tables/{name}` names an enrichment whose column an `aggregate` pipeline's group key,
  measure, filter or measure `where` reads, and no `enrich` declaration reads it
- **THEN** the request is rejected with HTTP 409 naming that pipeline, and no table is dropped

#### Scenario: The enrichment becomes deletable once nothing reads it

- **WHEN** the last pipeline reading that enrichment is deleted and the delete is retried
- **THEN** it succeeds — the guard is a dependency, not a lock

#### Scenario: One check answers for both kinds

- **WHEN** `DELETE /v1/tables/{name}` is called for a `source` read by an `enrich` pipeline and by an
  `aggregate` pipeline
- **THEN** the request is rejected with HTTP 409 naming both, from one lookup rather than one per kind

#### Scenario: Delete of a pipeline's input table is blocked

- **WHEN** `DELETE /v1/tables/{name}` is called for a table that a pipeline reads as its input
- **THEN** the request is rejected with HTTP 409 naming the pipeline, and no table is dropped

#### Scenario: Delete of a pipeline's target removes the pipeline

- **WHEN** `DELETE /v1/tables/{name}` is called for a table that is a pipeline's target
- **THEN** the table is deleted and its pipeline is removed with it

#### Scenario: Delete removes the table's access

- **WHEN** `DELETE /v1/tables/{name}` is called for a table that has per-table access roles
- **THEN** the table is deleted and its access lists are removed with it

#### Scenario: A table an incomplete declaration will eventually read stays deletable

- **WHEN** `DELETE /v1/tables/{name}` is called for a `source` with no enrichments that the only
  pipeline destined to read it has not yet named as its input, that pipeline being disabled
- **THEN** the delete succeeds, because both dependency checks resolve readers from stored declarations
  and that declaration declares no such read

#### Scenario: Completing that declaration afterwards fails at the gate rather than reading a deleted table

- **WHEN** the author then declares that table as the pipeline's input and arms or previews the pipeline
- **THEN** the request is rejected with HTTP 422 naming the table that does not exist, so the cost of
  the bound is the authoring work and never a run against a table that is gone

#### Scenario: A complete declaration blocks the delete even while disabled

- **WHEN** `DELETE /v1/tables/{name}` is called for a table that a **complete** declaration names as its
  input or whose enrichment column that declaration reads, and that pipeline is disabled
- **THEN** the request is rejected with HTTP 409 naming that pipeline, and no table is dropped —
  completeness, not enablement, is what arms these guards

### Requirement: Catalog PostgreSQL availability is mapped to the error contract

The system SHALL respond with HTTP 503 and the shared `ErrorView` contract using a stable `error`
code (`postgres_unavailable`) when the PostgreSQL catalog database is unreachable or a data-access
error occurs on a table-management endpoint, without leaking stack traces or raw driver internals.

#### Scenario: Catalog database unavailable

- **WHEN** a `/v1/tables` endpoint is invoked while PostgreSQL is unreachable
- **THEN** the service responds 503 with an `ErrorView` whose `error` code identifies a PostgreSQL
  availability failure

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

Being undeclarable is not being unreported: the read surface SHALL report an `enrichment` as
`upsert_by_key`, the discipline its kind implies, rather than the `append` a stored default holds. What is
reported is the discipline the table *has*, never the one a caller was permitted to state — otherwise the
one kind whose discipline is not caller-settable is the one the catalog describes wrongly, which is
precisely the mis-declaration the next paragraph rules out.

**The facet SHALL describe the table's actual engine, not merely have selected it.** For a table ADAS
materializes the two cannot diverge — the facet is the input that produced the engine, and it is
immutable thereafter. For a table whose DDL is written by hand and registered in the catalog afterwards,
nothing derives one from the other, so the agreement SHALL be established deliberately: a source backed by
a `ReplacingMergeTree` SHALL be declared `upsert_by_key`, and one backed by a plain `MergeTree` SHALL be
declared `append`.

A disagreement is not always self-revealing, and where it does reveal itself it does so as a failure. The
pipeline's own reads deduplicate through a mechanism that asks ClickHouse what engine a table has, so a
mis-declared table keeps behaving correctly there while the catalog explains that behaviour wrongly — and
the wrong explanation is what an operator reasons from. A user-facing read decides from the facet instead
(see [structured-query](../structured-query/spec.md) "Read result deduplication"): a `ReplacingMergeTree`
declared `append` never takes `FINAL`, returning the unmerged versions the engine means to collapse, and a
plain `MergeTree` declared `upsert_by_key` is rendered `FINAL` and fails in ClickHouse. Either way the difference between a raw `SELECT count(*)` and an ADAS read
has no correct interpretation available from the catalog.

The facet SHALL select the engine at materialization:

- **`append`** — a plain `MergeTree ORDER BY (ordering_key)`. Repeated writes of the same key
  accumulate rows.
- **`upsert_by_key`** — a `ReplacingMergeTree(_updated_at) ORDER BY (ordering_key)` carrying the
  version column rendered as `_updated_at DateTime64(3) DEFAULT now64(3)`, so a re-write of an
  existing `ordering_key` collapses to the latest version. The `ordering_key` is therefore the
  table's unique key.

`_updated_at` on an `upsert_by_key` source SHALL be a hidden system column with exactly the standing
system-column semantics: it SHALL NOT appear in the exposed `columns` and SHALL NOT be counted by
`column_count`; it SHALL NOT be declarable in a schema (the reserved `_`-prefix rule in "Schema
validation"); and it SHALL NOT be caller-settable in a row write (the reserved-prefix rule in "Write
rows into a materialized table"), so ClickHouse stamps an increasing version on every write.

Reads of an `upsert_by_key` source SHALL return the latest row per key unless the query opts out: a
structured query that omits `final` reads it final, a SQL reference reads it final when it carries `FINAL`,
and while `query.force-final` is on every user-facing read of it is final whatever the query says (see
[structured-query](../structured-query/spec.md) "Read result deduplication"). A structured query sending
`"final": false`, or a SQL reference without `FINAL`, with the flag off reads its raw parts, unmerged
versions included.

**The seeded sources.** `dial_usage_log` and `rate_analytics` are materialized by hand-written ClickHouse
DDL as `ReplacingMergeTree(_ingested_at)` ordered by `(project_id, deployment, request_time, trace_id,
core_span_id)`, so both SHALL be declared `upsert_by_key`. The collapse is the point of that engine
choice: the sort tuple is the ingest path's re-ingest dedup key, so a log line delivered twice resolves to
one row on the greatest `_ingested_at` instead of being counted twice.

The facet defaults to `append`, so a source seeded without an explicit value claims a discipline it may
not have — the mis-declaration this requirement rules out. Because the facet is immutable through the
API, a seeded value SHALL be set or corrected by catalog migration; doing so changes no engine, issues no
`ALTER`, and moves no row.

#### Scenario: Create a source with an explicit write discipline

- **WHEN** `POST /v1/tables` is called with `{name, type:"source", write:"upsert_by_key"}`
- **THEN** the response is 201 with `status=PENDING` and the definition carries
  `write: "upsert_by_key"`

#### Scenario: Write discipline defaults to append

- **WHEN** `POST /v1/tables` is called with `{name, type:"source"}` and no `write` member
- **THEN** the created definition carries `write: "append"`

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
  version column

#### Scenario: Write discipline on an enrichment create is rejected

- **WHEN** `POST /v1/tables` is called with `type:"enrichment"` and a `write` member
- **THEN** the request is rejected with HTTP 422 (an enrichment's write discipline is implied by its
  kind)

#### Scenario: An enrichment reports the discipline its kind implies

- **WHEN** `GET /v1/tables/{name}` or `GET /v1/tables` is called for an `enrichment`
- **THEN** it reports `write: "upsert_by_key"`, matching the `ReplacingMergeTree(_updated_at)` its kind
  materializes as — not the `append` default of a facet that is not settable for it

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
  measure values and the parts have not merged, then the table is queried by a structured query that
  omits `final`
- **THEN** the query returns one row for that key carrying the values from the later write

#### Scenario: The seeded sources declare the engine they were built with

- **WHEN** the catalog is read after the table-catalog migrations run
- **THEN** `dial_usage_log` and `rate_analytics` both report `write: "upsert_by_key"`, matching the
  `ReplacingMergeTree` their ClickHouse DDL creates

#### Scenario: Correcting a seeded facet changes no behaviour

- **WHEN** a seeded source's `write` facet is corrected to match its engine
- **THEN** no `ALTER` is issued and no row moves; every pipeline read returns the same rows it would have
  returned under the uncorrected facet, because its deduplication is the engine's; and a user-facing read
  now resolves an absent `final` and accepts `FINAL` from the corrected facet, as the engine allows

#### Scenario: An upsert-by-key source read raw returns unmerged versions

- **WHEN** `query.force-final` is off, an `upsert_by_key` source holds two not-yet-merged writes of one
  `ordering_key`, and a SQL statement without `FINAL` selects that key
- **THEN** both writes are returned; the same statement with `FINAL` returns only the later one

### Requirement: Seeded column nullability matches the physical table

The catalog's `nullable` flag on a built-in table SHALL agree with the column's physical
ClickHouse type: a column materialized as `Nullable(T)` SHALL be seeded `nullable = true`, and one
materialized as a bare `T` SHALL be seeded `nullable = false`.

The `dial_usage_log` seed SHALL therefore record as nullable exactly the columns its DDL declares
`Nullable(...)`: `core_parent_span_id`, `parent_deployment`, `response_upstream_uri`, `prompt_tokens`,
`completion_tokens`, `total_tokens`, `cached_prompt_tokens`, `cache_creation_tokens`, `total_price`,
`deployment_price`, `route_name`, and `route_type`.

The `dial_usage_log_payload` seed SHALL record all three of its columns as nullable — they are
`Nullable(String)`, so that "the producer sent nothing" stays distinguishable from "the producer sent
empty". Their nullability is also what makes them readable by a pipeline through the grain join, which
a non-nullable enrichment column is not.

The agreement holds against the table's *current* physical type, not the type it was created with: when
a migration changes a column's nullability, a paired catalog update SHALL follow it in the same change,
so the two never disagree between releases. Moving a column's catalog row between tables SHALL likewise
preserve the flag, since the physical type it describes is unchanged.

The agreement is a requirement rather than a data detail because two independent consumers read the flag
and neither can detect that it is wrong:

- **Discovery publishes it.** A consumer told a column cannot be null, which then returns null, holds a
  schema its own data contradicts.
- **Governance reasons from it.** A rule that decides anything from a column's nullability — such as the
  aggregate pipeline's requirement that a nullable measure land in a nullable target column, or the
  enrichment-read rule that refuses a non-nullable enrichment column — is inert on precisely the
  columns that need it if the catalog understates nullability. That is the quiet direction of the
  error: nothing is rejected, and nothing reports that nothing was checked.

The flag defaults to `FALSE`, so a column seeded without an explicit value claims non-nullability it may
not have. A seed SHALL therefore set it from the DDL rather than relying on the default.

Nullability is fixed at DDL, so aligning the catalog SHALL be a `column_mapping` update only and SHALL
NOT materialize as an `ALTER`. This is the same shape as the seeded `sensitive` flag, and unlike it, the
value is not an operator's to choose — the physical column decides it.

#### Scenario: The seeded flag agrees with the physical column

- **WHEN** the catalog is read after the table-catalog migrations run
- **THEN** `dial_usage_log` reports `total_tokens`, `total_price` and the other physically-`Nullable`
  columns with `nullable: true`, and its non-nullable columns with `nullable: false`

#### Scenario: The moved body columns keep their nullability

- **WHEN** the catalog is read after the payload-split migrations run
- **THEN** `dial_usage_log_payload` reports all three of its columns with `nullable: true`, and
  `dial_usage_log` reports none of them at all

#### Scenario: A column made nullable by a later migration

- **WHEN** a ClickHouse migration changes a `dial_usage_log` column from `T` to `Nullable(T)`
- **THEN** the catalog reports that column as `nullable: true` once the catalog migrations of the same
  release have run, without any `ALTER` being issued from the catalog side

#### Scenario: Columns added nullable by a later migration are seeded nullable

- **WHEN** the catalog is read after the migrations that add `route_name` and `route_type` run
- **THEN** `dial_usage_log` reports both columns with `nullable: true`, matching their
  `LowCardinality(Nullable(String))` physical type

#### Scenario: A governance rule keyed on nullability sees the true value

- **WHEN** an aggregate pipeline declares a `sum` over `dial_usage_log.total_tokens` into a non-nullable
  target column
- **THEN** it is rejected, because the catalog reports the input column as nullable — the refusal the
  rule exists for, which an understated flag would have skipped silently

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

### Requirement: Define and materialize a table schema

The system SHALL expose `POST /v1/tables/{name}/schema` to define the complete physical schema of a
not-yet-materialized table AND materialize it (issue `CREATE TABLE`, flip to `ACTIVE`) in a single
call. The endpoint SHALL be allowed only while the table's `status` is `PENDING` or `FAILED`; on an
already `ACTIVE` (materialized) table it SHALL be rejected with HTTP 409 (a materialized schema evolves
through `PATCH /v1/tables/{name}/schema`).

The request body SHALL carry the table's physical schema: `columns` (the exposed columns, each
`{source_name, name, type, element_type?, nullable?, description?, display_name?, tag?, sensitive?,
heavy?}`)
plus the physical keys for the table's kind — `ordering_key`, optional `partition_by`, and the optional
scan-metadata pair `identity_column`/`version_column` for a
`source`; `grain_key` and optional `cardinality` for an `enrichment` — and an optional `ttl`
declaration `{column, interval, unit}` (see "Table TTL" for its validation and rendering rules).
The `name`, `type`, `write`, and `source_table` (identity/relationship) SHALL NOT appear in this body
(they are set and fixed by `POST /v1/tables`).

`identity_column` and `version_column` are the pair the governed incremental scan pages a source by (see
[enrichment-runtime](../enrichment-runtime/spec.md)).

A `source` MAY declare them, and a source that declares neither is simply **not scannable** — the scan
answers 400 naming the missing configuration (see
[enrichment-runtime](../enrichment-runtime/spec.md)), and every other operation on the table is unaffected.
Declaring them SHALL NOT be required, because the identity half cannot always be declared truthfully: a
rollup's row identity is its whole `ordering_key`, so a source keyed on several columns has no single column
that is unique per row. Requiring one would only ever have produced a declaration naming part of the key,
and a repeated cursor tuple makes keyset paging **skip** rows rather than report anything — the failure the
pair exists to prevent. Such a table SHOULD therefore declare neither member and be scanned, if at all, on
a single-column grain.

When declared, each SHALL be a column the scan can actually page by. The system SHALL reject anything else
with HTTP 422:

- each SHALL reference a column declared in the same request — except that an `upsert_by_key` source MAY
  name the ReplacingMergeTree version `_updated_at`, the engine's own version column, which such a table
  physically carries and an `append` source does not (naming it on an `append` source SHALL be rejected
  with a message naming the write discipline, not merely "not a declared column");
- the `version_column` SHALL be declared `timestamp`: the scan orders and safety-lags a source by this
  column as a millisecond timestamp (`toUnixTimestamp64Milli(version)` compared against `now64(3)`), which
  no other declarable type answers;
- neither SHALL be nullable: a `NULL` makes both the safety-lag predicate and the keyset tuple compare
  `NULL`, so such a row would be silently absent from every page rather than reported;
- neither SHALL be `sensitive`: the scan projects both cursor columns and carries the identity inside its
  cursor, so a rule over that source could be operated only by a caller entitled to sensitive columns.

Those are the properties this endpoint can see, and a source declaring the pair is therefore *shaped* for
the scan — not proven correct for it. That the version column is assigned at ingest, monotonic and never
backdated, and that the identity column is unique per row, remain the declaring caller's promise: they are
physical facts about ingest, and the scan's exactly-once guarantee rests on them. That the promise is
optional is what keeps it meaningful — a caller declares the pair when it holds, rather than to satisfy a
required field.

An `enrichment` SHALL declare NEITHER member (422 for either, naming it): it is keyed on its `grain_key`,
and its row version is the `ReplacingMergeTree` column the platform stamps, so neither name could resolve
to anything the caller declared. An omitted member SHALL leave any stored value unchanged, so re-posting a
`FAILED` table's schema never silently clears one.

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
write is stamped server-side and duplicate writes for the same key collapse to the latest on a
`final=1` read — an ordering at the stamp's millisecond resolution, so writes of one key landing inside
a single millisecond tie and a tie leaves the surviving row unspecified (see `data-sync`'s "Supported
ClickHouse server version" for the client setting this depends on) — with a `TTL` clause appended when a `ttl` is declared — and flip
the definition to `status=ACTIVE`, returning the materialized definition. A `type=array` column SHALL
materialize as ClickHouse `Array(<element>)`. A `type=timestamp` column SHALL materialize as
`DateTime64(3, 'UTC')` — the zone SHALL be declared rather than left to the ClickHouse server's, because
the read path converts the zoneless value the driver surfaces to epoch milliseconds at UTC, and a time
bucket materialized into such a column would otherwise re-bucket on read in the server's zone rather than
the one it was computed in. If the `CREATE TABLE` fails or its outcome is ambiguous
(e.g. a timeout under which ClickHouse may still create the table), the system SHALL issue a
best-effort `DROP TABLE IF EXISTS`, set the definition to `status=FAILED`, and return an error using
the `ErrorView` contract without leaking ClickHouse internals; a `FAILED` table MAY be re-posted
(with its full schema) to retry. In `oidc` mode the endpoint SHALL require `TABLE_MODIFY`, held
globally or granted on the table, and a `system`-owned table SHALL reject it with HTTP 409 whatever the
caller holds. Declaring any column `sensitive` here SHALL additionally require `TABLE_SENSITIVE` on the
table, globally or granted on the `PENDING` table. Otherwise the request SHALL be rejected with
`403 forbidden` and nothing is materialized. This is the same rule as on `add` (see "Modify table
schema via PATCH"): anything that touches sensitivity needs the clearance, and it keeps an author from
creating a column it could not then see.

#### Scenario: Defining a sensitive column needs the clearance

- **GIVEN** a `PENDING` table whose `modify` list contains the caller's provider role and whose
  `sensitive` list does not
- **WHEN** the caller `POST`s a schema declaring one column `sensitive: true`
- **THEN** the response is `403 forbidden`, and the table stays `PENDING` with no physical table created
- **AND WHEN** the caller's role is added to the table's `sensitive` list and the request is repeated
- **THEN** the table is materialized with that column flagged sensitive

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

#### Scenario: A source declares its scan metadata

- **WHEN** the same request also carries `identity_column` and `version_column`, each naming a column it
  declares
- **THEN** both are persisted on the definition, returned by `GET /v1/tables/{name}`, and a rule over that
  source scans it — with no migration in between

#### Scenario: A source declaring no scan metadata materializes and is not scannable

- **WHEN** `POST /v1/tables/{name}/schema` is called on a `PENDING` source with `columns` and a valid
  `ordering_key` but neither scan column — or only one of the two
- **THEN** the table materializes and becomes `ACTIVE`, `GET /v1/tables/{name}` omits whichever member was
  not declared, and a scan of a rule over that source is refused with the 400 that names the missing
  scan-metadata configuration

#### Scenario: An upsert-by-key source may declare _updated_at as its version

- **WHEN** an `upsert_by_key` source's schema request carries `version_column: "_updated_at"`
- **THEN** the value is persisted and returned by `GET /v1/tables/{name}`
- **AND** the same `version_column` on an `append` source is rejected with HTTP 422, naming the write
  discipline as the reason (it carries no `_updated_at`)

#### Scenario: A scan column the scan could not page by is rejected

- **WHEN** a source's schema request declares a `version_column` that is not a `timestamp` column, or
  either member naming a column declared `nullable: true`, or either member naming a `sensitive` column
- **THEN** the request is rejected with HTTP 422 naming the field and the property that disqualifies it, no
  ClickHouse DDL is issued, and the definition remains `PENDING`/`FAILED`

#### Scenario: A scan-metadata column absent from the declared columns is rejected

- **WHEN** `POST /v1/tables/{name}/schema` carries an `identity_column` or `version_column` naming a
  column the request does not declare
- **THEN** the request is rejected with HTTP 422 naming that column, no ClickHouse DDL is issued, and the
  definition remains `PENDING`/`FAILED`

#### Scenario: Neither scan column is accepted for an enrichment

- **WHEN** `POST /v1/tables/{name}/schema` carries an `identity_column`, or a `version_column`, for an
  `enrichment`
- **THEN** the request is rejected with HTTP 422 naming the member — the identity because the table is
  keyed on its `grain_key`, the version because the platform stamps it — and nothing is persisted

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

### Requirement: Table responses carry caller-effective permissions

Each table returned by `GET /v1/tables` (every list item) and `GET /v1/tables/{name}` SHALL carry a
`permissions` object with boolean `get`, `read`, `sensitive`, `write`, `modify` and `rows_restricted`
fields describing what the calling identity may do with that table, computed per the
caller-effective permissions rules of
[table-access-control](../table-access-control/spec.md). All six fields SHALL always be present
booleans (never null/absent). The object grew from two flags to five with the per-table grants, and to
six with row policies; the earlier names keep their spelling and meaning, so a client reading only those
is unaffected. `rows_restricted` reports whether row policies confine the caller's reads of the table.
`sensitive` reports the clearance (see `table-access-control`) and replaces the former `read_sensitive`
flag, which SHALL NOT be emitted.
Mutation responses that return the table representation (create, description update, schema PATCH)
SHALL carry the same object.

#### Scenario: List items carry permissions

- **WHEN** `GET /v1/tables` is called
- **THEN** every list item carries a `permissions` object with all six boolean fields

#### Scenario: Single get carries permissions

- **WHEN** `GET /v1/tables/{name}` is called for an existing definition
- **THEN** the response carries a `permissions` object with all six boolean fields

#### Scenario: Create response reports the creator's permissions

- **WHEN** an authorized caller creates a table via `POST /v1/tables`
- **THEN** the 201 response reports the creator's own effective permissions on the new table — `get`,
  `read`, `sensitive`, `write` and `modify` all `true` for a caller holding every table-scoped
  privilege, and `false` for any the caller does not hold, since `TABLE_CREATE` alone confers none of
  them — and `rows_restricted = false`, because a new table carries no row policy

### Requirement: Seeded column sensitivity on the built-in source tables

The catalog migrations SHALL seed the `sensitive` flag on the built-in tables so that columns
carrying caller identity or raw user content are restricted to a caller holding the clearance
`TABLE_SENSITIVE` on the table that owns them, globally or granted on that table, alongside the verb
it uses. The restriction applies by
default (see the `authentication` capability's "Sensitive column authorization" for the enforcement
rule). The seeded sensitive set SHALL be `dial_usage_log_payload.jwt_claims`,
`dial_usage_log_payload.request_tags`, `dial_usage_log_payload.request_body`,
`dial_usage_log_payload.response_body`, `dial_usage_log_payload.assembled_response`,
`dial_usage_log_payload.request_envelope`, `dial_usage_log_payload.request_delta`,
`dial_usage_log_payload.request_cache_markers`, and `rate_analytics.comment`. Every other seeded column SHALL
default to `sensitive = false`.

The three request-delta columns SHALL be seeded sensitive on the same grounds as `request_body`: under
`sync.request-body-mode=delta` they hold that body's content — the envelope, the new history elements, and the
cache markers taken out of them — so storing a request in parts SHALL NOT relax its sensitivity.

All eight SHALL carry the flag on `dial_usage_log_payload` because that is where they are stored;
re-homing them SHALL NOT relax it. `rate_analytics.comment` SHALL therefore be the only sensitive
SOURCE column left in the seeded catalog — which matters for any surface reporting an entity's
wildcard schema, since an enrichment column is absent from one for every caller regardless of role. Enforcement is unchanged by the move: the flag is applied
when the column enters the query model, and an enrichment column enters it under its qualified name,
so an unentitled caller sees neither spelling and a reference to either fails as an unknown field.

`dial_usage_log_payload.assembled_response` SHALL be seeded sensitive on the same grounds as the two
body columns: it stores the model's answer verbatim, which is user-visible content, and for a streamed
call it is the *only* form in which that answer can be read without reassembling a chunk transcript.
That it is a merged rather than a raw payload changes its shape, not its sensitivity.

`dial_usage_log_payload.request_tags` SHALL be seeded sensitive because it stores the producer's request
`headers` object verbatim — the ingest mapping applies no allowlist or denylist — so it may carry
credentials, cookies, forwarded client addresses, and per-user correlation identifiers. It is
classified by what it stores rather than by its intended "routing metadata" role.

`dial_usage_log.user_hash` SHALL NOT be seeded sensitive: it is a de-identified per-user surrogate
whose purpose is to let unentitled callers run per-user analytics (distinct-user counts, per-user
rollups) without exposing identity.

Sensitivity is catalog metadata, not an immutable property: a caller holding `TABLE_MODIFY` and
`TABLE_SENSITIVE` on the table MAY change a column's flag via `PATCH /v1/tables/{name}/schema`, and the
catalog row is thereafter authoritative.
Seeding is applied by a `column_mapping` update only — the flag is never part of the ClickHouse DDL, so
no seeding change materializes as an `ALTER`. The seeded tables are the exception in practice: all
three are `system`, so every modifying `/v1/tables` operation on them is refused and their seeded flags
are changed only by a further migration.

#### Scenario: Seeded sensitivity is present after migration

- **WHEN** the catalog is read after the table-catalog migrations run
- **THEN** `dial_usage_log` reports `jwt_claims` and `request_tags` with `sensitive: true` and
  `user_hash` with `sensitive: false`, `dial_usage_log_payload` reports all eight of its exposed columns with
  `sensitive: true`, and `rate_analytics` reports `comment` with `sensitive: true`

#### Scenario: Management responses expose the seeded flag unfiltered

- **WHEN** a caller holding `TABLE_GET` and `TABLE_SENSITIVE` reads `GET /v1/tables/dial_usage_log_payload`
- **THEN** the response lists `request_tags` with `sensitive: true`
- **AND WHEN** a caller holding `TABLE_GET` but not `TABLE_SENSITIVE` reads the same table
- **THEN** `request_tags` is absent from its `columns`

#### Scenario: A seeded sensitive column can be unrestricted by an operator

- **WHEN** a caller holding `TABLE_MODIFY` and `TABLE_SENSITIVE` sends `PATCH /v1/tables/{name}/schema` with
  `{"update":[{"name":"<column>","sensitive":false}]}` for a **non-`system`** table whose column was
  flagged sensitive
- **THEN** the request succeeds without any ClickHouse `ALTER`, and the column is thereafter visible to
  unentitled callers on the query surface — while the same request against a seeded `system`
  table is refused with HTTP 409

#### Scenario: A non-admin caller sees no spelling of a body column

- **WHEN** a caller without `TABLE_SENSITIVE` on the owning table reads
  `GET /v1/queries/entities/schema/dial_usage_log` and
  then queries `"dial_usage_log_payload.request_body"`
- **THEN** the schema carries no body column, and the query is rejected as an unknown field without
  revealing that the column exists

### Requirement: Table TTL

The system SHALL support an optional per-table **TTL** (row expiry) on materialized tables,
declared as a structured object `{column, interval, unit}` — never a free-form ClickHouse
expression. `column` SHALL name a declared **non-nullable** column of the table of type `date` or
`timestamp`; `interval` SHALL be a positive integer; `unit` SHALL be one of `hour`, `day`, `week`,
`month`, `year`. The TTL SHALL render in ClickHouse DDL as
`TTL <source_name> + INTERVAL <interval> <UNIT>` for a `date` column and
`TTL toDateTime(<source_name>) + INTERVAL <interval> <UNIT>` for a `timestamp` column (ClickHouse
requires the TTL expression to be a non-nullable `Date`/`DateTime`; a bare `DateTime64` or a
nullable column is rejected server-side, so the system SHALL reject both at validation time). A
violation of any of these semantic constraints — an unknown, nullable, or non-time `column`, or a
zero/negative `interval` — SHALL be rejected with HTTP 422 and the `ErrorView` contract, before
any DDL is issued or anything is persisted. A `unit` value outside the enumerated set fails
request binding and SHALL be rejected with HTTP 400 `bad_request`, the codebase-wide
malformed-body convention (the same as any other unbindable enum member, e.g. a `partition_by`
granularity).

The TTL SHALL be declared and modified only through the schema endpoints: the optional `ttl`
member of `POST /v1/tables/{name}/schema` (declared at materialization, rendered into the
`CREATE TABLE`) and the optional `ttl` member of `PATCH /v1/tables/{name}/schema` (set, replace,
or remove on a live table — see "Modify table schema via PATCH"). `POST /v1/tables` (create)
SHALL reject a supplied `ttl` with HTTP 422 like every other physical-schema field. No dedicated
TTL endpoint SHALL exist.

The TTL SHALL be catalog metadata as well as physical DDL: it SHALL be persisted on the table's
`table_definition` row (nullable — unset means no TTL; stored either fully or not at all) and
returned as a `ttl` object by `GET /v1/tables/{name}`; consistent with `NON_NULL` serialization,
an unset TTL SHALL be omitted from responses. The seeded `system` tables' TTLs
SHALL NOT be mirrored into the catalog, and the API SHALL NOT modify them: they are provisioned by
the migrations, and `dial_usage_log`'s and `dial_usage_log_payload`'s can be changed through the
`SYNC_USAGE_LOG_TTL_DAYS` and `SYNC_PAYLOAD_TTL_DAYS` properties (see the data-sync capability).

TTL-based row expiry is applied by ClickHouse lazily (on merges), so rows older than the TTL MAY
remain visible for some time after a TTL change — the system SHALL NOT force immediate
re-materialization.

#### Scenario: GET returns the TTL

- **WHEN** `GET /v1/tables/{name}` is called for a table with a TTL set
- **THEN** the response carries the `ttl` object `{column, interval, unit}`; a table without a
  TTL omits the member

#### Scenario: TTL column must be a non-nullable date or timestamp column

- **WHEN** a TTL declaration (via `POST .../schema` or `PATCH .../schema`) names a column that is
  not a declared column of the table, is nullable, or is not of type `date` or `timestamp`
- **THEN** the request is rejected with HTTP 422 and nothing is persisted or issued

#### Scenario: Non-positive interval is rejected

- **WHEN** a TTL declaration carries a zero or negative `interval`
- **THEN** the request is rejected with HTTP 422 and nothing is persisted or issued

#### Scenario: Unknown unit fails request binding

- **WHEN** a TTL declaration carries a `unit` outside {`hour`, `day`, `week`, `month`, `year`}
- **THEN** the request is rejected with HTTP 400 `bad_request` (the value does not bind to the
  unit enum, the malformed-body convention)

#### Scenario: TTL at create is rejected

- **WHEN** `POST /v1/tables` is called with a body carrying a `ttl` member
- **THEN** the request is rejected with HTTP 422 (the TTL is declared via the schema endpoints)

#### Scenario: Timestamp TTL column is wrapped in the rendered DDL

- **WHEN** a TTL over a `timestamp` column is declared (at materialization or via PATCH)
- **THEN** the rendered DDL uses `toDateTime(<column>) + INTERVAL <n> <UNIT>`, while a `date`
  column is used bare

### Requirement: Heavy column metadata

Each exposed column SHALL carry a boolean `heavy` flag, default `false`, marking the column as
expensive to transfer (a large payload/blob column). Like `tag`, `display_name`, and `sensitive`, it
SHALL be **catalog-only metadata**: it SHALL NOT appear in any ClickHouse DDL, SHALL NOT be validated
against the physical column type, and changing it SHALL move no data and issue no `ALTER`.

The flag SHALL be declarable wherever column metadata is declared — a `columns` entry of
`POST /v1/tables/{name}/schema` and an `add` entry of `PATCH /v1/tables/{name}/schema` (defaulting to
`false` when omitted) — and changeable on a materialized table through the `PATCH` `update`
merge-patch. It SHALL be returned in the column payloads of `GET /v1/tables/{name}` unfiltered by
role, and preserved across a `rename`. The flag SHALL be settable on any
exposed column, including a system, grain, or ordering-key column.

`heavy` SHALL be a **cost hint, not an access control**: it SHALL NOT hide the column from any
caller and SHALL NOT restrict any clause. Its only effect is on the query surfaces' wildcard
projection (see the `structured-query` and `sql-query-translation` capabilities): a heavy column is
omitted from a wildcard expansion and returned normally when named explicitly. Confidentiality stays
governed exclusively by `sensitive`.

#### Scenario: Heavy declared at schema definition

- **WHEN** a `POST /v1/tables/{name}/schema` body declares one column with `heavy: true` and the
  others without the member
- **THEN** the table materializes with no reference to heaviness in the issued `CREATE TABLE`, and
  the catalog records `heavy: true` for that column and `false` for the rest

#### Scenario: Heavy toggled on a materialized table

- **WHEN** `PATCH /v1/tables/{name}/schema` sends `{"update": [{"name": "payload", "heavy": true}]}`
  on an `ACTIVE` table
- **THEN** the response is 200, no ClickHouse `ALTER` is issued, and `GET /v1/tables/{name}` reports
  that column with `heavy: true`

#### Scenario: Heavy is settable on a protected column

- **WHEN** an `update` entry marks an ordering-key column or a `_`-prefixed system column `heavy: true`
- **THEN** the request succeeds — the column-mutability restrictions apply to `drop`/`rename`, not to
  metadata

#### Scenario: Heavy does not restrict visibility

- **WHEN** a column is marked `heavy: true` and `sensitive: false`, and a caller holding `TABLE_READ`
  but not `TABLE_SENSITIVE`
  reads the query schema and then queries that column by name
- **THEN** the column is present in the discovered schema and the query returns its values — the flag
  changes only what a wildcard expands to

### Requirement: Seeded heavy columns on the built-in source tables

The catalog migrations SHALL seed the `heavy` flag on the built-in tables' raw-payload columns
so that a wildcard projection over the seeded schema is cheap by default. The seeded heavy set SHALL
be `dial_usage_log_payload.request_body`, `dial_usage_log_payload.response_body`,
`dial_usage_log_payload.assembled_response`, `dial_usage_log_payload.request_envelope`,
`dial_usage_log_payload.request_delta` and `dial_usage_log_payload.request_cache_markers` — the verbatim
LLM request, the verbatim response, the producer's merged response, and the three parts a chat request is
stored as under `sync.request-body-mode=delta` (see the data-sync capability), the only seeded columns whose
per-row size is unbounded. Every other
seeded column SHALL default to `heavy = false`, including the `object`-typed
`dial_usage_log_payload.jwt_claims` and `dial_usage_log_payload.request_tags` (small claim/header
documents) and `rate_analytics.comment` (a short free-text note). `heavy` and payload ownership SHALL
remain independent: the claim/header pair lives on the enrichment without being heavy.

All six seeded heavy columns are also seeded `sensitive` (see "Seeded column sensitivity on the
built-in source tables"), so for a caller without `TABLE_SENSITIVE` on the owning table they are
already absent from the
model and the heavy flag changes nothing.

For an entitled caller the flag is now belt-and-braces on a wildcard over `dial_usage_log`: every
heavy seeded column is an enrichment column, and a wildcard never fans out into an enrichment join, so
the six are omitted whether or not they are flagged. The flag remains load-bearing where the
enrichment rule does not reach — a wildcard's expansion is still reported with `heavy` in discovery,
the `validate-sql` heavy-column warning is keyed on it, and an operator flipping it through the API
still changes what a wildcard over a non-seeded table returns.

The claim and header columns are excluded from a wildcard over `dial_usage_log` too, but by the
enrichment rule alone rather than by the heavy flag — which is what shows the two rules are
independent.

#### Scenario: Seeded heavy flags after migration

- **WHEN** the catalog is read after the table-catalog migrations run
- **THEN** `dial_usage_log_payload` reports `request_body`, `response_body`, `assembled_response`,
  `request_envelope`, `request_delta` and `request_cache_markers` with `heavy: true`, and every other column of `dial_usage_log`, `dial_usage_log_payload` and
  `rate_analytics` with `heavy: false`

#### Scenario: Management responses expose the seeded heavy flag

- **WHEN** a caller holding `TABLE_GET` reads `GET /v1/tables/dial_usage_log_payload`
- **THEN** the response lists `response_body` with `heavy: true` alongside its `sensitive: true`, so
  an operator can audit which columns a wildcard omits

#### Scenario: A wildcard omits the merged response

- **WHEN** an entitled caller runs a wildcard projection over `dial_usage_log`
- **THEN** no row carries an `assembled_response`, `request_body`, `response_body`, `jwt_claims` or
  `request_tags` key under any spelling — the last two excluded for being enrichment columns despite
  not being heavy — and naming any of them by its qualified name returns its value in full

### Requirement: Map column type

The catalog column-type vocabulary SHALL include `map`, materialized as ClickHouse
`Map(String, String)` — an unordered set of string-keyed string values, which is what OpenTelemetry-style
attribute bags need (see [otel-sink](../otel-sink/spec.md)). A `map` column declares no `element_type`
and takes no key/value type parameters; an `element_type` on a `map` column SHALL be rejected.

Because ClickHouse has no `Nullable(Map(...))`, a `map` column SHALL always be non-nullable: an explicit
`nullable: true` on a `map` column SHALL be rejected with HTTP 422, and an omitted `nullable` SHALL
resolve to `false` for a `map` column even on an enrichment (whose other columns default nullable) —
the same rule an `array` column already follows. `map` SHALL NOT be a valid `array` `element_type`, and
SHALL NOT be usable as a table's `ordering_key`, `partition_by`, `grain_key`, TTL column,
`identity_column` or `version_column`.

A `map` value written through `POST /v1/tables/{name}/rows` SHALL be a JSON object whose values are
strings; the write path SHALL pass it through unchanged, exactly as it does for every non-`timestamp`
type.

#### Scenario: Map column is declared and materialized

- **WHEN** `POST /v1/tables/{name}/schema` declares a column `attributes` of type `map`
- **THEN** the table is materialized with that column as ClickHouse `Map(String, String)` and
  `GET /v1/tables/{name}` reports the column with `type` `map` and no `element_type`

#### Scenario: Nullable map column is rejected

- **WHEN** a schema declares a `map` column with `nullable: true`
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error`, and nothing is persisted

#### Scenario: Map is not a valid array element type

- **WHEN** a schema declares an `array` column with `element_type` `map`
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error`

#### Scenario: Map column cannot key the table

- **WHEN** a schema names a `map` column in its `ordering_key`, as its `partition_by`, or as an
  enrichment's `grain_key`
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error` before anything is
  persisted — the definition stays `pending` with no columns, rather than the `CREATE TABLE` failing
  in ClickHouse and leaving a `failed` definition behind

#### Scenario: Object column cannot key the table

- **WHEN** a schema names an `object` column in its `ordering_key`, as its `partition_by`, or as an
  enrichment's `grain_key`
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error` before anything is
  persisted, on the same grounds as a `map` column: ClickHouse has no ordering over `JSON`, so the
  `CREATE TABLE` would fail raw and leave a `failed` definition behind

#### Scenario: Rows carry map values as JSON objects

- **WHEN** `POST /v1/tables/{name}/rows` writes a row whose `attributes` value is
  `{"http.method": "GET"}`
- **THEN** the row is stored with that map, and a query projecting `attributes` returns the same object

### Requirement: Enum column type

The catalog column-type vocabulary SHALL include `enum` — a closed, ordered set of string values,
materialized as ClickHouse `Enum8`/`Enum16`. An `enum` column SHALL declare `enum_values`: an
ordered, non-empty list of distinct strings, declared on `POST /v1/tables/{name}/schema` and on a
`PATCH /v1/tables/{name}/schema` `add` entry. This mirrors the parameter shape `array` already
uses: `enum_values` is required iff `type` is `enum`, and SHALL be rejected on a column of any
other type.

An `enum` column SHALL materialize as `Enum8('<v1>' = 1, '<v2>' = 2, …)` when it declares at most
127 values, and `Enum16(…)` above that, with each value assigned its 1-based position in the
declared list as its numeric id. Enum columns follow the ordinary nullability rules — a source
column defaults non-nullable, an enrichment column defaults nullable, an explicit `nullable` wins —
and a nullable enum column materializes as `Nullable(Enum8(…))`/`Nullable(Enum16(…))`. Each value
is embedded in the DDL as a single-quoted literal with any single quote **and any backslash**
escaped, so a value containing either materializes faithfully rather than altering the statement.

Declaration SHALL be rejected with HTTP 422 and `error` `validation_error`, before anything is
persisted, when: a column of type `enum` declares no `enum_values`; a column of any other type
declares `enum_values`; the list is empty; any value is null or blank; two values are equal after
trimming; any value is longer than 64 characters; or the list holds more than 512 values. `enum`
SHALL NOT be a valid `array` `element_type`.

Declared values SHALL be **trimmed**, and the trimmed form is canonical: it is what materializes into
the DDL, what the catalog stores, and what every read surface returns — on the whole-table define path
and on the `PATCH .../schema` `add` path alike. The two SHALL NOT diverge; a column whose physical
domain held ` high ` while its catalog row advertised `high` would refuse the value it publishes, with
no repair available, since the domain is immutable.

`enum_values` SHALL be returned wherever a column's shape is returned — `GET /v1/tables/{name}`,
`GET /v1/tables` when column definitions are requested, and the historical snapshot
`GET /v1/tables/{name}/revision/{revision}` — as a JSON array in its declared order, and absent or
null for a column that is not an `enum`. Declaration order is significant: it is both the DDL id
assignment and the order returned, so it is preserved exactly as declared and never sorted.

The declared domain SHALL be immutable for the life of the column. A `PATCH .../schema` `update`
entry carrying `enum_values` SHALL be rejected with HTTP 422 and `error` `validation_error` rather
than silently ignored, because changing a ClickHouse enum's domain rewrites the column and can fail
against rows already stored; a domain is widened by dropping and re-adding the column, the same way
a column's type is changed. A `PATCH .../schema` `rename` SHALL carry the column's `enum_values`
across unchanged, along with its type, nullability and other metadata.

An `enum` column MAY be named in a table's `ordering_key` or as an enrichment's `grain_key`:
ClickHouse compares an enum by its numeric id, so ordering follows the **declared order of the
values**, not their alphabetical order. An `enum` column SHALL NOT be a table's TTL column or a
source's `version_column`, which require a `timestamp`.

A row written through `POST /v1/tables/{name}/rows` SHALL supply an `enum` column's value as a JSON
string, exactly as a `string` column takes; the write path passes it through unchanged. A value
outside the declared domain is rejected by ClickHouse, and SHALL surface through the endpoint's
existing row-write failure contract rather than a new error code.

#### Scenario: Enum column is declared and materialized

- **WHEN** `POST /v1/tables/{name}/schema` declares a column `status` of type `enum` with
  `enum_values: ["pending", "running", "failed"]`
- **THEN** the table is materialized with that column as ClickHouse
  `Enum8('pending' = 1, 'running' = 2, 'failed' = 3)` and `GET /v1/tables/{name}` reports the
  column with `type` `enum` and `enum_values` in that same order

#### Scenario: Nullable enum column materializes wrapped

- **WHEN** a schema declares an `enum` column with `nullable: true`
- **THEN** the column is materialized as `Nullable(Enum8(…))` and reported as `nullable: true`

#### Scenario: Enum column without values is rejected

- **WHEN** a `POST .../schema` or a `PATCH add` declares a column of type `enum` with no
  `enum_values`
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error`, and nothing is
  persisted

#### Scenario: Enum values on a non-enum column are rejected

- **WHEN** a `POST .../schema` or a `PATCH add` declares a column of type `string` (or any type
  other than `enum`) carrying `enum_values`
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error`

#### Scenario: Empty, blank, or duplicate enum values are rejected

- **WHEN** a schema declares an `enum` column whose `enum_values` is an empty list, contains a
  blank value, or lists the same value twice
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error`

#### Scenario: Oversized enum domain is rejected

- **WHEN** a schema declares a column whose `enum_values` holds more than 512 values, or any single
  value longer than 64 characters
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error`

#### Scenario: Enum domain above the Enum8 range uses Enum16

- **WHEN** a schema declares an `enum` column with 200 distinct `enum_values`
- **THEN** the column is materialized as `Enum16(…)` rather than `Enum8(…)`

#### Scenario: A value containing a quote is escaped into the DDL

- **WHEN** a schema declares an `enum` column whose `enum_values` includes `it's`
- **THEN** the `CREATE TABLE` embeds it as an escaped literal, the table materializes successfully
  (the physical type carries the value as data rather than the statement being altered), and the
  value is returned unchanged by `GET /v1/tables/{name}`

#### Scenario: Enum is not a valid array element type

- **WHEN** a schema declares an `array` column with `element_type` `enum`
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error`

#### Scenario: Adding an enum column to a live table

- **WHEN** `PATCH /v1/tables/{name}/schema` adds an `enum` column with
  `enum_values: ["a", "b"]` to an `ACTIVE` table
- **THEN** an `ALTER TABLE … ADD COLUMN … Enum8('a' = 1, 'b' = 2)` is issued and the column mapping
  records the declared values

#### Scenario: Whitespace around a declared value is trimmed on both paths

- **WHEN** a `POST .../schema` column or a `PATCH .../schema` `add` entry declares `enum_values`
  `[" high ", "low"]`
- **THEN** the column materializes as `Enum8('high' = 1, 'low' = 2)` and is returned with
  `enum_values` `["high", "low"]` — the DDL and the catalog carrying the same trimmed values, so a
  row written with `high` is accepted

#### Scenario: Changing an enum domain through update is rejected

- **WHEN** a `PATCH .../schema` `update` entry for an existing column carries `enum_values`
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error`, and the column's
  stored domain is unchanged

#### Scenario: Renaming an enum column preserves its domain

- **WHEN** a `PATCH .../schema` renames an `enum` column
- **THEN** the renamed column keeps type `enum` and the same `enum_values`, in the same order

#### Scenario: Enum column orders by declared position

- **WHEN** a table declares an `enum` column with `enum_values: ["low", "medium", "high"]` in its
  `ordering_key`, and rows of each value are written
- **THEN** rows sort in declared order (`low`, `medium`, `high`), not alphabetical order
  (`high`, `low`, `medium`)

#### Scenario: Enum column cannot carry the TTL or row version

- **WHEN** a schema names an `enum` column as its TTL column or as a source's `version_column`
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error`

#### Scenario: Rows carry enum values as plain strings

- **WHEN** `POST /v1/tables/{name}/rows` writes a row whose `status` value is `"running"` into a
  column declaring that value
- **THEN** the row is stored and a query projecting `status` returns the string `"running"`

#### Scenario: A value outside the declared domain is rejected

- **WHEN** `POST /v1/tables/{name}/rows` writes a row whose `status` value is not one of the
  column's declared `enum_values`
- **THEN** the write fails and is reported through the endpoint's existing row-write failure
  contract, with no partial row stored

### Requirement: Provenance columns are injected into an enrichment's schema definition

The system SHALL append the five provenance columns to the schema defined by
`POST /v1/tables/{name}/schema` when the table is an **enrichment**, so that a target's lineage
columns are one canonical declaration rather than each author's own. The five SHALL be
`pipeline_generation`, `model`, `enriched_at`, `group_version` and `truncated`,
injected with canonical types, descriptions, `display_name` and the `system` tag, in a **fixed
declared order** taken from an ordered list — never from an unordered set, whose iteration order is
unspecified and can differ between runs of the same process.

**`pipeline_generation` is injected** rather than left to the author because the
write path drops a provenance column the target does not physically carry. A target created without
it would silently record no lineage at all for any row written to it, which is
the opposite of what adding the stamp was for. Its canonical type SHALL be `long`, matching what a
pipeline `generation` is — a 64-bit counter — and matching `group_version`, the other integral
provenance column the write path stamps from a long. It SHALL be first in the declared order.

`evaluator_version` SHALL NOT be injected. Nothing writes it and a write
naming it is refused (see [enrichment-runtime](../enrichment-runtime/spec.md)), so on a new target it
could only ever be empty. It stays a permitted provenance name, so existing targets keep their
already-stamped column, and a caller who declares it on a new target gets it exactly as declared, under
the yield rule below.

Injection SHALL **yield to a caller who declared one**: a provenance name the request declares is
left **exactly** as declared — never duplicated, never overwritten, never reordered — and injection
appends only the names the request did not declare, in canonical order. Stated as an invariant:
injection can only ever *add* columns to the schema the caller sent. Without this rule every existing
authoring script, and both live targets that declare the original five with their own wording, would become a
duplicate-column 422.

The canonical types are load-bearing rather than cosmetic: the write path switches on the
**declared** type, stamping the bare number into an integral column, and skipping a column of an
unsupported type with a debug log rather than failing the write. That is why `pipeline_generation`
must be integral: a `string` declaration would stamp `name:generation` instead
of the bare counter, and any non-integral, non-string type would be skipped silently.

All five SHALL be injected **unconditionally**, including `model`, `group_version` and `truncated`,
which a Class-1 target can never populate — the materialize path skips exactly those three. Injecting
selectively would require the table to know its pipeline's kind and trigger, which the catalog
deliberately does not; three always-empty columns on a Class-1 target is the cheaper of the two costs.
The canonical descriptions SHALL say so outright, rather than leaving an analyst to wonder why the
column is empty. The canonical `truncated` description SHALL also state that it is **not** a quality
signal, since a member-selection policy of one row sets it on every multi-hop group by design.

Injection SHALL reach **new tables only**. `POST /v1/tables/{name}/schema` is refused with HTTP 409
on an already-materialized table, so an existing enrichment is not retro-fitted by this requirement,
and `PATCH /v1/tables/{name}/schema` SHALL NOT inject: it is an explicit operation list, and silently
widening it into a five-column DDL on a populated table would be the opposite of a caller declaring
what they want. Both statements stand unchanged. The consequence for `pipeline_generation` — that
**every enrichment that exists today lacks it**, and a best-effort write drops what the target does
not carry — SHALL be closed by the reconciler below rather than by an operator patching each target.

The endpoint's own operation description SHALL name all five columns and SHALL type `enriched_at`
consistently with what is injected.

#### Scenario: An enrichment declaring no provenance receives all five

- **WHEN** `POST /v1/tables/{name}/schema` defines the schema of a `PENDING` enrichment whose
  `columns` declare none of the provenance names
- **THEN** the materialized table carries all five injected provenance columns, appended after the
  caller's own columns in canonical order, each with its canonical type, description, `display_name`
  and the `system` tag, and carries no `evaluator_version` column

#### Scenario: An enrichment declaring every provenance name keeps its own declarations

- **WHEN** the request declares all six provenance names itself, `evaluator_version` included, with
  its own descriptions, tag and positions
- **THEN** the schema is accepted with no duplicate columns, and each declared column keeps its own
  description, tag and position exactly as sent

#### Scenario: An enrichment declaring some provenance receives only the rest

- **WHEN** the request declares `enriched_at` and `evaluator_version` and none of the other four
- **THEN** those two are left verbatim and `pipeline_generation`, `model`, `group_version` and
  `truncated` are appended in canonical order

#### Scenario: The injected pipeline_generation is long-typed

- **WHEN** provenance is injected into an enrichment's schema
- **THEN** `pipeline_generation` is declared `long`, so a write stamps the bare generation counter
  rather than a `name:generation` string, and it is first in the injected order

#### Scenario: An existing enrichment gains only the provenance column

- **WHEN** an enrichment materialized before this change, and targeted by an `ENRICH` pipeline, is read
  after a startup that ran the reconciler
- **THEN** it carries `pipeline_generation`, every other column is exactly as it was, and no
  `PATCH /v1/tables/{name}/schema` was issued to get there

#### Scenario: A source table receives no provenance

- **WHEN** `POST /v1/tables/{name}/schema` defines the schema of a `source` table
- **THEN** no provenance column is injected

#### Scenario: A schema patch injects nothing

- **WHEN** `PATCH /v1/tables/{name}/schema` adds a column to a materialized enrichment that carries no
  provenance columns
- **THEN** only the requested operation is applied and no provenance column is added

#### Scenario: The injected order is stable across runs

- **WHEN** two enrichments are given schemas in separate runs of the service, each declaring no
  provenance
- **THEN** both carry the five injected provenance columns in the same order

### Requirement: Seeded payload enrichment on dial_usage_log

The catalog migrations SHALL seed a third built-in table, `dial_usage_log_payload`, as an
`enrichment` of `dial_usage_log` with `status=ACTIVE`, `system=true` and `grain_key=event_id`, and
SHALL therefore report `cardinality` as `ZERO_OR_ONE` (a derived facet of having a grain key, not a
stored one). It SHALL expose the three body columns — `request_body`, `response_body`,
`assembled_response` — which SHALL resolve into `dial_usage_log`'s flat schema under the qualified
names `"dial_usage_log_payload.request_body"`, `"dial_usage_log_payload.response_body"` and
`"dial_usage_log_payload.assembled_response"`, by the ordinary grain join (see "Grain-based enrichment
join with enforced cardinality").

It SHALL also expose the three request-delta columns `request_envelope`, `request_delta` and
`request_cache_markers` (see the data-sync capability, "A chat request body is stored as an envelope, a
delta and a parent link"), under the qualified names `"dial_usage_log_payload.request_envelope"`,
`"dial_usage_log_payload.request_delta"` and `"dial_usage_log_payload.request_cache_markers"`. They SHALL
be added by a later catalog migration as new `column_mapping` rows of type `string`, `nullable`,
`sensitive` and `heavy`, with descriptions, placed after the existing columns. For a row whose request
body is stored whole they read as `NULL`; for a row in delta form `request_body` reads as `NULL`.

**BREAKING**: those five columns SHALL NOT be exposed as columns of `dial_usage_log`. A query naming
any of them as a bare field of `dial_usage_log` SHALL be rejected as an unknown field, exactly as any
other undeclared name is.

The seed SHALL move the five existing `column_mapping` rows rather than re-declare them, so every
per-column attribute — `sensitive`, `heavy`, `nullable`, `description`, `display_name`, `tag` — is
carried over unchanged and continues to be reported by `GET /v1/tables/dial_usage_log_payload` and by
schema discovery under the qualified name. Vacating those ordinals MAY leave gaps in
`dial_usage_log`'s column order; the surviving columns SHALL keep the relative order they had.

The definition's physical columns beyond the exposed ones — its `event_id` grain key,
`request_time`, `trace_id`, `core_span_id`, its `_updated_at` version column, and the chain columns
`request_scope`, `request_path`, `request_parent`, `request_depth` and `request_ancestors` — SHALL NOT appear in
the exposed schema, matching the existing rule that an enrichment's grain key is a hidden physical
column.

The definition's TTL fields (`ttl_column`, `ttl_interval`, `ttl_unit`) SHALL be unset, because a
seeded system table's retention is owned by its ClickHouse migration and is not mirrored into a catalog
the API could try to modify (see "Table TTL").

#### Scenario: The payload enrichment is present after migration

- **WHEN** the catalog is read after the table-catalog migrations run
- **THEN** it contains `dial_usage_log_payload` as an `enrichment` with `source_table=dial_usage_log`,
  `status=ACTIVE`, `system=true`, `grain_key=event_id`, and its exposed columns, including
  `request_envelope`, `request_delta` and `request_cache_markers`

#### Scenario: Body columns resolve under their qualified names

- **WHEN** `GET /v1/queries/entities/schema/dial_usage_log` is called by a caller entitled to the
  payload table's sensitive columns
- **THEN** the schema carries `"dial_usage_log_payload.request_body"`,
  `"dial_usage_log_payload.response_body"` and `"dial_usage_log_payload.assembled_response"`, and
  carries none of those names unqualified

#### Scenario: The old bare column name no longer resolves

- **WHEN** a query names `request_body` as a field of `dial_usage_log`
- **THEN** it is rejected as an unknown field, with no ClickHouse query issued

#### Scenario: The claim and header columns move with the bodies

- **WHEN** a query names `jwt_claims` or `request_tags` as a bare field of `dial_usage_log`
- **THEN** it is rejected as an unknown field, and the value is reachable only as
  `"dial_usage_log_payload.jwt_claims"` / `"dial_usage_log_payload.request_tags"`

#### Scenario: Carried-over column attributes are unchanged

- **WHEN** a caller holding `TABLE_GET` reads `GET /v1/tables/dial_usage_log_payload`
- **THEN** all three columns report `sensitive: true` and `heavy: true`, and each keeps the
  description and display name it carried on `dial_usage_log`

#### Scenario: The payload enrichment is not a queryable entity of its own

- **WHEN** a query targets `dial_usage_log_payload` as its entity
- **THEN** the request is rejected as an unknown entity, because only an `ACTIVE` `source` is
  queryable — the body columns are reachable only through their parent's grain join

#### Scenario: The payload enrichment rejects modification

- **WHEN** any of `PUT /v1/tables/dial_usage_log_payload`,
  `POST|PATCH /v1/tables/dial_usage_log_payload/schema`, `DELETE /v1/tables/dial_usage_log_payload`, or
  `POST /v1/tables/dial_usage_log_payload/rows` is called, by any caller including one holding every
  privilege
- **THEN** the request is rejected with HTTP 409 and the system-owned `error` code, and no ClickHouse
  DDL or write is issued

#### Scenario: The request-delta columns resolve under their qualified names

- **WHEN** `GET /v1/queries/entities/schema/dial_usage_log` is called by a caller entitled to the
  payload table's sensitive columns
- **THEN** the schema carries `"dial_usage_log_payload.request_envelope"`,
  `"dial_usage_log_payload.request_delta"` and `"dial_usage_log_payload.request_cache_markers"`, each
  reported `sensitive: true` and `heavy: true`, and carries none of the chain columns

#### Scenario: A caller without sensitive access does not see the request-delta columns

- **WHEN** a caller not entitled to sensitive columns reads the schema of `dial_usage_log`
- **THEN** none of the three request-delta columns is listed, exactly as for the body columns

### Requirement: An object column is always non-nullable

An `object` column SHALL resolve to `nullable = false` on every table kind, and a declaration setting
`nullable: true` on one SHALL be refused with a `422` naming the column. This matches the rules
already in force for `array` and `map`, and for the same reason: ClickHouse has no `Nullable(JSON)`,
so the DDL renderer cannot emit the nullability the catalog would otherwise claim.

The divergence this closes SHALL be recorded, because it was silent. An `object` column on an
`ENRICHMENT` previously inherited that kind's nullable-by-default, and an explicit `nullable: true`
was accepted — while the renderer emitted a plain `JSON` either way. The stored flag is what the
enrichment-read rule consults to decide whether an unmatched grain join yields `NULL`, so the guard
was inoperative for exactly the type whose physical form can never satisfy it.

#### Scenario: An enrichment's object column defaults to non-nullable

- **WHEN** an operator creates an `ENRICHMENT` declaring an `object` column with no `nullable` field
- **THEN** the column is stored with `nullable: false`, matching the `JSON` column the DDL renders

#### Scenario: An explicitly nullable object column is refused

- **WHEN** an operator declares an `object` column with `nullable: true`
- **THEN** the request is refused with `422`, naming the column and stating that ClickHouse has no
  `Nullable(JSON)` and that an absent document is the empty document

### Requirement: An enrichment's declared partitioning is readable but not settable

`GET /v1/tables/{name}` SHALL report `partition_by` for any table whose definition declares a partition
column, an enrichment included, and SHALL omit it for a table that declares none. Declaring one SHALL
remain a source-only operation for the **caller**: `POST /v1/tables/{name}/schema` and
`PATCH /v1/tables/{name}/schema` SHALL continue to reject a caller-supplied `partition_by` on an enrichment
with HTTP 422. The system itself SHALL set an enrichment's `partition_by` when it lays the enrichment out
aligned (see "An eligible row-grain enrichment is laid out aligned with its source"), and the DDL renderer
SHALL emit `PARTITION BY` for a source and for an aligned enrichment, and for no other enrichment.

The read matters because an enrichment's declared partition column changes how every query over its parent
entity is planned: it is what lets a keyed grain join bound the enrichment to the partitions that can hold
the row it is looking for. A value with that effect has to be visible on the definition that carries it, or
an operator asking why a body read is expensive has no way to see whether the alignment is declared. This
retires the state the split-payload migration created deliberately — a value it declined to set because it
"would be stored and never read".

The caller's write stays refused because the declaration asserts something about how a table is
**populated**: that for a given grain key both tables hold the same value in that column. The system can
guarantee that for a table whose rows it stamps from the source row itself, and a caller cannot. Nothing
at query time can catch a false claim — an over-restricted outer join yields the column's type default,
which is indistinguishable from a row that legitimately has no enrichment — so the claim is set only by
the system, for tables whose population it controls.

#### Scenario: A system enrichment reports its declared partitioning

- **GIVEN** an enrichment whose catalog definition declares a partition column
- **WHEN** it is fetched by name
- **THEN** the response carries `partition_by` with that column and its granularity

#### Scenario: An aligned API-created enrichment reports the partitioning ADAS set

- **GIVEN** an API-created enrichment of `dial_usage_log` laid out aligned at define-schema
- **WHEN** it is fetched by name
- **THEN** `partition_by` carries `request_time` with the source's granularity

#### Scenario: An enrichment without a declared partition column omits the member

- **GIVEN** an API-created enrichment that is not eligible for the aligned layout
- **WHEN** it is fetched by name
- **THEN** `partition_by` is absent

#### Scenario: Declaring partitioning on an enrichment is still refused

- **GIVEN** an enrichment whose schema is not yet defined
- **WHEN** its schema is posted with `partition_by`
- **THEN** the response is HTTP 422

### Requirement: The provenance stamp column is reconciled onto existing enrich targets at startup

The system SHALL reconcile the `pipeline_generation` provenance column onto every existing enrichment
that an `enrich` pipeline targets, at startup, without operator action. Creation-time injection reaches
new tables only and `PATCH /v1/tables/{name}/schema` deliberately does not inject, so without this
requirement every enrichment materialized before the column existed would silently drop the stamp on
every write, and lineage would stop at the deploy instead of improving. This is the first time the
system adds a column to an already-materialized enrichment, which is why it is a requirement of its own
rather than a widening of injection.

Reconciliation SHALL be scoped to enrichments that are `ACTIVE` **and** named as the `target` of a
`kind = 'ENRICH'` pipeline. A non-target enrichment can never carry the stamp, so the column would be
dead weight; and the narrower scope keeps the reconciler away from a system-owned enrichment, which the
schema API itself refuses to modify.

The physical `ALTER` SHALL be issued for **every** target in that scope on every startup, and the
catalog check SHALL decide only whether the catalog row is appended. Gating the `ALTER` on the catalog
instead would leave one divergence permanently unhealable: a target whose catalog row exists but whose
physical column does not is skipped forever, and that state is not inert, because the physical column
set is derived from the catalog rather than from the store — so the write path believes the column is
there, names it in every write, and every write-back and materialize to that target fails with an
unknown-column error. The `ALTER` is a metadata-only no-op when the column is present, so issuing it
unconditionally costs one statement per target per boot and closes the direction the catalog check
cannot see.

Reconciliation SHALL be **idempotent** in both directions, and SHALL apply the physical `ALTER` before
the catalog row, using `ADD COLUMN IF NOT EXISTS`. The order matters and the conditional is what makes it safe: a
failure after the `ALTER` and before the catalog write leaves the two diverged, and only a replayable
`ALTER` lets the next startup finish the job. This is the one respect in which the reconciler SHALL
differ from `PATCH /v1/tables/{name}/schema`, whose unconditional `ADD COLUMN` cannot be retried after
such a failure — the catalog check passes, the `ALTER` is re-issued, and the store refuses it.

Reconciliation SHALL NOT bump any pipeline's `generation` or advance any `updated_at`. A provenance
column binds to no output, so neither the composed response schema nor the derived output mapping
changes, and there is nothing for a consumer to re-read. This is a deliberate difference from an added
**user** column, which does bump an `llm` pipeline.

Reconciliation SHALL NOT fail startup. A failure SHALL be logged and reported through a metric, and
SHALL leave the service serving: the degraded mode is already defined — the stamp is dropped
best-effort for targets that do not carry the column — and the next startup retries. Failing instead
would be worse than the condition it reports, because liveness reads the aggregate health endpoint, so
a boot-failing reconciler would restart-loop the service over a lineage column.

Reconciliation SHALL be controlled by one configuration property, defaulting to **enabled**. A
disabled reconciler is a silently degrading stamp, which is the failure mode this requirement exists to
remove, so the default SHALL NOT be `false`.

#### Scenario: A target missing the column gains it on startup

- **WHEN** the service starts with an `ACTIVE` enrichment targeted by an `enrich` pipeline whose
  catalog records no `pipeline_generation`
- **THEN** the column is added to the physical table and to the catalog, typed `long` and tagged
  `system`, and the next enrich write stamps it

#### Scenario: A second startup changes nothing

- **WHEN** the service starts again against the same targets
- **THEN** no DDL is issued, no catalog row is written, and no pipeline's `generation` changes

#### Scenario: Reconciliation bumps no generation

- **WHEN** the reconciler adds the column to an `llm` pipeline's target
- **THEN** that pipeline's `generation` and `updated_at` are unchanged, and a Class-1 pipeline's
  `materialized_through_version` is preserved

#### Scenario: An enrichment no pipeline targets is left alone

- **WHEN** the service starts with an `ACTIVE` enrichment that is not the target of any pipeline, and a
  system-owned enrichment
- **THEN** neither is altered

#### Scenario: A physical column without a catalog row is completed rather than refused

- **WHEN** the service starts against a target whose physical table already carries
  `pipeline_generation` but whose catalog has no row for it
- **THEN** the conditional `ALTER` succeeds without error, the catalog row is written, and the two
  agree afterwards

#### Scenario: A failure degrades rather than blocking startup

- **WHEN** the reconciler cannot reach the data store, or its `ALTER` is refused
- **THEN** the service completes startup and serves traffic, the failure is logged and counted, writes
  to that target continue with the stamp dropped, and the next startup retries

### Requirement: Catalog reads require a table privilege

The catalog read surface SHALL be authorized per table rather than by application role.
`GET /v1/tables` SHALL require `TABLE_LIST` and SHALL return only the definitions the caller may
see; `GET /v1/tables/{name}` and `GET /v1/tables/{name}/revision/{revision}` SHALL require
`TABLE_GET` on the named table and SHALL respond `404 not_found` when the caller lacks it, so the
listing does not disclose the existence of tables the caller may not see.

A table the caller holds no read-side privilege on SHALL be absent from the listing rather than
present-but-forbidden, and an empty listing SHALL be `200` with no entries rather than `403`.

The read surface SHALL be **filtered at column granularity** by the clearance, as specified in "Read
and list table definitions": `TABLE_GET` alone exposes the non-sensitive column inventory, and
`TABLE_GET` with `TABLE_SENSITIVE` exposes every column with its `sensitive` flag. A revision read
(`GET /v1/tables/{name}/revision/{revision}`) SHALL apply the same filter. It uses the flag each column
carried **at that revision** and the caller's clearance **now**, so a column that was sensitive then
stays hidden from an uncleared caller even if it has since been declassified.

#### Scenario: The listing is caller-filtered

- **GIVEN** two tables, one of which grants `read` to the caller's provider role
- **WHEN** a caller holding no global read privilege lists the catalog
- **THEN** the response is `200` and contains only the granted table

#### Scenario: An unreadable definition is not found

- **WHEN** that caller requests the other table by name
- **THEN** the response is `404 not_found` and does not reveal that the table exists

#### Scenario: A revision read honours the same privilege

- **WHEN** that caller requests a revision of the other table
- **THEN** the response is `404 not_found`

#### Scenario: A caller with no grants sees an empty listing

- **WHEN** a caller holding no global read privilege and named in no access list lists the catalog
- **THEN** the response is `200` with no entries

#### Scenario: A gettable table still shows sensitive column names

- **GIVEN** a table whose `get` and `sensitive` lists contain the caller's provider role and which has
  a sensitive column
- **WHEN** the caller reads that table's definition, and a revision of it at which the column was
  already sensitive
- **THEN** both responses carry the column with `sensitive: true`
- **AND WHEN** the caller's role is removed from the table's `sensitive` list
- **THEN** the sensitive column is absent from both responses

### Requirement: Service-internal table names are reserved

Table names beginning with `_dial_internal_` SHALL be reserved for tables the service itself owns in the
ClickHouse database — tables provisioned by its migrations, never registered in the catalog and never
reachable through any query surface (the ingest ledger `_dial_internal_ingested_blob` is the first). `POST /v1/tables`
SHALL reject such a name with `422`, before any catalog row is written or any ClickHouse statement is
issued, with a message stating that the prefix is reserved for service-internal tables. The rejection SHALL
hold independently of the general identifier grammar, which already refuses a leading `_`, so that relaxing
that grammar later cannot open the prefix; every other path that creates catalog tables through the same
creation step inherits it.

The reason is not only naming: a catalog create whose `CREATE TABLE` fails undoes its partial table with a
best-effort `DROP TABLE IF EXISTS`, so a create aimed at the name of an existing table the catalog does not
know would drop that table. Rejecting the prefix before any DDL guarantees that no table API can reach a
service-internal table, by create or by its compensating drop.

#### Scenario: Creating a table under the internal prefix is rejected

- **WHEN** `POST /v1/tables` is called with the name `_dial_internal_ingested_blob` or any other name beginning with
  `_dial_internal_`
- **THEN** the request is rejected with `422`, the message says the prefix is reserved for service-internal
  tables, no catalog row is written and no ClickHouse statement is issued, and the existing internal table
  is untouched

#### Scenario: Internal tables are not addressable through the tables API

- **WHEN** a caller reads, patches, defines the schema of, or deletes `_dial_internal_ingested_blob` through `/v1/tables`
- **THEN** the service responds as for a table that does not exist, and issues no ClickHouse statement
  against it

### Requirement: Table definitions are promoted between environments through catalog export and import

A user table's identity and schema SHALL be exportable and importable as defined by
[catalog-export-import](../catalog-export-import/spec.md), and an import SHALL create a table only through the same create and
define-schema steps as `POST /v1/tables` and `PUT /v1/tables/{name}/schema`. Every rule in this capability therefore applies to an
imported table: identifier and reserved-name checks, `max-tables` and `max-columns-per-table`, the write-mode rules and provenance
injection. That capability owns which tables travel (system, OTLP landing, PENDING and FAILED tables do not), how columns travel ("Columns
travel as stored") and the known provenance asymmetry. An import never patches or deletes an existing table; it only creates tables and
deletes the ones it created during compensation.

#### Scenario: An imported table obeys the create rules

- **WHEN** a bundle table would bring the target over `max-tables`, or over `max-columns-per-table` once provenance is injected
- **THEN** the import is refused with `422` `catalog_import_invalid` and nothing is written, as a direct create would be refused

### Requirement: A column a row policy references is protected against drop and rename

A column a row policy references SHALL NOT be dropped or renamed.

A `PATCH /v1/tables/{name}/schema` that drops or renames a column referenced by the filter of any row
policy stored on that table (see [table-access-control](../table-access-control/spec.md)) SHALL be
refused, before any DDL is rendered, with HTTP 409 and error code `column_in_row_policy`, naming the
column and the roles whose policies reference it. The refusal SHALL hold whoever the caller is, and in
`none` mode too, because a policy over a missing column would fail every read of the table once it is
enforced. Removing or rewriting those policies first SHALL make the operation possible.

The same refusal SHALL apply to `POST /v1/tables/{name}/schema` on a table that is not yet materialized
(`PENDING` or `FAILED`), which replaces the whole column set: a table whose CREATE failed keeps its
columns and can already carry policies, so a re-defined schema that no longer exposes a column a policy
references SHALL be refused `409 column_in_row_policy`, naming the columns and roles, before any column
is replaced or any DDL is issued.

A column referenced by no policy SHALL remain droppable and renamable under the existing rules.

#### Scenario: Dropping a policy column is refused

- **GIVEN** a policy for role `X` filtering `dial_usage_log` on `project_id`
- **WHEN** a caller holding `TABLE_MODIFY` patches the schema to drop or rename `project_id`
- **THEN** the request is refused `409 column_in_row_policy` naming `project_id` and `X`, and no
  `ALTER` is issued

#### Scenario: Removing the policy releases the column

- **WHEN** the policy for `X` is deleted and the same patch is repeated against a column no other
  policy references
- **THEN** the patch is applied under the existing schema rules

#### Scenario: Re-defining a failed table's schema without a policy column is refused

- **GIVEN** a `FAILED` table with column `c` and a policy for role `X` filtering on `c`
- **WHEN** a caller posts the table's schema again without `c`
- **THEN** the request is refused `409 column_in_row_policy` naming `c` and `X`, the stored columns are
  unchanged, and no `CREATE` is issued

### Requirement: An eligible row-grain enrichment is laid out aligned with its source

At `POST /v1/tables/{name}/schema`, the system SHALL lay out an enrichment **aligned** with its source when
all of these hold: its `grain_key` is the source's `identity_column`; the source declares a partition
column other than that identity column; and the source is partition-local. No request member SHALL request or
suppress the layout. An API-written source qualifies like a system one: an `append` source is partition-local by
construction, and an `upsert_by_key` source is when its partition column is in its `ordering_key`.

An aligned enrichment SHALL be created with `PARTITION BY` the source's partition expression, an
ADAS-owned column of the same name and type as the source's partition column (the **alignment column**,
`request_time` for `dial_usage_log`), and `ORDER BY` its grain key. The alignment column SHALL be a
**hidden** physical column, like the grain key and like `dial_usage_log_payload`'s `request_time`: it SHALL
NOT be a catalog column of the enrichment, SHALL NOT appear in its columns, in schema discovery or in an
export bundle, and SHALL NOT be droppable or renameable through `PATCH /v1/tables/{name}/schema`. The
definition's `partition_by` SHALL name it, and import SHALL re-add it at define-schema.

A declared column with the alignment column's name SHALL be **adopted** as the alignment column when it has
the source partition column's type, is non-nullable, and is neither `sensitive` nor `heavy`: no hidden column is added, the declared column
stays a visible catalog column, and its description, display name, tag and ordinal are kept. Its values are written by the system from
the source row from then on (see the class1-enrichment capability), whatever any writer binds to it. A
declared column with that name and a different type, or nullable, SHALL be refused with HTTP 422
`validation_error`, stating that it collides with the alignment column; so SHALL a declared column of that name
that is `sensitive` or `heavy`, and a `PATCH /v1/tables/{name}/schema` `update` marking the adopted column
either. Both flags would misdescribe a column the system writes from the source row: its visibility is decided
by the source column's own, and it is a sort-key-cheap scalar, not a body. An enrichment that is not
eligible — a group-grain enrichment, one whose grain key is not the source identity, one whose source's identity
column is its partition column (the grain key already carries the partition value), or one over an unpartitioned
source, or one that is not partition-local — SHALL be laid out exactly as before, and a column of that name on it
is an ordinary user column.

While an eligible enrichment is not yet laid out aligned — its relayout has not run, or is running (see "Existing
eligible enrichments are relaid out automatically") — `PATCH /v1/tables/{name}/schema` SHALL refuse with HTTP 422
`validation_error` an `add` of a column with the alignment column's name, or a `rename` onto it, unless that
column could be adopted. A column the relayout cannot adopt would leave the table `layout: unsupported` until that column is changed.

Adoption is what lets an existing table that already carries a copy of its source's partition column —
say its own `request_time`, which drives its TTL — be relaid out and round-trip through
catalog export and import, where import replays define-schema with the column as stored.

#### Scenario: An eligible enrichment is created aligned

- **GIVEN** an enrichment of `dial_usage_log` with `grain_key` `event_id`
- **WHEN** its schema is defined
- **THEN** the ClickHouse table is `PARTITION BY` `dial_usage_log`'s partition expression, carries a
  hidden `request_time` column of the source's type, and is `ORDER BY event_id`
- **AND** `GET /v1/tables/{name}` lists no `request_time` column, and schema discovery exposes no
  `<table>.request_time` field

#### Scenario: A group-grain enrichment is not aligned

- **GIVEN** an enrichment of `dial_usage_log` whose `grain_key` is `client_session_id`
- **WHEN** its schema is defined
- **THEN** it is laid out without `PARTITION BY` and without an alignment column, as before

#### Scenario: A matching declared column is adopted

- **GIVEN** an eligible enrichment of `dial_usage_log`
- **WHEN** its schema declares a non-nullable `timestamp` column `request_time` with its own description
- **THEN** the table is created aligned with exactly one `request_time` column, `partition_by` names it,
  and `GET /v1/tables/{name}` returns the declared description

#### Scenario: An eligible enrichment of a partitioned append API source is created aligned

- **GIVEN** an `append` source declared through the API with `partition_by` `day` and `identity_column` `id`
- **WHEN** an enrichment of it with `grain_key` `id` has its schema defined
- **THEN** the table is `PARTITION BY` `toYYYYMMDD(day)` with a hidden `day` column, and `layout` reads `aligned`

#### Scenario: An upsert source partitioned outside its key yields an unaligned enrichment

- **GIVEN** an `upsert_by_key` source with `ordering_key` `[id]`, `partition_by` `day` and `identity_column` `id`
- **WHEN** an enrichment of it with `grain_key` `id` has its schema defined
- **THEN** it is laid out without `PARTITION BY` and without an alignment column

#### Scenario: A source whose identity is its partition column yields an unaligned enrichment

- **GIVEN** a source whose `identity_column` is its partition column
- **WHEN** an enrichment keyed on it has its schema defined
- **THEN** it is created without `PARTITION BY` and with a single column of that name, its grain key

#### Scenario: A sensitive or heavy declared column is not adopted

- **GIVEN** an eligible enrichment of `dial_usage_log`
- **WHEN** its schema declares a non-nullable `timestamp` column `request_time` that is `sensitive`, or `heavy`
- **THEN** the response is HTTP 422 `validation_error` naming the collision, and no DDL is issued

#### Scenario: An unadoptable alignment name cannot be added before the relayout

- **GIVEN** an eligible enrichment of `dial_usage_log` that is not laid out aligned yet
- **WHEN** a `PATCH /v1/tables/{name}/schema` adds a nullable `request_time`, or renames a `string` column to it
- **THEN** the response is HTTP 422 `validation_error` and no `ALTER` is issued

#### Scenario: A mismatched declared column is refused

- **GIVEN** an eligible enrichment of `dial_usage_log`
- **WHEN** its schema declares a column `request_time` of type `string`, or a nullable `timestamp` one
- **THEN** the response is HTTP 422 `validation_error` naming the collision, and no DDL is issued

#### Scenario: An aligned enrichment imports aligned onto a fresh environment

- **GIVEN** `usage_client_identity` and `usage_request_baggage`, aligned, exported with their pipelines
- **WHEN** the bundle is imported into an environment holding only the system tables
- **THEN** the import succeeds, both tables are created aligned with one physical `request_time` column
  each — hidden on `usage_client_identity`, the adopted visible one on `usage_request_baggage` — their
  column lists and descriptions match the exporting environment, and `layout` reads `aligned`

### Requirement: The rows API requires the alignment column for an aligned enrichment

`POST /v1/tables/{name}/rows` on an aligned enrichment SHALL require every row to carry the alignment
column; a row without it SHALL be refused with HTTP 422 `validation_error` naming the column, and no row of
the request SHALL be written. A row carrying a value that does not match its source row's SHALL be written
as given; under the tuple join it is unmatched and attaches to no source row.

#### Scenario: A row without the alignment column is refused

- **WHEN** a row lacking `request_time` is posted to an aligned enrichment of `dial_usage_log`
- **THEN** the response is HTTP 422 `validation_error` and nothing is written

#### Scenario: A wrong alignment value only orphans its own row

- **WHEN** a row is posted with an `event_id` that exists in the source and a `request_time` that differs
  from that source row's
- **THEN** a query projecting the enrichment over that source row reads the column's unmatched value, and
  no other source row reads the posted values

### Requirement: An aligned enrichment inherits its source's TTL

An aligned enrichment that declares no `ttl` SHALL be created with its source's physical TTL clause and its
`ttl_only_drop_parts` setting, both read from the source's ClickHouse table (for `dial_usage_log`, its live
retention — one year as provisioned unless `SYNC_USAGE_LOG_TTL_DAYS` changed it, see the data-sync
capability — with `ttl_only_drop_parts = 1`).
The clause applies unchanged because the alignment column has the source partition column's name and type.
It is read when the enrichment is created: a later change of the source's retention SHALL NOT by itself
re-align an existing enrichment's TTL. An explicitly declared `ttl` SHALL override the inherited one. An
inherited TTL SHALL NOT be mirrored into the catalog, so `GET /v1/tables/{name}` SHALL omit `ttl` for it,
as it does for the seeded tables' migration-managed TTLs. Every read of an enrichment starts from its
source, so a row whose source has expired is unreadable; inheriting the TTL removes only rows that can never
be read again.

Only the single-rule shape the server renders (`TTL <column> + toInterval<Unit>(<n>)`, optionally wrapped in
`toDateTime`) over the alignment column is inherited; a source TTL with several rules, a `WHERE`, or over another
column is not, and the enrichment is then created without one.

Because an inherited TTL is not in the catalog, `PATCH /v1/tables/{name}/schema` with `"ttl": null` SHALL NOT
remove it: with no catalog TTL the removal is the same no-op it is for any table without one, and no
`REMOVE TTL` is issued. Declaring a `ttl` replaces the inherited clause (`MODIFY TTL`); the inherited
`ttl_only_drop_parts` setting stays in either case.

#### Scenario: An aligned enrichment carries its source's TTL

- **GIVEN** an eligible enrichment of `dial_usage_log` defined without `ttl`, while `dial_usage_log` keeps
  its default one-year retention
- **WHEN** its ClickHouse table is inspected
- **THEN** it carries `TTL toDateTime(request_time) + INTERVAL 1 YEAR` and `ttl_only_drop_parts = 1`, and
  `GET /v1/tables/{name}` omits `ttl`

#### Scenario: A declared TTL overrides the inherited one

- **WHEN** an eligible enrichment is defined with `ttl` `{column: request_time, interval: 90, unit: day}`
- **THEN** the table's TTL is 90 days

#### Scenario: An existing enrichment keeps the TTL it inherited

- **GIVEN** an aligned enrichment of `dial_usage_log` created while the source carried its one-year `TTL`
- **WHEN** the service restarts with `SYNC_USAGE_LOG_TTL_DAYS=180`
- **THEN** `dial_usage_log`'s `TTL` becomes 180 days while the enrichment still carries one year, and an
  aligned enrichment created afterwards inherits the 180 days

### Requirement: A grouped-by column of an aligned enrichment carries a lookup index

The system SHALL index an aligned enrichment column that a group trigger's `group_by` or a form-mode aggregate's `group_by` names.
When a pipeline naming it is registered or patched, the column SHALL carry
`INDEX idx_<column> <column> TYPE bloom_filter(0.001) GRANULARITY 1`. The index SHALL be added with
`ALTER TABLE … ADD INDEX` only and SHALL NOT be materialized over existing parts: no `MATERIALIZE INDEX`
mutation SHALL be issued. Deleting the pipeline SHALL leave the index in place. A column of a non-aligned
enrichment SHALL receive no index.

Parts without the index SHALL still be read correctly; only the partition pre-selection over them is slower.

A column SHALL count as indexed when any `bloom_filter` skip index is over exactly that column, whatever its name:
a `RENAME COLUMN` keeps an index's name and moves its expression to the new name. When `idx_<column>` is taken by an
index over another column, the new index SHALL take the first free `idx_<column>_<n>`. An SQL-mode aggregate's
grouping lives in its statement, which is not parsed for this, so its grouped-by columns get the partition
pre-selection but no index.

Because the index outlives its pipeline, a `drop` of an indexed column through `PATCH /v1/tables/{name}/schema`
SHALL drop every skip index over exactly that column in the same `ALTER`, ahead of the `DROP COLUMN`;
ClickHouse otherwise refuses the drop.

#### Scenario: Grouping by an aligned column adds the index without a mutation

- **GIVEN** an aligned enrichment with a `client_session_id` column and no index on it
- **WHEN** a group pipeline with `group_by` `client_session_id` is registered over it
- **THEN** the table carries a `bloom_filter(0.001)` index on `client_session_id`, and
  `system.mutations` holds no `MATERIALIZE INDEX` for it

#### Scenario: An index already present is not re-added

- **WHEN** a second pipeline groups by the same column
- **THEN** no further DDL is issued

#### Scenario: A non-aligned enrichment column receives no index

- **GIVEN** an enrichment of `dial_usage_log` keyed on `chat_id`
- **WHEN** an aggregate grouping by one of its columns is registered
- **THEN** the table carries no skip index

#### Scenario: A renamed column stays covered by its index

- **GIVEN** an aligned enrichment whose `session` column carries `idx_session`, renamed to `sid` with a new
  `session` column added
- **WHEN** one pipeline groups by `sid` and another by `session`
- **THEN** `idx_session` still covers `sid`, no index is added for it, and `session` gets `idx_session_2`

#### Scenario: Dropping a column whose index outlived its pipeline succeeds

- **GIVEN** an aligned enrichment column carrying a lookup index whose pipelines have been deleted
- **WHEN** the column is dropped through `PATCH /v1/tables/{name}/schema`
- **THEN** the drop succeeds and the table carries neither the column nor the index

### Requirement: Existing eligible enrichments are relaid out automatically

After the application is ready, and after a fixed grace delay of ten minutes, the system SHALL migrate every
existing enrichment that is eligible for the aligned layout but not laid out aligned, and whose only writer
is a Class-1 materialize pipeline or no pipeline. An eligible enrichment with a row-grain LLM pipeline
writing it SHALL be left as it is, with a WARN log naming it: the runner may hold queued and dead-lettered
write rows for it built before the runner echoed the partition value (enrichment-runtime, "The scan carries an
aligned target's partition value and the write-back returns it"), and those would be refused once it is aligned. A table already declaring a column with the
alignment column's name SHALL be migrated with that column adopted when it matches (see "An eligible
row-grain enrichment is laid out aligned with its source"), its values re-derived from the source by the
copy; when it does not match, the table SHALL be left as it is with `layout: unsupported` and a WARN naming
the column. The migration SHALL need no call, property or operator step.

The migration SHALL run as one background job per table, off the probe path, under a Postgres advisory lock
so that exactly one instance runs it, with progress persisted in Postgres so that a restart resumes it from
its last completed step. It SHALL:

1. read the current database's engine and proceed only for `Atomic` or `Replicated`; for any other engine
   it SHALL record `layout: unsupported`, log a WARN and leave the table unchanged;
2. copy the table's columns, with `_updated_at` unchanged and the alignment column derived from the source,
   into a new aligned table — one slice of grain keys per statement (see below) — whose DDL already carries every lookup index
   the table's pipelines need, and no TTL yet. The copy is named with a reserved leading `_`, which no user
   table can have. A row whose source row no longer exists is not copied, and their count is logged;
3. copy the rows written during that copy;
4. check the table's writer again, and abandon the relayout — dropping the copy and recording
   `layout: unsupported` with a WARN — when a row-grain LLM pipeline now writes it; otherwise hold the table's
   writers (see "The writers of a relaying-out enrichment are held briefly");
5. copy the rows written since the previous copy, after the hold has waited out a materialize already
   running, and check the writer again before the swap as in step 4;
6. swap the two tables with `EXCHANGE TABLES`, then re-stamp and move any row of the new table's epoch
   partition from the source;
7. update the catalog and release the hold;
8. copy any row of the old table newer than the step-5 mark, repeat the epoch-partition repair, and verify
   that every row of the old table whose source row exists is in the aligned table — logging the rows with
   no source row at WARN, and keeping the old table, with an ERROR log, when any row with a source row is
   missing; otherwise copy what the old table received since this step began and drop it. Then apply the
   table's TTL, its own or the inherited one, recomputing each part's TTL information without rewriting its
   data (`materialize_ttl_recalculate_only`, reset once the recalculation is done).

Each catch-up copy SHALL include the rows stamped up to `pipeline.budget.timeout` plus the hold's margin below
its mark, skipping any version (grain key and `_updated_at`) the target already holds, and SHALL write any number
of partitions in one statement. `_updated_at` is stamped when a write starts, so a write that started before the
one that set the mark can commit after the mark was read with a stamp below it.

Every statement that reads the table's rows — the copies of steps 2, 3, 5 and 8, the epoch-partition repair and
the step-8 verification — SHALL read one **slice** at a time: a range of the table's grain keys, in the table's
sort order, holding about 256 MiB of the table's uncompressed data, each range's upper key read just before it
runs and the last range open above, so that together the ranges cover every key whatever is written meanwhile.
The source SHALL be joined restricted to the keys the slice holds, and every such statement SHALL run capped at
`pipeline.budget.max-memory`. The history copy SHALL persist the last key a completed slice copied through, and a
restart SHALL resume its copy after that key. A source month or day SHALL NOT bound a statement: its size follows
the traffic, and the join that stamps the alignment column holds the source rows of its whole range — a month of
`dial_usage_log` can need gigabytes, more than the whole server budget at higher volumes.

The TTL comes last because a row an earlier build writes with no alignment value lands in the epoch partition,
which a TTL in force has already expired: ClickHouse would drop its part before the repair could re-stamp it.

A failure before the swap SHALL leave the old table in service, release the hold before any further
ClickHouse statement, drop the copy and record the error; the next start SHALL migrate the table again from
the beginning. A failure after the swap SHALL keep the progress for the next start to resume. Each start SHALL
also judge a skipped table again, and migrate it once nothing refuses it. A failure of one table SHALL NOT
stop the migration of the others, and a table another instance holds the lock of SHALL be tried again after
the grace delay. A stop — shutdown or interruption — SHALL release the hold, drop nothing and keep the
progress for the next start to resume. A migration failure SHALL NOT fail application startup. Finding rows
in step 6 or step 8 SHALL log a WARN with their count; in step 6 the rows re-stamped from the source and the
rows with no source row are counted separately.

While a table's migration has started and not finished, `PATCH /v1/tables/{name}/schema` SHALL be refused with
HTTP 409 `conflict` before any DDL: the migration copies the columns and TTL it planned from at its start
into the table that replaces this one, so an ALTER would reach only the table being replaced.

`GET /v1/tables/{name}` SHALL report `layout` for an enrichment: `migrating` while the job has started and not
finished, `aligned` once laid out aligned (a system enrichment such as `dial_usage_log_payload` included),
`unsupported` when the job skipped it — an LLM writer, a same-named column it cannot adopt, or an engine that
cannot exchange tables — and omit it otherwise, a relayout that failed before the exchange included. Catalog export SHALL carry no layout; import SHALL derive it at
define-schema.

#### Scenario: An existing eligible enrichment is migrated without an operator step

- **GIVEN** an unaligned enrichment of `dial_usage_log` with `grain_key` `event_id`, written only by a
  Class-1 pipeline
- **WHEN** the application has been ready for the grace delay
- **THEN** the table is replaced by an aligned table holding the same rows with the same `_updated_at`,
  every row carrying its source row's `request_time`, and `layout` reads `aligned`

#### Scenario: A table with its own copy of the partition column is migrated with it adopted

- **GIVEN** `usage_request_baggage`, unaligned, declaring a non-nullable `timestamp` column `request_time`
  and a catalog `ttl` over it
- **WHEN** the migration runs
- **THEN** the aligned table has one `request_time` column carrying its source rows' values, keeps the
  declared column's description and its declared `ttl`, and `layout` reads `aligned`

#### Scenario: A migration interrupted mid-way resumes

- **WHEN** the instance stops after any step of the migration and starts again
- **THEN** the migration resumes from the step after the last completed one and finishes with every row of the
  original table whose source row exists

#### Scenario: A history copy interrupted between slices resumes after the last completed slice

- **GIVEN** a migration whose history copy completed the slices up to some grain key
- **WHEN** the instance stops and starts again
- **THEN** the copy continues with the keys above that key, and the aligned table ends with every row of the
  original table

#### Scenario: Statement size does not follow traffic

- **GIVEN** an enrichment whose busiest source day holds more rows than one slice
- **WHEN** it is migrated
- **THEN** no statement of the migration reads more than one slice of the table, and each runs under
  `pipeline.budget.max-memory`

#### Scenario: Only one instance migrates

- **WHEN** two instances become ready together
- **THEN** exactly one runs the migration of a given table

#### Scenario: An LLM-written enrichment is left alone

- **GIVEN** an eligible enrichment written by a row-grain LLM pipeline
- **WHEN** the migration runs
- **THEN** the table is unchanged, `layout` reads `unsupported`, and a WARN names it

#### Scenario: An unsupported database engine is skipped

- **WHEN** the current database's engine is neither `Atomic` nor `Replicated`
- **THEN** the table is unchanged and `layout` reads `unsupported`

#### Scenario: A failure before the exchange leaves the table in service

- **WHEN** the exchange cannot run
- **THEN** the old table is unchanged and in service, the hold is released and the error is recorded
- **AND** the next start migrates the table from the beginning

#### Scenario: A stop inside the hold keeps the progress

- **WHEN** the application shuts down while the migration holds a table's writers
- **THEN** the hold is released, the copy is kept and no error is recorded, and the next start resumes

#### Scenario: Rows whose source rows have expired are not a loss

- **GIVEN** an enrichment row whose source row no longer exists
- **WHEN** the migration completes
- **THEN** the row is not carried over, a WARN gives the count, and the old table is dropped

#### Scenario: A schema change during the migration is refused

- **WHEN** `PATCH /v1/tables/{name}/schema` reaches a table whose migration is running
- **THEN** it is refused with HTTP 409 `conflict` and no ALTER runs

#### Scenario: A write from an old instance during deploy skew survives

- **GIVEN** rows written into the old table after the catch-up copy, and rows with an epoch `request_time`
  written into the new table after the swap
- **WHEN** the migration completes
- **THEN** both sets of rows are present in the aligned table with their source's `request_time`, and each
  repair logged one WARN with its count

### Requirement: The writers of a relaying-out enrichment are held briefly

The system SHALL refuse every write to an enrichment with HTTP 503 `table_relayout_in_progress` while its
relayout is between its hold and its catalog update — `POST /v1/pipelines/{name}/materialize` for a pipeline
targeting it, `POST /v1/pipelines/{name}/rows`, and `POST /v1/tables/{name}/rows` — before any statement is
issued. The hold SHALL be read from Postgres on each write, so that it holds on every instance.
Reads SHALL NOT be held. Before its last copy the hold SHALL wait `pipeline.budget.timeout` plus a small
margin, so that a materialize already past its check has finished. The relayout SHALL refresh the hold while
it stands, and a hold not refreshed for ten minutes — its instance gone — SHALL no longer refuse writes.
A migration resumed at its swap or its catalog update SHALL take the hold again and wait it out the same way
before continuing: the stop or failure that ended the earlier attempt released the hold, and a writer that read
the catalog unaligned would otherwise write rows with no alignment value after the last epoch-partition repair.

#### Scenario: A materialize during the hold is refused and retried later

- **WHEN** a materialize targets an enrichment whose relayout is holding writers
- **THEN** the response is HTTP 503 `table_relayout_in_progress`, nothing is written, and a materialize
  after the hold is released succeeds from the same cursor

#### Scenario: A migration resumed at the swap holds the writers again

- **GIVEN** a migration stopped after its hold and before its catalog update, its hold released
- **WHEN** the instance starts again and resumes it
- **THEN** writes to the enrichment are refused with HTTP 503 `table_relayout_in_progress` for the hold's wait
  before the swap and the catalog update run

#### Scenario: A hold whose instance died lapses

- **WHEN** the instance holding an enrichment's writers dies and ten minutes pass
- **THEN** writes to the enrichment are accepted again

#### Scenario: Reads continue during the hold

- **WHEN** a query projects a held enrichment's columns
- **THEN** it succeeds
