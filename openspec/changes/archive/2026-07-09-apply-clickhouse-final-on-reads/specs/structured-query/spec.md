## ADDED Requirements

### Requirement: Read result deduplication (latest-version-wins)

The system SHALL return query results as the deduplicated, latest-version-wins view of the underlying
data. For any table backed by a `ReplacingMergeTree` (the source and every joined enrichment),
results SHALL reflect only the surviving (latest-version) row per sort key, exactly as if a physical
merge had completed, regardless of whether ClickHouse has actually merged the parts yet. The system
SHALL achieve this by applying ClickHouse `FINAL` semantics to every read the query pipeline issues,
via the query-level `final = 1` setting attached at execution time rather than by rewriting the
rendered SQL. Because the setting is applied to the executed statement, it SHALL apply uniformly to
the source table, all LEFT-JOINed enrichment tables, and the separate `count(*)` total-count query.
The setting SHALL be a no-op for tables whose engine does not support `FINAL` (e.g. a plain
`MergeTree` user-created source), leaving their results unchanged. Deduplication SHALL be global and
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

#### Scenario: Plain MergeTree source is unaffected

- **WHEN** a query runs against a user-created plain `MergeTree` source (no version-collapsing engine)
- **THEN** the `final = 1` setting is a no-op and the returned rows are identical to today's behavior

## MODIFIED Requirements

### Requirement: SQL translation

The system SHALL translate a validated structured query into ClickHouse SQL by building a jOOQ query
model on the connection-less `SQLDialect.CLICKHOUSE` render context and rendering it to a SQL string
with literals inlined (the ClickHouse HTTP client executes a raw SQL string). Field bindings SHALL be
sourced from the table-management catalog; allowlisted fields SHALL expand to their physical columns,
qualified by their owning table. Source columns SHALL be referenced by their bare flat name; enrichment
columns SHALL be referenced by their table-qualified flat name `<enrichment_table>.<column>` (the same
name discovery publishes). When a query references columns contributed by one or more enrichments of
the source, the translator SHALL emit a `LEFT JOIN` of each referenced enrichment table on the shared grain key
`source.grain_key = enrichment.grain_key` (the same column name on both sides), joining only
the enrichments actually referenced across the
query's `select`/`filter`/`sort`/`group_by`; enrichment joins are single-key with 0..1 cardinality and
SHALL NOT change the source row multiplicity. That 0..1 cardinality holds regardless of pending
background merges because reads deduplicate the enrichment via `FINAL` semantics (see the read
deduplication requirement), so an enrichment grain key with unmerged versions resolves to a single
latest row and never fans out the source. The `FINAL` deduplication is applied through the query-level
`final = 1` execution setting, not rendered into this SQL, so the rendered `FROM`/`LEFT JOIN` clauses
are unchanged by it. A projected enrichment column SHALL be returned under its
qualified flat name as the result-row key. Comparison operators SHALL map to SQL per the wire
contract: `eq`/`ne`/`lt`/`gt`/`le`/`ge` to direct comparisons, `co`/`nc` to case-insensitive
`LIKE`/`NOT LIKE` with wildcards, `in` to `IN`, and `eq`/`ne` against a null literal to `IS NULL`/`IS
NOT NULL`. Aggregate functions SHALL translate to their SQL aggregates. Aggregate mode SHALL require
aliased select entries and SHALL build `group_by`/`having`/`sort` against the base fields together with
the select aliases. The offset pagination strategy SHALL be applied with a default limit of 100 and a
maximum of 1000.

#### Scenario: Row projection renders against the entity table

- **WHEN** a validated row-mode query projects two fields from the source with a filter, sort, and
  offset page
- **THEN** the rendered SQL selects those columns from the entity table with the filter, `ORDER BY`,
  and `LIMIT` present, and no enrichment is joined because none is referenced

#### Scenario: Query referencing an enrichment column joins that enrichment

- **WHEN** a validated query references an enrichment column by its qualified flat name
  `<enrichment_table>.<column>`
- **THEN** the rendered SQL `LEFT JOIN`s that enrichment on `source.grain_key = enrichment.grain_key`
  and selects/filters the enrichment column through the join, rendering the qualified flat name as a
  single quoted alias

#### Scenario: Two enrichments exposing the same column name are queryable via their qualified names

- **WHEN** a validated query references two columns that share a bare name but belong to different
  enrichments, each named by its qualified flat name (`<enrichment_a>.<col>` and `<enrichment_b>.<col>`)
- **THEN** both enrichments are `LEFT JOIN`ed and both columns are projected as distinct result fields

#### Scenario: Contains operator maps to case-insensitive LIKE

- **WHEN** a query uses the `co` operator on a string field
- **THEN** the translator emits a case-insensitive `LIKE '%value%'` predicate

#### Scenario: Aggregate query groups by base field and select alias

- **WHEN** an aggregate-mode query groups by a field and selects an aliased aggregate
- **THEN** the translator builds GROUP BY against the field and resolves `having`/`sort` references
  against the base fields plus the select alias
