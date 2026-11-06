## RENAMED Requirements

- FROM: `### Requirement: Projection-only heavy enrichment columns are fetched for the returned page only`
- TO: `### Requirement: Projection-only enrichment columns are fetched for the returned page only`

## MODIFIED Requirements

### Requirement: SQL translation

The system SHALL translate a validated structured query into ClickHouse SQL by building a jOOQ query
model on the connection-less `SQLDialect.CLICKHOUSE` render context and rendering it to a SQL string
with literals inlined (the ClickHouse HTTP client executes a raw SQL string). Every inlined string
literal SHALL escape the backslash as well as the quote, because ClickHouse reads a backslash inside a
literal as an escape: a value matches exactly the characters the caller sent, and no value can close its
literal early and run the rest of itself as SQL. Field bindings SHALL be
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
enrichment table — except where "A partition-only key on an aligned enrichment is transferred, not keyed"
replaces the key sets with the transferred partition bounds, for a join to a partition-aligned enrichment
whose every key predicate is a bound on the source's partition column. The keying SHALL be statement-wide: a conjunct naming one enrichment's column SHALL
NOT prevent the source-only conjuncts beside it from keying that enrichment's join or any other. The
query's **whole** `filter` SHALL still be applied, unchanged, in the statement's own `WHERE`; an
enrichment conjunct SHALL NOT be moved out of it, and SHALL be copied into a derived table only as
"Null-rejecting enrichment-only conjuncts narrow the enrichment's derived table" allows. A query with no
`filter`, or whose `filter` has no source-only top-level conjunct (an enrichment-only filter, or an `or`
mixing source and enrichment columns), SHALL join the enrichment table unkeyed, unless that requirement
or "Scalar calls over a heavy text column are computed inside the enrichment's derived table" emits it as
a derived table. On the single-entity read path a late-materialized row-mode page SHALL instead join its
late enrichments for the page's keys, as "Projection-only enrichment columns are fetched for the returned page
only" defines them and states. The same rule SHALL apply to
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
  (for example `trace_id = 'x'`), and the block is not late-materialized (see "Projection-only enrichment columns
  are fetched for the returned page only")
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
  projects a column of `<enrichment_b>`, and neither enrichment is partition-aligned with the source on
  `request_time`
- **THEN** both enrichments are joined as derived tables keyed by `request_time >= T` alone

#### Scenario: A filter naming an enrichment column joins the enrichment unkeyed

- **WHEN** a validated query's `filter` references enrichment columns only (it has no source-only
  top-level conjunct), or the query has no `filter`, and the block is not late-materialized
- **THEN** the rendered SQL `LEFT JOIN`s the enrichment table itself, with no key sub-query

#### Scenario: A mixed or is left unkeyed

- **WHEN** a validated query's `filter` is `trace_id = 'x' OR <enrichment>.<column> = 'v'`
- **THEN** the filter has no source-only top-level conjunct, and the rendered SQL `LEFT JOIN`s the
  enrichment table itself, with no key sub-query

#### Scenario: An enrichment IS NULL conjunct beside a time window is keyed and returns the same rows

- **GIVEN** source rows inside and outside a time window, some inside it with an enrichment row and some
  without one
- **WHEN** a validated query's `filter` is `request_time >= T AND <enrichment>.<column> IS NULL`
- **THEN** the enrichment join is keyed by `request_time >= T` (or, when the enrichment is partition-aligned
  with the source on `request_time`, bounded by that transferred window instead)
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

#### Scenario: A backslash in a string value cannot escape its literal

- **WHEN** a filter compares a string field with the value `x\' OR 1=1 --`, over the DSL or ad-hoc SQL
- **THEN** the value renders as one escaped literal and the query returns only rows whose field equals
  those exact characters, here none

#### Scenario: A backslash in a string value matches literally

- **WHEN** a filter compares a string field with the four-character value `a\nb`
- **THEN** it matches a row holding a backslash followed by `n`, never a row holding a newline

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
  aggregate, or in a block that is not eligible for late materialization (see "Projection-only
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

### Requirement: A keyed grain join is bounded by an aligned partition column

A keyed enrichment join SHALL restrict the enrichment side by the source's partition values, in addition to
its grain keys, whenever the catalog declares the enrichment's partition column to be aligned with its
source's. The restriction SHALL be
`enrichment.partition_column IN (SELECT source.partition_column FROM source WHERE <source predicates>)`,
emitted inside the derived table's `WHERE` alongside the grain-key restriction and drawn from the same
predicates — except where "A partition-only key on an aligned enrichment is transferred, not keyed" replaces
both sets with the transferred bounds, because every source predicate keying the join is a bound on the
partition column itself.

Alignment SHALL be declared, never inferred. Both definitions SHALL declare a partition column and the two
SHALL be the **same column name**; the shared name is the whole of the claim, namely that for a given grain
key both tables hold the same value in that column. A source that declares no partitioning, an enrichment
that declares none, and a pair whose columns are named differently SHALL all be emitted exactly as before,
keyed on the grain key alone. Partition **granularity** SHALL NOT be compared: the restriction is a set of
column values, so it prunes correctly whatever expression either table partitions that column by.

The restriction SHALL be placed in the derived table rather than on the join condition. Both prune the
scan, but only the inner form also narrows what the derived table projects, and projecting a heavy column
across every part is the cost being removed.

**Where a statement declares the `object` paths it addresses, the derived table SHALL project those paths
and not the whole `object` column.** Where every expression in the statement that references an `object`
column addresses a *compile-time literal* path within it, the derived table SHALL project those paths as individual
subcolumns, each aliased to its own dotted path (`request_tags.baggage`) as a single quoted identifier, and
the outer statement SHALL address that alias. Where any reference addresses no statically known path, the
whole column SHALL be projected as before.

**A statement SHALL declare which paths it addresses, and one that declares nothing SHALL project whole
columns.** The paths are derived from the statement's own clauses and handed to the join, rather than
discovered by the join from the statement, because the join is emitted before those clauses are rendered
and is built from referenced *names*. A caller that renders a clause the declaration did not account for
would address a column its own join no longer projects, so the safe direction is explicit: no declaration
means no pruning, and the statement renders exactly as it did before this requirement existed. This is what
lets the rule be adopted per call site rather than everywhere at once.

The statements that declare their usage SHALL be the Class-1 materialize probe and its write, and the
query surface: the single-entity read statement (including the page block and the outer statement of a
late-materialized read), its `include_total` count statement, and every block of a composite SQL statement.
A late-materialized read's page-keyed join is the enrichment table itself, so it has no derived table to prune; how
it reads an addressed path is stated in "Projection-only enrichment columns are fetched for the returned page only".
The enrichment scan, the group-fetch projection and the aggregate refresh and its preview SHALL declare none
and SHALL render exactly as they did before this requirement. Naming them is what makes the previous paragraph
falsifiable: "adopted per call site" with no list is a claim no reader can check.

A query-surface statement SHALL derive its usage from every clause it renders against the joined enrichments:
its `select` expressions, `filter`, `having`, `sort` keys and `group_by` keys, and the enrichment conjuncts and
scalar calls that read shaping pushes into a derived table (their copies in the outer statement address the
same paths). A `sort` or `group_by` key naming an `object` column, and an `object` column projected bare, SHALL
make that column whole. Every expression the outer statement renders SHALL address a pruned path through its
alias, and an expression rendered *inside* the derived table (a pushed conjunct's `WHERE` copy, a pushed call)
SHALL keep addressing the enrichment's own column, which is in scope there. The count statement SHALL derive
its usage from the clauses it renders, which is its filter.

On the query surface this is what keeps a keyed join cheap for a path read: `json_extract_string(jwt_claims, 'email')`
grouped over a month of history goes from exceeding the memory budget to completing in about a second, and
the same path as a filter roughly halves its time and memory, returning the same rows.

A path key containing a dot SHALL fall back to the whole column. The alias joins the column name and the
path with dots into one identifier, so the single key `a.b` and the two-segment path `a`,`b` would collide
on `request_tags.a.b`; one derived table projecting both under one name resolves the outer reference to
whichever it chooses, and one of the two reads silently returns the other's value. The fallback SHALL be per column rather than per statement: one
unprunable reference costs its own column its pruning and no other's. The references that SHALL fall back
are `json_extract_string_ci` and any use of the column as a value in its own right —
a bare projection, a comparison, or an argument to a function taking the whole document.
`json_extract_array` SHALL NOT fall back: it addresses a compile-time literal path like the four scalar
extractors, so a statement whose only reference to an `object` column is an array extraction SHALL prune
that column's projection.

Without this, keying a join makes an `object` column *more* expensive to read rather than less. ADAS
renders a single-path read of an `object` field as native subcolumn access, which reads an order of
magnitude less data than the serialized form; a derived table projecting the whole column has materialized every path before that
expression runs, so the optimization survives only where the join is left unkeyed — the opposite of the
intent. The same single-path filter returns instantly through an unkeyed join
and cannot complete even a window of a few minutes through a keyed one.

An empty source-predicate set SHALL emit no restriction of either kind. A statement with no source
restriction has no value set to offer, and bounding an enrichment by an empty set would restrict it to
nothing.

This requirement exists because restricting a join to a grain key bounds what it *matches* without bounding
what it *reads*. Where an enrichment's grain key is uncorrelated with its partitioning — a hashed
`event_id` over daily partitions — a lookup for one key value lands a candidate granule in every part,
because the row genuinely might be in any of them, and the grain-key set cannot exclude one. That is on
the order of a GiB per heavy column to return a single row, on every path that reads
a body, keyed or unkeyed. The value that excludes the other partitions is the source's own partition
column, and a statement that identifies a row usually already carries it.

The restriction is only as sound as the declaration behind it, which is why nothing derives it from two
columns happening to share a name at query time. A false claim does not fail: it restricts the enrichment
to partitions that cannot hold the row, and the outer join then yields the column's type default —
indistinguishable from a row that legitimately has no enrichment.

#### Scenario: An aligned enrichment is bounded by the source's partition values

- **GIVEN** a source partitioned on `day` and an enrichment declaring `day` as its own partition column
- **AND** a query filtering on a source column alone and selecting and sorting by an enrichment column, so the block
  is not late-materialized (see "Projection-only enrichment columns are fetched for the returned page only")
- **WHEN** the statement is rendered
- **THEN** the derived table carries
  `enrichment.day IN (SELECT source.day FROM source WHERE <filter>)`
- **AND** it also carries the grain-key restriction over the same predicates
- **AND** the join condition remains the single grain-key equality

#### Scenario: An enrichment that only co-stores the column name is not aligned

- **GIVEN** a source partitioned on `day` and an enrichment that holds a column named `day` but declares no
  partition column
- **WHEN** the statement is rendered
- **THEN** the derived table carries the grain-key restriction only
- **AND** no predicate names the enrichment's `day`

#### Scenario: Differently-named partition columns are not aligned

- **GIVEN** a source partitioned on `day` and an enrichment partitioned on `created_on`
- **WHEN** the statement is rendered
- **THEN** the join is keyed on the grain key alone

#### Scenario: A single-path reference prunes the derived table's projection

- **GIVEN** a statement that declares its object-path usage
- **AND** an enrichment holding an `object` column and a statement whose only reference to it is
  `json_extract_string(<column>, 'k')`
- **WHEN** the keyed join is rendered
- **THEN** the derived table projects `<column>.k` aliased as the single identifier `<column>.k`
- **AND** the whole `object` column does not appear in the derived table's projection
- **AND** the outer expression addresses that alias

#### Scenario: A case-insensitive reference falls back to the whole column

- **GIVEN** a statement that declares its object-path usage
- **AND** the same enrichment and a statement referencing it with `json_extract_string_ci`
- **WHEN** the keyed join is rendered
- **THEN** the derived table projects the whole `object` column, exactly as before

#### Scenario: An array extraction prunes the derived table's projection

- **GIVEN** a statement that declares its object-path usage
- **AND** an enrichment holding an `object` column and a statement referencing it with
  `json_extract_array(<column>, 'roles')` and `json_extract_string(<column>, 'sub')`
- **WHEN** the keyed join is rendered
- **THEN** the derived table projects `<column>.roles` and `<column>.sub` as aliased subcolumns
- **AND** the whole `object` column does not appear in the derived table's projection
- **AND** neither reference costs the other its pruning

#### Scenario: One unprunable reference does not cost another column its pruning

- **GIVEN** a statement that declares its object-path usage
- **AND** a statement referencing one `object` column by a literal path and a second `object` column
  through `json_extract_string_ci`
- **WHEN** the keyed join is rendered
- **THEN** the first column is projected as its addressed paths and the second as the whole column

#### Scenario: A statement that declares no object-path usage projects whole columns

- **GIVEN** a keyed join for an enrichment holding an `object` column
- **AND** a statement that addresses a literal path within it but declares no object-path usage
- **WHEN** the statement is rendered
- **THEN** the derived table projects the whole `object` column
- **AND** the outer statement addresses it as nested subcolumn segments, exactly as before this requirement

#### Scenario: A query with no source predicates is joined unkeyed

- **GIVEN** an aligned source and enrichment
- **AND** a query with no filter that selects and sorts by an enrichment column, so the block is not
  late-materialized
- **WHEN** the statement is rendered
- **THEN** the enrichment is joined as a bare table with no restriction of either kind

#### Scenario: A query-surface path read prunes its keyed derived table

- **GIVEN** a query over a source with a source-only filter that groups by
  `json_extract_string(<object column>, 'email')` of an enrichment
- **WHEN** the statement is rendered
- **THEN** the enrichment's derived table projects `<object column>.email` aliased as that single identifier and
  not the whole `object` column
- **AND** the outer projection and `GROUP BY` address that alias
- **AND** the result equals the result of the same statement rendered with whole columns

#### Scenario: A column referenced whole and by path stays whole on the query surface

- **GIVEN** a query that filters on `json_extract_string(<object column>, 'email')` and also projects the bare
  `<object column>`
- **WHEN** the statement is rendered
- **THEN** the derived table projects the whole `object` column and the filter addresses it as nested subcolumn
  segments

#### Scenario: A pushed enrichment conjunct keeps its inner copy on the real column

- **GIVEN** a query whose filter has a pushed enrichment conjunct beside a literal-path read of an `object`
  column of the same enrichment
- **WHEN** the statement is rendered
- **THEN** the derived table's `WHERE` copy of the conjunct addresses the enrichment's own columns
- **AND** every reference in the outer statement to the pruned path addresses its alias

#### Scenario: The count statement prunes as its filter reads

- **GIVEN** an offset-paged query with `include_total` whose filter reads `json_extract_string(<object column>,
  'email')` through a keyed join
- **WHEN** the count statement is rendered
- **THEN** its derived table projects `<object column>.email` and not the whole column

#### Scenario: The aggregate refresh still declares no usage

- **GIVEN** an aggregate pipeline whose block reads `json_extract_string(<object column>, 'email')` through a
  restricted grain join
- **WHEN** its refresh statement is compiled
- **THEN** the derived table projects the whole `object` column, exactly as before this change

### Requirement: A partition-only key on an aligned enrichment is transferred, not keyed

On the query surface, a keyed join to a partition-aligned enrichment SHALL be restricted by the source's
partition-column bounds themselves, rendered on the enrichment's alignment column, **in place of** both key sets
(`grain_key IN (…)` and `partition_column IN (…)`), when every source predicate that keys it is such a bound. A
bound is a top-level source-only comparison of the statement's filter that reads the source's partition column
and nothing else, and holds no subquery.

The statements this applies to are the single-entity read statement (including the page block of a
late-materialized read), its `include_total` count statement, and the entity blocks of a composite SQL statement.
The enrichment scan, the group fetch, the Class-1 materialize and the aggregate refresh and its preview SHALL
render exactly as they did before this requirement.

The join SHALL keep every other part of its derived table: the projection, the projection pruning of `object`
paths, the pushed enrichment conjuncts and computed columns, and the `FINAL` modifier. Only the two key sets are
replaced. The join condition SHALL remain the aligned pair's tuple equality.

The key sets SHALL be kept, exactly as before, when any source predicate that keys the join is not a bound. That
covers:
- an equality or range on any other source column;
- a bound on the partition column that holds a subquery, or that reads another column as well;
- a disjunction;
- a key conjunct a composite join's reduction hands to the block.

A point lookup such as `trace_id = X` therefore keeps the grain-key set that confines its read to one granule. A
join with no aligned pair and an unkeyed join SHALL render exactly as before, and a late-materialized join SHALL render
as "Projection-only enrichment columns are fetched for the returned page only" states.

The transfer is sound for the same reason the partition restriction is: the alignment invariant gives each
admitted source row an enrichment row with the same partition value, so the bound holds for that enrichment row
whenever it holds for the source row, and the `LEFT JOIN` result is unchanged. The derived table then holds every
enrichment row of the bounded partitions. For a row-grain aligned enrichment that is no more than one row per
source row the window admits.

The key sets cost more than they restrict for a window-only read. Each holds every value the window selects (millions
of values over a month of history) and is built in full before the scan. The grain-key set prunes nothing
on a table sorted by a random key, and the partition set restates a range ClickHouse can apply directly.

Removing both builds is what makes a window-only read cheap. With a pushed conjunct narrowing the enrichment (an
equality on an enrichment column, or a path filter on an `object` column) it uses about an order of magnitude less
time and memory. A read that joins per-user totals back over a long window fits a memory budget that the two sets
exceeded. Full-window aggregates run several times faster, with peak memory unchanged or lower. Point lookups by
`trace_id`, `event_id` or `chat_id` are unchanged, because they are not window-only.

#### Scenario: A window-only read transfers its window onto the aligned enrichment

- **GIVEN** a source partitioned on `day` and an enrichment aligned with it on `day`
- **AND** a query whose filter is `day >= T` and which selects and sorts by an enrichment column, so the block is
  not late-materialized (see "Projection-only enrichment columns are fetched for the returned page only")
- **WHEN** the statement is rendered
- **THEN** the enrichment's derived table carries `enrichment.day >= T`
- **AND** it carries neither `enrichment.grain_key IN (SELECT …)` nor `enrichment.day IN (SELECT …)`
- **AND** the join condition still compares the grain key and `day`

#### Scenario: A window-only page projecting the aligned enrichment only is page-keyed instead

- **GIVEN** the same aligned pair and a row-mode query whose filter is `day >= T`, which selects an enrichment column
  and neither filters nor sorts on one
- **WHEN** the statement is rendered
- **THEN** the block is late-materialized: the enrichment is joined as its own table for the page's keys, with
  neither a transferred window nor a key set, and the page block does not join it

#### Scenario: A pushed enrichment conjunct stays beside the transferred window

- **GIVEN** the same aligned pair and a filter `day >= T AND enrichment_column = X`
- **WHEN** the statement is rendered
- **THEN** the derived table carries `enrichment.day >= T` and `enrichment_column = X`
- **AND** no key set

#### Scenario: Any other source predicate keeps both key sets

- **GIVEN** the same aligned pair, a filter `day >= T AND deployment = 'd'`, and a query that selects and sorts by an
  enrichment column
- **WHEN** the statement is rendered
- **THEN** the derived table carries both key sets over both conjuncts, exactly as before

#### Scenario: A join without an aligned pair is keyed as before

- **GIVEN** an enrichment keyed on another source column with no partition pair, and a window-only filter
- **WHEN** the statement is rendered
- **THEN** its derived table carries its grain-key set over the window, exactly as before

#### Scenario: The count statement transfers as its page does

- **GIVEN** an offset-paged query with `include_total` whose filter is `day >= T AND enrichment_column = X`
- **WHEN** the count statement is rendered
- **THEN** the enrichment's derived table carries `enrichment.day >= T` and no key set

#### Scenario: A composite reduction keeps the key sets

- **GIVEN** a composite SQL join whose entity operand carries a window and receives a one-hop reduction of its
  join key
- **WHEN** the operand's block is rendered
- **THEN** its aligned enrichment joins carry both key sets

#### Scenario: The aggregate refresh and its preview keep the key sets

- **GIVEN** an SQL aggregate declaration whose `WHERE` is only a bound on the source's partition column and whose
  block reads an aligned enrichment
- **WHEN** its refresh statement and its preview are rendered
- **THEN** that enrichment's derived table carries both key sets in each, exactly as before

#### Scenario: The transferred read returns the keyed read's rows

- **GIVEN** an aligned source and enrichment holding rows inside and outside a window
- **WHEN** the same window-only query, sorted on the enrichment column so it is not late-materialized, runs rendered
  with the transfer and with both key sets
- **THEN** both return identical rows

### Requirement: A lookup on an aligned enrichment pre-selects the source's partitions

A statement whose `WHERE` carries a qualifying conjunct `C` over an aligned enrichment `E` SHALL gain
`partitionExpr(source.p) IN PS` in its own `WHERE`, alongside `C`, where
`PS = (SELECT DISTINCT partitionExpr(p) FROM E WHERE C [AND B] SETTINGS final = 0)`, `p` is the alignment column and
`B` is the statement's source-only bounds on the source's partition column, rendered on `E.p` instead. A bound is a
top-level source-only comparison conjunct that reads the source's partition column and nothing else and holds no
subquery — `request_time >= now() - INTERVAL 1 DAY`, say. `B` exists so that a statement which already bounds its time
does not pay a read of `C`'s column over all of `E`'s history to name the days it named itself; without a bound `PS`
reads `C`'s column across every partition of `E`.

`PS` SHALL NOT be repeated inside `E`'s derived table. ClickHouse evaluates an uncorrelated subquery once per scope, so
a copy there is a second read of `C`'s column, and it prunes nothing more: the analyzer carries the source's
restriction into `E`'s derived table, and into every other aligned join, through the tuple join's `source.p = E.p`
(measured on ClickHouse 26.3 and 26.8 under `do_not_merge_across_partitions_select_final = 1`, which every aligned read
carries). A join of the same statement without an aligned partition pair has no such equality; it SHALL be keyed on
`partitionExpr(source.p) IN PS` as one of its source-only key predicates, so that it reads the grain keys of the
selected partitions rather than hashing its whole table. When the statement is late-materialized, the restriction
SHALL appear in the page block only, and the outer statement is confined to the page's keys instead. Only a block with
a heavy late enrichment is late-materialized beside a pre-selection (see "Projection-only enrichment columns are
fetched for the returned page only"). A count for
`include_total` SHALL take the same restriction as the page.

On the read path a top-level `AND` conjunct SHALL qualify only when all of these hold: it references one
column of one aligned enrichment alone; that column is not heavy; it has the form `col = <literal>` or
`col IN (<literals>)`; no literal is the column type's default value (`''` for text, `0` for a number); and it is
false on an unmatched row (the null-rejecting notion of "Null-rejecting enrichment-only conjuncts narrow the
enrichment's derived table"). Any other conjunct — a range, a substring match, a function call over the column,
`NOT IN`, `!=`, a comparison with the default value, a subquery, a lookup on a heavy column — SHALL NOT qualify, and
the statement SHALL be emitted exactly as before. A heavy column is left out because the pre-selection reads the
lookup column across every partition, which for a body is the read the shaping exists to avoid; a subquery because its
values may include the default an unmatched row reads. The pipeline paths (enrichment-runtime, aggregate-pipeline)
apply the same rewrite to the lookups they build themselves, an `IN (<subquery>)` among them. `C` SHALL be rendered
over the bare column in the pre-selection, never through `toString` or another wrapper.

The rewrite SHALL be exact: the result of a rewritten statement SHALL equal the result of the unrewritten
one. An admitted row's matched enrichment row satisfies `C` and shares the row's partition value, so it satisfies `B`
on `E.p` too and the row's partition is in `PS`; reading `PS` with `final = 0` only adds stale versions and can only
widen it; keying a join on a conjunct of the statement's own `WHERE` never changes which rows it returns; and an
unmatched row fails `C` and was never admitted.

When several conjuncts qualify, each SHALL contribute its own restriction. A statement with no qualifying
conjunct SHALL be unchanged.

#### Scenario: A session lookup prunes both tables to the session's days

- **GIVEN** an aligned `usage_client_identity` of `dial_usage_log`
- **WHEN** a query filters `"usage_client_identity.client_session_id" = 's1'`, and the block is not late-materialized
- **THEN** the statement restricts `dial_usage_log` to the partitions holding an enrichment row of `s1`, its plan
  reads the enrichment's derived table in those partitions only, `PS` occurs once per scope that needs it, and the
  statement returns the same rows as without the restriction

#### Scenario: A time-bounded lookup bounds the pre-selection's own read

- **WHEN** a query filters `request_time >= <t>` and `"usage_client_identity.client_session_id" = 's1'`
- **THEN** `PS` reads `usage_client_identity` only in the partitions at or after `<t>`, and the statement returns the
  same rows as without the restriction

#### Scenario: A join without a partition pair is keyed on the pre-selection

- **GIVEN** a query that also reads an enrichment of `dial_usage_log` keyed on `chat_id`
- **WHEN** it filters `"usage_client_identity.client_session_id" = 's1'`
- **THEN** that enrichment's derived table is keyed on the `chat_id`s of the selected partitions' source rows, and the
  statement returns the same rows as without the restriction

#### Scenario: A late-materialized page and its count are pre-selected

- **WHEN** a lookup query projects a heavy payload column and is read late-materialized with `include_total`
- **THEN** the page block and the count each carry the restriction, the outer statement does not, and the rows and
  the total equal the unrewritten statement's

#### Scenario: A default-valued lookup does not qualify

- **WHEN** a query filters `"usage_client_identity.client_session_id" = ''`, or a numeric enrichment column `= 0`
- **THEN** no partition pre-selection is emitted and the statement returns the unmatched source rows as
  before

#### Scenario: A negated or ranged conjunct does not qualify

- **WHEN** a query filters an aligned enrichment column with `NOT IN`, `!=` or a range
- **THEN** no partition pre-selection is emitted

#### Scenario: An IN list lookup qualifies

- **WHEN** a query filters an aligned enrichment column with `IN ('s1', 's2')`
- **THEN** the pre-selection is emitted, and the result equals the unrewritten statement's

#### Scenario: A lookup on a heavy column does not qualify

- **WHEN** a query filters `"dial_usage_log_payload.response_body" = 'x'`
- **THEN** no partition pre-selection is emitted

#### Scenario: The lookup is rendered on the bare column

- **WHEN** a pre-selection is emitted for a lookup on a `string` column
- **THEN** `PS` does not wrap the column in `toString`

### Requirement: Projection-only enrichment columns are fetched for the returned page only

A block's **late enrichments**, which it SHALL join for its page's keys rather than for its window, are of two
kinds:

- a **heavy late enrichment** is an enrichment the block references at least one `heavy` column of;
- a **light candidate** is a partition-aligned enrichment the block references no `heavy` column of and whose every
  referenced column is referenced only by `select` entries — not by `filter`, `sort` (directly, or through the name
  of a `select` entry that references it), `group_by` or `having`. A light candidate is a **light late
  enrichment** when its source grain key and aligned source partition column are those of the block's key join.

The block's **key join** is its first heavy late enrichment in the entity's enrichment order when it has one (every
heavy late enrichment is on its keys, so which one does not change the statement). Otherwise it is the light
candidate whose source keys defer the most: the most referenced `object` columns across the light candidates on those
keys, then the most referenced columns, ties broken by the source grain key's name and then the enrichment table's.
The entity's enrichment order SHALL NOT decide it, because the catalog does not fix that order. A **deferred column** is a heavy column of a heavy late
enrichment or any column of a light late enrichment.

A block SHALL be **late-materialized** on the single-entity read path — `POST /v1/queries/execute`,
`POST /v1/queries/execute-sql`, their CSV twins and the Flight SQL endpoint — when all of these hold: it is in row
mode and not `distinct`; it renders a `LIMIT`; it has at least one late enrichment; every heavy column of every
heavy late enrichment is referenced only by `select` entries — not by `filter`, `sort` (directly, or through the
name of a `select` entry that references it), `group_by` or `having`; no `select` entry contains a window
expression or `array_join`; it calls no non-deterministic catalog function other than `now`; every heavy late
enrichment is keyable and all of them are joined on the same source grain key and the same aligned source partition
column (or on the same grain key, none of them aligned); and no `select` output is named `__adas_lm_key` or
`__adas_lm_part`. A referenced enrichment that is neither a heavy late enrichment nor a light late enrichment — an
unaligned enrichment read only through light columns, an aligned one read outside the projection, or a light
candidate on other keys than the key join — SHALL NOT disqualify the block, except as the next paragraph states: it
is joined as "SQL translation" describes, in the page block where the page block references it and in the outer
statement.

A block with no heavy late enrichment SHALL NOT be late-materialized when its filter qualifies for a partition
pre-selection (see "A lookup on an aligned enrichment pre-selects the source's partitions"), whatever else it joins.
The pre-selection restricts the page block only. In the outer statement the page's keys bound the late joins, through
the `IN (K)` in their `ON`, but every other join — the lookup's own enrichment among them, which its filter reads, so
it is never late — would keep only what ClickHouse carries into it from the outer `WHERE`, and that it carries the
page-key tuple through a join equality is not established. Such a join could lose the bound the pre-selection gives it
today — a key predicate for an unaligned join, the carried partition restriction for an aligned one — and read its
whole table. The block keeps today's statement instead, in which the pre-selection already bounds its aligned joins to
the lookup's partitions. A block with a heavy late enrichment is late-materialized as before.

Any other block SHALL render as the "SQL translation" requirement describes. Composite SQL blocks, the translation-only endpoints and every pipeline
statement SHALL NOT be late-materialized.

A late-materialized block SHALL be rendered as one statement that evaluates its page's keys **once**:

```
SELECT <the block's projection, unchanged>
FROM source [FINAL]
<every other enrichment join, exactly as "SQL translation" renders it>
LEFT JOIN late_enrichment [FINAL]
  ON source.grain_key = late_enrichment.grain_key
 AND (late_enrichment.grain_key, late_enrichment.partition_col) IN (K)
WHERE <the block's whole filter>
  AND ((source.grain_key, source.partition_col) IN (K)
       OR source.grain_key IS NULL OR source.partition_col IS NULL)
ORDER BY <the block's sort> LIMIT <limit> OFFSET <offset>
```

with one such `LEFT JOIN` per late enrichment, where `K` is
`SELECT "__adas_lm_key", "__adas_lm_part" FROM (<page block>) AS "__adas_lm"` and the **page block** is the same
block projecting, of its `select` entries, only those a `sort` item names (under their resolved output names, none
of which reads a deferred column), projecting the source grain key as `__adas_lm_key` and the source partition
column as `__adas_lm_part`, ordered by the block's `sort`, with `LIMIT <offset + limit>` and no `OFFSET`. The page
block joins only the enrichments its filter and sort read, so it joins no light late enrichment. Without an
aligned partition column, `K` and both `IN` tuples carry the grain key alone. The two references to `K` SHALL both
stand in the outer statement's scope — the outer `WHERE` and each late join's `ON` — and never inside a derived
table, because ClickHouse builds one set per distinct subquery within a scope and re-evaluates a subquery
referenced from a nested scope: two evaluations of a page whose order has ties, or that reads rows ingested in
between, can choose different rows, and a row whose page key is missing from a late join's set would read its
late columns as unmatched with no error. A late join SHALL therefore join the enrichment table itself, never a
derived table over it. A compile-time literal path that one of the scalar extractors or `json_extract_array`
addresses on a late enrichment's `object` column SHALL be read as that path's subcolumn of the joined table. A
reference that reads the whole document — `json_extract_string_ci`, a bare projection, a comparison, or an argument
to a function taking the whole document — reads the whole column, as it does unshaped, but for the page's rows
only. A materialized CTE
SHALL NOT be used for this (ClickHouse gates it behind a setting that is off by default).

The late-materialized statement SHALL return exactly the rows, column names, column order, values and
NULL/default semantics the unshaped statement returns, up to the order of rows that tie on the sort, which neither
statement fixes: the page block selects the top `offset + limit` rows of the same filtered relation, the outer
`WHERE` keeps those rows (and any other row sharing one of their keys, which ranks after them), and the outer
`ORDER BY`/`LIMIT`/`OFFSET` then cuts the same page; a late join's extra condition is true for every kept row,
so its match is the grain-key match the unshaped join gives it. A row whose grain key or partition column is NULL
is kept by the `IS NULL` arms and reads its late columns as unmatched, as it does unshaped. The `include_total`
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

#### Scenario: The last page of requests with their headers reads headers for the page only

- **WHEN** a row-mode query selects `request_time`, `dial_usage_log_payload.request_tags` and `trace_id`, none of
  them heavy, filters `request_time >= T`, sorts by `request_time` descending and pages `limit 25`
- **THEN** the block is late-materialized: the rendered SQL joins `dial_usage_log_payload` with
  `("dial_usage_log_payload"."event_id", "dial_usage_log_payload"."request_time") IN (K)` in its `ON`, and the
  outer `WHERE` carries the filter and the same `IN (K)` with the two `IS NULL` arms
- **AND** `K`'s page block joins no payload table, carries the filter and the sort, and has `LIMIT 25`

#### Scenario: A header path on a page-keyed join reads the path's subcolumn

- **WHEN** a late-materialized block whose late enrichments are light projects a literal path of
  `dial_usage_log_payload.request_tags`
- **THEN** the payload join is the payload table itself, not a derived table, with `IN (K)` in its `ON`
- **AND** the outer projection reads that path as a subcolumn of the joined table, not the whole `request_tags`
  column
- **AND WHEN** the block reads `request_tags` through `json_extract_string_ci` instead
- **THEN** the payload join is still the payload table itself with `IN (K)` in its `ON`, and the whole column is read
  for the page's rows

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
  without an `offset`, once projecting a heavy payload column and once projecting only light payload columns
- **THEN** both return identical column names, types and values, row for row

#### Scenario: The page keys are evaluated once

- **WHEN** the late-materialized shape runs with a page block ordered by a per-row random value
- **THEN** every row the outer `WHERE` keeps finds its late row, on every run

#### Scenario: A heavy column outside the projection keeps today's join

- **WHEN** a row-mode query filters, sorts or groups on a heavy enrichment column, or sorts by the output name of
  a `select` entry that reads one
- **THEN** the block is not late-materialized

#### Scenario: Shapes whose page cannot be chosen first keep today's join

- **WHEN** a row-mode query that projects a heavy enrichment column, or only light columns of an aligned
  enrichment, is `distinct`, or projects a window expression or `array_join`, or is in aggregate mode
- **THEN** the block is not late-materialized

#### Scenario: A light column of the heavy enrichment may appear anywhere

- **WHEN** a row-mode query projects `dial_usage_log_payload.request_body` and filters on
  `dial_usage_log_payload.request_tags`
- **THEN** the block is late-materialized, the page block joins the payload for `request_tags` only, and the
  outer statement reads both columns from the page-restricted join

#### Scenario: A filtered light column keeps today's join

- **WHEN** a row-mode query projects `dial_usage_log_payload.request_tags`, references no heavy column, and
  filters on a path of `dial_usage_log_payload.request_tags`
- **THEN** the block is not late-materialized and renders as "SQL translation" describes, including any partition
  pre-selection its filter qualifies for

#### Scenario: A sorted light column keeps today's join

- **WHEN** a row-mode query references no heavy column and sorts by a light column of an aligned enrichment,
  directly or through the output name of a `select` entry that reads one
- **THEN** the block is not late-materialized
- **AND WHEN** the same sort appears beside a projection-only heavy column of a second enrichment on the same keys
- **THEN** the block is late-materialized with only the heavy enrichment joined for the page's keys, and the sorted
  enrichment is joined as "SQL translation" describes

#### Scenario: A light column of an unaligned enrichment keeps today's join

- **WHEN** a row-mode query under a `LIMIT` projects only non-heavy columns of a keyable enrichment that declares no
  aligned partition column
- **THEN** the block is not late-materialized

#### Scenario: A light enrichment on another grain key does not disqualify the page

- **WHEN** a row-mode query projects a heavy column of one aligned enrichment and a non-heavy column of a second
  aligned enrichment joined on a different source grain key, both only in its `select`
- **THEN** the block is late-materialized, the first enrichment is joined for the page's keys, and the second is
  joined as "SQL translation" describes

#### Scenario: Heavy and light enrichments on the same keys share one page

- **WHEN** a row-mode query projects a heavy column of one aligned enrichment and a non-heavy column of a second
  aligned enrichment joined on the same source grain key and partition column, both only in its `select`
- **THEN** both enrichments are joined with the same `IN (K)` in their `ON`, `K` is one subquery text, and the
  page block joins neither

#### Scenario: A pre-selected light-only page keeps today's statement

- **GIVEN** an aligned `usage_client_identity` of `dial_usage_log` and an enrichment keyed on `chat_id` with no
  aligned partition pair
- **WHEN** a row-mode query filters `"usage_client_identity.client_session_id" = 's1'` and projects
  `dial_usage_log_payload.request_tags` and a column of the `chat_id` enrichment, references no heavy column, and
  pages `limit 25`
- **THEN** the block is not late-materialized: it renders as "SQL translation" describes, with the partition
  pre-selection in its `WHERE` and keying the `chat_id` enrichment's join
- **AND WHEN** it projects, instead of the `chat_id` enrichment's column, a column of an aligned enrichment joined on
  another grain key than the payload, or only `request_tags` beside source columns
- **THEN** the block is not late-materialized either
- **AND WHEN** it also projects a heavy payload column, beside the `chat_id` enrichment's column
- **THEN** the block is late-materialized as a heavy page is, and only its page block carries the pre-selection

#### Scenario: A light-only page takes its keys from what it defers

- **GIVEN** an aligned enrichment `trace_summary` of `dial_usage_log` joined on `trace_id`, listed before the payload in
  the entity's enrichment order
- **WHEN** a row-mode query references no heavy column and projects `trace_summary.title` and
  `dial_usage_log_payload.request_tags`, both only in its `select`
- **THEN** the page's keys are `event_id` and `request_time`, the payload is joined for them, and `trace_summary` is
  joined as "SQL translation" describes
- **AND WHEN** the query projects `trace_summary.title` and one non-`object` payload column instead
- **THEN** the payload is still joined for the page's keys, because `event_id` sorts before `trace_id`
- **AND** each statement is the same whatever order the catalog lists the enrichments in, up to the order of its joins

#### Scenario: A heavy column of an enrichment that cannot be keyed disqualifies the block

- **WHEN** a row-mode query projects a heavy column of an enrichment whose grain-key fields the model does not know,
  beside a light column of an aligned enrichment
- **THEN** the block is not late-materialized

#### Scenario: Composite blocks keep today's join

- **WHEN** a composite SQL statement's block projects a heavy enrichment column, or a non-heavy column of an
  aligned enrichment, under a `LIMIT`
- **THEN** the block renders as "SQL translation" describes, with no page-key subquery
