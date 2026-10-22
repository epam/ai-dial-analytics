# table-management Delta Specification

## ADDED Requirements

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
an unset TTL SHALL be omitted from responses. The seeded `system` tables' migration-defined TTLs
SHALL NOT be mirrored into the catalog (their retention is migration-managed and the API cannot
modify them).

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

## MODIFIED Requirements

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
`tag`, `display_name`, `sensitive` flag, and `description` — and SHALL NOT contribute any
ClickHouse `ALTER` command; a request carrying only `update`s (or only an idempotent TTL removal)
SHALL issue no `ALTER` at all. Each `update` entry declares `{name, tag?, display_name?,
sensitive?, description?}`, keyed by the column's current exposed name, and SHALL follow
merge-patch semantics per field: a metadata field that is **absent or null** SHALL leave that
column attribute unchanged; a string field (`tag`, `display_name`, `description`) that is
**present and blank** SHALL clear that attribute; a string field that is **present and non-blank**
SHALL set it (trimmed); and `sensitive`, when **present**, SHALL set the flag to the supplied
boolean. A single `update` entry MAY change several metadata fields at once. Each `add` declares
`{source_name, name, type, element_type?, nullable?, description, display_name?, tag?,
sensitive?}` (`sensitive` defaults to false when omitted; `element_type` is required iff
`type=array`, see "Schema validation"), and an added `type=array` column SHALL materialize as
ClickHouse `Array(<element>)`.

An `update` entry MAY target a just-added or just-renamed column by its resulting exposed name. A
column's `display_name`, `tag`, `sensitive` flag, and `description` SHALL all be preserved across
a `rename`. Schema modification SHALL be breaking and immediate (a drop or rename takes effect at
once). A ClickHouse `ALTER` failure SHALL leave the table `ACTIVE` (surfaced via the `ErrorView`
contract, status unchanged) and SHALL NOT transition it to `FAILED` — so a materialized table that
holds data is never reachable by the define-and-materialize `POST .../schema` path (which is
`ACTIVE` → 409); because the statement is single and validated as a whole, a failure leaves no
partially-applied operations, and the catalog is written only after the `ALTER` succeeds. In
`oidc` mode this endpoint SHALL be authorized by `FULL_ADMIN` or a matching `modify` access role
on the table. The system SHALL reject: a type-change of an existing column (422); a drop or rename
of a system column (reserved `_`-prefix), the grain key, or an ordering-key column (422); a `drop`
of the column the table's stored TTL references (422 — remove or repoint the TTL in a prior
request first, even if the same request also carries a `ttl` member); an `update` of a column that
is not an exposed column of the table after the request's own add/drop/rename operations are
applied (422); a duplicate `update` of the same column within one request (422); and any operation
on a table not in the catalog (404). A `rename` of the TTL-referenced column SHALL be allowed:
ClickHouse rewrites the physical TTL expression itself, and the catalog's stored TTL column SHALL
be updated to the renamed name in the same transaction. Neither the `update` operation carries a
column mutability restriction — system, grain, and ordering-key columns MAY be tagged, given a
display name, marked sensitive, or described.

#### Scenario: Schema PATCH before materialization is rejected

- **WHEN** `PATCH /v1/tables/{name}/schema` is called on a `PENDING` or `FAILED` table
- **THEN** the request is rejected with HTTP 409 (define and materialize the schema first via
  `POST .../schema`)

#### Scenario: A frozen-key member in a PATCH is rejected

- **WHEN** `PATCH /v1/tables/{name}/schema` carries `ordering_key`, `partition_by`, `grain_key`, or
  `cardinality`
- **THEN** the request is rejected with HTTP 422 (physical keys are frozen after materialization)

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

### Requirement: Define and materialize a table schema

The system SHALL expose `POST /v1/tables/{name}/schema` to define the complete physical schema of a
not-yet-materialized table AND materialize it (issue `CREATE TABLE`, flip to `ACTIVE`) in a single
call. The endpoint SHALL be allowed only while the table's `status` is `PENDING` or `FAILED`; on an
already `ACTIVE` (materialized) table it SHALL be rejected with HTTP 409 (a materialized schema evolves
through `PATCH /v1/tables/{name}/schema`).

The request body SHALL carry the table's physical schema: `columns` (the exposed columns, each
`{source_name, name, type, element_type?, nullable?, description?, display_name?, tag?, sensitive?}`)
plus the physical keys for the table's kind — `ordering_key` and optional `partition_by` for a
`source`; `grain_key` and optional `cardinality` for an `enrichment` — and an optional `ttl`
declaration `{column, interval, unit}` (see "Table TTL" for its validation and rendering rules).
The `name`, `type`, and `source_table` (identity/relationship) SHALL NOT appear in this body (they
are set and fixed by `POST /v1/tables`).

The system SHALL validate the schema — both completeness for the table's kind and internal consistency
(see "Schema validation") — before persisting anything or issuing any ClickHouse DDL: a `source` SHALL
have at least one column and a non-empty `ordering_key` whose columns (and `partition_by` column, if
any) are present; an `enrichment` SHALL have a `grain_key` that is a declared column of its
`source_table` and MAY have zero user columns; a `ttl`, when present, SHALL satisfy the "Table TTL"
constraints (declared non-nullable `date`/`timestamp` column, positive `interval`, valid `unit`). An
incomplete or inconsistent schema SHALL be rejected with HTTP 422, leaving the definition untouched
(still `PENDING`/`FAILED`, nothing persisted).

On a valid schema the system SHALL persist the columns + keys (and the `ttl`, when declared) and
issue a single ClickHouse `CREATE TABLE` (writer credential) — a `source` as `MergeTree ORDER BY
(ordering_key)` (with `PARTITION BY` when `partition_by` is set), an `enrichment` as
`ReplacingMergeTree(_updated_at)` with `ORDER BY (grain_key)` carrying its grain-key and system
columns, the version column rendered as `_updated_at DateTime64(3) DEFAULT now64(3)` so that every
write lands with a strictly increasing RMT version and duplicate writes for the same grain collapse
to the latest on a `final=1` read — with a `TTL` clause appended when a `ttl` is declared — and flip
the definition to `status=ACTIVE`, returning the materialized definition. A `type=array` column SHALL
materialize as ClickHouse `Array(<element>)`. If the `CREATE TABLE` fails or its outcome is ambiguous
(e.g. a timeout under which ClickHouse may still create the table), the system SHALL issue a
best-effort `DROP TABLE IF EXISTS`, set the definition to `status=FAILED`, and return an error using
the `ErrorView` contract without leaking ClickHouse internals; a `FAILED` table MAY be re-posted
(with its full schema) to retry. In `oidc` mode the endpoint SHALL be authorized by `FULL_ADMIN` or a
matching `modify` access role, and a `system`-owned table SHALL reject it with HTTP 409 regardless of
role.

#### Scenario: Define and materialize a source

- **WHEN** `POST /v1/tables/{name}/schema` is called on a `PENDING` source with `columns` and a
  non-empty `ordering_key` referencing declared columns
- **THEN** a single `CREATE TABLE ... ENGINE = MergeTree ORDER BY (...)` is issued, the definition
  becomes `status=ACTIVE`, and the response reflects the materialized schema

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
