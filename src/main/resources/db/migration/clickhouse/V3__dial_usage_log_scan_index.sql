-- B4 (enrichment-runtime): add a minmax skip index on dial_usage_log._ingested_at.
--
-- The governed incremental scan advances by ingest/version order (the cursor tuple leads with
-- _ingested_at) so late-arriving rows are never skipped. But _ingested_at does NOT lead the table's
-- primary sort key (project_id, deployment, request_time, trace_id, core_span_id), so a range scan
-- over _ingested_at would otherwise read every granule. A minmax index lets ClickHouse prune
-- granules by their _ingested_at min/max, keeping the keyset range scan cheap.
--
-- MATERIALIZE INDEX builds the index for EXISTING parts; its cost is proportional to the data
-- already stored, so run this as an explicit, monitored, off-peak migration.
ALTER TABLE dial_usage_log ADD INDEX IF NOT EXISTS idx_ingested_at _ingested_at TYPE minmax GRANULARITY 1;
ALTER TABLE dial_usage_log MATERIALIZE INDEX idx_ingested_at;
