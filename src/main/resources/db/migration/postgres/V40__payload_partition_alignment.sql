-- Declares dial_usage_log_payload's partition column in the catalog, so a keyed grain join can bound the
-- payload side to the partitions that can hold the row it is looking for.
--
-- V38 left partition_column/partition_granularity unset on this definition, and said why: "the API
-- surfaces partition_by for a SOURCE only (TableMapper), so a value here would be stored and never read.
-- The physical PARTITION BY is owned by V10." Both halves of that were true when it was written. The
-- reader now exists — CatalogQueryService reads the pair to build a partition-aligned EnrichmentJoin — so
-- the value is set here rather than staying a fact only the ClickHouse DDL knows.
--
-- WHAT THIS FIXES. dial_usage_log_payload is ORDER BY (event_id) and PARTITION BY toYYYYMMDD(request_time).
-- event_id is a deterministic hash of (trace_id, core_span_id), so it is distributed uniformly across every
-- daily partition: a lookup for one event_id gives the primary-key search no basis to exclude any part, and
-- it lands one candidate granule in each. Adaptive granularity sizes a granule by bytes, so on the
-- reference deployment (140 GiB over 2.5 M rows, ~57 KB/row, 271 parts) that single-row lookup reads ~1.2
-- GiB per heavy column projected. Two heavy columns exceed a 2 GiB ceiling to return one row.
--
-- The value that excludes the other partitions is request_time, and every path that reads a payload row
-- already has it: dial_usage_log's ordering key is (project_id, deployment, request_time, trace_id,
-- core_span_id), so the tuple identifying a row pins its time. Before V10/V11 the bodies lived on
-- dial_usage_log itself and that predicate pruned them for free. The split moved the bodies to a table the
-- same predicate no longer names, which is what made every body point-read cost gigabytes. This row is how
-- ADAS learns to apply the value it holds to the table that needs it.
--
-- THE INVARIANT BEING DECLARED. CatalogQueryService treats a partition column shared by name with the
-- source as a claim that, for a given grain key, both tables hold the same value in it. Here that is true by
-- construction: BlobIngestor writes the dial_usage_log row and the dial_usage_log_payload row from the same
-- usage-log record, taking request_time from the same field, and a re-ingest of that record rewrites both
-- with the same value. It is not a coincidence of naming — mapping/dial-usage-log-payload.yaml and
-- mapping/dial-usage-log.yaml read the one source field.
--
-- Nothing verifies the claim at query time, and it cannot be made to: a partition restriction that excludes
-- the partition actually holding the row does not fail, it yields the column's type default through the
-- outer join, which is indistinguishable from a row that legitimately has no enrichment. That is why the
-- claim is carried by the catalog for a table whose population guarantees it, and why the API still refuses
-- partition_by on an enrichment (TableColumnRules / CatalogSchemaWriter): an API-created enrichment is
-- populated by arbitrary row writes, so no equivalent guarantee exists to declare.
--
-- NOT A PHYSICAL CHANGE. V10 already created the table with this PARTITION BY; ALTER cannot change one, and
-- nothing here tries to. This migration records what the physical table already is, the way V38's
-- ordering_key line records the physical ORDER BY. granularity is DAY because the physical expression is
-- toYYYYMMDD(request_time) — though nothing reads the granularity for this purpose: the restriction emitted
-- is a set of request_time values, which prunes correctly whatever expression the column is partitioned by.
--
-- IDEMPOTENT AND NARROW: one UPDATE against one seeded system row, guarded on the definition existing. No
-- audit_trail row, for V38's reason — a raw SQL UPDATE fires no Hibernate event.
UPDATE table_definition
   SET partition_column      = 'request_time',
       partition_granularity = 'DAY'
 WHERE name = 'dial_usage_log_payload';

-- Fails the migration rather than leaving the alignment half-declared: without the definition row the
-- UPDATE above is a silent no-op, and the only symptom would be that body reads stay expensive.
DO $$
DECLARE
    aligned INTEGER;
BEGIN
    SELECT count(*) INTO aligned
      FROM table_definition payload
      JOIN table_definition source ON source.name = payload.source_table
     WHERE payload.name = 'dial_usage_log_payload'
       AND payload.partition_column = 'request_time'
       AND source.partition_column = payload.partition_column;
    IF aligned <> 1 THEN
        RAISE EXCEPTION 'V40: dial_usage_log_payload is not partition-aligned with its source, found %', aligned;
    END IF;
END $$;
