-- Fold the enrichment `rule` registry into `pipeline` as the ENRICH kind (ADR 0006 item 2, capability
-- pipeline-registry). This is the convergence V20 anticipated in its own comment: "the discriminator is
-- persisted so the `external` and `enrich` kinds can converge onto this object later without a second
-- re-modelling". `rule` and `pipeline` were the same idea twice — a declared process producing rows for
-- exactly one target table — with two identities (UUID vs name), two DTO families and two error-code sets.
--
-- WHAT THIS MIGRATION IS CAREFUL ABOUT, in order of consequence:
--
--   1. It never touches an existing row's `spec`. Four aggregate rollups are live; their declarations are
--      read back byte-identically after the fold. The kind discriminator lives in its own column and the
--      spec codec dispatches on it, precisely so no working declaration is behind a data migration.
--   2. `generation` carries over unchanged. It is the Enrichment Runner's ONLY change-detection token, so
--      resetting it would look to the runner like every pipeline was re-declared at once.
--   3. The five Class-1 position columns carry over unchanged. They are what every dependent rollup's
--      freshness clamp reads; resetting them would unclamp all of them silently.
--   4. `enabled` carries over per row. One live rule is deliberately disabled, and re-enabling a group LLM
--      lane an operator switched off spends real model budget.
--
-- Identity changes from `rule.id` (UUID) to `name`. Both columns were already UNIQUE on both tables, so
-- there is no new uniqueness to establish — but the Enrichment Runner keys five runtime-state tables on
-- the old identity, so this migration is one step of a coordinated cut-over, not a standalone deploy. The
-- runner does not carry those values across: its own V8 renames the tables and DISCARDS their contents, so
-- the cut-over is stop-runner, deploy ADAS, deploy runner — with no remap step wedged between the deploys,
-- and nothing to re-enable afterwards, which is why `enabled` below is carried per row.
-- `rule` is RENAMED rather than dropped: it is the only in-database rollback reference, and the only record
-- of what a rule looked like before the fold. Dropping it is a follow-up change.
--
-- NUMBERED V36 rather than V34, which is where this change was authored: V34 and V35 (the audit trail's
-- extension to `rule`, `pipeline` and `saved_query`) landed on `development` first and are applied in
-- deployed environments, so they are left byte-identical — editing an applied migration aborts startup
-- everywhere, and a green build never catches it. Part D below therefore RECONCILES what they built with
-- the fold rather than pre-empting it.

-- ---------------------------------------------------------------------------------------------------
-- Part A — widen `pipeline` to carry both kinds.
-- ---------------------------------------------------------------------------------------------------

-- `pipeline_kind_check` is PostgreSQL's auto-generated name for V20's inline column CHECK, not a declared
-- one. V15 drops `rule_trigger_kind_check` the same way, so the precedent holds.
ALTER TABLE pipeline DROP CONSTRAINT pipeline_kind_check;
ALTER TABLE pipeline ADD CONSTRAINT pipeline_kind_check CHECK (kind IN ('AGGREGATE', 'ENRICH'));

-- An ENRICH pipeline may follow its target enrichment's parent instead of naming an input (that is what
-- NULL has always meant on `rule.source_table`, and the API reports the RESOLVED value either way). An
-- AGGREGATE pipeline must still name one, so the guard is one-way rather than a biconditional and the
-- constraint name says so.
ALTER TABLE pipeline ALTER COLUMN input_table DROP NOT NULL;
ALTER TABLE pipeline ADD CONSTRAINT pipeline_input_required_unless_enrich
    CHECK (input_table IS NOT NULL OR kind = 'ENRICH');

-- ON_INGEST and GROUP triggers have no cron; only SCHEDULE does.
ALTER TABLE pipeline ALTER COLUMN trigger_cron DROP NOT NULL;

-- The trigger fields stay REAL COLUMNS rather than folding into `spec`, and that is what lets V11's, V15's
-- and V28's three structural guards survive verbatim below. The split is trigger (shared infrastructure)
-- vs transform (per-kind) — ADR 0006's own framing, "the kind selects the transform".
--
-- `evaluator_name` keeps its foreign key to evaluator(name); a JSONB member could not have one.
--
-- The five Class-1 position columns are written by @Modifying bulk updates and read by the freshness
-- clamp, so they cannot be JSON either. Note the trap while touching that code: loading the entity in a
-- transaction that bulk-updates the same row silently loses the bulk write.
--
-- `legacy_rule_id` is populated once, read by nothing, and dropped with `rule_legacy`. It exists so a
-- record that predates the fold and is keyed by the old UUID — a log line, a metric series, the runner's
-- dead-letter capture taken before its state was discarded — can still be tied back to the pipeline it
-- belongs to, without the reader needing `rule_legacy` to be still there.
ALTER TABLE pipeline
    ADD COLUMN trigger_kind      TEXT,
    ADD COLUMN trigger_group_by  TEXT,
    ADD COLUMN ready_when        JSONB,
    ADD COLUMN member_select     JSONB,
    ADD COLUMN evaluator_name    TEXT REFERENCES evaluator (name),
    ADD COLUMN evaluator_version INTEGER,
    ADD COLUMN materialized_through_version  BIGINT,
    ADD COLUMN materialized_through_identity TEXT,
    ADD COLUMN drained_at            TIMESTAMPTZ,
    ADD COLUMN history_rederived_at  TIMESTAMPTZ,
    ADD COLUMN materialized_generation BIGINT,
    ADD COLUMN legacy_rule_id      TEXT;

-- Every pre-existing row is an aggregate, and V20 made `trigger_cron` mandatory for those, so SCHEDULE is
-- the only value consistent with what is already stored.
UPDATE pipeline SET trigger_kind = 'SCHEDULE';
ALTER TABLE pipeline ALTER COLUMN trigger_kind SET NOT NULL;
ALTER TABLE pipeline ADD CONSTRAINT pipeline_trigger_kind_check
    CHECK (trigger_kind IN ('SCHEDULE', 'ON_INGEST', 'GROUP'));

-- The three guards `rule` already carried, re-created under `pipeline_*` names.
--
-- The biconditional holds across the fold only because of two facts about the live rows: every existing
-- AGGREGATE row is SCHEDULE and already carries a cron, and no live rule uses SCHEDULE (all are on_ingest
-- or group, so all arrive with a NULL cron).
ALTER TABLE pipeline ADD CONSTRAINT pipeline_cron_iff_schedule
    CHECK ((trigger_kind = 'SCHEDULE') = (trigger_cron IS NOT NULL));

ALTER TABLE pipeline ADD CONSTRAINT pipeline_group_by_iff_group
    CHECK ((trigger_kind = 'GROUP') = (trigger_group_by IS NOT NULL));

-- One-way, as V28 spells it: a group pipeline need not narrow its members.
ALTER TABLE pipeline ADD CONSTRAINT pipeline_member_select_only_group
    CHECK (member_select IS NULL OR trigger_kind = 'GROUP');

-- TWO INVARIANTS THAT WOULD OTHERWISE BE LOST. Neither has a counterpart on `rule` to copy: they exist
-- there as plain NOT NULLs that only make sense per-kind, and the merged columns cannot be NOT NULL.
--
-- V11 declares `rule.evaluator_name TEXT NOT NULL`. The merged column has to be nullable because an
-- AGGREGATE row has no evaluator — and dropping that NOT NULL without this check would make an ENRICH
-- pipeline with NO EVALUATOR storable: a row nothing can execute and nothing refuses.
ALTER TABLE pipeline ADD CONSTRAINT pipeline_evaluator_iff_enrich
    CHECK ((kind = 'ENRICH') = (evaluator_name IS NOT NULL));

-- Symmetrically, the widened `trigger_kind` would admit an AGGREGATE row claiming GROUP or ON_INGEST,
-- which AggregateScheduler would never fire and findDue would never select. The API refuses it as a
-- cross-kind field; this is the database saying the same thing, the way V11's and V15's checks do.
ALTER TABLE pipeline ADD CONSTRAINT pipeline_aggregate_is_scheduled
    CHECK (kind <> 'AGGREGATE' OR trigger_kind = 'SCHEDULE');

-- The runner sync feed filters on (enabled, updated_since) and orders by updated_at; `rule` indexed both
-- access paths (V11) and the merged listing keeps them.
CREATE INDEX pipeline_enabled_idx ON pipeline (enabled);
CREATE INDEX pipeline_updated_at_idx ON pipeline (updated_at);
CREATE INDEX pipeline_evaluator_name_idx ON pipeline (evaluator_name);

-- ---------------------------------------------------------------------------------------------------
-- Part B — move the rules onto `pipeline`, keyed on `name`.
-- ---------------------------------------------------------------------------------------------------

-- Guard first: a name collision would violate the primary key mid-insert and leave a half-folded
-- registry. There are no collisions in any known environment; this makes that a checked fact rather than
-- an assumption, and fails the deploy loudly instead of at the first read.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM rule r JOIN pipeline p ON p.name = r.name) THEN
        RAISE EXCEPTION 'rule/pipeline name collision; resolve before folding: %',
            (SELECT string_agg(r.name, ', ') FROM rule r JOIN pipeline p ON p.name = r.name);
    END IF;
END $$;

INSERT INTO pipeline (name,
                      kind,
                      target,
                      input_table,
                      filter_sql,
                      spec,
                      trigger_kind,
                      trigger_cron,
                      trigger_group_by,
                      ready_when,
                      member_select,
                      evaluator_name,
                      evaluator_version,
                      enabled,
                      generation,
                      created_at,
                      updated_at,
                      materialized_through_version,
                      materialized_through_identity,
                      drained_at,
                      history_rederived_at,
                      materialized_generation,
                      legacy_rule_id,
                      rebuilt_at)
SELECT r.name,
       'ENRICH',
       r.target_enrichment,
       r.source_table,
       r.filter_sql,
       -- The ENRICH transform declaration, symmetric with AggregateSpec. Most members are optional and
       -- most live rules leave them unset, so this writes JSON nulls for those; that is fine for Jackson
       -- (both specs tolerate unknown and absent properties), but it means the assembly cannot be verified
       -- by "the object has eight keys" — check the values that were actually set.
       jsonb_build_object(
           'input_bindings', r.input_bindings,
           'output_bindings', r.output_bindings,
           'sampling', r.sampling,
           'cadence', r.cadence,
           'batch_scan_limit', r.batch_scan_limit,
           'batch_chunk', r.batch_chunk,
           'rate_rpm', r.rate_rpm,
           -- Lower-cased because `priority` moves from an @Enumerated(EnumType.STRING) column (uppercase
           -- TEXT) into JSON, where the application's JsonMapper writes enums lower-case. The mapper reads
           -- case-insensitively, so either form loads — this is so the value the fold stores is identical
           -- to the value a later PATCH would store, rather than differing by case for one release.
           'priority', lower(r.priority)),
       r.trigger_kind,
       r.trigger_cron,
       r.group_by,
       r.ready_when,
       r.member_select,
       r.evaluator_name,
       r.evaluator_version,
       -- Per-row, never a blanket true. A disabled group LLM lane stays disabled.
       r.enabled,
       -- The runner's only change token. Must not be reset.
       r.generation,
       r.created_at,
       r.updated_at,
       -- The Class-1 position. Resetting it would unclamp every dependent rollup.
       r.materialized_through_version,
       r.materialized_through_identity,
       r.drained_at,
       r.history_rederived_at,
       r.materialized_generation,
       r.id,
       -- The rollup rebuild anchor takes the migration instant, as V32 did for existing pipelines. It is
       -- inert on an ENRICH row, which is not a rollup, but the column is NOT NULL.
       now()
FROM rule r;

-- Deliberately NOT carried over, and left NULL/default: `cursor_version`, `cursor_identity`,
-- `next_run_at`, `last_run_at`, `last_error`, `has_more`, `clamped_by`, `clamped_to_version`,
-- `clamped_to_identity`. An ENRICH pipeline's scan cursor lives in the Enrichment Runner and ADAS drives
-- no schedule for it. `next_run_at` staying NULL is the first of three independent guards against the
-- in-process aggregate scheduler picking up an ENRICH row (the others being the kind filter in `findDue`
-- and a kind assertion in the refresh service) — an aggregate refresh against an enrichment target would
-- write wrong rows rather than fail.

-- ---------------------------------------------------------------------------------------------------
-- Part C — retire `rule`.
-- ---------------------------------------------------------------------------------------------------

-- Renamed, not dropped. Its indexes and constraints come with it and are inert once nothing maps to the
-- table. It is the only in-database rollback reference, and the only record of what a rule looked like
-- before the fold. A follow-up change drops it once the cut-over window has closed.
ALTER TABLE rule RENAME TO rule_legacy;

-- ---------------------------------------------------------------------------------------------------
-- Part D — reconcile the audit trail (V27, V34, V35) with the fold.
-- ---------------------------------------------------------------------------------------------------

-- V35 mirrored `pipeline` as it stood BEFORE this migration, so the six control-plane columns Part A
-- added have no mirror yet. Without them Envers' insert into `pipeline_aud` names columns that do not
-- exist and EVERY pipeline write fails — a hard failure at the first mutation rather than a silent gap,
-- but a failure of the whole write path, not just of the trail.
--
-- Same shape rules as V27/V34/V35: every non-id column nullable, no live foreign keys and no CHECK
-- constraints carried over. `evaluator_name` in particular gets no FK here on purpose — a mirror row is
-- a historical fact and must survive the evaluator it named being retired.
--
-- The five Class-1 position columns Part A added are deliberately NOT mirrored, matching the decision
-- V34 recorded for `rule`: they are @NotAudited on PipelineEntity. Every writer of them is a @Modifying
-- bulk update, which bypasses the persistence context, so Envers never observes the write — mirroring
-- them would store not the recorded position but whatever the entity last held in memory. Neither is
-- `legacy_rule_id`, a transitional artifact this migration populates once and a follow-up change drops.
ALTER TABLE pipeline_aud
    ADD COLUMN trigger_kind      TEXT,
    ADD COLUMN trigger_group_by  TEXT,
    ADD COLUMN ready_when        JSONB,
    ADD COLUMN member_select     JSONB,
    ADD COLUMN evaluator_name    TEXT,
    ADD COLUMN evaluator_version INTEGER;

-- The activity feed named a rule by its UUID under resource type 'Rule'. That resource type is gone —
-- ActivityResourceType no longer has the constant, and a row carrying it would fail to deserialize and
-- take the whole feed page down with it, not merely hide itself. So the existing rows are re-pointed at
-- the identity the fold gave them, exactly as Part B re-pointed the rules themselves.
--
-- COALESCE rather than a join, deliberately: an activity for a rule DELETED before the fold has no
-- `rule_legacy` row to resolve, and audit history is never discarded to tidy up. Such a row keeps its
-- legacy id as an opaque resource id — a `Pipeline` activity naming a resource that no longer exists,
-- which is what every deleted resource's history already looks like to a reader.
--
-- `parent_activity_id` needs nothing: it points at a Table activity that is unaffected, and the parent
-- of a bumped ENRICH pipeline is still its effective read source's table.
UPDATE audit_activity a
   SET resource_type = 'Pipeline',
       resource_id = COALESCE((SELECT r.name FROM rule_legacy r WHERE r.id = a.resource_id), a.resource_id)
 WHERE a.resource_type = 'Rule';

-- Renamed, not dropped, for exactly the reason `rule` is: it is the only record of what a rule looked
-- like at a past revision, and the fold cannot carry that history onto the new identity (a snapshot read
-- resolves an entity at a revision from ITS own mirror rows, and `pipeline_aud` has none for these names
-- before this deploy). Nothing maps to it once RuleEntity is gone, so it is inert; the follow-up change
-- that drops `rule_legacy` drops this with it.
--
-- The consequence for readers, stated plainly: a folded pipeline's history begins at this deploy.
-- `AuditSnapshotGuards.requireCreationWithinTheTrail` already answers 404 for a resource whose creation
-- is not in the trail, so the point-in-time read refuses rather than reconstructing a half-state.
ALTER TABLE rule_aud RENAME TO rule_legacy_aud;
ALTER INDEX rule_aud_id_rev_idx RENAME TO rule_legacy_aud_id_rev_idx;
