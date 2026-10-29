# ClickHouse server requirements

What the ClickHouse server behind ADAS must provide, and what the two ADAS credentials must be
granted. This is server-side and grant-side setup — for the ADAS variables that point at the server,
see [`configuration.md`](configuration.md#clickhouse-connection).

## Supported versions

**ClickHouse 25.3 LTS or newer**, verified through 26.8 (the test suite runs 26.8). ADAS performs no
version check: it fails at the statement that needs a missing feature, not at a version comparison.

The one exception is the AI query functions (`aiGenerate`, `aiClassify`, …): they render onto
ClickHouse's native AI functions, which exist from **26.8**. Every other ADAS surface keeps the 25.3
floor.

The `JSON` type used by the payload table's `jwt_claims` / `request_tags` is generally available
across that whole range and needs no experimental setting.

### One server message is load-bearing

ADAS reads the text of one ClickHouse error. `MEMORY_LIMIT_EXCEEDED` (code 241) is reported both when a
query exceeds the ceiling ADAS set for it and when the **server** runs out of memory under other load,
and the two need opposite answers: the first is deterministic and refused (`422`), the second is
transient and reported as an outage (`503`) so the caller retries. The code cannot tell them apart, so
ADAS matches the server's own description of the limit — `Query memory limit exceeded` for its own
ceiling, as opposed to `Total` or `User`. (`Query …` and `User …` were observed on 26.3.21.7, and
`Query …` again on 26.8.15.10; `Total …` follows the same pattern but could not be provoked without
starving the server, so it is inferred.)

A per-account setting profile or quota can also impose `max_memory_usage`, which renders identically to
the ceiling ADAS sets. That refusal stays a `422` — it is still deterministic — but the value named in
the message is ADAS's ceiling rather than the one actually enforced.

The match is **anchored** immediately after `DB::Exception: `, not searched for anywhere in the message,
so a statement that merely quotes the wording cannot change the outcome.

**Verified against 26.3.21.7 and 26.8.15.10 only — this is a deployment precondition, not just an upgrade
note.** The supported floor above is 25.3 LTS, and 25.3 was never checked. If a server words the per-query form
differently, `422 query_budget_exceeded` and `422 pipeline_read_budget_exceeded` become unreachable and
every budget refusal arrives as a retryable `503`, so a caller retries a statement that cannot succeed.
The suite pins the wording via `ClickHouseMemoryLimitMessageIntegrationTest`, but only for the image the
tests happen to run — which does not track the deployed server.

**Check it once per server version**, before or just after deploying against one that has not been
checked:

```sql
-- Expect: Code: 241 … DB::Exception: Query memory limit exceeded: …
SELECT number, count() FROM numbers(100000) GROUP BY number SETTINGS max_memory_usage = 1024;
```

The description must read `Query memory limit exceeded` directly after `DB::Exception: `. A node-wide or
per-account breach reads `Total …` / `User …` there instead, and is correctly reported as `503`.

## Encryption key for the body columns

The `dial_usage_log_payload` DDL applies the **AES-256-GCM-SIV** codec to its three body columns;
`dial_usage_log`'s own DDL declares them the same way (V1/V7, before V11 drops them in favour of the
payload table), and `rate_analytics.comment` uses the same codec. All need an `encryption_codecs` key
in `config.d`, or `CREATE TABLE` fails:

```xml
<clickhouse>
    <encryption_codecs>
        <aes_256_gcm_siv>
            <key_hex>...</key_hex>
        </aes_256_gcm_siv>
    </encryption_codecs>
</clickhouse>
```

The Testcontainers tests and `local_env/docker-compose.yml` mount a throwaway key. Real deployments
must supply one from their config/secret store — and must keep it, since the columns are unreadable
without it.

## Upgrading past 25.10 is one-way for the data

The on-disk part format changed inside the 25.x line and 26.3 inherits it, so **a part written by a
newer server cannot be read by an older one**. Floors below are taken from `system.settings_changes`
on 26.3.21.7, not from release notes:

| MergeTree setting                          | Default on 26.3    | Changed in | Oldest server that can read it |
|--------------------------------------------|--------------------|------------|--------------------------------|
| `string_serialization_version`             | `with_size_stream` | 25.11      | 25.10                          |
| `serialization_info_version`               | `with_types`       | 25.11      | 25.10                          |
| `object_serialization_version`             | `v3`               | 25.12      | 25.12                          |
| `object_shared_data_serialization_version` | `advanced`         | 25.12      | 25.12                          |

Every `String` column is affected, so this bites from the first part written — an older server
reports `serialization_info_version` as `Unknown version of serialization infos`. The `JSON` columns
need 25.12. Staging through an intermediate LTS does not soften it: the whole change lands in the hop
that crosses 25.10–25.12.

**To keep rollback available**, set `<compatibility>` in the **default profile** (`users.d`) naming
the version to roll back *to*, **before** starting the newer server:

```xml
<clickhouse>
    <profiles>
        <default>
            <compatibility>25.8</compatibility>
        </default>
    </profiles>
</clickhouse>
```

Verified on 26.3.21.7 to restore the pre-25.10 part layout. These are MergeTree settings, so a
per-query `SETTINGS compatibility=…` does not reach them, and setting the profile afterwards does not
retrofit parts already written.

`compatibility` is not scoped to the part format — it reverts **every** default changed after the
version it names:

- At `25.8` that includes `defer_partition_pruning_after_final` → `0`, re-arming the `FINAL`
  partition-pruning regression 26.3's default exists to gate. Harmless for `dial_usage_log` and
  `rate_analytics`, whose partition column is in the sort key, and since ADAS sends
  `do_not_merge_across_partitions_select_final = 1` for every partition-local entity (see the
  `structured-query` spec's read-deduplication requirement) it no longer decides anything for
  `dial_usage_log_payload` either: with that setting the 26.3 default and the `25.8` compatibility value
  prune the same way. Only a table partitioned outside its ordering key that ADAS cannot vouch for still
  loses partition pruning under `final = 1` at the 26.3 default.
- Below 25.6 it also reverts `use_skip_indexes_if_final` → `0`, which makes every skip index inert
  under `final = 1`.

Take a backup regardless.

### 26.8 adds a softer door: packed skip indexes

No serialization-format default moved between 26.3 and 26.8 (`system.settings_changes` on 26.8.15.10
lists none for 26.4–26.8), so a part written by 26.8 is still readable by 26.3. What 26.8 changes is how
a part stores its skip indexes: `packed_skip_index_max_bytes` went `0` → `1 MiB`, and every index small
enough — all of ADAS's `bloom_filter` and `minmax` indexes — is written into one `skp_idx.packed` file
instead of a `skp_idx_<name>.idx` + mark file per index. 26.3 reads such a part but does not know the
packed file, so on a rollback the indexes are **silently inert** on every 26.8-written part until a merge
rewrites it: a lookup by `trace_id` or `chat_id` reads every granule of those parts instead of the few
the bloom filter would have left. Nothing fails and nothing is lost; reads are slower until the parts
have been re-merged by the older server.

To keep the classic layout while on 26.8, either name 26.3 in the `compatibility` profile shown above
(on 26.8.15.10 that reverts `packed_skip_index_max_bytes` to `0`, verified at the part-file level) or set
only that one default in the server's `config.d`:

```xml
<clickhouse>
    <merge_tree>
        <packed_skip_index_max_bytes>0</packed_skip_index_max_bytes>
    </merge_tree>
</clickhouse>
```

Prefer the server-level setting over `ALTER TABLE … MODIFY SETTING`: the setting is unknown to 26.3, so
a table that carries it in its own definition cannot be loaded by the older server at all.

`compatibility = 26.3` on 26.8 also reverts — measured on 26.8.15.10 — the auto-statistics default back
to off (26.8 writes a `statistics.packed` file into every part otherwise), the lenient
string-to-`DateTime` parsing 26.5 made the default (`cast_string_to_date_time_mode`,
`date_time_input_format` → `basic`), and `analyzer_compatibility_apply_final_to_all_joined_tables` → `1`,
which makes a `FINAL` keyword on a `FROM` table deduplicate the tables joined after it again (see the
`sql-query-translation` spec's FINAL requirement; ADAS's own rendering does not depend on either value).

## Row inserts are pinned synchronous

ClickHouse defaults `async_insert` to `1` from **26.2**. ADAS pins it to `0` on the two clients that
insert rows, because the async buffer evaluates a column `DEFAULT` once per *flush* rather than once
per `INSERT` — and both deduplication version columns (`dial_usage_log._ingested_at` and a catalog
table's `_updated_at`) are exactly such a default.

Measured on 26.3.21.7: eight concurrent inserts of one key coalesced into one flush, shared a single
`now64(3)` stamp, and `ReplacingMergeTree` kept an arbitrary row of the eight. With the pin each
request is stamped on its own, so a later write collapses over an earlier one at the version column's
millisecond resolution — two writes of the same key inside one millisecond still tie, and a tie
leaves the surviving row unspecified.

Pinning `0` is a no-op on any server older than 26.2, so ingest semantics stay identical across the
supported range; 26.8.15.10 still defaults `async_insert` to `1`, so the pin still does work there.
`INSERT … SELECT` (the Class-1 enrichment path) and DDL are never routed through the buffer and need no
pin. The Flyway path is insulated by the JDBC driver, which pins the same setting
itself — **do not bump `clickhouse-java` alongside the server** (see the note in `build.gradle`).

`deduplicate_insert` also defaults to `enable` from 26.2 but does not reach ADAS: for a
non-replicated table, insert deduplication is gated by `non_replicated_deduplication_window`, still
`0` on 26.8.

## Settled ingest days are force-merged

`V12__force_merge_settled_partitions` sets, on `dial_usage_log_payload` and on `dial_usage_log`:

```sql
ALTER TABLE dial_usage_log_payload MODIFY SETTING
    min_age_to_force_merge_seconds = 172800, min_age_to_force_merge_on_partition_only = 1,
    vertical_merge_algorithm_min_columns_to_activate = 1, vertical_merge_algorithm_min_rows_to_activate = 1;
ALTER TABLE dial_usage_log MODIFY SETTING
    min_age_to_force_merge_seconds = 172800, min_age_to_force_merge_on_partition_only = 1;
```

`V14__force_merge_settled_rate_analytics` sets the same two force-merge settings on `rate_analytics`, the third
ingest target, which V12 left out. Its days hold a few dozen rows, so the regular merge selector never combines
two equal parts, and the days written twice before the ingest ledger kept their duplicates. Applying it costs
nothing measurable: its partitions are tiny.

The two `vertical_merge_algorithm_*` settings are the payload table's only. ClickHouse chooses the merge
algorithm by column count, and the payload table's 8 non-key columns fall short of the default 11, so
without them every merge of its heavy rows is horizontal: every column of every source part is read at
once. Vertical merges stream one column at a time. On a day partition of several parts with realistic row
widths that cut the merge's peak memory to roughly a third on 26.3, and made it faster. Check
it with `SELECT merge_algorithm, peak_memory_usage FROM system.part_log WHERE table =
'dial_usage_log_payload' AND event_type = 'MergeParts'`.

Some ingested rows are still written twice — a blob written again after a crash between its rows and
its ingest-ledger entry, a backfill with `includeIngested`, and every day ingested before the ledger
existed — and the automatic merge selector leaves a finished day as a few similar-sized parts, so the
stale versions otherwise stay on disk until the `TTL` (by default 90 days for the payload table, one year for `dial_usage_log`).
With the settings, once **every** part of a day partition is older than 2 days ClickHouse merges that
partition into one part, one row per key. Reads are unaffected either way — they deduplicate with
`final = 1` — so this is about disk and `FINAL` work. The why and the exact behaviour are in the
`data-sync` spec.

**It applies to parts that already exist.** On a populated table, every partition whose parts are all
older than 2 days becomes a merge candidate as soon as the migration runs, so the first startup on such
a deployment starts a burst of merges on the shared background pool. `dial_usage_log` partitions are
small (on the order of MiB a day); the payload table's reach tens of GiB, so it is where the cost is. Each merge:

- holds about **2× the partition's size** on disk while it runs (the new part is written before the old
  ones are released), so the volume needs free space of roughly the partition being merged — more when
  several run in parallel;
- is bounded by the server's merge memory share; a merge that exceeds it fails and is retried.

Watch `system.merges`, `system.part_log` and free disk for the first hour.

**To stage it** on a deployment with little disk or merge-memory headroom: the migration applies 2 days
at startup, so run with `CLICKHOUSE_MIGRATIONS_ENABLED=false`, apply a longer interval to the payload
table by hand (with `min_age_to_force_merge_on_partition_only = 1`), and lower it step by step to `172800`:

```sql
ALTER TABLE dial_usage_log_payload MODIFY SETTING
    min_age_to_force_merge_seconds = 2592000, min_age_to_force_merge_on_partition_only = 1; -- 30 days
```

Keep migrations disabled until the payload table's hand-applied interval has reached `172800`: Flyway sees
`V12` as pending and applies its 2 days at once when they are turned back on, which is the burst the staging
avoids. Once the payload table already carries `172800`, re-enabling migrations lets Flyway record `V12`
(it also applies the setting to the small `dial_usage_log`). While migrations are disabled, every later
migration has to be applied out of band too.

**To remove it** (merges return to the automatic selector; rows already collapsed stay collapsed):

```sql
ALTER TABLE dial_usage_log_payload RESET SETTING
    min_age_to_force_merge_seconds, min_age_to_force_merge_on_partition_only;
ALTER TABLE dial_usage_log RESET SETTING
    min_age_to_force_merge_seconds, min_age_to_force_merge_on_partition_only;
ALTER TABLE rate_analytics RESET SETTING
    min_age_to_force_merge_seconds, min_age_to_force_merge_on_partition_only;
```

The vertical-merge thresholds are independent of the force merge and removed on their own, returning the
payload table to horizontal merges:

```sql
ALTER TABLE dial_usage_log_payload RESET SETTING
    vertical_merge_algorithm_min_columns_to_activate, vertical_merge_algorithm_min_rows_to_activate;
```

The interval is not an ADAS configuration property — it follows the tables' partitioning and the
writers' cadence, like the retention. A backfill of an older day reopens that partition, which is then
merged again 2 days after its last write.

Verified on 26.3.27.3 by hand and on the `clickhouse-server:26.8` image the suite pins (including
`RESET SETTING`). Both are long-standing MergeTree settings, but the 25.3 floor is unverified.

## Credentials and grants

ADAS uses two credentials against the same host/port/database. The split is load-bearing: every read
goes through the read-only client, every write and every runtime DDL through the writer.

`<database>` below is the value of `CLICKHOUSE_DATABASE`, or `default` when that variable is unset.

### Read credential

Set by `CLICKHOUSE_USER` / `CLICKHOUSE_PASSWORD`.

Backs `/v1/queries`, Flight SQL, and the scan / group-rows / preview / window-probe reads under
`/v1/pipelines`. It needs no DDL and no write grants:

```sql
GRANT SELECT ON <database>.* TO <reader>
```

### Writer credential

Set by `CLICKHOUSE_WRITER_USER` / `CLICKHOUSE_WRITER_PASSWORD`.

Used by `data-sync` ingest, the startup schema migrations, the table catalog, and the Class-1 and
aggregate `INSERT … SELECT` statements. Which grants it needs depends on which surfaces are in use:

| Surface                                                   | Statements issued                                                                                                                                                    | Grant                                                     |
|-----------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------|
| `data-sync` ingest                                        | `INSERT INTO`, `DESCRIBE TABLE` (startup schema validation, and the payload table's request-delta columns on the first flush), `SELECT` / `INSERT INTO` on the ingest ledger `_dial_internal_ingested_blob`, and `SELECT` on `dial_usage_log_payload` (the request-delta parent lookup) | `INSERT`, `SELECT`                                        |
| Startup migrations (`CLICKHOUSE_MIGRATIONS_ENABLED=true`) | `CREATE TABLE`, `ALTER TABLE … ADD COLUMN` / `MODIFY COLUMN` / `MATERIALIZE COLUMN` / `ADD INDEX` / `MATERIALIZE INDEX` / `MODIFY SETTING`, usage-log and payload `MODIFY TTL` / `REMOVE TTL`, Flyway history r/w | `CREATE TABLE`, `INSERT`, `SELECT`, `ALTER`               |
| Table catalog create (`POST /v1/tables`)                  | `CREATE TABLE`, best-effort `DROP TABLE IF EXISTS` after a failed create                                                                                             | `CREATE TABLE`, `DROP TABLE`                              |
| Table catalog schema evolution (`PATCH …/schema`)         | `ALTER TABLE … ADD/DROP/RENAME COLUMN`                                                                                                                               | `ALTER`                                                   |
| Table catalog delete (`DELETE /v1/tables/{name}`)         | `DROP TABLE`                                                                                                                                                         | `DROP TABLE`                                              |
| Class-1 materialize and aggregate refresh                 | `INSERT … SELECT` reading the input source **and** every enrichment table the declaration references                                                                 | `INSERT`, `SELECT` on both                                |
| Enrichment relayout (once per table, after startup)       | `CREATE TABLE`, `INSERT … SELECT`, `EXCHANGE TABLES`, `ALTER TABLE … DROP PARTITION` / `MODIFY TTL` / `MODIFY SETTING` / `RESET SETTING` / `ADD INDEX`, `DROP TABLE` | `CREATE TABLE`, `INSERT`, `SELECT`, `ALTER`, `DROP TABLE` |

For the full functional surface:

```sql
GRANT SELECT, INSERT, CREATE TABLE, ALTER, DROP TABLE ON <database>.* TO <writer>
GRANT SELECT ON system.parts TO <writer>
GRANT SELECT ON system.data_skipping_indices TO <writer>
```

The two system-table grants are not covered by `<database>.*`: without them the relayout fails with
`ACCESS_DENIED … SELECT ON system.parts` before its exchange and retries on every start, a lookup index is
never added (one WARN), and a schema `PATCH` that drops a column fails. Both tables hold part and index
metadata only, no row data. Grant them by name, never `system.*`, which would expose `system.query_log` and
`system.users`.

Trim it to fit the deployment: a sync-and-migrate-only deployment that never uses `/v1/tables`
PATCH/DELETE needs `SELECT, INSERT, CREATE TABLE, ALTER`. `ALTER` cannot be dropped while
`CLICKHOUSE_MIGRATIONS_ENABLED=true`, because the migrations evolve the ingest tables in place —
including the `DROP COLUMN` that V11 issues against `dial_usage_log`.

A deployment that grants per table rather than on `<database>.*` adds `SELECT, INSERT` on
`<database>._dial_internal_ingested_blob` (ClickHouse migration V13): every ingest path reads and writes
it, and startup schema validation describes it. The `_dial_internal_` prefix is reserved for tables the
service owns; `POST /v1/tables` refuses it.

### Narrowing `ALTER` to sub-privileges

Where `ALTER` sub-privileges are granted individually rather than wholesale, the migrations need
exactly seven: `ALTER ADD COLUMN`, `ALTER MODIFY COLUMN`, `ALTER MATERIALIZE COLUMN`,
`ALTER ADD INDEX`, `ALTER MATERIALIZE INDEX`, and `ALTER SETTINGS` (`MODIFY SETTING` is one of its
aliases, used by `V9__ttl_only_drop_parts` on `dial_usage_log` and `rate_analytics`, and by
`V12__force_merge_settled_partitions` on `dial_usage_log_payload` and `dial_usage_log` and by
`V14__force_merge_settled_rate_analytics` on `rate_analytics`), plus
`ALTER DROP COLUMN` for `V11__drop_dial_usage_log_payload_columns`. Each may be narrowed to the three ingest tables instead of
`<database>.*`. The retention reconciliation after the migrations needs an eighth, `ALTER TTL` (alias
`ALTER MODIFY TTL`), on `dial_usage_log` and `dial_usage_log_payload`, but only on a start where
`SYNC_USAGE_LOG_TTL_DAYS` or `SYNC_PAYLOAD_TTL_DAYS` is set and differs from the live `TTL`; it covers both `MODIFY TTL`,
including the materialization that follows, and `REMOVE TTL` (verified against 26.8.34.13). The `dial_usage_log_payload` line below is **required**, not optional: V12 is the first
migration to `ALTER` that table, so without it startup fails at V12:

```sql
GRANT ALTER SETTINGS ON <database>.dial_usage_log TO <writer>
GRANT ALTER SETTINGS ON <database>.dial_usage_log_payload TO <writer>
GRANT ALTER SETTINGS ON <database>.rate_analytics TO <writer>
```

`ALTER SETTINGS` is a table-level MergeTree privilege, not a server- or user-settings one: it permits
`ALTER TABLE … MODIFY SETTING` / `RESET SETTING` on the granted tables and nothing else. Verified
against 25.3.14.14 and re-verified against 26.3.21.7 and 26.8.15.10 — a credential holding it alone
cannot delete or rewrite rows, drop or move partitions, change the schema, change the `TTL` expression, `TRUNCATE`,
`DROP TABLE`, grant anything, or touch settings profiles, users, or `SYSTEM` commands. Each is
refused as a separate missing grant, and the grant does not extend to a table it was not issued for.
What it does reach is the other MergeTree settings of those tables, whose worst case is
availability of the writer's own ingest (setting `parts_to_throw_insert` low would block its own
inserts) rather than exposure or loss of data.

A deployment that will not grant it at all has two supported routes, both leaving the rest of the
writer grant unchanged:

- Set the server-wide default in `config.d`
  (`<merge_tree><ttl_only_drop_parts>1</ttl_only_drop_parts></merge_tree>`, and likewise
  `min_age_to_force_merge_seconds` / `min_age_to_force_merge_on_partition_only` and the two
  `vertical_merge_algorithm_*` thresholds for V12). Needs no SQL
  privilege, but applies to every MergeTree table on the server and does not show up in
  `SHOW CREATE TABLE`.
- Run with `CLICKHOUSE_MIGRATIONS_ENABLED=false` and apply the migration DDL out of band with an
  administrative credential. The DDL lives in `src/main/resources/db/migration/clickhouse`.
