-- Pipeline registry (capability aggregate-pipeline, ADR 0006): a declared row-producing process that
-- writes exactly ONE target table. `kind` selects the transform and where it runs; only AGGREGATE is
-- implemented here, and the discriminator is persisted so the `external` and `enrich` kinds can converge
-- onto this object later without a second re-modelling. The existing `rule` table stays the `enrich`
-- realization for now (the Enrichment Runner consumes /v1/rules), and the two registries can never
-- contend for the same table: a rule targets an ENRICHMENT, a pipeline targets a SOURCE.
--
-- One pipeline per target (D-TR-1) is the UNIQUE on `target`. The two FKs differ deliberately:
--   * target      ON DELETE CASCADE — the pipeline exists only to fill that table, so it goes with it.
--   * input_table  no cascade       — deleting an input is REJECTED (409) in the service layer, so the
--                                     dependency breaks loudly at the delete instead of silently at the
--                                     next refresh. A cascade here would delete the pipeline (and, via
--                                     its own cascade, nothing else) leaving a rollup nobody refreshes.
--
-- Enum-like columns are uppercase TEXT with CHECK constraints (the @Enumerated(EnumType.STRING)
-- convention used across this catalog); the loosely-structured aggregate declaration lives in `spec`
-- JSONB, validated in the service layer at registration and re-validated at execution.
CREATE TABLE pipeline (
    name            TEXT PRIMARY KEY,
    kind            TEXT NOT NULL CHECK (kind IN ('AGGREGATE')),
    -- The one table this pipeline writes. UNIQUE = one pipeline per target.
    target          TEXT NOT NULL UNIQUE REFERENCES table_definition (name) ON DELETE CASCADE,
    -- v1 reads exactly one source (+ its enrichments); multi-source input is post-v1 (D-TR-4).
    input_table     TEXT NOT NULL REFERENCES table_definition (name),
    -- Membership predicate over the input, in the bounded SQL-boolean grammar shared with the query
    -- façade and enrichment rules.
    filter_sql      TEXT,
    -- The aggregate declaration: {group_by: [...], measures: [...], freshness: {mode}}.
    spec            JSONB NOT NULL DEFAULT '{}'::jsonb,
    trigger_cron    TEXT NOT NULL,
    -- A pipeline lands disabled and runs only after the dry-run preview gate (POST .../preview).
    enabled         BOOLEAN NOT NULL DEFAULT false,

    -- Runtime execution state. Server-owned: the schedule is ADAS-driven, so no caller holds the
    -- position, and no endpoint may set it — a caller-chosen cursor could skip input rows and produce
    -- measures that are wrong without being visibly wrong. Typed columns rather than a free-form bag
    -- because next_run_at drives due-selection and must be indexable.
    cursor_version  BIGINT,
    cursor_identity TEXT,
    next_run_at     TIMESTAMPTZ,
    last_run_at     TIMESTAMPTZ,
    last_error      TEXT,
    -- Whether the last refresh left input behind (its probe filled the window limit). Stored rather than
    -- merely logged because lag alone cannot distinguish "caught up, and the lag is just the safety
    -- margin" from "still working through a backlog": every run makes window-limit progress, so a pipeline
    -- whose input arrives at roughly its drain rate settles at a large CONSTANT lag, which reads as a
    -- healthy steady state on a graph while actually meaning permanently-stale numbers. While this is
    -- true the scheduler re-arms the pipeline for the next tick instead of its own cron.
    has_more        BOOLEAN NOT NULL DEFAULT false,

    generation      BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The scheduler tick selects due pipelines on exactly (enabled, next_run_at); the input-table delete
-- guard looks up dependents by input_table.
CREATE INDEX pipeline_due_idx ON pipeline (enabled, next_run_at);
CREATE INDEX pipeline_input_table_idx ON pipeline (input_table);
