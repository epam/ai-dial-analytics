## ADDED Requirements

### Requirement: Output column naming when `as` is omitted

A select entry's `as` key SHALL be optional for every expression kind and in both query modes; no
query SHALL be rejected for omitting it. When `as` is absent (or blank), the system SHALL derive the
output column name the way PostgreSQL derives an unnamed target-list name, restricted to the
expressions this DSL can express:

- a field expression SHALL yield the field's own name **verbatim**, including the dots of an
  enrichment column's qualified flat name (`deployment_ref.display_name` stays whole — the dot is part
  of the name, not a table qualifier);
- a function expression (scalar, aggregate, or ordered-set aggregate, with or without `distinct`)
  SHALL yield the lowercase catalog function name (`count`, `sum`, `date_bin`, `percentile_cont`);
- a value literal SHALL yield `?column?`, PostgreSQL's placeholder for a name it cannot derive;
- an array expression SHALL yield `array` (unreachable in a projection today, since array literals are
  only valid as an `in` right operand).

Because rows are name-keyed maps and result schemas are flat field lists, output names SHALL be unique
— unlike PostgreSQL, which tolerates duplicates. Uniqueness SHALL be established deterministically from
the select list's order in two passes: explicit `as` values SHALL be reserved first, in list order, an
exact repeat of an earlier explicit alias taking the first free `<name>_<n>` (n from 1); derived names
SHALL then be assigned in list order, each taking the first free candidate from `<base>`, `<base>_1`,
`<base>_2`, … An explicit alias SHALL therefore always appear under exactly the name the caller asked
for, and derived names SHALL move out of its way regardless of position in the list.

The resolved names SHALL be the response-row keys, the rendered SQL aliases, and the reference names
available to `group_by`, `sort`, and `having` — one resolution shared by execution, DSL→SQL rendering,
and SQL translation, so those three can never disagree.

#### Scenario: Unaliased aggregate takes the function name

- **WHEN** an aggregate-mode query groups by `model` and selects `{ "expr": { "type": "fn", "name": "count", "args": [] } }` with no `as`
- **THEN** the request succeeds and each row carries the keys `model` and `count`

#### Scenario: Unaliased scalar function takes the function name

- **WHEN** a row-mode query selects `{ "expr": { "type": "fn", "name": "lower", "args": [{ "type": "field", "name": "deployment" }] } }` with no `as`
- **THEN** the request succeeds and each row carries the key `lower`

#### Scenario: Colliding derived names are suffixed by position

- **WHEN** an aggregate-mode query selects two unaliased `count` entries (e.g. `count()` and `count(distinct model)`)
- **THEN** the request succeeds and the rows carry the keys `count` and `count_1`, in select-list order

#### Scenario: An explicit alias keeps its exact name against a colliding derived name

- **WHEN** a query selects an unaliased `count()` entry followed by an entry aliased `count`
- **THEN** the entry aliased `count` is returned under `count`, and the unaliased aggregate is returned
  under `count_1`

#### Scenario: Repeated explicit aliases are disambiguated

- **WHEN** a query selects two entries both aliased `total`
- **THEN** the first is returned under `total` and the second under `total_1`, rather than one silently
  overwriting the other in the row map

#### Scenario: Projected literal takes PostgreSQL's placeholder name

- **WHEN** a row-mode query projects a value literal with no `as`
- **THEN** the request succeeds and the row key is `?column?`

#### Scenario: Enrichment column keeps its qualified flat name

- **WHEN** a row-mode query selects the enrichment field `deployment_ref.display_name` with no `as`
- **THEN** the row key is the full qualified flat name `deployment_ref.display_name`

#### Scenario: Derived name is referenceable from group_by, sort, and having

- **WHEN** an aggregate-mode query selects an unaliased `count()` and orders by `count` descending, or
  filters `having` on `count`
- **THEN** the reference resolves to that select output and the request succeeds without any `as`

## MODIFIED Requirements

### Requirement: Structured query envelope

The system SHALL provide an immutable request object model representing a single structured query
envelope with the fields `entity` (required), `filter`, `mode` (required), `select`, `group_by`,
`aggregate`, `having`, `sort`, and `page`. JSON keys SHALL be `snake_case` (`group_by`, `include_total`,
`value_type`, and the optional output-alias key `as`).

#### Scenario: Full row-mode envelope binds

- **WHEN** a row-mode JSON query with `entity`, `mode: "row"`, `filter`, `select`, `sort`, and an
  `offset` page is deserialized into the envelope record
- **THEN** every field binds to its typed component, `mode` resolves to `ROW`, and `select`/`sort`
  resolve to their element record types

#### Scenario: Full aggregate-mode envelope binds

- **WHEN** an aggregate-mode JSON query with `group_by`, `select` aggregate entries (with or without
  the optional alias `as`), `having`, and a `cursor` page is deserialized
- **THEN** `mode` resolves to `AGGREGATE`, `group_by` binds to a string list, the aggregate select
  entries bind with their aliases where present and a null alias where absent, and `having` binds to a
  filter node

#### Scenario: Mode is required

- **WHEN** a query omits `mode`
- **THEN** request validation rejects it as a bad request; `mode` must be supplied explicitly and is
  never inferred from `group_by`/`select`. The remaining sections stay optional, with mode-coherence
  left to the validation layer.

### Requirement: Aggregation, sort, and pagination request shapes

The system SHALL model aggregate select entries (a function expression with an **optional** output
alias bound from the `as` key), a sort item (`field`, `dir` ∈ `asc`/`desc`, and an optional `nulls` ∈
`first`/`last`), and a sealed `PageSpec` hierarchy
discriminated by `type` with an offset strategy (`offset`, `limit`, `include_total`) and a cursor
strategy (`cursor`, `limit`).

#### Scenario: Offset page binds

- **WHEN** `{ "type": "offset", "offset": 0, "limit": 25, "include_total": true }` is deserialized
- **THEN** it binds to the offset page record with `include_total` mapped from the snake_case key

#### Scenario: Cursor page binds with absent cursor

- **WHEN** `{ "type": "cursor", "limit": 100 }` (no `cursor`) is deserialized
- **THEN** it binds to the cursor page record with a null cursor (first page)

#### Scenario: Sort item null ordering binds

- **WHEN** a sort item `{ "field": "total_tokens", "dir": "asc", "nulls": "last" }` is deserialized
- **THEN** `nulls` binds to LAST; when `nulls` is omitted it is null and the SQL translation emits no
  NULLS clause, letting the database default apply (`ASC` → NULLS LAST, `DESC` → NULLS FIRST)

#### Scenario: Aggregate alias binds from `as`

- **WHEN** a select entry `{ "expr": { "type": "fn", "name": "count", "args": [] }, "as": "total" }`
  is deserialized
- **THEN** the alias component binds from the `as` key

#### Scenario: Select entry binds with no alias

- **WHEN** a select entry `{ "expr": { "type": "fn", "name": "count", "args": [] } }` is deserialized
- **THEN** it binds with a null alias component and is accepted in both modes, the output name being
  derived at translation time

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
NOT NULL`. Aggregate functions SHALL translate to their SQL aggregates. Every entry of a non-empty
`select` SHALL be rendered with an explicit SQL alias carrying its resolved output name (see the
output-column-naming requirement) in both modes, so the executed statement itself declares the
result-row keys instead of relying on the database's own naming of an unaliased expression; an
omitted/empty row-mode `select` SHALL keep projecting the source's own columns under their own names.
Aggregate mode SHALL NOT require caller-supplied aliases and SHALL build `group_by`/`having`/`sort`
against the base fields together with the resolved select output names. Where a name matches both an
entity column and a select output, resolution SHALL follow PostgreSQL clause by clause: `sort` SHALL
resolve to the **select output**; `group_by` SHALL resolve to the **entity column**, falling back to the
select output for a name that is not a bound field (so a computed group key such as a `width_bucket`
output stays groupable); and `having` SHALL resolve to the entity column unless the caller supplied that
name as an explicit `as` alias. A `group_by` key that is both a bound entity column **and** the explicit
`as` alias of a select entry projecting a different expression SHALL be rejected as ambiguous
(`400 bad_request` naming the key): the two readings group differently and neither the DSL document nor
its SQL rendering — a bare `GROUP BY "<name>"`, which names the column on re-parse — can distinguish
them. An alias that repeats the name of the field it projects is not such a collision, since both
readings group by the same column. The offset pagination strategy SHALL be applied with a default limit of
100 and a maximum of 1000.

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

#### Scenario: Unaliased aggregate select renders an explicit derived alias

- **WHEN** an aggregate-mode query selects `count()` with no `as`
- **THEN** the rendered SQL aliases the aggregate as `"count"`, and `group_by`/`having`/`sort` may
  reference `count`

#### Scenario: Derived output name does not shadow an entity column in having or group_by

- **WHEN** an entity owns a column named `count`, and an aggregate-mode query groups by that column,
  selects an unaliased `count()` aggregate, and filters `having` on `count`
- **THEN** both `group_by` and `having` resolve to the entity column, and the aggregate is still
  returned under its derived output name

#### Scenario: Computed select output stays groupable by name

- **WHEN** an aggregate-mode query selects a `width_bucket` expression aliased `bucket` and groups by
  `bucket`, which is not an entity column
- **THEN** `group_by` falls back to the select output and groups by that output column

#### Scenario: group_by key that is both a column and the alias of another expression is rejected

- **WHEN** an aggregate-mode query selects `date_bin(1, 'hour', request_time)` under the explicit alias
  `request_time` (the name of an entity column) and groups by `request_time`
- **THEN** the request is rejected with `400 bad_request` naming the ambiguous key, no query is
  executed, and the message points at renaming the alias to a name that is not a column of the entity

#### Scenario: Alias repeating its own field name stays groupable

- **WHEN** an aggregate-mode query selects `{field: event_kind, as: "event_kind"}` plus an aggregate and
  groups by `event_kind`
- **THEN** the query is accepted and groups by the entity column, because the alias and the column name
  the same grouping
