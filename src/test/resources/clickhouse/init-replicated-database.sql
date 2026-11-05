-- Creates the Replicated session database the suite runs against (clickhouse-replication spec).
CREATE DATABASE IF NOT EXISTS adas ENGINE = Replicated('/clickhouse/databases/adas', '{shard}', '{replica}');
