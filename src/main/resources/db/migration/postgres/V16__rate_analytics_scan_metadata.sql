-- Scan metadata for rate_analytics (capability enrichment-runtime), the catalog half of the ClickHouse
-- migration V5. V13 gave every source an optional (version_column, identity_column) pair and seeded it
-- for dial_usage_log only; declaring it here makes rate_analytics a second scannable source without a
-- code change, so enrichment rules can target ratings the way they already target usage-log events.
--
-- _ingested_at is the ReplacingMergeTree version (DEFAULT now64(3)): assigned at ingest, monotonic,
-- never backdated — the physical property the scan's exactly-once guarantee rests on. event_id is
-- added by ClickHouse migration V5 as a deterministic hash of the table's full sort key, so it is
-- unique per surviving row and stable across re-ingests. As with dial_usage_log these stay operator
-- configuration via migration, deliberately not settable through the /v1/tables API.
UPDATE table_definition
SET version_column  = '_ingested_at',
    identity_column = 'event_id'
WHERE name = 'rate_analytics';

-- Unlike the other physical-only columns of this table (trace_id, core_span_id, _ingested_at),
-- event_id MUST be registered in the catalog: materializing an enrichment's schema resolves the grain
-- key's type from its source's column_mapping, so an unregistered identity column would leave every
-- enrichment on rate_analytics unbuildable — and a Class-1 rule's target must be keyed on exactly this
-- column. The registration is additive (the eight already-exposed columns are untouched) and carries
-- the same `identifier` tag as its dial_usage_log counterpart, a tag already listed in this table's
-- seeded tag_order.
INSERT INTO column_mapping (table_name, source_name, name, type, element_type, nullable,
                            description, display_name, tag, ordinal)
VALUES ('rate_analytics', 'event_id', 'event_id', 'uuid', NULL, FALSE,
        'Unique identifier of this rate event.', 'Event ID', 'identifier', 8);
