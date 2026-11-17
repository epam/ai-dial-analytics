-- Add per-role column policies, the access generation and each role's last-change generation to the table catalog; openspec/specs/table-access-control/spec.md
ALTER TABLE table_definition
    ADD COLUMN column_policies JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN access_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN access_role_generations JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE table_definition_aud
    ADD COLUMN column_policies JSONB;
