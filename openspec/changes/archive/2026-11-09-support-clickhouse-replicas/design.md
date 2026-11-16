## Context

**Current state.**
- ADAS talks to one ClickHouse endpoint (`clickhouse.host`/`port`). It does so through four driver clients — the read client, the writer client, the usage-log writer and the catalog row writer — plus Flyway's JDBC connection.
- Every table is a plain `MergeTree` / `ReplacingMergeTree` in an `Atomic` database.
- Scheduled jobs run on every ADAS instance without leader election. The only cross-instance lock is the relayout's PostgreSQL advisory lock.
- Incremental pipelines advance a cursor over a server-stamped version column (`_ingested_at` / `_updated_at`, `DEFAULT now64(3)`), with a 5-second safety lag evaluated on the server running the statement.
- Several invariants assume every statement reaches the same server:
  - a Class-1 window is a probe on the read client followed by an `INSERT … SELECT` on the writer client;
  - an aggregate refresh is a probe followed by a write;
  - ingest writes the payload part before its source part, so an enrichment joined on the payload is never late (the "ingest-written" no-clamp rule);
  - the ingest ledger is "visible once acknowledged";
  - relayout takes marks, then copies, then counts.

**Why now.** On a single server the resource ceiling is one node. Pipeline scans, pipeline writes and merges are where it runs out first. A running deployment ingests on the order of 10^5 usage events and several GB of request and response bodies a day, and this design targets an order of magnitude more. At that scale the heavy work is:
- merging the payload table's day partitions;
- the `FINAL` reads inside pipeline statements: Class-1 windows every minute over payload JSON, aggregate dirty-key recomputes every few minutes, rebuilds, and runner body scans.

**Constraints the maintainers set.**
- Replicated storage on every deployment, single-node included.
- Replica topology is owned by infrastructure and ClickHouse, never listed in ADAS configuration.
- Pipeline work, writes included, moves off the node that takes ingest.
- User queries use the same discovery.
- The supported server floor stays ClickHouse 25.3 LTS.

**Evidence.** A three-replica Docker prototype (one Keeper, three servers) checked every ClickHouse behaviour this design relies on, on 25.3.14, 26.3.21, 26.3.62 and 26.8.36. The figures below come from that prototype on synthetic data. They are orders of magnitude, not benchmarks.

## Goals / Non-Goals

**Goals:**
- The same ADAS build and configuration run on a single-shard cluster of 1..N replicas, with no silent divergence and no skipped cursor rows.
- Pipeline runs, user queries and merges spread across replicas. The primary keeps ingest and API writes.
- Topology changes (adding or removing a replica) need no ADAS configuration change.
- Single-node behaviour stays as it is, apart from the replicated engines and the Keeper dependency.

**Non-Goals:**
- Sharding and `Distributed` tables. The goal is load and availability, not data beyond one node's disk.
- Automatic primary failover. Infrastructure moves the primary Service; see Risks.
- Write availability for ingest and `POST …/rows` while the primary is down. They answer `503` and their callers retry, as today.
- Parallel replicas and quorum inserts; see D6.
- Converting existing deployments from inside ADAS. That is an operator procedure.

## Decisions

### D1. A `Replicated` database and `Replicated*MergeTree` tables everywhere

A `Replicated` database replicates DDL itself: `CREATE`, `ALTER`, `DROP` and `EXCHANGE` reach every replica. A
replica that joins later creates every table from the database's metadata. Tables declared `Replicated*MergeTree`
with no arguments get the server's default path (`/clickhouse/tables/{uuid}/{shard}`, `{replica}`), so ADAS never
spells a Keeper path. All of this was verified on all three versions.

**Rejected alternatives:**
- **Engine follows the database engine** (plain on `Atomic`, replicated on `Replicated`). Two DDL paths and two test matrices forever, and the conversion still has to happen when a deployment scales out. The maintainers chose a single path.
- **`Atomic` database with replicated tables and `ON CLUSTER`.** `CREATE`/`DROP`/`EXCHANGE` would not propagate, a new replica would need every table created by hand, and every runtime DDL would need a `remote_servers` cluster definition.
- **Plain engines inside a `Replicated` database.** This is the hazard, not an option. The prototype created the table on every replica but replicated no data: 1 row on the inserting replica, 0 on the others, silently.
  - `database_replicated_allow_only_replicated_engine = 1` refuses such a create (`UNKNOWN_STORAGE`, code 56) on all three versions.
  - ADAS's operator documentation recommends that setting in the default profile as defence in depth, so that an older ADAS build or an operator cannot create one.

### D2. Insert deduplication is switched off per table

Replicated tables deduplicate inserted blocks by default. Measured:

| Behaviour (default settings)                                           | 25.3        | 26.3        | 26.8        |
|------------------------------------------------------------------------|-------------|-------------|-------------|
| Two identical `INSERT … VALUES` → rows kept                            | 1 of 2      | 1 of 2      | 1 of 2      |
| Upsert `open → closed → open` (server-stamped `_updated_at`) → `FINAL` | `open` ✓    | `closed` ✗  | `closed` ✗  |
| Two identical `INSERT … SELECT` → rows kept                            | 3 of 6 ✗    | 6 of 6      | 6 of 6      |
| `insert_deduplicate = 0` honoured                                      | yes         | **ignored** | **ignored** |
| `deduplicate_insert = disable` honoured                                | unknown setting | yes     | yes         |
| Table setting `replicated_deduplication_window = 0` honoured           | yes         | yes         | yes         |

On 26.x the block hash ignores the server-filled `DEFAULT` column, so a write that repeats an earlier payload is
dropped and an upsert ends on the wrong value. On 25.3, a rebuild that reproduces its previous output is dropped.
The table setting is the only spelling that disables deduplication across the range. It goes on every table,
including the Flyway history table, and the startup guard refuses a table without it.

**Rejected alternatives:**
- **Query-level settings.** They differ by version: `insert_deduplicate` is ignored on 26.x and `deduplicate_insert` does not exist on 25.3.
- **Server default in `config.d`.** Correctness would then rest on an operator remembering it. Tuning belongs to infrastructure; correctness belongs to ADAS.

### D3. Fresh installs: pre-create each pending migration's table replicated

Applied migrations are checksummed and create plain engines (V1, V2, V10, V13, V17). The plan for fresh installs:

- **Pre-create hook.** `ClickHouseMigrationRunner` registers a Flyway `beforeEachMigrate` callback. For a pending migration whose script declares `CREATE TABLE IF NOT EXISTS <t> … ENGINE = <X>`, the callback creates `<t>` from that statement, with `<X>` swapped for its `Replicated` counterpart and the two deduplication settings merged into the statement's `SETTINGS` (V13 and V17 already carry `ttl_only_drop_parts = 1`). The migration's own statement then finds the table and does nothing, so the checksum is unchanged.
- **History table.** It is pre-created as `ReplicatedMergeTree PRIMARY KEY (script)` with the plugin's exact column list, before `migrate()`.
- **Tests.** A test walks every file in `db/migration/clickhouse` and asserts that each `CREATE` transforms. A second test asserts that the transformed table's columns, keys and TTL equal those of the untransformed statement on a scratch database.
- **Later migrations.** From V18 on, migrations declare replicated engines themselves. The callback is a no-op for them, because their engine is already replicated.

**Rejected alternatives:**
- **Editing applied migrations.** It breaks checksums on every existing deployment.
- **Flyway's `clusterName`.** It renders `ON CLUSTER` plus explicit Keeper path arguments, both of which a `Replicated` database treats specially. It also only covers the history table.
- **Plain creates followed by a conversion migration.** Plain engines would have to be allowed, and there would be a window in which each replica holds its own data.
- **Replicated twin files.** Five duplicated DDL files that could drift from their originals.

### D4. Converting existing deployments: tables automatically, the database engine never

Deployments already run on a `Replicated` database whose ADAS tables are plain. A deployment managed by ClickHouse's
Kubernetes operator typically already has Keeper and a `Replicated` database. Every ADAS table in it is still plain
with default deduplication, because ADAS has only ever rendered plain engines; that includes the Flyway history
table. That is the expected starting state, so ADAS converts it itself.

**Tables in a `Replicated` database: automatic, at startup, before the migrations.**
- **Which tables.** The candidates come from `system.tables`: every non-replicated `MergeTree`-family table in the session database. No list is kept in code, so catalog tables created by earlier versions, OTel sinks, the relayout's `_relayout_` copies and the Flyway history table are all covered. The session database is the service's own; this is documented.
- **The steps for one table.**
  1. Create a twin under a reserved prefix from `create_table_query`, with the engine swapped and the deduplication settings added.
  2. `ATTACH PARTITION ALL FROM` the original. These are hardlinks: seconds per table and no extra disk.
  3. Compare physical counts without `FINAL`.
  4. `EXCHANGE TABLES`.
  5. Re-check the counts.
  6. Drop the old copy.
  - Every step was verified on 25.3, 26.3 and 26.8. Both `ATTACH PARTITION ALL FROM` and `EXCHANGE TABLES` work inside a `Replicated` database.
- **Already-replicated tables still deduplicating.** They get `MODIFY SETTING`, which is metadata only.
- **Failure safety.** Before the exchange, the original keeps serving and a failure drops the twin. After the exchange, a count mismatch exchanges back. A restart finds reserved-prefix leftovers and resumes or cleans them up. A table that stays plain is refused by the startup table check, which names it, so nothing serves from a half-converted table.
  - The reserved prefix differs from the relayout's `_relayout_`, and the advisory-lock key is in its own namespace, so the two jobs never mistake each other's leftovers.
  - A pre-exchange mismatch is retried once from the twin's creation before the table is given up. A TTL merge with `ttl_only_drop_parts` can drop a part from the original after `ATTACH PARTITION ALL FROM` took its snapshot, which is a mismatch that loses nothing.
- **Refusing diverged copies.** A plain table in a `Replicated` database keeps one independent copy per replica. If another replica's copy may hold rows, the copies have diverged, because writes landed on whichever replica a connection reached. Picking the primary's copy would silently discard the others. ADAS therefore refuses before any DDL whenever another replica is **registered** in the database (active or not), unless every other replica is reachable and holds no rows of that table.
  - Counting only active replicas is not enough. A stopped replica holding a diverged copy would, on restart, replay the twin's creation, the exchange and the drop from the database log. Its copy would be discarded with no error.
  - A replica registered but gone for good is removed with `SYSTEM DROP DATABASE REPLICA` before the upgrade; the refusal names it.
- **One converter at a time.** A PostgreSQL advisory lock, the relayout's pattern, makes one instance convert while the others wait. The same lock is held through the pre-create hook, the Flyway run and the table check. Flyway's ClickHouse support has no lock of its own ("No concurrent migration supported"), and the pre-create hook adds DDL to that path. With several instances starting at once, which a `Recreate` deploy does, two of them would otherwise migrate concurrently.
- **Only when provisioning is enabled.** The conversion is provisioning DDL, so it runs only when `clickhouse.migrations.enabled` is true. With migrations disabled the service issues no provisioning DDL (data-sync spec), and the table check refuses every plain table it checks, leaving the operator to convert it.
- **Nothing in the converting instance writes meanwhile.** Spring starts `@Scheduled` cron jobs (blob poll, retry sweep, aggregate tick) and the embedded web server when the context finishes refreshing, which is **before** any `ApplicationRunner` runs. An `ApplicationRunner` alone, like today's `ClickHouseMigrationRunner`, would let the conversion race the instance's own ingest: a poll that fires during the attach-to-exchange window writes rows into the table about to be dropped. The conversion therefore runs behind a storage-ready gate (D13).
- **Deploy-skew.** An instance of the previous version would keep writing to the original table during the attach-to-exchange window, and those rows would be lost. The upgrade note therefore requires a deploy that stops the previous version first: `Recreate`, or scale to zero. The counts are re-checked after the exchange to catch a violation.

**An `Atomic` database: refused, changed by an operator.** The engine check is the first thing startup does, so a
refusal leaves ClickHouse and the catalog untouched. The log names the database and engine and points to the
manual procedure in `docs/clickhouse-replication.md`:
1. Create a `Replicated` database.
2. Re-create and attach each table, as above.
3. `RENAME DATABASE` twice to swap the names.
4. Redeploy.

Changing a database engine needs `CREATE`/`DROP DATABASE` and a swap that affects everything else in the
database, so it stays out of ADAS.

**Rejected alternatives:**
- **A hard-coded table list.** It misses catalog and OTel tables.
- **A table set derived from the migration scripts and the catalog.** Deployments hold ADAS tables that match neither, so they would be left plain and refused by the table check.
- **Converting inside the Flyway migrations.** Migrations are checksummed and run per version, while the set of tables to convert depends on the deployment.
- **`RENAME TABLE` from an `Atomic` database into a `Replicated` one.** Refused (`NOT_IMPLEMENTED`, code 48).
- **The `convert_to_replicated` flag file.** It needs filesystem access on every server and a restart.
- **Converting an `Atomic` database automatically.** It needs database-level privileges, and it renames a database that may hold more than ADAS.

### D5. Write model: one writing replica per table

ClickHouse executes both halves of an `INSERT … SELECT`, the `FINAL` read and the write, on the server that
receives it. "Only the primary writes" and "pipeline `INSERT … SELECT`s run off the primary" therefore cannot both
hold. Cursor correctness needs one writing replica per table, so that each table's version column has one clock
and one view.

| Writes                                                      | Replica                                  |
|-------------------------------------------------------------|------------------------------------------|
| Ingest targets, the ledgers, `POST …/rows`, OTel, all DDL, migrations, relayout | primary (`clickhouse.host`) |
| A pipeline's target (Class-1 enrichment, aggregate rollup)  | that pipeline's home replica              |

Ingest stays on the primary because the ingest-ordering invariants (ledger, flush order) and the ingest ledger's
skip decision need one view. Ingest is also cheap to replicate. A 300k-row, ~300 MB `JSONEachRow` batch cost the
inserting replica the same on plain and replicated tables: ~1 s, ~0.9 GiB, ~0.6 s CPU. Each follower fetched the
~35 MiB compressed part in ~40–50 ms. Small inserts gain one Keeper commit, about 10–15 ms locally.

**Rejected alternatives:**
- **W1, a single writer.** Every `INSERT … SELECT` stays on the primary, so the primary keeps the `FINAL` read load that is the bottleneck.
- **W3, a single writer with a remote read.** `INSERT` on the primary, reading `remote(<home>, view(SELECT …))`. It rewrites every pipeline SQL compiler and puts interserver credentials into statements.
- **Any replica writes ingest.** It needs a sync before every ledger read and breaks the payload-before-source order across replicas, all to spread insert parsing, which is the cheap part.

### D6. A pipeline run is pinned to its home and synchronized first

**Mechanism.**
- **Home choice.** The home is a rendezvous (highest-random-weight) hash of the pipeline name over the active non-primary replicas. A topology change moves only the pipelines whose home was added or removed, and every ADAS instance agrees from the same discovered set.
- **Pinning.** Every statement of a run is issued through a run-scoped handle bound to the home's two clients.
- **Sync order.**
  1. The run reads any PostgreSQL position it needs first, including the freshness clamp.
  2. It issues `SYSTEM SYNC DATABASE REPLICA <db>`, so the home has applied every DDL in the database's log.
  3. It issues `SYSTEM SYNC REPLICA <t> LIGHTWEIGHT` for its input sources, then for its enrichments, then for its own target when the run reads it.
  4. Only then does it probe and write.
- **Why the database sync.** A `Replicated` database applies DDL on each replica asynchronously. `null_status_on_timeout_only_active` (D8) returns once the *active* replicas have applied it, or once the timeout passes, so a slow replica can still be behind when a create, patch or exchange returns. A home that is behind on DDL fails in one of two ways:
  - Visibly: a just-created table, or a column a patch just added, is unknown (`UNKNOWN_TABLE`, code 60; `UNKNOWN_IDENTIFIER`, code 47). That is a statement rejection today, so the run would be reported as `4xx`.
  - Silently: the relayout `EXCHANGE`s an enrichment on the primary, and a home that has not applied it resolves the name to the old table's UUID. Its `INSERT … SELECT` writes into the table the relayout is about to drop.

  The database sync closes both before the first statement resolves a name. A run already in flight at an exchange is covered by the relayout's hold and settle margin, as on a single server. The sync is a few Keeper reads when the home is current.
- **Why that order.** Ingest commits each payload part before its source part. A source part visible to the source sync was committed before that sync, so its payload was committed earlier still, and the payload sync, issued later, waits for it. A clamp read from PostgreSQL names positions committed before the syncs, so the synced replica holds everything the clamp vouches for.
- **What the order alone does not cover.** The syncs only wait for entries already in the log. Background fetches keep running, so between the payload sync and the statement a *newer* source part can become visible without its payload, because fetch order is not commit order. The window's upper bound excludes such a part: the probe applies the safety lag, and the write reuses the probe's end tuple. The part was committed after the source sync, so every row in it carries a version no older than (source sync − longest insert). That is above the horizon (probe − safety lag) whenever the safety-lag inequality under **Clocks** holds. Sync order and safety lag are therefore one mechanism, and the operator documentation states them together.
- **Sync versus the statement budget.** A sync that times out fails with `TIMEOUT_EXCEEDED` (159). `ClickHouseBudget.translate` turns *every* 159 raised under a budget into the budget's own refusal (`422`, non-retryable). The sync therefore runs outside the run's budget translation and does not draw on its remaining time. It carries its own server-side bound, `receive_timeout = clickhouse.replicas.sync-timeout`, which is what `SYSTEM SYNC REPLICA` waits under. The client socket timeout must exceed that bound, so the server, not the transport, reports the timeout. Its 159 maps to `503` unconditionally.

**Evidence.** In a simulation with continuous ingest on the primary and a Class-1-style pipeline on a home replica,
three disruptions were applied in turn: the home's fetches stopped for 20 s, another replica restarted, and the home
was killed, forcing a home change.
- With the sync: 0 of ~70k source rows were missed, 0 were enriched without their payload, and the run was refused 5 times while the home was behind or being replaced.
- Without the sync, with probe and write reaching different replicas as through a load-balanced Service: ~11k of ~67k rows, about a sixth, were skipped forever with no error.
- Cost: `SYNC … LIGHTWEIGHT` on an up-to-date replica adds a few milliseconds over a plain round trip.
- Failure mode: when the only holder of a part is down, the sync times out (`TIMEOUT_EXCEEDED`, code 159, governed by `receive_timeout`). ADAS already maps that code to `503`, so the run is refused rather than reading a partial replica.

**Clocks.** Three clocks are involved:
- the primary's clock stamps source versions;
- the home's clock evaluates the safety-lag horizon (`now64(3)` in the probe);
- the ADAS instance's JVM clock stamps the drained horizon (`MaterializeService` records `Instant.now()` taken before the probe), which downstream freshness clamps compare against versions.

The safety lag (default 5 s) must therefore exceed the clock skew among all of them, plus the longest insert, plus
the gap from the first sync to the statement. On a single server the first two clocks were one, and only the
JVM-to-server skew counted. With NTP-synchronized nodes the skew is milliseconds. This is documented as an
operator requirement covering ClickHouse nodes and ADAS pods; the default is unchanged.

**Home agreement is for efficiency, not correctness.** Two runs of one pipeline can execute on different replicas:
- `AggregateScheduler` has no leader election, so every ADAS instance may refresh the same pipeline on the same tick;
- instances can briefly hold different discovery snapshots.

Each such run is correct on its own, because it syncs before it reads. The target collapses duplicates as it
does on a single server today. Agreement on the home keeps a target's writes, and their `_updated_at` clock, on
one replica in the steady state. The home is therefore chosen from the Keeper-reported active set, which every
instance reads the same way. A replica that one instance cannot reach is excluded by that instance alone.

**Target versions.** A target's `_updated_at` is stamped by its home. During a home change, or on a tick that two
instances run on two homes, the same target is written from two replicas whose clocks differ by the skew.
- Successive writes of a key by later windows are a refresh interval apart, minutes rather than milliseconds, so their order by `_updated_at` is unaffected.
- Two concurrent writes of one key read the same synced input for every row inside the shared window, so whichever collapses last carries the same value.
- A row newer than one run's window is re-dirtied by the next window.

**Grants.** The syncs run on the writer credential (`SYSTEM SYNC REPLICA ON <db>.*` and
`SYSTEM SYNC DATABASE REPLICA ON <db>.*`), so the read credential, which user queries also use, gains no privilege.

**A home that cannot be reached.** A run whose first statement cannot connect to its home answers `503`, as any
unreachable ClickHouse does. The instance excludes that replica until the next discovery refresh, so the
pipeline's next run lands on another home without waiting up to `discovery-interval` plus the Keeper session
timeout. Nothing is retried inside the failed run, because a run that has started may already have read.

**Rejected alternatives, all measured on 26.8:**
- **Parallel replicas.** ClickHouse refuses `FINAL` under parallel replicas (`SUPPORT_IS_DISABLED`, code 344), and every pipeline statement reads with `final = 1`. Parallel replicas did offload a non-`FINAL` query and remain available to user reads.
- **`select_sequential_consistency`.** It protects reads only against parts written with `insert_quorum ≥ 2` and `insert_quorum_parallel = 0`. With the defaults a lagging replica returned 10 of 20 rows silently. With non-parallel quorum, 7 of 8 concurrent inserts were refused (`UNSATISFIED_QUORUM_FOR_PREVIOUS_WRITE`, code 286), and inserts need two live replicas (`TOO_FEW_LIVE_REPLICAS`, code 285).
- **A longer safety lag alone.** Replication lag is unbounded: a replica can be down holding parts nobody else has.
- **A horizon clamped from `system.replication_queue` inside the statement.** Its safety depends on whether ClickHouse evaluates the scalar subquery before or after it snapshots the parts.
- **A sticky proxy (chproxy) keyed on `session_id`.** ClickHouse sessions are per user, while a run spans two credentials. It also adds a component and a hop.

### D7. Topology is discovered from `system.clusters`

A `Replicated` database registers itself as a cluster named after the database. On 25.3 and 26.8,
`system.clusters` lists each replica's `host_name`, `database_replica_name` and `is_active`; `is_active` flipped to 0
within the Keeper session timeout after a replica stopped. A server managed by ClickHouse's Kubernetes operator also
defines a `remote_servers` cluster called `default`, so a database named `default` shows two rows per replica.
Discovery keeps only the rows carrying a database replica name; the operator's row has a null `is_active`. ADAS reads it through `clickhouse.host` on the writer credential
(`SELECT ON system.clusters`), every `clickhouse.replicas.discovery-interval`.

- **Primary.** It is whatever `clickhouse.host` reaches (`is_local = 1`). In a cluster, infrastructure points it at the primary pod's FQDN or at a Service that selects only that pod, like a database writer endpoint. Once there are several replicas, an endpoint that reaches all of them spreads writes across them, and two such shapes are common:
  - A **headless Service** over all pods resolves to several addresses. Startup fails when `clickhouse.host` resolves to more than one address while the database has more than one active replica.
  - A **ClusterIP Service** over all pods resolves to one virtual address, so a DNS check cannot see it. Startup therefore also checks identity: it opens several fresh connections (no keep-alive) to `clickhouse.host` and asks each which replica answered (`is_local`). It fails when they disagree. Each discovery refresh also compares the replica that answered with the previous one. A change is logged at WARN and counted, because it means either a failover or an endpoint that spreads. On every refresh the check runs on one connection, and only at startup on several, so its cost stays negligible.
- **Addressing.** Replicas are addressed by `host_name` on `clickhouse.port`. That requires resolvable host names, such as StatefulSet pod FQDNs behind a headless Service, and one HTTP port across replicas. Both are documented.
- **User-read health.** It comes from each replica's own `/replicas_status`, whose lag threshold is the server's replicated MergeTree settings `min_relative_delay_to_close` / `min_absolute_delay_to_close`. That keeps the threshold in infrastructure.
- **Caveats.**
  - `/replicas_status` still answers OK while Keeper is down. That is acceptable for reads, which keep working without Keeper.
  - It judges every replicated table on the server, other databases included, so a lagging table outside ADAS's database takes the replica out of user reads.
  - It knows nothing of database-level DDL lag, which is covered by the user-read fallback below.
- **User-read fallback.** A user query fails on a non-primary replica in two cases where the primary would still serve it:
  - the connection cannot be made, because the replica stopped since the last refresh;
  - the replica reports a table or column the catalog knows as unknown (`UNKNOWN_TABLE` 60, `UNKNOWN_IDENTIFIER` 47, `NO_SUCH_COLUMN_IN_TABLE` 16), because the replica has not yet applied a recent DDL.

  In either case, provided no row has been sent to the caller yet, the query runs once more on the primary. The replica is excluded until the next refresh after a connection failure. The read credential gains no sync privilege for this.

This is the pattern drivers for replicated databases use: discover the topology from the database and route by
read preference.

**Rejected alternatives:**
- **A replica URL list in ADAS configuration.** The maintainers rejected it.
- **A load-balanced reader Service for user reads.** One more endpoint, and pipelines need per-replica addressing anyway.
- **The Kubernetes API.** It couples ADAS to one orchestrator.

### D8. DDL with a replica down

Measured with one of three replicas stopped:

| `distributed_ddl_output_mode`        | 25.3                                           | 26.3 / 26.8                        |
|--------------------------------------|------------------------------------------------|------------------------------------|
| `throw` (the default)                | applied on active replicas, then code 159 after the timeout, streamed after the response headers | same |
| `throw_only_active`                  | timed out when the replica had just crashed (its Keeper session was still live) | returned at once |
| `null_status_on_timeout_only_active` | returned, with a `NULL` status for the down host | returned at once |

ADAS sets `null_status_on_timeout_only_active` with `distributed_ddl_task_timeout = clickhouse.ddl.task-timeout`.
A DDL that took effect on the primary is never compensated. Before any compensating `DROP`, the create path checks
`system.tables` on the primary, and the patch path checks `system.columns`. The stopped replica applied every DDL
from the database log on restart, `CREATE`, `ALTER ADD COLUMN` and `EXCHANGE TABLES` alike.

**Startup DDL needs the same settings.** Every statement in a `Replicated` database goes through the database's
DDL queue, startup included. Without the settings, a stopped replica makes each startup DDL wait for the default
`distributed_ddl_task_timeout` (180 s) and then fail with 159, which aborts startup. The settings therefore apply
on every DDL path, not only on `ClickHouseDdlExecutor`:
- the Flyway JDBC URL's `custom_settings`, beside `final=1`;
- the pre-create hook and the history-table create;
- the conversion;
- `IngestTtlReconciler`.

**Mutations wait for the active replicas where a later statement depends on them.** The relayout applies a TTL as
three statements:
1. `MODIFY SETTING materialize_ttl_recalculate_only = 1`;
2. `MODIFY TTL … SETTINGS mutations_sync = 1`;
3. `RESET SETTING`.

On a replicated table, `mutations_sync = 1` waits only for the initiating replica. Every other replica runs the
TTL mutation after the reset has reached it, and so rewrites the whole table instead of recomputing TTL
information. The step uses `mutations_sync = 2`, which waits for the active replicas. A replica that is down at
that moment rewrites on recovery, which costs work but loses nothing. The step does not wait for inactive replicas
(`replication_wait_for_inactive_replica_timeout = 0`), treats the `UNFINISHED` ClickHouse may report for them as
success, and resets the setting in a `finally`, so no reporting variant leaves it in place. No other runtime statement depends on a
mutation having finished.

### D9. Keeper and replication failures are `503`

With Keeper stopped, on all three versions:
- `SELECT` kept working;
- `INSERT` and `INSERT … SELECT` failed with `TABLE_IS_READ_ONLY` (242);
- `CREATE` and `ALTER` failed with `KEEPER_EXCEPTION` (999);
- `SYNC REPLICA` failed with 242.

`NO_ZOOKEEPER` (225) and `UNKNOWN_STATUS_OF_INSERT` (319) are added from ClickHouse's error catalogue; they were
not provoked. All four join `ClickHouseFailures`. `READONLY` (164) is left out: it is a statement rejection
except for one message, and was not observed.

**Why `503` even for the cases that do not clear by themselves.** `KEEPER_EXCEPTION` mostly means a lost Keeper
connection or an expired session, which clear on their own. It also covers rarer coordination errors that persist,
such as a node that already exists. Likewise, a replica whose Keeper metadata was lost stays `TABLE_IS_READ_ONLY`
until an operator runs `SYSTEM RESTORE REPLICA`. Even those are not the caller's fault: the same statement
succeeds once the cluster is repaired.
- A `4xx` would tell a first-party caller to fix or drop its request. The runner would then discard valid enrichment rows on every Keeper blip.
- A `503` makes it retry, which costs a round trip per attempt.

So `503` is the right answer in every case. A persistent case is an operator problem, made visible by the log line,
which names the replica and the code. The message is not read to split the code into transient and persistent
sub-cases. `ClickHouseFailures` classifies by code alone, and this keeps it that way.

### D10. Merges are spread by a server setting, not by ADAS

By default every replica executes every merge itself. One `OPTIMIZE … FINAL` over 30 parts ran 6 merges on each of
three replicas. With `execute_merges_on_single_replica_time_threshold` set, each replica ran 2 and fetched the rest,
so the primary's merge share drops to about 1/N. The payload table's settled-day force-merges are the largest
merges ADAS causes, so this setting is the main lever on the merge part of the bottleneck.

It is documented as an infrastructure setting (`config.d` `<merge_tree>`) together with
`wait_for_unique_parts_send_before_shutdown_ms`. That second setting makes a gracefully stopping primary wait
until its unique parts are fetched, so home runs are not refused during rolling restarts.

**Recommended value: `1800` (30 minutes).** The value is how long the other replicas wait for the chosen replica
to finish a merge before they merge locally instead. It is bounded on both sides:
- Too low, and a settled payload day's force-merge outlasts it, so every replica runs the largest merge anyway. That forfeits the point of the setting.
- Too high, and a chosen replica that is overloaded or has just died holds the merge back on every replica for that long. Parts then accumulate. On the primary, accumulated parts are what `TOO_MANY_PARTS` on ingest is measured against.

The largest merges ADAS causes are expected to take minutes at the volumes this design targets, so 30 minutes
should clear them with room. The tuning rule below covers deployments where that does not hold. The operator
documentation gives the tuning rule: at least twice the longest `MergeParts` duration of
`dial_usage_log_payload` in `system.part_log`. It also says to lower the value if the primary's active part count
approaches `parts_to_delay_insert`. `wait_for_unique_parts_send_before_shutdown_ms` is recommended at `60000`,
which exceeds the measured fetch time of the largest ingest part with room.

### D11. Key parentheses: no change

A `METADATA_MISMATCH` over redundant key parentheses, a suspected failure mode, did not reproduce in any of these cases:
- `ATTACH PARTITION ALL FROM` in every combination of `ORDER BY (k)` and `ORDER BY k`;
- adding a replica;
- `ALTER`;
- mixed 26.3/26.8 clusters in both directions.

The versions tested were 26.3.21, 26.3.62 and 26.8.36. Keeper stores the key normalized (`primary key: id`), while
`SHOW CREATE` prints `id` on 26.3 and `(id)` on 26.8. `TableDdlRenderer` keeps its rendering. The multi-replica
integration test covers a single-column key, so a regression is caught.

### D12. Configuration, credentials, migrations

- **New keys:**
  - `clickhouse.routing.queries` and `clickhouse.routing.pipelines`: `replicas` (default) or `primary`. These are escape hatches that send everything to one server without reconfiguring infrastructure.
  - `clickhouse.replicas.discovery-interval` (30s);
  - `clickhouse.replicas.sync-timeout` (30s);
  - `clickhouse.ddl.task-timeout` (30s).
- **Changed meaning:** `clickhouse.host` now means the primary. Its name and default are unchanged.
- **New writer grants:** `SYSTEM SYNC REPLICA ON <db>.*`, `SYSTEM SYNC DATABASE REPLICA ON <db>.*` and `SELECT ON system.clusters`. The read credential is unchanged.
- **Users:** both credentials must exist identically on every replica, through replicated access storage or identical `users.d`.
- **Migrations:** no new ClickHouse or PostgreSQL migration. The pre-create hook replaces one.
- **Default route.** A statement that no route names runs on the primary, so a new code path is safe by default and moves off the primary only deliberately.

### D13. Startup order: nothing writes before the storage is verified

Spring Boot runs PostgreSQL Flyway during context refresh. When the refresh completes, it starts `@Scheduled` jobs
and the embedded web server. Only after that does it call `ApplicationRunner`s. Today's ClickHouse migrations run
as an `ApplicationRunner`, so the blob poll, the retry sweep and the aggregate tick can already fire while they
run. That is harmless today, because a missing table fails a cycle that is retried. It is not harmless for the
conversion: a poll during the attach-to-exchange window writes rows into the table that is then dropped.

The ClickHouse storage phase therefore completes before anything in the instance can write. The phase is:
1. the engine check;
2. the conversion, under the advisory lock;
3. the pre-create hook and Flyway, still under the lock;
4. the TTL reconciliation;
5. the table check.

The readiness probe alone is not enough: it keeps a Kubernetes Service's traffic away, but not the instance's own
cron jobs. Two mechanisms were weighed.

- **Run the phase inside context startup** (a bean that the schedulers and the web server depend on). This is simple and nothing can start early, but the web server, and with it `/actuator/health`, is not up while the phase runs. A conversion of a large database takes minutes. A liveness probe without a generous startup probe would kill the pod mid-conversion, and it would resume, be killed again, and so on.
- **A central "storage ready" gate (chosen).** The application starts normally, so liveness answers. Readiness stays down until the phase completes, as Spring Boot already does until its runners finish. The phase runs as the first `ApplicationRunner`. The gate is enforced in three places, never per job:
  1. a `TaskDecorator` on both schedulers declared in `SchedulingConfiguration` (`taskScheduler`, `aggregateTaskScheduler`), which skips any scheduled run before the gate opens, so a job added later is covered without remembering to check;
  2. a servlet filter that answers `503 clickhouse_unavailable` on `/v1/**` before the gate opens, for traffic that bypasses the Service, such as a port-forward;
  3. the Flight SQL server, which starts when the gate opens instead of with the context.

  A test asserts that no ClickHouse statement other than the phase's own is issued before the gate opens.

The engine check also runs before PostgreSQL Flyway, through a `FlywayMigrationStrategy`, so an `Atomic` database
is refused with the catalog untouched, as the spec promises. PostgreSQL migrations can seed catalog rows.

## Risks / Trade-offs

- **Breaking upgrade.** Keeper and a `Replicated` database are now required. → Deployments already on a `Replicated` database convert their tables automatically. An `Atomic` database is refused before any change, with the manual procedure named.
- **Automatic conversion runs DDL against live data at startup.** → Hardlinks only, so no data is rewritten. A count check before and after each exchange, with exchange-back on mismatch. Resumable leftovers under a reserved prefix. One converter under an advisory lock. A refusal when replicas have already diverged. A deploy that stops the previous version first.
- **The primary is still the only target for ingest and API writes.** → Ingest retries on its cadence, `POST …/rows` and OTel callers retry on `503`, and the primary can be sized apart from the other replicas. Failover means repointing the primary Service. The ledger sync at the start of each ingest cycle stops a new primary from re-ingesting blobs it has not fetched the record of. Pooled keep-alive connections can keep reaching the old primary until they close, so restarting ADAS after repointing is the documented step.
- **A failover away from a primary that died with unfetched parts stalls ingest.** The new primary's ledger sync waits for parts only the old primary holds and times out, so every cycle is skipped. → Chosen over ingesting without the sync, for four reasons:
  1. **Pipelines stall anyway.** The dead primary's unique parts are mostly ingest parts. Every home's source sync waits for the same parts and refuses its runs, so rows ingested during the stall could not be consumed downstream.
  2. **Nothing is lost by waiting.** A skipped cycle leaves the poller's watermark where it was, so the first cycle after the stall re-covers the whole backlog from blob storage.
  3. **Proceeding costs real money.** A blob whose ledger entry is missing but whose rows did arrive would be ingested again with a new `_ingested_at`. Every scan cursor would deliver it again, and Class-2 enrichments would run their LLM calls again.
  4. **The window is small.** It holds only what the primary wrote in the fetch latency before it died, tens of milliseconds. `wait_for_unique_parts_send_before_shutdown_ms` removes it entirely for a graceful stop.

  The runbook in `docs/clickhouse-replication.md` covers both outcomes:
  - **The old primary comes back:** it serves its parts, the syncs complete, and ingest and pipelines resume by themselves.
  - **It is gone for good:**
    1. Drop it with `SYSTEM DROP DATABASE REPLICA`. The other replicas then give up the parts only it held, and the syncs complete.
    2. Backfill the day or days of the crash with `includeIngested: true`, which restores the rows only it held. The ledger may record their blobs from a part that did arrive, so the default backfill would skip them.
    3. If ADAS restarted during the stall for longer than `poll.restart-lookback`, also run a default backfill over the stall. The poller's watermark is in memory, so the restart moved it past part of the backlog.

  The ledger-sync failure meter is the alert signal.
- **DDL reaches non-primary replicas asynchronously.** → Pipeline runs sync the database first (D6). User queries fall back to the primary on an unknown table or column (D7). The relayout's exchange cannot be raced by a home, because every run resolves names only after the database sync.
- **A replica down while holding unique parts refuses its pipelines' runs until it returns.** → This is correct rather than silent. `wait_for_unique_parts_send_before_shutdown_ms` removes the common case, a rolling restart.
- **Host names that do not resolve or ports that differ make discovery useless.** → Every route degrades to the primary with a WARN and a meter, and the requirement is documented.
- **Clock skew greater than the safety lag could skip rows.** → An NTP requirement for ClickHouse nodes and ADAS pods, and a stated safety-lag inequality in the operator docs (D6 **Clocks**).
- **With deduplication off, a transport replay or a retried `UNKNOWN_STATUS_OF_INSERT` can duplicate append-table rows.** → Same as the transport replay on a single server today. Documented as at-least-once for `append` and OTel tables. Upsert tables and enrichments collapse.
- **A Keeper outage stops all writes, DDL and pipeline runs, while reads continue.** → Three Keeper members for clusters, and a `503` everywhere rather than a misreported `4xx`.
- **User reads can be stale by the replication lag.** → Bounded by the replica's `/replicas_status` threshold. No user read takes a cursor.
- **Mixed ADAS versions during rollout.** An older build would create plain tables. → `database_replicated_allow_only_replicated_engine = 1` in the default profile refuses them, and the conversion happens with ADAS stopped.

## Migration Plan

1. **Infrastructure:**
   - Keeper: embedded in the server on a single node, or a three-member ensemble.
   - Per-replica `macros` (`shard`, `replica`), interserver credentials, and identical `config.d` (including `encryption_codecs`).
   - Identical ADAS users with the new writer grants.
   - `database_replicated_allow_only_replicated_engine = 1`, the merge threshold, and the shutdown wait.
   - An init step that creates the `Replicated` database.
   - A primary Service, and resolvable pod host names.
   - NTP on every ClickHouse node and ADAS pod.
2. **Fresh install:** start ADAS. The migrations pre-create replicated tables.
3. **Existing deployment on a `Replicated` database:** deploy the new version with a strategy that stops the previous one first. Startup converts the tables. If the database has more than one registered replica, first make sure only one of them ever held ADAS data, and drop the others with `SYSTEM DROP DATABASE REPLICA`, or empty their copies. Otherwise the conversion refuses (D4). Rollback: the previous ADAS version runs on replicated tables. It reads and writes them normally, but its table creates render plain engines, which `database_replicated_allow_only_replicated_engine = 1` refuses.
4. **Existing deployment on an `Atomic` database:** startup refuses without changes. The operator changes the engine as described in D4, then redeploys.
5. **Scale out:** add a replica that creates the same `Replicated` database. ADAS starts routing to it after the next discovery refresh.
6. **Scale in:** stop the replica, and drop it with `SYSTEM DROP DATABASE REPLICA` once its pipelines have moved.

## Open Questions

- **Mid-stream DDL exceptions.** Whether client-v2 surfaces an exception ClickHouse streams after the response headers in `null_status_on_timeout_only_active` mode, for example a DDL another replica failed. It could not be provoked on 26.8. The obvious attempt is a replica missing the encryption key, asked for a `CREATE TABLE` with an `AES_256_GCM_SIV` column. That replica applied the create from the database log and reported `OK`, because replicas apply a DDL from the log without repeating that check. The response carried an `X-ClickHouse-Exception-Tag` header, which is how the server marks an exception streamed after the headers. `ClickHouseDdlExecutor` reads the whole status result, so such an exception reaches it if the driver raises it at all. Either way the compensation rule in D8 makes the answer non-critical: a create or patch that took effect on the primary is treated as applied.

## Resolved questions

- **The database sync's bound.** `SYSTEM SYNC DATABASE REPLICA` honours `receive_timeout` and fails with `TIMEOUT_EXCEEDED` (159), "database is readonly or command timed out", when the bound elapses. That is the code ADAS already maps to `503`. With Keeper down it fails at once with `KEEPER_EXCEPTION` (999). On a current replica it returns in well under a second. The timeout was observed on 26.8, on a replica that had just restarted. Every pipeline run in the multi-replica tests issues it, on each version in the suite's matrix.
- **`mutations_sync = 2` with a replica down.** The relayout's `MODIFY TTL … SETTINGS mutations_sync = 2` returns at once on 25.3, 26.3 and 26.8. The active replicas finish the mutation, and the down replica is reported `QUEUED` in the DDL status rows and applies it on recovery. Nothing waits for it.
- **User reads with two replicas.** With exactly two replicas, every pipeline home lands on the single non-primary replica. User reads then spread over both replicas, the primary included, so the pipeline replica does not also carry every user query. With three or more replicas, user reads stay off the primary.
- **Per-home run concurrency: no cap in this change.** Today every run of every pipeline executes on the one server. With homes, each replica executes a subset of those runs, so no replica runs more at once than the single server does now. With two replicas, the non-primary replica runs today's pipeline load without ingest. The existing bounds still apply on every replica: the per-statement budgets (`pipeline.budget.*`), the aggregate scheduler pool and the server's `max_concurrent_queries`. A gauge of in-flight runs per replica shows whether a cap is ever needed.
