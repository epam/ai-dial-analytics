-- Optional per-table ordering of column tags: a display hint telling discovery/read consumers in what
-- order to render the tag groups a table's columns fall into. Stored denormalized as a nullable
-- Postgres text[] on the table_definition row (NULL = "no ordering set", which preserves the
-- pre-existing behaviour). The list is always read and written whole and never joined or queried
-- per-entry, so an array column beats a separate ordered child table here. It is catalog-only metadata
-- (never part of any ClickHouse DDL), decoupled from the tags currently on columns: entries need not
-- match any column tag, and column add/drop/rename/update never touch it. See docs/column-tags.md.
ALTER TABLE table_definition ADD COLUMN tag_order TEXT[];

-- Seed a sensible taxonomy ordering for the two seeded system tables. They reject PATCH (system-owned),
-- so their ordering can only arrive via migration. Each list contains only tags the table's exposed
-- columns actually carry after V7 (the `system` role tag sits on hidden `_`-prefixed columns, so it is
-- intentionally omitted). Order: keys first, then who/what, then request/response, then the metrics.
UPDATE table_definition
   SET tag_order = ARRAY['identifier', 'dimension', 'principal', 'deployment', 'request', 'response',
                         'token-usage', 'cost', 'performance']
 WHERE name = 'dial_usage_log';

UPDATE table_definition
   SET tag_order = ARRAY['identifier', 'dimension', 'principal', 'deployment', 'request']
 WHERE name = 'rate_analytics';
