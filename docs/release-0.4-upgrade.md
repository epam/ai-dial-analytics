# ADAS release-0.4 — upgrade notes

What an environment has to do to move from **release-0.3** to **release-0.4**. An environment still on an
earlier build first follows [`release-0.3-upgrade.md`](release-0.3-upgrade.md).

Two things make this release more than a redeploy:

- a **coordinated deploy** with the Enrichment Runner, because the contract version rises from `3` to `4`;
- an **automatic relayout** of existing row-grain enrichments, which holds their writers for two to three
  minutes each, ten minutes after the deploy.

There is **no rollback to 0.3 once a table is aligned** (§3): a 0.3 build writes aligned tables with no
`request_time`, so its rows land in the 1970 partition, which the inherited TTL drops at the next merge.

---

## 1. Before you deploy

- **Memory per statement.** Leave `PIPELINE_BUDGET_MAX_MEMORY` at its `2GB` default, or at least ~1 GiB: a
  relayout slice needs on the order of 1 GiB. Below that, every slice is refused, the relayout fails and it is retried
  from the beginning on every start.
- **Disk.** The copy needs the table's size again until the previous table is dropped.
- **Database engine and grants.** The database must be `Atomic` or `Replicated` (verified on `Replicated` with ClickHouse
  26.3), otherwise the table is left `layout: unsupported`. The writer account needs the full grant in
  [`clickhouse-server.md`](clickhouse-server.md) — the relayout creates, exchanges and drops tables.
- **New system-table grants.** 0.4 reads `system.parts` and `system.data_skipping_indices` on the writer
  account, which `<database>.*` does not cover. Grant them before the deploy:

  ```sql
  GRANT SELECT ON system.parts TO <writer>;
  GRANT SELECT ON system.data_skipping_indices TO <writer>;
  ```

  Without them the relayout logs `ACCESS_DENIED … SELECT ON system.parts` and fails before the exchange.
  The table stays unchanged and in service; grant, then restart the pod — it retries only on start.
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
- **Actuator moves to its own port, 9464.** Probes, `/actuator/info` and `/actuator/prometheus` now answer only
  on the management port (`MANAGEMENT_SERVER_PORT`, default `9464`) and are `404` on the API port 5000. Before
  deploying, point the liveness and readiness probes and the Prometheus scrape at port 9464 and declare it as a
  container port: a probe left on 5000 fails and restarts the pod. Set `MANAGEMENT_SERVER_PORT=5000` to keep the
  old layout. The image's Docker `HEALTHCHECK` already calls 9464.
- **Default service name is now `ai-dial-analytics`.** The `OTEL_SERVICE_NAME` default changes from
  `analytics-data-access-service`, so a deployment that leaves it unset exports traces, metrics and logs under the
  new `service.name`. Dashboards, alerts and queries that filter on the old name stop matching. To keep the old
  identity, set `OTEL_SERVICE_NAME=analytics-data-access-service` before the deploy.
- **Flight SQL server name is now `ai-dial-analytics`.** `GetSqlInfo` reports it as `FLIGHT_SQL_SERVER_NAME`, and
  JDBC and ADBC clients show it as the database product name (`DatabaseMetaData.getDatabaseProductName()`).
  Queries and results do not change. The name is fixed, not configurable, so a client that keys a connection
  profile, dialect or driver setting on `analytics-data-access-service` has to match the new name instead.
- **Evaluator archive (optional).** 0.4 drops the read-only evaluator archive and the rule history left
  over from the fold (§4). The drop cannot be undone: the dump is the only copy of the definitions as they
  stood before the evaluator fold (0.2.2) and of the pre-fold rule revisions. To keep them, dump the four
  tables first:

  ```bash
  pg_dump --table=evaluator --table=evaluator_version --table=rule_legacy --table=rule_legacy_aud \
    <database> > evaluator-archive.sql
  ```

## 2. Deploy order — ADAS and the Enrichment Runner ship as a pair

The runner must be its 0.4 build. ADAS now requires **`Adas-Contract-Version: 4`** on the four runner-only
endpoints (`scan`, `group-rows`, `materialize`, `rows`), and each side refuses the other's old version. The
contract change: for a per-row LLM pipeline over an aligned enrichment (§3), the scan returns `__partition_v`,
which the runner echoes back on every write-back row.

Follow the usual pair order ([`configuration.md`](configuration.md#release-and-deployment-order)):

1. **Scale the runner to 0** and wait for the pod to be gone.
2. **Deploy ADAS.**
3. **Deploy the matching runner build** (0.4).
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

## 4. Migrations (automatic on startup)

| Migration | Effect |
|---|---|
| Postgres `V47` | Adds `enrichment_relayout`: the progress and writer hold of the automatic relayout (§3). |
| Postgres `V48` | Drops the evaluator archive (`evaluator`, `evaluator_version`), `pipeline.evaluator_name` / `evaluator_version` and their `pipeline_aud` mirrors, and `rule_legacy` / `rule_legacy_aud`. Keeps `pipeline.legacy_rule_id`, which 0.3 still maps; the next release drops it. |

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

## 6. Breaking changes at a glance

| Change | Symptom | Action |
|---|---|---|
| Runner data-plane needs `Adas-Contract-Version: 4` | `409` | Redeploy the runner in the same window (§2) |
| Writer reads `system.parts` / `system.data_skipping_indices` | Relayout ERROR `ACCESS_DENIED`; schema `PATCH` dropping a column fails | Grant both (§1), restart |
| Row write to an aligned enrichment without `request_time` (`POST /v1/tables/{name}/rows`) — a new table, or an existing one once the relayout aligned it | `422 validation_error` | Send the source row's `request_time` (§3) |
| Per-row LLM write-back to an aligned enrichment without `__partition_v` | `422 validation_error` | Runner 0.4 echoes it (§2) |
| Registering a pipeline onto an aligned enrichment that reads a table other than the enrichment's source, or has a group trigger | `422 pipeline_validation_failed` | Read the enrichment's own source with a row trigger |
| Importing a bundle whose identity-keyed enrichment declares a `request_time` that is nullable or not a `timestamp` | `422 validation_error` | Rename the column, or make it a non-nullable `timestamp` |
| API description in `oidc` mode needs a credential | `401` on `/v3/api-docs` and the Swagger UI | Send a credential, or set `DISABLE_SWAGGER_AUTHORIZATION=true` (§1) |
| Evaluator archive removed (`GET /v1/evaluators*`) | `404 not_found` | Read the definition from the pipeline's `transform` (`GET /v1/pipelines/{name}`); dump the tables first if the pre-fold definitions are wanted (§1) |
| OpenAPI component `EvaluatorVarDto` renamed `TransformVarDto`; `Evaluators` tag gone | Generated clients change; wire JSON does not | Regenerate the client |
| Ingest trims inline files from payload bodies (`SYNC_INLINE_FILES_DROP_TRUNCATED=true` by default) | A body truncated at 4 MiB is stored without its half inline file | Set `SYNC_INLINE_FILES_DROP_TRUNCATED=false` to store bodies as sent (§7) |
| Default `service.name` changed to `ai-dial-analytics` | Telemetry arrives under the new service name; filters on `analytics-data-access-service` find nothing | Set `OTEL_SERVICE_NAME=analytics-data-access-service` to keep the old name (§1) |
| Flight SQL server name changed to `ai-dial-analytics` | `GetSqlInfo` and JDBC/ADBC metadata report the new product name; client settings keyed on the old name stop applying | Match the new name in the client; it cannot be set back (§1) |
| `AGGREGATE_PREVIEW_ROWS` default lowered from `20` to `5` | An aggregate preview returns fewer rows | Set it back to `20` if wanted |

## 7. New configuration

- `DISABLE_SWAGGER_AUTHORIZATION` (default `false`) — serve the API description without a credential in `oidc`
  mode; see [`configuration.md`](configuration.md#api-documentation-springdoc--swagger-ui).
- `SYNC_INLINE_FILES_DROP_TRUNCATED` (default `true`) — cut a payload body truncated at 4 MiB back to before its half
  inline file; see [`column-mapping.md`](column-mapping.md).
- `SYNC_INLINE_FILES_MAX_SIZE` (default unset) — remove inline files whose stored base64 is longer than this size
  (`256KB`, `1MB`; `0` removes all). An earlier `SYNC_INLINE_FILES_MODE` no longer exists and is ignored if left set:
  replace `keep` with `SYNC_INLINE_FILES_DROP_TRUNCATED=false` and `drop` with `SYNC_INLINE_FILES_MAX_SIZE=0`.
- `SYNC_MAX_LINE_BYTES` (default `67108864`) — the largest decompressed log line ingest reads; a longer line is skipped and
  counted as malformed.
