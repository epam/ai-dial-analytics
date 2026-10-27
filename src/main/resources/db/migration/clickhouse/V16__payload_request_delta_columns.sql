-- Add the request-delta columns to dial_usage_log_payload: the identity columns the mapping writes, the chain hashes
-- and cache_control markers ingest writes for a chat request, and a skip index for the parent lookup. Metadata-only:
-- existing rows read the column defaults, and the sort key, partitioning and TTL are untouched.
--
-- Specified in openspec/specs/data-sync/spec.md (requirement "A chat request body is stored as its new history and a
-- parent link" and the ones after it).
--
-- The AES_256_GCM_SIV codec requires a server-side encryption key, as V10 does. mapping/dial-usage-log-payload.yaml
-- maps the identity columns, so an operator running with clickhouse.migrations.enabled=false applies this DDL before
-- the new build.
ALTER TABLE dial_usage_log_payload
    ADD COLUMN IF NOT EXISTS project_id LowCardinality(String),
    ADD COLUMN IF NOT EXISTS deployment LowCardinality(String),
    ADD COLUMN IF NOT EXISTS user_hash String CODEC(ZSTD(1)),
    ADD COLUMN IF NOT EXISTS session_id Nullable(String) CODEC(ZSTD(1)),
    ADD COLUMN IF NOT EXISTS request_hash UUID,
    ADD COLUMN IF NOT EXISTS request_parent_hash UUID,
    ADD COLUMN IF NOT EXISTS request_ancestor_hashes Array(UUID) CODEC(ZSTD(3)),
    ADD COLUMN IF NOT EXISTS request_cache_control Nullable(String) CODEC(ZSTD(3), AES_256_GCM_SIV),
    ADD INDEX IF NOT EXISTS idx_request_hash request_hash TYPE bloom_filter(0.001) GRANULARITY 1;
