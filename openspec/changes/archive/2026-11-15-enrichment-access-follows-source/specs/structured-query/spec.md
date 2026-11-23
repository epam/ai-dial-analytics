## ADDED Requirements

### Requirement: Composite entity visibility follows the source

A queryable entity is a source table flattened with its active enrichments. Read visibility SHALL be
decided **per owning physical table** at the single point where the resolved query model is
assembled, so the same entity resolves to different flat schemas for different callers. For an enrichment, the
read-side privileges are its source's (see `table-access-control`, "An enrichment's read-side access is its
source's"), so per-owning-table evaluation differs between the source and an enrichment only in which columns are
which table's.

An entity SHALL be visible if and only if its **source** is readable by the caller. For a visible
entity, every active enrichment's non-sensitive columns SHALL be present in the model, and its sensitive columns
SHALL be present exactly when the caller holds the source's clearance. A caller SHALL therefore never see a different set of enrichments
than another caller with the same privileges on the source.

There SHALL be no read of an enrichment without a read of its source: an enrichment is not a queryable entity, so
there is no statement that can reach its rows, and the service SHALL NOT serve an enrichment's columns through its
source's entity to a caller that may not read the source, because the grain key is the source's column and the
statement's `FROM` is the source table, so doing so would disclose the source's row population and its grain-key
values.

#### Scenario: A reader of the source sees every enrichment

- **GIVEN** an entity with two active enrichments, and a caller that may read the source and holds nothing on either
  enrichment
- **WHEN** the caller requests that entity's schema and then queries a column of each enrichment
- **THEN** both enrichments' non-sensitive columns are present in the schema, the query resolves, and the row count
  matches what a fully-privileged caller receives

#### Scenario: An entity is invisible when its source is

- **GIVEN** an entity whose source the caller may not read, and one of whose enrichments lists the caller's role in
  its `write` or `modify` list
- **WHEN** the caller lists entities or queries that entity
- **THEN** the entity is absent from discovery and the query reports an unknown entity

#### Scenario: Sensitive columns follow the source's clearance

- **GIVEN** an entity whose source and one enrichment both carry sensitive columns, and a caller
  holding `read` on the source and no clearance
- **WHEN** the caller queries the entity
- **THEN** neither the source's nor the enrichment's sensitive columns resolve
- **AND WHEN** the caller is granted `sensitive` on the source
- **THEN** both resolve

## MODIFIED Requirements

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
its source is `upsert_by_key`, or it has at least one active enrichment the caller may read — every active enrichment, for a caller that may
read the source, because an enrichment's read is its source's. Capability SHALL
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

- **WHEN** a caller holds `list` and `get` but not `read` on an `append` source with an active enrichment, so it may
  not read that enrichment either, and sends `"final": true` (or writes `FINAL`) to translate
- **THEN** the translation is rejected with `illegal_final`, a query omitting `final` is translated raw, and executing
  either reports an unknown entity

#### Scenario: An absent final reads an enriched append source final for a reader of the source

- **WHEN** `query.force-final` is off, a caller holding `read` on an `append` source and nothing on its enrichment
  sends a query that omits `final` and projects an enrichment column holding two unmerged versions for a grain key
- **THEN** the query is read final and the source row is returned once with the latest enrichment value

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

## REMOVED Requirements

### Requirement: Composite entity visibility is decided per owning table

**Reason**: An enrichment no longer has read-side access of its own, so one caller cannot read a source and not one of its enrichments, nor see the source's sensitive columns and not an enrichment's. Replaced by "Composite entity visibility follows the source".

**Migration**: A column of an enrichment that should not reach every reader of its source is marked `sensitive`, and is then readable only with the source's clearance.
