-- Retag the columns of the two seeded system tables (dial_usage_log, rate_analytics) to the current
-- tagging convention, retiring the muddled `identity` / `lineage` / `payload` tags. Columns are
-- grouped by two subject tags and a set of role tags:
--   * principal  -> who made the request (project_id, user_hash)
--   * deployment -> what they called (deployment, parent_deployment, execution_path)
--   * identifier -> keys/correlation ids that identify one interaction and its response
--   * dimension  -> plain categorical/time slicers (event_kind, request_time)
--   * request    -> request-side fields and the request body (plus user-submitted rate/comment)
--   * response   -> response-side fields and the response body
-- The measurement columns keep their role tags (token-usage, cost, performance, system) and are not
-- touched here. `tag` is catalog-only metadata (never part of the ClickHouse DDL); this migration only
-- UPDATEs values, issues no DDL, and touches no ClickHouse. It supersedes the tag values seeded in
-- V1 (dial_usage_log) and V2 (rate_analytics), which are immutable and not edited here. The tagging
-- convention is documented in docs/column-tags.md.

-- dial_usage_log: who made the request.
UPDATE column_mapping SET tag = 'principal'
    WHERE table_name = 'dial_usage_log'
      AND source_name IN ('project_id', 'user_hash');

-- dial_usage_log: what they called (the deployment and the routing path to it).
UPDATE column_mapping SET tag = 'deployment'
    WHERE table_name = 'dial_usage_log'
      AND source_name IN ('deployment', 'parent_deployment', 'execution_path');

-- dial_usage_log: keys/correlation ids that identify one interaction (and its response) -> identifier.
UPDATE column_mapping SET tag = 'identifier'
    WHERE table_name = 'dial_usage_log'
      AND source_name IN ('chat_id', 'trace_id', 'core_span_id', 'core_parent_span_id', 'event_id',
                          'response_id');

-- dial_usage_log: low-cardinality categorical/time slicers -> dimension.
UPDATE column_mapping SET tag = 'dimension'
    WHERE table_name = 'dial_usage_log'
      AND source_name IN ('event_kind', 'request_time');

-- dial_usage_log: request-side fields and the request body -> request. number_request_messages is a
-- request-shape metric (count of chat messages / embedding inputs), not a token count, so it moves
-- here from token-usage.
UPDATE column_mapping SET tag = 'request'
    WHERE table_name = 'dial_usage_log'
      AND source_name IN ('request_method', 'request_uri', 'mcp_method', 'mcp_tool_call_name',
                          'jwt_claims', 'request_tags', 'number_request_messages', 'request_body',
                          'request_body_bytes');

-- dial_usage_log: response-side fields and the response body -> response.
UPDATE column_mapping SET tag = 'response'
    WHERE table_name = 'dial_usage_log'
      AND source_name IN ('response_status', 'success', 'response_upstream_uri', 'response_body',
                          'response_body_bytes');

-- rate_analytics: same subjects, for the identity columns exposed on this table.
UPDATE column_mapping SET tag = 'principal'
    WHERE table_name = 'rate_analytics'
      AND source_name IN ('project_id', 'user_hash');

UPDATE column_mapping SET tag = 'deployment'
    WHERE table_name = 'rate_analytics'
      AND source_name IN ('deployment');

UPDATE column_mapping SET tag = 'identifier'
    WHERE table_name = 'rate_analytics'
      AND source_name IN ('chat_id', 'response_id');

-- rate_analytics: request_time stays a plain time slicer -> dimension.
UPDATE column_mapping SET tag = 'dimension'
    WHERE table_name = 'rate_analytics'
      AND source_name IN ('request_time');

-- rate_analytics: the rating value and free-text comment are user-submitted with the rating request.
UPDATE column_mapping SET tag = 'request'
    WHERE table_name = 'rate_analytics'
      AND source_name IN ('rate', 'comment');
