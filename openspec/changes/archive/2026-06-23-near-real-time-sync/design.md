## Context

`DataSyncService` runs daily at 01:00 UTC, lists the previous day's blob prefix, and ingests each
file via `BlobIngestor` (stream → parse → map → batch-insert). Deduplication is the target table's
`ReplacingMergeTree` engine (`trace_id` + `core_span_id`); the service skips nothing. The new
requirement is near-real-time ingestion.

The prior revision of this change added an Azure Event Grid → Storage Queue → consumer path. It met
the latency target but bound the service to Azure-specific infrastructure and SDKs. Since the
service's clients run on heterogeneous infrastructure and the service already isolates the backend
behind jclouds `BlobStore`, this revision keeps discovery **inside the provider-agnostic seam** and
drops the per-cloud event path entirely.

Two facts make a poll-and-filter design both correct and simple:

1. `BlobStore.list(prefix)` returns a `PageSet<StorageMetadata>`, and `StorageMetadata.getLastModified()`
   is populated **from the list response** on `azureblob` and `s3` (and the `filesystem` test
   provider) — no per-blob `HEAD`. Log blobs are write-once, so `lastModified` ≡ creation time.
2. `ReplacingMergeTree` (from `harden-usage-log-schema`) collapses duplicate spans on merge, so
   re-ingesting a blob is harmless. The poller can therefore over-scan (overlap, restart re-scan)
   without producing duplicate rows and without any app-level dedup or persisted cursor.

## Goals / Non-Goals

**Goals:**

- Ingest a new log blob within seconds–minutes of it being finalized.
- Stay **provider-agnostic**: depend only on the jclouds listing abstraction — no cloud-specific
  notification service (Event Grid / S3 events / Pub/Sub) and **no external infra prerequisite**.
- Be correct under clock skew, last-modified ties, overlap, and restarts — by relying on engine
  deduplication rather than exactly-once discovery.
- Reuse the existing parse → map → batch-insert pipeline, mapping, schema validation, and table
  unchanged.

**Non-Goals:**

- Sub-second / true-streaming latency.
- Any per-cloud event integration (explicitly removed — not "deferred").
- An HTTP trigger for ingest.
- A persistent watermark store / exactly-once delivery.
- Replacing blob storage with a streaming bus, or any producer-side change.

## Decisions

### Decision 1: Discover by last-modified watermark, not by name and not by events

Each poll lists the active prefix and selects blobs whose `lastModified` is `≥ watermark − overlap`.

- **Not by name.** Blob names embed an *event-time* epoch while writes lag by hours, so lexicographic
  name order ≠ arrival order; a name high-water-mark would skip late-arriving blobs whose name sorts
  below it — silent data loss. Last-modified reflects actual write time, so a late blob is still
  caught.
- **Not by events.** Event Grid / S3-events / Pub/Sub each couple the service to one cloud and require
  provisioned infra. The whole point of this revision is to avoid that.

`lastModified` comes back in the list response, so filtering is purely client-side over data we
already fetch.

### Decision 2: Scan the active date-derived prefix(es); accept full-prefix listing at minute cadence

The poller resolves the **current day** prefix (`logs/{yyyy}/{MM}/{dd}/`, offset 0) and, within a
configurable post-midnight grace window, also the **previous day** prefix, then filters the listing by
last-modified. Neither Azure nor S3 can filter a list server-side by time or resume at an arbitrary
key (Azure's `marker` is an opaque single-pass continuation, not a blob name), so a poll lists the
whole day prefix. That is acceptable because the target is *minutes*, not sub-second: polling every
~30–60s (not every 5s) keeps list volume modest, and listing pages at the provider's max page size.
Cost is bounded by the poll interval and by scanning only the current/previous day — never the whole
container. (If the producer ever exposes an hour-level folder, narrowing to current+previous hour
makes cost flat regardless of day size; not required.)

### Decision 3: In-memory moving watermark + overlap; correctness via engine dedup

The poller holds an in-memory `watermark` (greatest `lastModified` ingested) plus a short-lived
seen-set of names at the watermark boundary to avoid redundant re-reads. Each poll ingests blobs with
`lastModified ≥ watermark − overlap` that are not already in the seen-set, then advances the watermark.
The seen-set and overlap are *optimizations*; **correctness rests on the table's `ReplacingMergeTree`**
— any blob re-ingested across the overlap, a tie at equal `lastModified`, or after a restart is
collapsed on merge. On startup the watermark is initialized to `now − restart-lookback`, so a restart
re-scans a bounded recent window and the engine absorbs the overlap. No cursor is persisted.

### Decision 4: Reuse `BlobIngestor`; extend `BlobClient` to expose `lastModified`

`BlobIngestor.ingest(blobName)` is reused verbatim — discovery is the only thing that changes.
`BlobClient` gains a listing that returns each blob's name **and** `lastModified` (e.g.
`List<BlobListing> listWithMetadata(prefix)` reading `StorageMetadata.getLastModified()`), keeping
jclouds the single component aware of the backend. The poller is a `@Scheduled` loop (reusing the
existing single-threaded scheduler + overlap guard), mirroring `DataSyncService`.

### Decision 5: Retain the daily listing run as a reconciliation safety net

The daily full-prefix sweep stays (previous-day target, unchanged) as a backstop for downtime longer
than the poller's restart-lookback window. Both paths dedup via the engine, so they compose without
duplicates.

### Decision 6: Poller enabled by default

Unlike the queue consumer (which was off by default because its infra had to be provisioned first),
the poller has **no external prerequisite**, so it ships **enabled by default** as the primary
near-real-time path. Operators can disable it via the enable flag; the daily sweep still ingests.

## Risks / Trade-offs

- **Full day-prefix listing each poll grows through the day** → *Mitigation:* minute-cadence (not
  5s), scan only current/previous day, page at provider max. Far cheaper than running and securing a
  per-cloud event pipeline; still O(blobs-in-day) per poll, which is the accepted cost of staying
  provider-agnostic.
- **Latency = poll interval** (seconds–minutes), not sub-second → in-scope per Goals.
- **Clock skew / last-modified granularity ties** → overlap window + engine dedup (Decision 3).
- **Restart re-scans the lookback window** → engine dedups the overlap; no duplicates, no loss within
  the window. Downtime beyond the window is covered by the daily reconciliation sweep.
- **Relies on jclouds populating `getLastModified()` from the list response** → true for `azureblob`,
  `s3`, and `filesystem`; documented as an assumption and covered by a test against the filesystem
  provider.

## Migration Plan

1. Ship the poller (enabled by default) and remove the queue code, config, and dependency.
2. No external infrastructure to provision — the poller works wherever blob access already works.
3. **Rollback:** set the poller enable flag false; the daily reconciliation sweep still ingests, so
   nothing regresses.

## Open Questions

- Default poll interval, overlap, and restart-lookback values — tune against blob volume and the
  producer's write lag (e.g. interval 60s, overlap 5m, restart-lookback 1h).
- Length of the post-midnight grace window for also scanning the previous-day prefix.
- Whether to persist the watermark (to avoid the restart re-scan) — chosen *not* to for now, since
  the engine makes the re-scan harmless and statelessness is simpler.
