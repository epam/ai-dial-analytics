-- Column-level access control: mark a column as sensitive so it is exposed only to FULL_ADMIN callers.
-- Catalog-only metadata (never part of the ClickHouse DDL), mirroring `tag`. Defaults to FALSE so every
-- existing column stays visible until it is explicitly restricted.
ALTER TABLE column_mapping ADD COLUMN sensitive BOOLEAN NOT NULL DEFAULT FALSE;

-- Seed the direct-identity and raw-content columns of the two source tables as sensitive. user_hash and
-- request_tags are intentionally left visible: user_hash is a de-identified per-user surrogate that lets
-- read-only roles do safe per-user analytics (distinct users, per-user rollups), and request_tags is
-- routing metadata. Sensitivity is catalog metadata and can be changed later via
-- PATCH /v1/tables/{name}/schema (set_sensitive).
UPDATE column_mapping SET sensitive = TRUE
    WHERE table_name = 'dial_usage_log' AND source_name IN ('jwt_claims', 'request_body', 'response_body');
UPDATE column_mapping SET sensitive = TRUE
    WHERE table_name = 'rate_analytics' AND source_name = 'comment';
