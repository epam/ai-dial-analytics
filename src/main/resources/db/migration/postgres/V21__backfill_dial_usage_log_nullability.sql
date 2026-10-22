
-- `column_mapping.nullable` records whether a column can hold NULL. It was added with a FALSE default,
-- and the `dial_usage_log` seed in V1 predates it: every one of that table's rows says non-nullable while
-- eleven of its ClickHouse columns are physically `Nullable(...)`. (`rate_analytics`, seeded in V2 after
-- the flag existed, sets it correctly — this is a gap in one table, not a missing convention.)
--
-- The divergence is not cosmetic. Schema discovery publishes the flag, so every consumer has been told
-- these columns cannot be null while reads return nulls from them; and any governance rule that reasons
-- about nullability from the catalog is inert on exactly the columns that need it — the aggregate
-- pipeline's nullable-measure rule would let a `sum(total_tokens)` land in a non-nullable rollup column
-- and store 0 for a bucket that had no data at all.
--
-- The physical types are the source of truth here (V1__create_dial_usage_log.sql), and the sync mapping
-- already agrees with them (`nullable: true` in mapping/dial-usage-log.yaml). This aligns the third copy.
-- Column nullability is fixed at DDL, so this changes only what the catalog says, never the table.
UPDATE column_mapping
SET nullable = TRUE
WHERE table_name = 'dial_usage_log'
  AND source_name IN (
    'core_parent_span_id',
    'parent_deployment',
    'response_upstream_uri',
    'prompt_tokens',
    'completion_tokens',
    'total_tokens',
    'cached_prompt_tokens',
    'total_price',
    'deployment_price',
    'request_body',
    'response_body'
  );
