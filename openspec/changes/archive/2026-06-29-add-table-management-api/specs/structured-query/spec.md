## MODIFIED Requirements

### Requirement: Query execution endpoint

The system SHALL execute a structured query submitted as a request body at
`POST /v1/queries/execute` and return its results. Execution SHALL be entity-agnostic: the request
SHALL be routed by its `entity` to a catalog-backed executor, where a valid `entity` is a
`MATERIALIZED` `source` table in the table-management catalog. A query naming an entity that is not a
materialized source (unknown, an enrichment, an aggregation, or a non-materialized table) SHALL be
rejected with a validation error (HTTP 400). The query SHALL be translated to ClickHouse SQL — built
through a jOOQ query model on a connection-less `SQLDialect.CLICKHOUSE` render context and rendered to
a SQL string, then executed via the ClickHouse HTTP client — never assembled by raw string
concatenation.

#### Scenario: Row-mode query executes and returns rows

- **WHEN** a valid row-mode query for a queryable source (e.g. `dial_usage_log`) is posted to
  `/v1/queries/execute`
- **THEN** the response carries the projected rows as field-name → value maps

#### Scenario: Non-queryable entity is rejected

- **WHEN** a query names an entity that is not a materialized source (unknown, an enrichment, an
  aggregation, or a non-materialized table)
- **THEN** the request is rejected with HTTP 400

### Requirement: SQL translation

The system SHALL translate a validated structured query into ClickHouse SQL by building a jOOQ query
model on the connection-less `SQLDialect.CLICKHOUSE` render context and rendering it to a SQL string
with literals inlined (the ClickHouse HTTP client executes a raw SQL string). Field bindings SHALL be
sourced from the table-management catalog; allowlisted fields SHALL expand to their physical columns,
qualified by their owning table. Source columns SHALL be referenced by their bare flat name; enrichment
columns SHALL be referenced by their table-qualified flat name `<enrichment_table>.<column>` (the same
name discovery publishes). When a query references columns contributed by one or more enrichments of
the source, the translator SHALL emit a `LEFT JOIN` of each referenced enrichment table on
`source.source_key = enrichment.local_key`, joining only the enrichments actually referenced across the
query's `select`/`filter`/`sort`/`group_by`; enrichment joins are single-key with 0..1 cardinality and
SHALL NOT change the source row multiplicity. A projected enrichment column SHALL be returned under its
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
- **THEN** the rendered SQL `LEFT JOIN`s that enrichment on `source.source_key = enrichment.local_key`
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
