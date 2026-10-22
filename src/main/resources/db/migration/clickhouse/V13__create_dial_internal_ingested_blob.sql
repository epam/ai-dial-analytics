-- _dial_internal_ingested_blob — the ingest ledger: one row per log blob whose rows have all been written,
-- so the reconciliation run, the poller and a default backfill skip it instead of writing it again. The
-- behaviour is specified in openspec/specs/data-sync "Ingested blobs are recorded in a persisted ledger".
--
--   * Same database as the ingest tables on purpose: a restore or re-creation takes the ledger with the
--     rows it vouches for, so it can never claim rows that are gone.
--   * ReplacingMergeTree(ingested_at), ORDER BY blob_key — recording a blob again supersedes its entry;
--     readers take argMax(blob_last_modified, ingested_at) per key, so they need no FINAL and see the
--     newest entry even across monthly partitions.
--   * TTL 13 months on blob_last_modified, monthly partitions, ttl_only_drop_parts — outlives the longest
--     ingest-table TTL (one year), and expires a whole month at a time with no prune code.
--   * The _dial_internal_ prefix is reserved: no table API may create such a name (TableCatalogService),
--     so a failed catalog create's compensating DROP can never reach this table. Never catalogued, so no
--     query surface reads it.
CREATE TABLE IF NOT EXISTS _dial_internal_ingested_blob
(
    blob_key           String,
    blob_last_modified DateTime64(3, 'UTC'),
    ingested_at        DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(blob_last_modified)
ORDER BY blob_key
TTL toDateTime(blob_last_modified) + INTERVAL 13 MONTH
SETTINGS ttl_only_drop_parts = 1;
