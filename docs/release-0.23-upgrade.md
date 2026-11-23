# ADAS 0.23 — upgrade notes

What an environment has to do to move from **release-0.3** to **0.23**. An environment still on an
earlier build first follows [`release-0.3-upgrade.md`](release-0.3-upgrade.md).

Three things make this release more than a redeploy:

- a **coordinated deploy** with the Enrichment Runner, because the contract version rises from `3` to `4`;
- an **automatic relayout** of existing row-grain enrichments, which holds their writers for two to three
  minutes each, ten minutes after the deploy;
- **new writer grants**: `SELECT` on `system.parts` and `system.data_skipping_indices`, which
  `<database>.*` does not cover, and access to the new failure-ledger table `_dial_internal_failed_ingest`,
  which every ingest path writes. Without the system-table grants the relayout fails; without the ledger
  grant a writer narrowed per table either fails startup or stops ingesting at the first failed blob (§1).

There is **no rollback to 0.3 once a table is aligned** (§3): a 0.3 build writes aligned tables with no
`request_time`, so its rows land in the 1970 partition, which the inherited TTL drops at the next merge.

**0.23.1** is a patch on 0.23.0. It replaces the sensitive-column switch `SYNC_SENSITIVE_COLUMNS_ENABLED` with
`SYNC_SENSITIVE_COLUMNS`, which names the sensitive columns ingest writes. Startup fails while the old variable is
set, so a deployment that set it changes one variable (§8).

**0.23.3** is a patch on 0.23.2. It brings own-rows row policies and caller values, role-keyed table access with
column policies, enrichments whose read access follows their source, and the spending-limit columns. The access
changes are breaking: the old access and row-policy endpoints are removed, and a migration clears the enrichments'
read-side grants. Check who gains and loses access before deploying (§9).

## Why 0.23 and not 0.4

0.23 comes straight after 0.3. There were no releases from 0.4 to 0.22. This is the first release of ADAS and
the Enrichment Runner as open source on GitHub, and both now use the same version numbers as the other DIAL Admin
services, which are also at 0.23. The release contains what was planned as 0.4. The earlier upgrade notes keep
their old numbers.

**The image registry has changed.** Images are now published from the public repositories to a different registry.
Update the image repository in your deployment values for both services. The Helm chart for this release already
points at the new location. The images themselves are built the same way.

---

## 1. Before you deploy

- **Memory per statement.** Leave `PIPELINE_BUDGET_MAX_MEMORY` at its `2GB` default, or at least ~1 GiB: a
  relayout slice needs on the order of 1 GiB. Below that, every slice is refused, the relayout fails and it is retried
  from the beginning on every start.
- **Disk.** The copy needs the table's size again until the previous table is dropped.
- **Database engine and grants.** The database must be `Atomic` or `Replicated` (verified on `Replicated` with ClickHouse
  26.3), otherwise the table is left `layout: unsupported`. The writer account needs the full grant in
  [`clickhouse-server.md`](clickhouse-server.md) — the relayout creates, exchanges and drops tables.
- **New system-table grants.** 0.23 reads `system.parts` and `system.data_skipping_indices` on the writer
  account, which `<database>.*` does not cover. Grant them before the deploy:

  ```sql
  GRANT SELECT ON system.parts TO <writer>;
  GRANT SELECT ON system.data_skipping_indices TO <writer>;
  ```

  Without them the relayout logs `ACCESS_DENIED … SELECT ON system.parts` and fails before the exchange.
  The table stays unchanged and in service; grant, then restart the pod — it retries only on start.
- **Writer reads the payload table.** Ingest now looks up earlier requests in `dial_usage_log_payload` on the writer
  account (request-delta storage, §7). The full grant in [`clickhouse-server.md`](clickhouse-server.md) covers it; a
  writer narrowed per table needs `SELECT` on `dial_usage_log_payload`, and `ALTER` on it for the ClickHouse `V16`
  migration. Without `SELECT` every lookup fails with a WARN and `ingest.request.delta.lookup.failures`, and requests
  are stored as chain roots: ingest goes on, the saving is smaller.
- **Grants for the new failure ledger.** ClickHouse `V17` creates `_dial_internal_failed_ingest` in the ingest
  database (§3b), and every ingest path reads and writes it. A writer granted on `<database>.*` already covers it.
  A writer narrowed per table needs `SELECT` and `INSERT` on it, and `CREATE TABLE` unless that is granted on the
  database:

  ```sql
  GRANT CREATE TABLE ON <database>._dial_internal_failed_ingest TO <writer>;
  GRANT SELECT, INSERT ON <database>._dial_internal_failed_ingest TO <writer>;
  ```

  - Without `CREATE TABLE`, startup fails at `V17`.
  - Without `INSERT`, the first blob that fails to ingest also fails to be recorded, which fails the poll cycle:
    the poller's watermark holds and **near-real-time ingest stops** until the grant is added. A reconciliation or
    backfill day with a failed blob fails as a whole.
  - Without `SELECT`, every retry sweep logs an ERROR and retries nothing, and `GET /v1/data-sync/failures`
    answers `503 clickhouse_unavailable`.

  See [`clickhouse-server.md`](clickhouse-server.md#writer-credential).
- **No new `ALTER` grant for the route columns.** `V15` adds them to `dial_usage_log` (§4) with `ALTER ADD COLUMN`,
  which the documented sub-privilege set already holds on the ingest tables
  ([`clickhouse-server.md`](clickhouse-server.md#narrowing-alter-to-sub-privileges)).
- If `CLICKHOUSE_MIGRATIONS_ENABLED=false`, apply ClickHouse `V15`, `V16` and `V17` by hand, in that order, **before**
  starting the new build. The new build assumes all three are applied.
- **Writer hold.** `PIPELINE_BUDGET_TIMEOUT` sets the hold: it waits this plus 15 s. Raising it lengthens the
  window of `503`s (§3).
- **API description needs a credential in `oidc` mode.** `/v3/api-docs`, `/swagger-ui.html` and `/swagger-ui/**`
  now answer `401` without one. A browser sends no `Bearer` token or `Api-Key` when it loads the Swagger UI, so in
  practice the UI is closed; an API client sending any valid credential still reads the OpenAPI JSON. Set
  `DISABLE_SWAGGER_AUTHORIZATION=true` where the UI is needed and ADAS is not broadly reachable; `/v1` stays
  protected either way. In `none` mode nothing changes.
- **Readiness now includes PostgreSQL.** `/actuator/health/readiness` reports the catalog database (`db`) beside
  the readiness state, so a pod that cannot reach PostgreSQL answers `503` there and leaves Service rotation
  instead of serving errors. A readiness probe on that path sees this; one on the aggregate `/actuator/health`
  sees no change, and neither does any liveness probe. The liveness and readiness groups are now served outside
  Kubernetes too (inside a cluster Spring Boot already enabled them). Point liveness at
  `/actuator/health/liveness`, which consults no dependency, so a PostgreSQL outage never restarts the pod.
  The image's Docker `HEALTHCHECK` now calls that path.
- **Default service name is now `ai-dial-analytics`.** The `OTEL_SERVICE_NAME` default changes from
  `analytics-data-access-service`, so a deployment that leaves it unset exports traces, metrics and logs under the
  new `service.name`. Dashboards, alerts and queries that filter on the old name stop matching. To keep the old
  identity, set `OTEL_SERVICE_NAME=analytics-data-access-service` before the deploy.
- **Flight SQL server name is now `ai-dial-analytics`.** `GetSqlInfo` reports it as `FLIGHT_SQL_SERVER_NAME`, and
  JDBC and ADBC clients show it as the database product name (`DatabaseMetaData.getDatabaseProductName()`).
  Queries and results do not change. The name is fixed, not configurable, so a client that keys a connection
  profile, dialect or driver setting on `analytics-data-access-service` has to match the new name instead.
- **`enabled` is a pipeline's only run control.** A `PATCH` that only changes `enabled` now moves `updated_at` but
  no longer bumps `generation`, so stopping and restarting a pipeline leaves the runner's queued work and the
  pipeline's materialized position valid. The Enrichment Runner build that follows this rule reads the unfiltered
  feed and drops its own operator pause. Before deploying that runner, list `GET /v1/pipelines/paused` on the
  runner and set `enabled: false` here for each entry whose `origin` is `operator`, so a pipeline stopped on purpose
  stays stopped. Do it immediately before that deploy: the runner still deployed drops a disabled pipeline from its
  cache, and the pipeline's dead-letter items lose their stored labels while it stays disabled. See [pipeline-registry](../openspec/specs/pipeline-registry/spec.md), "A pipeline has exactly one
  run control".
- **`roles-mapping` with raw privileges.** The new sessions-source setting (§3e) is read with `SETTINGS_READ` and
  written with `SETTINGS_WRITE`. Every read-only bundle gains `SETTINGS_READ` and `FULL_ADMIN` gains both, but a
  mapping that names raw privileges gains neither: add `SETTINGS_READ` to the roles whose holders use the sessions
  view, or their read answers `403`.
- **Evaluator archive (optional).** 0.23 drops the read-only evaluator archive and the rule history left
  over from the fold (§4). The drop cannot be undone: the dump is the only copy of the definitions as they
  stood before the evaluator fold (0.2.2) and of the pre-fold rule revisions. To keep them, dump the four
  tables first:

  ```bash
  pg_dump --table=evaluator --table=evaluator_version --table=rule_legacy --table=rule_legacy_aud \
    <database> > evaluator-archive.sql
  ```

## 2. Deploy order — ADAS and the Enrichment Runner ship as a pair

The runner must be its 0.23 build. ADAS now requires **`Adas-Contract-Version: 4`** on the four runner-only
endpoints (`scan`, `group-rows`, `materialize`, `rows`), and each side refuses the other's old version. The
contract change: for a per-row LLM pipeline over an aligned enrichment (§3), the scan returns `__partition_v`,
which the runner echoes back on every write-back row.

Follow the usual pair order ([`configuration.md`](configuration.md#release-and-deployment-order)):

1. **Scale the runner to 0** and wait for the pod to be gone.
2. **Deploy ADAS.**
3. **Deploy the matching runner build** (0.23).
4. **Scale the runner back to 1.** Its readiness stays DOWN until the first pipeline sync succeeds, so a Ready
   pod confirms the pair matches.

If the order goes wrong, both services stall and then recover by themselves once both speak contract version
`4`. The runner holds work that comes due during the skew and does not dead-letter it.

## 3. Row-grain enrichments are relaid out by themselves — expect a few failed Class-1 ticks

An enrichment keyed on its source's `identity_column`, over a partitioned source — every `event_id`-keyed
enrichment of `dial_usage_log` — is now **aligned**: partitioned like its source by a hidden `request_time`
column and joined on `(event_id, request_time)`. Lookups by an enrichment value
then read only the days the value occurs on instead of all of history.

- **New tables** are created aligned. `GET /v1/tables/{name}` reports `partition_by` and `layout: aligned`; the
  column itself is not listed. A `request_time` the schema declares as a non-nullable `timestamp` is adopted as it
  is (`usage_request_baggage`). A row written through `POST /v1/tables/{name}/rows` must carry `request_time`.
- **Existing tables** are migrated by ADAS itself, **10 minutes after the pod is ready**, one at a time, with no
  call, property or runbook step. A table written by a per-row LLM pipeline is left as it is
  (`layout: unsupported`, one WARN). With two pods, a Postgres advisory lock lets only one of them run it.
  - What it logs: one INFO line per step under `EnrichmentRelayoutService` (`… started`, `history copied`,
    `writers held`, `tables exchanged`, `catalog aligned`, `… finished`). A WARN mentioning the *previous table*
    or the *epoch partition* means a write from the previous pod was repaired: the rollout outlived the
    10-minute delay. A WARN that rows *have no source row* is expected: rows whose source rows have expired are
    not carried over. An ERROR means the relayout failed (`FAILED`) or kept the previous table for inspection; the
    table stays in service, and the next start retries it — from the beginning when it failed before the exchange,
    from where it stopped otherwise. A pod stopped mid-relayout just releases the hold; the next start resumes it,
    and takes the hold again first when it stopped between the hold and the catalog update.
  - What it costs ClickHouse: it copies one slice of the table's keys at a time (about 256 MiB of uncompressed
    data each), every statement capped at `PIPELINE_BUDGET_MAX_MEMORY` (default `2GB`). A slice needs on
    the order of 1 GiB and runs in seconds against a `dial_usage_log` of tens of millions of rows. The copy
    temporarily needs the table's size again on disk.
  - **For two to three minutes per table its writers are held** — the hold waits out `PIPELINE_BUDGET_TIMEOUT`
    (default `120s`) so a materialize already running finishes first: materialize and row writes answer
    `503 table_relayout_in_progress`. The runner retries from its committed cursor, so expect a few failed
    Class-1 ticks (WARN lines and `runner.class1.tick.failures` increments) per table. **Do not alert on them.**
    A hold whose pod died lapses by itself after 10 minutes.
  - `GET /v1/tables/{name}` reports `layout: migrating` while it runs and `layout: aligned` afterwards.
- A group key or aggregate group key on an aligned column gains a `bloom_filter(0.001)` index, added for new and
  merged parts only (no mutation). A migrated table has it over its whole history.
- A schema `PATCH` of a table whose relayout is running answers `409 conflict`; retry once `layout` reads `aligned`.
- An aligned enrichment inherits its source's TTL unless it declares one. The inherited TTL is not in the catalog,
  so `PATCH …/schema {"ttl": null}` does not remove it; declaring a `ttl` replaces it.
- A declared column named like the alignment column is adopted only when it is a non-nullable column of the source
  column's type and neither `sensitive` nor `heavy`; otherwise define-schema and import answer `422`, and an existing
  table stays `layout: unsupported` until the column is changed.
- Dropping a column that carries a lookup index now drops the index in the same `ALTER`.

## 3a. Fixed along the way

- A source with `granularity: year` could not be created (ADAS rendered `toYYYY`, which ClickHouse does not have);
  it now renders `toYear`.

## 3b. Ingest failures are recorded and retried

Before 0.23 a blob that failed to ingest could go missing from analytics, and only a log line and an aggregate counter
showed it. On the daily reconciliation run and on a backfill, one unreadable or unmappable blob stopped the rest of its
day, and a deterministic failure stopped every re-run at the same blob. On the poller, a failed blob was retried only
while it stayed inside the overlap window.

- **A failed blob no longer stops its day.** Its failure is recorded and the day goes on with the next blob, on every
  path. Only day-level faults still fail a day: the listing or preparing the day fails, a batch or ledger write fails,
  or ClickHouse is out of disk.
- **Failures are recorded in `_dial_internal_failed_ingest`**, an append-only log of failed blobs and days kept for 13
  months. A failure is `deterministic` (a corrupt gzip, a mapping or deriver fault, a blob that no longer exists) and
  **parked** at once, or `transient` (any other, an `EOF` on read included) and retried with exponential backoff until
  `SYNC_RETRY_MAX_ATTEMPTS`, then parked. A blob is `resolved` once the ingest ledger records it; a day, once a run of
  it succeeds.
- **A retry sweep** (`SYNC_RETRY_CRON`, every 15 minutes by default) re-ingests due blobs one by one and re-runs due
  days in the default skip-ingested mode. A failed reconciliation day is now retried by itself; before, only an
  operator backfill recovered it.
- **Operator surface:**
  - `GET /v1/data-sync/failures` (`REGISTRY_READ`) lists `pending` and `parked` entries, newest first, filtered by
    `state`, `kind`, `reason`, `from`/`to`, `backfill_run_id` and `limit`. It reports the `total` matched (with the
    filters of a retry, the count that retry would requeue), the `by_state` counts, and `retry_sweep`: whether the
    sweep runs and when it next starts.
  - `POST /v1/data-sync/failures/retry` (`DATA_SYNC_OPERATE`) requeues the matching outstanding entries with their
    attempts reset, for example `{"state": "parked", "reason": "mapping_error"}` after a mapping fix, or the failures
    of one backfill run with `{"backfill_run_id": "<runId>"}`. `targets` names exact `{kind, subject}` pairs, since a
    blob's `blob` and `records` entries share its key. An empty body is `400`, so nothing is requeued by accident.
- **Backfill status** adds `failedBlobs`. A day with a failed blob is still named in `failedDates`, but its other
  blobs are now ingested.
- **Metrics:** gauge `sync.failures.outstanding{kind,state}`, counters `sync.failures.recorded{kind,path,reason}` and
  `sync.retry.attempts{kind,outcome}`, and gauge `sync.failures.unwritten`: day failures that could not be
  written during a ClickHouse outage and are held in memory until the next sweep (lost on a restart). Alert on
  `sync.failures.outstanding{state="parked"}` above zero: a parked entry stays missing until an operator acts.

Behaviour: [`data-sync-failures`](../openspec/specs/data-sync-failures/spec.md).

## 3c. Values ingest cannot read are counted, and stored as absent instead of `0`

Ingest used to read a `long`, `double` or `bool` value it could not parse as `0` or `false`, silently. It now reads
each kind strictly ([`column-mapping.md`](column-mapping.md#extraction-kinds)):

- A value that fails to read — `"abc"` or `12.7` for `long`, `"yes"` for `bool`, a non-finite or out-of-range number —
  takes the column's fallback as an absent value does: the `default`, else `NULL` on a nullable column, else the kind's
  zero. **A nullable column (the token counts, `rate`, the cost columns) now stores `NULL` where earlier rows stored
  `0`** for the same producer value.
- A fractional value is refused for a `long` column, not truncated. Blank text is absent for every kind but `string`
  and `object`.
- `json-extract-uint` refuses a negative value, and `success-from-status` stores `false` for an unreadable status.
- An `array<string>` element that is an object or array is stored as its JSON text instead of `""`.
- Every failure counts on `sync.values.coercion.failures{table,column,kind}`, with at most one WARN per column per
  minute naming the table, column, kind, JSON type and blob, never the value.

Rows already stored are unchanged. A backfill with `"includeIngested": true` applies the new reading to history, and
re-fires the row-grain enrichments over the re-ingested rows. A counter that keeps rising points at a producer field or
a mapping `kind` that does not match. The DIAL Core fields the shipped mappings read are expected to stay at zero.

## 3d. Sensitive columns kept out of ingest, opt-in

New `SYNC_SENSITIVE_COLUMNS_ENABLED` (see [`configuration.md`](configuration.md#data-sync-schedule)). Under `false`,
ingest stops writing the columns the catalog flags `sensitive` on each target table, and new rows hold their
ClickHouse defaults there (see [`data-sync`](../openspec/specs/data-sync/spec.md)). Nothing changes under `true`, the
default.

- **Under `false`, startup fails** when a target table has no catalog definition, or when a column it would leave
  out is a deduplication-key, grain, identity, ordering-key, partition, version or TTL column.
- Rows already stored keep their values, and switching back to `true` restores the columns on new rows only. Under
  `SYNC_REQUEST_BODY_MODE=delta`, a parent re-ingested under `false` loses its body, so its delta children can no
  longer be rebuilt.
- **Rollback.** A 0.23 release candidate or an earlier build ignores the variable and writes sensitive columns
  again.

## 3e. Sessions-source setting

New endpoints `GET` and `PUT /v1/settings/sessions-source` store which source table holds session rows and which of
its fields play the session roles (see [`instance-settings`](../openspec/specs/instance-settings/spec.md)). Reading
needs the new privilege `SETTINGS_READ`, which every read-only bundle carries; writing needs `SETTINGS_WRITE`, which
only `FULL_ADMIN` carries. `GET` answers `404 setting_not_found` until a setting is stored, so store one with `PUT`
after the deploy wherever the sessions view is used. Postgres `V53` creates the backing table at startup.

- **A `roles-mapping` that names raw privileges** gains neither privilege (§1).
- **Rollback.** A 0.23 release candidate or an earlier build knows neither the setting nor its privileges. Before
  rolling back:
  - Remove `SETTINGS_READ` and `SETTINGS_WRITE` from `roles-mapping`. The older build fails startup on a mapping
    that names either.
  - Delete the setting's audit activities. Every `PUT` records one under the resource type `Setting`, which the older
    build cannot read, so any `/v1/activities` page holding one fails:

    ```sql
    DELETE FROM audit_activity WHERE resource_type = 'Setting';
    ```

  The tables `instance_setting` and `instance_setting_aud` are ignored by the older build and may be left in place.

## 4. Migrations (automatic on startup)

| Migration | Effect |
|---|---|
| ClickHouse `V15` | Adds the nullable `route_name` and `route_type` columns to `dial_usage_log`: the route that served the request. Rows ingested earlier read `NULL` until re-ingested. |
| ClickHouse `V16` | Adds the request-delta columns, `project_id` and `user_hash`, and the `idx_request_hash` skip index to `dial_usage_log_payload`. Metadata-only; existing rows read the defaults. |
| ClickHouse `V17` | Creates the failure ledger `_dial_internal_failed_ingest`: one row per failure, retry or resolution event, 13-month TTL (§3b). |
| Postgres `V47` | Adds `enrichment_relayout`: the progress and writer hold of the automatic relayout (§3). |
| Postgres `V48` | Drops the evaluator archive (`evaluator`, `evaluator_version`), `pipeline.evaluator_name` / `evaluator_version` and their `pipeline_aud` mirrors, and `rule_legacy` / `rule_legacy_aud`. Keeps `pipeline.legacy_rule_id`, which 0.3 still maps; the next release drops it. |
| Postgres `V49` | Adds the catalog rows of the two route columns (`V15`), so they are queryable. |
| Postgres `V50` | Registers `request_cache_control` on `dial_usage_log_payload`, sensitive and heavy, and extends the seeded `request_body` description. |
| Postgres `V51` | Adds `dashboard` and its audit mirror `dashboard_aud`, for the new `/v1/dashboards` API. Both start empty. |
| Postgres `V52` | Adds the nullable `settings` column to `dashboard` and `dashboard_aud`. |
| Postgres `V53` | Adds `instance_setting` and its audit mirror `instance_setting_aud`, for the sessions-source setting (§3e). Both start empty. |
| Postgres `V54` | Re-describes `request_cache_control` on `dial_usage_log_payload` to name its `text_blocks` entry (§7). A description an operator edited is left alone. |

The relayout itself is runtime DDL, not a migration: it runs after startup, off the probe path.

## 5. After deploy — verify

About 15 minutes after the deploy (the 10-minute delay, then the relayout):

- **Postgres:** `SELECT table_name, step, hold, error FROM enrichment_relayout;` — every row `DONE` (or
  `UNSUPPORTED` with a reason), `hold` false, `error` empty.
- **API:** `GET /v1/tables/<table>` of each row-grain enrichment (for example `usage_client_identity`)
  reports `layout: aligned`.
- **ClickHouse:** `SELECT name FROM system.tables WHERE database = currentDatabase() AND name LIKE '\\_relayout\\_%';`
  returns nothing. A leftover table means the check found rows with a source row missing (ERROR in the log): it
  is the previous table, kept under its `_relayout_` name for inspection, while the aligned table is in service. It is not
  dropped automatically: compare it with the aligned table by grain key, rewrite any row you need (or let its
  pipeline write it again), then `DROP TABLE` it.

**Ingest failures** (any time after the deploy):

- **ClickHouse:** `SELECT count() FROM _dial_internal_failed_ingest;` succeeds as the writer. An `ACCESS_DENIED`
  means a grant from §1 is missing.
- **API:** `GET /v1/data-sync/failures` answers `200`. Look at any `parked` entry: its `reason` and `message` say why
  it failed, and after a fix `POST /v1/data-sync/failures/retry` requeues it.
- **Logs:** no `Retry sweep could not read the failure ledger` ERROR from `IngestRetrySweep`.

**Recover blobs an earlier failure left behind (recommended, once).** Before 0.23, a blob that failed on the
reconciliation run stopped the rest of its day, so the blobs after it may never have been ingested. Run one default
backfill (no `includeIngested`) over the weeks since the 0.3 deploy: it skips every blob the ingest ledger already
records, so it ingests only what is missing, and records any blob that still fails in the failure ledger. Do not reach
back past the 0.3 deploy. Days before it are not in the ingest ledger, so they are re-ingested in full, and that
re-fires every row-grain enrichment over them, a full-cost replay on an LLM pipeline.

## 6. Breaking changes at a glance

| Change | Symptom | Action |
|---|---|---|
| Runner data-plane needs `Adas-Contract-Version: 4` | `409` | Redeploy the runner in the same window (§2) |
| Writer reads `system.parts` / `system.data_skipping_indices` | Relayout ERROR `ACCESS_DENIED`; schema `PATCH` dropping a column fails | Grant both (§1), restart |
| Writer writes the failure ledger `_dial_internal_failed_ingest` (per-table grants only) | Startup fails at `V17`; or the poller stops at the first failed blob; or the retry sweep logs ERROR and `GET /v1/data-sync/failures` is `503` | Grant `SELECT`, `INSERT` (and `CREATE TABLE` unless database-wide) on it (§1) |
| A blob that fails to ingest no longer stops its day | The day's other blobs are ingested; the day is still in `failedDates`, and `failedBlobs` counts the blob | Watch `GET /v1/data-sync/failures` and requeue after a fix (§3b) |
| An unreadable `long` / `double` / `bool` producer value is no longer read as `0` / `false` | `NULL` in a nullable column where earlier rows had `0`; `12.7` in a `long` column is refused, not truncated; `sync.values.coercion.failures` rises | None for the shipped mappings; fix the producer or the mapping `kind` if the counter rises (§3c) |
| Row write to an aligned enrichment without `request_time` (`POST /v1/tables/{name}/rows`) — a new table, or an existing one once the relayout aligned it | `422 validation_error` | Send the source row's `request_time` (§3) |
| Per-row LLM write-back to an aligned enrichment without `__partition_v` | `422 validation_error` | Runner 0.23 echoes it (§2) |
| Registering a pipeline onto an aligned enrichment that reads a table other than the enrichment's source, or has a group trigger | `422 pipeline_validation_failed` | Read the enrichment's own source with a row trigger |
| Importing a bundle whose identity-keyed enrichment declares a `request_time` that is nullable or not a `timestamp` | `422 validation_error` | Rename the column, or make it a non-nullable `timestamp` |
| API description in `oidc` mode needs a credential | `401` on `/v3/api-docs` and the Swagger UI | Send a credential, or set `DISABLE_SWAGGER_AUTHORIZATION=true` (§1) |
| Evaluator archive removed (`GET /v1/evaluators*`) | `404 not_found` | Read the definition from the pipeline's `transform` (`GET /v1/pipelines/{name}`); dump the tables first if the pre-fold definitions are wanted (§1) |
| OpenAPI component `EvaluatorVarDto` renamed `TransformVarDto`; `Evaluators` tag gone | Generated clients change; wire JSON does not | Regenerate the client |
| Ingest trims inline files from payload bodies (`SYNC_INLINE_FILES_DROP_TRUNCATED=true` by default) | A body truncated at 4 MiB is stored without its half inline file | Set `SYNC_INLINE_FILES_DROP_TRUNCATED=false` to store bodies as sent (§7) |
| Request-delta storage, opt-in (`SYNC_REQUEST_BODY_MODE=delta`) | A later chat turn's `request_body` holds only its new messages, and no tools when they equal the earlier turn's. Every chat turn's `request_body`, the first included, is re-serialized (keys sorted, no whitespace) and its `cache_control` markers move to `request_cache_control` | Only when you set `delta`: re-point pipelines and readers that need a turn's whole request to `request_body_full` first; check readers that need the body text as sent, or `cache_control` in the body (§7) |
| Sessions-source setting, new privileges `SETTINGS_READ` / `SETTINGS_WRITE` | Bundles gain them; a raw-privilege `roles-mapping` does not, and its holders get `403` reading the setting | Add `SETTINGS_READ` to raw-privilege mappings that need the setting; before a rollback, remove both names and delete the `Setting` audit activities (§1, §3e) |
| Sensitive-column ingest switch, opt-in (`SYNC_SENSITIVE_COLUMNS_ENABLED=false`) | New rows hold defaults in the columns the catalog flags `sensitive` | None by default; under `false`, startup fails on an undefined target table or a sensitive key column (§3d) |
| Payload rows carry the caller's identity | `dial_usage_log_payload` stores `project_id` and `user_hash` on every row, under every mode, next to the bodies. The catalog hides both; the table already holds the caller's `jwt_claims` | Account for it where direct ClickHouse access to the payload table is granted |
| Default `service.name` changed to `ai-dial-analytics` | Telemetry arrives under the new service name; filters on `analytics-data-access-service` find nothing | Set `OTEL_SERVICE_NAME=analytics-data-access-service` to keep the old name (§1) |
| Flight SQL server name changed to `ai-dial-analytics` | `GetSqlInfo` and JDBC/ADBC metadata report the new product name; client settings keyed on the old name stop applying | Match the new name in the client; it cannot be set back (§1) |
| A `PATCH` that only changes `enabled` no longer bumps `generation` | `generation` stays put across a stop and restart; the generation-change log line is not written for it | Watch `updated_at` for any change to a pipeline; `generation` tracks declaration revisions only (§1) |
| `AGGREGATE_PREVIEW_ROWS` default lowered from `20` to `5` | An aggregate preview returns fewer rows | Set it back to `20` if wanted |
| Pipeline preview `statement` is now the declaration in the ADAS SQL dialect (aggregate and Class-1 `sql`), with a new `query` member holding the same query as structured-query DSL, and a new note code `statement_not_portable` | `statement` names catalog entities and target columns and carries no `SETTINGS`, joins or `__` helper aliases; it is ordered by the keys, not newest first, and does not reproduce `total_rows`. A client that parsed the ClickHouse text, or showed it as the SQL that produced `rows`, misreads it | Run `statement` on `POST /v1/queries/execute-sql`, or `query` on `POST /v1/queries/execute`; handle `statement_not_portable` (both absent); read the physical sampling statement from the SQL log at `DEBUG` |
| `SAVED_QUERY_WRITE` and `SAVED_QUERY_PUBLISH` now each imply `SAVED_QUERY_READ` | A `roles-mapping` naming either without `SAVED_QUERY_READ` now also lists and reads `common` saved queries and their revisions, the holder's own `personal` ones and `private` dashboards, and `public` dashboards. No bundle widens | None, unless such a mapping was meant to write without reading; that is no longer expressible |
| A `personal` saved query and a `private` dashboard are visible to their owner alone | `FULL_ADMIN`, and any `SAVED_QUERY_PUBLISH` holder, no longer reads other users' `personal` saved queries at `GET /v1/saved-queries/{id}/revision/{rev}` (now `404`) or sees their activities in `/v1/activities`. The new dashboards follow the same rule from the start. A deleted saved query's activities, visible only to such holders before, are now visible to no one | None; a client that showed administrators other users' personal history loses it |

## 7. New configuration

- `DISABLE_SWAGGER_AUTHORIZATION` (default `false`) — serve the API description without a credential in `oidc`
  mode; see [`configuration.md`](configuration.md#api-documentation-springdoc--swagger-ui).
- `MANAGEMENT_SERVER_PORT` (default unset) — serve the actuator (`/actuator/health` with its probe groups,
  `/actuator/info`, `/actuator/prometheus`) on a port of its own instead of the API port. Unset changes nothing:
  probes and the Prometheus scrape stay on the API port. Once set, the actuator answers only on that port and `404`s on
  the API port, so move the probes and the scrape there and declare it as a container port; the image's Docker
  `HEALTHCHECK` follows it. See [`configuration.md`](configuration.md#server).
- `SYNC_INLINE_FILES_DROP_TRUNCATED` (default `true`) — cut a payload body truncated at 4 MiB back to before its half
  inline file; see [`column-mapping.md`](column-mapping.md).
- `SYNC_INLINE_FILES_MAX_SIZE` (default unset) — remove inline files whose stored base64 is longer than this size
  (`256KB`, `1MB`; `0` removes all). An earlier `SYNC_INLINE_FILES_MODE` no longer exists and is ignored if left set:
  replace `keep` with `SYNC_INLINE_FILES_DROP_TRUNCATED=false` and `drop` with `SYNC_INLINE_FILES_MAX_SIZE=0`.
- `SYNC_REQUEST_BODY_MODE` (default `whole`) — `whole`, `whole-with-path` or `delta`. `delta` stores a chat request
  with only the messages its previous turn did not have, without the tools that turn has, and a link to that turn, so
  repeated conversation history is not stored again. See [`heavy-columns.md`](heavy-columns.md#request-bodies-in-delta-form).
  Under `whole` nothing changes for readers and no body is parsed. `whole-with-path` stores bodies whole too, and
  stamps each chat request with the hidden path a `delta` row links to. **Once `delta` has been on, re-ingest those
  days only under `whole-with-path` or `delta` on 0.23:** a build older than 0.23, or `whole`, writes rows without the
  path, and the later turns that link to them can no longer be rebuilt. Roll back from `delta` to `whole-with-path`
  for that reason, and set `delta` only once no pre-0.23 pod is left ingesting.
  - **Read a turn's whole request from `request_body_full`.** Under `delta`, `request_body` holds only what a row's
    parent lacked. The new computed fields `dial_usage_log_payload.request_body_full` and `request_body_rebuild`
    return the rebuilt request and whether it is `stored`, `rebuilt` or `partial`. **Before setting `delta`,
    re-point every pipeline whose `transform.inputs` binds `request_body` to `request_body_full`** (and bind
    `request_body_rebuild` too if the pipeline should tell a `partial` rebuild apart), and move saved queries and
    other readers that need a turn's whole request the same way. Under `whole` and `whole-with-path`,
    `request_body_full` returns the stored body marked `stored`, so re-pointing first is safe. ADAS does not rewrite
    pipelines. Both fields are projection-only: filtering, sorting or grouping on them answers `400 bad_request`.
  - A later turn's `request_cache_control` holds a `{"text_blocks":[…]}` entry, usually empty, naming the earlier
    messages the turn sent as one text block, so it is not `NULL` when the turn carried no `cache_control` marker. A
    reader that parses the column skips that entry.
  - **An environment that ran a 0.23 release candidate under `delta` or `whole-with-path`:** a message sent as one
    text block now hashes like the same message sent as a plain string, so a request whose history holds such a
    block gets a different path than under the release candidate. A conversation in progress at the upgrade whose
    history holds one links its next turn to an earlier turn or starts a new chain, once. Re-ingest across the
    upgrade only by whole UTC days: such an event re-ingested alone takes the new path, and its children stored
    before can no longer be rebuilt. A later turn stored before the upgrade has no `text_blocks` entry and rebuilds
    its earlier messages as they are stored. Pods on different builds during a rolling upgrade may miss each other's
    rows as parents for such requests, which costs storage only. Rolling back to a release candidate: re-point
    pipelines bound to `request_body_full` back to `request_body` first, since a release candidate refuses them.
- `QUERY_REQUEST_BODY_REBUILD_MAX_ROUNDS` (default `8`) and `QUERY_REQUEST_BODY_REBUILD_MAX_ANCESTORS` (`20000`) —
  bound the chain lookups one read of `request_body_full` / `request_body_rebuild` may run; past them rows read
  `partial`. See [`configuration.md`](configuration.md#request-body-rebuild).
- `SYNC_SENSITIVE_COLUMNS_ENABLED` (default `true`) — `false` keeps the columns the catalog flags `sensitive` out of
  ingest (§3d).
- `BLOB_PROVIDER=google-cloud-storage` — Google Cloud Storage as the `data-sync` blob source, with a service-account
  key pair in `BLOB_IDENTITY` / `BLOB_CREDENTIAL` or, with both blank, Application Default Credentials (GKE Workload
  Identity). See [`gcs-access.md`](gcs-access.md). The PostgreSQL catalog can likewise run on Cloud SQL with IAM
  database authentication through connector parameters in `POSTGRES_OPS`, with no new variable; see
  [`cloud-sql-access.md`](cloud-sql-access.md).
- `SYNC_MAX_LINE_BYTES` (default `67108864`) — the largest decompressed log line ingest reads; a longer line is skipped and
  counted as malformed.
- `SYNC_PAYLOAD_TTL_DAYS` (default unset) — the payload table's retention in days, `-1` for none, applied at
  startup; no longer than the usage log's. Unset leaves the live `TTL` alone, so an upgrade changes no table and
  removing the variable keeps the last `TTL`; set `90` to return to the provisioned value. With sub-privileges
  granted individually, applying a value needs `ALTER TTL` (see
  [`clickhouse-server.md`](clickhouse-server.md#narrowing-alter-to-sub-privileges)).
- `SYNC_USAGE_LOG_TTL_DAYS` (default unset) — `dial_usage_log`'s retention in days, `-1` for none, applied the same
  way. No day count equals the provisioned `INTERVAL 1 YEAR`; `365` is the nearest. See
  [`configuration.md`](configuration.md).
- `SYNC_RETRY_ENABLED` (default `true`), `SYNC_RETRY_CRON` (`0 */15 * * * *`), `SYNC_RETRY_INITIAL_BACKOFF` (`15m`),
  `SYNC_RETRY_MAX_BACKOFF` (`24h`), `SYNC_RETRY_MAX_ATTEMPTS` (`10`), `SYNC_RETRY_MAX_PER_SWEEP` (`20`) — the retry
  sweep over the failure ledger (§3b); failures are recorded and listed even with retries disabled. See
  [`configuration.md`](configuration.md).

## 8. 0.23.1

0.23.1 keeps runner contract version `4` and adds no migration. Its one configuration change is the sensitive-column
setting below; a deployment that never set `SYNC_SENSITIVE_COLUMNS_ENABLED` takes it as an ordinary redeploy. An
environment still on 0.3 follows §1 to §7 and deploys 0.23.1 in place of 0.23.0, setting `SYNC_SENSITIVE_COLUMNS`
wherever §3d and §7 name `SYNC_SENSITIVE_COLUMNS_ENABLED`.

### Sensitive columns are chosen per column

`SYNC_SENSITIVE_COLUMNS` (see [`configuration.md`](configuration.md#data-sync-schedule)) is a comma-separated list of
the columns the catalog flags `sensitive` that ingest writes; the others hold their ClickHouse defaults on new rows,
as under the 0.23.0 `false` (see [`data-sync`](../openspec/specs/data-sync/spec.md), "Sensitive columns can be
excluded from ingest").

| 0.23.0 | 0.23.1 |
|--------|--------|
| unset or `SYNC_SENSITIVE_COLUMNS_ENABLED=true` | unset, or `SYNC_SENSITIVE_COLUMNS=*` |
| `SYNC_SENSITIVE_COLUMNS_ENABLED=false` | `SYNC_SENSITIVE_COLUMNS=none` |
| — | `SYNC_SENSITIVE_COLUMNS=request_tags,jwt_claims`: only those; `rate_analytics.comment` limits an entry to one table |

- **Startup fails while `SYNC_SENSITIVE_COLUMNS_ENABLED` is set**, whatever its value, naming the replacement.
  Ignoring it would turn a `false` into the default `*` and write payload content again.
- **Startup fails** on an entry that names no column of an ingest target table, and on `*` or `none` mixed with
  other entries. An entry that names only a column the catalog does not flag `sensitive` logs a warning instead. The 0.23.0 checks, an undefined target table and an excluded key column,
  apply to every value other than `*`.
- **Under `SYNC_REQUEST_BODY_MODE=delta`, `request_body` needs `request_cache_control`** in the same list, or startup
  fails: delta moves a request's cache markers into that column.
- Write `none`, not an empty value: an empty value also means none, but tooling that prunes empty variables
  restores `*`.
- **Rollback.** 0.23.0 ignores `SYNC_SENSITIVE_COLUMNS` and writes every sensitive column unless
  `SYNC_SENSITIVE_COLUMNS_ENABLED=false` is set again, so set it before rolling back a deployment that kept columns
  out.

## 9. 0.23.3

0.23.3 keeps runner contract version `4`, so the Enrichment Runner stays on its 0.23 release. It is more than a
redeploy because the table-access changes are breaking. An environment still on 0.3 follows §1 to §8 first and
deploys 0.23.3 in place of 0.23.0.

**Before deploying:**

1. **Check for a stored `*`** in `get`, `read` and `sensitive` lists and row policies, and remove the ones you did
   not mean to open to every caller (§9a).
2. **Run the two enrichment queries** in §9c: decide for each role that gains access whether to hide the columns
   from it, and grant each role that loses access on the source. Save the lists and policies for a rollback.
3. **Give runner roles the source's clearance** where the source's partition, version or identity column is
   `sensitive` (§9d).
4. **Update access-API clients**, the Admin UI included: move from the old `/access` and `/row-policies` calls to
   `/v1/tables/{name}/access?role=`, and read `roles` instead of `access` and `row_policies` in revision snapshots
   (§9d). Deploy ADAS and a client that speaks the new API in the same window.
5. **Plan a freeze**: no access, table or pipeline edits until every ADAS pod runs 0.23.3 (§9c, §9d).

**Migrations** (automatic on startup):

| Store | Migration | Effect |
|---|---|---|
| Postgres | `V55` | Admits the `app-routes` instance-setting name, which this release does not offer |
| Postgres | `V56` | Removes every enrichment's `get`, `read` and `sensitive` lists and its row policies (§9c) |
| Postgres | `V57` | Adds per-role column policies and the access generation to the table catalog (§9d) |
| Postgres | `V58` | Rewrites the description of `dial_usage_log_payload.request_body` |
| Postgres | `V59` | Adds the catalog rows of the spending-limit columns (§9e) |
| ClickHouse | `V18` | Adds the five spending-limit columns to `dial_usage_log` (§9e) |

`V55` and `V58` ship unchanged from 0.24, so the migration history stays continuous and a later upgrade to 0.24 is
not refused for an out-of-order migration. `V58`'s new description already mentions a 0.24 storage change:
under `delta`, 0.24 stores a repeated large non-JSON body once. 0.23.3 still stores every body.

### 9a. The `*` grantee

`*` in a table's `get`, `read` or `sensitive` grant list, or as the role of a row policy, now stands for every
authenticated caller (see [`row-level-security.md`](row-level-security.md#everyone-the--grantee)). Earlier builds
stored `*` as an ordinary role name that matched only a caller carrying a provider role spelled `*`.

- **Before upgrading, look for a stored `*`.** A table that already names `*` in a read-side list becomes readable
  by every authenticated caller, with its sensitive columns when the list is `sensitive`. Remove the entry with
  0.23.2's `PUT /v1/tables/{name}/access` unless that is what you want. After the upgrade, the startup log lists
  every table on which the `*` entry carries a privilege or a row policy. It skips an enrichment's `get`, `read` and
  `sensitive`, which confer nothing any more (and which `V56` clears, a `*` included); a `*` row policy on one is
  still listed.
- A `*` stored with `write` or `modify` grants nothing, and the next `PUT /v1/tables/{name}/access?role=*` drops it.
- **Flight SQL admits every authenticated caller.** The connection gate used to turn away a caller holding no
  privilege and no provider role; such a caller can now be reached by a `*` grant. Each call is still decided per
  table.
- **Rollback.** 0.23.2 reads `*` as an ordinary role name again. Callers who read a table only through a `*` grant
  lose access to it, and a `*` row policy confines nobody, so a caller it confined who reads the table another way
  sees every row. Delete `*` policies, or the read paths they narrow, before rolling back if a rollback must not
  widen reads.

### 9b. Row policies and queries on the caller's own rows

A row policy may compare a `string` column with a value of the caller reading the table, `caller.user_hash` or
`caller.project`, and an entry's `rows` (`PUT /v1/tables/{name}/access?role={role}`, §9d) accepts the policy as a SQL
condition in `where` instead of `filter` (see
[`row-level-security.md`](row-level-security.md#own-rows-comparing-a-column-with-the-caller)). Nothing changes for
stored policies. A query may filter on the same values, so a caller whose roles read every row can still ask for its
own: `caller.user_hash()` / `caller.project()` in SQL, the `param` of that name in the structured-query DSL (see
[`sql-reference.md`](sql-reference.md#your-own-rows-caller-values)).

- **`caller.user_hash` needs new settings.** It is derived the way DIAL Core derives its `user.id` log field, from
  `providers.<name>.logging-key`, `.logging-salt` and `.obfuscate-user-email` for an identity provider
  (see [`configuration.md`](configuration.md#identity-providers)), and from `API_KEY_LOGGING_KEY`,
  `API_KEY_LOGGING_SALT` and `API_KEY_OBFUSCATE_USER_EMAIL` for a per-user API key
  (see [`configuration.md`](configuration.md#api-key-authentication)). Set them to the values DIAL Core uses for
  the same provider; leave the API-key settings unset when Core's providers use different salts. The salt is a
  credential: keep it in the deployment's secret store. Without the settings a caller has no user hash, and a
  policy matching on `caller.user_hash`, or a query filtering on `caller.user_hash()`, gives that caller no rows.
- **Rollback.** 0.23.2 refuses a policy that uses a caller value, so every read that policy confines fails. Replace
  or delete such policies before rolling back. It also refuses, with `400`, a query that holds a caller value, so a
  saved query or dashboard widget that stores one fails to run until it is rewritten.

### 9c. An enrichment's read-side access is its source's

An enrichment is always read through its source, so its `get`, `read` and `sensitive` access and its rows now come
from the source (see [`table-access-control`](../openspec/specs/table-access-control/spec.md)). `write` and `modify`
stay its own.

- `V56` removes every enrichment's `get`, `read` and `sensitive` lists and its row policies.
- A `PUT .../access?role=` on an enrichment with anything but `write` and `modify` is
  `409 enrichment_follows_source`.
- **Source readers gain** the enrichments' columns (sensitive ones with the source's clearance). **Enrichment-only
  grantees lose** them: the columns become unknown fields. **Enrichment row policies are dropped**, and a role they
  confined reads the enrichment's values on every source row it can read. To keep the values from a role, leave the
  `<enrichment>.<column>` names out of its column policy on the source, mark the columns `sensitive`, or confine the
  role's rows on the source.
- The sensitive-grant subset rule is gone for every derived table: its clearance holders no longer need clearance on
  its input. Review who holds each derived table's clearance.

**What to do before upgrading.** First, find who gains access: each source `read` or `sensitive` grantee the
enrichment does not name, and each role whose enrichment row policy is dropped.

```sql
WITH pair AS (
  SELECT e.name AS enrichment, e.access AS ea, e.row_policies AS ep, s.access AS sa
    FROM table_definition e
    JOIN table_definition s ON s.name = e.source_table
   WHERE e.type = 'ENRICHMENT')
SELECT enrichment, 'sensitive' AS gains, role
  FROM pair, jsonb_array_elements_text(sa -> 'sensitive') AS role
 WHERE NOT coalesce(ea -> 'sensitive', '[]'::jsonb) ? role
UNION ALL
SELECT enrichment, 'read', role
  FROM pair, jsonb_array_elements_text(sa -> 'read') AS role
 WHERE NOT coalesce(ea -> 'read', '[]'::jsonb) ? role
UNION ALL
SELECT enrichment, 'rows (policy dropped)', role
  FROM pair, jsonb_object_keys(ep) AS role;
```

For each row, accept the gain or hide the columns from that role as described above. Column policies exist from
0.23.3 on, so set them right after the upgrade; mark columns `sensitive` before it.

Then find the roles that would lose access, and grant each the same key on the source. This query is expected to
return nothing; a role mapped to the privilege globally can be ignored.

```sql
WITH pair AS (
  SELECT e.name AS enrichment, e.access AS ea, s.access AS sa
    FROM table_definition e
    JOIN table_definition s ON s.name = e.source_table
   WHERE e.type = 'ENRICHMENT')
SELECT enrichment, 'sensitive' AS key, role
  FROM pair, jsonb_array_elements_text(ea -> 'sensitive') AS role
 WHERE NOT coalesce(sa -> 'sensitive', '[]'::jsonb) ?| ARRAY[role, '*']
UNION ALL
SELECT enrichment, 'read', role
  FROM pair, jsonb_array_elements_text(ea -> 'read') AS role
 WHERE NOT coalesce(sa -> 'read', '[]'::jsonb) ?| ARRAY[role, '*']
UNION ALL
SELECT enrichment, 'get', role
  FROM pair, jsonb_array_elements_text(ea -> 'get') AS role
 WHERE NOT coalesce(sa -> 'get', '[]'::jsonb) ?| ARRAY[role, '*']
   AND NOT coalesce(sa -> 'read', '[]'::jsonb) ?| ARRAY[role, '*']
   -- a '*' in a modify list confers nothing
   AND NOT (role <> '*' AND coalesce(sa -> 'modify', '[]'::jsonb) ? role)
   AND NOT (role <> '*' AND coalesce(ea -> 'modify', '[]'::jsonb) ? role);
```

Save the lists and policies for a rollback:
`SELECT name, access, row_policies FROM table_definition WHERE type = 'ENRICHMENT';`

**During the rollout**, make no access or pipeline edits until every pod runs 0.23.3. Until then, pods on 0.23.2
judge enrichments by their own, now empty, lists: they may refuse enrichment columns to per-table readers of the
source and to runner scans, and fail refreshes of rollups that read enrichment columns. These failures need no
action and stop when the rollout finishes.

**Rollback.** `V56` cannot be undone. After redeploying 0.23.2, restore the saved lists and policies through its
`/access` and `/row-policies` endpoints. Upgrading again does not re-run `V56`; then reduce each restored enrichment
entry to `write` and `modify`.

### 9d. Role-keyed table access

A table's access is now one entry per provider role, `{"privileges", "rows", "columns"}`, at
`/v1/tables/{name}/access?role={role}` (see [`row-level-security.md`](row-level-security.md)). Stored grants and
policies carry over as entries; `V57` adds the new columns.

**Moving clients** (`...` is `/v1/tables/{name}`):

| 0.23.2 call | 0.23.3 call |
|---|---|
| `GET .../access` (five lists) | `GET .../access`: `{"generation", "roles": {"<role>": <entry>}, "ineffective_grants"}` |
| `PUT .../access` with lists | `PUT .../access?role={role}` with the whole entry, per role; `DELETE` a role left with nothing |
| `GET .../row-policies` | `GET .../access` (each entry's `rows`) |
| `PUT` / `DELETE .../row-policies/{role}` | `PUT .../access?role={role}` with `rows` set, or `null` |
| `permissions` in the `PUT` response | `GET /v1/tables/{name}` |

- URL-encode the role. A `PUT` replaces the whole entry and must send the `generation` it read; it gets
  `409 access_conflict` only when that role changed since. Writes to one table that wait past `TABLES_LOCK_TIMEOUT`
  get `409 table_busy`.
- Revision snapshots carry `roles` instead of `access` and `row_policies`.
- A `list` privilege or a bundle-named role (`FULL_ADMIN`, ...) is `422`. Firewall refusals are an `ErrorView` `400`.
- Additive: `permissions.grant` and `permissions.columns_restricted`; `ineffective_grants` on both reads, with the new
  reasons `lifts_column_policies` and `everyone_reads_all_rows`.

**Column policies** are new. A hidden column is absent from every read surface, and pipeline, runner and export paths
refuse it with `403 column_not_entitled`. A restricted runner's list must also name `event_id`, `_ingested_at` and, for
an aligned target, `request_time` on `dial_usage_log`. Gaining a column-restricted role can hide columns (see
[`row-level-security.md`](row-level-security.md#column-policies)). Revisions hide what the table's policies hide now,
or, for a deleted table, what they hid when it was deleted. Catalog bundles carry no access, as before.

**Runners and pipeline authors.** A runner needs the source's clearance when the source's partition, version or
identity column is `sensitive`, or it gets `403 sensitive_column_not_entitled`. A malformed `enrich` fragment over a
source with sensitive columns answers `403` instead of `422` to an author without the clearance.

**During the rollout**, make no access or table edits until every pod runs 0.23.3: 0.23.2 pods ignore column
policies.

**Rollback.** 0.23.2 ignores column policies, so every column they hide becomes visible. Delete them first if that
must not happen.

### 9e. Spending-limit columns on `dial_usage_log`

DIAL Core reports the spending-limits check of a token caller's model call as a `spending_limits` object. ADAS
stores it in five nullable columns of `dial_usage_log`, tagged `cost`: `spending_limits_mode`, `budget_cost_object`,
`budget_outcome`, `budget_policy` and `budget_assignment` (see
[`data-sync`](../openspec/specs/data-sync/spec.md), "Reported spending limits are persisted").

- **Migrations.** ClickHouse `V18` adds the columns with `ALTER ADD COLUMN`, a metadata-only change that needs no
  new grant. Postgres `V59` adds their catalog rows. With `CLICKHOUSE_MIGRATIONS_ENABLED=false`, apply `V18` by hand
  **before** starting the new build: startup validation refuses a mapping whose columns the table lacks. With
  `SYNC_VALIDATE_SCHEMA=false` as well, nothing stops that start: rows are then written without the five values,
  their blobs are recorded as ingested, and a query that reads the columns, a wildcard included, fails until `V18`
  is applied. With `POSTGRES_MIGRATIONS_ENABLED=false`, apply `V55` to `V59` by hand too: without `V59` ingest fills
  the columns, but no query surface knows them (`unknown field`), and nothing warns at startup.
- **Budget paths are sensitive.** `budget_policy` and `budget_assignment` hold paths an administrator chooses, which
  can name a user, so a caller needs `TABLE_SENSITIVE` on `dial_usage_log` to see them (see
  [`table-management`](../openspec/specs/table-management/spec.md), "Seeded column sensitivity on the built-in source
  tables"). `spending_limits_mode`, `budget_cost_object` and `budget_outcome` are visible to every reader.
- **A custom mapping file needs the five entries.** With `SYNC_MAPPING_FILE` set to your own copy, copy the five
  `spending_limits.*` entries from `mapping/dial-usage-log.yaml`. Without them the columns stay `NULL`, which reads
  the same as "budgets did not apply".
- **Deploy order does not matter.** Until DIAL Core reports the object, the five columns read `NULL`, the same as
  for a call budgets do not apply to.
- **`NULL` means "did not apply" only from the day both sides are upgraded.** Earlier rows read `NULL` whether or
  not budgets applied, and a query cannot tell the two apart. Note the first full UTC day on which both ADAS and a
  DIAL Core that reports the object ran, and read `NULL` as "did not apply" only from then on.
- **Rows ingested earlier read `NULL`** until their blobs are re-ingested. Under `delta`, re-ingest whole UTC days
  on one release and configuration.
- **A refused call has no row.** `budget_outcome` `no_budget` and `exhausted` appear in shadow mode only.
- **Rollback.** 0.23.2 does not declare the columns, so it ignores them and they read `NULL` on the rows it ingests.

### 9f. Revision snapshots of system tables

`GET /v1/tables/{name}/revision/{revision}` answered `404 revision_not_found` at every revision of `dial_usage_log`,
`rate_analytics` and `dial_usage_log_payload`: a migration creates them, so their creation is not in the audit trail.
It now answers `200` at every revision from the first recorded state of the table's definition, so the access edits
already recorded on these tables become readable, with no backfill (see
[`audit-trail`](../openspec/specs/audit-trail/spec.md), "Historical table snapshots").

- **No `columns` member.** The trail does not track a system table's columns, so the snapshot leaves the member out
  rather than send an empty list or today's. `ordering_key`, `partition_by`, `ttl` and `grain` are those last
  recorded at or before the revision: a migration's change to the definition, such as `V40` setting
  `dial_usage_log_payload`'s partitioning, shows only from the table's next recorded change. `roles` is served under
  the usual `TABLE_GRANT` bar. A history client such as the Admin UI must not assume `columns` is present.
- **`permissions.columns_restricted`** is `true` whenever a column policy confines the caller, one that lists every
  column included.
- A revision before the table's first recorded state still answers `404 revision_not_found`, as does every revision
  of a non-system table created before the trail.
- **Rollback.** 0.23.2 answers `404` for these tables again. No data changes.

### 9g. Summary

| Change | Effect | Action |
|---|---|---|
| `*` means every authenticated caller in read-side grant lists and row policies; Flight SQL admits every authenticated caller | A `*` stored earlier as a role name opens that table to all callers | Remove unintended `*` entries before upgrading; check the startup log after; before a rollback, delete `*` policies that must keep confining (§9a) |
| Row policies and queries may use `caller.user_hash` / `caller.project` | `caller.user_hash` matches no row until the `logging-*` settings are set | Set the `logging-*` settings to DIAL Core's values where own-rows policies or queries use it; before a rollback, remove caller-value policies and rewrite saved queries and widgets that store a caller value (§9b) |
| An enrichment's read access and rows follow its source (`V56`) | Source readers gain its columns, enrichment-only grantees lose them, its row policies are gone | Run the two §9c queries, hide or grant per role, save the lists for a rollback (§9c) |
| Sensitive-grant subset rule removed | Derived tables no longer need clearance on their input | Review who holds each derived table's clearance (§9c) |
| Role-keyed access API, column policies (breaking) | Old access and row-policy calls are gone; snapshots carry `roles`; hidden columns are absent and refused `403 column_not_entitled` | Move clients, the Admin UI included, to the calls in §9d; name the cursor columns in a restricted runner's list; delete column policies before a rollback (§9d) |
| Runner clearance, `enrich` parse failures, new codes | New `403 sensitive_column_not_entitled` cases; `409 access_conflict`, `409 table_busy`, `409 column_in_column_policy` | Give runners the source's clearance; handle the new codes (§9d) |
| Spending-limit columns on `dial_usage_log` (ClickHouse `V18`, Postgres `V59`) | Five nullable `cost` columns hold DIAL Core's reported spending-limits check; earlier rows read `NULL`; `budget_policy` and `budget_assignment` need `TABLE_SENSITIVE` | With ClickHouse migrations disabled, apply `V18` before starting the new build; with Postgres migrations disabled, apply `V55` to `V59`; copy the five entries into a custom mapping file (§9e) |
| System-table revision snapshots | `GET /v1/tables/{name}/revision/{revision}` answers `200` for `dial_usage_log`, `rate_analytics` and `dial_usage_log_payload`, with `roles` and no `columns` | Let history clients handle an absent `columns` (§9f) |
