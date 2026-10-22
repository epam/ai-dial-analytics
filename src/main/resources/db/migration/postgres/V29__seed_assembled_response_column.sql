-- Register dial_usage_log.assembled_response in the catalog, seeded sensitive and heavy.
--
-- The physical column is added by db/migration/clickhouse/V7__dial_usage_log_assembled_response.sql;
-- this migration only registers it, so it issues no ClickHouse ALTER — `sensitive`, `heavy`, `tag`,
-- `display_name` and `description` are catalog-only metadata and never appear in any DDL. dial_usage_log
-- is a seeded SOURCE in status ACTIVE, so its catalog row can only be edited by migration
-- (PATCH /v1/tables/{name}/schema rejects a system-owned table).
--
-- ordinal 38 puts it last, matching the physical table: ADD COLUMN appends, so it follows
-- response_body_bytes (ordinal 37) rather than sitting beside response_body. Catalog order is what a
-- wildcard projection and schema discovery render in, so it has to track the DDL.
--
-- tag 'response' groups it with the other response-side columns (V7__retag_column_semantic_groups.sql),
-- and 'response' is already present in dial_usage_log's tag_order (V10__add_table_tag_order.sql), so no
-- ordering change is needed.
INSERT INTO column_mapping (table_name, source_name, name, type, element_type, nullable, description,
                            display_name, tag, sensitive, heavy, ordinal)
VALUES ('dial_usage_log', 'assembled_response', 'assembled_response', 'string', NULL, TRUE,
        'The producer''s merged JSON response message, stored verbatim: the readable form of the '
            || 'answer, where response_body on a streamed call is the raw chunk transcript. ADAS never '
            || 'parses or normalizes it. NULL means the producer reported no assembled response, and '
            || 'rows ingested before the column existed read NULL until they are re-ingested. Parse '
            || 'defensively: a minority of rows, current ones included, carry a value that is not JSON '
            || '(some an SSE transcript), and ADAS stores it as reported rather than repairing it.',
        'Assembled response', 'response', TRUE, TRUE, 38);

-- Sensitive on the same grounds as request_body/response_body (V3, V12): it stores the model's answer
-- verbatim — user-visible content — which restricts it to FULL_ADMIN on every query surface. That it is
-- a merged rather than a raw payload changes its shape, not its sensitivity. Heavy on the same grounds
-- as those two (V18): unbounded per-row size, so a wildcard projection leaves it out and it comes back
-- in full when named. Both are reversible in principle via the schema patch, but not on this table.
