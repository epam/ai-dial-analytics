## MODIFIED Requirements

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
