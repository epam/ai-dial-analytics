-- Register request_cache_control of dial_usage_log_payload (added by ClickHouse V16) in the catalog, sensitive and
-- heavy like request_body, and describe the delta form of request_body. The other V16 columns stay hidden.
--
-- Specified in openspec/specs/table-management/spec.md and openspec/specs/data-sync/spec.md (requirement "A chat
-- request body is stored as its new history and a parent link").
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
