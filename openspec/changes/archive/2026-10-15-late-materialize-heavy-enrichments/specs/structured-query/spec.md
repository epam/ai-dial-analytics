## ADDED Requirements

### Requirement: Projection-only heavy enrichment columns are fetched for the returned page only

On the single-entity read path — `POST /v1/queries/execute`, `POST /v1/queries/execute-sql`, their CSV twins and
the Flight SQL endpoint — a block SHALL be **late-materialized** when all of these hold: it is in row mode and not
`distinct`; it renders a `LIMIT`; it references at least one `heavy` column of a keyable enrichment; every heavy
column of every such enrichment is referenced only by `select` entries — not by `filter`, `sort` (directly, or
through the name of a `select` entry that references it), `group_by` or `having`; no `select` entry contains a
window expression or `array_join`; it calls no non-deterministic catalog function other than `now`; every such
enrichment is joined on the same source grain key and the same aligned source partition column (or on the same
grain key, none of them aligned); and no `select` output is named `__adas_lm_key` or `__adas_lm_part`. Any other
block SHALL render as the "SQL translation" requirement describes. Composite SQL blocks, the translation-only
endpoints and every pipeline statement SHALL NOT be late-materialized.

A late-materialized block SHALL be rendered as one statement that evaluates its page's keys **once**:

```
SELECT <the block's projection, unchanged>
FROM source [FINAL]
<every other enrichment join, exactly as "SQL translation" renders it>
LEFT JOIN heavy_enrichment [FINAL]
  ON source.grain_key = heavy_enrichment.grain_key
 AND (heavy_enrichment.grain_key, heavy_enrichment.partition_col) IN (K)
WHERE <the block's whole filter>
  AND ((source.grain_key, source.partition_col) IN (K)
       OR source.grain_key IS NULL OR source.partition_col IS NULL)
ORDER BY <the block's sort> LIMIT <limit> OFFSET <offset>
```

where `K` is `SELECT "__adas_lm_key", "__adas_lm_part" FROM (<page block>) AS "__adas_lm"` and the **page block**
is the same block with every `select` entry that references a heavy column removed (an entry a `sort` item names
is kept, under its resolved output name), projecting the source grain key as `__adas_lm_key` and the source
partition column as `__adas_lm_part`, ordered by the block's `sort`, with `LIMIT <offset + limit>` and no
`OFFSET`. Without an aligned partition column, `K` and both `IN` tuples carry the grain key alone. The two
references to `K` SHALL both stand in the outer statement's scope — the outer `WHERE` and the heavy join's `ON` —
and never inside a derived table, because ClickHouse builds one set per distinct subquery within a scope and
re-evaluates a subquery referenced from a nested scope: two evaluations of a page whose order has ties, or that
reads rows ingested in between, can choose different rows, and a row whose page key is missing from the heavy
join's set would read its heavy column as unmatched with no error. A materialized CTE SHALL NOT be used for this
(ClickHouse gates it behind a setting that is off by default).

The late-materialized statement SHALL return exactly the rows, column names, column order, values and
NULL/default semantics the unshaped statement returns, up to the order of rows that tie on the sort, which neither
statement fixes: the page block selects the top `offset + limit` rows of the same filtered relation, the outer
`WHERE` keeps those rows (and any other row sharing one of their keys, which ranks after them), and the outer
`ORDER BY`/`LIMIT`/`OFFSET` then cuts the same page; the heavy join's extra condition is true for every kept row,
so its match is the grain-key match the unshaped join gives it. A row whose grain key or partition column is NULL
is kept by the `IS NULL` arms and reads its heavy columns as unmatched, as it does unshaped. The `include_total`
count statement SHALL NOT be late-materialized. On the single-entity read path a count that is read final SHALL
join only the enrichments its `filter` references: read final, every grain join is 0..1, so a join the filter
does not read cannot change the count, and joining it would build the columns the page projects — a heavy body —
over the whole window. A count that is not read final SHALL keep every join its page has, because an unmerged
enrichment version fans a row out in the page and must fan it out in the count too.

#### Scenario: The last page of requests with their bodies reads bodies for the page only

- **WHEN** a row-mode query selects `trace_id` and `dial_usage_log_payload.request_body`, filters
  `request_time >= T`, sorts by `request_time` descending and pages `limit 10`
- **THEN** the rendered SQL joins `dial_usage_log_payload` (under `FINAL` when read final) with
  `("dial_usage_log_payload"."event_id", "dial_usage_log_payload"."request_time") IN (K)` in its `ON`
- **AND** the outer `WHERE` carries the whole filter and `("dial_usage_log"."event_id", "dial_usage_log"."request_time")
  IN (K)` with the two `IS NULL` arms, where `K` is the same subquery text
- **AND** `K`'s page block projects no heavy column, carries the filter and the sort, and has `LIMIT 10`

#### Scenario: An offset page fetches keys for the rows up to its end

- **WHEN** the same query pages `limit 10, offset 20`
- **THEN** the page block has `LIMIT 30` and no `OFFSET`, and the outer statement keeps `LIMIT 10 OFFSET 20`

#### Scenario: The total of a body page does not read bodies

- **WHEN** the same query, read final, also asks for `include_total`
- **THEN** the count statement joins no payload table, and its total equals the unshaped count's

#### Scenario: Late materialization returns the unshaped rows

- **GIVEN** source rows with no payload row, a payload key with several unmerged versions, rows tying on the
  sort key, and a body larger than 1 MB
- **WHEN** a late-materialized query and the same query rendered unshaped run against ClickHouse, with and
  without an `offset`
- **THEN** both return identical column names, types and values, row for row

#### Scenario: The page keys are evaluated once

- **WHEN** the late-materialized shape runs with a page block ordered by a per-row random value
- **THEN** every row the outer `WHERE` keeps finds its heavy row, on every run

#### Scenario: A heavy column outside the projection keeps today's join

- **WHEN** a row-mode query filters, sorts or groups on a heavy enrichment column, or sorts by the output name of
  a `select` entry that reads one
- **THEN** the block is not late-materialized

#### Scenario: Shapes whose page cannot be chosen first keep today's join

- **WHEN** a row-mode query that projects a heavy enrichment column is `distinct`, or projects a window
  expression or `array_join`, or is in aggregate mode
- **THEN** the block is not late-materialized

#### Scenario: A light column of the heavy enrichment may appear anywhere

- **WHEN** a row-mode query projects `dial_usage_log_payload.request_body` and filters on
  `dial_usage_log_payload.request_tags`
- **THEN** the block is late-materialized, the page block joins the payload for `request_tags` only, and the
  outer statement reads both columns from the page-restricted join

#### Scenario: Composite blocks keep today's join

- **WHEN** a composite SQL statement's block projects a heavy enrichment column under a `LIMIT`
- **THEN** the block renders as "SQL translation" describes, with no page-key subquery

### Requirement: Null-rejecting enrichment-only conjuncts narrow the enrichment's derived table

On the single-entity read path, in the row statement and in the `include_total` count statement, a top-level
conjunct of the filter (flattened as "SQL translation" flattens it for keying) SHALL be **copied** into an
enrichment's derived-table `WHERE`, beside its grain-key and partition restrictions, when it names exactly one
field, that field belongs to that enrichment and is of type `string`, `integer`, `long` or `decimal`, and the
conjunct is one of:

- `co` or `ico` with a non-empty string literal;
- `eq` against a non-null literal that is not the field type's default (`''` for `string`, `0` for a number);
- `in` whose every literal is non-null and not the field type's default;
- `eq` of `starts_with(field, literal)` or `contains(field, literal)` against `true`, with a non-empty literal.

The conjunct SHALL also stay in the statement's own `WHERE`. An enrichment that receives a copied conjunct but has
no source-only conjunct to key it SHALL be joined as a derived table restricted by its copied conjuncts alone.
When the conjunct's field is `heavy` and the statement reads it nowhere but in copied conjuncts and in calls
"Scalar calls over a heavy text column are computed inside the enrichment's derived table" pushes, the derived
table SHALL NOT project the field: it SHALL compute each copied conjunct over it as a column aliased
`__adas_c<n>`, and the statement's own `WHERE` SHALL read that column in place of the conjunct. On a matched row
the column holds the conjunct's value, which the copy has made true; on an unmatched row it reads `0` or NULL, both
rejected, which is what the conjunct gives an unmatched row — so the build holds a flag per matching row instead
of the column the conjunct was decided on. No
other conjunct SHALL be copied — in particular not `IS NULL`, `IS NOT NULL`, `ne`, `nc`, `inc`, `lt`, `le`, `gt`,
`ge`, a `not`, an `or`, a `coalesce`, a comparison over an `object`, `map`, `array`, `enum`, `boolean`, `uuid`,
`date` or `timestamp` field, or a conjunct naming more than one field.

Copying SHALL NOT change any result. Every allow-listed conjunct is false (or NULL) on the value an unmatched
enrichment row reads — the column's type default under the single-entity statements' `join_use_nulls = 0`, or
NULL for a `Nullable` column — so a source row whose enrichment row the copy removes is rejected by the outer
`WHERE` either way, and a kept row's enrichment row satisfies the copy because it satisfies the outer conjunct.
`IS NOT NULL` is excluded because it is true on a non-nullable column's default. A copied conjunct inside a
`FINAL` derived table SHALL filter the newest version of each key, not an older one; ClickHouse applies a
non-sorting-key `WHERE` after the merge, and the read path sends no setting that moves it before.

#### Scenario: A body search narrows the payload build

- **WHEN** a query counts rows with `request_time >= T AND dial_usage_log_payload.request_body ico 'error'`
- **THEN** the payload derived table carries the `ILIKE '%error%'` condition beside its `event_id IN` and
  `request_time IN` restrictions
- **AND** the derived table projects `(request_body ILIKE '%error%') AS "__adas_c0"` and not `request_body`, and
  the outer `WHERE` reads `"dial_usage_log_payload"."__adas_c0"` in place of the conjunct

#### Scenario: A body search beside a raw read of the body keeps the body joined

- **WHEN** the same conjunct stands in a query that also reads `dial_usage_log_payload.request_body` raw
- **THEN** the derived table carries the copied condition and projects `request_body`, and the outer `WHERE`
  carries the conjunct itself

#### Scenario: Conjuncts that are true on an unmatched row stay outside

- **WHEN** a query's filter carries, beside `request_time >= T`, a conjunct on an enrichment column that is
  `IS NULL`, `IS NOT NULL`, `ne`, `inc`, `lt` over `length(...)`, a `not`, an `or`, or `eq` against `''`
- **THEN** that conjunct appears only in the outer `WHERE`, not in the derived table

#### Scenario: An unkeyed enrichment is narrowed by its own conjunct

- **WHEN** a query's filter is only `dial_usage_log_payload.request_body ico 'error'`
- **THEN** the payload is joined as a derived table restricted by that condition alone, with no key sub-query

#### Scenario: The newest version decides

- **GIVEN** a payload key whose older version matches a copied conjunct and whose newest version does not
- **WHEN** a final-read query with that conjunct runs
- **THEN** the source row is not returned, exactly as the unshaped statement returns

#### Scenario: Copying returns the unshaped rows

- **GIVEN** source rows with no payload row, payload rows that match and fail the conjunct, and several
  versions of one key
- **WHEN** a query with a copied conjunct and the same query rendered unshaped run against ClickHouse
- **THEN** both return identical rows and identical `include_total` counts

### Requirement: Scalar calls over a heavy text column are computed inside the enrichment's derived table

On the single-entity read path, in the row and count statements, a `heavy` enrichment field of type `string` that
the statement references **only** as the first argument of allow-listed calls SHALL NOT be projected by its
enrichment's derived table. The derived table SHALL instead project each distinct allow-listed call over it once,
aliased `__adas_c<n>`, and every occurrence of that call in the statement SHALL read that column. The allow-list
is: `length`; `json_extract_string`, `json_extract_long`, `json_extract_double`, `json_extract_bool`,
`json_extract_array`, `json_extract_string_ci`; and `starts_with` and `contains` with a non-empty literal. Every
argument after the first SHALL be a literal. A field referenced anywhere else — raw, inside another function, inside
a lambda, or in a call outside the list — SHALL be projected whole, and its calls SHALL be rendered over it as
today. An enrichment carrying a pushed call but no key or copied conjunct SHALL be joined as a derived table with
no `WHERE`. A heavy field a late-materialized block fetches for its page is not pushed.

The calls are allow-listed because their value on an unmatched row is already the default of their result type,
which is what a column computed inside the derived table reads on an unmatched row: over a `Nullable` column each
returns NULL (or, for the two array/ci extractions, which read an absent document as `''`, the empty array and
the empty string), and over a non-nullable column each returns `0`, `''`, `false` or `[]` for `''`. So a pushed
call SHALL return, for matched and unmatched rows alike, exactly what the call over the joined column returns
unshaped. Output names and validation are decided on the call as written, never on the pushed column.

#### Scenario: An average body length joins a number, not a body

- **WHEN** an aggregate query selects `avg(length(dial_usage_log_payload.request_body))` grouped by `deployment`
  over a `request_time` window
- **THEN** the payload derived table projects `length(request_body) AS "__adas_c0"` and not `request_body`, and
  the aggregate reads `"dial_usage_log_payload"."__adas_c0"` under the output name `avg`

#### Scenario: A raw reference keeps the whole column

- **WHEN** the same query also selects `dial_usage_log_payload.request_body` itself, or wraps it in a call outside
  the allow-list
- **THEN** the derived table projects `request_body` and the call renders over it

#### Scenario: Pushed calls return the unshaped values for unmatched rows

- **GIVEN** source rows with no payload row and payload rows whose body is NULL, `''` and a JSON document
- **WHEN** each allow-listed call is evaluated through a pushed column and through the unshaped join
- **THEN** both return identical values, NULLs included, for every row

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
query's **whole** `filter` SHALL still be applied, unchanged, in the statement's own `WHERE`; an
enrichment conjunct SHALL NOT be moved out of it, and SHALL be copied into a derived table only as
"Null-rejecting enrichment-only conjuncts narrow the enrichment's derived table" allows. A query with no
`filter`, or whose `filter` has no source-only top-level conjunct (an enrichment-only filter, or an `or`
mixing source and enrichment columns), SHALL join the enrichment table unkeyed, unless that requirement
or "Scalar calls over a heavy text column are computed inside the enrichment's derived table" emits it as
a derived table. On the single-entity read path a row-mode page whose heavy enrichment columns are
projection-only SHALL instead join those enrichments as "Projection-only heavy enrichment columns are
fetched for the returned page only" states. The same rule SHALL apply to
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

### Requirement: Query execution runs under a resource budget

The system SHALL execute every structured query under an explicit, configured **resource budget** — a
wall-clock ceiling and a memory ceiling — applied as per-query ClickHouse server settings on the
executed statement, and SHALL refuse a query that exceeds either ceiling rather than letting it run
unbounded.

The budget is required because a query's cost is not bounded by anything the caller supplies. `limit`
bounds the rows returned, not the rows read: an entity's `heavy` columns are unbounded in size per
row, and a filter on a column that is not a prefix of the source's ordering key prunes nothing, so a
single small-result query can read the whole table's payload columns. Without a ceiling that read
continues after the caller's HTTP client has given up, so the cost is paid in full and the caller
learns nothing about why.

The budget SHALL bound the query's **cost**, never its **result**. The system SHALL NOT sample, narrow,
or silently truncate a query's input or output to stay inside the budget: a partial answer returned as
though it were complete is worse than a refusal, because the caller cannot tell the difference. A
query that cannot be completed within its budget SHALL be refused with an error naming the ceiling it
exceeded.

The budget SHALL apply to the query surface only — the paths that serve a caller-supplied query. The
enrichment scan, group-fetch, materialize, and aggregate-refresh paths share the executor but are
bounded by their own windows, page sizes, and limits; the dry-run preview has its own budget. A ceiling
imposed on those paths would make a legitimate large window fail as though it were malformed.

The wall-clock ceiling SHALL bound the **caller's request**, not each statement it runs. A query that
also asks for a total count runs a second statement, and that statement SHALL inherit what remains of the
ceiling rather than being granted a fresh one: a request able to occupy twice the configured ceiling puts
the caller past its own read timeout, which is the failure the budget exists to remove.

The ceilings SHALL be deployment-configurable, so an installation whose ordinary queries are heavier
than the defaults can raise them without a code change. A configured value that would leave a ceiling
bounding nothing — a spill threshold at or above the memory ceiling, or a wall-clock ceiling below the
granularity the store enforces — SHALL be refused at startup rather than accepted and silently ignored.

#### Scenario: An ordinary query is unaffected

- **WHEN** a query completes inside the configured ceilings
- **THEN** it returns its rows exactly as before, with no truncation, sampling, or added metadata

#### Scenario: A query exceeding the wall-clock ceiling is refused

- **WHEN** an executed query exceeds the configured execution-time ceiling
- **THEN** the response is HTTP 422 with `error` `query_budget_exceeded`, and the message names the
  ceiling that was exceeded and its configured value

#### Scenario: A query exceeding the memory ceiling is refused

- **WHEN** an executed query exceeds the configured memory ceiling
- **THEN** the response is HTTP 422 with `error` `query_budget_exceeded`, and the message names the
  ceiling that was exceeded and its configured value

#### Scenario: An unbounded heavy-column read is refused, not dropped

- **WHEN** a query reads a `heavy` column that its returned page does not bound — in its `filter`, inside an
  aggregate, or in a block that is not eligible for late materialization (see "Projection-only heavy
  enrichment columns are fetched for the returned page only") — with no predicate that prunes the source's
  partitions, so the read exceeds a ceiling
- **THEN** the caller receives the `query_budget_exceeded` error rather than an empty or transport-level
  failure, and the statement is refused by ClickHouse rather than continuing to run

#### Scenario: A projection-only heavy enrichment column is bounded by the page, not the window

- **WHEN** a row-mode query projects a `heavy` enrichment column only in its `select`, under a `limit`, with no
  predicate that prunes the source's partitions
- **THEN** the heavy column is read for the returned page's rows only, so its cost no longer grows with the
  window; the source scan that chooses the page still runs under the same budget, and a source scan that
  exceeds a ceiling is still refused with `query_budget_exceeded`

#### Scenario: A total count shares the request's ceiling

- **WHEN** a row-mode query requests a total count, so a second statement runs for the same request
- **THEN** that statement runs under what remains of the wall-clock ceiling, and the caller's total wait
  stays within the single configured ceiling

#### Scenario: A refusal is deterministic, not a transient failure

- **WHEN** a query is refused for exceeding its budget
- **THEN** the status is 422 rather than 503, because re-running the identical query is refused
  identically and a retrying client would loop

#### Scenario: An unrelated ClickHouse failure is not reported as a budget refusal

- **WHEN** the executed statement fails for a reason other than the budget's ceilings
- **THEN** the existing error mapping for that failure applies unchanged, and `query_budget_exceeded`
  is not returned

#### Scenario: The scan and refresh paths keep their own bounds

- **WHEN** an enrichment scan, group fetch, materialize, or aggregate refresh runs a statement through
  the same executor
- **THEN** it is not subject to the query budget, and its own window, page size, and limits govern it

#### Scenario: A node-wide exhaustion is not reported as a budget refusal

- **WHEN** an executed query fails because ClickHouse itself ran out of memory rather than because the
  query exceeded its own ceiling
- **THEN** the response is HTTP 503 `clickhouse_unavailable` rather than 422 `query_budget_exceeded`
- **AND** over Flight the failure is `UNAVAILABLE` rather than `INVALID_ARGUMENT`
