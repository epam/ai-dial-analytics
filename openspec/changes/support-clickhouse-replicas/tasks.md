## 1. Test infrastructure on replicated storage

- [x] 1.1 Give the Testcontainers ClickHouse (`ClickHouseContainerSupport`) an embedded Keeper, `macros` and a `Replicated` session database, so the whole suite runs on replicated storage
- [x] 1.2 Add a multi-replica test support class with three ClickHouse containers and one Keeper. It must be able to stop and start a replica, stop a replica's fetches, and route ADAS's primary at a chosen replica
- [x] 1.3 Change `local_env/docker-compose.yml` to a single replica with embedded Keeper and an init script that creates the `Replicated` database. `local_env` stays a single server; the multi-replica setup lives in the tests only

## 2. Replicated engines and insert deduplication

- [x] 2.1 Make `TableDdlRenderer` render `ReplicatedMergeTree` / `ReplicatedReplacingMergeTree(_updated_at)` with no arguments. Merge `replicated_deduplication_window = 0, replicated_deduplication_window_for_async_inserts = 0` into every rendered `SETTINGS`, including next to `ttl_only_drop_parts = 1`
- [x] 2.2 Confirm that every create path uses the renderer: schema define, `POST /v1/tables` with a schema, catalog import, OTel sink creation and the relayout's aligned copy. Update the renderer and DDL tests
- [x] 2.3 Add regression tests on the replicated container:
  - upsert `open → closed → open` reads back `open`;
  - two identical append batches keep 6 rows;
  - a rebuild that reproduces its output is stored again

## 3. Migrations on replicated storage

- [x] 3.1 Add the Flyway `beforeEachMigrate` callback to `ClickHouseMigrationRunner`. It pre-creates a pending migration's `CREATE TABLE IF NOT EXISTS` table with the `Replicated` engine and the two deduplication settings
- [x] 3.2 Pre-create the Flyway history table as `ReplicatedMergeTree PRIMARY KEY (script)` with the plugin's column list, before `migrate()`
- [x] 3.3 Add a test that transforms every `CREATE` in `db/migration/clickhouse`, plus a test that the transformed table's columns, keys, partitioning and TTL equal those of the original statement
- [x] 3.4 Add the project convention to the `context:` section of `openspec/config.yaml`: new ClickHouse migrations declare `Replicated*MergeTree` without arguments plus the two deduplication settings, and keep their one-line header

## 4. Startup checks and automatic table conversion

- [x] 4.1 Make the database-engine check the first startup step, before any DDL, migration or catalog write. Run it through a `FlywayMigrationStrategy`, so it also precedes the PostgreSQL migrations, which seed catalog rows. A non-`Replicated` session database fails startup, naming the database, its engine and the manual procedure
- [x] 4.2 Make the ClickHouse storage phase the first `ApplicationRunner`, replacing today's `ClickHouseMigrationRunner` (`@Order(0)`): conversion → pre-create + Flyway → `IngestTtlReconciler` → table check (the engine check has already run, task 4.1). Nothing else in the instance may write to ClickHouse or serve `/v1` or Flight before it completes: the `@Scheduled` jobs (`BlobPollingService`, `IngestRetrySweep`, `AggregateScheduler`) start with the context and must not run. Enforce it with a central "storage ready" gate opened by the phase: a `TaskDecorator` on both schedulers in `SchedulingConfiguration` that skips runs before the gate opens, a servlet filter answering `503 clickhouse_unavailable` on `/v1/**` until then, and Flight SQL started when the gate opens. Keep liveness answering throughout, and readiness down until the gate opens. Hold one PostgreSQL advisory lock, in its own key namespace, over conversion + migrations + table check
- [x] 4.3 Add `ReplicatedTableConversion`, gated by `clickhouse.migrations.enabled`. It discovers non-replicated `MergeTree`-family tables from `system.tables` (no table list in code; `_relayout_` copies and the Flyway history table included). Each table is converted in these steps:
  1. create the twin from `create_table_query`, with the engine swapped, the dedup settings added and any UUID clause stripped;
  2. `ATTACH PARTITION ALL FROM` the original;
  3. check the counts, retrying once from the twin on a mismatch;
  4. `EXCHANGE TABLES`;
  5. re-check the counts, exchanging back on a mismatch;
  6. drop the old copy.

  Replicated tables still deduplicating get `MODIFY SETTING`
- [x] 4.4 Make the conversion resumable: detect reserved-prefix leftovers (a prefix distinct from `_relayout_`) at start and resume or clean them up. Refuse before any DDL when another replica is registered (active or not) and a plain table exists, unless every other replica is reachable through a per-replica client and holds no rows of it
- [x] 4.5 After the migrations, verify every owned table (migration-provisioned, Flyway history, every `ACTIVE` catalog table) is `Replicated*MergeTree` with both deduplication settings at `0`; otherwise fail startup, naming each offender
- [x] 4.6 Add tests:
  - an `Atomic` database fails startup having issued no DDL, run no PostgreSQL migration and written no catalog row;
  - a `Replicated` database with plain built-in, catalog, OTel, `_relayout_` and history tables converts with unchanged row counts;
  - a conversion interrupted after the attach resumes;
  - two active replicas with a plain table refuse before any DDL, and so does an inactive second replica holding rows;
  - no scheduled job issues a ClickHouse write while the storage phase runs;
  - a replicated table without the setting gets it;
  - a single-replica `Replicated` database starts with migrations enabled, and with migrations disabled and the tables provisioned replicated;
  - migrations disabled over a plain table issues no DDL and fails the table check

## 5. Failure classification and DDL settings

- [x] 5.1 Classify `TABLE_IS_READ_ONLY` (242), `NO_ZOOKEEPER` (225), `KEEPER_EXCEPTION` (999) and `UNKNOWN_STATUS_OF_INSERT` (319) as unavailable in `ClickHouseFailures`. Make data-sync treat them as transient
- [x] 5.2 Run every DDL with `distributed_ddl_output_mode = null_status_on_timeout_only_active` and `distributed_ddl_task_timeout = clickhouse.ddl.task-timeout`. That means `ClickHouseDdlExecutor`, plus the Flyway JDBC URL's `custom_settings` (beside `final=1`; update `ClickHouseMigrationRunner.MIGRATION_SETTINGS` and its Javadoc), the pre-create hook, the history-table create, the conversion and `IngestTtlReconciler`
- [x] 5.3 Change the relayout's `applyTtl` `MODIFY TTL` to `mutations_sync = 2`, so every active replica finishes the recalculate-only mutation before `RESET SETTING materialize_ttl_recalculate_only`. Verify how an inactive replica is reported on 25.3–26.8
- [x] 5.4 Stop `TableSchemaCreationService` and the schema-patch path from compensating a DDL that took effect on the primary: check `system.tables` / `system.columns` before any compensating `DROP`. Verify how client-v2 surfaces a DDL exception streamed after the response headers
- [x] 5.5 Add multi-replica tests:
  - create and patch with a replica stopped succeed, and the replica has the change after restart;
  - startup with a pending migration and a replica stopped completes within the DDL bound;
  - with Keeper stopped, a query is served, `POST …/rows` answers `503 clickhouse_unavailable`, and a refresh answers `503` with its cursor unchanged

## 6. Replica discovery and routing

- [x] 6.1 Add `ClickHouseTopology`, which discovers replicas from the `Replicated` database's own `system.clusters` rows (session database as the cluster, non-empty `database_replica_name`; ignore a same-named `remote_servers` cluster) through `clickhouse.host` every `clickhouse.replicas.discovery-interval`. It identifies the primary (`is_local`), probes `/replicas_status`, keeps the last good set on failure, and falls back to primary-only
- [x] 6.2 Fail startup, while more than one replica is active, when `clickhouse.host` either resolves to more than one address, or is answered by different replicas over several fresh (non-keep-alive) connections. WARN and count on later refreshes when either appears, or when the answering replica changes. Test it with a headless-style multi-address host and with a single-address proxy that alternates replicas
- [x] 6.3 Build per-replica driver clients for both credentials through `ClickHouseClients`, so the shared transport policy still holds, and close them when a replica leaves
- [x] 6.4 Add the five config keys with startup validation: `clickhouse.routing.queries`, `clickhouse.routing.pipelines`, `clickhouse.replicas.discovery-interval`, `clickhouse.replicas.sync-timeout` and `clickhouse.ddl.task-timeout`
- [x] 6.5 Route user query execution (`StructuredQueryExecutor`, which backs DSL, SQL, CSV and Flight) to a healthy replica, spreading across candidates. The primary is a candidate only when there is exactly one healthy non-primary replica. Keep a request's row and count statements on one replica. Re-run once on the primary, before any row reaches the caller, on a connect failure (and exclude the replica until the next refresh) or on `UNKNOWN_TABLE` / `UNKNOWN_IDENTIFIER` / `NO_SUCH_COLUMN_IN_TABLE` from a non-primary replica
- [x] 6.6 Keep ingest, the ledgers, `…/rows`, OTel, DDL, migrations, schema validation and relayout on the primary, and make the primary the default for any statement no route names. Add the ledger syncs at the start of each ingest cycle

## 7. Pipeline runs on their home replica

- [x] 7.1 Add the home choice (rendezvous hash of the pipeline name over the active non-primary replicas, primary fallback) and a run-scoped replica handle carrying the home's read and writer clients
- [x] 7.2 Choose the home over the Keeper-active set less this instance's exclusions. On a connect failure to the home, answer `503` and exclude the replica until the next refresh
- [x] 7.3 Add the sync step. After the run's PostgreSQL reads, issue on the home:
  - `SYSTEM SYNC DATABASE REPLICA <db>`;
  - then `SYSTEM SYNC REPLICA … LIGHTWEIGHT` for the input sources, then joined enrichments, then the run's own target when it reads it.

  Each is bounded server-side by `receive_timeout = clickhouse.replicas.sync-timeout`, with a client socket timeout above it. The syncs run outside `ClickHouseBudget.translate` and the run's remaining-time accounting, so their 159 is a `503`, never a `422` budget refusal. A failure refuses the run (`503` / recorded last error) without moving any position. Confirm `SYSTEM SYNC DATABASE REPLICA`'s bound and failure code on 25.3, 26.3 and 26.8
- [x] 7.4 Execute every statement of a Class-1 materialize call through the run handle (`MaterializeService`: probe, re-probes, clamp probe, `INSERT … SELECT`)
- [x] 7.5 Do the same for aggregate refresh and preview (`AggregateRefreshService`: probe, write, sample) on both the scheduled and API paths
- [x] 7.6 Do the same for runner scan, group-rows and previews (`ScanService`, `GroupFetchService`)
- [x] 7.7 Sync the enrichment on the primary before each relayout mark, slice, copy, epoch check and final missing-row check (`EnrichmentRelayoutService`)
- [x] 7.8 Add the routing meters (active replicas, discovery outcome, sync duration and outcome per table, statements per route and replica, pipeline runs in flight per replica, user queries re-run on the primary, answering-replica changes), and put the replica name on each run's log line and span
- [x] 7.9 Add a multi-replica integration test under continuous ingest on the primary, with a Class-1 pipeline and an aggregate pipeline on a home replica. It stops a home's fetches, restarts another replica, and kills the home to force a move. Assert: no source row is missing from the enrichment, none is enriched without its payload, aggregate totals equal a recomputation, and refused runs moved no position
- [x] 7.10 Add tests:
  - runner scan pages continue gap-free across a home change;
  - `clickhouse.routing.pipelines=primary` keeps every run on the primary;
  - a run on a home that has not applied a create or a relayout exchange syncs the database first and writes to the right table;
  - a sync timeout under a pipeline budget answers `503`

## 8. Documentation

- [x] 8.1 Add `docs/clickhouse-replication.md`, covering:
  - topology and routing;
  - ClickHouse requirements: Keeper with 1 or 3 members, macros, interserver credentials, identical `config.d` and users, resolvable host names with one HTTP port, `database_replicated_allow_only_replicated_engine = 1`, `execute_merges_on_single_replica_time_threshold` (`1800`, with the `system.part_log` tuning rule), `wait_for_unique_parts_send_before_shutdown_ms` (`60000`), the init step that creates the database, and readiness;
  - the clock-skew and safety-lag requirement, covering ClickHouse nodes and ADAS pods (NTP);
  - scale-up, scale-down and primary failover, including the stalled-ingest runbook: old primary back (self-healing), or gone for good (`SYSTEM DROP DATABASE REPLICA`, then an `includeIngested: true` backfill of the crash days, plus a default backfill over the stall if ADAS restarted for longer than `poll.restart-lookback`);
  - the automatic table conversion: what it touches (every `MergeTree`-family table in the session database, which is the service's own), how to watch it, leftovers, and the registered-replica refusal;
  - the manual procedure for changing an `Atomic` database to `Replicated`;
  - that `CLICKHOUSE_HOST` must reach only the primary
- [x] 8.2 Rehearse on `local_env`: populate a `Replicated` database holding plain tables through the previous ADAS version, start the new version, and verify the automatic conversion and counts. Also rehearse the manual `Atomic` procedure and record its exact commands in the doc
- [x] 8.3 Update `docs/kubernetes-exposure.md`'s egress section. ADAS dials the primary Service and every replica pod's host name on the HTTP port, and `CLICKHOUSE_HOST` stops naming the headless Service once there are several replicas
- [x] 8.4 Update `docs/clickhouse-server.md`: the three new writer grants, Keeper and replication error behaviour, the replicated-engine and dedup notes (replace the paragraph that says `deduplicate_insert` does not reach ADAS), and at-least-once semantics for append and OTel writes on retry
- [x] 8.5 Add one row each to `docs/configuration.md` for the five new keys, and reword the `CLICKHOUSE_HOST` description to "the primary replica: ingest, API writes and DDL"
- [x] 8.6 Add a release upgrade note: Keeper and a `Replicated` database are required. Plain tables convert automatically on first start, with a deploy that stops the previous version first. An `Atomic` database is refused until an operator changes it. `CLICKHOUSE_HOST` must point at the primary only. A headless Service keeps working while there is one replica, but must be replaced by an endpoint that reaches only the primary pod before a second replica is added; startup refuses otherwise
- [x] 8.7 Run the public-repo hygiene sweeps from `CLAUDE.md` (`git grep -nIP` and the multiline perl pass) over the changed files

## 9. Verification

- [x] 9.1 Confirm that the `clickhouse-replication`, `data-sync` and `table-management` delta specs match the shipped behaviour
- [x] 9.2 `./gradlew clean build` passes
