-- Instance settings and their audit mirror; see openspec/specs/instance-settings/spec.md.
CREATE TABLE instance_setting (
    name        TEXT PRIMARY KEY CHECK (name IN ('sessions-source')),
    value       JSONB NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    updated_by  TEXT
);

CREATE TABLE instance_setting_aud (
    rev         INTEGER  NOT NULL REFERENCES revinfo (id),
    revtype     SMALLINT,
    name        TEXT     NOT NULL,
    value       JSONB,
    updated_at  TIMESTAMPTZ,
    updated_by  TEXT,
    PRIMARY KEY (rev, name)
);

CREATE INDEX instance_setting_aud_name_rev_idx ON instance_setting_aud (name, rev);
