-- `dial_usage_log.cache_creation_tokens` becomes `Nullable(UInt32)` in the paired ClickHouse migration
-- V6, so the catalog's `nullable` flag has to follow it: the flag SHALL agree with the column's current
-- physical type, not the type the table was created with. V21 aligned the eleven columns V1 declared
-- Nullable; this is the twelfth, made nullable a release later.
--
-- The alignment matters for the same two consumers V21 names: schema discovery publishes the flag (a
-- consumer told the column cannot be null would now be reading nulls from it), and the aggregate
-- pipeline's nullable-measure rule reasons from it — a `sum(cache_creation_tokens)` into a non-nullable
-- rollup column must be rejected, which an understated flag would skip silently.
--
-- Nullability is fixed at DDL, so this is a `column_mapping` update only; it issues no ALTER and cannot.
UPDATE column_mapping
SET nullable = TRUE
WHERE table_name = 'dial_usage_log'
  AND source_name = 'cache_creation_tokens';
