## MODIFIED Requirements

### Requirement: Create an enrichment table

The system SHALL create an `enrichment` table at `POST /v1/tables` from `{name, description,
type:"enrichment", source_table, grain_key, cardinality}`. The `source_table` SHALL name an existing
`type=source` definition; the `grain_key` SHALL be a declared column of that source (any column, not
necessarily one of its `ordering_key` columns); `cardinality` SHALL be `ZERO_OR_ONE` in this phase. On
success the system SHALL create the enrichment in ClickHouse as `ReplacingMergeTree(_updated_at)` with
`ORDER BY (grain_key)` carrying only its system columns (no user columns yet), and return HTTP 201
with `status=ACTIVE`. The system version column SHALL be rendered as `_updated_at DateTime64(3)
DEFAULT now64(3)` so that every write lands with a strictly increasing RMT version and duplicate
writes for the same grain collapse to the latest on a `final=1` read. User columns are added
afterward via `PATCH /v1/tables/{name}/schema`. A `grain_key` outside the source's `ordering_key` is
permitted but is not index-seekable on the source side of the join (full-scan); this trade-off is the
caller's choice.

#### Scenario: Create an enrichment names its source and grain

- **WHEN** `POST /v1/tables` is called with `type:"enrichment"`, a `source_table` naming an existing
  source, and a `grain_key` that is any declared column of that source
- **THEN** the response is 201, a `ReplacingMergeTree(_updated_at)` shell ordered by `grain_key` is
  created, and `status=ACTIVE`

#### Scenario: Enrichment version column carries a default

- **WHEN** an enrichment table is created
- **THEN** its `_updated_at` column is rendered `DateTime64(3) DEFAULT now64(3)`, so a write that
  omits `_updated_at` still receives an increasing version stamped by ClickHouse

#### Scenario: Enrichment without a valid source is rejected

- **WHEN** `POST /v1/tables` is called with `type:"enrichment"` and a missing or non-`source`
  `source_table`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Grain key outside the source ordering key is accepted

- **WHEN** `POST /v1/tables` is called for an enrichment whose `grain_key` is a declared column of the
  source that is not part of the source's `ordering_key`
- **THEN** the response is 201 and the enrichment shell is created

#### Scenario: Grain key that is not a declared column of the source is rejected

- **WHEN** `POST /v1/tables` is called for an enrichment whose `grain_key` is not a declared column of
  the source
- **THEN** the request is rejected with HTTP 422

### Requirement: Write rows into a materialized table

The system SHALL accept row inserts at `POST /v1/tables/{name}/rows` from a body carrying a non-empty
`rows` array, each row a JSON object keyed by physical column name (the `source_name`s, and for an
enrichment its grain key). The system SHALL insert the rows into the table's ClickHouse table using
the writer credential and return the inserted count. In `oidc` mode this endpoint SHALL be authorized
by `FULL_ADMIN` or a matching `write` access role on the table (see the `table-access-control`
capability). System-owned columns (the `_`-prefixed columns, including the enrichment version column
`_updated_at`) SHALL NOT be caller-settable: a row that names one SHALL be rejected with HTTP 422, and
these columns SHALL be omitted from the insert so ClickHouse stamps their defaults. It SHALL reject a
write to an unknown table (404), a write to a table that is not materialized (409), and a row that is
empty or references a column that is not a physical column of the table (422). This is the minimal
data-write surface for populating sources and enrichments; richer ingest/upsert semantics are out of
scope for this phase.

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

#### Scenario: Write attempting to set a system column is rejected

- **WHEN** a row attempts to set a `_`-prefixed system column such as `_updated_at`
- **THEN** the request is rejected with HTTP 422 and ClickHouse stamps the column default on the (otherwise valid) inserts

#### Scenario: A write access role authorizes a non-admin write

- **WHEN** a non-`FULL_ADMIN` caller holding a matching `write` access role on the table `POST`s rows
- **THEN** the write is authorized and processed
