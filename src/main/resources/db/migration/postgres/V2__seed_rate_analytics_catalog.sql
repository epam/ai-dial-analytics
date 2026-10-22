-- Catalog registration for rate_analytics (PostgreSQL). Like dial_usage_log, this table is created by
-- a ClickHouse migration (db/migration/clickhouse/V2__create_rate_analytics.sql) and already exists, so
-- it is seeded here as a SOURCE in status ACTIVE — ADAS must NOT re-CREATE it.
--
-- Only the 8 analytics columns are exposed to the structured query API. The physical columns trace_id,
-- core_span_id, and _ingested_at exist in the ClickHouse table (sort key + ReplacingMergeTree version)
-- but are intentionally omitted from column_mapping, so they are neither projectable, filterable,
-- sortable, nor groupable via /v1/queries. Ordering-key columns need not be exposed, so the ordering
-- key still lists the full physical sort tuple.
INSERT INTO table_definition (name, description, type, source_table, status, partition_column, partition_granularity)
VALUES
    ('rate_analytics', 'DIAL feedback/rating events, keyed on the rated response_id.', 'SOURCE', NULL, 'ACTIVE',
     'request_time', 'DAY');

INSERT INTO table_ordering_key (table_name, ordinal, column_name) VALUES
    ('rate_analytics', 0, 'project_id'),
    ('rate_analytics', 1, 'deployment'),
    ('rate_analytics', 2, 'request_time'),
    ('rate_analytics', 3, 'trace_id'),
    ('rate_analytics', 4, 'core_span_id');

INSERT INTO column_mapping (table_name, source_name, name, type, element_type, nullable, description, ordinal) VALUES
    ('rate_analytics', 'response_id',  'response_id',  'string',    NULL, FALSE, 'Rated response id, from the request body responseId.', 0),
    ('rate_analytics', 'rate',         'rate',         'integer',   NULL, TRUE,  'Normalized rating: numbers pass through, true->1, false->0.', 1),
    ('rate_analytics', 'comment',      'comment',      'string',    NULL, TRUE,  'Optional user-authored feedback comment.', 2),
    ('rate_analytics', 'deployment',   'deployment',   'string',    NULL, FALSE, NULL, 3),
    ('rate_analytics', 'user_hash',    'user_hash',    'string',    NULL, FALSE, NULL, 4),
    ('rate_analytics', 'chat_id',      'chat_id',      'string',    NULL, FALSE, NULL, 5),
    ('rate_analytics', 'project_id',   'project_id',   'string',    NULL, FALSE, NULL, 6),
    ('rate_analytics', 'request_time', 'request_time', 'timestamp', NULL, FALSE, NULL, 7);

-- Tags reuse the dial_usage_log vocabulary (identity / lineage / dimension / payload) so consumers can
-- group columns consistently across tables: request_time is the identity, the join/lineage ids are
-- lineage, the low-cardinality slicers (including the rating value) are dimensions, and the free-text
-- comment is payload like the dial_usage_log body columns.
UPDATE column_mapping SET tag = 'identity'  WHERE table_name = 'rate_analytics' AND source_name IN ('request_time');
UPDATE column_mapping SET tag = 'lineage'   WHERE table_name = 'rate_analytics' AND source_name IN ('response_id', 'project_id', 'chat_id');
UPDATE column_mapping SET tag = 'dimension' WHERE table_name = 'rate_analytics' AND source_name IN ('deployment', 'user_hash', 'rate');
UPDATE column_mapping SET tag = 'payload'   WHERE table_name = 'rate_analytics' AND source_name IN ('comment');
