-- Register request_cache_control of dial_usage_log_payload in the catalog, seeded sensitive and heavy, and describe
-- the delta form of request_body.
--
-- The physical columns are added by db/migration/clickhouse/V16__payload_request_delta_columns.sql; this migration
-- only registers them, so it issues no ClickHouse ALTER. Under sync.request-body-mode=delta a chat request's
-- request_body is cut to the history elements its parent request did not already have, without the tools it shares
-- with that parent (openspec/specs/data-sync/spec.md, "A chat request body is stored as its new history and a parent
-- link"). From here request_cache_control resolves through the payload table's grain join as
-- "dial_usage_log_payload.request_cache_control", like the bodies.
--
-- ONLY THE MARKERS ARE EXPOSED. The identity columns (project_id, deployment, user_hash, session_id) serve ingest and
-- a future sort key; the first three repeat what dial_usage_log already exposes. The chain columns (request_hash,
-- request_parent_hash, request_ancestor_hashes) stay hidden physical columns, like event_id and request_time: they
-- exist for ingest's parent lookup and for a later full-body rebuild.
--
-- Sensitive and heavy on the same grounds as request_body (V3, V18): the markers are verbatim request content and
-- unbounded per row, so a wildcard projection leaves them out and a caller without the TABLE_SENSITIVE clearance never
-- sees them. Tagged 'request', like request_body (V7), and ordinal 5 puts it last, after the five columns V38 moved
-- (ordinals 0-4), matching the physical order ADD COLUMN produced.
INSERT INTO column_mapping (table_name, source_name, name, type, element_type, nullable, description,
                            display_name, tag, sensitive, heavy, ordinal)
VALUES ('dial_usage_log_payload', 'request_cache_control', 'request_cache_control', 'string', NULL, TRUE,
        'The cache_control markers removed from this chat request''s history when it was stored in delta form, as a '
            || 'JSON array of element index, location and value. NULL when the request carried none or its body is '
            || 'stored whole.',
        'Request cache control', 'request', TRUE, TRUE, 5);

-- request_body keeps its name and meaning for a body stored whole; only the seeded description is replaced, so a
-- description an operator has edited since stays as it is.
UPDATE column_mapping
   SET description = 'Raw request body payload. A chat request stored in delta form carries only the history '
                         || 'elements its parent request did not have, and leaves out the tools it shares with it.'
 WHERE table_name = 'dial_usage_log_payload' AND source_name = 'request_body'
   AND description = 'Raw request body payload.';
