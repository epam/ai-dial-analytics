## Why

Today the Blob→ClickHouse data-sync runs once daily at 01:00 UTC and ingests the *previous* day's
logs. The new requirement is **near-real-time** ingestion (seconds–minutes).

An earlier revision of this change met that with an event-driven path: Azure Storage `BlobCreated`
events → Event Grid → an Azure Storage Queue → an in-process consumer. That works, but it **couples
the service to one cloud's notification stack** — `azure-storage-queue`, Event Grid event schemas,
and an operator-provisioned subscription + queue + managed-identity role. Deployments run on different
infrastructure (S3, GCS, on-prem object stores); an Azure-only ingest path is not portable, and
re-implementing an equivalent per cloud (S3 events → SQS, GCS → Pub/Sub) multiplies the surface.

The service already abstracts blob access behind **jclouds `BlobStore`** (`azureblob` in deployments,
`filesystem` in tests, `s3`/`gs` available). Near-real-time discovery should live behind *that same
seam* rather than a cloud queue. It can: a short-interval poll that lists the active date-derived
prefix and selects blobs whose **last-modified time** is at/after a moving watermark needs nothing
but `BlobStore.list(...)` — which already returns each blob's `lastModified` in the list response on
both Azure and S3, at no extra call. Filtering by *name* is unsafe here (blob names embed an
**event-time** epoch and writes lag by hours, so name order ≠ arrival order and a name watermark
would silently skip late writes); filtering by **last-modified** is correct regardless of naming,
because a late-landing blob still carries a recent last-modified.

Crucially, the `harden-usage-log-schema` change already moved deduplication into the table's
`ReplacingMergeTree` engine (collapsing on `trace_id` + `core_span_id`). That makes an **overlapping
re-scan free of consequence**: any blob re-observed inside the watermark overlap, on a clock-skew
boundary, or after a restart is simply re-inserted and collapsed on merge. The poller therefore
needs **no exactly-once machinery, no persistent state, and no external infrastructure**.

## What Changes

- **Replace the Azure queue path with a provider-agnostic poller.** A new scheduled poll lists the
  active prefix on a short, configurable interval; selects blobs with `lastModified ≥ watermark −
  overlap`; ingests each through the existing parse → map → batch-insert pipeline; and advances the
  watermark to the greatest last-modified observed. No cloud-specific notification service.
- **Extend the blob-listing abstraction** to surface each blob's `lastModified` (it currently
  returns names only). This is the one new capability the seam needs; it is read from the existing
  list response, with no per-blob `HEAD`.
- **Correctness rests on engine dedup, not on discovery.** Overlap, ties, clock skew, and restarts
  are all absorbed by the table's `ReplacingMergeTree`; the poller keeps only an in-memory watermark
  (and a short-lived seen-set to avoid redundant re-reads), reset to `now − lookback` on restart.
- **Remove the Azure coupling:** drop `com.azure:azure-storage-queue`, the `queue.*` config, the
  Event Grid event parsing, and the queue consumer. (`azure-identity` stays — it backs blob
  managed-identity auth, which is unchanged.) The Event Grid → queue infra prerequisite is gone.
- **Daily reconciliation run retained** as a low-frequency safety net for downtime longer than the
  poller's window. Both paths dedup via the engine, so they compose without duplicates.
- **Reused unchanged:** gzip-NDJSON parsing, the configurable column mapping, batched inserts via
  the writer credential, startup schema validation, the operator-provisioned `dial_usage_log` table
  and its `ReplacingMergeTree` engine.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `data-sync`: **ADD** a *Near-real-time polling ingestion* requirement (provider-agnostic poll of
  the active prefix, last-modified watermark selection, ingest per blob, advance the watermark).
  **MODIFY** *Scheduled daily ingestion* to reframe the daily run as a reconciliation safety net
  complementing the poller. **MODIFY** *Ingest observability* to add poller metrics. The
  parsing/mapping/insert/schema-validation/dedup requirements are unchanged.

## Impact

- **Code:** remove the `queue` package (`MessageQueue`, `QueueMessage`, `AzureStorageQueueClient`,
  `BlobEventParser`) and `BlobEventConsumer`; add a scheduled `BlobPollingService`; extend
  `BlobClient` to return name + `lastModified`. `DataSyncService` (daily reconciliation) and
  `BlobIngestor` are essentially unchanged.
- **Build:** remove `com.azure:azure-storage-queue`. Keep `com.azure:azure-identity` (blob auth).
- **Config/docs:** remove the `queue.*` block and the Event Grid prerequisite; add a `poll.*` block
  (enabled, interval, overlap, restart-lookback, previous-day grace) in `application.yml`; update
  `README.md` and `docs/configuration.md`. The daily `sync.*` block is unchanged.
- **Spec:** `openspec/specs/data-sync/spec.md` updated for the added/modified requirements.
- **Ops:** **no external infrastructure prerequisite** — the poller works on any jclouds-supported
  store (Azure, S3, GCS, filesystem) with the credentials the service already uses for blob access.
