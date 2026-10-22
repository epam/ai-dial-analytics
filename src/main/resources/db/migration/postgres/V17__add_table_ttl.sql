-- Optional per-table row-expiry TTL, mirrored from the ClickHouse table's TTL clause
-- (TTL <column> + INTERVAL <interval> <unit>). Stored structured (column + interval + unit, never a
-- free-form expression) on the table_definition row: ttl_column is the physical source_name of a
-- non-nullable date/timestamp column of the table. NULL on all three = "no TTL". The seeded system
-- tables' migration-defined ClickHouse TTLs are deliberately NOT mirrored here: the API cannot modify
-- system tables, and their retention stays owned by the ClickHouse migration files.
ALTER TABLE table_definition
    ADD COLUMN ttl_column   TEXT,
    ADD COLUMN ttl_interval INTEGER CHECK (ttl_interval > 0),
    ADD COLUMN ttl_unit     TEXT CHECK (ttl_unit IN ('HOUR', 'DAY', 'WEEK', 'MONTH', 'YEAR'));

-- The three TTL fields are set together or not at all (same pattern as partition_by_both_or_neither).
ALTER TABLE table_definition
    ADD CONSTRAINT ttl_all_or_none
        CHECK (((ttl_column IS NULL) AND (ttl_interval IS NULL) AND (ttl_unit IS NULL))
            OR ((ttl_column IS NOT NULL) AND (ttl_interval IS NOT NULL) AND (ttl_unit IS NOT NULL)));
