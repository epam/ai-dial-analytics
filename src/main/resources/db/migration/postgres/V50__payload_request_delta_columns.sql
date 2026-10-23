-- Register the three request-delta content columns of dial_usage_log_payload in the catalog, seeded sensitive and
-- heavy.
--
-- The physical columns are added by db/migration/clickhouse/V16__payload_request_delta_columns.sql; this migration
-- only registers them, so it issues no ClickHouse ALTER. Under sync.request-body-mode=delta a chat request is stored
-- as request_envelope + request_delta + a parent link, with request_body NULL (openspec/specs/data-sync/spec.md,
-- "A chat request body is stored as an envelope, a delta and a parent link"). From here the three columns resolve
-- through the payload table's grain join as "dial_usage_log_payload.request_envelope" and so on, like the bodies.
--
-- ONLY THE CONTENT COLUMNS ARE EXPOSED. The chain columns (request_scope, request_path, request_parent,
-- request_depth, request_ancestors) stay hidden physical columns, like event_id and request_time: they are hashes
-- that mean nothing to a reader, and they exist for ingest's parent lookup and for a later full-body rebuild.
--
-- Sensitive and heavy on the same grounds as request_body (V3, V18): the envelope and the delta are verbatim
-- request content, and the markers sit beside it; each is unbounded per row, so a wildcard projection leaves them
-- out and a caller without the TABLE_SENSITIVE clearance never sees them. The tag is copied from request_body so the three group with
-- it, and ordinals 5-7 put them last, after the five columns V38 moved (ordinals 0-4), matching the physical order
-- ADD COLUMN produced.
INSERT INTO column_mapping (table_name, source_name, name, type, element_type, nullable, description,
                            display_name, tag, sensitive, heavy, ordinal)
SELECT 'dial_usage_log_payload', seeded.source_name, seeded.source_name, 'string', NULL, TRUE, seeded.description,
       seeded.display_name, body.tag, TRUE, TRUE, seeded.ordinal
  FROM (VALUES
        ('request_envelope',
         'The request body without its message history (model, parameters, tools and so on), as canonical JSON, '
             || 'for a chat request stored in delta form. NULL when request_body holds the whole body.',
         'Request envelope', 5),
        ('request_delta',
         'The history elements this chat request added to its parent request, as a canonical JSON array: the whole '
             || 'history for the first request of a chain. cache_control markers are removed. NULL when '
             || 'request_body holds the whole body.',
         'Request delta', 6),
        ('request_cache_markers',
         'The cache_control markers removed from this request''s history, as a JSON array of element index, '
             || 'location and value. NULL when the request carried none or request_body holds the whole body.',
         'Request cache markers', 7)
       ) AS seeded (source_name, description, display_name, ordinal)
 CROSS JOIN (SELECT tag FROM column_mapping
              WHERE table_name = 'dial_usage_log_payload' AND source_name = 'request_body') AS body;

DO $$
DECLARE
    registered INTEGER;
BEGIN
    SELECT count(*) INTO registered FROM column_mapping
     WHERE table_name = 'dial_usage_log_payload'
       AND source_name IN ('request_envelope', 'request_delta', 'request_cache_markers')
       AND sensitive AND heavy;
    IF registered <> 3 THEN
        RAISE EXCEPTION 'V50: expected three sensitive, heavy request-delta columns on dial_usage_log_payload, found %',
            registered;
    END IF;
END $$;
