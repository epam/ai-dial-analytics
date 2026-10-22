## MODIFIED Requirements

### Requirement: Structured query envelope

The system SHALL provide an immutable request object model representing a single structured query
envelope with the fields `entity` (required), `filter`, `mode` (required), `select`, `group_by`,
`aggregate`, `having`, `sort`, `page`, and the optional boolean `final` (see "Read result deduplication").
JSON keys SHALL be `snake_case` (`group_by`, `include_total`, `value_type`, and the optional output-alias
key `as`). `final` SHALL be three-valued — `true`, `false`, or absent — and an absent `final` SHALL stay
absent through deserialization and re-serialization, never becoming `false`, because absent and `false`
read an `upsert_by_key` source differently.

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

#### Scenario: final binds three ways

- **WHEN** three otherwise identical queries are deserialized, one with `"final": true`, one with
  `"final": false`, and one without `final`
- **THEN** they bind to three distinct envelopes, and re-serializing the third emits no `final` key

### Requirement: Read result deduplication (latest-version-wins)

The system SHALL let a user-facing query choose, per entity it reads, between the **final** view of the
data — deduplicated, latest-version-wins, exactly as if a physical merge had completed — and the **raw**
view that the table's parts hold before background merges, with ClickHouse's `FINAL` semantics. For any
table backed by a `ReplacingMergeTree` (an `upsert_by_key` source and every enrichment), a final read
SHALL reflect only the surviving (latest-version) row per sort key regardless of whether ClickHouse has
merged the parts yet; a raw read SHALL return every row the parts hold, not-yet-merged versions included.
User-facing reads are those of `POST /v1/queries/execute`, `POST /v1/queries/execute-csv`,
`POST /v1/queries/execute-sql`, `POST /v1/queries/execute-sql-csv` and the Flight SQL / ADBC endpoint; the
rule SHALL hold identically across them, because they all delegate to the shared execution pipeline.

**The unit is the entity.** A final read of an entity SHALL deduplicate every table of it that collapses
versions — its source when the source is `upsert_by_key`, and every enrichment table the query joins for it
(an enrichment always collapses) — and its separate `count(*)` total-count statement likewise; a raw read
SHALL deduplicate none of them. An entity is **final-capable** when anything it reads collapses versions:
its source is `upsert_by_key`, or it has at least one active enrichment the caller may read. Capability SHALL
be decided at `TABLE_READ` — the bar execution resolves the entity at — on every surface, including translation
(resolved at `TABLE_GET`) and discovery (`TABLE_LIST`), so an enrichment the caller may get but not read
never makes an entity final-capable on one surface and not on another. An `append`
source with an enrichment is therefore final-capable: its final read deduplicates the enrichment and reads
the source's plain `MergeTree` as it is, so the grain join stays 0..1. There is no way to read an entity's source final and one of its
enrichments raw or the reverse, because a caller never names an enrichment join. A composite SQL statement
reads each of its entity references final or raw independently (see
[sql-query-translation](../sql-query-translation/spec.md) "FINAL table modifier").

**What a query asks for.** A structured query SHALL carry an optional top-level boolean `final`:

- `true` — read the entity final;
- `false` — read it raw;
- absent or `null` — read it final when it is final-capable and raw otherwise, so a query that never
  mentions `final` over an `append` source with no enrichment stays valid.

A SQL entity reference is read final when it carries the `FINAL` modifier and raw when it does not.

**`FINAL` is legal only where something can honour it.** An explicit `"final": true`, or a SQL `FINAL`,
on an entity that is not final-capable — an `append` source (a plain `MergeTree`, which ClickHouse refuses
`FINAL` on with `ILLEGAL_FINAL`) with no enrichment — SHALL be rejected with HTTP 400, `error` =
`illegal_final`, and a message naming the entity and stating that nothing it reads collapses versions; no
statement is executed. `FINAL` SHALL never be rendered on a plain `MergeTree` table. The same rejection SHALL
apply to `POST /v1/queries/translate` and `POST /v1/queries/translate-sql`, so a query that translates is
one that executes. Over the Flight SQL endpoint the same condition SHALL fail with Flight status
`INVALID_ARGUMENT` carrying the same message.

**The migration flag.** While `query.force-final` (`FORCE_FINAL_QUERIES`, default `true`) is on, every
user-facing read SHALL read every final-capable entity final, whatever the query says — `"final": false`
and a SQL reference without `FINAL` included — which is the always-on deduplication the system shipped
before `final` existed. An entity that is not final-capable is read raw under the flag, since it has nothing
to collapse. The flag SHALL NOT suspend the `illegal_final` rejection. Turning the flag off
SHALL change the result of exactly two kinds of read: a SQL reference without `FINAL` to a final-capable
entity, and a structured query sending `"final": false` for one. The flag governs what
is **executed**; `POST /v1/queries/translate` and `translate-sql` SHALL translate what the query says, not
what the flag would force.

**Internal reads are always final.** The pipeline's own reads — the enrichment scan, group fetch, the
Class-1 materialize and aggregate-refresh `INSERT … SELECT`, and the aggregate dry-run preview — SHALL keep
deduplicating every table they read, whatever `query.force-final` says and with no opt-out, through the
query-level `final = 1` execution setting (a no-op on an `append` source's plain `MergeTree`). Their
exactly-once and window guarantees rest on that and are not a caller's choice.

**Deduplication SHALL stay inside a partition wherever the catalog can vouch for it.** Under `FINAL`
ClickHouse assumes that two versions of one key may sit in different partitions, so it merges across every
partition of a version-collapsing table before it can apply a filter — and from ClickHouse 26.3
(`defer_partition_pruning_after_final`, default on) it stops using the table's partition and MinMax indexes
altogether for any table whose partition column is not part of its sorting key. For such a table a one-row
lookup reads a granule from every part of the table. The system SHALL therefore attach ClickHouse's
`do_not_merge_across_partitions_select_final = 1` to a statement whenever **every entity it reads final**
is *partition-local* — meaning that for the entity's source and each of its active enrichments, all
versions of a key are guaranteed to land in one partition. A table is partition-local when any of the
following holds:

- it declares no partition column;
- its partition column is one of its ordering-key columns (the key itself fixes the partition);
- it is an enrichment whose partition column is aligned with its source's — the same column name on both
  sides, carrying the same value for a grain key (the catalog's partition-alignment claim) — and that
  source is itself partition-local.

An `append` source (plain `MergeTree`) collapses nothing, so it never disqualifies an entity. The rule is
evaluated per entity from the catalog, never inferred from data, and a single non-qualifying table
withholds the setting for every read of that entity: the read then runs merging across partitions —
slower, never wrong. An entity read raw is not consulted, because the setting affects only tables read
under `FINAL`; a statement that reads no entity final SHALL carry the setting not at all. A composite
statement SHALL carry the setting only when every entity it reads final qualifies. The setting SHALL be
decided the same way on every statement the pipeline issues — row, count, scan, composite and the Class-1
materialize and aggregate-refresh `INSERT … SELECT` — where a pipeline read, being final throughout, is
decided over every entity it reads.

This is a cost rule, not a result rule: for a partition-local table the two modes return identical rows,
because a key whose versions all live in one partition is deduplicated the same way whether or not other
partitions are consulted. What the rule rests on is the immutability of the partition column per key, and
the catalog SHALL keep that an invariant of its own declarations: a source's partition column inside its
ordering key cannot change without changing the key, and an enrichment populated from the same record as
its source carries the source's partition value for the grain key by construction.

**The catalog SHALL describe the engine.** Whether an entity may be read final, what an absent `final`
resolves to, and whether its source takes the `FINAL` modifier are decided from the declared `write` facets
of its tables, so each facet SHALL agree with its engine (see
[table-management](../table-management/spec.md) "Table write discipline"). A source recorded `append` that is
physically a `ReplacingMergeTree` would never take `FINAL`, returning unmerged versions the engine means to
collapse; and one recorded `upsert_by_key` that is physically a plain `MergeTree` would be rendered `FINAL`
and fail in ClickHouse with `ILLEGAL_FINAL`, surfacing as the ClickHouse query rejection (HTTP 400
`bad_request`). The same holds for an enrichment registered over a hand-written plain `MergeTree`: its kind
says it collapses, so a final read that joins it renders `FINAL` on it and ClickHouse refuses the statement —
where the former query-level `final = 1` setting silently did nothing on that table. The internal reads, which deduplicate through the execution
setting, resolve against the table's actual engine and stay correct either way; the partition-local
decision reads the declared facet too, which is why the facet's agreement with the engine is a requirement
and not a courtesy.

#### Scenario: Source with unmerged duplicate rows returns one row per key

- **WHEN** a `ReplacingMergeTree` source (e.g. `dial_usage_log`) holds multiple not-yet-merged versions
  sharing its sort key and a row-mode query that is read final selects them
- **THEN** the response returns only the latest version per sort key, not the raw unmerged duplicates

#### Scenario: A raw read returns the unmerged versions

- **WHEN** `query.force-final` is off and a structured query with `"final": false` (or a SQL statement
  without `FINAL`) selects a key of `dial_usage_log` that has two not-yet-merged versions
- **THEN** the response returns both versions, as the same statement without `FINAL` returns them in
  ClickHouse

#### Scenario: An absent final reads a collapsing source final

- **WHEN** `query.force-final` is off and a structured query over `dial_usage_log` omits `final`
- **THEN** the query is read final and returns one row per sort key

#### Scenario: Total count reflects deduplicated rows

- **WHEN** a row-mode offset query with `include_total` that is read final runs against a source that has
  unmerged duplicate rows
- **THEN** the returned `totalCount` counts each sort key once (the deduplicated count), matching the
  deduplicated row set rather than the raw part-level row count

#### Scenario: Re-patched enrichment does not fan out source rows

- **WHEN** a query that is read final references an enrichment column and that enrichment has multiple
  unmerged versions for a grain key that matches source rows
- **THEN** each matching source row appears exactly once, joined to the latest enrichment version,
  because the enrichment side is deduplicated with the source

#### Scenario: Recomputed upsert-by-key source row reads as the latest value

- **WHEN** an `upsert_by_key` source receives a second write for a key it already holds (a rollup bucket
  recomputed with a new value), the parts have not merged, and a query that is read final selects it
- **THEN** the query returns one row for that key carrying the later write's values, so a re-written key
  reads as a replacement rather than as two rows

#### Scenario: Append source is unaffected

- **WHEN** a query that omits `final` runs against an `append` source (a plain `MergeTree`, no
  version-collapsing engine) with no enrichment, with `query.force-final` on or off
- **THEN** the source is read raw, no `FINAL` is applied, and every row the source holds is returned

#### Scenario: FINAL on an append source is rejected

- **WHEN** a structured query sends `"final": true` for an `append` source with no enrichment, or a SQL
  statement writes `FINAL` after one, with `query.force-final` on or off
- **THEN** the request is rejected with HTTP 400, `error` = `illegal_final`, naming the entity, and no
  statement is executed

#### Scenario: An append source reads final through its enrichments

- **WHEN** an `append` source has an enrichment holding two unmerged versions for a grain key, and a query
  that omits `final` (or says `"final": true`, or writes `FINAL`) projects the enrichment column
- **THEN** the statement renders `FINAL` on the enrichment and not on the source, and the source row is
  returned once with the latest enrichment value; with `"final": false` and `query.force-final` off it is
  returned once per enrichment version

#### Scenario: An enrichment the caller cannot read does not make the entity final-capable

- **WHEN** a caller holds `list` and `get` but not `read` on the only enrichment of an `append` source it may
  read, and sends `"final": true` (or writes `FINAL`) to execute or to translate
- **THEN** both are rejected with `illegal_final`, and a query omitting `final` is read and translated raw

#### Scenario: The flag forces final reads

- **WHEN** `query.force-final` is on and a structured query with `"final": false`, or a SQL statement
  without `FINAL`, reads a key of `dial_usage_log` that has two not-yet-merged versions
- **THEN** the response returns one row for the key, as it did before `final` existed

#### Scenario: Internal reads stay final with the flag off

- **WHEN** `query.force-final` is off and a Class-1 materialize, an aggregate refresh or an enrichment scan
  reads a source key that has two not-yet-merged versions
- **THEN** the read sees one version of the key, the latest, exactly as with the flag on

#### Scenario: The declared discipline predicts what a read returns

- **WHEN** a caller reads a source's `write` facet and then queries that source's own columns omitting `final`
- **THEN** an `upsert_by_key` source returns one row per ordering key and an `append` source returns every
  row, so the declaration accounts for any difference between an ADAS read and a raw `SELECT`

#### Scenario: Partition-local entity reads deduplicate within partitions

- **WHEN** a query reads final an entity whose source and every active enrichment are partition-local (for
  example `dial_usage_log`, whose partition column is in its ordering key, together with
  `dial_usage_log_payload`, an enrichment partitioned by the same `request_time` its source partitions by,
  and unpartitioned enrichments)
- **THEN** every statement the read issues carries `do_not_merge_across_partitions_select_final = 1`, and a
  single-row lookup of a heavy enrichment column prunes to the partition that holds the row instead of
  merging every partition of the enrichment

#### Scenario: Partition-local reads return the same rows as cross-partition reads

- **WHEN** a partition holds several not-yet-merged versions of one key and a final read of a
  partition-local entity selects that key
- **THEN** the read returns exactly one row for the key carrying the latest version, a LEFT-JOINed
  enrichment never fans the source out, and the result equals what the same statement returns without the
  partition-local setting

#### Scenario: A non-qualifying table withholds the partition-local setting for its entity

- **WHEN** an entity's source or one of its active enrichments is an `upsert_by_key` table whose partition
  column is neither in its ordering key nor aligned with a partition-local source's partition column, and a
  query reads that entity final
- **THEN** the statement does not carry `do_not_merge_across_partitions_select_final`, merging across
  partitions, and the setting is withheld for every final read of that entity — including ones that do not
  reference the non-qualifying enrichment

#### Scenario: A raw read carries no partition-local setting

- **WHEN** `query.force-final` is off and a query reads a partition-local entity raw
- **THEN** the statement carries neither `FINAL` nor `do_not_merge_across_partitions_select_final`

#### Scenario: A composite statement qualifies only when every entity it reads does

- **WHEN** a SQL statement joins two entities, one partition-local and one not, and marks both `FINAL`
- **THEN** the statement does not carry the partition-local setting; when both qualify it carries it as a
  single-entity read would; and when only the partition-local one is marked `FINAL` (with
  `query.force-final` off) it carries it, because the entity read raw is not consulted

#### Scenario: Write-side reads follow the same rule

- **WHEN** a Class-1 materialize or an aggregate refresh writes its target with an `INSERT … SELECT` over a
  partition-local entity
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
them. The key sub-query of a keyed join (`SELECT source.grain_key FROM source WHERE <filter>`)
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

#### Scenario: A final-read query renders FINAL on the source and every joined enrichment

- **WHEN** a validated query that is read final references columns of two enrichments, one joined keyed
  and one unkeyed
- **THEN** the rendered SQL carries `FINAL` after the source table reference, after the unkeyed
  enrichment table, and after the enrichment table inside the keyed join's derived table, and the key
  sub-query `SELECT source.grain_key FROM source WHERE <filter>` carries none

#### Scenario: A query not read final renders no FINAL

- **WHEN** a validated query that is not read final references an enrichment column
- **THEN** the rendered SQL carries `FINAL` on no table reference, and a grain key of the enrichment
  with two unmerged versions returns its source row twice
