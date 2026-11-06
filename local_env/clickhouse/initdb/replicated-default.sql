-- LOCAL DEV ONLY. Runs once, on an empty data volume: re-creates the server's empty `default` database
-- with the Replicated engine, so ADAS's default session database (CLICKHOUSE_DATABASE unset) is the
-- Replicated database it requires. See docs/clickhouse-replication.md.
DROP DATABASE IF EXISTS default SYNC;
CREATE DATABASE default ENGINE = Replicated('/clickhouse/databases/default', '{shard}', '{replica}');
