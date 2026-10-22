-- Fold enrichment_grain and table_ordering_key into table_definition.
--
-- Neither table had an identity of its own: enrichment_grain's primary key WAS table_name (a strict
-- 1:1 holding two columns, read only ever by findById), and table_ordering_key was an ordered list of
-- column names always read whole and never queried independently. Both become columns on the row they
-- belonged to, and column_mapping deliberately stays a table — it has genuine per-row identity and two
-- uniqueness constraints no array could express.
--
-- cardinality is NOT carried over. It had one legal value on every row that had one (the service layer
-- rejects anything but ZERO_OR_ONE at the door and then writes that literal), so the stored value was a
-- function of "does this row have a grain key" and carried no information. It is derived at the mapper
-- instead; the API still emits and accepts it unchanged.
--
-- Forward-only: the prior application version reads tables this migration drops, so rollback is a
-- forward fix or a database restore, not a redeploy.

ALTER TABLE table_definition
    ADD COLUMN grain_key    TEXT,
    ADD COLUMN ordering_key TEXT[] NOT NULL DEFAULT '{}';

-- The grain join is 1:1 on the primary key, so it cannot fan out.
UPDATE table_definition d
SET grain_key = g.grain_key
FROM enrichment_grain g
WHERE g.table_name = d.name;

-- ORDER BY ordinal INSIDE the aggregate is load-bearing: without it PostgreSQL gives no ordering
-- guarantee, and a permuted array here silently reorders the MergeTree ORDER BY that every downstream
-- CREATE TABLE is rendered from — a corruption that would surface much later as wrong query plans
-- rather than as an error.
UPDATE table_definition d
SET ordering_key = k.cols
FROM (SELECT table_name, array_agg(column_name ORDER BY ordinal) AS cols
      FROM table_ordering_key
      GROUP BY table_name) k
WHERE k.table_name = d.name;

-- enrichment_grain's foreign key pointed at table_definition(name) and could not see the row's type,
-- so nothing at the database level stopped a grain row existing for a SOURCE. As a column on the row
-- itself, the type is right there.
--
-- Deliberately not biconditional: an enrichment is created as a grain-less shell at POST /v1/tables and
-- gets its grain only at POST /v1/tables/{name}/schema, so a PENDING enrichment legitimately has none.
-- This constrains the direction that is always true and leaves the lifecycle-dependent direction to the
-- service layer, which already owns it.
ALTER TABLE table_definition
    ADD CONSTRAINT grain_key_only_on_enrichment
        CHECK (grain_key IS NULL OR type = 'ENRICHMENT');

DROP TABLE enrichment_grain;
DROP TABLE table_ordering_key;
