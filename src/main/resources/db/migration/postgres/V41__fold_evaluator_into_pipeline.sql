-- Fold the evaluator definition into its pipeline's `spec`, as a nested `transform` block (capability
-- enrichment-registry). The evaluator registry was a second control-plane object holding what an ENRICH
-- pipeline needs in order to run -- type, model, preset, params, request_template and outputs -- named by
-- `pipeline.evaluator_name` plus an optional `evaluator_version`, and resolved at read time.
--
-- Three standing complaints shared one root cause: an evaluator cannot be deleted, cannot be saved
-- incomplete, and does not pay for itself. Measured on the dev registry: 8 evaluators, 4 enrich
-- pipelines, each naming a distinct evaluator, so maximum reuse is 1 and four evaluators have no pipeline
-- at all; all 54 output bindings across all four pipelines are identity mappings. Reuse is also
-- STRUCTURALLY blocked, because `outputs` is keyed by target column names. Everything the evaluator
-- provided, the pipeline already provides better: it is Envers-audited with a full attributed snapshot per
-- revision, it has a `generation` change token, it can be patched while enabled, and it can be deleted.
--
-- WHAT THIS MIGRATION IS CAREFUL ABOUT, in order of consequence:
--
--   1. It CARRIES BOTH OUTPUT SHAPES VERBATIM and converts neither. `outputs`, `output_vars` and
--      `response_schema` are copied as they stand. Converting looks mechanical and is not: the killer is
--      the row with `output_vars` and NO `response_schema`, which calls the model UNCONSTRAINED today and
--      is served that way deliberately. Giving it an `outputs` array and no stored schema is precisely the
--      input composition fires on -- so the next feed read would send a `response_format` where none was
--      sent before. That is a silent live prompt change, made by a migration, on an environment nobody
--      inspected. Dev may hold no such row; another environment's orphan may.
--   2. It BUMPS NOTHING. `generation` and `updated_at` are untouched, and that is deliberate: the fold
--      writes in the transform each pipeline ALREADY resolved to, so a bump would re-sync every runner
--      lane and discard every Class-1 materialized position for a change the migration did not make. The
--      release does change the compiled projection -- that is the deploy's job, gated by the contract
--      version -- but this UPDATE is not what changes it.
--   3. It RETAINS `pipeline.evaluator_name` (with its FK and index) and `pipeline.evaluator_version`,
--      unmapped and unread, and drops ONLY the `pipeline_evaluator_iff_enrich` CHECK. That CHECK is the
--      only object making `evaluator_name` required for an ENRICH row (V36:107-109 says so; neither
--      column carries a column-level NOT NULL), so dropping it alone is the whole of what the new build
--      needs. Dropping the COLUMNS would fail every pipeline read on a pod still running the old build --
--      `PipelineRepository.findAllDeclarations()` is a constructor projection naming both -- and the
--      cluster's `maxUnavailable: 0` GUARANTEES such a pod exists for the length of the roll. A named
--      follow-up change (`drop-retained-evaluator-columns`) removes them once no deployed build maps
--      them. Their presence here is a decision, not an oversight.
--   4. It RESOLVES ONCE, into a temp table, and drives both the log and the UPDATE from it. Flyway runs a
--      migration in one transaction but at READ COMMITTED, so a pre-flight and an UPDATE that each
--      resolve COALESCE(pinned, max(version)) take separate snapshots -- and a concurrent admin write
--      between them would make the logged mapping disagree with what was written.
--   5. It is the project's FIRST IN-PLACE MUTATION of `pipeline.spec`. V36 populated `spec` only for rows
--      it inserted and states outright that it "never touches an existing row's spec".
--
-- `evaluator` and `evaluator_version` are NOT touched. They become a read-only archive, and they are
-- retained for two reasons that outlive the registry: 3,631 dev rows carry an `evaluator_version`
-- provenance stamp that only the archive keeps answerable, and a `pipeline_aud` revision recorded before
-- the fold carries no transform, so the archive is the only place a pre-fold prompt can be read at all.
--
-- `input_vars` is deliberately NOT folded. EvaluatorService has written it empty since the `vars` shape
-- landed and nothing reads it, so there is nothing to carry -- its absence below is a decision.
--
-- `pipeline_aud` needs NO new columns, which is checked rather than hoped: every folded member lands in
-- `spec`, which `pipeline_aud` already mirrors (V35). That is the exact trap V36 hit -- a missing mirror
-- column makes Envers' insert name a column that does not exist and EVERY pipeline write fails at commit.
--
-- NUMBERED V41, verified against `development` at V40: V39 `evaluator_outputs` (merged 2026-09-14) and
-- V40 `payload_partition_alignment` (merged 2026-09-15, also on release-0.2/patch-release-0.2.1 and so
-- plausibly already applied in deployed environments). A duplicate version never conflicts in git and
-- instead fails every test at startup, so this was re-checked at authoring time and must be re-checked at
-- merge.

-- ---------------------------------------------------------------------------------------------------
-- Part A -- resolve every ENRICH pipeline's evaluator version ONCE, and fail loudly on any that does not.
-- ---------------------------------------------------------------------------------------------------

-- `evaluator_version` has NO foreign key (and `evaluator.latest_version` has none back either, so an
-- evaluator with zero versions makes max(version) NULL), so an UPDATE ... FROM would silently not match
-- and leave a pipeline with no transform. COALESCE(pinned, max(version)) is EvaluatorService.select()
-- expressed in SQL.
CREATE TEMP TABLE fold_resolution ON COMMIT DROP AS
SELECT p.name                                       AS pipeline_name,
       p.evaluator_name                             AS evaluator_name,
       COALESCE(p.evaluator_version,
                (SELECT max(e2.version)
                 FROM evaluator_version e2
                 WHERE e2.name = p.evaluator_name)) AS resolved_version
FROM pipeline p
WHERE p.kind = 'ENRICH';

DO
$$
    DECLARE
        offenders TEXT;
        folded    RECORD;
    BEGIN
        SELECT string_agg(format('%s -> %s v%s', r.pipeline_name, coalesce(r.evaluator_name, '<null>'),
                                 coalesce(r.resolved_version::text, '<none>')), ', ')
        INTO offenders
        FROM fold_resolution r
                 LEFT JOIN evaluator_version ev
                           ON ev.name = r.evaluator_name AND ev.version = r.resolved_version
        WHERE ev.name IS NULL;

        IF offenders IS NOT NULL THEN
            RAISE EXCEPTION 'cannot fold: % enrich pipeline(s) name an evaluator version that does not '
                'resolve (%). Such a pipeline already breaks the whole runner sync feed, because '
                'resolveAll throws on the first unresolvable reference -- so this is pre-existing damage '
                'surfaced, not caused. Fix the pipeline or register the version, then re-deploy.',
                (SELECT count(*)
                 FROM fold_resolution r
                          LEFT JOIN evaluator_version ev
                                    ON ev.name = r.evaluator_name AND ev.version = r.resolved_version
                 WHERE ev.name IS NULL), offenders;
        END IF;

        -- The only record of this mapping once the two pointer columns are gone, so it is logged at the
        -- moment it stops being derivable (see the header on why the columns are retained for now).
        FOR folded IN SELECT * FROM fold_resolution ORDER BY pipeline_name
            LOOP
                RAISE NOTICE 'folding pipeline % <- evaluator % version %',
                    folded.pipeline_name, folded.evaluator_name, folded.resolved_version;
            END LOOP;
    END
$$;

-- ---------------------------------------------------------------------------------------------------
-- Part B -- fold the resolved version into `pipeline.spec` under a `transform` key.
-- ---------------------------------------------------------------------------------------------------

-- `type` and `preset` are uppercase TEXT with CHECK constraints (V11/V14) and are LOWER-CASED here for
-- the same reason V36 lower-cased `priority`: the application's JsonMapper writes enums lower-case, so
-- the folded value must equal what a later PATCH would store.
--
-- `jsonb_strip_nulls` wraps ONLY the four scalar members. Never the whole object -- it recurses, and a
-- stored `response_schema` or `params` may legitimately contain a JSON null. `jsonb_build_object` writes
-- a key even for a NULL argument, so without this a folded `sql` transform would store `model: null`,
-- `preset: null` and `request_template: null` -- untidiness on the WRITE surface, where a round-tripped
-- patch echoes it back.
--
-- The `outputs` test is `IS NOT NULL AND <> '[]'`, not `IS NOT NULL` alone: the output-shape accessor
-- reads an EMPTY `outputs` as old-shape (`outputs == null || outputs.isEmpty()`), so the looser test
-- would fold a row the accessor then reads the other way. `params` and `output_vars` need no null guard
-- -- both columns are NOT NULL DEFAULT, so `<>` can never yield NULL there -- while `outputs` and
-- `response_schema` are the two nullable ones and correctly use IS NOT NULL.
UPDATE pipeline p
SET spec = p.spec || jsonb_build_object('transform',
                                        jsonb_strip_nulls(jsonb_build_object(
                                                'type', lower(ev.type),
                                                'model', ev.model,
                                                'preset', lower(ev.preset),
                                                'request_template', ev.request_template))
                                            || CASE
                                                   WHEN ev.params <> '{}'::jsonb
                                                       THEN jsonb_build_object('params', ev.params)
                                                   ELSE '{}'::jsonb END
                                            || CASE
                                                   WHEN ev.outputs IS NOT NULL AND ev.outputs <> '[]'::jsonb
                                                       THEN jsonb_build_object('outputs', ev.outputs)
                                                   ELSE '{}'::jsonb END
                                            || CASE
                                                   WHEN ev.output_vars <> '[]'::jsonb
                                                       THEN jsonb_build_object('output_vars', ev.output_vars)
                                                   ELSE '{}'::jsonb END
                                            || CASE
                                                   WHEN ev.response_schema IS NOT NULL
                                                       THEN jsonb_build_object('response_schema', ev.response_schema)
                                                   ELSE '{}'::jsonb END)
FROM fold_resolution r
         JOIN evaluator_version ev ON ev.name = r.evaluator_name AND ev.version = r.resolved_version
WHERE p.name = r.pipeline_name;

-- ---------------------------------------------------------------------------------------------------
-- Part C -- assert the fold covered every row.
-- ---------------------------------------------------------------------------------------------------

-- A statement, not only the pre-flight's prediction: `UPDATE ... FROM` skips a non-joining row with no
-- error and no row count, so this is what turns "every ENRICH pipeline has a transform" into a checked
-- fact. The pre-flight cannot serve instead even though both read `fold_resolution`, because the join in
-- Part B is what could fail to match.
DO
$$
    DECLARE
        missing TEXT;
    BEGIN
        SELECT string_agg(name, ', ')
        INTO missing
        FROM pipeline
        WHERE kind = 'ENRICH'
          AND spec -> 'transform' IS NULL;

        IF missing IS NOT NULL THEN
            RAISE EXCEPTION 'fold incomplete: enrich pipeline(s) % carry no transform after the update; '
                'the join resolved for the pre-flight and not for the write, so nothing may be assumed '
                'about the rest of this deploy', missing;
        END IF;
    END
$$;

-- ---------------------------------------------------------------------------------------------------
-- Part D -- drop the evaluator biconditional, and NOTHING else.
-- ---------------------------------------------------------------------------------------------------

-- V36: CHECK ((kind = 'ENRICH') = (evaluator_name IS NOT NULL)). With it in place the new build cannot
-- store an ENRICH pipeline at all, because it no longer maps the column. Both columns stay -- see header
-- item 3 for why dropping them would break the pod this deploy is rolling FROM, and which follow-up
-- change removes them. `pipeline_aud.evaluator_name` / `pipeline_aud.evaluator_version` stay too: a
-- mirror row is a historical fact, and V36 already gave them no FK precisely so they survive the
-- evaluator being retired.
ALTER TABLE pipeline DROP CONSTRAINT pipeline_evaluator_iff_enrich;

COMMENT ON COLUMN pipeline.evaluator_name IS
    'RETAINED, UNMAPPED AND UNREAD. The evaluator definition is folded into spec->transform (V41); this '
    'column and evaluator_version survive only so a pod running the pre-fold build keeps reading through a '
    'rolling deploy. Dropped by a named follow-up change once no deployed build maps them.';

COMMENT ON COLUMN pipeline.evaluator_version IS
    'RETAINED, UNMAPPED AND UNREAD. See pipeline.evaluator_name.';
