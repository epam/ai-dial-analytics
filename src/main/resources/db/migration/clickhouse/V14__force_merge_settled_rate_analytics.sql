-- Force-merge settled day partitions of rate_analytics, as V12 does for dial_usage_log, so its duplicate rows
-- collapse on disk. Metadata-only and idempotent, but it applies to parts that already exist.
--
-- Specified in openspec/specs/data-sync/spec.md (requirement "Settled ingest day partitions are force-merged so
-- their duplicates collapse") and operated per docs/clickhouse-server.md.
ALTER TABLE rate_analytics MODIFY SETTING min_age_to_force_merge_seconds = 172800, min_age_to_force_merge_on_partition_only = 1;
