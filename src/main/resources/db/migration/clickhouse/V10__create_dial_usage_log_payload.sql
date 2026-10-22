-- dial_usage_log_payload — the five verbatim-content columns of dial_usage_log, moved into a table of
-- their own so they can carry a shorter retention (90 days) than the analytic columns (one year).
--
-- A ClickHouse row-expiry TTL is a property of the TABLE, not of a column, so "keep the metrics for a
-- year and the payloads for a quarter" cannot be expressed on one table. This is the second table.
-- It is catalogued (Postgres V38) as a system ENRICHMENT of dial_usage_log keyed on event_id, so the
-- five columns stay reachable from the same entity under their qualified names —
-- "dial_usage_log_payload.request_body" and so on — and the grain LEFT JOIN is emitted only for a
-- query that names one. V11 copies the existing content across and V12 drops the originals.
--
-- WHAT MOVES, AND WHY THESE FIVE. The three body columns are the bulk: request_body, response_body and
-- assembled_response are almost every byte of the table and all of its raw prompt/response content.
-- jwt_claims and request_tags are small by comparison and move for the other reason — they are the two
-- columns that can carry credentials, cookies and forwarded client IPs (see Postgres V3 and V12), and a
-- year is a long time to keep a bearer token. Storage argues for the bodies; disclosure argues for the
-- documents; both argue for a shorter clock.
--
-- The two JSON columns are carried across VERBATIM — type JSON, no codec, exactly as V1 declared them.
-- Re-homing a column and re-encrypting it are separate changes, and doing both at once would make a
-- failure ambiguous. They stay non-nullable because ClickHouse has no Nullable(JSON) (see below).
--
-- The split is three migrations: this one creates the table, V11 copies the existing content inside the
-- 90-day window into it, and V12 drops the five columns from dial_usage_log. Between V11 and V12 the
-- content exists twice — an estimated 45-70 GB compressed on the reference deployment (measured:
-- 2,890,685 rows, ~345 GB uncompressed, essentially all of it the bodies) — and V12 releases it again.
-- An operator whose volume cannot absorb that transient peak applies the three out of band with
-- clickhouse.migrations.enabled=false rather than letting startup run them back to back.
--
-- THE PHYSICAL SHAPE IS FIXED AT CREATE TABLE — engine, sort key and PARTITION BY cannot be ALTERed:
--   * ORDER BY (event_id) — event_id is the only single column identifying a dial_usage_log row (a
--     deterministic hash of (trace_id, core_span_id), already that table's identity_column), and a
--     grain join keys on ONE column, so the parent's five-column sort key cannot serve. Being the sole
--     ORDER BY column also makes it this table's unique key, which is what makes the collapse correct.
--   * ReplacingMergeTree(_updated_at) with _updated_at DEFAULT now64(3) — what TableDdlRenderer emits
--     for every enrichment, so the catalogued table is the shape the rest of the system expects. The
--     caller never sets _updated_at; ClickHouse stamps every write, and reads run with final=1
--     (StructuredQueryExecutor.FINAL_SETTINGS) so a re-ingested row collapses to the latest on read.
--   * PARTITION BY toYYYYMMDD(request_time) — load-bearing, not decorative. ttl_only_drop_parts=1
--     drops a part only once its NEWEST row has expired, so without daily partitions expiry degrades
--     to "never" as merges widen each part's date span. See V9 for the full argument.
--
-- NON-NULLABLE JSON, AND WHY THAT IS SAFE HERE. An enrichment column is read through an outer grain
-- join, so the design premise everywhere else is "an unmatched base row carries NULL". That holds only
-- for a column the catalog declares nullable: join_use_nulls defaults to 0, so an unmatched LEFT JOIN
-- renders a non-nullable right-hand column as its TYPE DEFAULT. For jwt_claims and request_tags that
-- default is the empty document, and — measured on ClickHouse 26.3 — every accessor ADAS exposes for an
-- object field returns over {} exactly what it returns over a document that is present but lacks the
-- key: toJSONString gives '{}', subcolumn access gives NULL, json_extract_array gives [],
-- json_extract_string_ci gives ''. Nothing can tell absence from emptiness, so nothing can be silently
-- corrupted by the default. That is the same exemption array and map columns already have, and
-- EntityFields.absenceIsUnrepresentable now grants it to object columns for the reasons written there.
-- Storing them as Nullable(String) instead is not an option: JSONExtract over a Nullable(String) fails
-- with Code 43 (Nested type Array(String) cannot be inside Nullable type), which would buy the NULL by
-- removing array extraction from the columns entirely.
--
-- Columns beyond the five moved ones, none of them exposed in the catalog: event_id (the grain key,
-- hidden on every enrichment), request_time (drives PARTITION BY and the TTL; the parent publishes its
-- own), trace_id and core_span_id, and the _updated_at system column. The two id columns are NOT
-- decoration: BlobIngestor drops a record for a target when the row that target produced has a blank
-- trace_id, core_span_id or request_time, so without them here EVERY payload row would be dropped. The
-- rule matters on its own terms too — a record with a blank trace_id hashes to a CONSTANT event_id,
-- which on a table keyed by event_id alone would collapse every such record into one row.
-- rate_analytics maps the same two columns for the same reason.
--
-- TTL: 90 days against dial_usage_log's one year. V11 fills the table with the window's worth of
-- history, so the first expiry follows the oldest copied row rather than the deployment date.
-- toDateTime(...) is required around a DateTime64 column or ClickHouse rejects the expression with
-- BAD_TTL_EXPRESSION; TableDdlRenderer applies the same wrapping.
--
-- WARNING — PII: this table holds raw prompt/response content, the producer's merged answer, the
-- caller's JWT claims and the request headers. All five columns are seeded `sensitive`, so only
-- FULL_ADMIN callers see them (or every caller when security.mode=none). The three body columns use
-- the AES_256_GCM_SIV codec, which REQUIRES a server-side encryption key (encryption_codecs in
-- config.d) or CREATE TABLE fails — exactly as V1 and V7 do. The Testcontainers tests and the
-- local_env Docker Compose mount a throwaway dev key; real deployments supply it from their
-- config/secret store.
--
-- Ordering invariant: SchemaValidator refuses at startup a mapping declaring a column the physical
-- table lacks, so this file, V11, V12, mapping/dial-usage-log-payload.yaml and the edited
-- mapping/dial-usage-log.yaml ship together. An operator running with
-- clickhouse.migrations.enabled=false applies all three ClickHouse migrations, in order, BEFORE the
-- new build: V10 because the payload mapping is validated against it, V12 because the edited
-- dial_usage_log mapping no longer declares the dropped columns.
CREATE TABLE IF NOT EXISTS dial_usage_log_payload
(
    event_id           UUID,
    request_time       DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD(1)),
    _updated_at        DateTime64(3) DEFAULT now64(3) CODEC(Delta, ZSTD(1)),
    trace_id           String CODEC(ZSTD(1)),
    core_span_id       String CODEC(ZSTD(1)),
    jwt_claims         JSON,
    request_tags       JSON,
    request_body       Nullable(String) CODEC(ZSTD(3), AES_256_GCM_SIV),
    response_body      Nullable(String) CODEC(ZSTD(3), AES_256_GCM_SIV),
    assembled_response Nullable(String) CODEC(ZSTD(3), AES_256_GCM_SIV)
)
ENGINE = ReplacingMergeTree(_updated_at)
PARTITION BY toYYYYMMDD(request_time)
ORDER BY (event_id)
TTL toDateTime(request_time) + INTERVAL 90 DAY
SETTINGS ttl_only_drop_parts = 1;
