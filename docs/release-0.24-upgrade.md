# ADAS 0.24 — upgrade notes

What an environment has to do to move from **0.23.3** to **0.24**. An environment on an earlier build first
follows [`release-0.23-upgrade.md`](release-0.23-upgrade.md), through its 0.23.3 section (§9).

This release runs ADAS on replicated ClickHouse storage, on every deployment, a single server included
([`clickhouse-replication.md`](clickhouse-replication.md)). That makes it more than a redeploy:

- **ClickHouse Keeper and a `Replicated` database are required.** An `Atomic` database is refused at startup,
  before any change, until an operator changes its engine (§2).
- **Plain tables convert automatically on the first start**, with a deploy that stops the previous version
  first (§3).
- **`CLICKHOUSE_HOST` must reach only the primary replica** (§5).
- **Four new writer grants** (§1).
- **Chart `y_field` is an array** in saved-query and dashboard responses (§6).
- **A coordinated deploy with the Enrichment Runner**: the contract version rises from `4` to `6`, and a runner on
  any other version is refused, so enrichment pauses until both sides match (§7, §10).
- **The recommended catalog reads the DIAL CLI harness from `X-DIAL-CLI-Harness`**: a deployment that already
  registered `usage-client-identity-live` applies it by hand (§8).
- **Under `SYNC_REQUEST_BODY_MODE=delta`, a repeated large request body that is not JSON is stored once**, so later
  rows repeating it have an empty `request_body` (§9).
- **A group pipeline may set its own input token budget** with `advanced.input_token_budget` (§10).
- **Catalog bundles are `format_version` `4`**, which a 0.23 ADAS refuses to import, and carry table access on
  request (§11).
- **Arming an `llm` pipeline composes its response schema**, so one that does not compose is refused when enabled;
  route outputs may bind `object` and `map` columns (§12).
- **A chart may carry a `legend`**, so deploy ADAS before a client that sends it (§13).
- **With Keeper down, row writes, pipeline runs and the data-sync requeue answer `503` in about ten seconds**, and the writer user must be
  allowed two `insert_keeper_*` settings (§14).
- **A column that only row-confined roles allow, and another of the caller's roles does not, is masked rather than
  hidden**: JSON rows carry a reserved `$masked` member, and `$masked` is refused as an output name for everyone (§15).

---

## 1. Before you deploy

- **Keeper.** Embedded in the server on a single node, or a three-member ensemble. Each server also needs
  the `shard` and `replica` macros.
- **Server settings.** In the default profile, `database_replicated_allow_only_replicated_engine = 1`. In
  `<merge_tree>`, `execute_merges_on_single_replica_time_threshold = 1800` and
  `wait_for_unique_parts_send_before_shutdown_ms = 60000`. The reasons are in
  [`clickhouse-replication.md`](clickhouse-replication.md#clickhouse-requirements).
- **Writer grants.**

  ```sql
  GRANT SYSTEM SYNC REPLICA ON <database>.* TO <writer>;
  GRANT SYSTEM SYNC DATABASE REPLICA ON <database>.* TO <writer>;
  GRANT SELECT ON system.clusters TO <writer>;
  GRANT SYSTEM MERGES ON <database>.* TO <writer>;
  ```

  Startup fails naming a missing `SYSTEM SYNC` grant: without them every pipeline run and every ingest cycle
  would be refused with `503`.
  Without `SELECT ON system.clusters` ADAS starts, logs a WARN and routes everything to the primary.
  Without `SYSTEM MERGES` the first-start table conversion (§3) compares row counts with merges running, so a merge
  that collapses rows between the two reads can make it give a table up and fail startup, which a restart retries.
- **NTP** on every ClickHouse node and every ADAS pod: the pipeline safety lag covers clock skew among them.
- **New optional keys.** `CLICKHOUSE_ROUTING_QUERIES`, `CLICKHOUSE_ROUTING_PIPELINES`,
  `CLICKHOUSE_REPLICAS_DISCOVERY_INTERVAL`, `CLICKHOUSE_REPLICAS_SYNC_TIMEOUT` and `CLICKHOUSE_DDL_TASK_TIMEOUT`
  ([`configuration.md`](configuration.md#clickhouse-replicas-and-routing)). The defaults fit most deployments.

## 2. An `Atomic` database

Startup fails before issuing any DDL or writing any catalog row, naming the database and its engine. ADAS
never changes a database engine. Follow
[the manual procedure](clickhouse-replication.md#changing-an-atomic-database-to-replicated), then redeploy.

## 3. A `Replicated` database with plain tables

This is the usual starting point for a deployment managed by ClickHouse's Kubernetes operator: Keeper and a
`Replicated` database already exist, and every ADAS table in it is plain. The first start with this
version converts them in place, before the migrations run. The data moves by hardlink and is not
rewritten. A table larger than `max_table_size_to_drop` needs no server change: the conversion lifts that
limit for its own drops.

- **Stop the previous version first.** Use a `Recreate` strategy or scale to zero before deploying. A
  previous-version instance writing during the conversion would lose those rows.
- **With several registered replicas,** make sure only one of them ever held ADAS data, and drop the others
  with `SYSTEM DROP DATABASE REPLICA` or empty their copies. Otherwise the conversion refuses, naming each
  table and replica.
- **While it runs,** readiness is down, `/v1` answers `503` and no scheduled job runs. Liveness keeps
  answering.
- **With `CLICKHOUSE_MIGRATIONS_ENABLED=false`** nothing is converted and startup fails naming each plain
  table.
- **An interrupted conversion** leaves an `_adas_replicate_<table>` table. Every start fails naming it until a
  start with migrations enabled resumes the conversion.

**Rollback.** A build older than 0.24 reads and writes the converted tables normally, but its table creates
render plain engines, which `database_replicated_allow_only_replicated_engine = 1` refuses.

## 4. ClickHouse unreachable at startup

With `CLICKHOUSE_MIGRATIONS_ENABLED=false`, an instance that cannot reach ClickHouse at startup no longer
serves unverified. It starts with readiness down, `/v1` answering `503` (the PostgreSQL-only endpoints
included) and no scheduled job running, and retries the storage checks every
`CLICKHOUSE_REPLICAS_DISCOVERY_INTERVAL` until they pass. A probe on `/actuator/health/readiness` keeps
traffic away meanwhile; liveness is unaffected. With migrations enabled startup still fails, as before.

## 5. `CLICKHOUSE_HOST`

It now means the primary replica: ingest, API writes and DDL run there. A headless Service over every
ClickHouse pod keeps working while there is one replica. Replace it with an endpoint that reaches only the
primary pod before a second replica joins: startup refuses a host that resolves to several addresses, or
that different replicas answer over fresh connections.

## 6. Multi-series charts

A chart's `y_field` — on a saved query and on a dashboard widget's `inline_query` — is now an array of result
columns, one per series (see [`saved-queries`](../openspec/specs/saved-queries/spec.md)). Responses always carry the
array, so a client that reads `y_field` as a string must move to the array; a one-element array is the old
single-series chart. Requests may still send a string, and charts stored before the upgrade are returned as a
one-element array without a migration.

- **Rollback.** A build older than 0.24 cannot read a chart stored as an array: reading that saved query, the
  scope list it appears in, its revision snapshot, or a dashboard holding such a widget fails. Before rolling back,
  rewrite each stored `y_field` array to its first element in `saved_query.chart`, `saved_query_aud.chart` and
  `dashboard.widgets` (the nested `inline_query.chart`).

## 7. Enrichment pipelines calling a DIAL application route

An `llm` transform may now call a DIAL application's custom route instead of chat completions, with
`preset: dial_app_route` and a `route`. Each output may name a `source`, a JSONata expression over the route's
response such as `intent_counts.check`, and otherwise reads the response member of its own name; outputs bound to
nullable columns may be absent from the response (see [`enrichment-registry`](../openspec/specs/enrichment-registry/spec.md)). Request templates
also accept `{{name:json}}`, which inserts a value as JSON instead of an escaped string.

- **Deploy ADAS and the Enrichment Runner together.** The runner contract version moves from `4` to `6` in this
  release (the route preset and the input token budget of §10), and a runner on any other version is refused
  until both sides match, so enrichment pauses during the skew.
- **Allow each route first.** A route pipeline can be enabled only when its `(deployment, route)` pair is listed
  in `PUT /v1/settings/app-routes` (see [`instance-settings`](../openspec/specs/instance-settings/spec.md)). The
  setting starts absent, which allows no route. A write that drops a pair an enabled pipeline calls is refused
  with `409 app_route_in_use`.
- **Moving a pipeline off a chat-completions shim.** Allow the pair, disable the pipeline, patch `preset`,
  `route`, `request_template` and each nested output's `source`, then enable it again.
- **Rollback.** A build older than 0.24 cannot read a stored `preset: dial_app_route`, so listing, `GET` and the
  sync feed fail on that row even while it is disabled. Before rolling back, re-declare each route pipeline as
  `chat_completion`, clearing `route` and each output's `source`, or delete it. Then roll ADAS and the runner
  back together. For the same reason, store no route pipeline, disabled draft included, until every ADAS pod
  runs 0.24. The migration that admits the setting name stays applied and is harmless to an older build.

## 8. Recommended catalog: DIAL CLI harness header

The `usage-client-identity-live` pipeline in [`recommended-catalog`](recommended-catalog/README.md) now reads
`dial_cli_harness_name` from the `X-DIAL-CLI-Harness` header, falling back to the second token of a legacy combined
`X-DIAL-CLI-Version`, and decides `client_type` `dialcli` on either header. A DIAL CLI that sends the two headers
separately leaves `dial_cli_harness_name` empty under the earlier expression.

Importing the bundle again does not apply this: import never changes an object that exists, so the preview reports
the pipeline as `SKIP` with `differs: true` (see [`catalog-export-import`](../openspec/specs/catalog-export-import/spec.md)).
A deployment that already registered the pipeline applies it by hand:

1. Re-declare `usage-client-identity-live` with the bundle's `client_type` and `dial_cli_harness_name` expressions.
2. Update the `client_type`, `dial_cli_version` and `dial_cli_harness_name` column descriptions of
   `usage_client_identity` from the bundle (a schema patch `update`). Descriptions only; no data changes.
3. Backfill the pipeline over the period in which the separate header was sent. Rows whose payload is still
   retained get their harness; rows past the payload retention window keep it empty.

The backfill is recorded as a re-derivation (see [`class1-enrichment`](../openspec/specs/class1-enrichment/spec.md)),
so a rollup that reads `usage_client_identity`, such as `sessions_rollup`, may be flagged for a rebuild.

## 9. Request-delta storage: a repeated large body is stored once

This applies only under `SYNC_REQUEST_BODY_MODE=delta` or `whole-with-path`.

Under `delta`, a request body of 65,536 characters (UTF-16 code units) or more that is not JSON, such as a chat body cut at the 4 MiB
limit, is now stored once per project, user and UTC day. A later request repeating it exactly is stored with an
empty `request_body`. Read `dial_usage_log_payload.request_body_full` for the body, as for a later chat turn (see
[`data-sync`](../openspec/specs/data-sync/spec.md), "A large request body equal to an earlier one is stored once").
A JSON body is never stored empty, so `json_extract_*` over `request_body` reads as before. A text function over
`request_body` (`length`, `LIKE`, a regular expression) sees the empty string on such a row, where it saw the cut
text before.

Such an empty row holds nothing of its own, so two rules matter once any exist:

- **Re-ingest a UTC day whole, on one release and one configuration.** A row holding a body that is re-ingested
  by a 0.23 instance, under `whole`, with `request_body` left out of ingest (`SYNC_SENSITIVE_COLUMNS`, see
  [`release-0.23-upgrade.md`](release-0.23-upgrade.md) §8), or after a change of
  `SYNC_INLINE_FILES_DROP_TRUNCATED` or `SYNC_INLINE_FILES_MAX_SIZE` no longer answers for the rows stored empty
  after it; they read `partial` with an empty body until the day is re-ingested.
- **Roll back to `whole-with-path` on 0.24, not to 0.23.** A 0.23 build reads every such row as `partial` with an
  empty body.

## 10. Per-pipeline input token budget for group pipelines

The Enrichment Runner trims a group pipeline's assembled input to an approximate token budget before the call, and
that budget was one runner-wide setting. A `group` pipeline may now declare its own with
`advanced.input_token_budget` (see [`enrichment-registry`](../openspec/specs/enrichment-registry/spec.md)).

- **`0` switches the runner's trimming off** for that pipeline, for example a `dial_app_route` application that
  counts tokens and decides what to keep itself. A positive value is the pipeline's own ceiling. Absent keeps the
  runner's configured default.
- **Group pipelines only.** On a `schedule` pipeline the member is refused with `422 pipeline_validation_failed`
  on every write, a disabled draft included, as `trigger.member_select` is. A patch that moves a pipeline from
  `group` to `schedule` leaves it out of `advanced`. A negative value is refused the same way.
- **Restate it on every declaration patch.** `advanced` is replaced, not merged, so a `PATCH` that re-declares the
  pipeline without `advanced.input_token_budget` clears it, and the runner's default trimming applies again.
- **Contract `6`.** The member is part of the runner contract, so the runner must be the 0.24 release that reads
  it (§7).
- **Rollback.** An older build ignores the stored member and drops it on the next rewrite of the pipeline, so the
  runner's default trimming applies again. Roll ADAS and the runner back together.

## 11. Catalog bundle format version 4, and access on request

`POST /v1/catalog/export` now writes `format_version: 4`, because a bundle may carry an `enrich` transform's `route`
(§7), `advanced.input_token_budget` (§10) and, exported with `include_access: true`, the covered tables' access (see
[`catalog-export-import`](../openspec/specs/catalog-export-import/spec.md)). Every export writes `4`, whatever it
carries.

- **Import into 0.24 first.** A 0.23 ADAS refuses a 0.24 bundle with `422 catalog_bundle_version_unsupported`.
  Upgrade the target before importing a bundle exported by 0.24.
- **Older bundles still import.** A version `1`, `2` or `3` bundle imports into 0.24 unchanged.
- **Access travels only on request.** `include_access: true` in the export body and `include_access=true` on the
  import, each requiring `TABLE_GRANT`, carry every covered table's role entries, system tables included, and upsert
  them per role on the target, never removing one. Without them nothing about access changes. The runbook is
  [`catalog-promotion.md`](catalog-promotion.md).
- **Import responses always carry an `access` list**, empty without `include_access=true`, and an access entry's
  `import_action` may be `REPLACE`. A client that parses these responses strictly must accept both.

## 12. Route outputs may bind `object` and `map` columns; arming composes the schema

A `dial_app_route` output may now bind an `object` column, stored as the JSON document the route's `source`
yields, or a `map` column of strings. Neither column can be nullable, so such an output is always required. A
`source` that filters members, for example dropping zero counts, must still yield `{}`, and should yield nothing
when the response lacks the member:
`$exists(intent_counts) ? $merge([{}, $sift(intent_counts, function($v){ $v != 0 })])`. `$sift` alone yields
nothing when every member is zero, and the unit is then dead-lettered. Without the `$exists` guard, a response
missing `intent_counts` would be stored as `{}` instead. A `chat_completion` output bound to either type is
refused (see [`enrichment-registry`](../openspec/specs/enrichment-registry/spec.md)).

Arming now composes each `llm` pipeline's response schema, so a declaration whose schema cannot be composed is
refused with `422 pipeline_validation_failed` when it is enabled or its declaration is patched while enabled. A
draft is still stored. Before, such a pipeline was accepted when armed, was then left out of the runner's feed and refused
on a compiled read, so it never ran. Nothing that runs today starts failing: the refusal moves to arming, where the
author sees it.

- **Before upgrading, find armed pipelines that never ran for this reason.** Compare the names in
  `GET /v1/pipelines?kind=enrich&enabled=true` with those in `GET /v1/pipelines?kind=enrich&enabled=true&view=compiled`.
  The missing names include these pipelines, and incomplete declarations too. Confirm each with
  `GET /v1/pipelines/{name}?view=compiled`, which answers `422` saying its outputs compose to no response schema,
  or with `POST /v1/pipelines/{name}/preview`, which names the output and the column. ADAS also logs
  an ERROR containing `compose to no response schema` for each one. Fix the output or the column before the
  pipeline's next patch; a catalog import preview reports such a pipeline as `armable: false`, unless the target already has it enabled.
- **Rollback.** A build older than 0.24 cannot read a `dial_app_route` pipeline at all (§7), so follow §7:
  re-declare each route pipeline as `chat_completion` on scalar columns, or delete it, before rolling back.

## 13. Chart legend

A chart, on a saved query and on a dashboard widget's `inline_query`, may now carry `legend` `{ visible, position }`
(see [`saved-queries`](../openspec/specs/saved-queries/spec.md)). The member is additive: a chart stored without it
reads back without it, and no row is migrated.

- **Deploy ADAS before the client sends `legend`.** A build older than 0.24 refuses every chart save that carries it
  with `400 bad_request`.
- **Rollback.** A build older than 0.24 refuses chart saves carrying `legend` again. It reads a stored `legend` but
  leaves it out of its responses, so a client that reads a chart and saves it back, even without touching the
  legend, deletes the stored `legend`. Stop the client sending `legend` before rolling back, and expect each chart's
  legend to be lost on its next save.

## 14. Writes with Keeper down

ClickHouse's default Keeper retries held an insert into a replicated table whose replica had lost Keeper for about
two minutes. `POST /v1/tables/{name}/rows`, the pipeline write-back `POST /v1/pipelines/{name}/rows`, OTel ingest,
pipeline runs and `POST /v1/data-sync/failures/retry` now cap those retries and answer `503 clickhouse_unavailable`
in about ten seconds once the replica has noticed the lost session; the requeue answered `500` before. Data-sync
ingest and enrichment relayout copies keep ClickHouse's defaults and pause through a short Keeper outage (see
[`clickhouse-replication`](../openspec/specs/clickhouse-replication/spec.md)).

- **Settings.** Those inserts, and no other statement, now send `insert_keeper_max_retries` and
  `insert_keeper_retry_max_backoff_ms` on the writer user. A settings profile constraint that makes either
  read-only, or whose `min`/`max` excludes the value sent (`8`, `2000`), refuses those inserts; startup, DDL and
  ingest are unaffected. Allow both before deploying.
- **Callers** of `POST …/rows`, the OTel endpoints and the enrichment runner's write-back get the `503` they
  already handle, sooner. Fewer retries also mean a Keeper blip during an insert's commit more often ends in
  `UNKNOWN_STATUS_OF_INSERT`, whose retry on an `append` table can store the rows twice (at-least-once, as before).
- **Rollback.** An earlier build sends neither setting and waits out ClickHouse's defaults again.

## 15. Masked columns and `$masked`

On 0.23.3 a caller saw a column only when every role it reads through allowed it, so gaining a column-restricted role
could hide columns. Now a column that only row-confined roles allow, and another of the caller's roles does not, is
**masked**: listed and queryable, its value present on the rows an allowing role admits and absent elsewhere
([`row-level-security.md`](row-level-security.md#column-policies)). Gaining a role no longer removes a column from the
rows the caller's other roles admit, except on a table where masking falls back to hiding: one whose source or
enrichments store a column named after a table of the entity.

- **JSON rows.** `POST /v1/queries/execute` and `/execute-sql`, and so the saved queries and dashboard widgets run
  through them, add a reserved `$masked` member to each row holding an absent cell or a partial aggregate or window
  cell, listing those output columns. A client that renders every row member as a column, such as a generic JSON
  panel, shows one more column on those rows: declare its columns. A consumer that sums a masked column reads
  `$masked`, or it reads a partial total as complete. CSV export and Flight SQL return nulls with no flag.
- **A reserved name, for every caller (breaking).** An output column named `$masked` is refused `400 bad_request`
  (Flight SQL `INVALID_ARGUMENT`) on every query surface, and a saved query or widget that projects one is refused on
  save. Rename such outputs before upgrading.
- **New refusals beside a masked column.** `INTERSECT` and `EXCEPT` over an output that reads one, and an output name
  or lambda parameter equal to a table the statement reads or beginning with `<table>.`, are refused `400`. A query
  that names the column failed on 0.23.3 with `unknown field`; one that reaches it through `SELECT *` ran there and is
  refused now.
- **Rows-only entries narrow.** An entry carrying `rows` on a role with no read path now narrows, through its
  `columns`, what the rows it adds show; with `columns: null` it narrows nothing, as on 0.23.3.
- **Paths without a row filter.** Pipeline authoring and the runner refuse a masked column as a hidden one
  (`403 column_not_entitled`), and so does a TTL over one.
- **Discovery** marks a masked field `"masked": true`, and `permissions.columns_restricted` is `true` when a column is
  masked as well as when one is hidden.
- **Rollback.** 0.23.3 hides masked columns again: queries, saved queries and widgets over them fail with
  `unknown field` for those callers. No data is at risk.

## Summary

| Change | Effect | Action |
|---|---|---|
| Replicated ClickHouse storage required | An `Atomic` database is refused at startup; plain tables in a `Replicated` database convert on the first start | Set up Keeper, server settings and writer grants (§1); change an `Atomic` engine by hand (§2); stop the previous version before deploying (§3) |
| `CLICKHOUSE_HOST` means the primary replica | Startup refuses a host that resolves to several addresses or that several replicas answer | Point it at an endpoint that reaches only the primary pod before a second replica joins (§5) |
| Chart `y_field` is an array (breaking response shape) | Saved-query and dashboard responses return `y_field` as an array; a string is still accepted on write | Move clients that read `y_field` to the array; before a rollback, rewrite stored arrays (§6) |
| Transform preset `dial_app_route` and the `app-routes` setting; runner contract `6` | A runner on any contract other than `6` is refused | Deploy the matching Enrichment Runner in the same window; allow each route before enabling its pipeline; before a rollback, re-declare or delete route pipelines (§7) |
| Recommended catalog reads the DIAL CLI harness from `X-DIAL-CLI-Harness` | A registered `usage-client-identity-live` keeps the earlier expression; a bundle import skips it | Re-declare the pipeline, patch the column descriptions, backfill (§8) |
| Under `delta`, a repeated large request body that is not JSON is stored once | Later rows repeating it have an empty `request_body` | Read `request_body_full`; re-ingest whole UTC days on one release and configuration; roll back to `whole-with-path` on 0.24 (§9) |
| `advanced.input_token_budget` on `group` pipelines | `0` switches the runner's trimming off for that pipeline; refused on a `schedule` pipeline | Optional: declare it where the runner's trimming is unwanted; nothing is lost on a rollback but the setting (§10) |
| Catalog bundles are `format_version` `4`; access travels on request | A 0.23 ADAS refuses a 0.24 bundle with `422 catalog_bundle_version_unsupported`; import responses carry an `access` list | Upgrade an import target before promoting to it from 0.24; accept `access` and `REPLACE` in import responses (§11) |
| Route outputs bind `object` / `map` columns; arming composes the response schema | A `chat_completion` output on either type, or any schema that does not compose, is refused `422 pipeline_validation_failed` when armed | Find enabled `llm` pipelines missing from `view=compiled` (they never ran) and fix them before their next patch; before a rollback, follow §7 for route pipelines (§12) |
| Chart `legend` on saved queries and dashboard inline queries | Additive; a build older than 0.24 refuses a chart save carrying it with `400` and drops a stored legend on the chart's next save | Deploy ADAS before the client sends `legend`; stop the client sending it before a rollback (§13) |
| Row writes, pipeline runs and the data-sync requeue cap ClickHouse's Keeper retries | With Keeper down they answer `503 clickhouse_unavailable` in about ten seconds, not two minutes; data-sync ingest and relayout copies are unchanged | Let the writer user set `insert_keeper_max_retries` and `insert_keeper_retry_max_backoff_ms` (§14) |
| Masked columns; reserved `$masked` (breaking) | A column only row-confined roles allow shows on their rows instead of being hidden; JSON rows list absent cells under `$masked`; `$masked`, and for masked callers `INTERSECT`/`EXCEPT` and table-named aliases, are refused `400` | Rename `$masked` outputs; declare columns in generic JSON panels; read `$masked` where a masked column is summed; expect `unknown field` for those callers after a rollback (§15) |
