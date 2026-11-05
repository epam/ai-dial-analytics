## 1. Ledger attribution

- [x] 1.1 ClickHouse migration `V17`: add `backfill_run_id String` to `_dial_internal_failed_ingest`, and add
  the column to `ClickHouseIngestFailureLedger.COLUMNS` for startup validation.
- [x] 1.2 `IngestSource(path, backfillRunId)`. `IngestFailureEvent` and `OpenIngestFailure` gain the run id or ids.
  `DataSyncService.runForDate` takes an `IngestSource`, and `DataBackfillService` passes its run id.
- [x] 1.3 Aggregate a subject's distinct run ids after its latest `resolved` event, in ClickHouse and in the in-memory
  test ledger. Integration-test the aggregation.

## 2. API

- [x] 2.1 List: `total`, `by_state` (disregarding `state`), `retry_sweep`, `backfill_run_ids` on entries, and a
  `backfill_run_id` parameter.
- [x] 2.2 Retry: `targets` and `backfill_run_id` members, validated, and `retry_sweep` in the response.
- [x] 2.3 `IngestFailureService.retrySweepStatus()` from `sync.retry.enabled` and `sync.retry.cron`.
- [x] 2.4 Controller, service and auth tests.

## 3. Docs and verification

- [x] 3.1 Upgrade guide: the new operator surface.
- [x] 3.2 `./gradlew clean build` passes.
