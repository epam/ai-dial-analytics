# ADAS 0.24 — upgrade notes

What an environment has to do to move from **0.23** to **0.24**. An environment on an earlier build first
follows [`release-0.23-upgrade.md`](release-0.23-upgrade.md).

This release runs ADAS on replicated ClickHouse storage, on every deployment, a single server included
([`clickhouse-replication.md`](clickhouse-replication.md)). That makes it more than a redeploy:

- **ClickHouse Keeper and a `Replicated` database are required.** An `Atomic` database is refused at startup,
  before any change, until an operator changes its engine (§2).
- **Plain tables convert automatically on the first start**, with a deploy that stops the previous version
  first (§3).
- **`CLICKHOUSE_HOST` must reach only the primary replica** (§5).
- **Four new writer grants** (§1).
- **Chart `y_field` is an array** in saved-query and dashboard responses (§6).
- **`*` in a read-side grant list means every authenticated caller**: check for a stored `*` before upgrading (§7).
- **Row policies may match the caller's own rows** through `caller.user_hash`, which needs new `logging-*` settings (§8).
- **A coordinated deploy with the Enrichment Runner**: the contract version rises from `4` to `6`, and a runner on
  any other version is refused, so enrichment pauses until both sides match (§9, §14).
- **An enrichment's read access follows its source table.** A migration removes its own read-side grants and row
  policies, so check who gains and loses access before upgrading (§10).
- **Table access is managed one role at a time** at `/v1/tables/{name}/access?role=`, with new column policies. The
  old access and row-policy endpoints are removed (§11).
- **The recommended catalog reads the DIAL CLI harness from `X-DIAL-CLI-Harness`**: a deployment that already
  registered `usage-client-identity-live` applies it by hand (§12).
- **Under `SYNC_REQUEST_BODY_MODE=delta`, a repeated large request body that is not JSON is stored once**, so later
  rows repeating it have an empty `request_body` (§13).
- **A group pipeline may set its own input token budget** with `advanced.input_token_budget` (§14).
- **Catalog bundles are `format_version` `3`**, which a 0.23 ADAS refuses to import (§15).
- **Five spending-limit columns on `dial_usage_log`** from ClickHouse `V18`, which an operator with migrations
  disabled applies before starting the new build (§16).

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

**Table access.** Do these before deploying:

1. **Check for a stored `*`** in `get`, `read` and `sensitive` lists and row policies, and remove the ones you did
   not mean to open to every caller (§7).
2. **Run the two enrichment queries** in §10: decide for each role that gains access whether to hide the columns
   from it, and grant each role that loses access on the source. Save the lists and policies for a rollback (§10).
3. **Give runner roles the source's clearance** where the source's partition, version or identity column is
   `sensitive` (§11).
4. **Update access-API clients**: move from the old `/access` and `/row-policies` calls to
   `/v1/tables/{name}/access?role=`, and read `roles` instead of `access` and `row_policies` in revision snapshots
   (§11).
5. **Plan a freeze**: no access, table or pipeline edits until every ADAS pod runs 0.24 (§10, §11).

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

## 7. The `*` grantee

`*` in a table's `get`, `read` or `sensitive` grant list, or as the role of a row policy, now stands for every
authenticated caller (see [`row-level-security.md`](row-level-security.md)). Earlier builds stored `*` as an
ordinary role name that matched only a caller carrying a provider role spelled `*`.

- **Before upgrading, look for a stored `*`.** A table that already names `*` in a read-side list becomes readable
  by every authenticated caller, with its sensitive columns when the list is `sensitive`. Remove the entry with the
  previous release's `PUT /v1/tables/{name}/access` unless that is what you want. After the upgrade, the startup log
  lists every table on which the `*` entry carries a privilege or a row policy. It skips an enrichment's `get`,
  `read` and `sensitive`, which confer nothing any more (and which the `V56` migration clears, a `*` included; see
  below); a `*` row policy on one is still listed.
- A `*` stored with `write` or `modify` grants nothing, and the next `PUT /v1/tables/{name}/access?role=*` drops it.
- **Flight SQL admits every authenticated caller.** The connection gate used to turn away a caller holding no
  privilege and no provider role; such a caller can now be reached by a `*` grant. Each call is still decided per
  table.
- **Rollback.** A build older than 0.24 reads `*` as an ordinary role name again. Callers who read a table only
  through a `*` grant lose access to it, and a `*` row policy confines nobody, so a caller it confined who reads the
  table another way sees every row. Delete `*` policies, or the read paths they narrow, before rolling back if a
  rollback must not widen reads.

## 8. Row policies on the caller's own rows

A row policy may compare a `string` column with a value of the caller reading the table, `caller.user_hash` or
`caller.project`, and an entry's `rows` (`PUT /v1/tables/{name}/access?role={role}`, see
[Role-keyed table access](#11-role-keyed-table-access)) accepts the policy as a SQL condition in `where` instead of
`filter` (see [`row-level-security.md`](row-level-security.md#own-rows-comparing-a-column-with-the-caller)).
Nothing changes for stored policies.

- **`caller.user_hash` needs new settings.** It is derived the way DIAL Core derives its `user.id` log field, from
  `providers.<name>.logging-key`, `.logging-salt` and `.obfuscate-user-email` for an identity provider
  (see [`configuration.md`](configuration.md#identity-providers)), and from `API_KEY_LOGGING_KEY`,
  `API_KEY_LOGGING_SALT` and `API_KEY_OBFUSCATE_USER_EMAIL` for a per-user API key
  (see [`configuration.md`](configuration.md#api-key-authentication)). Set them to the values DIAL Core uses for
  the same provider; leave the API-key settings unset when Core's providers use different salts. The salt is a
  credential: keep it in the deployment's secret store. Without the settings a caller has no user hash, and a
  policy matching on `caller.user_hash` gives that caller no rows.
- **Rollback.** A build older than 0.24 refuses a policy that uses a caller value, so every read that policy
  confines fails. Replace or delete such policies before rolling back.

## 9. Enrichment pipelines calling a DIAL application route

An `llm` transform may now call a DIAL application's custom route instead of chat completions, with
`preset: dial_app_route` and a `route`. Each output may name a `source`, a JSONata expression over the route's
response such as `intent_counts.check`, and otherwise reads the response member of its own name; outputs bound to
nullable columns may be absent from the response (see [`enrichment-registry`](../openspec/specs/enrichment-registry/spec.md)). Request templates
also accept `{{name:json}}`, which inserts a value as JSON instead of an escaped string.

- **Deploy ADAS and the Enrichment Runner together.** The runner contract version moves from `4` to `6` in this
  release (the route preset and the input token budget of §14), and a runner on any other version is refused
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

## 10. An enrichment's read-side access is its source's

An enrichment is always read through its source, so its `get`, `read` and `sensitive` access and its rows now come
from the source (see [`table-access-control`](../openspec/specs/table-access-control/spec.md)). `write` and `modify`
stay its own.

- The migration `V56` removes every enrichment's `get`, `read` and `sensitive` lists and its row policies.
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

For each row, accept the gain or hide the columns from that role as described above. Column policies exist from 0.24
on, so set them right after the upgrade; mark columns `sensitive` before it.

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

**During the rollout**, make no access or pipeline edits until every pod runs 0.24.

**Rollback.** `V56` cannot be undone. After redeploying the previous release, restore the saved lists and policies
through its old `/access` and `/row-policies` endpoints. Upgrading again does not re-run `V56`; then reduce each
restored enrichment entry to `write` and `modify`.

## 11. Role-keyed table access

A table's access is now one entry per provider role, `{"privileges", "rows", "columns"}`, at
`/v1/tables/{name}/access?role={role}` (see [`row-level-security.md`](row-level-security.md)). Stored grants and
policies carry over as entries; `V57` adds the new columns.

**Moving clients** (`...` is `/v1/tables/{name}`):

| 0.23 call | 0.24 call |
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
or, for a deleted table, what they hid when it was deleted.

**Runners and pipeline authors.** A runner needs the source's clearance when the source's partition, version or
identity column is `sensitive`, or it gets `403 sensitive_column_not_entitled`. A malformed `enrich` fragment over a
source with sensitive columns answers `403` instead of `422` to an author without the clearance.

**During the rollout**, make no access or table edits until every pod runs 0.24: old pods ignore column policies.

**Rollback.** An older build ignores column policies, so every column they hide becomes visible. Delete them first if
that must not happen.

## 12. Recommended catalog: DIAL CLI harness header

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

## 13. Request-delta storage: a repeated large body is stored once

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
  by a 0.23 instance, under `whole`, with `SYNC_SENSITIVE_COLUMNS_ENABLED=false`, or after a change of
  `SYNC_INLINE_FILES_DROP_TRUNCATED` or `SYNC_INLINE_FILES_MAX_SIZE` no longer answers for the rows stored empty
  after it; they read `partial` with an empty body until the day is re-ingested.
- **Roll back to `whole-with-path` on 0.24, not to 0.23.** A 0.23 build reads every such row as `partial` with an
  empty body.

## 14. Per-pipeline input token budget for group pipelines

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
  it (§9).
- **Rollback.** An older build ignores the stored member and drops it on the next rewrite of the pipeline, so the
  runner's default trimming applies again. Roll ADAS and the runner back together.

## 15. Catalog bundle format version 3

`POST /v1/catalog/export` now writes `format_version: 3`, because a bundle may carry an `enrich` transform's `route`
(§9) and `advanced.input_token_budget` (§14) (see
[`catalog-export-import`](../openspec/specs/catalog-export-import/spec.md)). Every export writes `3`, whatever its
pipelines use.

- **Import into 0.24 first.** A 0.23 ADAS refuses a 0.24 bundle with `422 catalog_bundle_version_unsupported`.
  Upgrade the target before promoting from an upgraded environment.
- **Older bundles still import.** A version `1` or `2` bundle imports into 0.24 unchanged.

## 16. Spending-limit columns on `dial_usage_log`

DIAL Core reports the spending-limits check of a token caller's model call as a `spending_limits` object. ADAS
stores it in five nullable columns of `dial_usage_log`, tagged `cost`: `spending_limits_mode`, `budget_cost_object`,
`budget_outcome`, `budget_policy` and `budget_assignment` (see
[`data-sync`](../openspec/specs/data-sync/spec.md), "Reported spending limits are persisted").

- **Migrations.** ClickHouse `V18` adds the columns with `ALTER ADD COLUMN`, a metadata-only change that needs no
  new grant. Postgres `V59` adds their catalog rows. With `CLICKHOUSE_MIGRATIONS_ENABLED=false`, apply `V18` by hand
  **before** starting the new build: startup validation refuses a mapping whose columns the table lacks. With
  `SYNC_VALIDATE_SCHEMA=false` as well, nothing stops that start: rows are then written without the five values,
  their blobs are recorded as ingested, and a query that reads the columns, a wildcard included, fails until
  `V18` is applied. With
  `POSTGRES_MIGRATIONS_ENABLED=false`, apply `V59` by hand too: without it ingest fills the columns, but no query
  surface knows them (`unknown field`), and nothing warns at startup.
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
  on one release and configuration (§13).
- **A refused call has no row.** `budget_outcome` `no_budget` and `exhausted` appear in shadow mode only.
- **Rollback.** A 0.23 build does not declare the columns, so it ignores them and they read `NULL` on the rows it
  ingests.

## Summary

| Change | Effect | Action |
|---|---|---|
| Replicated ClickHouse storage required | An `Atomic` database is refused at startup; plain tables in a `Replicated` database convert on the first start | Set up Keeper, server settings and writer grants (§1); change an `Atomic` engine by hand (§2); stop the previous version before deploying (§3) |
| `CLICKHOUSE_HOST` means the primary replica | Startup refuses a host that resolves to several addresses or that several replicas answer | Point it at an endpoint that reaches only the primary pod before a second replica joins (§5) |
| `*` means every authenticated caller in read-side grant lists and row policies; Flight SQL admits every authenticated caller | A `*` stored earlier as a role name opens that table to all callers | Remove unintended `*` entries before upgrading; check the startup log after; before a rollback, delete `*` policies that must keep confining (§7) |
| Chart `y_field` is an array (breaking response shape) | Saved-query and dashboard responses return `y_field` as an array; a string is still accepted on write | Move clients that read `y_field` to the array; before a rollback, rewrite stored arrays (§6) |
| Transform preset `dial_app_route` and the `app-routes` setting; runner contract `6` | A runner on any contract other than `6` is refused | Deploy the matching Enrichment Runner in the same window; allow each route before enabling its pipeline; before a rollback, re-declare or delete route pipelines (§9) |
| Row policies may use `caller.user_hash` / `caller.project` | `caller.user_hash` matches no row until the `logging-*` settings are set | Set the `logging-*` settings to DIAL Core's values where own-rows policies are used; before a rollback, remove caller-value policies (§8) |
| An enrichment's read access and rows follow its source (`V56`) | Source readers gain its columns, enrichment-only grantees lose them, its row policies are gone | Run the two §10 queries, hide or grant per role, save the lists for a rollback (§10) |
| Sensitive-grant subset rule removed | Derived tables no longer need clearance on their input | Review who holds each derived table's clearance (§10) |
| Role-keyed access API, column policies (breaking) | Old access and row-policy calls are gone; snapshots carry `roles`; hidden columns are absent and refused `403 column_not_entitled` | Move clients using the table in §11; name the cursor columns in a restricted runner's list; delete column policies before a rollback (§11) |
| Runner clearance, `enrich` parse failures, new codes | New `403 sensitive_column_not_entitled` cases; `409 access_conflict`, `409 table_busy`, `409 column_in_column_policy` | Give runners the source's clearance; handle the new codes (§11) |
| Recommended catalog reads the DIAL CLI harness from `X-DIAL-CLI-Harness` | A registered `usage-client-identity-live` keeps the earlier expression; a bundle import skips it | Re-declare the pipeline, patch the column descriptions, backfill (§12) |
| Under `delta`, a repeated large request body that is not JSON is stored once | Later rows repeating it have an empty `request_body` | Read `request_body_full`; re-ingest whole UTC days on one release and configuration; roll back to `whole-with-path` on 0.24 (§13) |
| `advanced.input_token_budget` on `group` pipelines | `0` switches the runner's trimming off for that pipeline; refused on a `schedule` pipeline | Optional: declare it where the runner's trimming is unwanted; nothing is lost on a rollback but the setting (§14) |
| Catalog bundles are `format_version` `3` | A 0.23 ADAS refuses a 0.24 bundle with `422 catalog_bundle_version_unsupported` | Upgrade an import target before promoting to it from 0.24 (§15) |
| Spending-limit columns on `dial_usage_log` (ClickHouse `V18`, Postgres `V59`) | Five nullable `cost` columns hold DIAL Core's reported spending-limits check; earlier rows read `NULL`; `budget_policy` and `budget_assignment` need `TABLE_SENSITIVE` | With ClickHouse migrations disabled, apply `V18` before starting the new build; with Postgres migrations disabled, apply `V59`; copy the five entries into a custom mapping file (§16) |
