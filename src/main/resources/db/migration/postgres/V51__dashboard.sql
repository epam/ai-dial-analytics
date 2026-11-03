-- Dashboards and their audit mirror; see openspec/specs/dashboards/spec.md.
CREATE TABLE dashboard (
    id           TEXT PRIMARY KEY,
    name         TEXT NOT NULL CHECK (length(trim(name)) > 0),
    description  TEXT,
    visibility   TEXT NOT NULL CHECK (visibility IN ('PRIVATE', 'PUBLIC')),
    owner_id     TEXT,
    owner_email  TEXT,
    time_intent  JSONB,
    comparison   TEXT CHECK (comparison IN ('OFF', 'PREVIOUS_PERIOD', 'PREVIOUS_MONTH', 'PREVIOUS_YEAR')),
    widgets      JSONB NOT NULL,
    generation   BIGINT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_dashboard_owner ON dashboard (owner_id);
CREATE INDEX idx_dashboard_visibility ON dashboard (visibility);

CREATE TABLE dashboard_aud (
    rev          INTEGER  NOT NULL REFERENCES revinfo (id),
    revtype      SMALLINT,
    id           TEXT     NOT NULL,
    name         TEXT,
    description  TEXT,
    visibility   TEXT,
    owner_id     TEXT,
    owner_email  TEXT,
    time_intent  JSONB,
    comparison   TEXT,
    widgets      JSONB,
    created_at   TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ,
    PRIMARY KEY (rev, id)
);

CREATE INDEX dashboard_aud_id_rev_idx ON dashboard_aud (id, rev);
