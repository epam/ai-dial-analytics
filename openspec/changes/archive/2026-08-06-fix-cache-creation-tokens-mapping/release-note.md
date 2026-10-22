# Release note — `cache_creation_tokens` now populated on every response shape

`dial_usage_log.cache_creation_tokens` was derived from the response payload at a path only one of the
shapes DIAL produces actually carries, so it stored `0` for nearly every call that wrote to the prompt
cache. It is now mapped from the producer's own
accounting (`token_usage.prompt_tokens_details.cache_write_tokens`), which is populated for streamed and
non-streamed responses and for both Anthropic-native and DIAL-normalized upstreams, and the column is
now `Nullable(UInt32)`.

Two operator-visible effects:

**1. Apply ClickHouse `V6` before deploying if migrations are disabled.**
With `clickhouse.migrations.enabled=true` (the default) Flyway applies
`V6__dial_usage_log_nullable_cache_creation_tokens.sql` at startup, before schema validation, and there
is nothing to do. With migrations disabled, apply it out of band **first**:

```sql
ALTER TABLE dial_usage_log
    MODIFY COLUMN cache_creation_tokens Nullable(UInt32) CODEC(T64, ZSTD(1));
```

Deploying without it fails startup — `SchemaValidator` refuses a mapping declaring the column nullable
against a bare `UInt32`. That is the intended stop, not a bug: ingesting against the old column would
store `0` where the mapping means NULL.

The `ALTER` is cheaper than a type change usually is: `T` → `Nullable(T)` hardlinks the stored values
and writes only the added null-map subcolumn. Measured on ClickHouse 25.3 over 24M rows in 4 parts, the
statement returned in 46 ms and the mutation did 13 ms of work with 0 rows rewritten. Cost scales with
part count, so still run it deliberately on a large deployment.

**2. The column steps at deploy, and history is not corrected.**
Rows ingested before this release keep their stored `0`. Rows after carry the real count, or NULL when
the producer reported none. A time series over `cache_creation_tokens` will therefore show a step at the
deploy boundary, and a `sum` spanning it under-reports the earlier side. Backfilling historical rows via
the `data-sync-backfill` endpoint is a separate, operator-scheduled step.

Consumers should also expect NULL from this column now, as they already do from `prompt_tokens`,
`completion_tokens`, `total_tokens`, and `cached_prompt_tokens`. Schema discovery publishes the flag.
An aggregate pipeline that sums this column into a non-nullable target column will be rejected at
registration — that rejection is the nullable-measure rule working, and the fix is a nullable target
column.
