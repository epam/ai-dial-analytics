-- rate_analytics — target table for DIAL feedback/rating events, populated by the same data-sync
-- ingest path that fills dial_usage_log but only for records matching the rate condition
-- (request.uri ending in /rate AND request.method = POST). A matching record produces BOTH its
-- dial_usage_log row and a rate_analytics row; dial_usage_log behavior is unchanged.
--
-- Unlike dial_usage_log, response_id here is taken from the client-supplied `responseId` in the
-- request body (a rate call carries no response of its own), so a rating can be joined back to the
-- rated completion's response_id in dial_usage_log.
--
-- Keep-all-ratings: every distinct rate event is its own row. The ReplacingMergeTree engine collapses
-- only rows sharing the full ORDER BY sort key (project_id, deployment, request_time, trace_id,
-- core_span_id) within a daily partition — so two separate ratings of one response (distinct spans)
-- are both kept, while a re-ingested identical log line collapses on the greatest _ingested_at.
--
-- The comment column holds user-authored free text (potential PII) and uses the AES_256_GCM_SIV
-- codec, which REQUIRES a server-side encryption key (encryption_codecs in config.d) or CREATE TABLE
-- fails — the same prerequisite as the dial_usage_log body columns.
--
-- trace_id, core_span_id, and _ingested_at are physical only (sort key + dedup version); they are
-- intentionally NOT registered in the query catalog and are invisible to the structured query API.
CREATE TABLE IF NOT EXISTS rate_analytics
(
    request_time  DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD(1)),
    _ingested_at  DateTime64(3, 'UTC') DEFAULT now64(3) CODEC(Delta, ZSTD(1)),
    project_id    LowCardinality(String),
    trace_id      String CODEC(ZSTD(1)),
    core_span_id  String CODEC(ZSTD(1)),
    deployment    LowCardinality(String),
    user_hash     String CODEC(ZSTD(1)),
    chat_id       String CODEC(ZSTD(1)),
    response_id   String CODEC(ZSTD(1)),
    rate          Nullable(Int8) CODEC(ZSTD(1)),
    comment       Nullable(String) CODEC(ZSTD(3), AES_256_GCM_SIV),

    INDEX idx_response_id response_id TYPE bloom_filter(0.01) GRANULARITY 1,
    INDEX idx_user_hash   user_hash   TYPE bloom_filter(0.01) GRANULARITY 1
)
ENGINE = ReplacingMergeTree(_ingested_at)
PARTITION BY toYYYYMMDD(request_time)
ORDER BY (project_id, deployment, request_time, trace_id, core_span_id)
TTL toDateTime(request_time) + INTERVAL 1 YEAR;
