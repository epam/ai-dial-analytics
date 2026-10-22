## MODIFIED Requirements

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
write lands with a strictly increasing RMT version and duplicate writes for the same key collapse
to the latest on a `final=1` read — with a `TTL` clause appended when a `ttl` is declared — and flip
the definition to `status=ACTIVE`, returning the materialized definition. A `type=array` column SHALL
materialize as ClickHouse `Array(<element>)`. A `type=timestamp` column SHALL materialize as
`DateTime64(3, 'UTC')` — the zone SHALL be declared rather than left to the ClickHouse server's, because
the read path converts the zoneless value the driver surfaces to epoch milliseconds at UTC, and a time
bucket materialized into such a column would otherwise re-bucket on read in the server's zone rather than
the one it was computed in. If the `CREATE TABLE` fails or its outcome is ambiguous
(e.g. a timeout under which ClickHouse may still create the table), the system SHALL issue a
best-effort `DROP TABLE IF EXISTS`, set the definition to `status=FAILED`, and return an error using
the `ErrorView` contract without leaking ClickHouse internals; a `FAILED` table MAY be re-posted
(with its full schema) to retry. In `oidc` mode the endpoint SHALL be authorized by `FULL_ADMIN` or a
matching `modify` access role, and a `system`-owned table SHALL reject it with HTTP 409 regardless of
role.

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

### Requirement: Schema validation

The system SHALL validate every physical-schema definition before persisting or issuing DDL — the
define-and-materialize `POST /v1/tables/{name}/schema` and the live `PATCH /v1/tables/{name}/schema` —
rejecting invalid requests with HTTP 422. Validation SHALL cover: table and column names match the
snake_case identifier grammar `^[a-z][a-z0-9_]*$` (≤ 64 chars) and do not use the reserved `_`-prefix
(which is for system columns); a table name is not one of the schema names the SQL surface uses —
`adas` and `information_schema` — since SQL resolves a one-part name against schemas before tables, so
such an entity could not be reached as `FROM <name>` while discovery went on listing it
(see sql-query-translation, "Entity names resolve schema-qualified as well as bare"); a source declares at least one column and a non-empty `ordering_key`,
and its `ordering_key`/`partition_by` reference declared columns; a source's optional `identity_column`
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
