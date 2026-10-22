## Context

Three paths ingest blobs: `BlobPollingService` (every minute, in-memory watermark and seen-set),
`DataSyncService.runOnce` (daily, the day `SYNC_DAY_OFFSET` names, by default yesterday) and the operator
backfill (`DataBackfillService` → `DataSyncService.runForDate`). All go through `BlobIngestor` into a
run-scoped `RunSink` that buffers rows per target table and flushes on row/byte thresholds, enrichments
before their source. A blob's rows are therefore durable only once every per-table buffer holding them has
flushed — not when `BlobIngestor.ingest` returns. A `RowBatchSink.FlushException` is a cycle/day-level fault
in every path. Log blobs are expected to be write-once (`BlobListing`). The ingest writer holds a dedicated
ClickHouse credential with `INSERT` and `SELECT` on the ingest tables, and the ClickHouse migrations (next
free version `V13`) provision those tables. Dedup is by `ReplacingMergeTree` and stays the correctness
backstop. See proposal.md for motivation.

## Goals / Non-Goals

**Goals:**
- Every path writes a blob's rows to ClickHouse once in the normal case.
- What is parsed and written is unchanged: the only new behaviour is "skip what the ledger records" before
  ingest and "record each blob once its rows are written" after each batch write.
- No new failure domain: the ledger is in the same ClickHouse as the data and fails like it.
- One backward-compatible HTTP change only: the optional `includeIngested` field on the backfill trigger.
- No new configuration property.

**Non-Goals:**
- Exactly-once ingestion. The window between the ClickHouse write and the ledger write stays and is absorbed
  by the engine.
- Retrying a failed reconciliation day automatically (unchanged: the backfill recovers it, now ingesting only
  what the failed run did not finish).
- Removing the settled-partition force-merge or any engine-level dedup.
- Tracking partial progress inside a blob.
- Changing the poller's watermark/band logic.
- Fixing the catalog's compensating `DROP TABLE IF EXISTS` for non-catalog names in general (separate change;
  see Risks).

## Decisions

**D1. The ledger is a ClickHouse table, one row per blob.**

```sql
CREATE TABLE IF NOT EXISTS _dial_internal_ingested_blob
(
    blob_key           String,
    blob_last_modified DateTime64(3, 'UTC'),
    ingested_at        DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(blob_last_modified)
ORDER BY blob_key
TTL toDateTime(blob_last_modified) + INTERVAL 13 MONTH
SETTINGS ttl_only_drop_parts = 1;
```

Provisioned by ClickHouse migration `V13__create_dial_internal_ingested_blob.sql`. Why ClickHouse rather than
PostgreSQL: the ledger is ingest state, meaningful only beside the rows it vouches for, so it shares their
failure domain. If ClickHouse is restored, recreated or loses a database, the ledger goes with it and the
paths re-ingest on their own; a Postgres ledger would keep claiming the rows exist. It adds no new dependency
to the ingest path, expires by `TTL` with no prune code, and the writer credential already holds `INSERT` and
`SELECT` on the ingest tables. The cost is one small insert per flush that completes at least one blob — on
the order of rows ÷ `SYNC_BATCH_SIZE` per day, plus one per busy poll cycle — counted by the existing
per-table insert metrics. Rejected regardless of store: diffing against `_source_file` in the data tables (a
blob larger than one batch can be half written and still look done; blobs yielding no rows never appear; the
scan cost scales with the day); insert-time dedup tokens (batches span blobs, so poller and reconciliation
never produce identical blocks).

**D2. Read by the same prefixes the path listed — no key lists in SQL.** Reconciliation and the backfill list
by the day prefix, the poller by its band (or day) prefixes; the ledger is read the same way.
`IngestLedger.recorded(String prefix)` returns key → `blob_last_modified` for the keys under that prefix, by

```sql
SELECT blob_key, argMax(blob_last_modified, ingested_at)
FROM _dial_internal_ingested_blob
WHERE startsWith(blob_key, {prefix:String})
GROUP BY blob_key
```

`startsWith` on the `ORDER BY` key uses the primary index, the prefix is a bound query parameter (keys come
from the producer and are never concatenated into SQL), and the statement size is constant — an `IN` list of
long keys could exceed `max_query_size`. Day prefixes end in `/` (or, under a blank date layout, are validated
prefix-free by `BlobPrefixResolver`), and band prefixes are emitted under every date directory a band touches,
so a prefix read returns exactly the keys its listing could return. No `FINAL`: an unmerged duplicate, or a
version in another monthly partition (a blob rewritten in a later month), is resolved by `argMax`.
`record(Collection<BlobListing>)` goes through `UsageLogWriter.insert`, which pins `async_insert = 0`, so
`ingested_at` is stamped per insert and the newest entry wins; `blob_last_modified` is rendered as
`yyyy-MM-dd HH:mm:ss.SSS` at UTC (the rendering `WriteTimestampNormalizer` uses), so the millisecond round trip
is exact. Both use the writer credential; the read-only query client stays out of ingest. Read-your-writes
holds because the deployment is a single non-replicated server and an insert is visible once acknowledged.

**D3. "Recorded" = same key and same last-modified (ms). Configuration is not tracked.** A caller treats a
listed blob as recorded only when `recorded(prefix)` returns its key with a `blob_last_modified` equal to the
listing's, truncated to milliseconds. Both sides come from the same store listing, so the comparison is
like-for-like (Azure lists at second precision on both sides). A rewrite bumps the time and the blob is
ingested again by the next path that consults the ledger for it — not by the poller inside its overlap, whose
in-memory seen-set skips by name first (accepted; the next reconciliation run catches it). A listing without a
last-modified time arrives as `Instant.EPOCH` (`BlobClient.toInstant`); `record()` filters such listings out
and the match helper never matches them (the poller never selects them anyway: EPOCH is before any cutoff).
Rejected: ETag/size (the listing carries only name and last-modified); a fingerprint of the targets and
mapping files (it would re-ingest only what the poller band and one reconciliation day happen to see —
neither "everything" nor "nothing"). Mapping or target changes apply to new data only; history is re-ingested
by a backfill with `includeIngested` over the range the operator picks (D7). Behaviour change to document:
today the daily run re-ingests yesterday with the current mapping, so a mapping fix reaches one old day; after
this change it does not.

**D4. Record each blob as soon as its rows are written.** `RunSink` keeps, per run, the blobs whose rows are
not all written yet and, for each, the tables still holding some of them. `BlobIngestor` tells the sink which
blob each row belongs to and when a blob has been read to the end. The moment a blob is complete with no table
pending — at the end of its read if none of its rows is still buffered (including a blob that produced no
rows), otherwise at the successful flush of the last table holding its rows — it becomes "written". Written
blobs are handed, in one batch per hand-over point, to an `onBlobsWritten` callback that records them;
`RunSink.flush()` always performs the hand-over, even when every buffer is empty, so a trailing empty blob is
never stranded. A failed flush hands over nothing, and since a `FlushException` ends the cycle or day in every
path, no later flush can hand over a blob whose rows were lost. No extra flushes are forced, so batching and
target-table part counts are unchanged. This is what lets a backfill re-triggered after a stopped run — or
after the scheduled run failed a day — ingest only what is missing (D7). For the poller it usually coincides
with the cycle's final flush. Rejected: recording at the end of the cycle/day (a stopped run loses every
entry, so the re-trigger re-ingests the whole range); flushing after every blob (one insert per blob per
table, a part explosion); deferring an empty blob until the rows read before it are written (not needed for
correctness, and it strands trailing empty blobs).

**D5. The ledger fails like any other ClickHouse statement on the ingest path.** It lives in the same server
as the target tables, so "ledger unavailable" means "ClickHouse unavailable": a separate fail-open path would
protect nothing. A failed `recorded` or `record` throws a cycle/day-level fault — the callback wraps its
failure as a `RowBatchSink.FlushException`-equivalent, so the poller's per-blob `catch` does not blame the blob
being read — and the cycle fails with its watermark held, or the day fails, exactly as on a failed insert.
Blobs whose rows were written but not recorded are re-ingested once by a later cycle or run and collapse in
the engine. No dedicated failure metric: the existing cycle/run outcome and per-table insert-failure metrics
(the ledger's inserts are counted under its own table name) already show it. Rejected: an
`INGEST_LEDGER_ENABLED` switch (no failure mode left for it to absorb; `includeIngested` covers re-ingest, a
release rollback covers a ledger bug).

**D6. Poller: seen-set first, ledger second.** Per cycle, the poller reads `recorded(prefix)` at most once per
listed prefix — lazily, when the first candidate gets past the seen-set, so an idle cycle issues no read — and
skips the candidates it records (adding them to the seen-set, counting
`poll.blobs.skipped{reason=ledger}`). A blob skipped by the seen-set inside the overlap is not counted, as
today. `poll.blobs.discovered` counts only blobs selected for ingestion. The watermark logic is unchanged,
including `maxSeen` advancing over skipped blobs.

**D7. Every path skips what is recorded; the backfill can opt out with `includeIngested`.** The mode is the
enum `IngestLedger.Mode` (`SKIP_RECORDED`, `INGEST_ALL`).
`DataSyncService.ingestDate` takes an `IngestLedger.Mode` and lists the day with `listWithMetadata` (today it
uses `list`, which drops last-modified). `SKIP_RECORDED` reads `recorded(dayPrefix)`, skips the recorded blobs
and counts them in `RunSummary.blobsSkipped`, and ingests the rest; `INGEST_ALL` ingests every listed blob.
Each caller turns the count into its own metric — `runOnce` into `sync.blobs.skipped{reason=ledger}`, the
backfill into `backfill.blobs.skipped{reason=ledger}` — exactly as the `rows.*` counts are emitted today. Both record per D4. `checkBandShape` still receives every listed name.
The scheduled run always uses `SKIP_RECORDED`. The backfill request gains an optional `Boolean
includeIngested` (absent/`null` → `SKIP_RECORDED`, `true` → `INGEST_ALL`) carried through
`DataBackfillService.start` to `runForDate`; the existing overloads default to `SKIP_RECORDED`. Values are
bound by the standard JSON binding (`"true"` is accepted like `true`); a value it cannot read as a boolean
fails as an unreadable request body, `400 bad_request`. The value is logged at run start and not echoed in
the run status (that contract is unchanged except for the files-processed clarification). `includeIngested`
is the escape hatch for what the ledger cannot see — rows lost to a dropped or restored partition, and
applying a changed mapping to history — and its OpenAPI description warns that re-ingested rows re-fire
on-ingest enrichments and aggregate refreshes. Rejected: the names `ignoreLedger` (exposes an internal noun,
reads like a security bypass) and `force` (does not say what is forced); rejecting string booleans (strictness
nothing else in the API asks for).

**D8. A missing ledger table stops startup, like a missing target table.** `SchemaValidator` (gated by
`SYNC_VALIDATE_SCHEMA`) already `DESCRIBE`s every target table at startup; it also describes
`_dial_internal_ingested_blob` and checks its three columns. It runs after the ClickHouse migrations, so on
the deploy that ships `V13` the table already exists. With validation off, a missing table makes every ledger
read fail and so every cycle and day fail — loud, like a missing target table in the same configuration.
Rejected: a "degrade to no ledger until restart" mode (protects nothing, hides an operator error).

**D9. Retention is the table's `TTL`: 13 months.** No path that reads the ledger for a blob can list it again
after more than a day (reconciliation) or the poller lookback, except the backfill, whose range has no lower
bound. 13 months keeps every entry for longer than the longest target-table TTL (`1 YEAR` on `request_time`;
a blob's last-modified is at or after its rows' request times), so a backfill within the data's retention
always sees the ledger; one reaching further back ingests as before the ledger existed. With monthly
partitions and `ttl_only_drop_parts`, expiry lags by up to a month. No prune code, scheduler or property.

**D10. Not queryable, and not reachable through the tables API.** `_dial_internal_ingested_blob` is created
by migration only and never registered in the table catalog, so neither the REST query surfaces nor Flight
SQL list or read it. The name matters as well. With a plain name such as `ingested_blob`, a caller holding
table-create rights could `POST /v1/tables` with that name; the catalog does not know it, so the request
reaches ClickHouse, `CREATE TABLE` fails because the table exists, and `TableSchemaCreationService`'s
compensating `DROP TABLE IF EXISTS` drops the ledger. Two guards close this:
- the `_dial_internal_` prefix, which the identifier grammar `^[a-z][a-z0-9_]*$` already refuses for any
  user-declared table name;
- an explicit reserved-prefix check in `TableCatalogService.create`, ahead of the grammar check and with its
  own message, so a later relaxation of the grammar cannot reopen it. `POST /v1/tables` and the OTLP sink's
  landing tables (always `otel_<sink>_<signal>`) both create through it. Pipelines never create tables: a
  pipeline target must already exist.

The prefix is the convention for any future service-owned table. Rejected: a fixed deny-list of names (easy to
forget when the next internal table is added).

**New configuration (operational invariants):** none. One migration, ClickHouse `V13`; with migrations disabled the operator applies it by
hand, and D8 stops startup if they forget. No new credential: the writer needs `INSERT` and `SELECT` on
`_dial_internal_ingested_blob` (`SELECT … FROM`, `INSERT INTO`, `DESCRIBE TABLE`), which a `<database>.*`
grant already gives; a deployment that narrows grants per ingest table must add it
(`docs/clickhouse-server.md`).

**New metrics:** `poll.blobs.skipped{reason=ledger}`, `sync.blobs.skipped{reason=ledger}`,
`backfill.blobs.skipped{reason=ledger}`. Ledger inserts appear in the existing per-table insert counters under
`table=_dial_internal_ingested_blob`.

## Risks / Trade-offs

- **Ledger says recorded, ClickHouse lost the data** (a dropped partition, a partial restore) → every path
  skips it by default. A full restore or re-creation takes the ledger with it (D1), so what remains is a
  partial loss, which a backfill with `includeIngested: true` repairs (D7).
- **An operator forgets `includeIngested` when repairing lost rows** → the backfill skips the recorded blobs and
  reports them in the skipped-by-ledger metric and logs; the API description says when to set it.
- **Crash, or ledger write failure, between the ClickHouse write and the ledger write** → one extra copy,
  collapsed by the engine (stated in the spec as at-least-once).
- **Concurrent poller and reconciliation/backfill ingest the same blob** → both write it before either records
  it; harmless duplicates, the newer ledger entry supersedes. Not worth a lock.
- **Ledger part count** → one small insert per flush that completes a blob; tiny monthly-partitioned table,
  merged by the normal selector, visible in the per-table insert metrics.
- **Per-blob tracking in `RunSink` is new state on the write path** (D4) → it only decides when to call the
  ledger, never what is written; covered by unit tests for interleaved table flushes, trailing empty blobs and
  a failed flush.
- **A mapping change no longer reaches the previous day on its own** (D3) → the operator backfills with
  `includeIngested`; documented in `docs/column-mapping.md`.
- **A rewrite inside the poller's overlap, or within one second on a store with second-resolution timestamps**
  (D3) → not detected by the poller / at all respectively; accepted.
- **Replicated ClickHouse** (D2) → read-after-write across replicas is not guaranteed; a just-recorded blob may
  be re-ingested once. Not a supported deployment today.
- **The compensating drop can hit other non-catalog tables** (pre-existing, outside this change). ClickHouse's
  Flyway history table `flyway_schema_history` lives in the same database and its name passes the identifier
  grammar, so a catalog create with that name would fail and then drop it. The reserved prefix protects the
  ledger only; making the compensating drop conditional on this request having created the table is a separate
  follow-up change.
- **First rollout** → ledger empty; the first reconciliation re-ingests yesterday's blobs the poller wrote
  before the deploy (once).

## Migration Plan

Deploy normally; ClickHouse migration `V13` creates the table (additive) before schema validation runs.
Rollback: redeploy the old version; the table is inert and may be dropped.

## Open Questions

Whether to retire the settled-partition force-merge once duplicates are measurably gone is a follow-up decision
based on measurements from a running deployment.
