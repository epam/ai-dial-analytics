-- Tag the audit trail with the catalog import that wrote it (capability audit-trail, "Activities carry the
-- import that produced them"). `revinfo.import_id` records the fact at its grain — the revision was written
-- while `/v1/catalog/import` applied a bundle — and `audit_activity.import_id` copies it, as author, email
-- and timestamp are already copied, so the feed stays a single-table read.
--
-- Both nullable with no default: existing rows were not written by an import, and a replica still on the
-- previous build simply leaves the column null. Only the feed filters on it, so only its column is indexed.
ALTER TABLE revinfo ADD COLUMN import_id VARCHAR(36);
ALTER TABLE audit_activity ADD COLUMN import_id VARCHAR(36);

CREATE INDEX audit_activity_import_idx ON audit_activity (import_id);
