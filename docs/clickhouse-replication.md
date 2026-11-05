# ClickHouse replication

ADAS runs on a single-shard ClickHouse cluster of one or more replicas. Every deployment, a single
server included, uses a `Replicated` database whose tables are `Replicated*MergeTree`, so going from
one replica to several never changes a table. The behaviour is specified in
[`openspec/specs/clickhouse-replication/spec.md`](../openspec/specs/clickhouse-replication/spec.md);
this page is the operator's side of it.

## Topology and routing

ADAS learns the replicas from ClickHouse rather than from its configuration. A `Replicated` database
registers itself in `system.clusters` as a cluster named after the database. ADAS reads those rows
through `CLICKHOUSE_HOST` at startup and every `CLICKHOUSE_REPLICAS_DISCOVERY_INTERVAL`. It keeps only
the rows that carry a database replica name, so a `remote_servers` cluster of the same name is ignored.

| Work                                                                                                        | Replica                                                                   |
|-------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------|
| Ingest, the ingest and failure ledgers, `POST …/rows`, OTel, all DDL, migrations, relayout, schema checks   | the **primary**: whatever `CLICKHOUSE_HOST` reaches                       |
| One pipeline run (materialize, refresh, scan page, group-rows, preview)                                      | the pipeline's **home**: a stable hash of its name over the non-primary replicas |
| User queries (DSL, SQL, CSV, Flight SQL)                                                                     | a healthy replica, spread per request                                     |
| Anything else                                                                                               | the primary                                                               |

- **Homes.** With no active non-primary replica the home is the primary. Adding or removing a replica
  moves only the pipelines whose home it was. Every ADAS instance computes the same home from the
  same Keeper-reported set.
- **Before a run reads anything,** its home runs `SYSTEM SYNC DATABASE REPLICA <db>` and then
  `SYSTEM SYNC REPLICA <table> LIGHTWEIGHT` on the run's sources, then on its enrichments. Each sync
  is bounded by `CLICKHOUSE_REPLICAS_SYNC_TIMEOUT`. A sync that cannot finish refuses the run with
  `503 clickhouse_unavailable`, and no cursor or position moves.
- **User queries** stay off the primary while two or more non-primary replicas are healthy, and
  include it when there is exactly one. Health is each replica's own `/replicas_status`, so the lag
  threshold is the server's `max_replica_delay_for_distributed_queries`. A query re-runs once on the
  primary when its replica cannot be reached or reports an unknown table or column, which means the
  replica has not applied a recent DDL yet.
- **A single server** resolves every route to itself.
- **Escape hatches.** `CLICKHOUSE_ROUTING_QUERIES=primary` and `CLICKHOUSE_ROUTING_PIPELINES=primary`
  send that workload to the primary without changing infrastructure.

### `CLICKHOUSE_HOST` must reach only the primary

Writes through a host that reaches several servers land on a different replica per connection. While
the database has more than one active replica, startup fails when `CLICKHOUSE_HOST`:

- resolves to more than one address, as a headless Service over every pod does; or
- is answered by different replicas over several fresh connections, as a ClusterIP Service over every
  pod is.

Point it at the primary pod's host name, or at a Service that selects only the primary pod. A headless
Service over all pods keeps working while there is a single replica, but must be replaced before a
second replica joins. After startup, a change of the answering replica is logged at WARN and counted
(`clickhouse.replicas.primary_changes`), because a failover looks the same as a spreading endpoint.

## ClickHouse requirements

- **Keeper**: embedded in the server on a single node, or a three-member ensemble for a cluster.
- **Macros** on every replica: `shard` (the same value everywhere) and `replica` (unique).
- **Interserver credentials** so replicas can fetch parts from each other.
- **Identical configuration** on every replica: the same `config.d`, including `encryption_codecs`,
  and the same users. Create both ADAS credentials through replicated access storage or identical
  `users.d` files.
- **Resolvable host names, one HTTP port.** Each replica is reached at the `host_name` it reports in
  `system.clusters`, on `CLICKHOUSE_PORT`. StatefulSet pod host names behind a headless Service work.
- **`database_replicated_allow_only_replicated_engine = 1`** in the default profile. It refuses a
  non-replicated `MergeTree` inside a `Replicated` database, where it would keep a separate copy on
  each replica.
- **`execute_merges_on_single_replica_time_threshold`**, recommended `1800`, in `<merge_tree>`, so each
  merge runs once in the cluster instead of once per replica. Set it to at least twice the longest
  `MergeParts` duration of `dial_usage_log_payload` in `system.part_log`. Lower it if the primary's
  active part count approaches `parts_to_delay_insert`.
- **`wait_for_unique_parts_send_before_shutdown_ms`**, recommended `60000`, in `<merge_tree>`, so a
  replica stopping gracefully waits until its unique parts are fetched. Without it, a rolling restart
  refuses the runs of pipelines whose sources it alone held.
- **An init step that creates the database** before ADAS starts:

  ```sql
  CREATE DATABASE IF NOT EXISTS <db> ENGINE = Replicated('/clickhouse/databases/<db>', '{shard}', '{replica}')
  ```

- **Readiness** that reports a replica ready only once it has joined the database.

The writer credential needs three grants on top of
[`clickhouse-server.md`](clickhouse-server.md#writer-credential):

```sql
GRANT SYSTEM SYNC REPLICA ON <db>.* TO <writer>
GRANT SYSTEM SYNC DATABASE REPLICA ON <db>.* TO <writer>
GRANT SELECT ON system.clusters TO <writer>
```

Without `SELECT ON system.clusters` discovery fails, ADAS logs a WARN, and every statement runs on the
primary. The read credential gains nothing.

## Clocks and the safety lag

Incremental pipelines advance a cursor over a server-stamped version column. Three clocks take part:

- the primary stamps source versions;
- the home evaluates the safety-lag horizon (`now64(3)` in the window probe);
- the ADAS instance stamps the drained horizon a freshness clamp compares against.

The sync step waits only for writes already committed. A source part committed after it can become
visible before its payload part, so the window's upper bound must exclude it. The safety lag
(`enrichment.scan.safety-lag`, default 5 s) must therefore exceed the clock skew among every
ClickHouse node and ADAS pod, plus the longest insert, plus the time from the first sync to the probe.
Run **NTP on every ClickHouse node and every ADAS pod**. Skew of milliseconds leaves the default with
plenty of room.

## Scale-up, scale-down and failover

- **Scale up.** Start a replica that creates the same `Replicated` database. It creates every table
  from the database's metadata and fetches the data. ADAS routes to it after the next discovery
  refresh, with no configuration change.
- **Scale down.** Stop the replica. Once ADAS reports it inactive, its pipelines move to other homes.
  Drop it for good with `SYSTEM DROP DATABASE REPLICA '<shard>|<replica>' FROM DATABASE <db>`.
- **Primary failover.** Repoint the primary endpoint at another replica, then restart ADAS so pooled
  keep-alive connections stop reaching the old primary. Each ingest cycle first syncs both ledgers, so
  a new primary does not re-ingest blobs whose ledger entries it has not fetched yet.

### A 25.3 replica stuck replaying an old `CREATE`

On ClickHouse 25.3, a replica that was stopped can fail to catch up after it restarts. This happens when, while it
was down, a table was both created and then given an index on a column added after the create. A fresh install's
migrations do exactly that to `dial_usage_log_payload`, and a column patch followed by a lookup index can too. The
replica replays the old `CREATE` from the database log against the table's newer shared metadata, and its DDL
worker retries forever:

```
DDLWorker(<db>): Unexpected error (… times in a row), will try to restart main thread: Code: 341. …
Code: 47. DB::Exception: Missing columns: '<column>' …
```

The replica stays behind on that table and every later DDL, and pipeline runs homed on it are refused with `503`.
26.3 and 26.8 apply the same log without error. To recover, re-join the replica. It then creates every table from
the database's current metadata and fetches the data from the other replicas. On the stuck replica only:

```sql
DROP DATABASE <db> SYNC;
CREATE DATABASE <db> ENGINE = Replicated('/clickhouse/databases/<db>', '{shard}', '{replica}');
```

Do this only while the other replicas are active and hold every part: the replica's local copy is discarded.

### Stalled ingest after a failover

When the old primary died holding parts no other replica had fetched, the new primary's ledger sync
times out and every ingest cycle is skipped. The `clickhouse.replicas.sync` meter with outcome
`timeout` on the ledger tables is the alert signal.

- **The old primary comes back.** It serves its parts, the syncs complete, and ingest and pipelines
  resume by themselves.
- **It is gone for good.**
  1. Drop it with `SYSTEM DROP DATABASE REPLICA`. The other replicas then give up the parts only it
     held, and the syncs complete.
  2. Backfill the day or days of the crash with `includeIngested: true`. That restores the rows only
     it held, which the ledger may record as ingested.
  3. If ADAS restarted during the stall and was down longer than `poll.restart-lookback`, also run a
     default backfill over the stall: the poller's watermark is kept in memory.

## Automatic table conversion

A deployment already on a `Replicated` database whose ADAS tables are plain `MergeTree` /
`ReplacingMergeTree` converts on its first start with this version, when
`CLICKHOUSE_MIGRATIONS_ENABLED` is `true`.

- **What it touches.** Every non-replicated `MergeTree`-family table in the session database, found in
  `system.tables`: the built-in tables, catalog tables, OTel sinks, relayout copies and the Flyway
  history table. The session database belongs to ADAS; keep nothing else in it.
- **How.** For each table: create a replicated twin named `_adas_replicate_<table>`, `ATTACH
  PARTITION ALL FROM` the original (hardlinks, no data rewritten), compare row counts, `EXCHANGE
  TABLES`, compare again, drop the old copy. A replicated table that still deduplicates inserts gets
  `ALTER TABLE … MODIFY SETTING` instead.
- **Watching it.** Each table logs `Converting plain table …` and `Converted … rows in … ms`. Readiness
  stays down, `/v1` answers `503` and scheduled jobs do not run until it finishes. Liveness keeps
  answering, so a long conversion is not killed by a liveness probe.
- **Leftovers.** An interrupted conversion leaves a `_adas_replicate_` table. The next start removes
  it, or finishes the exchange it belonged to.
- **Refusal.** When another replica is registered in the database, active or not, and a plain table
  exists, ADAS refuses before any DDL, naming the tables and replicas. The exception is when every
  other replica is reachable and holds no rows of it. Each replica's plain copy holds its own rows,
  so only an operator can decide which is right. Drop a replica gone for good with
  `SYSTEM DROP DATABASE REPLICA`, or empty its copies, then restart.
- **Deploy order.** Stop the previous version first (`Recreate`, or scale to zero). A previous-version
  instance writing during the conversion would write into the table that is about to be dropped. The
  counts are re-checked after each exchange to catch that.

With `CLICKHOUSE_MIGRATIONS_ENABLED=false` nothing is converted, and startup fails naming every plain
table. Convert them with the steps above, then restart.

## Changing an `Atomic` database to `Replicated`

ADAS never changes a database engine. Against an `Atomic` database it refuses to start before issuing
any DDL or touching the catalog, naming the database and its engine. To change it, stop ADAS, then run
the steps below on the server with `clickhouse-client` or over HTTP. `<db>` is the session database
(`default` when `CLICKHOUSE_DATABASE` is unset).

1. Create a `Replicated` database beside it. The Keeper path names the final database:

   ```sql
   CREATE DATABASE <db>_replicated ENGINE = Replicated('/clickhouse/databases/<db>', '{shard}', '{replica}');
   ```

2. For each `MergeTree`-family table, create its replicated twin in the new database and attach the data
   by hardlink. Take the definition from `system.tables.create_table_query`, change the database, prefix
   the engine with `Replicated` (no arguments) and add the two deduplication settings. For example:

   ```sql
   CREATE TABLE <db>_replicated.dial_usage_log ( … ) ENGINE = ReplicatedReplacingMergeTree(_ingested_at) …
       SETTINGS replicated_deduplication_window = 0, replicated_deduplication_window_for_async_inserts = 0, …;
   ALTER TABLE <db>_replicated.dial_usage_log ATTACH PARTITION ALL FROM <db>.dial_usage_log;
   SELECT count() FROM <db>.dial_usage_log;             -- must equal the next count
   SELECT count() FROM <db>_replicated.dial_usage_log;
   ```

   `RENAME TABLE` from an `Atomic` into a `Replicated` database is refused (`NOT_IMPLEMENTED`), which is
   why the data moves by attach. A shell loop over the tables does the whole step:

   ```bash
   q() { clickhouse-client -q "$1"; }
   for t in $(q "SELECT name FROM system.tables WHERE database = '<db>' AND engine LIKE '%MergeTree'"); do
     ddl=$(q "SELECT create_table_query FROM system.tables WHERE database = '<db>' AND name = '$t' FORMAT TSVRaw" \
       | sed -E "s/^CREATE TABLE <db>\./CREATE TABLE <db>_replicated./; s/ENGINE = ([A-Za-z]*)MergeTree/ENGINE = Replicated\1MergeTree/; \
                 s/ SETTINGS / SETTINGS replicated_deduplication_window = 0, replicated_deduplication_window_for_async_inserts = 0, /")
     case "$ddl" in *SETTINGS*) ;; *) ddl="$ddl SETTINGS replicated_deduplication_window = 0, replicated_deduplication_window_for_async_inserts = 0";; esac
     q "$ddl"
     q "ALTER TABLE <db>_replicated.\`$t\` ATTACH PARTITION ALL FROM <db>.\`$t\`"
     echo "$t: $(q "SELECT count() FROM <db>.\`$t\`") -> $(q "SELECT count() FROM <db>_replicated.\`$t\`")"
   done
   ```

3. Swap the names. Both renames work on the `default` database too:

   ```sql
   RENAME DATABASE <db> TO <db>_atomic;
   RENAME DATABASE <db>_replicated TO <db>;
   ```

4. Redeploy ADAS. It finds every table replicated and starts. Drop `<db>_atomic` once the service is
   verified.
