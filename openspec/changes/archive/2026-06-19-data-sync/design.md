## Context

ADAS is currently a read-only gateway over ClickHouse (the `table-discovery` PoC: `GET /v1/tables`).
This change adds the opposite-direction path — ingest — so ClickHouse is actually populated with the
DIAL usage data that the future query API will serve.

The source is DIAL usage logs in Azure Blob Storage: gzipped NDJSON, one record per upstream LLM
call, hive-partitioned by date. Container `logs`, key shape
`logs/{yyyy}/{MM}/{dd}/date=<YYYY-MM-DD><digits>-<uuid>.log.gz`. The UUID suffix is not predictable,
so files can only be found by listing a date prefix. A sample record's fields: `apiType`,
`chat.id`, `project.id`, `user.{id,title}`, `token_usage.{prompt_tokens,completion_tokens,total_tokens}`,
`deployment`, `execution_path[]`, `trace.{trace_id,core_span_id}`, `request.{protocol,method,uri,time}`,
`response.{status,upstream_uri}`, plus three large content fields — `assembled_response`,
`request.body`, `response.body` (~30KB embedding/text blobs).

Constraints that shape the design:
- `project.md` pins ClickHouse access as **read-only** — ingest needs INSERT, so this must be
  resolved deliberately, not by relaxing the existing client.
- `project.md` defers PII/tenancy handling — so prompt/response content must not be persisted.
- Spring Boot 4 / Java 21; env-driven config; `./gradlew clean build` (checkstyle + tests) gates done.
- One-component-per-edge pattern already established by `ClickHouseClientWrapper`.

Scope is iteration 1: one day's files, into a local ClickHouse. The near future (multiple/larger
files per day) is anticipated by the design but not built now.

## Goals / Non-Goals

**Goals:**
- Reliably ingest a day's DIAL usage logs into a pre-existing `dial_usage_log` table on a daily
  schedule, unattended.
- Make repeated runs (daily cadence, restarts, retries) idempotent — no duplicate rows.
- Persist no prompt/response content (no PII store).
- Keep the existing read/discovery path strictly read-only.
- Be forward-compatible with "more and bigger files per day" without a redesign.

**Non-Goals:**
- HTTP trigger, async job model, or persisted job store.
- Multi-day backfill / historical replay.
- Content analytics over the dropped payload fields.
- Engine-level dedup (`ReplacingMergeTree`), auth, or managed-identity blob auth.
- Service-managed DDL / schema migration.

## Decisions

### D1 — Trigger: Spring `@Scheduled` daily cron (no HTTP endpoint)
A single `@Scheduled(cron = ${SYNC_CRON})` method orchestrates the run. Spring's default scheduler is
single-threaded, so a run cannot overlap a still-running one — concurrency control is free and no
HTTP surface, job registry, or 409 handling is needed.
**Alternatives:** `POST /v1/sync` synchronous (simple but manual, no automation); async endpoint +
job registry + `GET /v1/sync/{id}` (rejected — needless surface for an unattended daily load).

### D2 — File selection: list a date-derived prefix, not an exact key
Each run computes the target date as `today − SYNC_DAY_OFFSET` (default 1 = yesterday, since a day's
logs are complete after midnight), builds the prefix `logs/{yyyy}/{MM}/{dd}/`, lists it, and
processes every blob found. Today the prefix returns one file; this is exactly the "multiple files
per day" future arriving early at zero extra cost.
**Alternatives:** fixed blob key in env (rejected — the UUID suffix is unpredictable, and a fixed
key re-loaded daily is the worst case for duplication).

### D3 — Idempotency: `_source_file` skip-check (plain MergeTree)
Before ingesting a blob, the service queries whether its name already exists in the target table's
`_source_file` column; if so the blob is skipped. Every inserted row stamps `_source_file`. This
makes daily/restart/retry re-runs safe with one cheap query per file and no schema cost beyond the
provenance column.
**Alternatives:** `ReplacingMergeTree` (async, eventual dedup; heavier, deferred); accept duplicates
(non-viable once the job is an unattended daily cron).

### D4 — Drop heavy/PII fields in transform
`assembled_response`, `request.body`, `response.body` are dropped during mapping and never reach
ClickHouse. This keeps the table lean and avoids creating a prompt/response content store that
`project.md` explicitly defers.
**Alternatives:** truncate/hash (keeps a fingerprint, still content-derived); keep full (bloat + PII
surface). Both rejected for iteration 1.

### D5 — Blob access via Apache jclouds (`azureblob`)
Use `jclouds-allblobstore` with provider `azureblob`, `identity` = storage account name, `credential`
= storage account key, `bucket` = container. This mirrors **ai-dial-core**, which is the DIAL
component that actually owns blob storage and accesses it through jclouds via its `Storage` model
(`provider`/`identity`/`credential`/`bucket`). (Note: ai-dial-admin-backend does *not* connect to
blob — it reaches resources over HTTP through DIAL Core and only vendors core's `Storage` class as
unused leftover; it is not a reference for this.) The jclouds approach is provider-agnostic —
switching to s3/gcs/filesystem is config-only, which also makes tests easy (filesystem provider).
Wrapped behind a single `blob/BlobClient`, the only component that knows about jclouds.
**Alternatives:** `com.azure:azure-storage-blob` SDK — Azure-native with a cleaner managed-identity
path, but Azure-only and diverges from DIAL convention. Rejected for ecosystem consistency +
multi-cloud; managed identity is deferred anyway.
**Auth (resolved — mirrors ai-dial-core):** uses EPAM's jclouds fork (`2.7.3`, GitHub Packages) +
`azure-identity`, with a `CredentialProvider` wired via `ContextBuilder.credentialsSupplier`. The
`AzureCredentialProvider` uses **shared key** when account name + key are both set, else falls back to
**Azure AD / managed identity** via `DefaultAzureCredential` (bearer token for
`https://storage.azure.com/.default`, refreshed before expiry). No SAS. This requires the fork —
upstream Apache jclouds azureblob is shared-key only. Building therefore needs GitHub-Packages creds
(`gpr.user`/`gpr.key` or `GPR_USERNAME`/`GPR_PASSWORD`).
Per ai-dial-core's `storage-requirements.md`, the managed-identity path also requires the jclouds
overrides `jclouds.azureblob.auth=azureAd` + `jclouds.oauth.credential-type=bearerTokenCredentials`
and the account `endpoint`. `BlobClient` **auto-applies those overrides** when azureblob runs without a
shared key (operator `blob.overrides.*` still win) and warns if `blob.endpoint` is unset, so managed
identity works with only `BLOB_ENDPOINT` configured. Operational requirements (not enforced by code):
**Storage Blob Data Contributor** role on the UAMI, Workload-Identity pod setup, and **HNS disabled**
on the account.

### D6 — Separate ClickHouse writer credential
Ingest needs INSERT, which conflicts with the read-only constraint. Resolve by provisioning a
dedicated writer credential (`clickhouse.writer.*` → `CLICKHOUSE_WRITER_USER` /
`CLICKHOUSE_WRITER_PASSWORD`) used only by a new `clickhouse/UsageLogWriter`. The existing
`ClickHouseClientWrapper` keeps its read-only posture, untouched. This requires amending the
"ClickHouse access is read-only" note in `project.md` to allow a separate, write-scoped ingest
credential — applied as part of this change.
**Alternatives:** reuse one credential with write grants (rejected — collapses the read-only posture
the gateway relies on).

### D7 — Batched, streaming inserts
Process each blob as a streaming gunzip + line-by-line NDJSON read, accumulating rows into a buffer
of `SYNC_BATCH_SIZE` (default 10000) and flushing per batch and at EOF. Bounded memory regardless of
file size — built this way from the start to absorb the bigger-files future.

### D8 — `response.status` kept as String; malformed lines skipped + counted
`response.status` is stored as `String` (no numeric cast — avoids losing/rejecting non-numeric
values). A line that fails to parse is logged at WARN, counted in a skipped-rows metric, and skipped;
it never aborts the file or the run.

### D9 — Observability: logs + Micrometer metrics only
Metrics: `sync_runs_total`, `sync_files_processed`, `sync_files_skipped`, `sync_rows_inserted`,
`sync_rows_skipped`, `sync_duration`, `sync_last_success_timestamp`. Surfaced via the existing
Actuator/Prometheus registry. No bespoke HTTP status endpoint (consistent with D1).

### D10 — DDL as a documentation artifact
Ship `dial_usage_log` `CREATE TABLE` as a `.sql` doc (flattened columns, `MergeTree`,
`PARTITION BY toYYYYMMDD(request_time)`, `ORDER BY (project_id, deployment, request_time)`, plus
`_source_file` / `_ingested_at` provenance). The service assumes the table exists and issues no DDL.

### Component layout
```
service/DataSyncService   @Scheduled orchestrator: resolve prefix → list → per blob:
                          dedup-check → download → parse → map → batch insert → metrics
blob/BlobClient           jclouds azureblob wrapper: list(prefix), openStream(key)
sync/LogParser            streaming gunzip + NDJSON; skip+warn on malformed
sync/RowMapper            record → row; drops PII fields; status as String
clickhouse/UsageLogWriter batched INSERT via writer credential; existsBySourceFile(name)
configuration/            BlobProperties, ClickHouseWriterProperties, scheduling enablement
```

## Risks / Trade-offs

- **Same-day partial re-run after a crash** → the `_source_file` check is per-file, so a file that
  was mid-insert when the process died could leave a partial set and then be skipped on retry
  (seen as already-present). Accepted for iteration 1 (single small file); a future
  `ReplacingMergeTree` or per-file transactional marker addresses it.
- **`_source_file` check cost grows with table size** → it's one indexed-ish scan per file per day;
  fine at PoC scale. If file counts explode, maintain a dedicated "ingested files" table instead of
  scanning the fact table.
- **Writer credential widens ClickHouse access** → mitigated by isolating it to a single
  write-scoped user used only by `UsageLogWriter`; the read path is unchanged and the constraint
  amendment is explicit in `project.md`.
- **jclouds is heavier / less Azure-native than the Azure SDK** → accepted for multi-cloud + DIAL
  consistency; managed identity is out of scope for now.
- **Schema drift in upstream logs** (new/renamed fields) → unknown fields are ignored by the mapper
  and missing fields map to defaults; genuinely malformed lines are skipped + counted rather than
  failing the run.
- **Timezone of the day offset** → prefix date is derived in a defined zone (UTC, matching the log
  partition convention); document the assumption so "yesterday" is unambiguous.

## Migration Plan

1. Operator applies the `dial_usage_log` DDL artifact to the target ClickHouse (one-time).
2. Provision a write-scoped ClickHouse user; supply `CLICKHOUSE_WRITER_*` via the secret store.
3. Supply `BLOB_*` credentials (account name/key, container) via the secret store.
4. Deploy with `SYNC_CRON` set; the first scheduled run ingests the previous day's prefix.
5. **Rollback:** unset/disable the schedule (no HTTP surface to remove). No data migration; the
   table is operator-owned and ingest is additive + idempotent, so a bad deploy can be reverted and
   re-run safely.

## Open Questions

- **Blob auth mode — DECIDED: option (b), mirror ai-dial-core** (EPAM jclouds fork + `azure-identity`
  + `DefaultAzureCredential`; supports both shared-key and managed identity). Implemented in
  `BlobClient` / `blob/credential/*`. Two real-world follow-ups remain: (1) confirm whether
  the storage account uses shared-key or managed identity, and supply the matching `BLOB_*` config (the code
  handles either); (2) ensure CI and operators have GitHub-Packages credentials, since the fork won't
  resolve without them.
- Non-blocking defaults baked in for review: `SYNC_DAY_OFFSET=1`, `SYNC_BATCH_SIZE=10000`,
  prefix-date timezone = UTC. Adjust during apply if operations prefer otherwise.
