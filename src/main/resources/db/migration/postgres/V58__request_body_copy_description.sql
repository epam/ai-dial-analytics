-- Re-describe dial_usage_log_payload.request_body: under delta storage a large body that is not JSON and repeats an
-- earlier one is stored empty. Specified in openspec/specs/table-management/spec.md and
-- openspec/specs/data-sync/spec.md ("A large request body equal to an earlier one is stored once"). Only the text the
-- request-delta migration wrote is replaced, so a description an operator has edited since stays as it is.
UPDATE column_mapping
   SET description = 'Raw request body payload. Under delta storage a chat request is re-serialized (keys sorted, no '
                         || 'whitespace) without the cache_control markers of its history, carries only the history '
                         || 'elements its parent request did not have, and leaves out the system keys and tools it '
                         || 'shares with it; a large body that is not JSON and repeats an earlier one exactly is '
                         || 'stored as an empty string. request_body_full returns the whole body in both cases.'
 WHERE table_name = 'dial_usage_log_payload' AND source_name = 'request_body'
   AND description = 'Raw request body payload. Under delta storage a chat request is re-serialized (keys sorted, no '
                         || 'whitespace) without the cache_control markers of its history, carries only the history '
                         || 'elements its parent request did not have, and leaves out the system keys and tools it '
                         || 'shares with it.';
