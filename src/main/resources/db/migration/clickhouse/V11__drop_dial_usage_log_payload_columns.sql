-- Drops the five verbatim-content columns from dial_usage_log; they live on dial_usage_log_payload
-- (V10) alone from here. See the data-sync capability spec, "The verbatim-content columns are dropped
-- from dial_usage_log", for the behaviour this migration is the implementation of, and
-- docs/heavy-columns.md for the operator procedure.
--
-- DESTRUCTIVE AND NOT PRECEDED BY A BACKFILL: content ingested before the mapping cutover is deleted
-- here and cannot be recovered from the database.
ALTER TABLE dial_usage_log
    DROP COLUMN IF EXISTS jwt_claims,
    DROP COLUMN IF EXISTS request_tags,
    DROP COLUMN IF EXISTS request_body,
    DROP COLUMN IF EXISTS response_body,
    DROP COLUMN IF EXISTS assembled_response;
