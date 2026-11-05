## Why

ADAS supports exactly one ClickHouse node. Run ClickHouse as two pods behind one Service and each pod becomes an independent server with its own data: writes land wherever the connection goes, reads return different or partial answers, and an incremental pipeline whose probe and `INSERT … SELECT` reach different servers skips rows forever. A multi-replica prototype that probed on one replica and wrote on another skipped a sixth of its rows with no error. The single node is also the resource ceiling: ingest, pipeline `INSERT … SELECT`s, runner body scans, merges and user queries share one node's CPU and memory, and pipeline scans, writes and merges are where it runs out first. The goal is the same ADAS build running on a single-shard cluster of 1..N replicas, with pipeline work and user queries spread across replicas and no silent data divergence.

## What Changes

- **BREAKING — Replicated storage everywhere.** ADAS requires its database to use the `Replicated` engine and every ADAS table to use a `Replicated*MergeTree` engine, on every deployment, single-node included, so going from 1 to N replicas never changes a table. ClickHouse Keeper (embedded on a single node) becomes a dependency of every deployment. Startup refuses a non-`Replicated` database before issuing any DDL, leaving nothing changed and naming the manual procedure. Changing a database's engine stays an operator step.
- **Insert deduplication off on every table** (`replicated_deduplication_window = 0`, `replicated_deduplication_window_for_async_inserts = 0`). This is the only spelling that works across 25.3–26.8. Without it, 26.x silently drops a write that repeats an earlier block (an upsert `open → closed → open` ends `closed`), and 25.3 drops a repeated identical `INSERT … SELECT`.
- **Catalog DDL renders the replicated variants.** `TableDdlRenderer` renders `ReplicatedMergeTree` / `ReplicatedReplacingMergeTree(_updated_at)` with no Keeper arguments, plus the deduplication settings. This covers `/v1/tables`, catalog import, OTel sinks and relayout copies.
- **Fresh installs provision replicated tables without touching applied migrations.** Before Flyway runs a pending `CREATE TABLE IF NOT EXISTS` migration, the migration runner pre-creates that table with the replicated engine. It also pre-creates the Flyway history table. Checksums are unchanged. New migrations declare replicated engines directly.
- **Plain tables in a `Replicated` database are converted automatically at startup.**
  - **Which tables.** ADAS finds every non-replicated `MergeTree`-family table in its session database through `system.tables`, with no table list kept in code.
  - **How.** For each table it creates a replicated twin, `ATTACH PARTITION ALL FROM` the original (hardlinks), compares counts, `EXCHANGE TABLES`, then drops the old copy. Every step is interruptible and resumable. Replicated tables still deduplicating get `MODIFY SETTING`.
  - **When it refuses.** ADAS refuses to convert when another replica is registered (active or not) and may hold rows in a plain table, because such copies have already diverged.
  - **Startup ordering.** The conversion and the migrations run under one advisory lock, only when migrations are enabled. They complete before the instance's scheduled jobs or endpoints can write.
- **An `Atomic` database is the operator's to change.** The manual procedure is documented: create a `Replicated` database, attach the data, swap the names, redeploy. ADAS never changes a database engine.
- **Topology is discovered, not configured.** ADAS reads the replica set from the `Replicated` database's own rows in `system.clusters` (rows carrying a database replica name), through the endpoint it already has. It ignores an operator-defined `remote_servers` cluster of the same name. No replica URLs appear in configuration. `CLICKHOUSE_HOST` must reach exactly one server, the primary. Startup fails, while the database has several replicas, in either case:
  - it resolves to several addresses, as a headless Service does;
  - fresh connections to it are answered by different replicas, as through a ClusterIP Service over every pod.
- **Routing by workload, with one writing replica per table:**
  - Ingest, the ingest and failure ledgers, `POST …/rows`, OTel, DDL, migrations and relayout run on the **primary**, the existing `CLICKHOUSE_HOST`.
  - Each pipeline runs on a **home replica**, chosen by a stable hash of its name over the active non-primary replicas. Every statement of a run executes there: Class-1 probe and `INSERT … SELECT`, aggregate probe and refresh, runner `/scan`, `/group-rows` and previews.
    - Each run starts with `SYSTEM SYNC DATABASE REPLICA`, so the home has applied every DDL. Then it runs `SYSTEM SYNC REPLICA … LIGHTWEIGHT` on the run's sources, then on its enrichments.
    - A sync that cannot complete refuses the run (`503 clickhouse_unavailable`, cursor unmoved) instead of reading a partial replica. The sync sits outside the statement budget, so its timeout is never reported as a budget refusal.
  - User queries (DSL, SQL, CSV export and Flight execution) go to an active replica that ClickHouse's `/replicas_status` reports healthy. They stay off the primary when there are two or more healthy non-primary replicas, and include it when there is only one. They re-run once on the primary when the replica is unreachable or has not yet applied a recent DDL.
  - Any statement no route names runs on the primary.
  - A single-node deployment resolves every route to its one server.
- **DDL tolerates a down replica.** Every DDL, startup migrations included, runs with `distributed_ddl_output_mode = null_status_on_timeout_only_active` and a bounded timeout. A DDL the initiator committed is a success, and a down replica applies it on recovery. A timeout after commit no longer triggers a compensating `DROP`. The relayout's TTL mutation waits for every active replica before it resets the setting the mutation reads.
- **Keeper and replication failures are transient.** `TABLE_IS_READ_ONLY` (242), `NO_ZOOKEEPER` (225), `KEEPER_EXCEPTION` (999) and `UNKNOWN_STATUS_OF_INSERT` (319) map to `503 clickhouse_unavailable`.
- **New config keys:**
  - `clickhouse.routing.queries` / `clickhouse.routing.pipelines` (`replicas` | `primary`) — escape hatches;
  - `clickhouse.replicas.discovery-interval`;
  - `clickhouse.replicas.sync-timeout`;
  - `clickhouse.ddl.task-timeout`.
- **New writer grants:** `SYSTEM SYNC REPLICA ON <db>.*`, `SYSTEM SYNC DATABASE REPLICA ON <db>.*` and `SELECT ON system.clusters`. The read credential gains nothing.
- **ClickHouse-side requirements documented:**
  - Keeper with 1 or 3 members;
  - identical `config.d` and users on every replica;
  - resolvable replica host names serving HTTP on the configured port;
  - `execute_merges_on_single_replica_time_threshold` (recommended `1800`), so each merge runs once per cluster;
  - `wait_for_unique_parts_send_before_shutdown_ms` (recommended `60000`);
  - an init step that creates the `Replicated` database;
  - scale-up and scale-down.

## Capabilities

### New Capabilities
- `clickhouse-replication`: Covers the following:
  - the `Replicated`-database startup check;
  - automatic conversion of plain tables and the replicated-table check;
  - insert deduplication off on every table;
  - replica discovery;
  - the startup order that keeps the instance from writing before its storage is verified;
  - the routing contract (primary for writes and by default, home replica per pipeline run with database and table sync before reading, discovered replicas for user queries with a primary fallback);
  - DDL behaviour with a replica down;
  - classification of Keeper and replication failures;
  - the routing and sync config keys and metrics.

### Modified Capabilities
- `data-sync`: "Target tables are provisioned by versioned ClickHouse migrations". Migrations now provision replicated tables: the runner pre-creates each pending migration's table and the Flyway history table with the replicated engine, and an operator applying DDL out of band applies the replicated form.
- `table-management`: ADDED requirement. Every table the catalog materializes uses the `Replicated` variant of the engine its kind and write discipline select, carries the deduplication settings, and is created in the `Replicated` database. Engine names elsewhere in the spec name the engine family.

## Impact

- **Code:**
  - `clickhouse/` gains replica discovery, per-replica clients for both credentials, the sync step, DDL settings and the new failure codes in `ClickHouseFailures`;
  - `ClickHouseMigrationRunner` gains the pre-create callback;
  - `TableDdlRenderer` renders the replicated engines;
  - pipeline services (`MaterializeService`, `AggregateRefreshService`, `ScanService`, `GroupFetchService`) execute through a run-scoped replica handle;
  - `StructuredQueryExecutor` reads through the query route;
  - `EnrichmentRelayoutService` syncs before marks;
  - `TableSchemaCreationService` stops compensating after a committed DDL;
  - the ClickHouse storage phase (engine check, conversion, migrations, table check) runs behind a central storage-ready gate that holds back the schedulers, `/v1` and Flight until it completes, and the engine check moves ahead of the PostgreSQL migrations;
  - the pipeline syncs bypass `ClickHouseBudget.translate`.
- **API:** No `/v1` endpoint, request or response shape changes. Existing error codes gain new causes: `503 clickhouse_unavailable` now also means "the run's replica could not be brought up to date" and "Keeper unavailable".
- **Configuration:** Five new keys in `docs/configuration.md`. `CLICKHOUSE_HOST` keeps its name and now means the primary.
- **Operations:** Keeper becomes mandatory and the database must be `Replicated`. Deployments already on a `Replicated` database convert their tables automatically on first start, with a short write pause and a deploy that stops the previous version first. An `Atomic` database must be changed by an operator first. New topic doc `docs/clickhouse-replication.md`; `docs/clickhouse-server.md` gains grants and server settings; `docs/kubernetes-exposure.md`'s egress section names the primary Service and every replica pod, replacing the headless Service as `CLICKHOUSE_HOST`. NTP is required on ClickHouse nodes and ADAS pods.
- **Tests:** The Testcontainers ClickHouse runs with embedded Keeper and a `Replicated` database for the whole suite, plus a multi-replica integration test that stops and restarts replicas under ingest, a Class-1 pipeline and an aggregate pipeline. `local_env` gains Keeper, a single-replica `Replicated` database, and a three-replica profile.
- **Supported range:** ClickHouse 25.3 LTS or newer, unchanged. Every mechanism used was verified on 25.3, 26.3 and 26.8.
