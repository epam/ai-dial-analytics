-- Force-merge settled day partitions of dial_usage_log_payload and dial_usage_log so their duplicate rows
-- collapse on disk, and merge the payload table's heavy rows with the vertical algorithm. Metadata-only and
-- idempotent, but it applies to parts that already exist.
--
-- What it does, why the interval is 2 days, why the payload table merges vertically, the rollout cost and the
-- ALTER SETTINGS grant are specified in openspec/specs/data-sync/spec.md (requirement "Settled ingest day
-- partitions are force-merged so their duplicates collapse") and operated per docs/clickhouse-server.md.
ALTER TABLE dial_usage_log_payload MODIFY SETTING min_age_to_force_merge_seconds = 172800, min_age_to_force_merge_on_partition_only = 1,
    vertical_merge_algorithm_min_columns_to_activate = 1, vertical_merge_algorithm_min_rows_to_activate = 1;
ALTER TABLE dial_usage_log MODIFY SETTING min_age_to_force_merge_seconds = 172800, min_age_to_force_merge_on_partition_only = 1;
