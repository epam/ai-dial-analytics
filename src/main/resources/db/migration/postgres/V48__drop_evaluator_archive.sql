-- Drop what the evaluator fold (V41) and the rule fold (V36) left behind (capability enrichment-registry,
-- "No evaluator surface remains"). An enrichment's definition lives on its pipeline as `spec.transform`; no
-- pipeline read, write or validation path has consulted any of the objects below since the fold.
--
--   * `evaluator` / `evaluator_version` -- the read-only archive behind `/v1/evaluators`, retired with this
--     release. Pre-fold prompts, and what an already-stamped `evaluator_version` provenance value referred
--     to, are not carried anywhere else; an operator who wants them dumps these two tables before upgrading.
--   * `pipeline.evaluator_name` (its foreign key and `pipeline_evaluator_name_idx` go with it) and
--     `pipeline.evaluator_version` -- unmapped and unread since V41, which retained them only until no
--     deployed build mapped them.
--   * `pipeline_aud.evaluator_name` / `evaluator_version` -- V36's mirrors of the two pointer columns, never
--     written since V41 because the entity stopped mapping the base columns.
--   * `rule_legacy` / `rule_legacy_aud` -- the pre-V36 rule table and its audit history, kept as the rule
--     fold's id mapping. `rule_legacy` also holds the last other foreign key to `evaluator`.
--
-- `pipeline.legacy_rule_id` is deliberately NOT dropped here. The previous release still maps it, and with
-- `ddl-auto: none` nothing catches a mapped column that no longer exists until the first query selecting it
-- -- so dropping it while a previous-release instance is still serving during a rolling deploy would fail
-- every pipeline read and write on that instance. This release stops mapping it; the next release drops it.
--
-- The ClickHouse `evaluator_version` provenance column on existing enrichment targets is not touched: it
-- stays a reserved provenance name (capability enrichment-runtime).
--
-- No CASCADE and no IF EXISTS: every object here is created by a tracked migration, so an unlisted
-- dependent stops this migration with a named error rather than disappearing with it. Order is dependency
-- order -- every foreign key into `evaluator` is gone before `evaluator` is.

ALTER TABLE pipeline
    DROP COLUMN evaluator_name,
    DROP COLUMN evaluator_version;

ALTER TABLE pipeline_aud
    DROP COLUMN evaluator_name,
    DROP COLUMN evaluator_version;

DROP TABLE rule_legacy_aud;
DROP TABLE rule_legacy;

DROP TABLE evaluator_version;
DROP TABLE evaluator;
