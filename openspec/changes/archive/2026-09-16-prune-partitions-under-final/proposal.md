## Why

Every ADAS read runs under the ClickHouse `final = 1` setting. ClickHouse 26.3 introduced
`defer_partition_pruning_after_final` (default `1`), which under FINAL skips partition pruning entirely
for tables whose partition-key columns are not part of the sorting key — because two versions of one
key could, in general, live in different partitions. `dial_usage_log_payload` (`ORDER BY event_id`,
partitioned by day of `request_time`) is exactly that shape. Under ClickHouse
26.3.24.4 (`EXPLAIN indexes=1`) a one-row lookup of one heavy payload column keeps every part,
merges hundreds of part-streams and reads on the order of GiB; a six-hour `project_id` range query with
`LIMIT 10` exceeds the 2 GiB query budget. After a server moves to 26.3, a few such reads running
concurrently — the enrichment runner's group fetches, the `usage_client_identity` materialize and the
baggage scans — can exhaust the server-total memory limit, failing ADAS's own reads by the thousands and
killing unrelated small queries alongside them.

ClickHouse does the right thing when told that a key's versions never span partitions
(`do_not_merge_across_partitions_select_final = 1`, available on every supported server version). It
already infers this on 26.2+ for tables whose partition columns are in the sorting key
(`dial_usage_log`, `rate_analytics`), but cannot for the payload table. ADAS can: the payload's
`request_time` is copied from the parent event and never changes — the same partition-alignment claim
the catalog already relies on to key its joins. Replaying real ADAS-generated statements with
the setting on: the point lookup costs a few MB instead of GiB, the runner's group fetch tens of MB instead
of GiB, and the range query returns its 10 rows in about a second using a few hundred MB instead of failing.
Deduplication, latest-version-wins and the 0..1 join fan-out were verified unchanged, and zero keys span
partitions on either partitioned upsert table.

## What Changes

- Every ADAS-issued read — the structured/SQL/Flight row and count statements, the pipeline scan,
  group-fetch, materialize probe and aggregate preview/probe reads, and the Class-1 materialize and
  aggregate refresh `INSERT … SELECT` — sends `do_not_merge_across_partitions_select_final = 1`
  alongside `final = 1` **when every version-collapsing table the statement's entity can read is
  partition-local**: it has no partition column, or its partition column is part of its ordering key, or
  it is an enrichment whose partition column is aligned with a partition-local source's (the catalog's
  existing alignment claim). Otherwise the statement runs exactly as today.
- The `structured-query` spec's read-deduplication requirement states this partition-local
  deduplication and the invariant it rests on (an upsert table's partition column is immutable per key),
  replacing the SQL-translation paragraph that currently says the setting keeps its default of `0` and
  that the 0..1 join guarantee depends on merging across partitions.
- Unit coverage asserts the setting is sent for a partition-local entity and withheld for an entity
  with a non-qualifying table, on the row/count, scan and `INSERT … SELECT` paths.
- No configuration property, endpoint, catalog field or migration is added. No wire contract changes;
  results are identical, only their cost changes.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `structured-query`: the "Read result deduplication (latest-version-wins)" requirement gains the
  partition-local deduplication rule and its qualifying conditions; the "SQL translation" requirement
  drops the sentence that pins `do_not_merge_across_partitions_select_final` to its default and
  makes the 0..1 guarantee depend on cross-partition merging, referring to the deduplication
  requirement instead.

## Impact

- **Code**: `StructuredQueryExecutor` (standing FINAL settings for the row, count, scan and composite
  paths), `ClickHouseInsertSelectExecutor` (the write-side `final = 1`), `QueryEntityModel` /
  `CatalogQueryService` (the entity carries whether it is partition-local, derived from the catalog's
  ordering key, partition column and enrichment alignment), `StructuredQueryService` (composite
  statements qualify only when every entity they read does), and the pipeline call sites that hand a
  statement to those executors (`PipelineReadBudget`, `MaterializeService`, `AggregateRefreshService`).
- **Behaviour**: no result change on any existing table — all partitioned upsert
  tables qualify and all unpartitioned ones are unaffected. Cost drops by two to three orders of
  magnitude for statements touching `dial_usage_log_payload`, which removes the dominant cause of the
  ClickHouse server-total memory failures.
- **Specs**: `openspec/specs/structured-query/spec.md`. The `class1-enrichment` and
  `enrichment-runtime` specs describe their reads as `final=1` by reference to the shared pipeline and
  need no delta. `docs/clickhouse-server.md` already names `defer_partition_pruning_after_final` in its
  `compatibility` note and gets one sentence pointing at the rule that now neutralises it.
- **Operational**: a user-created `upsert_by_key` table whose partition column is neither in its
  ordering key nor aligned with a qualifying source keeps today's slow-but-correct behaviour, and if it
  is an enrichment of a partition-local source it also disables the optimisation for that source's
  reads (logged at INFO when the entity model is built). No configuration or deployment step is
  required; no ClickHouse version floor changes.
