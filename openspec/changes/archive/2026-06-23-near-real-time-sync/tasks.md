## 1. Dependencies & configuration

- [x] 1.1 Remove `com.azure:azure-storage-queue` from `build.gradle` (keep `com.azure:azure-identity` — it backs blob managed-identity auth).
- [x] 1.2 Remove `QueueProperties` and the `queue.*` keys from `application.yml`. Add a `PollProperties` (`poll.*`) binding: `enabled` (default true), `poll-cron`, `overlap`, `restart-lookback`, `previous-day-grace`. Wire env vars with `${ENV:default}`.
- [x] 1.3 Update `docs/configuration.md` (remove the queue-consumer table + Event Grid prerequisite; add a `poll.*` table) and `README.md` (replace the queue path description with the provider-agnostic poller; drop the infra prerequisite).

## 2. Provider-agnostic discovery

- [x] 2.1 Extend `BlobClient` with `listWithMetadata(String prefix)` returning each blob's name **and** `lastModified` (read from `StorageMetadata.getLastModified()`), following pagination; the name-only `list` now delegates to it.
- [x] 2.2 Remove the `queue` package: `MessageQueue`, `QueueMessage`, `AzureStorageQueueClient`, `BlobEventParser`.

## 3. Poller

- [x] 3.1 Add a scheduled `BlobPollingService` (enabled by default via `poll.enabled`): resolve the active prefix(es) — current day, plus previous day within `previous-day-grace` — list with metadata, select blobs with `lastModified ≥ watermark − overlap` not already seen this window, ingest each via `BlobIngestor`, then advance the watermark to the greatest `lastModified` observed. Reuse the single-threaded scheduler; never overlap runs.
- [x] 3.2 Initialize the watermark on startup to `now − restart-lookback`; hold it (and the short-lived boundary seen-set) in memory only — no persisted cursor.
- [x] 3.3 Remove `BlobEventConsumer`.
- [x] 3.4 Add poller observability: counters for poll cycles (by outcome), blobs discovered/ingested/failed, rows inserted/skipped/dropped, a duration timer, and a `poll.watermark.lag.seconds` gauge.
- [x] 3.5 Make per-blob ingest failures non-fatal: catch around each blob, count `poll.blobs.failed`, skip and continue, and still advance the watermark — so one corrupt blob cannot wedge the poller. Keep listing-level failures fatal to the cycle (watermark held).

## 4. Tests

- [x] 4.1 Remove `BlobEventConsumerTest`, `BlobEventParserTest`, and the in-memory queue fake.
- [x] 4.2 Add `BlobPollingServiceTest` against the filesystem provider: a blob with `lastModified` after the watermark is ingested; a blob older than the watermark is not; the watermark advances to the newest observed.
- [x] 4.3 Test the overlap path: a blob already ingested this window is not re-read while a newer blob is. Test per-blob failure tolerance: a corrupt blob is skipped and counted while a sibling good blob is still ingested and the watermark advances; and a listing-level failure holds the watermark.
- [x] 4.4 Test `BlobClient.listWithMetadata` surfaces the correct names and `lastModified` from the list response.
- [x] 4.5 `DataSyncServiceTest` unchanged — `BlobClient.list` keeps its signature (delegates to `listWithMetadata`); behavior verified intact.

## 5. Finalize

- [x] 5.1 Update `openspec/specs/data-sync/spec.md` (via archive) and run `openspec validate near-real-time-sync`.
- [x] 5.2 Run `./gradlew clean build` (compile + checkstyle + tests) and confirm it passes.
