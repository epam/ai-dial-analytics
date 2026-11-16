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
- **An enrichment's read access follows its source table**: its own read-side grants and row policies are
  removed by the migration, so check who gains and loses access before upgrading (§10).
- **Table access is managed one role at a time** at `/v1/tables/{name}/access?role=`, with column policies;
  the old five-list access and row-policy endpoints are removed (§11).
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

An enrichment is never queried on its own: every read of its rows has its source as `FROM`. Its `get`, `read` and
`sensitive` access is now its `source_table`'s, and it stores no row policy (see
[`table-access-control`](../openspec/specs/table-access-control/spec.md)). `write` and `modify` stay the
enrichment's own, and its own `modify` still lets a role read its definition. The Postgres migration `V56` removes
every enrichment's `get`, `read` and `sensitive` lists and its row policies, the seeded `dial_usage_log_payload`
included.

- **API.** A `PUT /v1/tables/{enrichment}/access?role={role}` whose body carries `get`, `read` or `sensitive`, or a `rows`
  or `columns` that is not `null`, is refused `409 enrichment_follows_source`, in `none` mode too; the message names
  the source to grant instead. An entry carrying only `write` and `modify` is accepted. The `enrichment_read_inert`
  reason is gone from `ineffective_grants`, and a source `sensitive` grant to a role holding `modify` on one of its
  enrichments is no longer reported `sensitive_without_verb`. On every table, an access `PUT` resolves the table
  first, so one whose `rows` carries both `filter` and `where` for a table that does not exist answers `404`, not
  `422`.
- **Who gains access.** A per-table reader of a source reads its enrichments' non-sensitive columns, and a holder
  of the source's clearance their sensitive columns, whatever the enrichment's own lists said: the payload
  enrichment and `usage_client_identity` now follow `dial_usage_log` (their body and personal-data columns are
  `sensitive`). A caller that may get a source, through its `get`, `read` or `modify` list, gets every enrichment
  of it: the listing, `GET /v1/tables/{enrichment}`, its revision snapshots, its inclusion in an export, and its
  non-sensitive columns in schema discovery and translation. A holder of an enrichment's `modify` and the source's
  clearance gains schema authority over the enrichment's sensitive columns. A column not meant for every reader
  of the source is hidden by a column policy on the source that does not name it (see
  [Role-keyed table access](#11-role-keyed-table-access)), or by marking it `sensitive`.
- **Who loses access.** A role granted `get`, `read` or `sensitive` on an enrichment but not on its source loses
  the enrichment's listing and definition, its columns, or its sensitive columns: on query surfaces they become
  unknown fields (`400`), schema edits of them answer `422` unknown column, and pipeline authoring and runner scans
  that reference them answer `403 sensitive_column_not_entitled`. A runner cleared only on
  `dial_usage_log_payload` stops scanning until it is cleared on `dial_usage_log`.
- **Enrichment row policies have no equivalent.** A policy that blanked an enrichment's values for a role is
  removed, and those values become visible on the source rows that role reads. A source policy can name only the
  source's own columns, so a condition on an enrichment column is refused `422`, and it hides whole rows rather
  than the enrichment's values on them. For each policy the first query below finds, decide before upgrading:
  mark the enrichment's columns `sensitive`, which hides them on every row from callers without the source's
  clearance, or write a source policy on source columns, which hides those rows entirely. A caller confined only
  by a removed enrichment policy is no longer refused `403 row_policy_confined` by preview, arming or the pipeline
  data plane over that enrichment.
- **The sensitive-grant subset rule is removed**, for enrichment and aggregate targets alike. A derived table's
  sensitive columns are read by its own clearance holders (for an enrichment, its source's), whether or not they
  are cleared on the table it derives from. The access API and both schema endpoints no longer
  answer `422 sensitive_grant_widens`; pipeline registration, enable and aggregate refresh no longer answer
  `422 pipeline_validation_failed` for a widening role; and grant edits, schema edits that mark a column
  sensitive, and `enrich` registration, enable and armed patch no longer answer
  `409 pipeline_declaration_unresolvable`. A pipeline's author must still be entitled to every sensitive column it
  reads, and a rollup column derived from a `sensitive` input column must still be declared `sensitive`.
- **`supports_final` changes** for a per-table reader of an `append` source that could not read its enrichment:
  `GET /v1/queries/entities` now reports `supports_final: true`, an absent `final` reads final, and `"final": true`
  or SQL `FINAL`, which answered `400 illegal_final`, now succeed.
- **Exports of everything** now include the enrichments of every source the caller may get, and are refused
  `403 sensitive_column_not_entitled` when one carries a sensitive column the caller lacks the source's clearance
  for, where the enrichment used to be left out. An imported enrichment of a source this environment already
  grants is readable at once by that source's readers.
- **Revision snapshots lag the migration.** `V56` writes no audit revision, so an enrichment's latest snapshot keeps
  showing the removed lists and policies until its next change.

**Before upgrading**, run both queries against the catalog database.

Who gains access: per enrichment, the source's `read` and `sensitive` grantees it does not name, and every policy
it stores.

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

Who loses access, per key; expected empty. Move any role it finds to the same key on the source. A role that
`roles-mapping` maps to the privilege globally keeps it whatever the lists say, and can be ignored.

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

- **During the rollout**, make no access or pipeline edits. Until every ADAS pod runs the new build, old
  pods judge enrichments by their own, now empty, lists and still apply the subset rule: they may refuse
  enrichment columns to per-table readers of the source and to runner scans, and fail refreshes of rollups that
  read enrichment columns. These failures are expected, need no action, and stop when the rollout finishes.
- **Rollback.** `V56` has no undo, and an older build reads the cleared lists as the enrichments' own empty grants:
  per-table readers of a source and runner scans lose the enrichment columns, rollups that read them fail every
  refresh, and arming or patching the `enrich` pipeline filling an enrichment is refused. Before upgrading, save
  every enrichment's read-side lists and row policies (`GET /v1/tables/{enrichment}/access` and
  `.../row-policies`, or `SELECT name, access, row_policies FROM table_definition WHERE type = 'ENRICHMENT'`).
  After redeploying the previous release, re-apply them through its `PUT /v1/tables/{enrichment}/access`, sending
  only `get`, `read` and `sensitive`, and `PUT /v1/tables/{enrichment}/row-policies/{role}` with each saved filter.
  If a restore is refused `422 sensitive_grant_widens`, or a rollup still fails its refresh, a table was cleared
  after the upgrade for a role not cleared on a table it reads: narrow that table's `read` or `sensitive` list, or
  grant the role on the table it reads. Grant enrichments created after the upgrade the roles of their source's
  readers that should read them. Upgrading again does not re-run `V56`: then, for each role you restored on an
  enrichment, `PUT /v1/tables/{enrichment}/access?role={role}` with only its `write` and `modify`, or
  `DELETE /v1/tables/{enrichment}/access?role={role}` when it holds neither, since the restored lists confer nothing and
  the restored policies keep narrowing.

## 11. Role-keyed table access

A table's access is now one **entry** per raw provider role, `{"privileges": [...], "rows": ..., "columns": ...}`,
read and replaced at `/v1/tables/{name}/access?role={role}` (see
[`table-access-control`](../openspec/specs/table-access-control/spec.md) and
[`row-level-security.md`](row-level-security.md)). It replaces both the five-list grant API and the row-policy API,
which are removed with no alias. Stored grants and row policies need no migration: the Postgres migration `V57` adds
`column_policies`, `access_generation` and `access_role_generations` (the generation at which each role's entry last
changed) to `table_definition` (and `column_policies` to its audit mirror), and every existing grant and policy reads
as an entry, at `generation` 0.

| 0.23 call | 0.24 call |
|---|---|
| `GET /v1/tables/{name}/access` (five lists `get`, `read`, `sensitive`, `write`, `modify`) | `GET /v1/tables/{name}/access`, returning `{"generation", "roles": {"<role>": {"privileges", "rows", "columns"}}, "ineffective_grants"}`; a role's `privileges` are the keys whose lists named it |
| `PUT /v1/tables/{name}/access` with lists to merge | For each role whose privileges change, `PUT /v1/tables/{name}/access?role={role}` with `generation`, the role's full `privileges`, and its `rows` and `columns` as read; a role left with nothing, `DELETE /v1/tables/{name}/access?role={role}` |
| `GET /v1/tables/{name}/row-policies` | `GET /v1/tables/{name}/access` (each entry's `rows`), or `GET /v1/tables/{name}/access?role={role}` |
| `PUT /v1/tables/{name}/row-policies/{role}` with `{"filter": ...}` or `{"where": ...}` | `PUT /v1/tables/{name}/access?role={role}` with `generation`, the role's `privileges` and `columns` as read, and `rows` set to the former body |
| `DELETE /v1/tables/{name}/row-policies/{role}` | `PUT /v1/tables/{name}/access?role={role}` with `"rows": null` and the rest as read, or `DELETE /v1/tables/{name}/access?role={role}` when the role holds nothing else |
| `permissions` in the access `PUT` response | `GET /v1/tables/{name}` |

- **A `PUT` replaces the role's whole entry.** An absent or `null` `privileges` means none, and an absent `rows` or
  `columns` means `null`, so a client that sends only what it changes clears the rest. An entry left empty is
  removed. `rows` is stored and returned as `{"filter": ...}`, as before.
- **The role is a query parameter.** `role` carries the raw provider role as an ordinary URL-encoded query value, so
  every role is addressable, whatever characters it holds: IdP group `/analytics/viewers` is
  `?role=%2Fanalytics%2Fviewers`. The decoded role is trimmed; a missing or blank `role` on `PUT` or `DELETE`, a blank
  `role` on `GET`, and a `role` given more than once on any method, is `400 bad_request`, and `GET /v1/tables/{name}/access` without `role`
  lists every entry.
- **The generation.** Each table carries one access `generation`, and the service records the generation at which
  each role's entry last changed, a removal included. A `PUT` carries the value it read and is refused
  `409 access_conflict`, naming no generation and changing nothing, when **its own role's** entry changed after that
  value or the value is above the table's; re-read the access and reapply the change. A change to another role since
  the read does not conflict, so two clients editing different roles from one read both succeed. A missing, `null` or
  non-integer `generation` (`"7"`, `7.5`, `7.0`), or one outside the signed 64-bit range, is `422 validation_error`.
- **A retried `PUT` is safe.** A `PUT` that would store exactly the role's current entry answers `200` with it and
  the current generation, whatever generation it carries, and changes nothing: the generation does not move and no
  revision is recorded. A `PUT` or `DELETE` that changes an entry moves the generation by one, and so does a schema
  change that rewrites a column policy; take the next value from the response.
- **`DELETE ...?role={role}` takes an optional `generation`.** Without it, it removes the entry whatever changed and
  never answers `409`: `204`, or `404 not_found` when the role has no entry, so of two concurrent deletes one gets
  `204` and the other `404`. With `&generation=<n>` it is conditional like a `PUT` (`409 access_conflict`, or `422`
  for a non-integer `n`); a role with no entry is still `404`.
- **Writes to one table wait a bounded time for each other.** An access `PUT` or `DELETE`, a description update, a
  schema definition or `PATCH` and a table delete take turns on the table's definition. One that waits longer than
  `TABLES_LOCK_TIMEOUT` (default `10s`, see [`configuration.md`](configuration.md#table-definition-writes)) for
  another change to the same table is refused `409 table_busy`, changes nothing, and can be retried.
- **The `permissions` member is gone** from the `PUT` response; read the caller's own permissions from
  `GET /v1/tables/{name}`. `ineffective_grants` keeps its item shape and its earlier reasons, and gains
  `lifts_column_policies` and `everyone_reads_all_rows` (additive). The latter is a standing warning: a `read` for `*`
  with no `rows` on a table that is not an enrichment, reported beside `lifts_row_policies` and
  `lifts_column_policies`, so a `PUT` of `read` for `*` without `rows` reports one more item than before.
- **Both access reads carry `ineffective_grants`** (additive): `GET .../access` reports every entry, grouped by role
  in the order of `roles`, and `GET .../access?role={role}` reports that entry, judged on the entries as stored at the
  read; `[]` when nothing is reported.
- **`list` is now `422`.** A `list` (or `read_sensitive`) privilege is refused `422 validation_error` like any unknown
  privilege, where the old grant API answered `400`.
- **A bundle-named role is `422` on `PUT`.** A role spelled like a bundle or privilege (`READ_ONLY_ANALYTIC`,
  `FULL_ADMIN`, ...), which the old grant API accepted, is refused `422 validation_error`. An existing entry for such
  a role keeps applying as stored, and can only be `DELETE`d.
- **Firewall refusals are an `ErrorView`.** A request the request firewall refuses (a path carrying `;`, `\`, `//`,
  an encoded `%` or `.`, a NUL, carriage return or line feed, U+2028 or U+2029, or a segment that is exactly `.` or
  `..`) now answers an `ErrorView` `400 bad_request` instead of a bare `400`. What Tomcat rejects before the service
  sees it (an encoded `/`, an encoded NUL) keeps its plain `400` page.
- **Revision snapshots carry `roles`** (the role-keyed entries) in place of `access` and `row_policies`, withheld under
  the same rule as before (`TABLE_GRANT`, or `none` mode). This **breaks a client that reads a snapshot's `access` or
  `row_policies`**. A revision written before `V57` reads every entry with `columns: null`. Snapshots never carry the
  generation. A snapshot hides a column when the revision's column policies **or the table's current ones** hide it
  from the caller, so a restriction added later also hides the column in older revisions, those written before `V57`
  included; a column is matched by the name it had in the revision. A deleted table's revisions are judged by the
  policies it last had, and a table created again under the same name does not judge an earlier one's; a deleted
  enrichment's are judged by its source's as they stood just before that deletion. A snapshot shows no column when no
  state of the table or its source is recorded.
- **`permissions.columns_restricted`** is added on `GET /v1/tables` and `GET /v1/tables/{name}` (additive).
- **`permissions.grant`** is added on every surface that carries `permissions`, revision snapshots included
  (additive): `true` exactly when the caller holds `TABLE_GRANT` and so may use the access API, the same on every
  table, and always `true` in `none` mode.
- **Column policies** are new: an entry's `columns` restricts the role to the listed columns, naming an enrichment's
  as `<enrichment>.<column>` on its source. A hidden column is absent from every read surface, and pipeline
  authoring, the runner data plane and export refuse it `403 column_not_entitled`, as does a schema `PATCH` carrying
  any `ttl` member while the stored TTL is on a policy-hidden column. A rename rewrites the lists, and a
  drop that would empty one is refused `409 column_in_column_policy`. A restricted runner's list must also name the
  columns the scan, group assembly and materialize read back, such as `_ingested_at` and `event_id` on
  `dial_usage_log`, and `request_time` for an aligned target; see
  [`row-level-security.md`](row-level-security.md#pipelines-and-export).
- **Gaining a column-restricted role can hide columns.** Rows and columns combine per entry and fail closed: a
  caller holding role `A` (`rows` limited to project `P1`, every column) that also gains role `B` (every row,
  `"columns": ["x"]`) sees only `x`, because showing `A`'s other columns would show them on rows only `B` admits. List
  on `B` the columns `A`'s readers need, or leave `B` unrestricted; giving `B` a row policy does not help (see
  [`row-level-security.md`](row-level-security.md#column-policies)).
- **The scan now judges an aligned target's source partition column for the clearance.** A runner without
  `TABLE_SENSITIVE` on a source whose `partition_by` column is `sensitive`, scanning for a target aligned with that
  source, is now refused `403 sensitive_column_not_entitled`, where 0.23 accepted the scan. Give the runner's role
  the source's clearance.
- **Materialize now judges the source's version and identity columns for the clearance**, as the scan already did,
  because its `nextCursor` carries their values. A runner without `TABLE_SENSITIVE` on a source whose
  `version_column` or `identity_column` is `sensitive` is now refused `403 sensitive_column_not_entitled`, where 0.23
  accepted the materialize. Give the runner's role the source's clearance.
- **`enrich` authoring re-attributes a malformed `filter`, `ready_when.signal` or `member_select.prefer_sql`**, as it
  already did an expression. On registration, enable, armed patch and preview, a fragment that fails to parse now
  answers `403 sensitive_column_not_entitled` to an author lacking the clearance on a source that has, or one of whose
  active enrichments has, a `sensitive` column, and `403 column_not_entitled` to a column-restricted author, where 0.23
  answered `422 pipeline_validation_failed` with the parser's diagnostic. This applies with no column policy in use
  (the payload enrichment of `dial_usage_log` carries `sensitive` columns) and to any malformed fragment, not only one
  naming a hidden column. Debug a malformed fragment as an author holding the clearance and no column restriction.
- **New codes:** `409 access_conflict`, `403 column_not_entitled`, `409 column_in_column_policy`, `409 table_busy`.

- **During the rollout**, make no access or table edits until every ADAS pod runs the new build. An old pod
  serves the removed endpoints and ignores column policies, does not move the generation, and records
  `column_policies` as `NULL` in any revision it writes, which that revision's snapshot reads as no policies.
- **Rollback.** An older build ignores `column_policies`, so **every column a column policy hides becomes visible**
  to its readers. Before a rollback that must not widen reads, delete the column policies (or the read paths they
  narrow). The added columns are otherwise harmless to an older build, and grants and row policies written through
  the new API are in the documents it reads.

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
| An enrichment's `get`, `read`, `sensitive` and rows follow its source; `V56` clears its read-side lists and row policies | New `409 enrichment_follows_source` on `PUT .../access?role={role}` for an enrichment when the entry carries `get`, `read`, `sensitive`, `rows` or `columns`; source readers and clearance holders gain its columns; enrichment-only grantees lose them; `supports_final` can turn true | Run the two queries above before upgrading; grant enrichment readers on the source; decide each enrichment policy's replacement; save the lists and policies for a rollback (§10) |
| `enrichment_read_inert` removed from `ineffective_grants` | A client matching on the code never sees it | Drop the code from clients (§10) |
| Access `PUT` resolves the table first | A `rows` with both `filter` and `where` for a missing table answers `404`, not `422` | None (§10) |
| Sensitive-grant subset rule removed | No more `422 sensitive_grant_widens` (access and schema endpoints), `422 pipeline_validation_failed` for a widening role (pipeline registration, enable, refresh), or `409 pipeline_declaration_unresolvable` (grant edits, schema edits marking a column sensitive, `enrich` registration and enable) | Review who holds each derived table's clearance: it no longer has to be cleared on what the table derives from (§10) |
| Role-keyed access API replaces the five-list `GET`/`PUT .../access` and `.../row-policies` (breaking) | The old calls are gone; `PUT .../access?role={role}` replaces a whole entry and needs the `generation` (`422` missing, `409 access_conflict` when that role changed since the read); an unchanged `PUT` answers `200` and writes nothing; no `permissions` in its response; `list` and bundle-named roles are `422`; a missing `role` is `400`; `DELETE` answers `409` only when sent a `generation` | Move clients using the mapping table above; URL-encode the role as a query value (§11) |
| Firewall refusals are `ErrorView` `400 bad_request` | A client matching the bare `400` body finds an `ErrorView` | Update clients that matched the bare `400` (§11) |
| Bounded wait on concurrent writes to one table (`TABLES_LOCK_TIMEOUT`, default `10s`) | A write that waits longer for another change to the same table is refused `409 table_busy` | Retry on `table_busy`; raise the timeout if long schema changes make it frequent (§11) |
| Revision snapshots carry `roles` instead of `access` and `row_policies` (breaking); they hide a column the revision's or the current column policies hide | Snapshot consumers reading `access` or `row_policies` find nothing; a column restricted today is hidden in older revisions too | Read `roles` (§11) |
| Column policies; `permissions.columns_restricted` (additive) | Hidden columns are absent from every read surface; new `403 column_not_entitled` on pipeline and export paths and on a schema `PATCH` touching a TTL stored on a policy-hidden column, and `409 column_in_column_policy` on an emptying drop or enrichment delete | Name the cursor columns (`event_id` and `_ingested_at` on `dial_usage_log`) and an aligned target's partition column in a restricted runner's list; make no access or table edits during the rollout; before a rollback, delete column policies that must keep hiding (§11) |
| The scan judges an aligned target's source partition column for the clearance | A runner without `TABLE_SENSITIVE` on a source with a `sensitive` `partition_by` column is refused `403 sensitive_column_not_entitled` scanning for a target aligned with it | Grant the runner's role the source's clearance (§11) |
| Materialize judges the source's version and identity columns for the clearance | A runner without `TABLE_SENSITIVE` on a source with a `sensitive` `version_column` or `identity_column` is refused `403 sensitive_column_not_entitled` on `materialize` | Grant the runner's role the source's clearance (§11) |
| `enrich` authoring re-attributes a `filter`, `ready_when.signal` or `member_select.prefer_sql` that fails to parse | An author without the source's clearance, where the source or an active enrichment has a `sensitive` column, gets `403 sensitive_column_not_entitled` (a column-restricted author `403 column_not_entitled`) for any malformed fragment, where 0.23 answered `422 pipeline_validation_failed`; no column policy is needed for this | Debug malformed fragments as a cleared author with no column restriction (§11) |
| `permissions.grant`; `ineffective_grants` on both access reads; reason `everyone_reads_all_rows` (additive) | Clients can tell before a request whether the caller may manage access, and see the access warnings after a reload; a `PUT` or read of `read` for `*` without `rows` on a non-enrichment table reports `everyone_reads_all_rows` | Hide access editing when `permissions.grant` is `false`; show or let operators dismiss the new warning (§11) |
| New error codes | `409 access_conflict`, `403 column_not_entitled`, `409 column_in_column_policy`, `409 table_busy` | Handle them in clients (§11) |
| Recommended catalog reads the DIAL CLI harness from `X-DIAL-CLI-Harness` | A registered `usage-client-identity-live` keeps the earlier expression; a bundle import skips it | Re-declare the pipeline, patch the column descriptions, backfill (§12) |
