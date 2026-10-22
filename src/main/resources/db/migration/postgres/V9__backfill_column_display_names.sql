-- Backfill user-friendly display names for the two seeded system tables (dial_usage_log,
-- rate_analytics). The column_mapping.display_name column already exists (V8); this migration only
-- UPDATEs values, issues no DDL, and touches no ClickHouse. Display names are short, human-readable
-- names that surface on both /v1/tables and the query-schema-discovery path
-- (GET /v1/queries/entities/schema/{name}); they are catalog-only metadata and can be changed later
-- via PATCH /v1/tables/{name}/schema (set_display_name). Every exposed column of both seeded tables
-- gets a display name, matching the coverage of the V6 description backfill (including the
-- ingest-bookkeeping `_ingested_at` / `_source_file` columns, which the seeded dial_usage_log exposes
-- on the read path).

-- dial_usage_log: all 38 exposed columns (were NULL after V8).
UPDATE column_mapping SET display_name = 'Project ID'             WHERE table_name = 'dial_usage_log' AND source_name = 'project_id';
UPDATE column_mapping SET display_name = 'User hash'              WHERE table_name = 'dial_usage_log' AND source_name = 'user_hash';
UPDATE column_mapping SET display_name = 'Deployment'             WHERE table_name = 'dial_usage_log' AND source_name = 'deployment';
UPDATE column_mapping SET display_name = 'Parent deployment'      WHERE table_name = 'dial_usage_log' AND source_name = 'parent_deployment';
UPDATE column_mapping SET display_name = 'Execution path'         WHERE table_name = 'dial_usage_log' AND source_name = 'execution_path';
UPDATE column_mapping SET display_name = 'Event ID'               WHERE table_name = 'dial_usage_log' AND source_name = 'event_id';
UPDATE column_mapping SET display_name = 'Event kind'             WHERE table_name = 'dial_usage_log' AND source_name = 'event_kind';
UPDATE column_mapping SET display_name = 'Request time'           WHERE table_name = 'dial_usage_log' AND source_name = 'request_time';
UPDATE column_mapping SET display_name = 'Conversation ID'        WHERE table_name = 'dial_usage_log' AND source_name = 'chat_id';
UPDATE column_mapping SET display_name = 'Trace ID'               WHERE table_name = 'dial_usage_log' AND source_name = 'trace_id';
UPDATE column_mapping SET display_name = 'Span ID'                WHERE table_name = 'dial_usage_log' AND source_name = 'core_span_id';
UPDATE column_mapping SET display_name = 'Parent span ID'         WHERE table_name = 'dial_usage_log' AND source_name = 'core_parent_span_id';
UPDATE column_mapping SET display_name = 'Response ID'            WHERE table_name = 'dial_usage_log' AND source_name = 'response_id';
UPDATE column_mapping SET display_name = 'Prompt tokens'          WHERE table_name = 'dial_usage_log' AND source_name = 'prompt_tokens';
UPDATE column_mapping SET display_name = 'Completion tokens'      WHERE table_name = 'dial_usage_log' AND source_name = 'completion_tokens';
UPDATE column_mapping SET display_name = 'Total tokens'           WHERE table_name = 'dial_usage_log' AND source_name = 'total_tokens';
UPDATE column_mapping SET display_name = 'Cached prompt tokens'   WHERE table_name = 'dial_usage_log' AND source_name = 'cached_prompt_tokens';
UPDATE column_mapping SET display_name = 'Cache creation tokens'  WHERE table_name = 'dial_usage_log' AND source_name = 'cache_creation_tokens';
UPDATE column_mapping SET display_name = 'Reasoning tokens'       WHERE table_name = 'dial_usage_log' AND source_name = 'reasoning_tokens';
UPDATE column_mapping SET display_name = 'Request messages'       WHERE table_name = 'dial_usage_log' AND source_name = 'number_request_messages';
UPDATE column_mapping SET display_name = 'Total cost'             WHERE table_name = 'dial_usage_log' AND source_name = 'total_price';
UPDATE column_mapping SET display_name = 'Deployment cost'        WHERE table_name = 'dial_usage_log' AND source_name = 'deployment_price';
UPDATE column_mapping SET display_name = 'Duration (ms)'          WHERE table_name = 'dial_usage_log' AND source_name = 'operation_duration_ms';
UPDATE column_mapping SET display_name = 'Response status'        WHERE table_name = 'dial_usage_log' AND source_name = 'response_status';
UPDATE column_mapping SET display_name = 'Success'                WHERE table_name = 'dial_usage_log' AND source_name = 'success';
UPDATE column_mapping SET display_name = 'Request method'         WHERE table_name = 'dial_usage_log' AND source_name = 'request_method';
UPDATE column_mapping SET display_name = 'Request URI'            WHERE table_name = 'dial_usage_log' AND source_name = 'request_uri';
UPDATE column_mapping SET display_name = 'MCP method'             WHERE table_name = 'dial_usage_log' AND source_name = 'mcp_method';
UPDATE column_mapping SET display_name = 'MCP tool call'          WHERE table_name = 'dial_usage_log' AND source_name = 'mcp_tool_call_name';
UPDATE column_mapping SET display_name = 'JWT claims'             WHERE table_name = 'dial_usage_log' AND source_name = 'jwt_claims';
UPDATE column_mapping SET display_name = 'Request tags'           WHERE table_name = 'dial_usage_log' AND source_name = 'request_tags';
UPDATE column_mapping SET display_name = 'Request body'           WHERE table_name = 'dial_usage_log' AND source_name = 'request_body';
UPDATE column_mapping SET display_name = 'Response body'          WHERE table_name = 'dial_usage_log' AND source_name = 'response_body';
UPDATE column_mapping SET display_name = 'Request body size'      WHERE table_name = 'dial_usage_log' AND source_name = 'request_body_bytes';
UPDATE column_mapping SET display_name = 'Response body size'     WHERE table_name = 'dial_usage_log' AND source_name = 'response_body_bytes';
UPDATE column_mapping SET display_name = 'Upstream URI'           WHERE table_name = 'dial_usage_log' AND source_name = 'response_upstream_uri';
UPDATE column_mapping SET display_name = 'Ingested at'            WHERE table_name = 'dial_usage_log' AND source_name = '_ingested_at';
UPDATE column_mapping SET display_name = 'Source file'            WHERE table_name = 'dial_usage_log' AND source_name = '_source_file';

-- rate_analytics: all 8 exposed columns.
UPDATE column_mapping SET display_name = 'Project ID'             WHERE table_name = 'rate_analytics' AND source_name = 'project_id';
UPDATE column_mapping SET display_name = 'User hash'              WHERE table_name = 'rate_analytics' AND source_name = 'user_hash';
UPDATE column_mapping SET display_name = 'Deployment'             WHERE table_name = 'rate_analytics' AND source_name = 'deployment';
UPDATE column_mapping SET display_name = 'Conversation ID'        WHERE table_name = 'rate_analytics' AND source_name = 'chat_id';
UPDATE column_mapping SET display_name = 'Response ID'            WHERE table_name = 'rate_analytics' AND source_name = 'response_id';
UPDATE column_mapping SET display_name = 'Request time'           WHERE table_name = 'rate_analytics' AND source_name = 'request_time';
UPDATE column_mapping SET display_name = 'Rating'                 WHERE table_name = 'rate_analytics' AND source_name = 'rate';
UPDATE column_mapping SET display_name = 'Comment'                WHERE table_name = 'rate_analytics' AND source_name = 'comment';
