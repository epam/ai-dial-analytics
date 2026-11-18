-- Spending limits and budget of the request; see openspec/specs/data-sync/spec.md.
ALTER TABLE dial_usage_log
    ADD COLUMN IF NOT EXISTS spending_limits_mode LowCardinality(Nullable(String)),
    ADD COLUMN IF NOT EXISTS budget_cost_object LowCardinality(Nullable(String)),
    ADD COLUMN IF NOT EXISTS budget_outcome LowCardinality(Nullable(String)),
    ADD COLUMN IF NOT EXISTS budget_policy LowCardinality(Nullable(String)),
    ADD COLUMN IF NOT EXISTS budget_assignment LowCardinality(Nullable(String));
