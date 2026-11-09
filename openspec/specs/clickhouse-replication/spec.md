# clickhouse-replication Specification

## Purpose

This capability defines how ADAS runs on a single-shard ClickHouse cluster of one or more replicas. The session
database is a `Replicated` database and every table a `Replicated*MergeTree` with insert deduplication off, on
every deployment, so scaling from one replica to several never changes a table. Plain tables are converted at
startup, the replica set is discovered from the database's own cluster, and work is routed by kind: writes and
DDL on the primary, each pipeline run on a caught-up home replica, and user queries on a healthy replica. Keeper
and replication failures surface as `503 clickhouse_unavailable`. Operator setup is described in
`docs/clickhouse-replication.md`.

## Requirements

### Requirement: The service runs only on a Replicated database

The service SHALL require its session database (`clickhouse.database`, or `default` when blank) to use
ClickHouse's `Replicated` database engine. This SHALL hold on every deployment, single-replica included, so a
deployment scales from one replica to several without changing any table.

The service SHALL check the database engine first at startup. The check comes before it issues any DDL, writes
any row, or runs the PostgreSQL schema migrations. It SHALL do so whether or not `clickhouse.migrations.enabled`
is set.

The startup storage phase runs in this order: the engine check, replica discovery (which fails startup when
`clickhouse.host` reaches several replicas), the conversion, the ClickHouse migrations with their retention
reconciliation, then the table check. The service SHALL complete the phase before anything in
the instance can write to ClickHouse or serve a request: scheduled ingest, the retry sweep, the aggregate tick,
every `/v1` endpoint, which SHALL answer `503 clickhouse_unavailable` until then, and the Flight SQL endpoint,
which SHALL not accept connections until then. The operational endpoints SHALL keep answering throughout the
phase. Liveness SHALL report the instance alive, and readiness SHALL report it not ready until the phase
completes. A readiness probe that keeps external traffic away is not sufficient on its own, because the
instance's own scheduled jobs do not pass through it.

When the engine is not `Replicated`, the service SHALL abort startup having changed nothing in ClickHouse or in
the catalog. The log SHALL:

- name the database and its engine;
- state that the service does not change a database's engine itself;
- refer to the manual engine-change procedure in `docs/clickhouse-replication.md`.

A redeploy after the operator has changed the engine SHALL start normally.

When ClickHouse cannot be reached at startup, the engine cannot be checked. With
`clickhouse.migrations.enabled` true the service SHALL fail startup, as the migrations would. With it false
the service SHALL log a WARN and start unverified, as it always has without a reachable ClickHouse: every
request that needs ClickHouse then answers `503 clickhouse_unavailable`.

Once the startup conversion (see "Plain tables in the database are converted to replicated tables at startup")
and the migrations have run, the service SHALL verify every table it owns before serving any ingest, pipeline
or query traffic. Each such table SHALL use a `Replicated*MergeTree` engine whose definition sets
`replicated_deduplication_window = 0` and `replicated_deduplication_window_for_async_inserts = 0`. The tables
it owns are:

- every table the ClickHouse migrations provision;
- the Flyway history table;
- every catalog table in status `ACTIVE`.

A table that still fails the check SHALL abort startup, and the log SHALL name it. This happens only when the
conversion did not run, because `clickhouse.migrations.enabled` is false, or when it refused the table. A table
the catalog lists but ClickHouse does not have is not this check's concern; the existing reconciliation reports
it.

#### Scenario: A long conversion does not fail liveness

- **GIVEN** a conversion that takes several minutes
- **WHEN** liveness and readiness are probed during it
- **THEN** liveness reports the instance alive, readiness reports it not ready, and `POST /v1/queries/execute`
  answers HTTP 503 `clickhouse_unavailable`

#### Scenario: Scheduled ingest does not run during the conversion

- **GIVEN** a `Replicated` database whose tables are plain, and a poll cron that fires every minute
- **WHEN** the service starts and the conversion takes longer than a minute
- **THEN** no blob is ingested, and no other ClickHouse write is issued by the instance, until the table check
  has passed

#### Scenario: An Atomic database is refused without any change

- **WHEN** the service starts against a session database whose engine is `Atomic`
- **THEN** startup fails, naming the database, its engine and the manual procedure
- **AND** no DDL was issued, no migration was recorded and no catalog row was written
- **AND** no ingest, pipeline run or query is served

#### Scenario: An unreachable ClickHouse with migrations disabled starts unverified

- **GIVEN** `clickhouse.migrations.enabled` false and no ClickHouse reachable at `clickhouse.host`
- **WHEN** the service starts
- **THEN** it starts, logs a WARN that the storage was not verified, and answers a query with HTTP 503
  `clickhouse_unavailable`

#### Scenario: A redeploy after the engine was changed starts

- **GIVEN** a deployment refused for an `Atomic` database
- **WHEN** the operator re-creates the database as `Replicated` with its tables and data, then redeploys
- **THEN** the service starts

#### Scenario: A table the conversion refused fails startup

- **GIVEN** a plain table the startup conversion refused to convert
- **WHEN** startup reaches the table check
- **THEN** startup fails, naming the table and the reason the conversion gave

#### Scenario: A single-replica Replicated database starts

- **GIVEN** one ClickHouse server with embedded Keeper and a `Replicated` session database
- **WHEN** the service starts with migrations enabled on an empty database
- **THEN** every provisioned table is `Replicated*MergeTree` with deduplication disabled and the service starts

### Requirement: Inserts are never deduplicated

The service SHALL set `replicated_deduplication_window = 0` and `replicated_deduplication_window_for_async_inserts = 0`
on every table it creates: through the migrations, the table catalog, catalog import, an OTel sink, or an
enrichment relayout copy. Two inserts that carry identical blocks SHALL both be stored, as on a non-replicated
table, whichever server version runs and whatever its insert-deduplication defaults are.

#### Scenario: A repeated value is not lost on an upsert table

- **GIVEN** an `upsert_by_key` source `tickets` keyed on `ticket_id`
- **WHEN** three single-row writes for ticket `00000000-0000-4000-8000-000000000001` are posted in turn, with
  `status` `open`, then `closed`, then `open`
- **THEN** a read of the ticket returns `status = open`

#### Scenario: Identical append batches are both kept

- **GIVEN** an `append` source
- **WHEN** the same batch of 3 rows is posted twice
- **THEN** the table holds 6 rows

#### Scenario: A rebuild that reproduces its last output is written

- **GIVEN** an aggregate pipeline whose rebuild produces exactly the rows of its previous run
- **WHEN** the rebuild runs
- **THEN** its `INSERT … SELECT` stores those rows again with a newer `_updated_at`

### Requirement: Plain tables in the database are converted to replicated tables at startup

When the session database is `Replicated` and `clickhouse.migrations.enabled` is true, the service SHALL convert,
at startup and before the migrations run, every table in it that uses a non-replicated `MergeTree`-family
engine. It SHALL find these tables in `system.tables` for the session database, not in a list kept by the
service. The session database therefore belongs to the service: every `MergeTree`-family table in it is
converted, the Flyway history table and the relayout's reserved-prefix copies included. Tables of other engine
families, such as views or dictionaries, are left untouched, and so is a materialized view's implicit storage
table (`.inner.<view>` / `.inner_id.<uuid>`), which its view references by UUID.

Converting one table SHALL take these steps, issued on the primary:

1. Create a twin under a reserved-prefix name. The twin has the table's own definition, from
   `system.tables.create_table_query`, with the engine replaced by its `Replicated` counterpart (no Keeper
   arguments) and the two deduplication settings added to its settings.
2. `ALTER TABLE <twin> ATTACH PARTITION ALL FROM <table>`, which hardlinks the data.
3. Check that the twin's physical row count, read without `FINAL`, equals the table's.
4. `EXCHANGE TABLES <table> AND <twin>`.
5. Check the counts again on the two exchanged tables.
6. Drop the twin, which by then holds the original plain table.

A replicated table in the session database whose definition lacks `replicated_deduplication_window = 0` SHALL
instead receive `ALTER TABLE … MODIFY SETTING replicated_deduplication_window = 0,
replicated_deduplication_window_for_async_inserts = 0`.

Every step SHALL leave the database usable. An interruption, or a count mismatch before the exchange, SHALL
leave the original table in service and remove the twin. A pre-exchange mismatch SHALL be retried once from
step 1 before the table is given up. A mismatch after the exchange SHALL exchange the two tables back. A later
start SHALL detect the remains of an interrupted conversion by its reserved prefix and resume or clean it up: a
twin beside a plain table is dropped and the table converted again; a plain twin beside a converted table is
dropped when the converted table holds at least the twin's rows, and exchanged back otherwise. While any
reserved-prefix table exists, the startup table check SHALL fail, so no instance serves writes into a table whose
conversion has not finished and a resume never compares counts a write has moved.
That prefix SHALL differ from the relayout's. When a table is given up, it stays plain and the startup table
check then refuses it, naming the table and the reason.

The service SHALL refuse to convert, without issuing any DDL, when the database has another registered replica,
active or not, and still holds a plain table. A plain table keeps a separate copy of its data on each
replica, so only an operator can decide which copy is right. A stopped replica would discard its copy, with no
error, when it replays the conversion from the database's log. The refusal does not apply when every other
replica is reachable and holds no rows of that table. The log SHALL name the table, the replicas and the
reason.

Only one service instance SHALL convert at a time; the others wait for it. The same exclusion SHALL cover the
ClickHouse migrations and the table check that follow, because the migration tool takes no lock of its own on
ClickHouse. The conversion assumes that no service instance of a previous version is writing during it. The
upgrade documentation SHALL require a deploy that stops the previous version before the new one starts.

#### Scenario: A deployment with plain tables in a Replicated database is converted

- **GIVEN** a single-replica `Replicated` session database holding the service's tables as plain `MergeTree` /
  `ReplacingMergeTree`
- **WHEN** a version with this requirement starts
- **THEN** every such table, the Flyway history table included, becomes `Replicated*MergeTree` with insert
  deduplication disabled
- **AND** each table holds the same rows as before
- **AND** the service starts without operator action

#### Scenario: The conversion follows the database, not a list

- **GIVEN** a catalog table `orders_raw` created through `POST /v1/tables` before this version
- **WHEN** the service starts
- **THEN** `orders_raw` is converted like the built-in tables

#### Scenario: An interrupted conversion is safe

- **WHEN** the service stops after attaching a table's data to its twin and before the exchange
- **THEN** the original table still serves its data
- **AND** the next start removes the twin and converts the table again

#### Scenario: A conversion interrupted after its exchange is not served until resumed

- **GIVEN** a start that exchanged `orders_raw` with its twin and failed before dropping the old copy
- **WHEN** any instance starts, with migrations enabled or not
- **THEN** with migrations disabled the table check fails naming the leftover twin, and no traffic is served
- **AND** with migrations enabled the leftover is resolved first, the converted table is kept, and the
  service starts

#### Scenario: Diverged replicas are not converted

- **GIVEN** a `Replicated` database with two active replicas, each holding its own plain copy of `dial_usage_log`
- **WHEN** the service starts
- **THEN** startup fails before any DDL, naming `dial_usage_log`, both replicas and the reason

#### Scenario: A stopped replica's plain copy is not discarded

- **GIVEN** a `Replicated` database with replicas `chi-0` and `chi-1`, `chi-1` stopped, and each holding rows in
  its own plain copy of `rate_analytics`
- **WHEN** the service starts against `chi-0`
- **THEN** startup fails before any DDL, naming `rate_analytics` and `chi-1` as a replica whose copy cannot be
  inspected

#### Scenario: Migrations disabled means no conversion

- **GIVEN** `clickhouse.migrations.enabled` false and a plain `dial_usage_log` in a `Replicated` database
- **WHEN** the service starts
- **THEN** no DDL is issued and startup fails at the table check, naming `dial_usage_log`

#### Scenario: A replicated table without the deduplication setting gets it

- **GIVEN** a `ReplicatedMergeTree` table in the session database with default deduplication
- **WHEN** the service starts
- **THEN** the table's settings carry `replicated_deduplication_window = 0` and
  `replicated_deduplication_window_for_async_inserts = 0`, and none of its data was rewritten

### Requirement: The replica set is discovered from the database's own cluster

The service SHALL learn the replicas of its session database from ClickHouse rather than from configuration.
It SHALL read the rows of `system.clusters` that the `Replicated` database contributes for itself. Those rows
carry the session database's name as the cluster, a non-empty database replica name and a non-null activity
flag. The `Replicated` engine maintains these rows as replicas join and leave. A `remote_servers` cluster that
happens to share the database's name SHALL be ignored, because its rows carry neither marker. It SHALL read them through the primary
(`clickhouse.host`) on the writer credential, at startup and then every
`clickhouse.replicas.discovery-interval` (default `30s`, minimum `5s`).

Discovery SHALL work as follows:

- **Which replicas count.** A replica is a candidate while the row reports it active.
- **How a replica is addressed.** A candidate is reached over HTTP at its `host_name` on `clickhouse.port`, with
  the same two credentials the service uses for the primary.
- **Which candidate is the primary.** The primary is the row ClickHouse reports as local when queried through
  `clickhouse.host`.
- **When a candidate drops out.** A candidate whose HTTP endpoint does not answer is excluded until a later
  refresh finds it answering. The same applies when a pipeline run or a user query on this instance fails to
  connect to it. The exclusion is local to the instance that observed it.

`clickhouse.host` SHALL reach exactly one server. Writes through a host that reaches several servers would land
on a different replica for each connection, and one writing replica per table would no longer hold. When the
database has more than one active replica, the service SHALL fail startup, naming the host, in either case:

- `clickhouse.host` resolves to more than one address, as a headless Service does;
- several fresh connections to `clickhouse.host` are answered by different replicas, as through a ClusterIP
  Service that selects every replica.

On every later refresh, the service SHALL log at WARN and count each of these: a host that has started
resolving to several addresses, and a change in which replica answers through `clickhouse.host`. A failover
looks the same as a spreading endpoint, so neither is an error after startup.

When a refresh fails, the service SHALL keep the last replica set it discovered, log a WARN and count the
failure. When no discovery has ever succeeded, every route SHALL resolve to the primary, so the service keeps
serving with one server. Typical causes of a failed refresh are a missing `SELECT ON system.clusters` grant, a
query error, or an unreachable primary.

#### Scenario: A single replica routes everything to itself

- **GIVEN** a `Replicated` database with one replica
- **WHEN** the service routes a write, a pipeline run and a user query
- **THEN** all three execute on that replica

#### Scenario: A new replica is used without a configuration change

- **GIVEN** a running service routing over replicas `chi-0` (primary) and `chi-1`
- **WHEN** replica `chi-2` joins the `Replicated` database
- **THEN** after the next discovery refresh `chi-2` receives user queries and is a candidate home for pipelines,
  with no change to the service's configuration

#### Scenario: A stopped replica stops receiving work

- **GIVEN** replicas `chi-0` (primary), `chi-1` and `chi-2`
- **WHEN** `chi-2` stops and ClickHouse reports it inactive
- **THEN** after the next refresh no user query or pipeline run is routed to `chi-2`

#### Scenario: An operator cluster of the same name is ignored

- **GIVEN** a server whose `remote_servers` defines a cluster named `default`, and a `Replicated` session database
  named `default`
- **WHEN** discovery runs
- **THEN** only the `Replicated` database's replicas are candidates

#### Scenario: A primary endpoint that spreads over replicas is refused

- **GIVEN** a database with two active replicas, and `clickhouse.host` naming a headless Service that resolves to
  both
- **WHEN** the service starts
- **THEN** startup fails, naming `clickhouse.host` and the addresses it resolves to

#### Scenario: A single-address endpoint that spreads over replicas is refused

- **GIVEN** a database with two active replicas, and `clickhouse.host` naming a ClusterIP Service that selects
  both
- **WHEN** the service starts and its fresh connections are answered by both replicas
- **THEN** startup fails, naming `clickhouse.host` and the replicas that answered

#### Scenario: Discovery that is not permitted degrades to the primary

- **GIVEN** a writer credential without `SELECT ON system.clusters`
- **WHEN** the service starts
- **THEN** it starts, logs a WARN naming the missing grant, and routes every statement to the primary

### Requirement: Writes run on the primary

The following SHALL execute on the primary, `clickhouse.host`, whatever the routing configuration:

- data-sync ingest inserts;
- the ingest ledger's and the failure ledger's reads and writes;
- the payload parent lookup;
- `POST /v1/tables/{name}/rows`, `POST /v1/pipelines/{name}/rows` and the OTel sink writes;
- every runtime DDL;
- the startup engine check, the table conversion and the table check;
- the ClickHouse migrations and the retention reconciliation that follows them;
- startup schema validation;
- every statement of an enrichment relayout.

A pipeline's own `INSERT … SELECT` is not covered here; it runs on the pipeline's home replica. Any statement
that no routing rule in this specification names SHALL also execute on the primary.

Each ingest cycle SHALL first issue `SYSTEM SYNC REPLICA … LIGHTWEIGHT` on the primary for the ingest ledger and
for the failure ledger. A replica that has just become the primary may not have fetched every ledger entry yet,
and without the sync it would re-ingest blobs already recorded. A cycle whose ledger sync fails SHALL be skipped
and count as a failed cycle, like any other ClickHouse failure of the cycle. The failure retry sweep SHALL issue the
same syncs before it reads the ledgers, and skip the sweep when they fail. The sync is bounded by
`clickhouse.replicas.sync-timeout`.

After replica discovery and before any DDL, the storage phase SHALL issue `SYSTEM SYNC DATABASE REPLICA` and one
`SYSTEM SYNC REPLICA … LIGHTWEIGHT` on the primary, and SHALL fail startup, naming the missing grant, when the
writer credential is denied either. A deploy missing a grant therefore fails having changed nothing. Any other
failure of these checks (a table not yet replicated or not yet created) is logged and startup continues.

Skipping is deliberate, even when the previous primary is down holding ledger entries no other replica has: ingest
then stays stopped until that replica returns or an operator drops it from the database. A re-ingested row would
carry a new `_ingested_at`, so every cursor over the table would deliver it again. The failover runbook in the
operator documentation SHALL name both remedies, and for a replica dropped for good the backfill that restores
the rows only it held.

#### Scenario: A new primary does not re-ingest recorded blobs

- **GIVEN** the primary role moved to a replica that has not yet fetched the latest ingest-ledger entries
- **WHEN** the next ingest cycle runs on it
- **THEN** the cycle syncs the ledger first and skips every blob the ledger records

#### Scenario: Ingest parts originate on the primary

- **GIVEN** replicas `chi-0` (primary), `chi-1` and `chi-2`
- **WHEN** a data-sync poll ingests a blob
- **THEN** every new part of `dial_usage_log` and `dial_usage_log_payload` is created on `chi-0` and fetched by
  the other replicas

### Requirement: A pipeline run executes on its home replica once the replica has caught up

Each pipeline SHALL have a **home replica**. The home is chosen by a stable, rendezvous-style hash of the
pipeline's name over the active replicas other than the primary. When no such replica is active, the home is
the primary. When `clickhouse.routing.pipelines` is `primary` (default `replicas`), the home is always the
primary. Every service instance SHALL compute the same home from the same replica set. That set is the one
ClickHouse reports active, less any replica this instance has excluded. Agreement keeps a target's writes on one
replica in the steady state. Correctness does not depend on it: two runs of one pipeline on different replicas,
such as two instances refreshing on the same tick, are each correct because each synchronizes before it reads.

A **run** is one of the following:

- one `POST /v1/pipelines/{name}/materialize` call, including its narrowing re-probes and clamp probes;
- one aggregate refresh, scheduled or `POST /v1/pipelines/{name}/refresh`, including its window probe and its
  write;
- one `POST /v1/pipelines/{name}/scan` page;
- one `POST /v1/pipelines/{name}/group-rows` call;
- one `POST /v1/pipelines/{name}/preview` call.

Every ClickHouse statement of a run, on either credential, SHALL execute on the run's home. A pipeline's
`INSERT … SELECT` therefore writes its target table from its home.

Before the run's first data statement, the service SHALL bring the home up to date. It SHALL do this after
reading any position the run takes from the catalog, such as the freshness clamp. On the home it SHALL issue:

1. `SYSTEM SYNC DATABASE REPLICA <db>`, so the home has applied every DDL of the session database's log and
   resolves every table name as the primary does;
2. `SYSTEM SYNC REPLICA <table> LIGHTWEIGHT` for every table the run reads, in this order:
   - each input source the run's cursor or dirty keys range over;
   - every enrichment the run joins;
   - the run's own target, when the run reads it.

Each sync SHALL be bounded by `clickhouse.replicas.sync-timeout` (default `30s`). This SHALL happen whichever
replica is the home, the primary included.

The syncs SHALL NOT draw on the run's statement budget (`pipeline.budget.*`). A sync that exceeds its bound
SHALL be reported as unavailable, never as a budget refusal.

If a sync fails or exceeds its bound, the run SHALL neither read nor write, and no cursor, materialized
position, drain mark or rollup state SHALL move:

- an API call SHALL answer `503 clickhouse_unavailable`;
- a scheduled refresh SHALL record the failure as its last error and run again on its next tick.

The syncs wait only for writes already committed when they run. A source write committed later can become
visible on the home before the enrichment rows it depends on. The run's window SHALL exclude such writes through
its upper bound: the probe applies the safety lag, and the write reuses the probe's end. This holds when the
safety lag exceeds three things together: the clock skew among the ClickHouse replicas and the service
instances, the longest insert, and the time from the first sync to the probe. The operator documentation SHALL
state this requirement.

#### Scenario: A Class-1 window runs on its home replica

- **GIVEN** replicas `chi-0` (primary), `chi-1` and `chi-2`, and a Class-1 pipeline whose home is `chi-1`
- **WHEN** the runner calls `POST /v1/pipelines/{name}/materialize`
- **THEN** the syncs, the window probe and the `INSERT … SELECT` all execute on `chi-1`
- **AND** the enrichment rows are written on `chi-1` and fetched by the other replicas

#### Scenario: A home that cannot catch up refuses the run

- **GIVEN** a Class-1 pipeline whose home `chi-1` cannot fetch a source part that only `chi-0` holds
- **WHEN** `POST /v1/pipelines/{name}/materialize` is called
- **THEN** the response is HTTP 503 `clickhouse_unavailable` once the sync bound elapses
- **AND** the pipeline's materialized position and drain mark are unchanged
- **AND** a later call, after `chi-1` has fetched the part, writes every row of the window

#### Scenario: No row is skipped when the home changes

- **GIVEN** a Class-1 pipeline materializing continuously while ingest runs on the primary
- **WHEN** its home replica stops and the pipeline's runs move to another replica
- **THEN** every source row is materialized exactly once, and none is skipped

#### Scenario: An enrichment flushed before its source row is visible to the run

- **GIVEN** a Class-1 pipeline over `dial_usage_log` that joins `dial_usage_log_payload`
- **AND** data-sync writing each payload part before the source part it belongs to
- **WHEN** a run on a non-primary home reads a source row
- **THEN** the row's payload is visible to the same run. A row visible at the source sync has its payload
  waited for by the later payload sync. A row fetched after the syncs lies above the window's safety-lag bound
  and is left to a later run.

#### Scenario: A home that has not applied a recent DDL catches up before the run

- **GIVEN** a table created through `POST /v1/tables/{name}/schema` while replica `chi-1` was slow, so the
  create returned before `chi-1` applied it
- **WHEN** a pipeline whose home is `chi-1` and whose source is that table runs
- **THEN** the database sync applies the create on `chi-1` first, and the run reads the table rather than
  failing with an unknown-table error

#### Scenario: A relayout exchange does not divert a home's writes

- **GIVEN** an enrichment whose relayout has just exchanged it with its aligned copy on the primary, and its
  writer's home `chi-1` not yet having applied the exchange
- **WHEN** the writer's next materialize runs on `chi-1`
- **THEN** the database sync applies the exchange first, and the `INSERT … SELECT` writes into the aligned table

#### Scenario: A sync timeout is not a budget refusal

- **GIVEN** a pipeline run under a `pipeline.budget.timeout`
- **WHEN** its source sync exceeds `clickhouse.replicas.sync-timeout`
- **THEN** the response is HTTP 503 `clickhouse_unavailable`, not the budget's refusal

#### Scenario: A scheduled refresh that cannot sync retries on its next tick

- **GIVEN** an aggregate pipeline whose home cannot complete its sync
- **WHEN** its scheduled tick runs
- **THEN** the pipeline's state records the failure as its last error with its cursor unchanged
- **AND** the next tick runs the refresh again

#### Scenario: Runner scan pages stay gap-free across replicas

- **GIVEN** a runner paging `POST /v1/pipelines/{name}/scan` with a cursor
- **WHEN** the pipeline's home changes between two pages
- **THEN** the next page, served by the new home after its sync, continues from the cursor without skipping
  a row

#### Scenario: Pipelines can be pinned to the primary

- **WHEN** `clickhouse.routing.pipelines` is `primary`
- **THEN** every pipeline run executes on the primary, after the same syncs

### Requirement: User queries are served by a healthy replica

Every statement that executes a user query SHALL execute on one replica chosen per request. This covers
`POST /v1/queries/execute`, `execute-sql`, `execute-csv` and `execute-sql-csv`, and Flight SQL statement
execution. When a request includes a total count, its row statement and its count statement SHALL execute on
the same replica.

The replica SHALL be chosen as follows:

- **Healthy candidates.** The choice is among the active replicas whose `/replicas_status` endpoint answers
  healthy at the latest discovery refresh. ClickHouse's own lag threshold decides health
  (`max_replica_delay_for_distributed_queries`).
- **Preference.** With two or more healthy non-primary replicas, the primary is not a candidate. With exactly
  one, the primary is a candidate beside it, because that one replica is also the home of every pipeline.
- **Spread.** Successive requests are spread over the preferred candidates rather than pinned to one.
- **Fallback.** When no candidate qualifies, the primary serves the query.
- **Configured override.** When `clickhouse.routing.queries` is `primary` (default `replicas`), the primary
  serves every query.

A user query SHALL NOT be preceded by a sync. It sees what its replica has received, so a row written through
`POST …/rows` or a pipeline run can be absent from a query issued immediately after it.

A query on a non-primary replica SHALL be executed once more on the primary when, before any row has been sent to
the caller, either of these happens:

- the connection to the replica cannot be made;
- the replica reports a table or column unknown (`UNKNOWN_TABLE` 60, `UNKNOWN_IDENTIFIER` 47,
  `NO_SUCH_COLUMN_IN_TABLE` 16). The catalog had already resolved it, so the replica has not yet applied a
  recent DDL.

The answer on the primary is the response.

The statement's settings SHALL be the same whichever replica runs it, row-policy filters included. Schema
discovery, translation and validation execute no ClickHouse statement and are unaffected.

#### Scenario: A query is served by a non-primary replica

- **GIVEN** replicas `chi-0` (primary), `chi-1` and `chi-2`, all healthy
- **WHEN** `POST /v1/queries/execute` runs a query
- **THEN** it executes on `chi-1` or `chi-2`

#### Scenario: With two replicas the primary shares user reads

- **GIVEN** replicas `chi-0` (primary) and `chi-1`, both healthy
- **WHEN** many queries are executed
- **THEN** both `chi-0` and `chi-1` serve some of them

#### Scenario: A lagging replica serves no queries

- **GIVEN** replica `chi-2` whose `/replicas_status` reports it lagging
- **WHEN** queries are executed
- **THEN** none is routed to `chi-2` until it reports healthy again

#### Scenario: Rows and total come from one replica

- **WHEN** a query with `include_total` executes
- **THEN** its row statement and its count statement execute on the same replica

#### Scenario: A replica behind on DDL does not fail a query

- **GIVEN** a column added by `PATCH /v1/tables/{name}/schema` that replica `chi-2` has not yet applied
- **WHEN** a query selecting the column is routed to `chi-2`
- **THEN** the query is answered from the primary, not refused as a bad request

#### Scenario: A replica that stopped since the last refresh does not fail a query

- **GIVEN** replica `chi-2` stopped after the latest discovery refresh
- **WHEN** a query is routed to `chi-2`
- **THEN** the query is answered from the primary, and later queries avoid `chi-2` until a refresh finds it
  answering

#### Scenario: Row policies apply on every replica

- **GIVEN** a caller confined by a row policy on `dial_usage_log`
- **WHEN** the caller's query is served by a non-primary replica
- **THEN** the result is filtered exactly as on the primary

### Requirement: DDL completes while a replica is down

Every DDL the service issues, at startup or at runtime, SHALL run with `distributed_ddl_output_mode =
null_status_on_timeout_only_active` and `distributed_ddl_task_timeout` set to `clickhouse.ddl.task-timeout`
(default `30s`). This covers:

- the ClickHouse migrations, through the migration tool's connection;
- the pre-created migration tables and the migration history table;
- the startup table conversion and the retention reconciliation;
- the table catalog's create, schema patch and delete, and catalog import;
- OTel sink creation, lookup indexes and the provenance column;
- the relayout's statements.

A replica that is stopped therefore delays neither startup nor a runtime DDL beyond that bound.

A mutation that a later statement of the same operation depends on SHALL wait for every active replica, not only
the primary. The relayout's TTL application resets `materialize_ttl_recalculate_only` after its `MODIFY TTL`, so
it waits with `mutations_sync = 2`. A replica that is down at that moment applies the mutation on recovery.

A DDL the primary executed SHALL be treated as applied even when another replica has not executed it yet. Such
a replica applies it from the database's replication log when it recovers. On ClickHouse 25.3 a recovering replica can stall replaying a
table's original `CREATE` against newer shared metadata; the operator documentation SHALL give the signature and
the re-join that recovers it.

The service SHALL NOT compensate for a DDL that took effect on the primary. A failure the service observes after
that point SHALL NOT mark the table `FAILED`, and SHALL NOT drop the table, whether it is a timeout waiting for
other replicas or an error reported by another replica.

A DDL that the primary itself rejects SHALL keep its existing outcome.

#### Scenario: A table is created while a replica is down

- **GIVEN** replicas `chi-0` (primary), `chi-1` and `chi-2`, with `chi-2` stopped
- **WHEN** `POST /v1/tables/{name}/schema` defines and materializes a table
- **THEN** the call succeeds within `clickhouse.ddl.task-timeout` and the table is `ACTIVE`
- **AND** after `chi-2` restarts the table exists on `chi-2` with the same definition

#### Scenario: A schema patch is applied while a replica is down

- **GIVEN** one replica stopped
- **WHEN** `PATCH /v1/tables/{name}/schema` adds a column
- **THEN** the call succeeds, and the stopped replica has the column after it restarts

#### Scenario: Startup is not held by a stopped replica

- **GIVEN** replicas `chi-0` (primary), `chi-1` and `chi-2`, with `chi-2` stopped and a pending migration
- **WHEN** the service starts
- **THEN** the migration is applied within `clickhouse.ddl.task-timeout` per statement and the service starts
- **AND** `chi-2` applies the migration's DDL after it restarts

#### Scenario: A DDL the primary rejects still fails

- **WHEN** a create renders a definition the primary rejects
- **THEN** the table is marked `FAILED` and the response is the existing rejection, as on a single server

### Requirement: A Keeper or replication failure is reported as unavailable

The service SHALL report the following ClickHouse error codes as HTTP 503 `clickhouse_unavailable` on every path
that talks to ClickHouse. They mean the cluster's coordination is impaired, not that the statement is wrong:

- `TABLE_IS_READ_ONLY` (242);
- `NO_ZOOKEEPER` (225);
- `KEEPER_EXCEPTION` (999);
- `UNKNOWN_STATUS_OF_INSERT` (319).

The classification SHALL be made in the same shared place as the resource-exhaustion classification, and data-sync
SHALL treat these codes as a transient failure of the blob, which the retry sweep retries.

An insert answered `UNKNOWN_STATUS_OF_INSERT` may already have been stored, so retrying it can store the rows
twice. On an `upsert_by_key` table or an enrichment the copies collapse. On an `append` table and an OTel sink
table a retried write is at-least-once. Callers of `POST …/rows` and the OTel endpoints SHALL be told this in
the operator documentation.

#### Scenario: Reads keep working while Keeper is down

- **GIVEN** the Keeper ensemble unavailable
- **WHEN** a user query is executed
- **THEN** it is answered from the replica's data

#### Scenario: A row write while Keeper is down is retryable

- **GIVEN** the Keeper ensemble unavailable
- **WHEN** `POST /v1/tables/{name}/rows` is called
- **THEN** the response is HTTP 503 `clickhouse_unavailable`, not a 4xx

#### Scenario: A pipeline run while Keeper is down is refused as unavailable

- **GIVEN** the Keeper ensemble unavailable
- **WHEN** an aggregate refresh is requested
- **THEN** the response is HTTP 503 `clickhouse_unavailable` and the cursor is unchanged

### Requirement: A primary-side read of a pipeline-written table is synchronized first

Before an enrichment relayout reads anything it uses to decide what to copy, keep or drop, the service SHALL
issue `SYSTEM SYNC REPLICA <table> LIGHTWEIGHT` on the primary for that table. The relayout reads its marks, the
slice keys, the copy and catch-up selects, the epoch-partition checks and the final missing-row check from that
table, which a pipeline may write from its own home replica. A sync that fails or exceeds
`clickhouse.replicas.sync-timeout` SHALL fail the relayout step, with the existing failure handling for that
step.

#### Scenario: A relayout of a home-written enrichment loses no row

- **GIVEN** an enrichment written by a Class-1 pipeline whose home is not the primary
- **WHEN** the enrichment is relaid out while the pipeline keeps materializing
- **THEN** every grain key present in the old table is present in the new one when the old table is dropped

### Requirement: Routing is observable and configurable

The service SHALL expose the following meters:

- the number of active replicas it routes over;
- the outcome of every discovery refresh (`ok`, `failed`);
- the duration and outcome of every replica sync (`ok`, `timeout`, `failed`), tagged by table;
- the number of statements routed per route (`primary`, `pipeline`, `query`) and replica;
- the number of pipeline runs in flight per replica;
- the number of user queries re-executed on the primary, tagged by reason (`unreachable`, `ddl_lag`);
- the number of refreshes that found a different replica answering through `clickhouse.host`.

Every pipeline run's log line and trace span SHALL name the replica it ran on.

The routing keys SHALL be validated at startup, and an invalid value SHALL fail startup:

- `clickhouse.routing.queries` and `clickhouse.routing.pipelines`, each `replicas` or `primary`;
- `clickhouse.replicas.discovery-interval` at least `5s`, and `clickhouse.replicas.sync-timeout` and
  `clickhouse.ddl.task-timeout` each at least `1s`.

#### Scenario: An invalid routing value fails startup

- **WHEN** `clickhouse.routing.pipelines` is set to `nearest`
- **THEN** startup fails naming the property and its accepted values

#### Scenario: A refused sync is visible

- **WHEN** a pipeline run is refused because its home could not sync `dial_usage_log`
- **THEN** the sync meter records a `timeout` outcome for `dial_usage_log` and the run's log line names the home
  replica
