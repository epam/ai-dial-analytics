-- Route that served the request; see openspec/specs/data-sync/spec.md.
ALTER TABLE dial_usage_log
    ADD COLUMN IF NOT EXISTS route_name LowCardinality(Nullable(String)),
    ADD COLUMN IF NOT EXISTS route_type LowCardinality(Nullable(String));
