-- Extends change tracking to the aggregate pipeline registry and the saved query registry. Same
-- shape as V27's table_definition_aud/column_mapping_aud and V34's rule_aud — one mirror row per
-- audited entity per revision, every non-id column nullable, no live foreign keys or CHECK
-- constraints carried over, for the same reasons documented there. Only control-plane fields are
-- mirrored: the ten runtime-position fields written by PipelineStateWriter on every scheduler
-- tick (cursor_version/identity, next_run_at, last_run_at, last_error, has_more, clamped_*,
-- rebuilt_at) are @NotAudited on PipelineEntity, so Envers never writes them here and the feed
-- is not flooded by O(runs/day) entries per pipeline.

CREATE TABLE pipeline_aud (
    rev            INTEGER  NOT NULL REFERENCES revinfo (id),
    revtype        SMALLINT,
    name           TEXT     NOT NULL,
    kind           TEXT,
    target         TEXT,
    input_table    TEXT,
    filter_sql     TEXT,
    spec           JSONB,
    trigger_cron   TEXT,
    enabled        BOOLEAN,
    generation     BIGINT,
    created_at     TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ,
    PRIMARY KEY (rev, name)
);

CREATE TABLE saved_query_aud (
    rev          INTEGER  NOT NULL REFERENCES revinfo (id),
    revtype      SMALLINT,
    id           TEXT     NOT NULL,
    name         TEXT,
    description  TEXT,
    tag          TEXT,
    scope        TEXT,
    owner_id     TEXT,
    owner_email  TEXT,
    source       TEXT,
    query        JSONB,
    sql_text     TEXT,
    time_intent  JSONB,
    result_view  TEXT,
    chart        JSONB,
    generation   BIGINT,
    created_at   TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ,
    PRIMARY KEY (rev, id)
);

-- Same access-pattern rationale as V27/V32: a snapshot read resolves an entity at a revision with a
-- correlated max(rev) subquery keyed by the entity id, so id leads rev.
CREATE INDEX pipeline_aud_name_rev_idx ON pipeline_aud (name, rev);
CREATE INDEX saved_query_aud_id_rev_idx ON saved_query_aud (id, rev);
