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
rewritten.

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
  by every authenticated caller, with its sensitive columns when the list is `sensitive`. Remove the entry
  (`PUT /v1/tables/{name}/access`) unless that is what you want. After the upgrade, the startup log lists every
  table that names `*` and where.
- A `*` stored in `write` or `modify` grants nothing, and a later edit of that list is refused while it keeps it.
- **Flight SQL admits every authenticated caller.** The connection gate used to turn away a caller holding no
  privilege and no provider role; such a caller can now be reached by a `*` grant. Each call is still decided per
  table.
- **Rollback.** A build older than 0.24 reads `*` as an ordinary role name again. Callers who read a table only
  through a `*` grant lose access to it, and a `*` row policy confines nobody, so a caller it confined who reads the
  table another way sees every row. Delete `*` policies, or the read paths they narrow, before rolling back if a
  rollback must not widen reads.

## 8. Row policies on the caller's own rows

A row policy may compare a `string` column with a value of the caller reading the table, `caller.user_hash` or
`caller.project`, and `PUT /v1/tables/{name}/row-policies/{role}` accepts the policy as a SQL condition in `where`
instead of `filter` (see [`row-level-security.md`](row-level-security.md#own-rows-comparing-a-column-with-the-caller)).
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

## Summary

| Change | Effect | Action |
|---|---|---|
| Replicated ClickHouse storage required | An `Atomic` database is refused at startup; plain tables in a `Replicated` database convert on the first start | Set up Keeper, server settings and writer grants (§1); change an `Atomic` engine by hand (§2); stop the previous version before deploying (§3) |
| `CLICKHOUSE_HOST` means the primary replica | Startup refuses a host that resolves to several addresses or that several replicas answer | Point it at an endpoint that reaches only the primary pod before a second replica joins (§5) |
| `*` means every authenticated caller in read-side grant lists and row policies; Flight SQL admits every authenticated caller | A `*` stored earlier as a role name opens that table to all callers | Remove unintended `*` entries before upgrading; check the startup log after; before a rollback, delete `*` policies that must keep confining (§7) |
| Chart `y_field` is an array (breaking response shape) | Saved-query and dashboard responses return `y_field` as an array; a string is still accepted on write | Move clients that read `y_field` to the array; before a rollback, rewrite stored arrays (§6) |
| Row policies may use `caller.user_hash` / `caller.project` | `caller.user_hash` matches no row until the `logging-*` settings are set | Set the `logging-*` settings to DIAL Core's values where own-rows policies are used; before a rollback, remove caller-value policies (§8) |
