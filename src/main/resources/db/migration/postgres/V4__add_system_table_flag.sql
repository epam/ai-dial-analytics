-- System-owned tables: mark a table definition as owned/managed by ADAS itself so it is protected
-- from modification via the API. Server-controlled catalog metadata (never accepted or changed by any
-- /v1/tables request), mirroring the V3 `sensitive` column-flag pattern one level up on the table.
-- Defaults to FALSE so every existing and future definition stays user-modifiable until a migration
-- says otherwise. A `system` table stays fully readable and queryable; only the modifying endpoints
-- (PUT, PATCH schema, DELETE, POST rows) reject it.
ALTER TABLE table_definition ADD COLUMN system BOOLEAN NOT NULL DEFAULT FALSE;

-- The two seeded source tables are materialized out of band (ClickHouse migrations) and populated by
-- the data-sync ingest job writing straight to ClickHouse; the API must never modify them.
UPDATE table_definition SET system = TRUE WHERE name IN ('dial_usage_log', 'rate_analytics');
