## ADDED Requirements

### Requirement: Catalog reads require a table privilege

The catalog read surface SHALL be authorized per table rather than by application role.
`GET /v1/tables` SHALL require `TABLE_LIST` and SHALL return only the definitions the caller may
see; `GET /v1/tables/{name}` and `GET /v1/tables/{name}/revision/{revision}` SHALL require
`TABLE_GET` on the named table and SHALL respond `404 not_found` when the caller lacks it, so the
listing does not disclose the existence of tables the caller may not see.

A table the caller holds no read-side privilege on SHALL be absent from the listing rather than
present-but-forbidden, and an empty listing SHALL be `200` with no entries rather than `403`.

The read surface SHALL remain **unfiltered at column granularity**: it returns every column with
its `sensitive` flag, as already specified, so `TABLE_GET` exposes sensitive column names and flags
while their values stay governed by `TABLE_READ_SENSITIVE` on the query surface.

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

- **GIVEN** a table whose `get` list contains the caller's provider role and which has a sensitive
  column
- **WHEN** the caller reads that table's definition
- **THEN** the sensitive column is present with `sensitive: true`

## MODIFIED Requirements

### Requirement: Per-table mutations honor role-based access

The per-table mutating endpoints SHALL be authorized by the privilege each requires, held globally or
granted on that table as defined by the `table-access-control` capability: row writes
(`POST /v1/tables/{name}/rows`) require `TABLE_WRITE`, and the schema/metadata mutations — define +
materialize (`POST /v1/tables/{name}/schema`), live schema PATCH
(`PATCH /v1/tables/{name}/schema`), and definition update (`PUT /v1/tables/{name}`) — require
`TABLE_MODIFY`. Both schema endpoints SHALL require the same privilege; they SHALL NOT differ in
reachability as they do today.

Changing a column's `sensitive` flag through `PATCH /v1/tables/{name}/schema` SHALL require
`TABLE_GRANT` and SHALL NOT be authorized by `TABLE_MODIFY` in either direction, because a holder of
`TABLE_MODIFY` could otherwise declassify a column and then read it (see `authentication`). A patch
that changes only other metadata SHALL remain authorized by `TABLE_MODIFY`.

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

- **GIVEN** a non-system table whose `modify` access list contains the caller's provider role
- **WHEN** the caller sends `PATCH /v1/tables/{name}/schema` with
  `{"update":[{"name":"request_tags","sensitive":false}]}`
- **THEN** the response is `403 forbidden` and the column remains sensitive
- **AND WHEN** the same caller patches only that column's `description`
- **THEN** the request succeeds

#### Scenario: Delete is never authorized by an access role

- **WHEN** a caller holding a `modify` grant but not `TABLE_DELETE` deletes the table
- **THEN** the service responds `403 forbidden`

#### Scenario: System table rejects modification despite access roles

- **GIVEN** a `system` table with a `write` access role for the caller's provider role
- **WHEN** the caller inserts rows
- **THEN** the service rejects the modification (system-owned protection)

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
`oidc` mode this endpoint SHALL require `TABLE_MODIFY` on the table, held globally or granted on it —
except for a change to a column's `sensitive` flag, which requires `TABLE_GRANT` in either direction
(see `authentication`). An `add` that declares a new column `sensitive` is **not** such a change and
stays authorized by `TABLE_MODIFY`: the column does not exist yet, so it holds no value anyone could
have been barred from, and requiring the grant authority there would only push an author towards adding
the column unflagged. Marking a column sensitive — by patching one or adding one already flagged — on an
enrichment SHALL be subject to the laundering subset rule (see `enrichment-runtime`). The system SHALL reject: a type-change of an existing column (422); a drop or rename
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
- any other unparseable value — reported as not an ISO-8601 timestamp, with an example of the form

A `null` value for a nullable `timestamp` column SHALL remain accepted and SHALL be stored as null.

Values of every other declared type SHALL be passed through unchanged and SHALL NOT be inspected for
timestamp shape. In particular a `date`-typed value needs no normalization (`YYYY-MM-DD` is both its
ISO-8601 and its stored form), a `decimal`-typed value SHALL retain a caller-supplied string spelling
so its digits are not degraded through a JSON float, and a `string`-typed column SHALL accept a
timestamp-shaped value untouched.

The column-set validation SHALL be applied before timestamp validation, so a row naming an unknown or
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
readiness signal and its evaluator's output expressions, an `aggregate` one in its group keys, its
measures, its filter and each measure's own `where`. Deleting a table that is a pipeline's **target** SHALL be permitted and SHALL remove the
pipeline with it: the pipeline exists only to fill that table.
Deleting an unknown definition SHALL return 404. This endpoint SHALL
require `TABLE_DELETE` in `oidc` mode. That privilege is global-only and SHALL NOT be authorizable by
any per-table grant: delegated destruction of a table is deliberately out of scope.

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
caller holds. A column declared `sensitive` here needs no `TABLE_GRANT`, for the same reason an `add`
does not: the table carries no rows yet, so the declaration bars nobody from a value they could
previously read.

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
`permissions` object with boolean `get`, `read`, `read_sensitive`, `write` and `modify`
fields describing what the calling identity may do with that table, computed per the
caller-effective permissions rules of
[table-access-control](../table-access-control/spec.md). All five fields SHALL always be present
booleans (never null/absent) — the object grew from two flags to five with the read ladder, and the
two original names keep their spelling and meaning so a client reading only those is unaffected.
Mutation responses that return the table representation (create, description update, schema PATCH)
SHALL carry the same object.

#### Scenario: List items carry permissions

- **WHEN** `GET /v1/tables` is called
- **THEN** every list item carries a `permissions` object with all five boolean fields

#### Scenario: Single get carries permissions

- **WHEN** `GET /v1/tables/{name}` is called for an existing definition
- **THEN** the response carries a `permissions` object with all six boolean fields

#### Scenario: Create response reports the creator's permissions

- **WHEN** an authorized caller creates a table via `POST /v1/tables`
- **THEN** the 201 response reports the creator's own effective permissions on the new table — all
  six `true` for a caller holding every table-scoped privilege, and `false` for any the caller does
  not hold, since `TABLE_CREATE` alone confers none of them

### Requirement: Seeded column sensitivity on the built-in source tables

The catalog migrations SHALL seed the `sensitive` flag on the built-in tables so that columns
carrying caller identity or raw user content are restricted, on the query surface, to a caller holding
`TABLE_READ_SENSITIVE` on the table that owns them — globally or granted on that table — by
default (see the `authentication` capability's "Sensitive column authorization" for the enforcement
rule). The seeded sensitive set SHALL be `dial_usage_log_payload.jwt_claims`,
`dial_usage_log_payload.request_tags`, `dial_usage_log_payload.request_body`,
`dial_usage_log_payload.response_body`, `dial_usage_log_payload.assembled_response`, and
`rate_analytics.comment`. Every other seeded column SHALL default to `sensitive = false`.

All five SHALL carry the flag on `dial_usage_log_payload` because that is where they are stored;
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

Sensitivity is catalog metadata, not an immutable property: an operator holding `TABLE_GRANT` MAY
change a
column's flag via `PATCH /v1/tables/{name}/schema`, and the catalog row is thereafter authoritative.
Seeding is applied by a `column_mapping` update only — the flag is never part of the ClickHouse DDL, so
no seeding change materializes as an `ALTER`. The seeded tables are the exception in practice: all
three are `system`, so every modifying `/v1/tables` operation on them is refused and their seeded flags
are changed only by a further migration.

#### Scenario: Seeded sensitivity is present after migration

- **WHEN** the catalog is read after the table-catalog migrations run
- **THEN** `dial_usage_log` reports `jwt_claims` and `request_tags` with `sensitive: true` and
  `user_hash` with `sensitive: false`, `dial_usage_log_payload` reports all three of its columns with
  `sensitive: true`, and `rate_analytics` reports `comment` with `sensitive: true`

#### Scenario: Management responses expose the seeded flag unfiltered

- **WHEN** a caller holding `TABLE_GET` reads `GET /v1/tables/dial_usage_log`
- **THEN** the response lists `request_tags` with `sensitive: true` — the `/v1/tables` surface is not
  role-filtered, so an operator can always audit which columns are restricted

#### Scenario: A seeded sensitive column can be unrestricted by an operator

- **WHEN** a caller holding `TABLE_GRANT` sends `PATCH /v1/tables/{name}/schema` with
  `{"update":[{"name":"<column>","sensitive":false}]}` for a **non-`system`** table whose column was
  flagged sensitive
- **THEN** the request succeeds without any ClickHouse `ALTER`, and the column is thereafter visible to
  unentitled callers on the query surface — while the same request against a seeded `system`
  table is refused with HTTP 409

#### Scenario: A non-admin caller sees no spelling of a body column

- **WHEN** a caller without `TABLE_READ_SENSITIVE` on the owning table reads
  `GET /v1/queries/entities/schema/dial_usage_log` and
  then queries `"dial_usage_log_payload.request_body"`
- **THEN** the schema carries no body column, and the query is rejected as an unknown field without
  revealing that the column exists

### Requirement: Seeded heavy columns on the built-in source tables

The catalog migrations SHALL seed the `heavy` flag on the built-in tables' raw-payload columns
so that a wildcard projection over the seeded schema is cheap by default. The seeded heavy set SHALL
be `dial_usage_log_payload.request_body`, `dial_usage_log_payload.response_body`, and
`dial_usage_log_payload.assembled_response` — the verbatim LLM request, the verbatim response, and the
producer's merged response, the only seeded columns whose per-row size is unbounded. Every other
seeded column SHALL default to `heavy = false`, including the `object`-typed
`dial_usage_log_payload.jwt_claims` and `dial_usage_log_payload.request_tags` (small claim/header
documents) and `rate_analytics.comment` (a short free-text note). `heavy` and payload ownership SHALL
remain independent: the claim/header pair lives on the enrichment without being heavy.

All three seeded heavy columns are also seeded `sensitive` (see "Seeded column sensitivity on the
built-in source tables"), so for a caller without `TABLE_READ_SENSITIVE` on the owning table they are
already absent from the
model and the heavy flag changes nothing.

For an entitled caller the flag is now belt-and-braces on a wildcard over `dial_usage_log`: every
heavy seeded column is an enrichment column, and a wildcard never fans out into an enrichment join, so
the three bodies are omitted whether or not they are flagged. The flag remains load-bearing where the
enrichment rule does not reach — a wildcard's expansion is still reported with `heavy` in discovery,
the `validate-sql` heavy-column warning is keyed on it, and an operator flipping it through the API
still changes what a wildcard over a non-seeded table returns.

The claim and header columns are excluded from a wildcard over `dial_usage_log` too, but by the
enrichment rule alone rather than by the heavy flag — which is what shows the two rules are
independent.

#### Scenario: Seeded heavy flags after migration

- **WHEN** the catalog is read after the table-catalog migrations run
- **THEN** `dial_usage_log_payload` reports `request_body`, `response_body`, and `assembled_response`
  with `heavy: true`, and every other column of `dial_usage_log`, `dial_usage_log_payload` and
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

### Requirement: Seeded payload enrichment on dial_usage_log

The catalog migrations SHALL seed a third built-in table, `dial_usage_log_payload`, as an
`enrichment` of `dial_usage_log` with `status=ACTIVE`, `system=true` and `grain_key=event_id`, and
SHALL therefore report `cardinality` as `ZERO_OR_ONE` (a derived facet of having a grain key, not a
stored one). It SHALL expose exactly three columns — `request_body`, `response_body`,
`assembled_response` — which SHALL resolve into `dial_usage_log`'s flat schema under the qualified
names `"dial_usage_log_payload.request_body"`, `"dial_usage_log_payload.response_body"` and
`"dial_usage_log_payload.assembled_response"`, by the ordinary grain join (see "Grain-based enrichment
join with enforced cardinality").

**BREAKING**: those five columns SHALL NOT be exposed as columns of `dial_usage_log`. A query naming
any of them as a bare field of `dial_usage_log` SHALL be rejected as an unknown field, exactly as any
other undeclared name is.

The seed SHALL move the five existing `column_mapping` rows rather than re-declare them, so every
per-column attribute — `sensitive`, `heavy`, `nullable`, `description`, `display_name`, `tag` — is
carried over unchanged and continues to be reported by `GET /v1/tables/dial_usage_log_payload` and by
schema discovery under the qualified name. Vacating those ordinals MAY leave gaps in
`dial_usage_log`'s column order; the surviving columns SHALL keep the relative order they had.

The definition's physical columns beyond the three exposed ones — its `event_id` grain key,
`request_time`, `trace_id`, `core_span_id`, and its `_updated_at` version column — SHALL NOT appear in
the exposed schema, matching the existing rule that an enrichment's grain key is a hidden physical
column.

The definition's TTL fields (`ttl_column`, `ttl_interval`, `ttl_unit`) SHALL be unset, because a
seeded system table's retention is owned by its ClickHouse migration and is not mirrored into a catalog
the API could try to modify (see "Table TTL").

#### Scenario: The payload enrichment is present after migration

- **WHEN** the catalog is read after the table-catalog migrations run
- **THEN** it contains `dial_usage_log_payload` as an `enrichment` with `source_table=dial_usage_log`,
  `status=ACTIVE`, `system=true`, `grain_key=event_id`, and its three exposed columns

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
  but not `TABLE_READ_SENSITIVE`
  reads the query schema and then queries that column by name
- **THEN** the column is present in the discovered schema and the query returns its values — the flag
  changes only what a wildcard expands to
