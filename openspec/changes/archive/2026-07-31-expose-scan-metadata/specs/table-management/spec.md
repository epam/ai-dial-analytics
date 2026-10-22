## MODIFIED Requirements

### Requirement: Read and list table definitions

The system SHALL expose `GET /v1/tables/{name}` returning a single definition including its current
`columns` (404 if absent) and `GET /v1/tables` returning all definitions — sources, enrichments, and
aggregations — as a response object (not a bare array). Listing SHALL use a stable order. Each list
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
from modification. Each returned definition SHALL also carry its `identity_column` and
`version_column` — the pair defining a source's total order for the governed incremental scan — each
absent when the definition has none, so a consumer can tell which column identifies a row, which column
carries its version, and therefore whether the table is scannable at all. The table-management read
surface is not column-role-filtered — it returns every
column with its `sensitive` flag so operators can audit and manage sensitivity; role-based hiding of
sensitive columns applies to the query/discovery surface (see
[query-schema-discovery](../query-schema-discovery/spec.md)), not to `/v1/tables`.

#### Scenario: Get a definition with its columns

- **WHEN** `GET /v1/tables/{name}` is called for an existing definition
- **THEN** the response is 200 with the definition's `name`, `description`, `type`, `status`,
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
- **THEN** that column in the response carries `sensitive: true`, while columns that did not declare
  the flag carry `sensitive: false`

#### Scenario: Column heavy flag declared in the schema is returned

- **WHEN** a source's schema (`POST /v1/tables/{name}/schema`) declares a column with `heavy: true`
  and the table is materialized, then `GET /v1/tables/{name}` is called
- **THEN** that column in the response carries `heavy: true`, while columns that did not declare the
  flag carry `heavy: false`

#### Scenario: List includes all types

- **WHEN** `GET /v1/tables` is called
- **THEN** the response object lists every definition, including enrichment and aggregation
  definitions, in a stable order

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

### Requirement: Schema validation

The system SHALL validate every physical-schema definition before persisting or issuing DDL — the
define-and-materialize `POST /v1/tables/{name}/schema` and the live `PATCH /v1/tables/{name}/schema` —
rejecting invalid requests with HTTP 422. Validation SHALL cover: table and column names match the
snake_case identifier grammar `^[a-z][a-z0-9_]*$` (≤ 64 chars) and do not use the reserved `_`-prefix
(which is for system columns); a source declares at least one column and a non-empty `ordering_key`,
and its `ordering_key`/`partition_by` reference declared columns; a source declares BOTH an
`identity_column` and a `version_column`, each referencing a declared column of that table; an
enrichment declares no `identity_column` and MAY declare a `version_column` naming one of its own
columns or the ReplacingMergeTree version `_updated_at`; an enrichment's `grain_key` is
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

#### Scenario: Non-snake_case or reserved-prefix name is rejected

- **WHEN** a `POST .../schema` or a `PATCH` declares a table or column name that is not snake_case or begins
  with `_`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Schema without an ordering key is rejected

- **WHEN** `POST /v1/tables/{name}/schema` declares `columns` for a source but no `ordering_key`
- **THEN** the request is rejected with HTTP 422 and nothing is persisted (the table stays `PENDING`)

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

### Requirement: Define and materialize a table schema

The system SHALL expose `POST /v1/tables/{name}/schema` to define the complete physical schema of a
not-yet-materialized table AND materialize it (issue `CREATE TABLE`, flip to `ACTIVE`) in a single
call. The endpoint SHALL be allowed only while the table's `status` is `PENDING` or `FAILED`; on an
already `ACTIVE` (materialized) table it SHALL be rejected with HTTP 409 (a materialized schema evolves
through `PATCH /v1/tables/{name}/schema`).

The request body SHALL carry the table's physical schema: `columns` (the exposed columns, each
`{source_name, name, type, element_type?, nullable?, description?, display_name?, tag?, sensitive?,
heavy?}`)
plus the physical keys for the table's kind — `ordering_key`, optional `partition_by`, and the REQUIRED
scan-metadata pair `identity_column`/`version_column` for a
`source`; `grain_key`, optional `cardinality` and an optional `version_column` for an `enrichment` — and an optional `ttl`
declaration `{column, interval, unit}` (see "Table TTL" for its validation and rendering rules).
The `name`, `type`, and `source_table` (identity/relationship) SHALL NOT appear in this body (they
are set and fixed by `POST /v1/tables`).

`identity_column` and `version_column` are the pair the governed incremental scan pages a source by (see
[enrichment-runtime](../enrichment-runtime/spec.md)).

A `source` SHALL declare BOTH: a schema request for a source omitting either SHALL be rejected with HTTP
422 naming the missing field, so a source cannot be materialized that no enrichment rule could ever page.
Each SHALL reference a column declared in the same request (422 otherwise) — the only property this
endpoint checks. That the version column is assigned at ingest, monotonic and never backdated, and that
the identity column is unique per row, remain the declaring caller's promise: they are physical facts
about ingest, and the scan's exactly-once guarantee rests on them.

An `enrichment` SHALL NOT declare an `identity_column` (422): it is keyed on its `grain_key`. It MAY
declare a `version_column`, which SHALL reference one of its own declared columns or the
ReplacingMergeTree version `_updated_at` — a system column, and so deliberately absent from the exposed
columns. An omitted `version_column` SHALL leave the stored value unchanged, so re-posting a `FAILED`
table's schema never silently clears it.

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

#### Scenario: A source declares its scan metadata

- **WHEN** the same request also carries `identity_column` and `version_column`, each naming a column it
  declares
- **THEN** both are persisted on the definition, returned by `GET /v1/tables/{name}`, and a rule over that
  source scans it — with no migration in between

#### Scenario: A source schema missing either scan column is rejected

- **WHEN** `POST /v1/tables/{name}/schema` is called on a `PENDING` source with `columns` and a valid
  `ordering_key` but no `identity_column`, or no `version_column`
- **THEN** the request is rejected with HTTP 422 naming the missing field, no ClickHouse DDL is issued,
  and the definition remains `PENDING`/`FAILED`

#### Scenario: An enrichment may declare its version column

- **WHEN** an enrichment's schema request carries `version_column: "_updated_at"`, or the name of one of
  its own declared columns
- **THEN** the value is persisted and returned by `GET /v1/tables/{name}`

#### Scenario: A scan-metadata column absent from the declared columns is rejected

- **WHEN** `POST /v1/tables/{name}/schema` carries an `identity_column` or `version_column` naming a
  column the request does not declare
- **THEN** the request is rejected with HTTP 422 naming that column, no ClickHouse DDL is issued, and the
  definition remains `PENDING`/`FAILED`

#### Scenario: An identity column is not accepted for an enrichment

- **WHEN** `POST /v1/tables/{name}/schema` carries an `identity_column` for an `enrichment`
- **THEN** the request is rejected with HTTP 422 stating that it is keyed on its `grain_key`, and nothing
  is persisted

#### Scenario: An enrichment's version column must be one of its physical columns

- **WHEN** an enrichment's schema request carries a `version_column` that is neither one of its declared
  columns nor `_updated_at`
- **THEN** the request is rejected with HTTP 422 and nothing is persisted

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

### Requirement: Modify table schema via PATCH

The system SHALL reject a `drop` or a `rename` of a column named by the table's scan metadata — its
`identity_column` or its `version_column` — with HTTP 422, before any ClickHouse `ALTER` is issued.
The pair is declared once, when the schema is defined, and no endpoint repoints it, so either
operation would leave the table advertising a scan column that no longer exists, unpageable by the
governed incremental scan and unrepairable through the API.

#### Scenario: Dropping or renaming a scan-metadata column is rejected

- **WHEN** `PATCH /v1/tables/{name}/schema` carries a `drop` or a `rename` of the column named by the
  table's `identity_column` or `version_column`
- **THEN** the request is rejected with HTTP 422 before any `ALTER` is issued, and the table's columns
  and stored scan metadata are unchanged
- **AND** a `drop` or `rename` of a column the scan metadata does not name succeeds, leaving the
  stored pair intact
