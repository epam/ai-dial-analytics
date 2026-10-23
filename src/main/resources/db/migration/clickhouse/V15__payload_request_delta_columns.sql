-- Columns for storing a chat request body as an envelope, a delta and a parent link on dial_usage_log_payload.
--
-- Every turn of a chat or agent session re-sends the whole conversation so far, so storing each request body whole
-- stores the same messages over and over. Under sync.request-body-mode=delta, ingest stores a chat request as its
-- envelope (the body without its message history), only the history elements its parent request did not already
-- have, and a link to that parent; request_body is NULL on such a row. Specified in openspec/specs/data-sync/spec.md
-- ("A chat request body is stored as an envelope, a delta and a parent link" and the requirements after it).
--
-- THE CONTENT COLUMNS mirror the body columns exactly — Nullable(String) CODEC(ZSTD(3), AES_256_GCM_SIV) — because
-- they hold the same class of content: verbatim, user-visible, unbounded per row. request_cache_markers holds the
-- cache_control markers taken out of this row's history, so a rebuild can put back exactly the markers the request
-- was sent with.
--
-- THE CHAIN COLUMNS are 128-bit hashes stored as UUID, so they travel as plain strings in JSONEachRow:
--   * request_scope     — hash of the record's project, deployment and user, written by the mapping on every row;
--   * request_path      — hash over the scope, the UTC day and every history element in order; written for every
--                         chat request, whole or not, so a row stored whole can still be a parent;
--   * request_parent    — the parent's request_path; all-zero for a chain root and for a body stored whole;
--   * request_depth     — number of history elements of the full request;
--   * request_ancestors — the nearest ancestors' paths, root side first: a read hint that lets a rebuild usually read
--                         its chain in one query. The chain itself is request_parent, followed row by row.
-- They are hidden from the catalog; only the three content columns are exposed (Postgres migration of this change).
--
-- THE SKIP INDEX serves the parent lookup, which asks for request_path values within one daily partition. The
-- table's sort key is event_id, so without it the lookup reads request_path of the whole partition. An IN set keeps a
-- granule when any of its values may be there, so the false-positive rate is set low (0.1%) and the lookup asks about
-- few paths per request; with many candidates it degrades to reading request_path of the day, which is a small column.
-- Rows written before this migration hold no path, so the index is not materialized for existing parts.
--
-- Cost: ADD COLUMN and ADD INDEX are metadata-only. Nothing is rewritten: existing rows read the defaults — NULL
-- content, zero hashes, depth 0, no ancestors — and the sort key, partitioning and TTL are untouched.
--
-- The AES_256_GCM_SIV codec REQUIRES a server-side encryption key (encryption_codecs in config.d) or this ALTER
-- fails, exactly as V10's CREATE TABLE does without it.
--
-- Ordering invariant: SchemaValidator refuses at startup a mapping declaring a column the physical table lacks, and
-- mapping/dial-usage-log-payload.yaml maps request_scope, so this file ships with that mapping entry. An operator
-- running with clickhouse.migrations.enabled=false applies this DDL BEFORE the new build.
ALTER TABLE dial_usage_log_payload
    ADD COLUMN IF NOT EXISTS request_scope UUID,
    ADD COLUMN IF NOT EXISTS request_path UUID,
    ADD COLUMN IF NOT EXISTS request_parent UUID,
    ADD COLUMN IF NOT EXISTS request_depth UInt32,
    ADD COLUMN IF NOT EXISTS request_ancestors Array(UUID) CODEC(ZSTD(3)),
    ADD COLUMN IF NOT EXISTS request_envelope Nullable(String) CODEC(ZSTD(3), AES_256_GCM_SIV),
    ADD COLUMN IF NOT EXISTS request_delta Nullable(String) CODEC(ZSTD(3), AES_256_GCM_SIV),
    ADD COLUMN IF NOT EXISTS request_cache_markers Nullable(String) CODEC(ZSTD(3), AES_256_GCM_SIV),
    ADD INDEX IF NOT EXISTS idx_request_path request_path TYPE bloom_filter(0.001) GRANULARITY 1;
