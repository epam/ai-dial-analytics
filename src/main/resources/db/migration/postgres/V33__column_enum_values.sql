-- The declared domain of an `enum` column: the closed, ordered set of string values it may hold,
-- materialized as ClickHouse Enum8('v1' = 1, ...) / Enum16(...) with each value's id assigned by its
-- 1-based position here. Order is therefore significant and is never sorted — it is both the id
-- assignment and the order the read surfaces return, and it is what makes ORDER BY on an enum column
-- sort by declared order (say, low/medium/high) rather than alphabetically.
--
-- Nullable, with no backfill: a NULL means "not an enum column", which is exactly how every column
-- written before this migration behaves. Set only for a column of type 'enum' (a non-enum column
-- carrying values is rejected at declaration), and immutable for the life of the column — widening a
-- domain is a drop-and-re-add, since ALTER MODIFY COLUMN on a ClickHouse enum rewrites the column and
-- can fail against rows already stored.
ALTER TABLE column_mapping ADD COLUMN enum_values TEXT[];

-- The Envers mirror must carry every audited attribute or a point-in-time read of a column returns an
-- incomplete snapshot — the same column, added to the same entity's audit table in the same file so
-- the two cannot drift.
ALTER TABLE column_mapping_aud ADD COLUMN enum_values TEXT[];
