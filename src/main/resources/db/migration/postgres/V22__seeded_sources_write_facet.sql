-- The `write` facet names the engine a table was materialized with: `append` is a plain MergeTree that
-- keeps every row, `upsert_by_key` a ReplacingMergeTree that collapses to the latest row per
-- ordering_key. For the two seeded sources it has been naming the wrong one.
--
-- `dial_usage_log` and `rate_analytics` are both `ENGINE = ReplacingMergeTree(_ingested_at)` with
-- `ORDER BY (project_id, deployment, request_time, trace_id, core_span_id)` — deliberately, and their
-- ClickHouse migrations say why: the sort tuple is the re-ingest dedup key, so a log line delivered
-- twice collapses on the greatest `_ingested_at` instead of being counted twice. That is the
-- `upsert_by_key` discipline exactly.
--
-- They were nonetheless recorded as `append`, because `write_mode` arrived in V19 with an `APPEND`
-- default and these rows predate it. Their DDL is hand-written (clickhouse/V1, V2) rather than rendered
-- by TableDdlRenderer, so nothing ever reconciled the declaration with the engine.
--
-- Nothing has misbehaved yet only because no read path consults the facet: deduplication is applied as
-- the statement-wide `final = 1` setting, which asks ClickHouse what engine it has rather than asking
-- the catalog. The disagreement was invisible precisely because the mechanism ignored the thing that
-- disagreed. It stops being invisible for anything that reasons from the declaration — an operator
-- comparing a raw `SELECT count(*)` against ADAS sees a gap (measured: 70,281 rows across 65,665
-- distinct keys on a dev `dial_usage_log`) and a catalog insisting the table keeps every row.
--
-- Catalog metadata only: `write` selects the engine at materialization, and these tables are already
-- materialized, so this changes what ADAS believes and nothing about what ClickHouse holds. Reads and
-- writes are byte-for-byte unaffected.
UPDATE table_definition
SET write_mode = 'UPSERT_BY_KEY'
WHERE name IN ('dial_usage_log', 'rate_analytics');
