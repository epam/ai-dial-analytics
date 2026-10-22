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
SHALL NOT change the source row multiplicity **when the query is read final**. That 0..1 cardinality
holds regardless of pending background merges because a final read deduplicates the enrichment too (see
the read deduplication requirement), so an enrichment grain key with unmerged versions resolves to a
single latest row and never fans out the source. A query that is **not** read final joins the
enrichment's raw parts, so a grain key with unmerged versions MAY fan out the source row once per
version — exactly what the same join returns in ClickHouse without `FINAL`. For a user-facing read the
deduplication SHALL be rendered into this SQL: a final-read query SHALL carry ClickHouse's `FINAL`
modifier on its source table reference when the source is `upsert_by_key` (never on an `append` source's
plain `MergeTree`, which refuses it) and on every enrichment table it joins — inside the keyed join's
derived table when the join is keyed — and a query that is not read final SHALL carry it on none of
them. The key sub-query of a keyed join (`SELECT source.grain_key FROM source WHERE <key predicates>`)
SHALL NOT carry `FINAL`: the latest version of a key is one of its raw versions, so the raw key set is
a superset of the final one and keying still never changes which rows the statement returns, while
the sub-query does not pay a second merge. The internal pipeline reads that render through this
translator (enrichment scans, group fetch, Class-1 materialize, aggregate refresh and preview) SHALL
render no `FINAL` and keep deduplicating through the query-level `final = 1` execution setting, as the
read deduplication requirement states. Whether deduplication merges across partitions or stays inside
each one is decided by the read-deduplication requirement ("Deduplication SHALL stay inside a partition
wherever the catalog can vouch for it"); the 0..1 guarantee of a final read holds either way, because
an enrichment's partition column is immutable for a grain key, so no key ever has versions in two
partitions.
**The join SHALL be keyed by the source-only top-level conjuncts of the query's `filter`.** The filter's
top-level `and` SHALL be flattened, recursing into nested `and`s only; an `or`, a `not`, a comparison
and a subquery predicate are each one conjunct, never split further. A conjunct is **source-only** when
every field it references (the operands of a subquery predicate included, its body not) is a source
column. When at least one conjunct is source-only, the enrichment side of every referenced join SHALL
be emitted as a derived table — the grain key plus the enrichment columns the query references —
restricted to `enrichment.grain_key IN (SELECT source.grain_key FROM source WHERE <key predicates>)`
and aliased to the enrichment's own name, where `<key predicates>` are those source-only conjuncts
alone, so ClickHouse's right-side hash build covers the grain keys they select rather than the whole
enrichment table. The keying SHALL be statement-wide: a conjunct naming one enrichment's column SHALL
NOT prevent the source-only conjuncts beside it from keying that enrichment's join or any other. The
query's **whole** `filter` SHALL still be applied, unchanged, in the statement's own `WHERE`; no
enrichment conjunct SHALL be moved into, or copied into, a derived table. A query with no `filter`, or
whose `filter` has no source-only top-level conjunct (an enrichment-only filter, or an `or` mixing
source and enrichment columns), SHALL join the enrichment table unkeyed. The same rule SHALL apply to
the `include_total` count statement. Keying changes which rows the join build reads, never which rows
the statement returns, whatever shape the enrichment conjuncts take — including `IS NULL` and
`coalesce` over an enrichment column: the key predicates are a subset of the outer `WHERE`'s
conjuncts, so every source row the outer `WHERE` keeps passes them, its enrichment row (if it has one)
is in the derived table, and its `LEFT JOIN` result is the one the unkeyed join gives it. A projected enrichment column SHALL be returned under its
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

#### Scenario: A source-only filter keys the enrichment join

- **WHEN** a validated query projects an enrichment column and its `filter` names source columns only
  (for example `trace_id = 'x'`)
- **THEN** the rendered SQL `LEFT JOIN`s a derived table over the enrichment — its grain key and the
  referenced enrichment columns, restricted to `enrichment.grain_key IN (SELECT source.grain_key FROM
  source WHERE <filter>)` — aliased to the enrichment's name and joined on the same grain-key
  equality, and the statement's own `WHERE` still carries the filter

#### Scenario: A mixed and is keyed by its source-only conjuncts

- **WHEN** a validated query's `filter` is `request_time >= T AND trace_id = 'x' AND
  <enrichment>.<column> = 'v'` and the source and enrichment are partition-aligned on `request_time`
- **THEN** the enrichment is joined as a derived table restricted to `enrichment.grain_key IN (SELECT
  source.grain_key FROM source WHERE request_time >= T AND trace_id = 'x')` and to
  `enrichment.request_time IN (SELECT source.request_time FROM source WHERE request_time >= T AND
  trace_id = 'x')`
- **AND** neither key sub-query names the enrichment column
- **AND** the statement's own `WHERE` carries the whole filter, the enrichment conjunct included
- **AND** the `include_total` count statement is keyed by the same two conjuncts

#### Scenario: A filter on one enrichment keys another enrichment's join

- **WHEN** a validated query's `filter` is `request_time >= T AND <enrichment_a>.<column> = 'v'` and it
  projects a column of `<enrichment_b>`
- **THEN** both enrichments are joined as derived tables keyed by `request_time >= T` alone

#### Scenario: A filter naming an enrichment column joins the enrichment unkeyed

- **WHEN** a validated query's `filter` references enrichment columns only (it has no source-only
  top-level conjunct), or the query has no `filter`
- **THEN** the rendered SQL `LEFT JOIN`s the enrichment table itself, with no key sub-query

#### Scenario: A mixed or is left unkeyed

- **WHEN** a validated query's `filter` is `trace_id = 'x' OR <enrichment>.<column> = 'v'`
- **THEN** the filter has no source-only top-level conjunct, and the rendered SQL `LEFT JOIN`s the
  enrichment table itself, with no key sub-query

#### Scenario: An enrichment IS NULL conjunct beside a time window is keyed and returns the same rows

- **GIVEN** source rows inside and outside a time window, some inside it with an enrichment row and some
  without one
- **WHEN** a validated query's `filter` is `request_time >= T AND <enrichment>.<column> IS NULL`
- **THEN** the enrichment join is keyed by `request_time >= T`
- **AND** the query returns exactly the rows the same statement returns with the enrichment joined
  unkeyed: every in-window source row whose enrichment row is missing or holds a null in that column,
  and no out-of-window row

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

#### Scenario: A final-read query renders FINAL on the source and every joined enrichment

- **WHEN** a validated query that is read final references columns of two enrichments, one joined keyed
  and one unkeyed
- **THEN** the rendered SQL carries `FINAL` after the source table reference, after the unkeyed
  enrichment table, and after the enrichment table inside the keyed join's derived table, and the key
  sub-query `SELECT source.grain_key FROM source WHERE <key predicates>` carries none

#### Scenario: A query not read final renders no FINAL

- **WHEN** a validated query that is not read final references an enrichment column
- **THEN** the rendered SQL carries `FINAL` on no table reference, and a grain key of the enrichment
  with two unmerged versions returns its source row twice
