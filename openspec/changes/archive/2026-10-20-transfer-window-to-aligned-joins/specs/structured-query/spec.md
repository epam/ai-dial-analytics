## ADDED Requirements

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
join with no aligned pair, an unkeyed join and a late-materialized join SHALL render exactly as before.

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
- **AND** a query whose filter is `day >= T` and which selects an enrichment column
- **WHEN** the statement is rendered
- **THEN** the enrichment's derived table carries `enrichment.day >= T`
- **AND** it carries neither `enrichment.grain_key IN (SELECT …)` nor `enrichment.day IN (SELECT …)`
- **AND** the join condition still compares the grain key and `day`

#### Scenario: A pushed enrichment conjunct stays beside the transferred window

- **GIVEN** the same aligned pair and a filter `day >= T AND enrichment_column = X`
- **WHEN** the statement is rendered
- **THEN** the derived table carries `enrichment.day >= T` and `enrichment_column = X`
- **AND** no key set

#### Scenario: Any other source predicate keeps both key sets

- **GIVEN** the same aligned pair and a filter `day >= T AND deployment = 'd'`
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
- **WHEN** the same window-only query runs rendered with the transfer and with both key sets
- **THEN** both return identical rows

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
  projects a column of `<enrichment_b>`, and neither enrichment is partition-aligned with the source on
  `request_time`
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
- **AND** a query filtering on a source column alone and selecting an enrichment column
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
- **AND** a query with no filter
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

### Requirement: Null-rejecting enrichment-only conjuncts narrow the enrichment's derived table

A top-level conjunct of the filter SHALL be **copied** into an enrichment's derived-table `WHERE` on the single-entity
read path, in the row statement and in the `include_total` count statement (the filter flattened as "SQL translation"
flattens it for keying), beside its grain-key and partition restrictions (or the transferred partition bounds that replace them), when it names exactly one
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
- **THEN** the payload derived table carries the `ILIKE '%error%'` condition beside the transferred
  `request_time >= T` bound, with no key set, because the payload is partition-aligned on `request_time` and the
  window is the only source-only conjunct
- **AND** with a further source conjunct such as `deployment = 'd'` the condition stands beside the
  `event_id IN` and `request_time IN` restrictions instead
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
