-- Role-based per-table access control. A co-located JSONB access list on the catalog row:
--   access = {"write": [role, ...], "modify": [role, ...]}
-- write roles may insert rows; modify roles may change schema/description. The two are independent —
-- a WRITE role does NOT imply MODIFY. Roles are raw provider role names matched against the caller's
-- provider roles; FULL_ADMIN callers bypass the lists. Table create/delete and access management stay
-- FULL_ADMIN-only. Deleting a table drops its access (same row).
ALTER TABLE table_definition
    ADD COLUMN access JSONB NOT NULL DEFAULT '{"write": [], "modify": []}'::jsonb;
