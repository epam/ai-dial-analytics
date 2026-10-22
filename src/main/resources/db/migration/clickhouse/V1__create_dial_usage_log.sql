-- dial_usage_log — target table for the data-sync ingest path and the structured query API.
--
-- This is both the service-run provisioning of the schema and the canonical operator DDL: when
-- migrations are disabled, apply this file out of band. The columns are carried directly from the source
-- record, some are calculated at ingest. The body columns (request_body, response_body) use the
-- AES_256_GCM_SIV codec, which REQUIRES a server-side encryption key (encryption_codecs in config.d) or
-- CREATE TABLE fails. The jwt_claims/request_tags columns use ClickHouse's JSON type, which is generally
-- available on the supported server version (ClickHouse 25.3 LTS or newer).
-- The Testcontainers tests and the local_env Docker Compose mount a throwaway dev encryption
-- key; real deployments supply it from their config/secret store.
--
-- Dedup is by the ReplacingMergeTree engine, which collapses re-ingested records that share the full
-- ORDER BY sort key (project_id, deployment, request_time, trace_id, core_span_id) AND live in the same
-- partition (partitions are daily, PARTITION BY toYYYYMMDD(request_time)), keeping the greatest
-- _ingested_at on background merge. There is no PRIMARY KEY / two-column key — the whole sort tuple is
-- the dedup key. For this to be sound every column in that tuple must be deterministic for a given span:
-- request_time is, because the ingest pipeline DROPS any record whose request.time is absent (an absent
-- timestamp would otherwise land in the 1970-01-01 partition and could never collapse with the real
-- row). _source_file is ingest provenance only, not part of the key. _ingested_at (the version column)
-- defaults to now() on insert and is millisecond-precision so re-ingests in the same second still
-- compare distinctly. event_id is a deterministic hash of (trace_id, core_span_id).
--
-- WARNING — PII / heavy content: request_body and response_body capture the raw prompt/response
-- payloads, so user content IS persisted here (AES-256 encrypted at rest). Gate these columns with
-- column-level access controls; the TTL below bounds retention.
CREATE TABLE IF NOT EXISTS dial_usage_log
(
    event_id                UUID,
    event_kind              LowCardinality(String),
    request_time            DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD(1)),
    _ingested_at            DateTime64(3, 'UTC') DEFAULT now64(3) CODEC(Delta, ZSTD(1)),
    _source_file            LowCardinality(String),
    project_id              LowCardinality(String),
    chat_id                 String CODEC(ZSTD(1)),
    trace_id                String CODEC(ZSTD(1)),
    core_span_id            String CODEC(ZSTD(1)),
    core_parent_span_id     Nullable(String) CODEC(ZSTD(1)),
    execution_path          Array(LowCardinality(String)),
    parent_deployment       LowCardinality(Nullable(String)),
    deployment              LowCardinality(String),
    response_upstream_uri   LowCardinality(Nullable(String)),
    user_hash               String CODEC(ZSTD(1)),
    response_id             String CODEC(ZSTD(1)),
    prompt_tokens           Nullable(UInt32) CODEC(T64, ZSTD(1)),
    completion_tokens       Nullable(UInt32) CODEC(T64, ZSTD(1)),
    total_tokens            Nullable(UInt32) CODEC(T64, ZSTD(1)),
    cached_prompt_tokens    Nullable(UInt32) CODEC(T64, ZSTD(1)),
    cache_creation_tokens   UInt32 CODEC(T64, ZSTD(1)),
    reasoning_tokens        UInt32 CODEC(T64, ZSTD(1)),
    number_request_messages UInt32 CODEC(T64, ZSTD(1)),
    total_price             Nullable(Decimal(38, 12)),
    deployment_price        Nullable(Decimal(38, 12)),
    operation_duration_ms   UInt32,
    response_status         UInt16,
    success                 Bool,
    request_method          LowCardinality(String),
    request_uri             String,
    mcp_method              LowCardinality(String),
    mcp_tool_call_name      LowCardinality(String),
    jwt_claims              JSON,
    request_tags            JSON,
    request_body            Nullable(String) CODEC(ZSTD(3), AES_256_GCM_SIV),
    response_body           Nullable(String) CODEC(ZSTD(3), AES_256_GCM_SIV),
    request_body_bytes      UInt32 CODEC(T64, ZSTD(1)),
    response_body_bytes     UInt32 CODEC(T64, ZSTD(1)),

    INDEX idx_trace_id   trace_id     TYPE bloom_filter(0.01) GRANULARITY 1,
    INDEX idx_span_id    core_span_id TYPE bloom_filter(0.01) GRANULARITY 1,
    INDEX idx_chat_id    chat_id      TYPE bloom_filter(0.01) GRANULARITY 1,
    INDEX idx_user_hash  user_hash    TYPE bloom_filter(0.01) GRANULARITY 1
)
ENGINE = ReplacingMergeTree(_ingested_at)
PARTITION BY toYYYYMMDD(request_time)
ORDER BY (project_id, deployment, request_time, trace_id, core_span_id)
TTL toDateTime(request_time) + INTERVAL 1 YEAR;
