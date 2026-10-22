## MODIFIED Requirements

### Requirement: Read result deduplication (latest-version-wins)

The system SHALL return query results as the deduplicated, latest-version-wins view of the underlying
data. For any table backed by a `ReplacingMergeTree` (an `upsert_by_key` source and every joined
enrichment), results SHALL reflect only the surviving (latest-version) row per sort key, exactly as if
a physical merge had completed, regardless of whether ClickHouse has actually merged the parts yet. The
system SHALL achieve this by applying ClickHouse `FINAL` semantics to every read the query pipeline issues,
via the query-level `final = 1` setting attached at execution time rather than by rewriting the
rendered SQL. Because the setting is applied to the executed statement, it SHALL apply uniformly to
the source table, all LEFT-JOINed enrichment tables, and the separate `count(*)` total-count query.
The setting SHALL be a no-op for tables whose engine does not support `FINAL` (an `append` source's
plain `MergeTree`), leaving their results unchanged. Deduplication SHALL be global and
always-on; there is no per-query opt-out. This guarantee is part of the shared execution pipeline and
therefore SHALL hold identically for the structured DSL, the REST SQL endpoint, and the Flight SQL /
ADBC endpoint, which all delegate to it.

**Deduplication SHALL stay inside a partition wherever the catalog can vouch for it.** Under
`final = 1` ClickHouse assumes that two versions of one key may sit in different partitions, so it merges
across every partition of a version-collapsing table before it can apply a filter — and from ClickHouse
26.3 (`defer_partition_pruning_after_final`, default on) it stops using the table's partition and MinMax
indexes altogether for any table whose partition column is not part of its sorting key. For such a table a
one-row lookup reads a granule from every part of the table. The system SHALL therefore also attach
ClickHouse's `do_not_merge_across_partitions_select_final = 1` to a read whenever **every**
version-collapsing table the read's entity can reach — the source and each of its active enrichments — is
*partition-local*, meaning all versions of a key are guaranteed to land in one partition. A table is
partition-local when any of the following holds:

- it declares no partition column;
- its partition column is one of its ordering-key columns (the key itself fixes the partition);
- it is an enrichment whose partition column is aligned with its source's — the same column name on both
  sides, carrying the same value for a grain key (the catalog's partition-alignment claim) — and that
  source is itself partition-local.

An `append` source (plain `MergeTree`) collapses nothing, so it never disqualifies an entity. The rule
is evaluated per entity from the catalog, never inferred from data, and a single non-qualifying table
withholds the setting for every read of that entity: the read then runs exactly as before, merging across
partitions — slower, never wrong. A composite statement over several entities SHALL carry the setting only
when every entity it reads qualifies. The setting SHALL travel with `final = 1` on every read the pipeline
issues — row, count, scan, composite and the Class-1 materialize and aggregate-refresh `INSERT … SELECT`
— so a statement never carries one without the other having been decided.

This is a cost rule, not a result rule: for a partition-local table the two modes return identical rows,
because a key whose versions all live in one partition is deduplicated the same way whether or not other
partitions are consulted. What the rule rests on is the immutability of the partition column per key,
and the catalog SHALL keep that an invariant of its own declarations: a source's partition column inside
its ordering key cannot change without changing the key, and an enrichment populated from the same
record as its source carries the source's partition value for the grain key by construction.

**The engine decides, and the catalog SHALL describe the engine.** Because the setting is resolved by
ClickHouse against the table's actual engine rather than against the catalog, this guarantee holds
whatever the catalog says — which means a table whose declared `write` discipline disagrees with its
engine keeps returning correct rows while the catalog misdescribes why. The `write` facet SHALL therefore
agree with the engine (see [table-management](../table-management/spec.md) "Table write discipline"), so
that "which rows does a read return" has one answer reachable from either.

The requirement is on the *description*, not on the results, because the description is what an operator
reasons from: a `ReplacingMergeTree` declared `append` presents a legitimate engine-level collapse as an
unexplained shortfall against raw SQL, and rules out the only correct reading of it. The mechanism is
deliberately left resolving against the engine, so it stays right even when the catalog is wrong — the
one direction in which these two can disagree without any read returning a wrong row. The partition-local
decision above is the single exception, and SHALL be understood as one: whether a table is exempt from the
rule because it collapses nothing is read from the declared `write` facet, so a source recorded `append`
that is physically a `ReplacingMergeTree` partitioned outside its ordering key would be deduplicated inside
each partition and could return two versions of a key. That is why the facet's agreement with the engine
is a requirement and not a courtesy.

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
- **THEN** the `final = 1` setting is a no-op and every row the source holds is returned

#### Scenario: The declared discipline predicts what a read returns

- **WHEN** a caller reads a source's `write` facet and then queries that source
- **THEN** an `upsert_by_key` source returns one row per ordering key and an `append` source returns every
  row, so the declaration accounts for any difference between an ADAS read and a raw `SELECT`

#### Scenario: Partition-local entity reads deduplicate within partitions

- **WHEN** a query reads an entity whose source and every active enrichment are partition-local (for
  example `dial_usage_log`, whose partition column is in its ordering key, together with
  `dial_usage_log_payload`, an enrichment partitioned by the same `request_time` its source partitions by,
  and unpartitioned enrichments)
- **THEN** every statement the read issues carries both `final = 1` and
  `do_not_merge_across_partitions_select_final = 1`, and a single-row lookup of a heavy enrichment column
  prunes to the partition that holds the row instead of merging every partition of the enrichment

#### Scenario: Partition-local reads return the same rows as cross-partition reads

- **WHEN** a partition holds several not-yet-merged versions of one key and a read of a partition-local
  entity selects that key
- **THEN** the read returns exactly one row for the key carrying the latest version, a LEFT-JOINed
  enrichment never fans the source out, and the result equals what the same statement returns without the
  partition-local setting

#### Scenario: A non-qualifying table withholds the partition-local setting for its entity

- **WHEN** an entity's source or one of its active enrichments is an `upsert_by_key` table whose partition
  column is neither in its ordering key nor aligned with a partition-local source's partition column
- **THEN** reads of that entity carry `final = 1` only, merging across partitions as before, and the
  setting is withheld for every statement of that entity — including ones that do not reference the
  non-qualifying enrichment

#### Scenario: A composite statement qualifies only when every entity it reads does

- **WHEN** a SQL statement joins two entities, one partition-local and one not
- **THEN** the statement carries `final = 1` only; when both qualify it carries the partition-local setting
  as a single-entity read would

#### Scenario: Write-side reads follow the same rule

- **WHEN** a Class-1 materialize or an aggregate refresh writes its target with an `INSERT … SELECT`
  over a partition-local entity
- **THEN** the statement carries `do_not_merge_across_partitions_select_final = 1` alongside `final = 1`,
  and over a non-qualifying entity it carries `final = 1` only

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
are unchanged by it. Whether that deduplication merges across partitions or stays inside each one is
decided per entity by the read-deduplication requirement ("Deduplication SHALL stay inside a partition
wherever the catalog can vouch for it"); the 0..1 guarantee holds either way, because an enrichment's
partition column is immutable for a grain key, so no key ever has versions in two partitions.
**The join SHALL be keyed when the query's `filter` names source columns only**: the enrichment side
SHALL then be emitted as a derived table — the grain key plus the enrichment columns the query
references — restricted to `enrichment.grain_key IN (SELECT source.grain_key FROM source WHERE
<filter>)` and aliased to the enrichment's own name, so ClickHouse's right-side hash build covers the
grain keys the filter selects rather than the whole enrichment table. A query with no `filter`, or whose
`filter` names an enrichment column, SHALL join the enrichment table unkeyed. The same rule SHALL apply
to the `include_total` count statement. Keying changes which rows the join build reads, never which
rows the statement returns. A projected enrichment column SHALL be returned under its
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

#### Scenario: A filter naming an enrichment column joins the enrichment unkeyed

- **WHEN** a validated query's `filter` references an enrichment column, or the query has no `filter`
- **THEN** the rendered SQL `LEFT JOIN`s the enrichment table itself, with no key sub-query

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
