-- Backfill human-readable descriptions for the two seeded system tables (dial_usage_log,
-- rate_analytics). The column_mapping.description column already exists (V1); this migration only
-- UPDATEs values, issues no DDL, and touches no ClickHouse. Descriptions surface on both /v1/tables
-- (already) and the query-schema-discovery path (GET /v1/queries/entities/schema/{name}). Wording for
-- columns that map to a documented upstream measurement tag/field follows the ai-dial-analytics-realtime
-- README; columns with no upstream counterpart get concise ADAS-authored descriptions.

-- dial_usage_log: all 38 exposed columns (were NULL after V1).
UPDATE column_mapping SET description = 'Unique identifier of this usage-log event.'                                                        WHERE table_name = 'dial_usage_log' AND source_name = 'event_id';
UPDATE column_mapping SET description = 'Kind of event, derived from the request URI: llm_call, embedding, mcp, route, or empty when none match.' WHERE table_name = 'dial_usage_log' AND source_name = 'event_kind';
UPDATE column_mapping SET description = 'Timestamp when the request was made.'                                                              WHERE table_name = 'dial_usage_log' AND source_name = 'request_time';
UPDATE column_mapping SET description = 'Timestamp when ADAS ingested this event from blob storage.'                                        WHERE table_name = 'dial_usage_log' AND source_name = '_ingested_at';
UPDATE column_mapping SET description = 'Blob-storage source file this event was ingested from.'                                            WHERE table_name = 'dial_usage_log' AND source_name = '_source_file';
UPDATE column_mapping SET description = 'DIAL project the request belongs to: the project assigned to the API key that made the request. Every DIAL API key is bound to a project, so a project groups keys and attributes their usage.' WHERE table_name = 'dial_usage_log' AND source_name = 'project_id';
UPDATE column_mapping SET description = 'Identifier of the conversation the request belongs to.'                                            WHERE table_name = 'dial_usage_log' AND source_name = 'chat_id';
UPDATE column_mapping SET description = 'Trace id shared by every request in one interaction (turn): a single Core call can fan out into a chain of deployment-to-deployment calls, so grouping by trace_id reconstructs the whole chain.' WHERE table_name = 'dial_usage_log' AND source_name = 'trace_id';
UPDATE column_mapping SET description = 'DIAL Core span id identifying this single hop within the trace (one proxy request = one span = one row); together with trace_id it uniquely identifies the row.' WHERE table_name = 'dial_usage_log' AND source_name = 'core_span_id';
UPDATE column_mapping SET description = 'Span id of the calling hop (the parent span in the same trace); null for the first hop of the chain. Links this request to its caller so the call tree can be reconstructed.' WHERE table_name = 'dial_usage_log' AND source_name = 'core_parent_span_id';
UPDATE column_mapping SET description = 'Ordered array of deployment names naming the routing path (the chain of deployments) the request took within the trace.' WHERE table_name = 'dial_usage_log' AND source_name = 'execution_path';
UPDATE column_mapping SET description = 'Name of the deployment that called this one (the previous hop in the chain); null at the start of the chain.' WHERE table_name = 'dial_usage_log' AND source_name = 'parent_deployment';
UPDATE column_mapping SET description = 'Name of the deployment that handled this hop of the request.'                                       WHERE table_name = 'dial_usage_log' AND source_name = 'deployment';
UPDATE column_mapping SET description = 'Upstream URI that served the response.'                                                            WHERE table_name = 'dial_usage_log' AND source_name = 'response_upstream_uri';
UPDATE column_mapping SET description = 'Anonymized hash identifying the end user.'                                                         WHERE table_name = 'dial_usage_log' AND source_name = 'user_hash';
UPDATE column_mapping SET description = 'Identifier of the response produced for the request.'                                              WHERE table_name = 'dial_usage_log' AND source_name = 'response_id';
UPDATE column_mapping SET description = 'Number of prompt (input) tokens consumed.'                                                         WHERE table_name = 'dial_usage_log' AND source_name = 'prompt_tokens';
UPDATE column_mapping SET description = 'Number of completion (output) tokens generated.'                                                   WHERE table_name = 'dial_usage_log' AND source_name = 'completion_tokens';
UPDATE column_mapping SET description = 'Total tokens consumed (prompt plus completion).'                                                   WHERE table_name = 'dial_usage_log' AND source_name = 'total_tokens';
UPDATE column_mapping SET description = 'Number of prompt tokens served from cache.'                                                        WHERE table_name = 'dial_usage_log' AND source_name = 'cached_prompt_tokens';
UPDATE column_mapping SET description = 'Number of tokens written to the prompt cache.'                                                     WHERE table_name = 'dial_usage_log' AND source_name = 'cache_creation_tokens';
UPDATE column_mapping SET description = 'Number of reasoning tokens generated by the model.'                                                WHERE table_name = 'dial_usage_log' AND source_name = 'reasoning_tokens';
UPDATE column_mapping SET description = 'Number of messages in the request.'                                                                WHERE table_name = 'dial_usage_log' AND source_name = 'number_request_messages';
UPDATE column_mapping SET description = 'The total cost of the request (this request plus all related requests), as computed at request-execution time; later cost adjustments (pricing-config fixes, provider discounts, etc.) are not reflected here.' WHERE table_name = 'dial_usage_log' AND source_name = 'total_price';
UPDATE column_mapping SET description = 'The cost of this specific request (excluding requests it directly or indirectly initiated), as computed at request-execution time; later cost adjustments (pricing-config fixes, provider discounts, etc.) are not reflected here.' WHERE table_name = 'dial_usage_log' AND source_name = 'deployment_price';
UPDATE column_mapping SET description = 'Wall-clock duration of the operation, in milliseconds.'                                            WHERE table_name = 'dial_usage_log' AND source_name = 'operation_duration_ms';
UPDATE column_mapping SET description = 'HTTP status code of the response.'                                                                 WHERE table_name = 'dial_usage_log' AND source_name = 'response_status';
UPDATE column_mapping SET description = 'Whether the request completed successfully.'                                                       WHERE table_name = 'dial_usage_log' AND source_name = 'success';
UPDATE column_mapping SET description = 'HTTP method of the request.'                                                                       WHERE table_name = 'dial_usage_log' AND source_name = 'request_method';
UPDATE column_mapping SET description = 'URI path of the request.'                                                                          WHERE table_name = 'dial_usage_log' AND source_name = 'request_uri';
UPDATE column_mapping SET description = 'MCP method invoked, for MCP endpoint requests.'                                                    WHERE table_name = 'dial_usage_log' AND source_name = 'mcp_method';
UPDATE column_mapping SET description = 'Name of the MCP tool call, for MCP endpoint requests.'                                             WHERE table_name = 'dial_usage_log' AND source_name = 'mcp_tool_call_name';
UPDATE column_mapping SET description = 'JWT claims associated with the request.'                                                           WHERE table_name = 'dial_usage_log' AND source_name = 'jwt_claims';
UPDATE column_mapping SET description = 'A map of key/value pairs associated with the request (currently the request headers).' WHERE table_name = 'dial_usage_log' AND source_name = 'request_tags';
UPDATE column_mapping SET description = 'Raw request body payload.'                                                                         WHERE table_name = 'dial_usage_log' AND source_name = 'request_body';
UPDATE column_mapping SET description = 'Raw response body payload.'                                                                        WHERE table_name = 'dial_usage_log' AND source_name = 'response_body';
UPDATE column_mapping SET description = 'Size of the request body, in bytes.'                                                               WHERE table_name = 'dial_usage_log' AND source_name = 'request_body_bytes';
UPDATE column_mapping SET description = 'Size of the response body, in bytes.'                                                              WHERE table_name = 'dial_usage_log' AND source_name = 'response_body_bytes';

-- rate_analytics: the 5 columns left NULL by V2 (response_id/comment keep their V2 descriptions; rate
-- is re-described here in plainer thumbs-up/down terms, overriding its V2 text).
UPDATE column_mapping SET description = 'Name of the deployment the rating applies to.'                                                     WHERE table_name = 'rate_analytics' AND source_name = 'deployment';
UPDATE column_mapping SET description = 'Anonymized hash identifying the end user who submitted the rating.'                                WHERE table_name = 'rate_analytics' AND source_name = 'user_hash';
UPDATE column_mapping SET description = 'Identifier of the conversation the rating belongs to.'                                             WHERE table_name = 'rate_analytics' AND source_name = 'chat_id';
UPDATE column_mapping SET description = 'DIAL project the rating belongs to: the project assigned to the API key that submitted the rating. Every DIAL API key is bound to a project, so a project groups keys and attributes their usage.' WHERE table_name = 'rate_analytics' AND source_name = 'project_id';
UPDATE column_mapping SET description = 'Timestamp when the rating request was made.'                                                       WHERE table_name = 'rate_analytics' AND source_name = 'request_time';
UPDATE column_mapping SET description = 'Rating of the response: 1 for a thumbs-up, 0 for a thumbs-down.'                                   WHERE table_name = 'rate_analytics' AND source_name = 'rate';
