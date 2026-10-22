-- Write-discipline facet (ADR 0006): how a write to an already-present key behaves. `APPEND` keeps
-- every row (a plain MergeTree, the behaviour every table had until now); `UPSERT_BY_KEY` collapses to
-- the latest row per ordering key (a ReplacingMergeTree(_updated_at)), which is what a rollup target
-- needs so that recomputing a group key replaces its row instead of adding a second one.
--
-- The facet is IMMUTABLE by design: it selects the ClickHouse engine, and an engine is fixed by
-- CREATE TABLE and cannot be ALTERed. It is therefore declared once, at POST /v1/tables, next to the
-- other identity fields, and rejected on every mutating endpoint. Defaulting to 'APPEND' makes the
-- column additive: every existing definition reads back as append and re-materializes to identical DDL.
--
-- The facet is a TABLE facet, decoupled from `type`: it says how a write behaves, not what the table
-- is. It is caller-settable only on a source — an enrichment is always keyed and collapsed on its
-- grain, so its discipline is implied by its kind and the stored value is never read for it.
ALTER TABLE table_definition
    ADD COLUMN write_mode TEXT NOT NULL DEFAULT 'APPEND'
        CHECK (write_mode IN ('APPEND', 'UPSERT_BY_KEY'));

-- Drop the `aggregation` table kind (ADR 0006): aggregation is not a table kind but a PIPELINE kind
-- whose output is an ordinary source (a rollup is now `type=SOURCE, write_mode=UPSERT_BY_KEY`, keyed on
-- the pipeline's group_by). The kind was accepted for forward compatibility and materialized, queried,
-- and joined nothing, so an AGGREGATION row can only ever be a PENDING shell with no ClickHouse table
-- and no rows behind it. Deleting any such shell before narrowing the CHECK keeps the constraint from
-- failing on a dirty database and taking startup down with it; the row's children (column_mapping,
-- table_ordering_key, table_grant) go with it via ON DELETE CASCADE.
DELETE FROM table_definition WHERE type = 'AGGREGATION';

ALTER TABLE table_definition
    DROP CONSTRAINT table_definition_type_check,
    ADD CONSTRAINT table_definition_type_check CHECK (type IN ('SOURCE', 'ENRICHMENT'));
