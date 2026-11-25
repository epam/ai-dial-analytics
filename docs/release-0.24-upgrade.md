# ADAS 0.24 — upgrade guide

This guide covers moving from **ADAS 0.23.3** to **0.24**. If you run an older build, first follow
[`release-0.23-upgrade.md`](release-0.23-upgrade.md) up to 0.23.3.

Three components ship together in this release and are deployed in one window:

- **ADAS 0.24**
- **[Enrichment Runner](https://github.com/epam/ai-dial-analytics-enrichment-runner) 0.24**
- **[Admin UI](https://github.com/epam/ai-dial-admin-frontend) 0.24**

Every API-level step in this guide is done in the **Admin UI**. Steps on ClickHouse and Kubernetes are done with
`clickhouse-client` and your deploy configuration.

---

## 1. Breaking changes

| # | What changed | What you have to do |
|---|---|---|
| B1 | **ClickHouse must be replicated.** ADAS needs ClickHouse Keeper and a `Replicated` database, on every deployment, a single server included. It refuses to start on an `Atomic` database. | Check the database engine, and convert it if needed (step P1). |
| B2 | **The first start converts every ADAS table** to its replicated form. API and ingest are down while it runs, and a previous-version pod writing during it can lose rows or fail the conversion. | Stop the old ADAS before the new one starts (step P5, deploy step D2). |
| B3 | **`CLICKHOUSE_HOST` must reach the primary ClickHouse pod only.** A Service over all ClickHouse pods works while there is one pod, and stops ADAS from starting once a second replica appears. | Point it at the pod's own host name (step P3). |
| B4 | **The ClickHouse writer user needs four new grants.** Without two of them ADAS does not start. | Add the grants (step P2). |
| B5 | **ADAS and Enrichment Runner must both be 0.24.** The runner contract moves from `4` to `7`, and each refuses the other's previous release, so enrichment stops until both match. | Upgrade both in the same window (deploy steps D1 and D4). |
| B6 | **Chart `y_field` is now a list** in saved-query and dashboard API responses. An older Admin UI cannot show charts from ADAS 0.24. A string is still accepted on write. | Deploy Admin UI 0.24 right after ADAS (deploy step D5). Move any other client that reads `y_field` to the list. |
| B7 | **Catalog export files are format version 4.** An ADAS 0.23 refuses to import them. | Upgrade an environment before you import an export from 0.24 into it. |
| B8 | **A column only row-confined roles allow is masked rather than hidden.** When a column is allowed by some of the roles a caller reads the table through (for a caller that only gets the table, the roles it gets it through), every one of them confined to some rows, and not by the others, the column is now listed and queryable, its value present on the rows an allowing role admits and absent elsewhere; before, it was hidden ([`row-level-security.md`](row-level-security.md#column-policies)). JSON rows of `execute` and `execute-sql`, and so of the saved queries and widgets run through them, carry a `$masked` member listing the absent and partial cells. Where the source or one of its enrichments stores a column whose physical name is the name of one of those tables, a column that would be masked is hidden instead. | Before the deploy, declare the columns of a client that shows every row member, such as a generic JSON panel, which otherwise shows one more column. Read `$masked` where a masked column is summed, or a partial total reads as complete. |
| B9 | **`$masked` is a reserved output name** for every caller: a query projecting it is refused `400` (Flight SQL `INVALID_ARGUMENT`, `validate-sql` `valid: false`), and so is saving a saved query or widget that projects it. | Rename such outputs before the deploy. |
| B10 | **In a query that references a masked field, a wildcard included, some statements that ran are refused `400`** (Flight SQL `INVALID_ARGUMENT`, `validate-sql` `valid: false`): `INTERSECT` and `EXCEPT` (DSL `intersect`/`except`) over an output that reads the field, and an output name (an alias or a derived or generated name, at any level) or a lambda parameter equal to a table the statement reads or beginning with `<table>.`. An output named after the enrichment field it projects, such as an enrichment field selected without an alias, is exempt. | Before the deploy, rename such aliases and parameters, and name the outputs instead of `*`. For an `INTERSECT` or `EXCEPT`, drop the masked field from the outputs it compares. |
| B11 | **A rows-only entry's `columns` list now narrows what the rows it adds show.** On 0.23.3 the list of an entry carrying `rows` for a caller it gives no read path was ignored there, and the rows it added showed the governing roles' columns; now a column the list leaves out is absent on the rows only that entry adds (hidden, where B8's fallback applies). The same list still narrows the callers the role governs. | Before the deploy, review entries that carry `rows` and a `columns` list on a role without `read` on the table. If the role has no `get` or `modify` there and no global mapping, `columns: null` keeps the 0.23.3 result. Otherwise the one list narrows both the callers the role governs and the rows it adds: left as is, those rows show fewer columns, and any column you add also shows to the callers it governs. |

## 2. What else is new

None of these need action during the upgrade. They are available once all three components run 0.24.

- **Enrichment pipelines can call a DIAL application route** instead of a model. An administrator first allows the
  route in **System Properties → Enrichment routes**; a pipeline can be enabled only for an allowed route.
- **Dry run of a model pipeline.** The Enrichment Runner can send a sample row or group to a pipeline's model or
  application and show what it answers, before the pipeline is enabled. It runs as the person who asks, who needs
  `REGISTRY_ADMIN`, read access to the source and write access to the target. Each dry run counts towards the
  pipeline's daily spend.
- **Per-pipeline input token budget** for group pipelines, set on the pipeline's page. `0` turns the runner's input
  trimming off for that pipeline.
- **Route pipeline outputs** can fill `object` and `map` columns.
- **Charts** can show several series and a legend.
- **Catalog export and import can carry table access** (role grants, system tables included) when you tick the
  option in **Export Config** / **Import Config**. Both need `TABLE_GRANT`. An API client that reads import
  responses must accept their new `access` list.
- **A catalog export can carry less than a full closure**, for an environment that already has the tables. A
  pipeline can be exported without its dependencies, and a table's access alone, optionally for some roles only.
  The import never creates a missing table for these; its preview reports one a pipeline needs, or an access entry
  names, as a failure. The runbook is [`catalog-promotion.md`](catalog-promotion.md).
- **Faster failure when Keeper is down.** Row writes, pipeline runs and data-sync retries fail with `503` in about
  ten seconds instead of hanging for about two minutes. Data-sync ingest itself still waits out a short outage.
- **Smaller payload storage under `SYNC_REQUEST_BODY_MODE=delta`.** A large request body that is not JSON (for
  example a chat body cut at the size limit) and repeats an earlier one exactly is stored once. Later rows that
  repeat it have an empty `request_body`; the full body is in `request_body_full`. This matters to SQL and to
  pipelines that read `request_body` as text (`length`, `LIKE`, regular expressions); `json_extract_*` reads as
  before. Once you run `delta` on 0.24, two rules apply:
  - **Re-ingest a UTC day whole, on one release and one configuration.** Re-ingesting part of a day on 0.23,
    under `whole`, with `request_body` left out of ingest, or after changing `SYNC_INLINE_FILES_DROP_TRUNCATED` or
    `SYNC_INLINE_FILES_MAX_SIZE` leaves the empty rows of that day reading `partial` with an empty body until the
    whole day is re-ingested.
  - **To roll back, switch to `whole-with-path` on 0.24, do not go back to 0.23.** 0.23 reads every such row as
    `partial` with an empty body.
- **The recommended catalog** detects the DIAL CLI harness from the new `X-DIAL-CLI-Harness` header (post-deploy
  step A4).
- **A pipeline whose outputs cannot be turned into a model response schema is refused when you enable it**, or
  change it while enabled, with a message naming the output. Before, it was accepted and then silently never ran
  (post-deploy step A3).
- **Masked columns on other surfaces** (see B8): CSV export and Flight SQL return an absent cell as null with no
  flag; schema discovery marks a masked field `"masked": true`; `permissions.columns_restricted` is `true` when a
  column is masked as well as when one is hidden; pipeline authoring and the runner refuse a masked column as a hidden
  one (`403 column_not_entitled`). A TTL on a masked column, and any `ttl` change while the stored TTL is on one, is
  refused `403 column_not_entitled`. Rolling back to 0.23.3 hides masked columns again, so queries, saved queries and
  widgets over them fail with `unknown field` for those callers, except the columns only a rows-only list masked
  (B11), which 0.23.3 shows on every row again.
- **With `CLICKHOUSE_MIGRATIONS_ENABLED=false`, an ADAS that cannot reach ClickHouse at startup** no longer serves
  unverified: it stays not Ready and answers `503` until a background retry, every
  `CLICKHOUSE_REPLICAS_DISCOVERY_INTERVAL`, has checked the storage.

---

## 3. Before you deploy

Do these on the running 0.23.3 deployment. Apart from the Appendix A conversion, which needs ADAS stopped, none
of them changes how 0.23.3 behaves, so they can be done days ahead. In the commands below, `<db>` is the ADAS
ClickHouse database (`default` unless `CLICKHOUSE_DATABASE` is set) and `<writer>` is the user in
`CLICKHOUSE_WRITER_USER` (`default` when unset), not the read-only `CLICKHOUSE_USER`.

**Where each change goes.** In a Kubernetes deployment, configuration changes are made in the deployment's Helm
values (or the ClickHouse operator's `ClickHouseInstallation` resource), not inside a running server or pod. A change
made only inside ClickHouse or with `kubectl edit` is lost on the next redeploy.

| Step | Where |
|---|---|
| P1 checks | Read-only SQL on the ClickHouse server, nothing changes. Fixes go to the ClickHouse values. |
| P2 grants, A5 server settings | The ClickHouse values: the chart's users and server configuration, or the operator resource. |
| P3 host, P4 probes, P5 strategy | The ADAS values (for example the `dial-extension` chart release that deploys ADAS). |

### P1. Check ClickHouse: Keeper, macros, database engine

Run on the ClickHouse server, as an administrator:

```sql
SELECT engine FROM system.databases WHERE name = '<db>';                      -- 1
SELECT * FROM system.macros;                                                  -- 2
SELECT count() FROM system.zookeeper WHERE path = '/';                        -- 3
SELECT count() FROM system.clusters WHERE cluster = '<db>';                   -- 4
SELECT name FROM system.tables WHERE database = '<db>' AND name LIKE '\_adas\_replicate\_%';   -- 5
```

| Query | Expected | If not |
|---|---|---|
| 1 | `Replicated` | `Atomic`: convert the database by hand, see [Appendix A](#appendix-a-convert-an-atomic-database-to-replicated). This is the only manual data step. |
| 2 | rows for `shard` and `replica` | Add both macros to the ClickHouse server configuration in its values. The ClickHouse operator sets them by itself. |
| 3 | a number, no error | Keeper is not configured. Add it in the ClickHouse values: embedded in the server on a single node, or a three-member ensemble. |
| 4 | `1` | More than one replica is registered. Make sure only one of them holds ADAS data, and drop the others with `SYSTEM DROP DATABASE REPLICA`. Otherwise the conversion refuses to start, naming each table and replica. |
| 5 | no rows | A conversion was started before. It resumes on the next start, nothing to do. |

A deployment managed by the ClickHouse Kubernetes operator usually already passes all five.

The conversion moves data by hardlink and needs no extra disk space for the data itself. Keep the clocks of the
ClickHouse server and the ADAS pods in sync (NTP): the pipeline safety lag covers only a small skew between them.

### P2. Add four grants to the writer user

Add them next to the user's existing grants, wherever the user is defined. Keep the existing grants: the list is
replaced, not merged. A user defined in configuration **without** any grants list already holds every privilege
and needs nothing; adding a list of only these four would take everything else away.

**In the ClickHouse Helm values** (a chart that renders a `users.d` file, often under a key such as `users_xml`):

```xml
<adas_writer>   <!-- the writer user's name -->
  <grants>
    <!-- keep the existing grants -->
    <query>GRANT SYSTEM SYNC REPLICA ON <db>.*</query>
    <query>GRANT SYSTEM SYNC DATABASE REPLICA ON <db>.*</query>
    <query>GRANT SELECT ON system.clusters</query>
    <query>GRANT SYSTEM MERGES ON <db>.*</query>
  </grants>
</adas_writer>
```

**In a `ClickHouseInstallation`** (ClickHouse operator):

```yaml
spec:
  configuration:
    users:
      <writer>/grants/query:
        # existing grants, as they are now, for example:
        - GRANT SELECT, INSERT, ALTER, CREATE TABLE, DROP TABLE ON <db>.*
        - GRANT SELECT ON system.parts
        - GRANT SELECT ON system.data_skipping_indices
        # new in 0.24:
        - GRANT SYSTEM SYNC REPLICA ON <db>.*
        - GRANT SYSTEM SYNC DATABASE REPLICA ON <db>.*
        - GRANT SELECT ON system.clusters
        - GRANT SYSTEM MERGES ON <db>.*
```

**Only if the user was created with SQL** (`CREATE USER`), not in configuration, run instead:

```sql
GRANT SYSTEM SYNC REPLICA ON <db>.* TO <writer>;
GRANT SYSTEM SYNC DATABASE REPLICA ON <db>.* TO <writer>;
GRANT SELECT ON system.clusters TO <writer>;
GRANT SYSTEM MERGES ON <db>.* TO <writer>;
```

Roll out the ClickHouse change. It is safe on 0.23.3. The full grant list per surface is in
[`clickhouse-server.md`](clickhouse-server.md#writer-credential).

**Check:** `SHOW GRANTS FOR <writer>` on the ClickHouse server lists all four.

| Missing grant | What happens |
|---|---|
| `SYSTEM SYNC REPLICA`, `SYSTEM SYNC DATABASE REPLICA` | ADAS refuses to start and names the grant. Nothing is changed in ClickHouse. |
| `SELECT ON system.clusters` | ADAS starts, logs a WARN every discovery cycle and sends every query to the primary. |
| `SYSTEM MERGES` | The table conversion can give up on a table and fail startup. A restart retries. |

**Only if** the writer user's settings profile has `<constraints>` in the ClickHouse values, allow:

- `insert_keeper_max_retries` (value `8`) and `insert_keeper_retry_max_backoff_ms` (value `2000`). Without that,
  row writes and pipeline runs fail.
- `max_table_size_to_drop` (value `0`), if any ADAS table is larger than the server's limit (50 GB by default).
  Without that, the conversion cannot drop that table's old copy and startup fails.

A profile with no constraints needs nothing.

### P3. Point `CLICKHOUSE_HOST` at the ClickHouse pod

In the ADAS Helm values, set it to a name that reaches only the primary ClickHouse pod: the pod's own DNS name, or
a Service that selects only that pod (such as the operator's per-replica Service). Not a Service over all
ClickHouse pods. For a StatefulSet behind a headless Service the pod name looks like:

```yaml
- name: CLICKHOUSE_HOST
  value: <clickhouse-pod>.<headless-service>.<namespace>.svc.cluster.local
```

`SELECT host_name FROM system.clusters WHERE cluster = '<db>'` shows the name the server reports for itself.

**Check** from the ADAS pod; exactly one address must be printed:

```bash
kubectl exec deploy/<adas-deployment> -- getent hosts <the host name above>
```

One address is not proof on its own: a ClusterIP Service over several pods also prints one. Make sure the name
belongs to a single pod.

This change is safe on 0.23.3 and can be rolled out ahead of the upgrade.

### P4. Split the ADAS health probes

While the conversion runs, the general `/actuator/health` reports DOWN. A startup or liveness probe on it kills the
pod in the middle of the conversion. In the ADAS Helm values, use the dedicated endpoints, and keep the existing
delays and thresholds:

```yaml
startupProbe:
  httpGet: { path: /actuator/health/liveness, port: http }
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: http }
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: http }
```

When `MANAGEMENT_SERVER_PORT` is set, use that port instead of `http`. Safe on 0.23.3, and the right setup long
term.

### P5. Set the ADAS deploy strategy to `Recreate`

A rolling update keeps the old pod writing while the new one converts tables. In the ADAS Helm values, set
`strategy.type: Recreate` for this deploy, or plan to scale ADAS to 0 by hand in deploy step D2.

### Do not change the ClickHouse server settings yet

0.24 recommends three server settings (step A5), but ADAS starts without them, and the first one makes 0.23.3 fail
to create tables. Add them after the upgrade.

### Optional configuration

Five new ADAS keys tune replica routing (`CLICKHOUSE_ROUTING_QUERIES`, `CLICKHOUSE_ROUTING_PIPELINES`,
`CLICKHOUSE_REPLICAS_DISCOVERY_INTERVAL`, `CLICKHOUSE_REPLICAS_SYNC_TIMEOUT`, `CLICKHOUSE_DDL_TASK_TIMEOUT`), see
[`configuration.md`](configuration.md#clickhouse-replicas-and-routing). The defaults fit a single ClickHouse
server; leave them unset. The runner has no required configuration changes.

---

## 4. Deploy

Expect an outage of ingest, the analytics API and the Admin UI analytics pages for as long as the conversion takes.
The data moves by hardlink, so this is usually short. Ingest catches up on its own afterwards: the poll re-scans the
last `POLL_RESTART_LOOKBACK` (1 hour by default) on start, and blobs older than that are picked up by the next
daily run (`SYNC_CRON`). No usage data is lost.

**D1. Scale the Enrichment Runner to 0** and wait until its pod is gone.

**D2. Stop ADAS 0.23.3.** With `Recreate` this happens by itself in D3. Otherwise scale ADAS to 0 and wait until
every pod is gone. No old ADAS pod may run while 0.24 starts.

**D3. Deploy ADAS 0.24** with the P3–P5 changes and follow its log:

```bash
kubectl logs -f deploy/<adas-deployment> | grep -iE "convert|replicat|ERROR"
```

You should see a pair of lines per table:

```
Converting plain table '<table>' (<engine>) to its replicated form
Converted '<table>' to its replicated form: <N> rows in <M> ms
```

The pod turns Ready once every table is converted. If it does not, see [Troubleshooting](#6-troubleshooting).
`omitting pipeline '…' from the enrich feed` ERRORs are not part of the conversion; handle them in step A3.

**D4. Deploy Enrichment Runner 0.24 and scale it back** to its previous replica count, once ADAS is Ready. Its pod
becomes Ready only after it has synced pipelines from ADAS, so a Ready runner confirms both sides match.

**D5. Deploy Admin UI 0.24.**

---

## 5. After the deploy

### A1. Check the ClickHouse tables

```sql
SELECT engine, count() FROM system.tables WHERE database = '<db>' GROUP BY engine;   -- only Replicated… engines
SELECT name FROM system.tables WHERE database = '<db>' AND name LIKE '\_adas\_replicate\_%';  -- no rows
```

A materialized view's own storage table (`.inner…`) stays plain; ADAS creates none.

### A2. Check the service in the Admin UI

- **Table 'dial_usage_log'** contains logs from the last few minutes: ingest is running.
- **Pipelines**: the last runs of your scheduled pipelines are successful, and a manual refresh of one succeeds.
- **Tables → Ingest failures** does not grow, and the pipelines' **Failures** do not grow.
- **Dashboards** and **Queries** open, and their charts render.

In the ADAS log there is no WARN about `system.clusters`.

### A3. Find enrichment pipelines that never ran

Search the ADAS log for:

```
omitting pipeline '<name>' from the enrich feed
```

Each name is an enabled pipeline that the runner never receives: its declaration is incomplete, or its outputs do
not compose to a response schema against its target columns. 0.23.3 logged the same ERROR, so you can also run
this search before the upgrade. For each one, open it in **Pipelines**, run **Preview** — it names the output and
column at fault — and fix the declaration, or disable the pipeline. ADAS 0.24 refuses any change to such a
pipeline while it is enabled until the change fixes it, and a catalog import preview reports it as not armable.

### A4. Update `usage-client-identity-live` (only if you imported the recommended catalog)

Skip this if the pipeline does not exist, or if your DIAL CLI does not send the `X-DIAL-CLI-Harness` header.
Re-importing the catalog does not update an existing pipeline, so this is done by hand:

1. **Pipelines → `usage-client-identity-live`**, edit the outputs `client_type` and `dial_cli_harness_name`. Copy
   both expressions from the pipeline of the same name in
   [`recommended-catalog/adas-catalog.json`](recommended-catalog/adas-catalog.json). Save.
2. **Tables → `usage_client_identity`**, update the descriptions of the columns `client_type`, `dial_cli_version`
   and `dial_cli_harness_name` from the same file. Descriptions only; no data changes.
3. Optionally, **Pipelines → `usage-client-identity-live` → Backfill**, over the period since the DIAL CLI started
   sending the header. Rows older than the payload retention keep an empty harness.

After a backfill, `sessions_rollup`, which reads this table, may show **Rebuild needed** on its pipeline page.

### A5. ClickHouse server settings

After some time of stable work, add three settings to the ClickHouse values and roll them out:

| Setting | Where | Value |
|---|---|---|
| `database_replicated_allow_only_replicated_engine` | default profile (`users.d`) | `1` |
| `execute_merges_on_single_replica_time_threshold` | `<merge_tree>` (`config.d`) | `1800` |
| `wait_for_unique_parts_send_before_shutdown_ms` | `<merge_tree>` (`config.d`) | `60000` |

In a chart that renders configuration files:

```xml
<!-- users.d -->
<clickhouse><profiles><default>
  <database_replicated_allow_only_replicated_engine>1</database_replicated_allow_only_replicated_engine>
</default></profiles></clickhouse>

<!-- config.d -->
<clickhouse><merge_tree>
  <execute_merges_on_single_replica_time_threshold>1800</execute_merges_on_single_replica_time_threshold>
  <wait_for_unique_parts_send_before_shutdown_ms>60000</wait_for_unique_parts_send_before_shutdown_ms>
</merge_tree></clickhouse>
```

In a `ClickHouseInstallation`:

```yaml
spec:
  configuration:
    profiles:
      default/database_replicated_allow_only_replicated_engine: 1
    settings:
      merge_tree/execute_merges_on_single_replica_time_threshold: 1800
      merge_tree/wait_for_unique_parts_send_before_shutdown_ms: 60000
```

The last two matter once a second replica is added. The reasons are in
[`clickhouse-replication.md`](clickhouse-replication.md#clickhouse-requirements).

### A6. Optional

- **Deploy strategy.** You may switch ADAS back to `RollingUpdate`. The conversion runs only once, and later starts
  take a lock so two pods never set up storage at the same time.
- **Before adding a second ClickHouse replica**, keep `CLICKHOUSE_HOST` on the primary pod (P3), make sure both ADAS
  users (`CLICKHOUSE_USER` and `CLICKHOUSE_WRITER_USER`) and their grants exist on the new replica too, and that the
  `host_name` each replica reports resolves from the ADAS pod: ADAS reaches the other replicas by that name.

---

## 6. Troubleshooting

| Symptom | Cause | Action |
|---|---|---|
| Startup fails naming a database engine `Atomic` | P1 | Follow [Appendix A](#appendix-a-convert-an-atomic-database-to-replicated), then redeploy. Nothing was changed. |
| Startup fails: database `<db>` does not exist | The database was never created | Create it with the `Replicated` engine, as in Appendix A step 1 but under its final name. |
| Startup fails naming a `SYSTEM SYNC` grant | P2 not applied | Add the grants and restart. Nothing was changed in ClickHouse. |
| Startup fails: `clickhouse.host … resolves to several addresses`, or `is answered by different replicas` | P3 not applied | Use the pod host name from P3. |
| Startup fails: `Refusing to convert plain tables …` naming replicas | P1 query 4 | Drop the stale replicas with `SYSTEM DROP DATABASE REPLICA`, or empty their copies, then restart. Nothing was changed. |
| The pod restarts during the conversion | P4 not applied | Split the probes. The next start resumes the conversion. |
| Startup fails naming `_adas_replicate_<table>` | The conversion was interrupted | Restart. It resumes and finishes. |
| Startup fails: a row count differed twice for a table | Something wrote during the conversion, or `SYSTEM MERGES` is missing | Make sure only one ADAS pod runs, check P2, restart. |
| Startup fails dropping a twin over `max_table_size_to_drop` | A profile constraint, see P2 | Allow the setting, restart. |
| ERROR `Could not restart merges on '<table>'` | ClickHouse refused to restart merges | Run `SYSTEM START MERGES <db>.<table>` on ClickHouse, then restart ADAS. |
| Startup fails listing plain tables | `CLICKHOUSE_MIGRATIONS_ENABLED=false` | Enable migrations for the first start, so the tables are converted. |
| Runner stays not Ready | The runner or ADAS is not 0.24 | Check both image versions. |
| Charts do not render | The Admin UI is older than ADAS 0.24 | Deploy Admin UI 0.24 (D5). |

---

## Appendix A. Convert an `Atomic` database to `Replicated`

Only when step P1 query 1 returned `Atomic`. ADAS never changes a database engine itself. Do this with **ADAS
stopped**, on the ClickHouse server, after Keeper and the macros are in place. Either do it in its own short window
and start 0.23.3 again afterwards (it runs on a `Replicated` database), or do it in the deploy window between D2
and D3.

1. Create a `Replicated` database beside the current one:

   ```sql
   CREATE DATABASE <db>_replicated ENGINE = Replicated('/clickhouse/databases/<db>', '{shard}', '{replica}');
   ```

2. Copy every table into it. The data is attached by hardlink, not copied. This loop creates each table's
   replicated twin, attaches its data and prints both row counts, which must match:

   ```bash
   q() { clickhouse-client -q "$1"; }
   for t in $(q "SELECT name FROM system.tables WHERE database = '<db>' AND engine LIKE '%MergeTree' AND engine NOT LIKE 'Replicated%'"); do
     ddl=$(q "SELECT create_table_query FROM system.tables WHERE database = '<db>' AND name = '$t' FORMAT TSVRaw" \
       | sed -E "s/^CREATE TABLE <db>\./CREATE TABLE <db>_replicated./; s/ENGINE = ([A-Za-z]*)MergeTree/ENGINE = Replicated\1MergeTree/; \
                 s/ SETTINGS / SETTINGS replicated_deduplication_window = 0, replicated_deduplication_window_for_async_inserts = 0, /")
     case "$ddl" in *SETTINGS*) ;; *) ddl="$ddl SETTINGS replicated_deduplication_window = 0, replicated_deduplication_window_for_async_inserts = 0";; esac
     q "$ddl"
     q "ALTER TABLE <db>_replicated.\`$t\` ATTACH PARTITION ALL FROM <db>.\`$t\`"
     echo "$t: $(q "SELECT count() FROM <db>.\`$t\`") -> $(q "SELECT count() FROM <db>_replicated.\`$t\`")"
   done
   ```

   Then check that nothing was left behind; this must return no rows:

   ```sql
   SELECT name, engine FROM system.tables WHERE database = '<db>'
     AND name NOT IN (SELECT name FROM system.tables WHERE database = '<db>_replicated');
   ```

3. Swap the names:

   ```sql
   RENAME DATABASE <db> TO <db>_atomic;
   RENAME DATABASE <db>_replicated TO <db>;
   ```

4. Continue with step P2, or with D3 if you are in the deploy window. Drop `<db>_atomic` once the upgrade is
   verified.

Background on replicated storage: [`clickhouse-replication.md`](clickhouse-replication.md). Exact behaviour of each
change: the specs under [`openspec/specs/`](../openspec/specs/).
