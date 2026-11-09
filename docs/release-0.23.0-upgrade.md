# ADAS 0.23.0 — upgrade notes

What an environment has to do to move from **release-0.3** to **0.23.0**. An environment still on an
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

## Why 0.23.0 and not 0.4

0.23.0 comes straight after 0.3. There were no releases from 0.4 to 0.22. This is the first release of ADAS and
the Enrichment Runner as open source on GitHub, and both now use the same version numbers as the other DIAL Admin
services, which are also at 0.23.0. The release contains what was planned as 0.4. The earlier upgrade notes keep
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
- **New system-table grants.** 0.23.0 reads `system.parts` and `system.data_skipping_indices` on the writer
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
- **Evaluator archive (optional).** 0.23.0 drops the read-only evaluator archive and the rule history left
  over from the fold (§4). The drop cannot be undone: the dump is the only copy of the definitions as they
  stood before the evaluator fold (0.2.2) and of the pre-fold rule revisions. To keep them, dump the four
  tables first:

  ```bash
  pg_dump --table=evaluator --table=evaluator_version --table=rule_legacy --table=rule_legacy_aud \
    <database> > evaluator-archive.sql
  ```

## 2. Deploy order — ADAS and the Enrichment Runner ship as a pair

The runner must be its 0.23.0 build. ADAS now requires **`Adas-Contract-Version: 4`** on the four runner-only
endpoints (`scan`, `group-rows`, `materialize`, `rows`), and each side refuses the other's old version. The
contract change: for a per-row LLM pipeline over an aligned enrichment (§3), the scan returns `__partition_v`,
which the runner echoes back on every write-back row.

Follow the usual pair order ([`configuration.md`](configuration.md#release-and-deployment-order)):

1. **Scale the runner to 0** and wait for the pod to be gone.
2. **Deploy ADAS.**
3. **Deploy the matching runner build** (0.23.0).
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

Before 0.23.0 a blob that failed to ingest could go missing from analytics, and only a log line and an aggregate counter
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

**Recover blobs an earlier failure left behind (recommended, once).** Before 0.23.0, a blob that failed on the
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
| Per-row LLM write-back to an aligned enrichment without `__partition_v` | `422 validation_error` | Runner 0.23.0 echoes it (§2) |
| Registering a pipeline onto an aligned enrichment that reads a table other than the enrichment's source, or has a group trigger | `422 pipeline_validation_failed` | Read the enrichment's own source with a row trigger |
| Importing a bundle whose identity-keyed enrichment declares a `request_time` that is nullable or not a `timestamp` | `422 validation_error` | Rename the column, or make it a non-nullable `timestamp` |
| API description in `oidc` mode needs a credential | `401` on `/v3/api-docs` and the Swagger UI | Send a credential, or set `DISABLE_SWAGGER_AUTHORIZATION=true` (§1) |
| Evaluator archive removed (`GET /v1/evaluators*`) | `404 not_found` | Read the definition from the pipeline's `transform` (`GET /v1/pipelines/{name}`); dump the tables first if the pre-fold definitions are wanted (§1) |
| OpenAPI component `EvaluatorVarDto` renamed `TransformVarDto`; `Evaluators` tag gone | Generated clients change; wire JSON does not | Regenerate the client |
| Ingest trims inline files from payload bodies (`SYNC_INLINE_FILES_DROP_TRUNCATED=true` by default) | A body truncated at 4 MiB is stored without its half inline file | Set `SYNC_INLINE_FILES_DROP_TRUNCATED=false` to store bodies as sent (§7) |
| Request-delta storage, opt-in (`SYNC_REQUEST_BODY_MODE=delta`) | A later chat turn's `request_body` holds only its new messages, and no tools when they equal the earlier turn's. Every chat turn's `request_body`, the first included, is re-serialized (keys sorted, no whitespace) and its `cache_control` markers move to `request_cache_control` | Only when you set `delta`: first re-point pipelines binding `dial_usage_log_payload.request_body` to `request_body_full`, which returns the whole request with its `cache_control` markers, and move other readers that need a turn's whole request to it; check readers that need the body text as sent (§7) |
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
  days only under `whole-with-path` or `delta` on 0.23.0:** a build older than 0.23.0, or `whole`, writes rows without the
  path, and the later turns that link to them can no longer be rebuilt. Roll back from `delta` to `whole-with-path`
  for that reason, and set `delta` only once no pre-0.23.0 pod is left ingesting.
  **Before setting `delta`, re-point every pipeline whose `transform.inputs` binds `dial_usage_log_payload.request_body`
  to `dial_usage_log_payload.request_body_full`** (and bind `request_body_rebuild` too if the pipeline should tell a
  `partial` rebuild apart), and move saved queries and other readers that need a turn's whole request the same way.
  `request_body` keeps returning what is stored; `request_body_full` returns the rebuilt request, and under `whole` and
  `whole-with-path` it returns the stored body marked `stored`, so re-pointing first is safe. ADAS does not rewrite
  pipelines. To roll back to a build without the field, re-point such pipelines back to `request_body` and switch
  ingest to `whole-with-path` first: an older build refuses a pipeline bound to `request_body_full`.
- `QUERY_REQUEST_BODY_REBUILD_MAX_ROUNDS` and `QUERY_REQUEST_BODY_REBUILD_MAX_ANCESTORS` — new; see
  [`configuration.md`](configuration.md#request-body-rebuild).
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
