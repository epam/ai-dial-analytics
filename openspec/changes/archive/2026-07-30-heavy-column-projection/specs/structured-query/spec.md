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
contract: `eq`/`ne`/`lt`/`gt`/`le`/`ge` to direct comparisons, `co`/`nc` to **case-sensitive**
`LIKE`/`NOT LIKE` contains patterns, `ico`/`inc` to **case-insensitive** `LIKE`/`NOT LIKE`
contains patterns, `in` to `IN`, and `eq`/`ne` against a null literal to `IS NULL`/`IS
NOT NULL`. Aggregate functions SHALL translate to their SQL aggregates. Every entry of a non-empty
`select` SHALL be rendered with an explicit SQL alias carrying its resolved output name (see the
output-column-naming requirement) in both modes, so the executed statement itself declares the
result-row keys instead of relying on the database's own naming of an unaliased expression; an
omitted/empty row-mode `select` SHALL keep projecting the source's own **non-heavy** columns under
their own names (see "Wildcard projection excludes heavy columns").
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

## ADDED Requirements

### Requirement: Wildcard projection excludes heavy columns

A **wildcard projection** — a row-mode query whose `select` is omitted or empty — SHALL expand to the
entity's source columns that are **not** marked `heavy` in the table-management catalog (see the
`table-management` capability's "Heavy column metadata"), in their existing order and under their own
names. Enrichment columns SHALL continue to be excluded from the expansion, unchanged — and a SQL
`SELECT *` SHALL now expand to this same field set (see the `sql-query-translation` capability), so the
DSL and SQL wildcards resolve to one rule.

The exclusion SHALL apply **only** to the wildcard expansion. A `heavy` column named explicitly in
`select` SHALL be projected and returned exactly as before, and a `heavy` column SHALL remain fully
usable in `filter`, `group_by`, `having`, `sort`, and as a function argument. Heaviness SHALL NOT
change any value, type, or null semantics — a heavy column is either projected in full or not at all.

A wildcard projection whose expansion would be **empty** — every source column visible to the caller
is heavy — SHALL be rejected with HTTP 400 (`bad_request`), with a message naming the entity and
directing the caller to name the columns explicitly. The system SHALL NOT execute a zero-column query.

Because both flags are evaluated over the caller's role-visible model, the two filters compose: a
column that is `sensitive` and invisible to the caller is absent regardless of `heavy`, and a visible
heavy column is omitted from the wildcard for every caller alike, `FULL_ADMIN` included.

#### Scenario: Omitted select skips a heavy column

- **WHEN** a row-mode query with no `select` runs against an entity whose columns include one marked
  `heavy`
- **THEN** the response rows carry every non-heavy source column and no key for the heavy column

#### Scenario: Explicitly selected heavy column is returned

- **WHEN** a row-mode query names a `heavy` column in its `select`
- **THEN** the column is projected and its values are returned in full, exactly as for a non-heavy
  column

#### Scenario: Heavy column stays usable in other clauses

- **WHEN** a row-mode query with no `select` filters and sorts on a `heavy` column
- **THEN** the filter and sort apply normally and the response rows simply omit that column

#### Scenario: All-heavy wildcard is rejected

- **WHEN** a row-mode query with no `select` names an entity whose every caller-visible source column
  is marked `heavy`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the entity, and no query is
  executed

#### Scenario: Marking a column heavy is reversible mid-flight

- **WHEN** a wildcard query runs, the column is then marked `heavy` via
  `PATCH /v1/tables/{name}/schema`, and the same query runs again
- **THEN** the first response carries the column and the second does not, with no restart and no
  cached schema
