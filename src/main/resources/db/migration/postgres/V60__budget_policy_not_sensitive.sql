-- Clears the sensitive flag V59 seeded on dial_usage_log.budget_policy; see openspec/specs/table-management/spec.md.
UPDATE column_mapping SET sensitive = FALSE
WHERE table_name = 'dial_usage_log' AND name = 'budget_policy';
