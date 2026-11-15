-- Add per-role column policies and the access generation to the table catalog; openspec/specs/table-access-control/spec.md
ALTER TABLE table_definition
    ADD COLUMN column_policies JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN access_generation BIGINT NOT NULL DEFAULT 0;

ALTER TABLE table_definition_aud
    ADD COLUMN column_policies JSONB;
