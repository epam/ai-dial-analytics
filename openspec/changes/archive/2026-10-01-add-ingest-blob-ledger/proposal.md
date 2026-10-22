## Why

The platform writes every usage-log row at least twice: the poller ingests a blob as it appears, and the
daily reconciliation run lists the whole previous day and re-ingests every blob again. Nothing remembers
which blobs were already ingested, so the second copy is a full duplicate (roughly half of each settled
partition, and tens of GiB for `dial_usage_log_payload`); a poller restart replays its lookback window the same
way, and an operator backfill always re-ingests its whole range. `ReplacingMergeTree` collapses these only at
merge time, which is why settled partitions are force-merged. Log blobs are expected to be write-once (and
a rewrite is detectable by its last-modified time), so "this blob was ingested" is a sound, cheap fact to
persist and the duplicates need never be written.

## What Changes

- Add a persisted **ingest ledger**, a ClickHouse table `_dial_internal_ingested_blob` beside the data it
  vouches for: one row per fully ingested blob, with its key (`blob_key`), the store's last-modified time
  (`blob_last_modified`) and the ingest time. A blob counts as recorded only while its listed last-modified
  time matches the entry, so a blob rewritten under the same key is ingested again.
- A blob is recorded as soon as the rows it produced have been written to every target table — not at the end
  of the cycle or day — so a run that stops part-way keeps everything it finished. A crash or ledger-write
  failure between the ClickHouse write and the ledger write re-ingests that blob once (at-least-once); the
  engine still collapses it.
- The **daily reconciliation run** becomes a diff: list the day, subtract the ledger, ingest only blobs the
  ledger does not record. It still recovers real misses (poller downtime, finalization lag beyond the band).
- The **poller** consults the ledger for candidate blobs, so a restart no longer replays its lookback window.
- The **operator backfill** skips already-ingested blobs by default, so a backfill over ingested days — or a
  re-trigger of a range a stopped run left part-done — writes only what is missing. A new optional request
  field `includeIngested: true` re-ingests the whole range, for repairing lost rows or applying a changed
  mapping to history.
- The ledger is ordinary ingest-path ClickHouse state: a ledger read or write that fails fails the cycle or day
  exactly as a failed insert does, and a deployment missing the table fails startup schema validation.
- Ledger rows expire by table `TTL` after 13 months.
- Reserve the table-name prefix `_dial_internal_` for service-owned tables: no table API may create one (a
  failed catalog create's compensating drop would otherwise be able to drop the ledger).
- **Behaviour changes:** a mapping or target change applies to new data only, and re-ingesting history is a
  backfill with `includeIngested: true` (today the daily run implicitly re-ingests yesterday with the current
  mapping; that stops). A backfill without the new field no longer re-ingests days already ingested, and
  reports zero files processed for a day it skipped entirely.
- One new ClickHouse migration (`V13`); new skip metrics; no new configuration property.
- One backward-compatible API addition (`includeIngested` on `POST /v1/data-sync/backfill`); no other read or
  write contract changes. The "no existence pre-check" rule of `data-sync` and `data-sync-backfill` is relaxed
  to "skip what the ledger records".

## Capabilities

### New Capabilities
<!-- None: the ledger is part of the existing data-sync capability. -->

### Modified Capabilities
- `data-sync`: new requirement for the ingest ledger; "Scheduled daily ingestion", "Near-real-time polling
  ingestion", "Date-derived prefix file discovery", "An insert refused for lack of disk space is classified
  distinctly", "Primary-key deduplication" and "Ingest observability" change (the paths skip what the ledger
  records and record what they write, new skip metrics); "Settled ingest day partitions are force-merged…"
  changes its rationale (days are no longer written twice by design); "Target tables are provisioned by
  versioned ClickHouse migrations" adds the ledger table and its startup validation.
- `data-sync-backfill`: "Operator-triggered date-range backfill" gains `includeIngested` and skips recorded
  blobs by default; "Asynchronous execution with a status endpoint" states that files processed excludes
  skipped blobs; "Per-day failure isolation" updates its rationale; "Backfill observability" adds the skip
  metric.
- `table-management`: new requirement reserving the `_dial_internal_` table-name prefix for service-owned
  tables.

## Impact

- Code: `RunSink` (per-blob write tracking), `BlobIngestor`, `DataSyncService`, `BlobPollingService`,
  `DataBackfillService` and `BackfillRequest` (the new field), `SchemaValidator`, `TableCatalogService` (the
  reserved prefix), a new ledger component and ClickHouse repository.
- Data: new ClickHouse table `_dial_internal_ingested_blob` (migration `V13`); no Postgres change.
- Operations: ClickHouse write volume drops by roughly the reconciliation share; the settled-partition force-merge stays
  as a backstop but has far less to collapse. Deployments that narrow the writer's grants per table add
  `INSERT`/`SELECT` on the ledger table. First deployment has an empty ledger, so the first reconciliation
  after rollout still re-ingests one day (once).
- Docs/specs: `openspec/specs/data-sync/spec.md` and `openspec/specs/data-sync-backfill/spec.md` (including
  their Purpose sections), `openspec/specs/table-management/spec.md`, `docs/clickhouse-server.md`,
  `docs/configuration.md` (stale prose only, no new property), `docs/column-mapping.md`,
  `docs/release-0.2-upgrade.md`, and the backfill OpenAPI descriptions.
- Follow-up (separate change): make the catalog's compensating `DROP TABLE IF EXISTS` drop only a table the
  failed request itself created, closing the same hole for non-catalog tables such as
  `flyway_schema_history`.
