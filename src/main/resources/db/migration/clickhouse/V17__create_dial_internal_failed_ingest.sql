-- Failure ledger of the ingest paths; see openspec/specs/data-sync-failures/spec.md.
CREATE TABLE IF NOT EXISTS _dial_internal_failed_ingest
(
    kind               LowCardinality(String),
    subject            String,
    day                Date,
    blob_last_modified DateTime64(3, 'UTC'),
    event              LowCardinality(String),
    path               LowCardinality(String),
    mode               LowCardinality(String),
    backfill_run_id    String,
    failure_class      LowCardinality(String),
    reason             LowCardinality(String),
    message            String,
    event_at           DateTime64(3, 'UTC')
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(day)
ORDER BY (kind, subject, event_at)
TTL day + INTERVAL 13 MONTH
SETTINGS ttl_only_drop_parts = 1;
