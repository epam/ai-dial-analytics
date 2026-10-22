## Context

This document records the decisions from the investigation and lab measurements. Short version:

- `usage_client_identity` (UCI) is `ReplacingMergeTree(_updated_at) ORDER BY event_id`, unpartitioned,
  no skip index, millions of rows. `event_id` is a random UUID.
- Group-select (`GroupFetchService.selectMembers`) joins UCI **unkeyed** — the group-key predicate
  `toString(uci.client_session_id) = '<key>'` names an enrichment column and cannot travel into the key
  subquery — so it hashes all of UCI and reads `dial_usage_log` back to the floor.
- The scan's `event_id IN (<ids>)` touches about one granule per id in every part.
- `sessions_rollup`'s dirty-key restriction narrows the hash, not the reads; the outer `dial_usage_log`
  read has no predicate.
- `dial_usage_log_payload` already has the shape we want (partitioned by `request_time`, aligned partition
  column), and every keyed path already prunes it (`EnrichmentJoins.keys`, `PartitionLocality`,
  structured-query "A keyed grain join is bounded by an aligned partition column").
- Facts relied on [measured]: no `event_id` has more than one `request_time`; `dial_usage_log_payload` can start
  later than `dial_usage_log`, so UCI **cannot be re-materialized** for older rows
  and an existing table must be **copied**; UCI's only writer is the Class-1 pipeline
  `usage-client-identity-live`.

## Goals / Non-Goals

**Goals:**
- Make the scan, the group-select and the aggregate recompute cost O(active days of the keys involved)
  instead of O(history), for every row-grain enrichment of a partitioned source, with no user declaration.
- Reach existing tables on every environment by deploy alone — no call, property or runbook step.
- Keep every result identical to today's.

**Non-Goals:**
- Pre-selection over a *source* column with a bloom index (`chat_id`, `trace_id` — B5 /
  `conversations_rollup`). Same idea, follow-up change.
- Relaying out an existing table written by a row-grain LLM pipeline (its queued and dead-lettered runner state
  predates the echo).
- Aligning group-grain enrichments (`grain_key` ≠ source identity).

## Decisions

### D1. ADAS decides the layout; nobody declares it

Eligibility = `grain_key` is the source's `identity_column` ∧ the source declares a partition column ∧ the
source is partition-local (`PartitionLocality`). The define-schema path then adds the alignment column and
sets `partition_by` on the definition; `TableDdlRenderer.renderCreate` emits `PARTITION BY` for it.

*Rejected:* a user-facing `aligned: true` / `partition_by` on enrichments — it lets a caller assert a
population property they cannot guarantee (the reason the 422 exists). *Rejected:* an
"identity-fixes-partition" source flag — the tuple join (D2) makes the effective key
`(grain, partition)`, partition-local by construction, so the flag buys nothing.

A declared column with the alignment column's name, type and non-nullability is **adopted** as the
alignment column; any other same-named column is a 422. A pipeline output bound to it stays valid and is
superseded by the source value. This exists because `usage_request_baggage` already declares
`request_time` (bound `request_time: request_time` by `usage-request-baggage-live`, driving its TTL) [measured]:
refusing it would block that table's relayout and every catalog import of it, while adopting it writes the
same values the binding writes today. Catalog export then needs no change — the column travels as stored
and import's define-schema adopts it, keeping its description. *Rejected:* refusing the binding (would
break the existing pipeline until patched); omitting the column from export (loses the adopted column's
description and still leaves the relayout collision).

### D2. The aligned join is a tuple join

`grain = grain AND p = p`, built where the join condition is built (`CatalogQueryService`;
`EnrichmentJoins.pageKeyed` already uses the tuple). Applied to `dial_usage_log_payload` too, so there is one
rule; the RC2 late-materialize tests are re-run against it.

Consequence: a row written with a wrong `request_time` is unmatched, never attached to another source row;
two source rows sharing `event_id` across days never fan out.

### D3. Partition pre-selection from a lookup conjunct (F2)

`PS = SELECT DISTINCT partitionExpr(p) FROM E WHERE C [AND B(E.p)] SETTINGS final=0`, applied to the source's
`WHERE` only: the analyzer carries `partitionExpr(src.p) IN PS` through the tuple join's `src.p = E.p` into `E`,
so a second copy in `E`'s derived table only evaluated `PS` twice (172,800 vs 129,600 rows read, same pruning,
26.3 and 26.8 [lab]). `B` is the statement's own source-only bound on the partition column, re-rendered onto
`E.p` (exact by the tuple join), so a time-bounded lookup does not read `E` across all history (612k vs 1.02M
rows unbounded [lab]). `PS` also keys only the joins *without* an aligned pair; an aligned join is already pruned
through its tuple join, and keying it again doubled its reads. Implemented once in the query layer
(`ReadShaping` decides qualification; `QueryEntityModel.ownerOf` finds the owning join) and invoked by three
callers: the DSL path (`StructuredQueryBuilder`), group-select, and the aggregate
recompute (`AggregateCompiler.compileRefresh`, `AggregateSqlCompiler` via `LeafRestriction`).

*Why a partition set, not `min(request_time)`:* a lower bound reads a long session's whole
span; the set reads only active days and bounds from above. Its size is capped by the partition count
(≤ 366/year), so it cannot exhaust memory. *Rejected:* a set of exact `request_time` values — a large share of UCI rows
carry a session, and an empty key built a set of millions of values that exhausted a local lab's memory. That is also why a
default-valued literal never qualifies.

*Why only lookups:* a non-selective conjunct selects nearly every partition (a scan for nothing), and one
true on the default would drop unmatched rows the statement admits.

*Why `final=0`:* stale versions can only widen `PS` (still exact), and a skip index is only usable without
FINAL (`PrimaryKeyExpand`).

A `string` group key is rendered on the bare column: `toString(col)` disabled the index in the lab (6.10M rows
vs 53k). A key of any other type stays on `toString` and gets no pre-selection — parsing it into a typed literal
would make `'01'` match `1`.

### D4. Bloom index on grouped-by columns, never materialized (F3)

`bloom_filter(0.001)`, `GRANULARITY 1`, added with `ALTER TABLE … ADD INDEX` when a group trigger's or
aggregate's `group_by` names an aligned column. 0.001 because the default 0.025 floors at ~2.5% of the table
(153k rows for a 29-row session); 0.001 floors at ~15k and costs ~200 KiB per 6M rows [lab].

*Rejected:* `MATERIALIZE INDEX` — a mutation over all history on the shared cluster for an optimisation the
query does not need for correctness. Existing tables get full coverage for free from the F4 copy, because the
index is in the new table's DDL before it is filled. Deleting the pipeline leaves the index (harmless).

### D5. TTL inherited from the source's physical table

The source's TTL clause and `ttl_only_drop_parts` are read from ClickHouse (`system.tables.engine_full`, parsed
only in the single-rule shape the server renders) and rendered into the aligned table's DDL; the column name
matches by construction. A relayout applies it last instead (D7): an epoch-partition row is already expired, and a
TTL in force drops its part before the repair runs [verified in the integration test]. Not mirrored into the catalog, mirroring
the existing rule for migration-managed TTLs. *Rejected:* reading the catalog `ttl` — `dial_usage_log`'s
TTL is migration-managed and not in the catalog, so the case that matters would inherit nothing.

### D6. Automatic relayout as a resumable background job (F4)

- Started from `ApplicationReadyEvent` + a fixed 10-minute grace delay (constant), on its own executor — never
  on the probe path.
- Postgres advisory lock per table; progress (`step`, catch-up marks, error) in a new Postgres table; the
  writer hold is a flag in the same row, read on every write so it holds on all instances.
- Engine check first: `SELECT engine FROM system.databases WHERE name = currentDatabase()`. `Atomic` /
  `Replicated` → `EXCHANGE TABLES`; anything else → `layout: unsupported`, WARN, skip.
  *Rejected:* multi-table `RENAME` — refused on a Replicated database [lab].
- Copy one **slice of grain keys** per statement, `_updated_at` unchanged, `request_time` joined from
  `dial_usage_log` restricted to the slice's keys, with an `ANY` join (each identity has one partition value, so no
  `DISTINCT`); every slice statement — copies, catch-ups, epoch repair and the pre-drop check (an anti-join per
  slice) — is capped at `pipeline.budget.max-memory`. A slice holds about 256 MiB of the table's uncompressed data
  (rows per slice from `system.parts` bytes per row), and its upper key is read with `ORDER BY key LIMIT 1 OFFSET n`
  on the table's own sort key just before it runs; the last slice is open above. The resume position is the last
  key a completed slice copied through.
  [lab, ~10x scale: a source of tens of millions of rows] a slice statement peaks at 0.92-0.94 GiB in 1.4-1.8 s and
  reads only its slice of the table; the per-slice check is ~150 MiB. [measured] the read side of one slice is a few hundred MiB.
  *Rejected:* one source month per statement — ~2 GiB at 1x, uncapped, and ~18 GiB per month at x10 [estimate].
  *Rejected:* one day per statement — a typical x10 day (1M rows) was 1.22 GiB and the peak day (2.69M) was killed
  above 2 GiB [lab], and every day re-reads the whole table. *Rejected:* `grace_hash` with `max_bytes_in_join` —
  the same statement peaked at 0.79 GiB once and 3.2 GiB on a rerun (a hash-table resize lands before the limit's
  check) [lab]. *Rejected:* hash-bucket slices (`cityHash64(key) % n`) — bounded, but each reads the whole table.
- `do_not_merge_across_partitions_select_final=1` is required on aligned reads (7.56M vs 0.24M rows without it),
  and `FinalSettings` already supplies it.
- The hold waits out `pipeline.budget.timeout` plus a margin before the last copy, so a materialize admitted
  just before it finishes first; materialize also re-checks the hold right before its INSERT. A hold lapses when
  its row has not been saved for 10 minutes (an instance died mid-hold), judged by Postgres' clock. Schema PATCH
  is refused (409) while a relayout runs. A failure before the swap is retried from the start on the next start;
  a shutdown keeps the progress. The copy is named `_relayout_<name>`, a name no user table can have. The
  pre-drop check counts old-table keys missing from the new table, and splits them into rows whose source row
  has expired (WARN, expected — the copy joins the source) and real losses (ERROR, old table kept).
- Writer hold returns **503 `table_relayout_in_progress`**. The runner already retries a 503 from its
  committed cursor on both lanes and treats it as a gateway error (no contract-state change), so the hold
  itself needs no runner change. *Rejected:* 409 — same runner path, but misstates a transient
  condition. *Rejected:* no fence — leaves the epoch-default window.
- *Rejected:* adding the column in place (`ALTER … ADD COLUMN` on the live table + partial catalog state) — the copy
  derives `request_time` itself.

### D7. Deploy skew under RollingUpdate

The old pod knows neither the hold nor the alignment column. Three cheap measures: the grace delay; a
pre-drop re-copy of old-table rows newer than the catch-up mark; an epoch-partition (`19700101`) repair that
re-stamps from the source, run after the swap and again before the drop. Either repair finding rows logs a
WARN with the count (it means the delay was too short).

### D8. Row-grain LLM writers: the runner echoes the partition value

An LLM pipeline's rows reach ADAS only through the write-back, which today carries the grain key, the outputs
and provenance. For an aligned target the scan therefore returns the source row's alignment value as
`__partition_v` (beside the declared inputs, like `__signal`), the runner carries it from the scanned row to the
write row, and the write-back writes it into the hidden column. Contract version 3 → 4, so an old runner is
refused as a whole by the exact-match check instead of row by row.

*Cost [estimate]:* one more source column in the scan projection (`request_time` is in `dial_usage_log`'s sort
key, so it is cheap) and one more value per write row; nothing is looked up.

*Reliability [code, runner]:* the runner keeps only the grain key and the mapped inputs per row (`ChunkRow`), so
the value needs a new field there and in the write-row build (`WorkerService.enrichRow`). State built before the
change has no value: queued `enrich-chunk` tasks, and `dlq_item` payloads (`ChunkPayload` re-computed, or
`UpsertPayload` re-posted as built). That state can only target tables that existed before the upgrade, and the
relayout leaves every table with an LLM writer unaligned (F4), so none of it ever targets an aligned table. A row
that still lacks the value is refused with a 422, which the runner dead-letters — refused, never silently lost.

Registration therefore refuses (422) any pipeline onto an aligned target that reads a table other than the
target's own source (Class-1 included: its rows would carry another table's partition value, or none) and any
group-trigger pipeline onto one (group-rows carries no member); an LLM pipeline onto a target whose relayout is
running is refused with 503, since its scans would be built before the alignment it writes into.

*Rejected:* ADAS looking the value up at write-back (a scan of the source's identity column over every partition
per batch — the cost this change removes); refusing row-grain LLM pipelines onto aligned targets (every new
identity-keyed enrichment of a partitioned source would lose the per-row LLM use case); aligning a table only when a
Class-1 pipeline targets it (a hidden rebuild with a data copy behind a pipeline registration, and a layout that
depends on the order things were created in).

## Risks / Trade-offs

- [The pre-selection rewrite returns different rows from the unrewritten statement] → equivalence tests over
  late-materialized rows, `IN (subquery)`, and non-qualifying conjuncts (`= ''`, `NOT IN`); EXPLAIN asserts
  pruning.
- [An old pod outlives the grace delay plus the whole copy and writes unmatched rows after the final repair]
  → accepted residual gap; WARNs make it visible; `Recreate` strategy would remove it.
- [`EXCHANGE TABLES` on `Atomic` unverified] → covered by the Testcontainers default engine; failure before
  the swap leaves the old table in service.
- [A tuple join on the payload enrichment changes RC2 late-materialize statements] → re-run its tests.
- [Multi-day sessions not exercised in the lab] → add a multi-day fixture; cost is linear in active days by
  construction.
- [Larger-scale cost is a linear projection] → re-measure with the `query_log` query against a running deployment.

## Migration Plan

1. One Postgres Flyway migration, V47, creating the relayout-state table (table name, step, resume key, catch-up
   marks, hold flag, error, timestamps). No ClickHouse Flyway migration — the
   relayout is runtime DDL.
2. Deploy ADAS and the runner together (contract version 4; the exact-match check refuses a mismatched pair,
   and the runner's work gate holds its queued work until both are on 4). Existing UCI and `usage_request_baggage` tables migrate by themselves; expect ~3
   failed Class-1 ticks per table during the hold.
3. Re-measure against a running deployment; other deployments need only the deploy.

Deploy skew between the old and new pod is handled by D7.

## Open Questions

- Each deployment's database engine and Deployment rollout strategy (neither blocks: checked at runtime / covered by
  D7).
- Whether any environment has a row-grain LLM pipeline writing a row-grain enrichment of `dial_usage_log`; it
  would stay unaligned and logged.
