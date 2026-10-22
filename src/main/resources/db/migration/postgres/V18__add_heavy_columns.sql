-- Per-column transfer-cost hint: mark a column as heavy so wildcard projections skip it (a SQL
-- `SELECT *`, or a row-mode DSL query with no `select`). Catalog-only metadata (never part of the
-- ClickHouse DDL, like `tag` and `sensitive`): flipping it moves no data and issues no ALTER. Unlike
-- `sensitive` this is NOT access control — a heavy column stays visible in schema discovery and
-- returns normally when named explicitly. Defaults to FALSE so every existing column keeps expanding
-- into a wildcard until it is explicitly marked.
ALTER TABLE column_mapping ADD COLUMN heavy BOOLEAN NOT NULL DEFAULT FALSE;

-- Seed the two raw-payload columns of dial_usage_log: they hold the verbatim LLM request/response
-- bodies (unbounded per row, often tens of KB), which is what makes `SELECT * FROM dial_usage_log`
-- slow for external clients such as the Arrow Flight SQL pandas connector. Both are already
-- sensitive (V3), so the wildcard change is observable only to FULL_ADMIN callers and to deployments
-- running security.mode=none. jwt_claims/request_tags stay non-heavy (small claim/header documents),
-- as does rate_analytics.comment (a short free-text note). Reversible via
-- PATCH /v1/tables/{name}/schema (update → heavy).
UPDATE column_mapping SET heavy = TRUE
    WHERE table_name = 'dial_usage_log' AND source_name IN ('request_body', 'response_body');
