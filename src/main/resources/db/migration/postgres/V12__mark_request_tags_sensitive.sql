-- Supersede the V3 rationale for request_tags. V3 left it visible as "routing metadata", but the
-- column carries the request headers verbatim (dial-usage-log.yaml maps it from the record's
-- top-level `headers` object with no allow/deny filtering), so it can hold credentials, cookies,
-- forwarded client IPs and per-user correlation ids. Restrict it to FULL_ADMIN like jwt_claims.
-- Catalog-only metadata: no ClickHouse ALTER, no data migration, reversible by flipping back.
UPDATE column_mapping SET sensitive = TRUE
    WHERE table_name = 'dial_usage_log' AND source_name = 'request_tags';
