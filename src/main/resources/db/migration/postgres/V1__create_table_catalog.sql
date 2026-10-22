-- Table-management catalog (PostgreSQL). Source of truth for which ClickHouse tables exist and how
-- their columns are exposed to the structured query DSL. Replaces the throwaway `metadata` table.
-- Provisioned on startup by Boot's Flyway auto-configuration, scoped to this postgres-only location.

-- A table definition: name == ClickHouse table name == {table_id} in the API path.
--   type   ∈ source | enrichment | aggregation
--   status ∈ PENDING (intent recorded, ClickHouse CREATE not yet confirmed) | ACTIVE (ClickHouse
--          table confirmed created) | FAILED (ClickHouse CREATE rejected or unconfirmed)
--   source_table is set iff type = enrichment (the source it enriches).
--   partition_column/partition_granularity are an optional source PARTITION BY (e.g. request_time +
--   DAY → PARTITION BY toYYYYMMDD(request_time)); both set together, only on sources.
CREATE TABLE table_definition (
    name                  TEXT PRIMARY KEY,
    description           TEXT,
    type                  TEXT NOT NULL CHECK (type IN ('SOURCE', 'ENRICHMENT', 'AGGREGATION')),
    source_table          TEXT REFERENCES table_definition (name),
    status                TEXT NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'FAILED')),
    partition_column      TEXT,
    partition_granularity TEXT CHECK (partition_granularity IN ('DAY', 'MONTH', 'YEAR')),
    -- An enrichment must name a source; non-enrichments must not.
    CONSTRAINT source_table_iff_enrichment
        CHECK ((type = 'ENRICHMENT') = (source_table IS NOT NULL)),
    -- partition_column and partition_granularity are set together or not at all.
    CONSTRAINT partition_by_both_or_neither
        CHECK ((partition_column IS NULL) = (partition_granularity IS NULL))
);

-- One row per EXPOSED column. The foreign-key (join) column of an enrichment is NOT stored here —
-- it is added physically at materialization and recorded in enrichment_grain, and stays hidden from
-- the flat query schema. `name` is the alias clients see; `source_name` is the physical column.
CREATE TABLE column_mapping (
    table_name   TEXT    NOT NULL REFERENCES table_definition (name) ON DELETE CASCADE,
    source_name  TEXT    NOT NULL,
    name         TEXT    NOT NULL,
    type         TEXT    NOT NULL,
    element_type TEXT,
    nullable     BOOLEAN NOT NULL DEFAULT FALSE,
    description  TEXT,
    -- Optional free-form label (≤64 chars) to help consumers identify a column among many; metadata
    -- only, never part of the ClickHouse DDL. Duplicates within a table are allowed.
    tag          TEXT,
    ordinal      INT     NOT NULL,
    PRIMARY KEY (table_name, source_name),
    CONSTRAINT exposed_name_unique_per_table UNIQUE (table_name, name)
);

-- MergeTree ORDER BY columns (physical source_names), ordered. One row per key column.
CREATE TABLE table_ordering_key (
    table_name  TEXT NOT NULL REFERENCES table_definition (name) ON DELETE CASCADE,
    ordinal     INT  NOT NULL,
    column_name TEXT NOT NULL,
    PRIMARY KEY (table_name, ordinal)
);

-- The grain of an enrichment: the column (same name on the enrichment and its source) the enrichment
-- is keyed on and LEFT-joined by (source.grain_key = enrichment.grain_key). The grain_key may be any
-- declared column of the source (not necessarily an ordering-key column; a non-ordering-key grain key
-- is permitted but full-scans the source side of the join). The grain_key is a hidden
-- physical column on the enrichment, not exposed in the flat query schema. v1 cardinality is
-- ZERO_OR_ONE (one row per key), enforced physically by the enrichment's ReplacingMergeTree engine.
CREATE TABLE enrichment_grain (
    table_name  TEXT PRIMARY KEY REFERENCES table_definition (name) ON DELETE CASCADE,
    grain_key   TEXT NOT NULL,
    cardinality TEXT NOT NULL CHECK (cardinality IN ('ZERO_OR_ONE'))
);

-- ---------------------------------------------------------------------------------------------------
-- Backfill: dial_usage_log was the hardcoded schema (DialUsageLogSchema.java) and already exists in
-- ClickHouse (db/migration/clickhouse/V1__create_dial_usage_log.sql), so it is seeded as a source in
-- status ACTIVE — ADAS must NOT re-CREATE it. Column mapping mirrors the former Java schema in DDL
-- order; name == source_name (no aliasing). The ordering key mirrors the ClickHouse ORDER BY, and the
-- partition_by mirrors its PARTITION BY toYYYYMMDD(request_time). This is the one catalog row exempt
-- from the API validator: it keeps its reserved `_`-prefixed system columns (_ingested_at,
-- _source_file) and its array column (execution_path), neither of which is user-declarable.
-- ---------------------------------------------------------------------------------------------------
INSERT INTO table_definition (name, description, type, source_table, status, partition_column, partition_granularity)
VALUES
    ('dial_usage_log', 'DIAL usage log events ingested from blob storage.', 'SOURCE', NULL, 'ACTIVE',
     'request_time', 'DAY');

INSERT INTO table_ordering_key (table_name, ordinal, column_name) VALUES
    ('dial_usage_log', 0, 'project_id'),
    ('dial_usage_log', 1, 'deployment'),
    ('dial_usage_log', 2, 'request_time'),
    ('dial_usage_log', 3, 'trace_id'),
    ('dial_usage_log', 4, 'core_span_id');

INSERT INTO column_mapping (table_name, source_name, name, type, element_type, description, ordinal) VALUES
    ('dial_usage_log', 'event_id',                'event_id',                'uuid',      NULL,     NULL, 0),
    ('dial_usage_log', 'event_kind',              'event_kind',              'string',    NULL,     NULL, 1),
    ('dial_usage_log', 'request_time',            'request_time',            'timestamp', NULL,     NULL, 2),
    ('dial_usage_log', '_ingested_at',            '_ingested_at',            'timestamp', NULL,     NULL, 3),
    ('dial_usage_log', '_source_file',            '_source_file',            'string',    NULL,     NULL, 4),
    ('dial_usage_log', 'project_id',              'project_id',              'string',    NULL,     NULL, 5),
    ('dial_usage_log', 'chat_id',                 'chat_id',                 'string',    NULL,     NULL, 6),
    ('dial_usage_log', 'trace_id',                'trace_id',                'string',    NULL,     NULL, 7),
    ('dial_usage_log', 'core_span_id',            'core_span_id',            'string',    NULL,     NULL, 8),
    ('dial_usage_log', 'core_parent_span_id',     'core_parent_span_id',     'string',    NULL,     NULL, 9),
    ('dial_usage_log', 'execution_path',          'execution_path',          'array',     'string', NULL, 10),
    ('dial_usage_log', 'parent_deployment',       'parent_deployment',       'string',    NULL,     NULL, 11),
    ('dial_usage_log', 'deployment',              'deployment',              'string',    NULL,     NULL, 12),
    ('dial_usage_log', 'response_upstream_uri',   'response_upstream_uri',   'string',    NULL,     NULL, 13),
    ('dial_usage_log', 'user_hash',               'user_hash',               'string',    NULL,     NULL, 14),
    ('dial_usage_log', 'response_id',             'response_id',             'string',    NULL,     NULL, 15),
    ('dial_usage_log', 'prompt_tokens',           'prompt_tokens',           'long',      NULL,     NULL, 16),
    ('dial_usage_log', 'completion_tokens',       'completion_tokens',       'long',      NULL,     NULL, 17),
    ('dial_usage_log', 'total_tokens',            'total_tokens',            'long',      NULL,     NULL, 18),
    ('dial_usage_log', 'cached_prompt_tokens',    'cached_prompt_tokens',    'long',      NULL,     NULL, 19),
    ('dial_usage_log', 'cache_creation_tokens',   'cache_creation_tokens',   'long',      NULL,     NULL, 20),
    ('dial_usage_log', 'reasoning_tokens',        'reasoning_tokens',        'long',      NULL,     NULL, 21),
    ('dial_usage_log', 'number_request_messages', 'number_request_messages', 'long',      NULL,     NULL, 22),
    ('dial_usage_log', 'total_price',             'total_price',             'decimal',   NULL,     NULL, 23),
    ('dial_usage_log', 'deployment_price',        'deployment_price',        'decimal',   NULL,     NULL, 24),
    ('dial_usage_log', 'operation_duration_ms',   'operation_duration_ms',   'long',      NULL,     NULL, 25),
    ('dial_usage_log', 'response_status',         'response_status',         'integer',   NULL,     NULL, 26),
    ('dial_usage_log', 'success',                 'success',                 'boolean',   NULL,     NULL, 27),
    ('dial_usage_log', 'request_method',          'request_method',          'string',    NULL,     NULL, 28),
    ('dial_usage_log', 'request_uri',             'request_uri',             'string',    NULL,     NULL, 29),
    ('dial_usage_log', 'mcp_method',              'mcp_method',              'string',    NULL,     NULL, 30),
    ('dial_usage_log', 'mcp_tool_call_name',      'mcp_tool_call_name',      'string',    NULL,     NULL, 31),
    ('dial_usage_log', 'jwt_claims',              'jwt_claims',              'object',    NULL,     NULL, 32),
    ('dial_usage_log', 'request_tags',            'request_tags',            'object',    NULL,     NULL, 33),
    ('dial_usage_log', 'request_body',            'request_body',            'string',    NULL,     NULL, 34),
    ('dial_usage_log', 'response_body',           'response_body',           'string',    NULL,     NULL, 35),
    ('dial_usage_log', 'request_body_bytes',      'request_body_bytes',      'long',      NULL,     NULL, 36),
    ('dial_usage_log', 'response_body_bytes',     'response_body_bytes',     'long',      NULL,     NULL, 37);

-- Default identifying tags: group the 38 dial_usage_log columns by function so consumers can locate
-- columns among many. Tags are metadata only (never part of the ClickHouse DDL) and may be changed or
-- cleared later via PATCH /v1/tables/{name}/schema (retag).
UPDATE column_mapping SET tag = 'identity'    WHERE table_name = 'dial_usage_log' AND source_name IN ('event_id', 'event_kind', 'request_time');
UPDATE column_mapping SET tag = 'lineage'     WHERE table_name = 'dial_usage_log' AND source_name IN ('project_id', 'chat_id', 'trace_id', 'core_span_id', 'core_parent_span_id', 'execution_path', 'response_id');
UPDATE column_mapping SET tag = 'dimension'   WHERE table_name = 'dial_usage_log' AND source_name IN ('deployment', 'parent_deployment', 'user_hash');
UPDATE column_mapping SET tag = 'token-usage' WHERE table_name = 'dial_usage_log' AND source_name IN ('prompt_tokens', 'completion_tokens', 'total_tokens', 'cached_prompt_tokens', 'cache_creation_tokens', 'reasoning_tokens', 'number_request_messages');
UPDATE column_mapping SET tag = 'cost'        WHERE table_name = 'dial_usage_log' AND source_name IN ('total_price', 'deployment_price');
UPDATE column_mapping SET tag = 'performance' WHERE table_name = 'dial_usage_log' AND source_name IN ('operation_duration_ms');
UPDATE column_mapping SET tag = 'response'    WHERE table_name = 'dial_usage_log' AND source_name IN ('response_status', 'success', 'response_upstream_uri');
UPDATE column_mapping SET tag = 'request'     WHERE table_name = 'dial_usage_log' AND source_name IN ('request_method', 'request_uri', 'mcp_method', 'mcp_tool_call_name', 'jwt_claims', 'request_tags');
UPDATE column_mapping SET tag = 'payload'     WHERE table_name = 'dial_usage_log' AND source_name IN ('request_body', 'response_body', 'request_body_bytes', 'response_body_bytes');
UPDATE column_mapping SET tag = 'system'      WHERE table_name = 'dial_usage_log' AND source_name IN ('_ingested_at', '_source_file');
