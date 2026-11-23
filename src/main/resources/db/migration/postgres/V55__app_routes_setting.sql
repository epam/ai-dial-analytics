-- Admits the app-routes instance setting; see openspec/specs/instance-settings/spec.md.
ALTER TABLE instance_setting DROP CONSTRAINT instance_setting_name_check;
ALTER TABLE instance_setting ADD CONSTRAINT instance_setting_name_check CHECK (name IN ('sessions-source', 'app-routes'));
