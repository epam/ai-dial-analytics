## Why

ADAS today is a read-only gateway: it can list and (eventually) query ClickHouse, but nothing
populates ClickHouse with the analytics data those queries need. DIAL emits per-call usage logs to
Azure Blob Storage (gzipped NDJSON, hive date-partitioned), and that data must land in ClickHouse
before any analytics surface is useful. This change introduces the ingest path — the producer side
that fills the tables the future data-querying feature will read.

## What Changes

- Introduce a new `data-sync` capability: a scheduled, in-process daily job that ingests DIAL usage
  logs from Azure Blob Storage into a pre-existing ClickHouse table (`dial_usage_log`).
- Trigger is a Spring `@Scheduled` daily cron (env-configurable). No HTTP endpoint is added; the
  single-threaded scheduler guarantees runs cannot overlap.
- Each run resolves a **date-derived blob prefix** (`logs/{yyyy}/{MM}/{dd}/`, default = yesterday)
  and processes every blob found under it. Files are addressed by prefix, not exact key, because the
  blob name carries an unpredictable UUID suffix.
- Pipeline per blob: dedup-check → download (jclouds) → streaming gunzip + NDJSON parse → transform
  → batched ClickHouse `INSERT`.
- **Idempotency**: a blob is skipped if its name already appears in the table's `_source_file`
  column, making daily and post-restart re-runs safe without duplicating rows.
- **PII guard**: the three heavy payload fields (`assembled_response`, `request.body`,
  `response.body`) are dropped during transform and never written to ClickHouse.
- Malformed NDJSON lines are skipped with a warning and counted; they do not fail the run.
- Add Azure Blob access via Apache jclouds (`azureblob` provider), mirroring the DIAL ecosystem's
  provider-agnostic storage approach.
- Add a **separate ClickHouse writer credential** for the INSERT path so the existing
  read/discovery path stays strictly read-only.
- **BREAKING (convention, not API)**: amend `openspec/project.md`'s "ClickHouse access is read-only"
  operational constraint to permit a dedicated, separately-credentialed writer used solely by
  `data-sync`. The read gateway's read-only posture is unchanged.
- Ship the `dial_usage_log` `CREATE TABLE` as a documentation artifact (`.sql`); the service assumes
  the table pre-exists and does not run DDL.
- Expose ingest observability via logs + Micrometer metrics (no HTTP status surface).

## Capabilities

### New Capabilities
- `data-sync`: Scheduled daily ingestion of DIAL usage logs from Azure Blob Storage into ClickHouse —
  prefix-based file discovery, `_source_file` dedup, PII-field dropping, malformed-line skipping,
  batched inserts, and ingest metrics.

### Modified Capabilities
<!-- No existing capability's REQUIREMENTS change. table-discovery behavior is untouched.
     The read-only ClickHouse note in project.md is a project-convention amendment, captured in
     design.md and applied at apply-time, not a spec requirement change. -->

## Impact

- **New dependency**: `org.apache.jclouds:jclouds-allblobstore` (+ filesystem provider for tests) for
  provider-agnostic blob access. Pin per `build.gradle` `constraints {}` conventions if the BOM does
  not manage it.
- **New code** (base package `com.epam.aidial.analytics`): `service/DataSyncService` (scheduled
  orchestrator), `blob/BlobClient` (jclouds wrapper), `sync/LogParser`, `sync/RowMapper`,
  `clickhouse/UsageLogWriter`, plus `configuration` properties for blob + writer + scheduling.
- **Unchanged code**: `ClickHouseClientWrapper` and the `/v1/tables` read path keep their read-only
  posture.
- **New config / env vars** (documented in `application.yml` + `README.md`): `SYNC_CRON`,
  `SYNC_DAY_OFFSET`, `SYNC_BATCH_SIZE`; `BLOB_PROVIDER`, `BLOB_IDENTITY`, `BLOB_CREDENTIAL`,
  `BLOB_BUCKET`, `BLOB_ENDPOINT`; `CLICKHOUSE_WRITER_USER`, `CLICKHOUSE_WRITER_PASSWORD` (writer
  credential supplied from the deployment secret store).
- **External systems**: adds an outbound dependency on Azure Blob Storage (a storage account,
  container `logs`) and a write grant on the target ClickHouse database for the writer user only.
- **New failure modes**: blob unreachable / auth failure surface via logs + metrics (the run fails
  cleanly and is retried next schedule); no new HTTP error contract since there is no endpoint.
- **Docs**: new `dial_usage_log` DDL artifact; `project.md` operational-constraint amendment.
