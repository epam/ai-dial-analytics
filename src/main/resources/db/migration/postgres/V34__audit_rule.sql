-- Extends change tracking to the enrichment control plane's rule registry. Same shape as V27's
-- table_definition_aud/column_mapping_aud — one mirror row per audited entity per revision, every
-- non-id column nullable, no live foreign keys or CHECK constraints carried over, for the same
-- reasons documented there. Only control-plane fields are mirrored: the five runtime-position
-- fields (materialized_through_version/identity, drained_at, history_rederived_at,
-- materialized_generation) are @NotAudited on RuleEntity, so Envers never writes them here.

CREATE TABLE rule_aud (
    rev               INTEGER  NOT NULL REFERENCES revinfo (id),
    revtype           SMALLINT,
    id                TEXT     NOT NULL,
    name              TEXT,
    evaluator_name    TEXT,
    evaluator_version INTEGER,
    target_enrichment TEXT,
    source_table      TEXT,
    trigger_kind      TEXT,
    trigger_cron      TEXT,
    group_by          TEXT,
    ready_when        JSONB,
    member_select     JSONB,
    filter_sql        TEXT,
    input_bindings    JSONB,
    output_bindings   JSONB,
    sampling          DOUBLE PRECISION,
    cadence           TEXT,
    batch_scan_limit  INTEGER,
    batch_chunk       INTEGER,
    rate_rpm          INTEGER,
    priority          TEXT,
    enabled                      BOOLEAN,
    generation                   BIGINT,
    created_at                   TIMESTAMPTZ,
    updated_at                   TIMESTAMPTZ,
    PRIMARY KEY (rev, id)
);

-- Same access-pattern rationale as V27: a snapshot read resolves an entity at a revision with a
-- correlated max(rev) subquery keyed by the entity id, so id leads rev.
CREATE INDEX rule_aud_id_rev_idx ON rule_aud (id, rev);
