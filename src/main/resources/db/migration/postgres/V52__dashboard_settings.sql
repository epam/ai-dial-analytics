-- Free-form dashboard settings and their audit mirror; see openspec/specs/dashboards/spec.md.
ALTER TABLE dashboard ADD COLUMN settings JSONB;
ALTER TABLE dashboard_aud ADD COLUMN settings JSONB;
