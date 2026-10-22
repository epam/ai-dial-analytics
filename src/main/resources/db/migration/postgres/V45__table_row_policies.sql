-- Per-role row policies, co-located on the catalog row next to `access`:
--   row_policies = {"<provider role>": {"filter": <DSL filter node>} | {"all_rows": true}, ...}
-- A policy narrows what a role reads from this table (ClickHouse permissive row-policy semantics); it grants
-- nothing. Enforced by the service as the ClickHouse `additional_table_filters` setting on every user read.
-- Deleting a table drops its policies (same row).
--
-- The audit mirror gains the column in the same migration: `table_definition` is @Audited, and an audited
-- entity widened without its `_aud` mirror fails the first write of the new column at commit.
ALTER TABLE table_definition
    ADD COLUMN row_policies JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE table_definition_aud
    ADD COLUMN row_policies JSONB;
