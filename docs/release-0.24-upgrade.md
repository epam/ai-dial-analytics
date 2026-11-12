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
- **Three new writer grants** (§1).
- **Request bodies stored in delta form** (opt-in) need pipelines re-pointed before ingest switches (§6).
- **Chart `y_field` is an array** in saved-query and dashboard responses (§7).
- **`*` in a read-side grant list means every authenticated caller**: check for a stored `*` before upgrading (§8).
- **New privileges `SETTINGS_READ` and `SETTINGS_WRITE`** for the sessions-source setting (§10).

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
  ```

  Startup fails naming a missing `SYSTEM SYNC` grant: without them every pipeline run and every ingest cycle
  would be refused with `503`.
  Without `SELECT ON system.clusters` ADAS starts, logs a WARN and routes everything to the primary.
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

## 6. Request bodies rebuilt on read

New computed fields `dial_usage_log_payload.request_body_full` and `request_body_rebuild` return a chat turn's whole
request when it is stored in delta form (see [`heavy-columns.md`](heavy-columns.md#request-bodies-in-delta-form)).
Nothing changes under `SYNC_REQUEST_BODY_MODE=whole`, the default.

- **Before setting `SYNC_REQUEST_BODY_MODE=delta`, re-point every pipeline whose `transform.inputs` binds
  `dial_usage_log_payload.request_body` to `dial_usage_log_payload.request_body_full`** (and bind
  `request_body_rebuild` too if the pipeline should tell a `partial` rebuild apart), and move saved queries and other
  readers that need a turn's whole request the same way. `request_body` keeps returning what is stored;
  `request_body_full` returns the rebuilt request, and under `whole` and `whole-with-path` it returns the stored body
  marked `stored`, so re-pointing first is safe. ADAS does not rewrite pipelines.
- **Rollback.** Re-point such pipelines back to `request_body` and switch ingest to `whole-with-path` first: a build
  older than 0.24 refuses a pipeline bound to `request_body_full`.
- **An environment already on `delta` or `whole-with-path`:** a message sent as one text block now hashes like the
  same message sent as a plain string, so a request whose history holds such a block gets a different path than under 0.23. A conversation in progress
  at the upgrade whose history holds one links its next turn to an earlier turn or starts a new chain, once. Re-ingest across the upgrade, or after a rollback, only by whole
  UTC days: such an event re-ingested alone takes the new path, and its children stored before can no longer be rebuilt.
  Instances on different builds during a rolling upgrade may miss each other's rows as parents for such requests,
  which costs storage only. A later turn's `request_cache_control` is no longer `NULL` when the turn carried no marker:
  it holds a `{"text_blocks":[…]}` entry, usually empty, naming the earlier messages the turn sent as one text block.
  A reader that parses the column skips that entry. A later turn stored before the upgrade has no entry and rebuilds
  its earlier messages as they are stored; Postgres `V54` updates the column's seeded description.
- **New optional keys.** `QUERY_REQUEST_BODY_REBUILD_MAX_ROUNDS` and `QUERY_REQUEST_BODY_REBUILD_MAX_ANCESTORS`
  ([`configuration.md`](configuration.md#request-body-rebuild)).

## 7. Multi-series charts

A chart's `y_field` — on a saved query and on a dashboard widget's `inline_query` — is now an array of result
columns, one per series (see [`saved-queries`](../openspec/specs/saved-queries/spec.md)). Responses always carry the
array, so a client that reads `y_field` as a string must move to the array; a one-element array is the old
single-series chart. Requests may still send a string, and charts stored before the upgrade are returned as a
one-element array without a migration.

- **Rollback.** A build older than 0.24 cannot read a chart stored as an array: reading that saved query, the
  scope list it appears in, its revision snapshot, or a dashboard holding such a widget fails. Before rolling back,
  rewrite each stored `y_field` array to its first element in `saved_query.chart`, `saved_query_aud.chart` and
  `dashboard.widgets` (the nested `inline_query.chart`).

## 8. The `*` grantee

`*` in a table's `get`, `read` or `sensitive` grant list, or as the role of a row policy, now stands for every
authenticated caller (see [`row-level-security.md`](row-level-security.md)). Earlier builds stored `*` as an
ordinary role name that matched only a caller carrying a provider role spelled `*`.

- **Before upgrading, look for a stored `*`.** A table that already names `*` in a read-side list becomes readable
  by every authenticated caller, with its sensitive columns when the list is `sensitive`. Remove the entry
  (`PUT /v1/tables/{name}/access`) unless that is what you want. After the upgrade, the startup log lists every
  table that names `*` and where.
- A `*` stored in `write` or `modify` grants nothing, and a later edit of that list is refused while it keeps it.

## 9. Sensitive columns kept out of ingest, opt-in

New `SYNC_SENSITIVE_COLUMNS_ENABLED` (see [`configuration.md`](configuration.md#data-sync-schedule)). Under `false`,
ingest stops writing the columns the catalog flags `sensitive` on each target table, and new rows hold their
ClickHouse defaults there (see [`data-sync`](../openspec/specs/data-sync/spec.md)). Nothing changes under `true`, the
default.

- **Under `false`, startup fails** when a target table has no catalog definition, or when a column it would leave
  out is a deduplication-key, grain, identity, ordering-key, partition, version or TTL column.
- Rows already stored keep their values, and switching back to `true` restores the columns on new rows only. Under
  `SYNC_REQUEST_BODY_MODE=delta`, a parent re-ingested under `false` loses its body, so its delta children can no
  longer be rebuilt.
- **Rollback.** A build older than 0.24 ignores the variable and writes sensitive columns again.

## 10. Sessions-source setting

New endpoints `GET` and `PUT /v1/settings/sessions-source` store which source table holds session rows and which of
its fields play the session roles (see [`instance-settings`](../openspec/specs/instance-settings/spec.md)). Reading
needs the new privilege `SETTINGS_READ`, which every read-only bundle now carries; writing needs `SETTINGS_WRITE`,
which only `FULL_ADMIN` carries. Flyway migration `V53` creates the backing table at startup.

- **A `roles-mapping` that names raw privileges** rather than a bundle gains neither privilege. Add `SETTINGS_READ`
  to the roles whose holders use the sessions view, or they get `403` on the read.
- **Rollback.** A build older than 0.24 does not know `SETTINGS_READ` or `SETTINGS_WRITE` and fails startup on a
  `roles-mapping` that names either. Remove them before rolling back. The new table is ignored and may be left in place.

## Summary

| Change | Effect | Action |
|---|---|---|
| Replicated ClickHouse storage required | An `Atomic` database is refused at startup; plain tables in a `Replicated` database convert on the first start | Set up Keeper, server settings and writer grants (§1); change an `Atomic` engine by hand (§2); stop the previous version before deploying (§3) |
| `CLICKHOUSE_HOST` means the primary replica | Startup refuses a host that resolves to several addresses or that several replicas answer | Point it at an endpoint that reaches only the primary pod before a second replica joins (§5) |
| `*` means every authenticated caller in read-side grant lists and row policies | A `*` stored earlier as a role name opens that table to all callers | Remove unintended `*` entries before upgrading; check the startup log after (§8) |
| Request-delta storage, opt-in (`SYNC_REQUEST_BODY_MODE=delta`) | Readers of `request_body` see only a later turn's new messages | Only when you set `delta`: re-point pipelines and readers to `request_body_full` (§6) |
| Sensitive-column ingest switch, opt-in (`SYNC_SENSITIVE_COLUMNS_ENABLED=false`) | New rows hold defaults in the columns the catalog flags `sensitive` | None by default; under `false`, startup fails on an undefined target table or a sensitive key column (§9) |
| Sessions-source setting, new privileges `SETTINGS_READ` / `SETTINGS_WRITE` | Bundles gain them; raw-privilege mappings do not | Add `SETTINGS_READ` to raw-privilege mappings that need the setting; remove both names before a rollback (§10) |
| Chart `y_field` is an array (breaking response shape) | Saved-query and dashboard responses return `y_field` as an array; a string is still accepted on write | Move clients that read `y_field` to the array; before a rollback, rewrite stored arrays (§7) |
