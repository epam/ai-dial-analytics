-- Scan metadata per source (capability enrichment-runtime): the two columns that define a source's
-- unique total order for the governed incremental scan. version_column is the monotonic,
-- assigned-at-ingest, never-backdated column the scan orders and safety-lags by; identity_column is
-- the tiebreaker that makes the tuple unique, so keyset paging can never skip or repeat a row.
-- Previously hardcoded in ScanService for dial_usage_log only; storing them here makes "this source
-- is scannable, and this is its total order" a catalog fact instead of a code branch.
--
-- Deliberately NOT settable through the /v1/tables API: the scan's exactly-once guarantee depends on
-- physical properties of version_column (monotonic at ingest, never backdated) that the table API
-- cannot verify, so configuring a scan source stays an operator action via migration.
ALTER TABLE table_definition
    ADD COLUMN version_column  TEXT,
    ADD COLUMN identity_column TEXT;

-- Seed the one source scanned today with exactly what the deleted hardcoded branch returned:
-- _ingested_at (DEFAULT now64(3), the ReplacingMergeTree version) and event_id (hash of
-- (trace_id, core_span_id), unique per source row).
UPDATE table_definition
SET version_column  = '_ingested_at',
    identity_column = 'event_id'
WHERE name = 'dial_usage_log';
