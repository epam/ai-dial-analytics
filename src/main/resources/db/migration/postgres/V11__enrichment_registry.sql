-- Enrichment registry control-plane (capability enrichment-registry): evaluators (LLM scoring/
-- labelling definitions) with immutable versioning, and rules that bind an evaluator to a target
-- enrichment table. Governance is enforced at registration in the service layer; the DB adds the
-- structural invariants that must never be violated: immutable evaluator versions (natural
-- (name, version) key, no in-place edits), one rule per target enrichment (UNIQUE), and unique
-- rule names. Enum-like columns are stored as uppercase TEXT with CHECK constraints, matching the
-- @Enumerated(EnumType.STRING) convention used elsewhere in the catalog. JSONB columns hold the
-- loosely-structured evaluator/rule payloads (params, templates, variable and binding lists).

-- An evaluator name with a pointer to its current version. Editing an evaluator adds a new
-- evaluator_version row and advances latest_version; existing versions are never mutated.
CREATE TABLE evaluator (
    name           TEXT PRIMARY KEY,
    latest_version INTEGER NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One immutable version of an evaluator. The (name, version) pair is the natural key; a new edit
-- is a new row at max(version)+1. Phase-1 accepts only type=LLM, preset=CHAT_COMPLETION.
CREATE TABLE evaluator_version (
    name             TEXT NOT NULL REFERENCES evaluator (name) ON DELETE CASCADE,
    version          INTEGER NOT NULL,
    type             TEXT NOT NULL CHECK (type IN ('LLM')),
    preset           TEXT NOT NULL CHECK (preset IN ('CHAT_COMPLETION')),
    model            TEXT NOT NULL,
    params           JSONB NOT NULL DEFAULT '{}'::jsonb,
    request_template TEXT,
    input_vars       JSONB NOT NULL DEFAULT '[]'::jsonb,
    response_schema  JSONB,
    output_vars      JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (name, version)
);

-- A rule binds an evaluator (pinned version, or NULL = latest) to a target enrichment. The scan
-- source and grain are derived from the target enrichment (parent/grain), never re-declared here.
-- One rule per target enrichment: enrichment tables are ReplacingMergeTree with full-row replace
-- by grain, so two rules writing the same grain would clobber each other. generation is bumped on
-- every mutation so the runner sync feed can detect changes (ETag).
CREATE TABLE rule (
    id                TEXT PRIMARY KEY,
    name              TEXT NOT NULL UNIQUE,
    evaluator_name    TEXT NOT NULL REFERENCES evaluator (name),
    evaluator_version INTEGER,
    target_enrichment TEXT NOT NULL UNIQUE REFERENCES table_definition (name) ON DELETE CASCADE,
    trigger_kind      TEXT NOT NULL CHECK (trigger_kind IN ('ON_INGEST', 'SCHEDULE')),
    trigger_cron      TEXT,
    filter_sql        TEXT,
    input_bindings    JSONB NOT NULL DEFAULT '[]'::jsonb,
    output_bindings   JSONB NOT NULL DEFAULT '[]'::jsonb,
    sampling          DOUBLE PRECISION,
    cadence           TEXT,
    batch_scan_limit  INTEGER,
    batch_chunk       INTEGER,
    rate_rpm          INTEGER,
    priority          TEXT CHECK (priority IN ('LIVE', 'BACKFILL')),
    enabled           BOOLEAN NOT NULL DEFAULT false,
    generation        BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A scheduled rule must carry a cron expression; on_ingest rules must not.
    CONSTRAINT rule_cron_iff_schedule CHECK ((trigger_kind = 'SCHEDULE') = (trigger_cron IS NOT NULL))
);

-- The runner sync feed filters by enabled and updated_since; index both access paths.
CREATE INDEX rule_enabled_idx ON rule (enabled);
CREATE INDEX rule_updated_at_idx ON rule (updated_at);
