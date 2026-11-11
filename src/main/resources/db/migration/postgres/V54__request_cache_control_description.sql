-- request_cache_control of dial_usage_log_payload now also records, for a request stored with a parent, which
-- inherited messages it sent as one text block. Only the seeded description is replaced, so a description an
-- operator has edited since stays as it is.
--
-- Specified in openspec/specs/data-sync/spec.md (requirement "Cache markers are kept per event").
UPDATE column_mapping
   SET description = 'What a rebuild of this chat request needs beside its stored body, as a JSON array: the '
                         || 'cache_control markers removed from its history (element index, location and value) and, '
                         || 'for a request stored with a parent, one text_blocks entry listing the earlier messages '
                         || 'it sent as one text block. NULL when the body is stored whole, or has no parent and '
                         || 'carried no marker.'
 WHERE table_name = 'dial_usage_log_payload' AND source_name = 'request_cache_control'
   AND description = 'The cache_control markers removed from this chat request''s history when it was stored in delta form, as a '
                         || 'JSON array of element index, location and value. NULL when the request carried none or its body is '
                         || 'stored whole.';
