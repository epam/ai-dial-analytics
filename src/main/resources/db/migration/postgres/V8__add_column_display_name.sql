-- Column-level display metadata: a short, human-friendly display name (e.g. "Total money spend" for
-- the column total_money) used to render the field beautifully in consumer UIs. Catalog-only metadata
-- (never part of the ClickHouse DDL, mirroring `description`/`tag`); nullable so every existing column
-- keeps a null display name until one is explicitly set. Editable later via
-- PATCH /v1/tables/{name}/schema (set_display_name).
ALTER TABLE column_mapping ADD COLUMN display_name TEXT;
