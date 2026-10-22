-- Make rate_analytics scannable by the governed incremental scan (capability enrichment-runtime), the
-- way dial_usage_log already is. The scan needs a unique total order (version, identity) over the
-- source: rate_analytics already has the version half — _ingested_at, the ReplacingMergeTree version,
-- DEFAULT now64(3), assigned at ingest and never backdated — but had no per-row identity to break
-- ties, which is what this migration adds. The catalog side (declaring the two columns and exposing
-- event_id) is the paired PostgreSQL migration V16.
--
-- This complements the CREATE TABLE in V2 rather than editing it (an applied migration's checksum is
-- fixed): a fresh install runs V2 then this file, and an operator applying the DDL out of band does
-- the same in that order.
--
-- event_id is a table-side DEFAULT expression rather than an ingest-derived value, for two reasons.
-- It is computed from columns that are already persisted, so EXISTING rows read back exactly the
-- value a fresh insert would produce — no backfill step can disagree with ingest — and the data-sync
-- mapping needs no new column (rate-analytics.yaml is unchanged; a JSONEachRow insert that omits the
-- field gets the default, the same mechanism _ingested_at already relies on). dial_usage_log's
-- event_id instead comes from the Java hash-uuid deriver (UUIDv5/SHA-1), which ClickHouse cannot
-- reproduce in SQL, so that scheme could never have backfilled the rows already stored here.
--
-- The hash covers the FULL sort key, not just (trace_id, core_span_id) as on dial_usage_log. Two rows
-- of this table are distinct exactly when their sort tuple differs, so hashing that tuple makes
-- event_id unique per surviving row by construction — including rows whose trace/span ids are empty,
-- which a (trace_id, core_span_id) hash would map onto a single identity and thereby make the keyset
-- scan skip rows. Conversely, rows that DO collapse share every sort-key column and therefore share
-- event_id, so a re-ingested duplicate never changes the identity an enrichment row is keyed on.
ALTER TABLE rate_analytics
    ADD COLUMN IF NOT EXISTS event_id UUID DEFAULT reinterpretAsUUID(MD5(concat(
        project_id, '|', deployment, '|', toString(toUnixTimestamp64Milli(request_time)), '|',
        trace_id, '|', core_span_id)));
-- Store the column rather than recomputing it on every read. Correctness does not depend on this —
-- the DEFAULT yields the identical value on not-yet-materialized parts — so this mutation is free to
-- finish asynchronously after the migration returns.
ALTER TABLE rate_analytics MATERIALIZE COLUMN event_id;

-- Same reasoning as V3 for dial_usage_log: the scan advances by _ingested_at, which does NOT lead the
-- primary sort key (project_id, deployment, request_time, trace_id, core_span_id), so a range scan
-- over it would read every granule. A minmax index lets ClickHouse prune granules by their
-- _ingested_at min/max. MATERIALIZE INDEX builds it for EXISTING parts; its cost is proportional to
-- the data already stored, so run this as an explicit, monitored, off-peak migration.
ALTER TABLE rate_analytics ADD INDEX IF NOT EXISTS idx_ingested_at _ingested_at TYPE minmax GRANULARITY 1;
ALTER TABLE rate_analytics MATERIALIZE INDEX idx_ingested_at;
