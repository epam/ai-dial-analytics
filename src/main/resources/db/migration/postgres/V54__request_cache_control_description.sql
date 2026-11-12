-- Re-describe dial_usage_log_payload.request_cache_control (adds the text_blocks entry); openspec/specs/data-sync/spec.md
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
