-- Catalog rows for the dial_usage_log spending-limit columns added by clickhouse/V18.
INSERT INTO column_mapping (table_name, source_name, name, type, element_type, nullable, description,
                            display_name, tag, sensitive, heavy, ordinal)
VALUES ('dial_usage_log', 'spending_limits_mode', 'spending_limits_mode', 'string', NULL, TRUE,
        'DIAL Core spending-limits mode when the request''s limits were checked: off, shadow or enforce; any '
            || 'other value the producer reports is stored as reported. Set on the model call''s row of a token '
            || 'caller only. NULL for an API key caller, on other rows, and on rows ingested before the column '
            || 'existed until they are re-ingested.',
        'Spending limits mode', 'cost', FALSE, FALSE, 41),
       ('dial_usage_log', 'budget_cost_object', 'budget_cost_object', 'string', NULL, TRUE,
        'Cost object (project) the request is charged to by DIAL Core spending limits; Core uses its default '
            || 'cost object when the token carries no project, so this can differ from project_id. NULL when '
            || 'spending_limits_mode is off or NULL, and on rows ingested before the column existed until they '
            || 'are re-ingested.',
        'Budget cost object', 'cost', FALSE, FALSE, 42),
       ('dial_usage_log', 'budget_outcome', 'budget_outcome', 'string', NULL, TRUE,
        'Outcome of the budget check: paid (a budget paid), no_budget (none covers the caller and the model) or '
            || 'exhausted (every such budget was used up); any other value the producer reports is stored as '
            || 'reported. A refused request has no row, so no_budget and exhausted appear in shadow mode only. '
            || 'NULL when no check finished, and on rows ingested before the column existed until they are '
            || 're-ingested.',
        'Budget outcome', 'cost', FALSE, FALSE, 43),
       ('dial_usage_log', 'budget_policy', 'budget_policy', 'string', NULL, TRUE,
        'Path of the budget policy that paid for the request. NULL unless budget_outcome is paid, and on rows '
            || 'ingested before the column existed until they are re-ingested.',
        'Budget policy', 'cost', FALSE, FALSE, 44),
       ('dial_usage_log', 'budget_assignment', 'budget_assignment', 'string', NULL, TRUE,
        'Path of the policy assignment that granted the budget, <cost object>/<id>. NULL unless budget_outcome '
            || 'is paid, for a policy that applies by itself, and on rows ingested before the column existed until '
            || 'they are re-ingested.',
        'Budget assignment', 'cost', FALSE, FALSE, 45);
