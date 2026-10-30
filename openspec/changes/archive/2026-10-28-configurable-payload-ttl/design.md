## Context

V10 creates `dial_usage_log_payload` with `TTL toDateTime(request_time) + INTERVAL 90 DAY` and
`ttl_only_drop_parts = 1`, partitioned by `toYYYYMMDD(request_time)`. Applied Flyway migrations are frozen,
so a configurable retention cannot live in the migration; it has to be applied to the live table after
migrations run.

Pieces already in place:

- `ClickHouseMigrationRunner` (`@Order(0)`, gated on `clickhouse.migrations.enabled`) runs Flyway on the
  writer credential before any other startup runner.
- `ClickHouseTableInspector.engineFull(table)` reads `system.tables.engine_full`, which renders the TTL in
  one canonical spelling (`TTL toDateTime(request_time) + toIntervalDay(90)`).
- `InheritedTtl.parse(engineFull, column)` already parses that single-rule shape into a
  `TableDdlRenderer.TtlDdl`.
- The payload table name is only known as `sync.targets[1].table` (`SYNC_PAYLOAD_TABLE`); the target list
  carries no role, so code cannot tell which entry is the payload table.

## Goals / Non-Goals

**Goals:**

- One env property sets the payload retention; startup makes the live table match it.
- Changing the retention never rewrites the payload table's data parts.

**Non-Goals:**

- Retention for `dial_usage_log`, `rate_analytics` or user tables.
- Re-aligning the TTL of enrichments that inherited the payload TTL when they were created
  (`InheritedTtl`); they keep the TTL they were created with.
- A confirmation switch for shortening. Validation plus a WARN log is the guard: a confirmation switch would be a second setting, set once and left on.

## Decisions

### D1. Property shape: `sync.payload.ttl-days`, `-1` for none

A small `PayloadRetentionProperties` record (`@ConfigurationProperties(prefix = "sync.payload")`, fields
`ttlDays` and `table`), bound from `SYNC_PAYLOAD_TTL_DAYS:90`. It is its own record rather than two more
components on `SyncProperties`, because that record is built positionally in many tests and only the
reconciler reads these values. `SyncProperties` ignores the unknown `payload` key. The compact constructor
rejects `0` and anything below `-1` with an `IllegalArgumentException` naming `sync.payload.ttl-days` (the
same pattern as `inline-files-max-size`); a non-integer fails Spring's binding, which also names the
property.

`-1` rather than `0` for "no TTL": on a setting that deletes data, `0` reads as "keep zero days", so it is
rejected instead of being given a meaning. The OTel sink's `0`-means-none convention is not copied.

*Alternatives:* a separate boolean `SYNC_PAYLOAD_TTL_ENABLED` (two knobs that can disagree); a
`Duration` (`30d`), which invites sub-day values the daily partitioning cannot honour.

### D2. Naming the payload table: a dedicated key on the same env var

`sync.payload.table: ${SYNC_PAYLOAD_TABLE:dial_usage_log_payload}` sits next to the existing
`sync.targets[1].table`, which keeps reading the same variable. A deployment that renames the payload
table through `SYNC_PAYLOAD_TABLE` therefore reconciles the renamed table with no extra setting.

*Alternative:* inferring the table from the target list by position or mapping-file name — fragile,
since an external YAML can reorder or replace the list.

### D3. Where it runs: inside the migration runner, after Flyway

A `PayloadTtlReconciler` component with one method, called by `ClickHouseMigrationRunner.run` right after
`flyway.migrate()`. It inherits the runner's gate, so disabled migrations mean no reconciliation, and it
runs before `SchemaValidator` (`@Order(1)`) and everything else. It uses the writer client through
`ClickHouseTableInspector` and `ClickHouseDdlExecutor`, not a JDBC connection.

*Alternative:* a separate `ApplicationRunner`. Two runners at `@Order(0)` have no defined order between
them, and a new `@Order` slot would need the gate repeated.

### D4. Comparing the live TTL with the configured one

1. Read `engine_full`. If the table is absent, log at INFO that reconciliation was skipped, and return.
2. `InheritedTtl.parse(engineFull, "request_time")`. If it yields a single rule of `DAY` units whose interval
   equals the configured days, the table matches: return without any statement.
3. For `-1`, the table matches when `engine_full` declares no TTL clause at all.
4. Anything else differs, including a TTL in a shape `parse` does not recognise. Issue
   `ALTER TABLE <t> MODIFY TTL toDateTime(request_time) + INTERVAL <n> DAY`, or `ALTER TABLE <t> REMOVE TTL`,
   and log at WARN: table, previous TTL (the raw clause, or "none"), new TTL.

The table name is quoted as an identifier; `<n>` is a validated integer. Nothing else is interpolated.
Detecting "has a TTL clause" from `engine_full` uses a ` TTL ` token match, and so needs a test against a
table that carries only `ttl_only_drop_parts` in its settings.

### D5. No part rewrite: a plain `MODIFY TTL`, as measured

The concern: `MODIFY TTL` runs with `materialize_ttl_after_modify = 1` by default, materializing the TTL
across every part. If that rewrote the parts it would mean GiBs of bodies and the free-disk trap
`ttl_only_drop_parts` exists to avoid. With materialization off, existing parts keep TTL information
computed under the old expression, so a shortened retention would never reach settled day partitions.

Measured on ClickHouse 26.8 (the version `local_env` pins). The test used copies of the V10 table shape
(`ReplacingMergeTree`, daily partitions, `ttl_only_drop_parts = 1`), each with one day partition at 10, 40
and 60 days old holding tens of MB of body text, followed by an `ALTER` to 30 days:

| Variant | Outcome |
|---|---|
| plain `MODIFY TTL` (default materialization) | the 40/60-day partitions became empty parts within milliseconds; each `MutatePart` read only the `request_time` column (bytes per row = 8), and the surviving part kept its size: data columns hard-linked, not rewritten |
| table setting `materialize_ttl_recalculate_only = 1` | same reads; expired parts dropped by a follow-up `TTLDropMerge` instead. No benefit over the plain form |
| `materialize_ttl_after_modify = 0` | nothing expired: the stale per-part TTL information kept the old 90 days |
| raising to 120 days | one `MutatePart` per part, `request_time` read only; nothing dropped |
| `REMOVE TTL` | no mutation at all; the TTL clause is gone from `engine_full` |

Decision: issue the plain `MODIFY TTL …` with ClickHouse defaults, and `REMOVE TTL` for `-1`. No table
setting is changed. The mutation runs asynchronously, so startup does not wait for it. The enrichment
relayout wraps its `MODIFY TTL` in a temporary `materialize_ttl_recalculate_only = 1` because the table it
alters may lack `ttl_only_drop_parts`. The payload table is required to carry that setting (data-sync
spec), and with it the wrapper measured no cheaper, so it is not copied. `ttl_only_drop_parts = 1` stayed set in every variant. The integration test asserts the
end state (`engine_full`), not the part-level cost, which this measurement covers.

### D6. Failure handling

A rejected `ALTER` throws out of the migration runner and fails startup, like a failed migration. Several
replicas starting at once may each issue the same `ALTER`. The statement is idempotent, and the extra
recalculation is metadata-only per D5.

## Risks / Trade-offs

- [A mistyped value deletes data] → Validation rejects `0` and negatives other than `-1`; the WARN line
  records the old and new retention. No confirmation switch (see Non-Goals).
- [A deployment that set its TTL by hand loses it on upgrade] → Called out as BREAKING in the proposal and
  in the spec; they must set `SYNC_PAYLOAD_TTL_DAYS` first.
- [A later ClickHouse version makes the TTL materialization rewrite parts] → D5 records the measurement
  and how to repeat it; repeat it on a ClickHouse version bump. The integration test on the
  Testcontainers ClickHouse asserts the end state.
- [Aligned enrichments inherited 90 days at creation] → Out of scope (Non-Goals); they keep their own TTL.

## Migration Plan

No Flyway migration. On upgrade, the first start with the default compares 90 days with 90 days and does
nothing, unless the TTL was changed by hand. Rollback: redeploy the previous version. The table keeps
whatever TTL the last start set, and the older version never touches it.
