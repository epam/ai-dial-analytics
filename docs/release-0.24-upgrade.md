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
- **A coordinated deploy with the Enrichment Runner**: the contract version rises from `4` to `5`, and a runner on
  the other version is refused, so enrichment pauses until both sides match (§9).
- **An enrichment's read access follows its source table.** A migration removes its own read-side grants and row
  policies, so check who gains and loses access before upgrading (§10).
- **Table access is managed one role at a time** at `/v1/tables/{name}/access?role=`, with new column policies. The
  old access and row-policy endpoints are removed (§11).
- **The recommended catalog reads the DIAL CLI harness from `X-DIAL-CLI-Harness`**: a deployment that already
  registered `usage-client-identity-live` applies it by hand (§12).

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
2. **Run the two enrichment queries** in §10. Grant each role that would lose access on the source, and decide how
   to replace each enrichment row policy.
3. **Save every enrichment's access lists and row policies** with the query in §10, for a rollback.
4. **Give runner roles the source's clearance** where the source's partition, version or identity column is
   `sensitive` (§11).
5. **Update access-API clients**: move from the old `/access` and `/row-policies` calls to
   `/v1/tables/{name}/access?role=`, and read `roles` instead of `access` and `row_policies` in revision snapshots
   (§11).
6. **Plan a freeze**: no access, table or pipeline edits until every ADAS pod runs 0.24 (§10, §11).

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

- **Deploy ADAS and the Enrichment Runner together.** The runner contract version moves from `4` to `5`, and a
  runner on the other version is refused until both sides match, so enrichment pauses during the skew.
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

An enrichment is always read through its source, so its read access now comes from the source (see
[`table-access-control`](../openspec/specs/table-access-control/spec.md)).

**What changed**

- An enrichment's `get`, `read` and `sensitive` access, and its rows, are its `source_table`'s. `write` and `modify`
  stay the enrichment's own.
- The Postgres migration `V56` removes every enrichment's `get`, `read` and `sensitive` lists and its row policies,
  `dial_usage_log_payload` included.
- `PUT /v1/tables/{enrichment}/access?role={role}` accepts only `write` and `modify`. An entry with `get`, `read`,
  `sensitive`, `rows` or `columns` is refused `409 enrichment_follows_source`, and the message names the source.
- The sensitive-grant subset rule is gone: a derived table's clearance holders no longer need clearance on the table
  it derives from. `422 sensitive_grant_widens`, and the pipeline refusals it caused, no longer occur. A pipeline's
  author must still be entitled to every sensitive column the pipeline reads.

**What clients notice**

- **Source readers gain access.** A reader of the source reads its enrichments' columns; a holder of the source's
  clearance also reads their sensitive ones. Anyone who may get the source gets its enrichments. For example, the
  payload enrichment and `usage_client_identity` now follow `dial_usage_log`. To keep an enrichment column from some
  source readers, mark it `sensitive` or leave it out of a column policy on the source (§11).
- **Enrichment-only grantees lose access.** A role granted on an enrichment but not on its source loses it: its
  columns become unknown fields (`400`), and pipelines or runner scans that read them get
  `403 sensitive_column_not_entitled`.
- **Enrichment row policies have no equivalent.** The values they hid become visible on the source rows the role
  reads. Replace each one either by marking the enrichment's columns `sensitive` (hidden on every row from callers
  without the source's clearance) or by a row policy on the source (hides whole rows). A source policy cannot use an
  enrichment column.
- **Smaller effects.** `supports_final` can turn `true` for a reader of an `append` source who could not read its
  enrichment, so `FINAL`, which answered `400 illegal_final`, now works. An export of everything includes the
  enrichments of the sources the caller may get, and is refused `403 sensitive_column_not_entitled` when the caller
  lacks the clearance for one. `ineffective_grants` no longer reports `enrichment_read_inert`. An enrichment's latest
  revision snapshot shows the removed lists until its next change.

**What to do**

Before upgrading, run both queries against the catalog database.

Who gains access: per enrichment, the source's `read` and `sensitive` grantees it does not name, and the policies
`V56` removes.

```sql
SELECT e.name AS enrichment, e.source_table,
       ARRAY(SELECT jsonb_array_elements_text(s.access -> 'read')
             EXCEPT SELECT jsonb_array_elements_text(e.access -> 'read')) AS gains_read,
       ARRAY(SELECT jsonb_array_elements_text(s.access -> 'sensitive')
             EXCEPT SELECT jsonb_array_elements_text(e.access -> 'sensitive')) AS gains_sensitive,
       e.row_policies AS policies_removed
  FROM table_definition e
  JOIN table_definition s ON s.name = e.source_table
 WHERE e.type = 'ENRICHMENT';
```

Who loses access, per key; expected empty. Grant each role it finds the same key on the source. A role that
`roles-mapping` maps to the privilege globally keeps it and can be ignored.

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

Save every enrichment's lists and policies for a rollback:

```sql
SELECT name, access, row_policies FROM table_definition WHERE type = 'ENRICHMENT';
```

**During the rollout**, make no access or pipeline edits. Old pods judge enrichments by their now empty lists, so
they may refuse enrichment columns to source readers and runners, and fail refreshes of rollups that read them. This
stops when every pod runs the new build.

**Rollback.** `V56` cannot be undone. An older build reads the cleared lists as empty grants: source readers and
runners lose the enrichment columns, rollups that read them fail, and the `enrich` pipeline cannot be armed or
patched. After redeploying the previous release:

1. Restore the saved lists with its `PUT /v1/tables/{enrichment}/access`, sending only `get`, `read` and
   `sensitive`, and each saved policy with `PUT /v1/tables/{enrichment}/row-policies/{role}`.
2. If a restore is refused `422 sensitive_grant_widens`, or a rollup still fails, narrow that table's `read` or
   `sensitive` list, or grant the role on the table it reads.
3. Grant enrichments created after the upgrade to the source readers who should read them.

Upgrading again does not re-run `V56`. Then reduce each restored role to its `write` and `modify` with
`PUT /v1/tables/{enrichment}/access?role={role}`, or `DELETE` its entry when it holds neither.

## 11. Role-keyed table access

A table's access is now one **entry** per raw provider role, `{"privileges": [...], "rows": ..., "columns": ...}`,
managed at `/v1/tables/{name}/access?role={role}` (see
[`table-access-control`](../openspec/specs/table-access-control/spec.md) and
[`row-level-security.md`](row-level-security.md)).

**What changed**

- The five-list access API and the row-policy API are removed, with no alias.
- Column policies are new: an entry's `columns` limits the role to the listed columns.
- Revision snapshots carry `roles` (the entries) instead of `access` and `row_policies`. **This breaks a client that
  reads a snapshot's `access` or `row_policies`.**
- Stored grants and row policies need no data migration. The Postgres migration `V57` adds the new columns, and
  existing grants and policies read as entries at `generation` 0.

**Moving clients.** Below, `...` stands for `/v1/tables/{name}`.

| 0.23 call | 0.24 call |
|---|---|
| `GET .../access` (five lists) | `GET .../access`, returning `{"generation", "roles": {"<role>": <entry>}, "ineffective_grants"}` |
| `PUT .../access` with lists to merge | `PUT .../access?role={role}` with the whole entry, for each role that changes; `DELETE` a role left with nothing |
| `GET .../row-policies` | `GET .../access` (each entry's `rows`) |
| `PUT .../row-policies/{role}` | `PUT .../access?role={role}` with `rows` set to the old body and the other parts as read |
| `DELETE .../row-policies/{role}` | `PUT .../access?role={role}` with `"rows": null`, or `DELETE` when nothing else is left |
| `permissions` in the `PUT` response | `GET /v1/tables/{name}` |

- **URL-encode the role** as a query value: group `/analytics/viewers` is `?role=%2Fanalytics%2Fviewers`.
- **A `PUT` replaces the whole entry.** A part you leave out is cleared, so send the parts you do not change as read.
- **Send the `generation` you read.** A `PUT` is refused `409 access_conflict` only when that same role changed since
  the read; re-read and reapply. A `PUT` that changes nothing answers `200`, so a retry is safe. A missing or
  non-integer `generation` is `422`. `DELETE` takes an optional `&generation=`.
- **Concurrent writes to one table wait** up to `TABLES_LOCK_TIMEOUT` (default `10s`, see
  [`configuration.md`](configuration.md#table-definition-writes)), then answer `409 table_busy`; retry.
- **`422` for a `list` privilege or a bundle-named role** (`FULL_ADMIN`, ...), which the old API accepted. An
  existing entry for such a role still applies and can only be deleted.
- **Additive:** `permissions.grant` (the caller may manage access) and `permissions.columns_restricted`;
  `ineffective_grants` on both access reads, with new reasons `lifts_column_policies` and `everyone_reads_all_rows`.
- **Request-firewall refusals** are an `ErrorView` `400 bad_request` instead of a bare `400`.

**Column policies: what to watch**

- A hidden column is absent from every read surface. Pipeline authoring, the runner and export refuse it
  `403 column_not_entitled`.
- A restricted runner's list must also name the columns the runner reads back, such as `event_id` and `_ingested_at`
  on `dial_usage_log`, and `request_time` for an aligned target (see
  [`row-level-security.md`](row-level-security.md#pipelines-and-export)).
- Gaining a column-restricted role can hide columns. A caller with role `A` (rows of project `P1`, all columns) who
  gains role `B` (all rows, `"columns": ["x"]`) sees only `x`. List on `B` the columns `A`'s readers need, or leave
  `B` unrestricted; a row policy on `B` does not help (see
  [`row-level-security.md`](row-level-security.md#column-policies)).
- A revision snapshot hides a column that the revision's or the table's current policies hide, so a restriction
  added later applies to older revisions too. A deleted table's revisions are judged by the policies it had when it
  was deleted.

**Runner and pipeline authors**

- The runner needs the source's clearance (`TABLE_SENSITIVE`) in two more cases: scanning for an aligned target
  when the source's `partition_by` column is `sensitive`, and materializing when the source's `version_column` or
  `identity_column` is `sensitive`. Otherwise it gets `403 sensitive_column_not_entitled`, where 0.23 accepted the
  call. Give the runner's role the clearance.
- An `enrich` `filter`, `ready_when.signal` or `member_select.prefer_sql` that fails to parse now answers `403`
  instead of `422` with the parser's message, to an author without the clearance on a source with `sensitive`
  columns (such as `dial_usage_log`) or a column-restricted author. Debug it as an author with the clearance and no
  column restriction.

**During the rollout**, make no access or table edits. Old pods serve the removed endpoints, ignore column policies
and do not move the generation.

**Rollback.** An older build ignores column policies, so **every column they hide becomes visible**. Delete them (or
the read paths they narrow) before a rollback that must not widen reads. Grants and row policies written through the
new API stay readable by the older build.

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

## Summary

| Change | Effect | Action |
|---|---|---|
| Replicated ClickHouse storage required | An `Atomic` database is refused at startup; plain tables in a `Replicated` database convert on the first start | Set up Keeper, server settings and writer grants (§1); change an `Atomic` engine by hand (§2); stop the previous version before deploying (§3) |
| `CLICKHOUSE_HOST` means the primary replica | Startup refuses a host that resolves to several addresses or that several replicas answer | Point it at an endpoint that reaches only the primary pod before a second replica joins (§5) |
| `*` means every authenticated caller in read-side grant lists and row policies; Flight SQL admits every authenticated caller | A `*` stored earlier as a role name opens that table to all callers | Remove unintended `*` entries before upgrading; check the startup log after; before a rollback, delete `*` policies that must keep confining (§7) |
| Chart `y_field` is an array (breaking response shape) | Saved-query and dashboard responses return `y_field` as an array; a string is still accepted on write | Move clients that read `y_field` to the array; before a rollback, rewrite stored arrays (§6) |
| Transform preset `dial_app_route` and the `app-routes` setting; runner contract `5` | A runner on contract `4` is refused | Deploy the matching Enrichment Runner in the same window; allow each route before enabling its pipeline; before a rollback, re-declare or delete route pipelines (§9) |
| Row policies may use `caller.user_hash` / `caller.project` | `caller.user_hash` matches no row until the `logging-*` settings are set | Set the `logging-*` settings to DIAL Core's values where own-rows policies are used; before a rollback, remove caller-value policies (§8) |
| An enrichment's read access and rows follow its source; `V56` clears its own | Source readers gain its columns, enrichment-only grantees lose them, its row policies are gone; a read-side `PUT` on it is `409 enrichment_follows_source` | Run the §10 queries; grant on the source; replace enrichment policies; save the lists for a rollback (§10) |
| Sensitive-grant subset rule removed | `422 sensitive_grant_widens` and the pipeline refusals it caused no longer occur | Review who holds each derived table's clearance (§10) |
| Role-keyed access API replaces the old access and row-policy endpoints (breaking) | The old calls are gone; a `PUT` replaces a whole entry and needs the `generation` | Move clients using the table in §11 |
| Revision snapshots carry `roles` (breaking) | `access` and `row_policies` are no longer in snapshots | Read `roles` (§11) |
| Column policies | Hidden columns are absent everywhere; pipeline and export paths refuse them `403 column_not_entitled` | List the cursor columns in a restricted runner's list; delete column policies before a rollback (§11) |
| Runner clearance on partition, version and identity columns | A runner without the source's clearance gets `403 sensitive_column_not_entitled` | Give the runner's role the clearance (§11) |
| `enrich` fragments that fail to parse | An uncleared or column-restricted author gets `403` instead of `422` | Debug as a cleared author with no column restriction (§11) |
| Smaller API changes | New `permissions.grant`, `permissions.columns_restricted`, `ineffective_grants` reasons and error codes (`409 access_conflict`, `409 table_busy`, `409 column_in_column_policy`); `enrichment_read_inert` removed; firewall refusals are an `ErrorView` | Handle the new codes; retry `table_busy` (§10, §11) |
| Recommended catalog reads the DIAL CLI harness from `X-DIAL-CLI-Harness` | A registered `usage-client-identity-live` keeps the earlier expression; a bundle import skips it | Re-declare the pipeline, patch the column descriptions, backfill (§12) |
