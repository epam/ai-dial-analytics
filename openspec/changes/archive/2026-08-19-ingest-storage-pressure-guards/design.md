## Context

A ClickHouse volume can fill up. Capacity is an operator concern, but three contributing factors
are ADAS's own: part pressure from its own
inserts, a retention `TTL` that cannot fire without free disk, and bulk loads that keep retrying into an
exhausted volume. This change lands the service-side half.

Three facts about the current code shape every decision below.

**Where the parts actually come from.** `RowBatchSink` flushes when the buffer reaches `sync.batch-size`
rows **or** `sync.max-batch-bytes` accumulated estimated bytes, and each ingest run flushes once more at
the end. The estimate deliberately over-counts strings ~2× for escaping headroom, so on DIAL rows of
~86 KB the 32 MiB default flushes roughly every 190 rows.

- A **whole-day run** (`DataSyncService.ingestDate`, used by the scheduled reconciliation and by every
  backfill day) accumulates a full day across one sink: ~50k rows ÷ ~190 ≈ **260 flushes**, i.e. ~260
  parts densely into one daily partition. This is the dominant case, and the byte
  bound is exactly the lever for it.
- A **poll cycle** (`BlobPollingService.runCycle`) builds a *fresh* sink per cycle and flushes at the
  end. At tens of rows per minute the byte bound never fires, so normal daily ingest emits **one part per
  cycle-with-rows per table**, regardless of what the bound is set to.

**Why the failure looks generic.** `UsageLogWriter.insert` wraps everything in
`IllegalStateException("ClickHouse insert failed")`; `RowBatchSink` turns that into `FlushException`;
`DataSyncService.ingestDate` catches it and returns `success=false`; `DataBackfillService` counts the day
and moves to the next one. So a full disk reads as an ordinary day failure and a 30-day backfill attempts
all 30 days against a store that cannot accept the first. (The span wrapper in between is transparent:
`ClientSpanTracer.clientSpan` is declared `throws E` and rethrows the body's exception unwrapped, so a
cause chain from the writer reaches the sink intact.)

**Which metrics exist where.** The `sync.*` counters are emitted by `DataSyncService.runOnce` — the
*scheduled* entry point — not by `runForDate`, so the backfill path emits its own `backfill.*` counters
and the poller its own `poll.*`. Any number that must cover all three paths therefore either gets
triplicated at three call sites or moves to the shared write path.

## Goals / Non-Goals

**Goals:**

- Cut the parts a whole-day ingest run creates, without changing batching semantics or the validated
  configuration range.
- Make the service able to report its own part-creation rate, since it cannot query for it.
- Make retention able to run on a full volume, applied identically by Flyway and by an out-of-band
  operator.
- Make a disk-exhaustion write refusal recognisable, and stop a backfill that hits one.
- Keep daily ingest self-healing and keep the backfill capability enabled.

**Non-Goals:**

- Changing the poll cadence (`poll.poll-cron`). See Decision 2.
- A new field on the backfill status response. The terminal state plus the gap between the range total
  and the completed+failed counts already makes an early stop observable; the reason lives in logs and
  metrics. See Decision 5.
- Deleting, dropping, detaching or shortening the retention of any data; the change adds no mutation and
  no `OPTIMIZE`.
- Disabling backfill, or adding any switch to disable it.
- An `ErrorView`/HTTP mapping for a disk-full write on the request-scoped table-management write path
  (`CatalogRowWriter`); it keeps its current generic 500.
- JVM heap sizing. The deployment already sets a container-relative `MaxRAMPercentage`.

## Decisions

### 1. Raise the shipped `sync.max-batch-bytes` default to 128 MiB, and leave batching otherwise alone

`33554432` → `134217728`. A whole-day run then flushes roughly every 760 rows instead of 190: about 65
parts per day instead of about 260 (4×), with the retained row buffer peaking around 64 MiB of real
payload. The writer already streams a batch row by row, so the retained buffer is the entire memory cost
of the bound — there is no multi-copy serialization peak to multiply it.

- **Why not 256 MiB (an 8× reduction)?** It doubles the retained buffer to ~128 MiB
  for one more halving of an already-acceptable part count. 128 MiB keeps the spec's "heap-relative
  default" honest on a constrained container while capturing most of the benefit. Operators with large
  heaps can still raise it; the validated ceiling (`Integer.MAX_VALUE / 6` ≈ 341 MiB) is unchanged.
- **Why not raise `sync.batch-size` too?** At 10,000 rows it never gates these rows — the byte bound
  fires first at ~760 — so moving it would change nothing.
- **No spec text changes for the number.** The requirement constrains the default to be *heap-relative*
  and operator-overridable, not to be any particular value, and 128 MiB satisfies it.

### 2. Leave the poll cadence at one minute, and measure the part rate instead

The poller creates one part per cycle-with-rows per table and the byte bound cannot change that, so the
options were: lengthen `POLL_CRON`, or let the row buffer live across cycles.

Neither earns its cost. One INSERT per minute per table is inside ClickHouse's own guidance (at most
about one insert per second per table); `parts_to_delay_insert` fires when merges are
starved by a full disk, not because the arrival rate is unreasonable. Lengthening the cron trades ingest
freshness for at most a modest reduction in merge write amplification. Buffering across cycles would
avoid the freshness cost but rewrite the poller's load-bearing invariant — the watermark and the
ingested-blob record are committed only after a *successful final flush*, which is what makes a writer
outage re-ingestable and non-duplicating — for the same modest gain.

So instead the change makes the number visible (Decision 3). If the rate is still a problem once the
volume is resized and merges are healthy, that decision can be made against data rather than against
arithmetic.

### 3. Count inserts and insert failures in the writer, attributed to the table

`UsageLogWriter` gains a `MeterRegistry` and emits `clickhouse.inserts{table}` on each successful batch
insert and `clickhouse.insert.failures{table, reason}` on each failure, with `reason` ∈
`storage_full` | `other`.

- **Why the writer and not the three ingest paths?** The alternative was a flush counter threaded
  `RowBatchSink` → `RunSink` → `RunSummary` → three emit sites (`sync.*`, `poll.*`, `backfill.*`),
  which is four more touchpoints for the same number — and it cannot report a failure *reason*, because
  a `RunSummary` only carries `success=false`, which also covers a listing failure. The writer is the
  one place that knows both the table and the exception.
- **Why table-scoped rather than path-scoped, against the existing convention?** Every other ingest
  meter carries a path prefix because rows are attributed to the run that ingested them. Part pressure
  is a property of the *table* — a partition does not care which path wrote to it — and the per-run
  attribution already exists in traces (`clickhouse insert` client spans carry the table). Deviating
  here keeps one meter instead of three that each answer a third of the question.
- **Cost**: one constructor argument. `UsageLogWriterTest` is the only place that constructs
  `UsageLogWriter` by hand.

### 4. `ttl_only_drop_parts = 1` via a new migration on both ingest targets

New `V8__ttl_only_drop_parts.sql`, one `ALTER TABLE … MODIFY SETTING` per table (`dial_usage_log`,
`rate_analytics`). Both carry an identical `TTL toDateTime(request_time) + INTERVAL 1 YEAR` and
`PARTITION BY toYYYYMMDD(request_time)`, so both walk into the same trap, and daily partitioning keeps
expiry exact to within one part: a part holds one day, so a row can outlive its `TTL` by at most a day —
immaterial against a one-year retention, and the deliberate trade for expiry that runs without free disk.

Verified against `clickhouse/clickhouse-server:25.3` (server 25.3.14.14), the pinned test version:

- the server default is `ttl_only_drop_parts = 0`, so this migration is a real change, not a no-op;
- `ALTER TABLE … MODIFY SETTING ttl_only_drop_parts = 1` is accepted on a `ReplacingMergeTree` with a
  `TTL`, and re-running it is accepted too (idempotent);
- it lands in `SHOW CREATE TABLE` and `system.tables.engine_full` as
  `SETTINGS index_granularity = 8192, ttl_only_drop_parts = 1`, which is what the integration assertion
  reads.

- **Why `MODIFY SETTING` and not a `SETTINGS` clause on the `CREATE TABLE`?** An applied migration's
  checksum is fixed, so V1/V2 cannot be edited. `MODIFY SETTING` is metadata-only — no mutation, no part
  rewrite — and it converges a fresh install (V1/V2 then V8) and an existing instance to the same state.
- **Grant implication (operational invariant).** `MODIFY SETTING` is an alias of the `ALTER SETTINGS`
  privilege, a different `ALTER TABLE` sub-privilege from the `ALTER ADD COLUMN` /
  `ALTER MODIFY COLUMN` / `ALTER ADD INDEX` / `ALTER MATERIALIZE INDEX` that V3/V6/V7 exercise. A
  deployment whose writer credential holds ALTER sub-privileges individually rather than wholesale must
  add `ALTER SETTINGS`, or startup fails on V8 (a migration failure aborts startup by design).
- **Why not the generic table-management DDL renderer?** Catalog tables created through `/v1/tables` may
  have a coarse partition key or none, where `ttl_only_drop_parts = 1` would let rows outlive their TTL
  indefinitely rather than by a day. The setting is correct here *because* of daily partitioning, so it
  stays scoped to these two tables rather than becoming a default for every table ADAS can create.

### 5. Classify error 243 by numeric code; the backfill loop acts on a summary flag

A new `ClickHouseStorageFullException` is thrown from `UsageLogWriter.insert` when the cause chain
carries a `ServerException` with code 243 (`NOT_ENOUGH_SPACE`), matched on the numeric code exactly as
`ClickHouseBudget.translate` already matches 159/241 — message text is the more brittle of the two, and
an unrecognised failure falling through to the existing generic path costs the specific classification
and nothing else. The chain-walk is extracted as a package-private static predicate (mirroring the
existing private `isConnectivityFailure`) so it is unit-testable without a live client, which
`insert(...)` requires.

`RowBatchSink` keeps wrapping whatever the writer throws in `FlushException`, cause preserved, so
run-level fault handling is unchanged.

The backfill needs to see it without breaking the contract the other callers rely on. `runForDate` is
documented as never throwing, and both the poller and the scheduled reconciliation depend on that, so
the signal travels as a `storageFull` flag on `DataSyncService.RunSummary` rather than as an exception
escaping `ingestDate`. `DataBackfillService` breaks its day loop on that flag.

`BackfillRun.finish` currently resolves the terminal state as "`FAILED` only if every day failed,
otherwise `COMPLETED`", so an early stop after two good days would have reported `COMPLETED` — the same
value a full-range run reports. It therefore gains a distinct early-stop transition that lands in
`FAILED` regardless of the per-day tallies, and its state javadoc is amended to say so.

- **Why not make `runForDate` throw for this one case?** It would push a new failure path onto two
  callers that currently cannot fail from it, for no gain over a flag they can ignore.
- **Why no `reason` field on the status response?** `daysCompleted + daysFailed < daysTotal` on a
  `failed` run already says "stopped early", the status shape stays frozen, and the reason is one log
  line and one metric away. A field can be added later if operators ask for it.
- **Why not disable backfill while the disk is full?** The capability is wanted. Failing fast is the
  same protection without taking a feature away: a re-trigger costs one day's attempt, not a range's.
- **Poller behavior is unchanged on purpose.** It must keep retrying — holding the watermark and leaving
  its ingested-blob record uncommitted is exactly what lets daily ingest resume unattended when space
  returns, and a re-ingested row collapses on the engine's deduplication key.

## Risks / Trade-offs

- **A 128 MiB bound retains ~64 MiB of rows in heap on a whole-day run** → The writer streams, so there
  is no multiplier on top of the buffer; the value is still ~2.7× under the validated ceiling and is
  overridable downward via `SYNC_MAX_BATCH_BYTES` on a constrained deployment.
- **Fewer, larger inserts mean a larger unit of loss on a failed flush** → Unchanged in kind: a flush
  was always all-or-nothing, and a failed flush already leaves the blobs re-ingestable with dedup making
  the retry idempotent.
- **Rows outlive their `TTL` by up to a day under whole-part expiry** → Accepted and specified; against
  a one-year retention it is noise, and the alternative is retention that cannot run at all on the
  volume that most needs it.
- **V8 fails startup where the writer credential lacks `ALTER SETTINGS`** → Called out above and in the
  migration header so the grant is added with the deploy; failing at startup rather than silently
  skipping is the correct behavior for a provisioning gap.
- **The poll path's part rate is not reduced by this change** → Stated openly and
  now measurable; revisit it with data once merges are healthy rather than pay for it with freshness now.
- **Code-243 matching depends on the driver surfacing `ServerException.getCode()`** → If a driver or
  server change stops it, the failure degrades to the current generic handling: the backfill stops
  aborting early again, but nothing misreports. No behavior depends on the classification being present.
- **A backfill that stops early leaves days unattempted** → Deliberate; the failed state plus the
  day-count gap makes it visible, and the log names the day and the reason.
- **Table-scoped insert meters lose path attribution** → Traces retain it, and the question the meter
  answers (part pressure on a partition) is path-agnostic.

## Migration Plan

1. Land the code, config default, and `V8__ttl_only_drop_parts.sql`; `./gradlew clean build` green
   (integration tests run real Flyway against ClickHouse 25.3, so V8 is exercised on the pinned version).
2. Ensure the writer credential holds `ALTER SETTINGS` on both ingest tables where ALTER sub-privileges
   are granted individually.
3. Deploy. Flyway applies V8 on startup: metadata-only, no mutation, no downtime, and it needs no space
   for a data rewrite (it still writes table metadata, so a filesystem at literally 0 bytes free would
   fail it like anything else).
4. No config action required: the new `sync.max-batch-bytes` default applies wherever
   `SYNC_MAX_BATCH_BYTES` is unset.
5. Run no bulk load against a full volume until it is resized — re-loading a day would write tens of
   thousands of duplicate rows onto a full disk. The guard is verified by tests, not by provoking it on
   a running deployment.
6. **Rollback:** revert the commit. The setting can be reverted independently with
   `MODIFY SETTING ttl_only_drop_parts = 0` and needs no data change either way.

## Open Questions

- After the volume is resized and merges catch up, is the poller's part rate (`clickhouse.inserts` on
  `dial_usage_log`, minus what the scheduled/backfill runs contribute) still high enough to justify a
  cadence or cross-cycle-buffering change? Deferred to that measurement.
- `request_body` is the majority of the stored payload. Whether every row needs its verbatim body kept
  for a year is a product decision, outside the scope of this change.
