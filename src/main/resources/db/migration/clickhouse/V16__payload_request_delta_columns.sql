-- Columns for storing a chat request body as its new history and a parent link on dial_usage_log_payload.
--
-- Every turn of a chat or agent session re-sends the whole conversation so far, and usually the same tools, so storing
-- each request body whole stores the same messages and tool definitions over and over. Under
-- sync.request-body-mode=delta, ingest stores in request_body a chat request cut to the history elements its parent
-- request did not already have, without the tools it shares with that parent, and links the row to the parent.
-- Specified in openspec/specs/data-sync/spec.md ("A chat request body is stored as its new history and a parent link"
-- and the requirements after it).
--
-- THE CONTENT COLUMN request_cache_control mirrors the body columns exactly — Nullable(String) CODEC(ZSTD(3),
-- AES_256_GCM_SIV) — because it holds the same class of content: verbatim, user-visible, unbounded per row. It holds
-- the cache_control markers taken out of this row's history, so a rebuild can put back exactly the markers the request
-- was sent with.
--
-- THE IDENTITY COLUMNS are written by the mapping on every row; the first three repeat the record's dial_usage_log row:
--   * project_id, deployment, user_hash — project_id and user_hash are the chain scope, so a parent is only ever
--     another request of the same project and user; all three are also the leading columns a session-ordered sort
--     key of this table would need, which a hash of them could not serve;
--   * session_id        — the client's session id, as DIAL Core resolves it from its conversation-id headers;
--     nullable, since older logs and clients that send no such header carry none. New information, held only here,
--     and stored unencrypted like the other identifiers: it is an opaque id, not request content. Not part of the
--     chain scope: a client-supplied id can change or vanish mid-conversation, and would only cut links.
--
-- THE CHAIN COLUMNS are 128-bit hashes stored as UUID, so they travel as plain strings in JSONEachRow:
--   * request_hash            — hash over the scope, the UTC day, the tools and every history element in order; written
--                               for every chat request, whole or not, so a row stored whole can still be a parent. The
--                               tools are in it, so a parent always has its child's tools. It is NOT a hash of the
--                               whole body: requests differing only in model or sampling parameters share it, and the
--                               same body sent by another user or on another day does not;
--   * request_parent_hash     — the parent's request_hash; all-zero for a chain root and for a body stored whole, whose
--                               request_body is then the whole request;
--   * request_ancestor_hashes — the nearest ancestors' hashes, root side first: a read hint that lets a rebuild usually
--                               read its chain in one query. The chain itself is request_parent_hash, followed row by row.
-- A row with a parent leaves its top-level system keys and tools out of request_body: the hash covers them, so every
-- row of a chain has the same ones, and a rebuild copies them from the chain's root.
-- These are hidden from the catalog; it exposes request_cache_control only (Postgres migration of this change).
--
-- THE SKIP INDEX serves the parent lookup, which asks for request_hash values within one daily partition. The
-- table's sort key is event_id, so without it the lookup reads request_hash of the whole partition. An IN set keeps a
-- granule when any of its values may be there, so the false-positive rate is set low (0.1%) and the lookup asks about
-- few hashes per request; with many candidates it degrades to reading request_hash of the day, which is a small column.
-- Rows written before this migration hold no hash, so the index is not materialized for existing parts.
--
-- Cost: ADD COLUMN and ADD INDEX are metadata-only. Nothing is rewritten: existing rows read the defaults — NULL
-- markers and session, empty identity strings, zero hashes, no ancestors — and the sort
-- key, partitioning and TTL are untouched.
--
-- The AES_256_GCM_SIV codec REQUIRES a server-side encryption key (encryption_codecs in config.d) or this ALTER
-- fails, exactly as V10's CREATE TABLE does without it.
--
-- Ordering invariant: SchemaValidator refuses at startup a mapping declaring a column the physical table lacks, and
-- mapping/dial-usage-log-payload.yaml maps the identity columns, so this file ships with those mapping entries. An
-- operator running with clickhouse.migrations.enabled=false applies this DDL BEFORE the new build.
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
