# structured-query Delta

## MODIFIED Requirements

### Requirement: Filter sub-grammar (CQL2-JSON)

The system SHALL model a recursive filter tree as a sealed `FilterNode` hierarchy discriminated by
the `op` key: logical nodes (`and`, `or`, `not`) carrying a list of child nodes, and predicate nodes
carrying an operator code (`eq`, `ne`, `co`, `nc`, `ico`, `inc`, `lt`, `gt`, `le`, `ge`, `in`) and a
positional `args` list of expressions. The contains family has four operators: `co`/`nc` are
**case-sensitive** contains / not-contains, and `ico`/`inc` are **case-insensitive** contains /
not-contains. (**BREAKING**: `co`/`nc` previously matched case-insensitively; clients wanting the
old behavior use `ico`/`inc`.) Because `op` is both the discriminator and operator data, routing
SHALL be performed by a custom deserializer wired at each use site, not by a declarative
`@JsonTypeInfo` on the interface.

#### Scenario: Nested boolean tree binds

- **WHEN** a filter `(event_kind = 'llm_call' AND total_tokens > 100) OR (...)` is deserialized
- **THEN** the root binds to a logical `OR` node whose children are logical `AND` nodes containing
  predicate nodes with their operator codes and operand expressions

#### Scenario: Both operands are general expressions

- **WHEN** a predicate `length(project_id) = 3` is deserialized
- **THEN** the predicate's `args` hold a function expression on the left and a value expression on the
  right, with no requirement that either operand be a bare column

#### Scenario: Logical-vs-predicate routing by op

- **WHEN** a node's `op` is `and`/`or`/`not`
- **THEN** it routes to the logical node type; otherwise it routes to the predicate node type

#### Scenario: Case-insensitive contains binds

- **WHEN** a predicate `{"op": "ico", "args": [...]}` or `{"op": "inc", "args": [...]}` is deserialized
- **THEN** it binds to a predicate node with the case-insensitive contains (or not-contains) operator

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
contract: `eq`/`ne`/`lt`/`gt`/`le`/`ge` to direct comparisons, `co`/`nc` to **case-sensitive**
`LIKE`/`NOT LIKE` contains patterns, `ico`/`inc` to **case-insensitive** `LIKE`/`NOT LIKE`
contains patterns, `in` to `IN`, and `eq`/`ne` against a null literal to `IS NULL`/`IS
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

#### Scenario: Contains operator maps to case-sensitive LIKE

- **WHEN** a query uses the `co` operator on a string field
- **THEN** the translator emits a case-sensitive `LIKE '%value%'` predicate that matches only
  exact-case occurrences

#### Scenario: Case-insensitive contains maps to case-insensitive LIKE

- **WHEN** a query uses the `ico` operator on a string field
- **THEN** the translator emits a case-insensitive `LIKE '%value%'` predicate

#### Scenario: Aggregate query groups by base field and select alias

- **WHEN** an aggregate-mode query groups by a field and selects an aliased aggregate
- **THEN** the translator builds GROUP BY against the field and resolves `having`/`sort` references
  against the base fields plus the select alias
