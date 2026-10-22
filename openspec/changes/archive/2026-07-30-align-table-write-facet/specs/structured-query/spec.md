## MODIFIED Requirements

### Requirement: Query execution endpoint

The system SHALL execute a structured query submitted as a request body at
`POST /v1/queries/execute` and return its results. Execution SHALL be entity-agnostic: the request
SHALL be routed by its `entity` to a catalog-backed executor, where a valid `entity` is a
`ACTIVE` `source` table in the table-management catalog — of either write discipline, so an
`upsert_by_key` rollup source is queryable exactly like an `append` source. A query naming an entity
that is not a materialized source (unknown, an enrichment, or a non-materialized table) SHALL be
rejected with a validation error (HTTP 400). The query SHALL be translated to ClickHouse SQL — built
through a jOOQ query model on a connection-less `SQLDialect.CLICKHOUSE` render context and rendered to
a SQL string, then executed via the ClickHouse HTTP client — never assembled by raw string
concatenation.

#### Scenario: Row-mode query executes and returns rows

- **WHEN** a valid row-mode query for a queryable source (e.g. `dial_usage_log`) is posted to
  `/v1/queries/execute`
- **THEN** the response carries the projected rows as field-name → value maps

#### Scenario: Non-queryable entity is rejected

- **WHEN** a query names an entity that is not a materialized source (unknown, an enrichment, or a
  non-materialized table)
- **THEN** the request is rejected with HTTP 400

#### Scenario: An upsert-by-key source is queryable

- **WHEN** a query names an `ACTIVE` `source` whose write discipline is `upsert_by_key`
- **THEN** it is accepted and executed like any other source, with no aggregation-specific or
  engine-specific handling

### Requirement: Read result deduplication (latest-version-wins)

The system SHALL return query results as the deduplicated, latest-version-wins view of the underlying
data. For any table backed by a `ReplacingMergeTree` (an `upsert_by_key` source and every joined
enrichment), results SHALL reflect only the surviving (latest-version) row per sort key, exactly as if
a physical merge had completed, regardless of whether ClickHouse has actually merged the parts yet. The
system SHALL achieve this by applying ClickHouse `FINAL` semantics to every read the query pipeline
issues, via the query-level `final = 1` setting attached at execution time rather than by rewriting the
rendered SQL. Because the setting is applied to the executed statement, it SHALL apply uniformly to
the source table, all LEFT-JOINed enrichment tables, and the separate `count(*)` total-count query.
The setting SHALL be a no-op for tables whose engine does not support `FINAL` (an `append` source's
plain `MergeTree`), leaving their results unchanged. Deduplication SHALL be global and
always-on; there is no per-query opt-out. This guarantee is part of the shared execution pipeline and
therefore SHALL hold identically for the structured DSL, the REST SQL endpoint, and the Flight SQL /
ADBC endpoint, which all delegate to it.

#### Scenario: Source with unmerged duplicate rows returns one row per key

- **WHEN** a `ReplacingMergeTree` source (e.g. `dial_usage_log`) holds multiple not-yet-merged
  versions sharing its sort key and a row-mode query selects them
- **THEN** the response returns only the latest version per sort key, not the raw unmerged duplicates

#### Scenario: Total count reflects deduplicated rows

- **WHEN** a row-mode offset query with `include_total` runs against a source that has unmerged
  duplicate rows
- **THEN** the returned `totalCount` counts each sort key once (the deduplicated count), matching the
  deduplicated row set rather than the raw part-level row count

#### Scenario: Re-patched enrichment does not fan out source rows

- **WHEN** a query references an enrichment column and that enrichment has multiple unmerged versions
  for a grain key that matches source rows
- **THEN** each matching source row appears exactly once, joined to the latest enrichment version,
  because the enrichment side is deduplicated under the same `FINAL` semantics

#### Scenario: Recomputed upsert-by-key source row reads as the latest value

- **WHEN** an `upsert_by_key` source receives a second write for a key it already holds (a rollup
  bucket recomputed with a new value) and the parts have not merged
- **THEN** a query returns one row for that key carrying the later write's values, so a re-written key
  reads as a replacement rather than as two rows

#### Scenario: Append source is unaffected

- **WHEN** a query runs against an `append` source (a plain `MergeTree`, no version-collapsing engine)
- **THEN** the `final = 1` setting is a no-op and the returned rows are identical to today's behavior
