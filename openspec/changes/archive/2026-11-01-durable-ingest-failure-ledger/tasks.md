## 1. Failure ledger storage

- [x] 1.1 Add ClickHouse migration `V17__create_dial_internal_failed_ingest.sql`.
  - Table: append-only `MergeTree`, ordered by `(kind, subject, event_at)`, partitioned by month of
    `event_at`, 13-month TTL, `ttl_only_drop_parts`.
  - Comment: a one-line header linking `openspec/specs/data-sync-failures/spec.md`.
- [x] 1.2 Add the `IngestFailureLedger` interface and its `ClickHouseIngestFailureLedger` implementation.
  - Members: `TABLE` and `COLUMNS` constants; `recordFailure`, `recordResolved` and `recordRequeued` inserts
    through `UsageLogWriter.insert`; the outstanding query (state derivation plus the ingest-ledger semi-join);
    a point read of one day's state; and a count of outstanding subjects by kind and state.
  - Message: truncate it to 512 characters.
- [x] 1.3 Extend `SchemaValidator` to validate the failure-ledger table against `COLUMNS` at startup, as it does
  for the ingest ledger.
- [x] 1.4 Integration tests against ClickHouse:
  - pending, parked and resolved derivation;
  - backoff and next-attempt computation;
  - attempts reset after `requeued`;
  - a blob resolved through the ingest ledger, including a rewritten blob with a later last-modified time;
  - a day resolved by an explicit event.

## 2. Classification and configuration

- [x] 2.1 Add `SyncRetryProperties` (`sync.retry.enabled`, `cron`, `initial-backoff`, `max-backoff`,
  `max-attempts`, `max-per-sweep`), with startup validation, and bare `key: ${ENV:default}` lines in
  `application.yml`.
- [x] 2.2 Add `RecordMappingException`. In `BlobIngestor`, wrap any `RuntimeException` from the parse consumer
  in it, but pass `RowBatchSink.FlushException` and `ClickHouseStorageFullException` through unwrapped.
- [x] 2.3 Add `IngestFailureClassifier` with unit tests for every case:
  - a `ZipException` in the cause chain is `corrupt_gzip`, deterministic;
  - a `RecordMappingException` is `mapping_error`, deterministic;
  - an `EOFException` and any other blob-level exception are `read_error`, transient;
  - day-level failures are `listing_failed`, `prepare_failed`, `write_failed` and `storage_full`, all
    transient.

## 3. Per-blob isolation on the day paths

- [x] 3.1 In `DataSyncService.ingestDate`:
  - catch per blob. A `FlushException` still fails the day. Any other exception is classified, recorded with
    the path, counted in `blobsFailed`, and the loop continues;
  - pass the ingest path (`reconcile`, `backfill` or `retry`) into `runForDate`.
- [x] 3.2 Add `blobsFailed` and `complete()` to `RunSummary`.
  - `runOnce` reports `sync.runs{outcome}` and `sync.last.success.timestamp` from `complete()`, and
    increments `sync.blobs.failed`.
- [x] 3.3 Record a day failure (class `transient`, the reason from where it arose) when `!success`. When a day
  completes without a day-level fault and has an unresolved day failure, write `resolved`. When writing a
  day-failure event fails, hand it to the in-memory unwritten queue from 5.3.
- [x] 3.4 Backfill:
  - add `failedBlobs` to `BackfillRun`, its `Snapshot`, and the backfill status and list DTOs (camelCase,
    following those DTOs);
  - add an `onBlobFailed()` default callback to `IngestProgressListener`;
  - count a day as failed when it is not `complete()`;
  - increment `backfill.blobs.failed`.
- [x] 3.5 Tests:
  - a corrupt blob mid-day does not stop the blobs after it, on reconciliation and on backfill;
  - a day with only blob failures records no day failure but is still named in `failedDates`;
  - a listing failure records a day failure;
  - disk exhaustion records `storage_full` and records no unattempted day;
  - a failure-event write that fails, fails the day.

## 4. Poller

- [x] 4.1 In `BlobPollingService`, record each per-blob failure with the path `poll`. When that write fails,
  fail the cycle so the watermark holds.
- [x] 4.2 Tests: a failed blob is recorded; a blob that keeps failing writes one event per cycle; a failed write
  of a failure event holds the watermark.

## 5. Retry sweep

- [x] 5.1 Add `IngestRetrySweep`, `@Scheduled(cron = "${sync.retry.cron}")`, on the shared scheduler. It drains
  the unwritten queue first, then attempts the due pending subjects, oldest latest failure first, up to
  `max-per-sweep`.
- [x] 5.2 Blob attempt:
  - list by key and keep the exact-name match; with no match, record `blob_missing`;
  - if the ingest ledger records the blob, write `resolved` without reading it;
  - otherwise ingest it through a sweep-scoped `RunSink` with `ledger::record`, flush at the end of the sweep,
    and write `resolved` only for blobs whose rows were written;
  - on failure, record a `failed` event with the path `retry`.
- [x] 5.3 Day attempt: `runForDate(day, SKIP_RECORDED, retry path)`. Add the bounded in-memory unwritten-event
  queue: capacity 1000, oldest discarded with an ERROR log, and an ERROR log when an event is first held.
- [x] 5.4 With `sync.retry.enabled=false`, attempt nothing but still drain the queue and refresh the gauges.
- [x] 5.5 Metrics:
  - `sync.retry.attempts{kind,outcome}`;
  - `sync.failures.recorded{kind,path,reason}`, incremented wherever a failure is recorded;
  - gauges `sync.failures.outstanding{kind,state}`, refreshed per sweep and at startup, and
    `sync.failures.unwritten`.
- [x] 5.6 Integration tests:
  - a transient poller failure is recovered after the overlap window;
  - a failed reconciliation day is retried and resolved;
  - a missing blob is parked;
  - the per-sweep bound is honored;
  - with retries disabled nothing is attempted;
  - a day event held in memory is written by a later sweep with its original time.

## 6. Operator endpoints

- [x] 6.1 Add DTOs in `web/dto/datasync/` (snake_case `@JsonProperty`): `IngestFailureView`,
  `IngestFailureListResponse` (`failures`, `truncated`), `IngestFailureRetryRequest` (`kind`, `state`, `reason`,
  `from`, `to`, `subjects`), and `IngestFailureRetryResponse` (`requeued`).
- [x] 6.2 Add `DataSyncFailuresController`.
  - `GET /v1/data-sync/failures` requires `@RequiresPrivilege(REGISTRY_READ)`. Its filters are validated
    strictly: `400 bad_request` names the parameter, an empty value is rejected, and `limit` must be 1 to 1000.
  - `POST /v1/data-sync/failures/retry` requires `@RequiresPrivilege(DATA_SYNC_OPERATE)`. It refuses a body with
    no member, and `subjects` holds at most 1000 entries.
  - Add OpenAPI annotations.
- [x] 6.3 Tests:
  - controller tests: filter validation, an empty body refused, an unknown member refused, ordering and
    truncation;
  - authorization tests: a read-only admin can list but cannot requeue (`403 forbidden`);
  - `EndpointAuthorizationCoverageTest` still passes;
  - a ClickHouse read failure answers `503 clickhouse_unavailable`.

## 7. Documentation and specs

- [x] 7.1 `docs/configuration.md`: add a `sync.retry.*` table, one row and one line per property.
- [x] 7.2 `docs/clickhouse-server.md`: add `INSERT` and `SELECT` on `_dial_internal_failed_ingest` to the
  data-sync writer grants, and give the new table a short entry beside the ingest-ledger note.
- [x] 7.3 `CLAUDE.md` "What this is": mention the `/v1/data-sync/failures` surface (capability
  `data-sync-failures`) in one clause.
- [x] 7.4 Confirm the delta specs match the shipped behavior (`data-sync-failures`, `data-sync`,
  `data-sync-backfill`) and run `openspec validate durable-ingest-failure-ledger`.
- [x] 7.5 Run the public-repo hygiene sweep from `CLAUDE.md` (`git grep -nIP …` plus the multiline pass) over
  the changed files.

## 8. Verification

- [x] 8.1 `./gradlew clean build` passes.
