## 1. Dependencies & configuration

- [x] 1.1 Add `org.apache.jclouds:jclouds-allblobstore` (and the filesystem provider for tests) to `build.gradle`; pin versions via `constraints {}` if not BOM-managed
- [x] 1.2 Add `BlobProperties` (`blob.*`: provider, identity, credential, bucket, optional endpoint) bound from env
- [x] 1.3 Add `ClickHouseWriterProperties` (`clickhouse.writer.*`: user, password, reusing host/port/database) bound from env, separate from the read `ClickHouseProperties`
- [x] 1.4 Add sync config (`sync.*`: cron, day-offset default 1, batch-size default 10000) and enable scheduling (`@EnableScheduling`)
- [x] 1.5 Document every new env var (`SYNC_CRON`, `SYNC_DAY_OFFSET`, `SYNC_BATCH_SIZE`, `BLOB_PROVIDER`, `BLOB_IDENTITY`, `BLOB_CREDENTIAL`, `BLOB_BUCKET`, `BLOB_ENDPOINT`, `CLICKHOUSE_WRITER_USER`, `CLICKHOUSE_WRITER_PASSWORD`) in `application.yml` and `README.md`

## 2. Blob access (jclouds)

- [x] 2.1 Implement `blob/BlobClient` as the only component that knows about jclouds — build a `BlobStoreContext` from `BlobProperties` (provider `azureblob`, identity/credential/bucket)
- [x] 2.2 Implement `list(prefix)` returning blob names under a date prefix, and `openStream(key)` returning the raw blob input stream
- [x] 2.3 Lazily initialize the context (so the app still boots when blob storage is unreachable) and close it on shutdown (`@PreDestroy`)

## 3. Parsing & transformation

- [x] 3.1 Implement `sync/LogParser` — streaming gunzip + NDJSON line-by-line read from a blob stream
- [x] 3.2 Skip lines that fail to parse: log a WARN and count them; never abort the file or run
- [x] 3.3 Define the row model and implement `sync/RowMapper` — flatten nested fields to columns, drop `assembled_response`/`request.body`/`response.body`, keep `response.status` as String, set `_source_file`
- [x] 3.4 Map missing fields to sensible defaults and ignore unknown fields (forward-compatible with schema drift)

## 4. ClickHouse writer

- [x] 4.1 Implement `clickhouse/UsageLogWriter` using the writer credential (distinct client from the read-only `ClickHouseClientWrapper`)
- [x] 4.2 Implement `existsBySourceFile(name)` dedup check against the `_source_file` column
- [x] 4.3 Implement batched `INSERT` into `dial_usage_log`, flushing every `SYNC_BATCH_SIZE` rows and at end-of-file
- [x] 4.4 Confirm the existing `ClickHouseClientWrapper` / read path remains read-only and untouched in posture

## 5. Sync orchestration

- [x] 5.1 Implement `service/DataSyncService` with a `@Scheduled(cron = ${SYNC_CRON})` entry point
- [x] 5.2 Resolve the target date as `today − SYNC_DAY_OFFSET` (UTC) and build the prefix `logs/{yyyy}/{MM}/{dd}/`
- [x] 5.3 List the prefix; for each blob: skip if already in `_source_file`, else download → parse → map → batch insert
- [x] 5.4 Aggregate per-run counts (files processed/skipped, rows inserted/skipped) and handle blob/ClickHouse failures so the run fails cleanly and retries next schedule

## 6. Observability

- [x] 6.1 Register Micrometer metrics: `sync_runs_total`, `sync_files_processed`, `sync_files_skipped`, `sync_rows_inserted`, `sync_rows_skipped`, `sync_duration`, `sync_last_success_timestamp`
- [x] 6.2 Add structured run-summary logging (start, per-file outcome, final counts, duration)

## 7. DDL & docs

- [x] 7.1 Add the `dial_usage_log` `CREATE TABLE` as a documentation artifact (`.sql`) — flattened columns, `MergeTree`, `PARTITION BY toYYYYMMDD(request_time)`, `ORDER BY (project_id, deployment, request_time)`, `_source_file` / `_ingested_at`
- [x] 7.2 Amend the "ClickHouse access is read-only" operational constraint in `openspec/project.md` to permit a dedicated write-scoped ingest credential used only by data-sync
- [x] 7.3 Update `README.md` with the data-sync overview (schedule, prefix, dedup, drop-PII, operator-applied DDL)

## 8. Tests & verification

- [x] 8.1 Unit-test `RowMapper` (field flattening, PII fields dropped, status as String, `_source_file` set)
- [x] 8.2 Unit-test `LogParser` (gunzip + NDJSON happy path; malformed line skipped + counted)
- [x] 8.3 Test `DataSyncService` end-to-end against the jclouds **filesystem** provider + a stubbed/embedded ClickHouse writer: prefix listing, dedup skip, batching
- [x] 8.4 Run `./gradlew clean build` (compile + checkstyle Google style 180-char + tests) and confirm green
