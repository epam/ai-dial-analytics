-- Class-1 (type=sql) evaluators (capability class1-enrichment): a deterministic derivation computed
-- inside ClickHouse from a governed scalar expression, with no model call. Such an evaluator carries
-- no model, no preset, no request template, no input variables, and no response schema — only output
-- variables whose `sql` expression the rule-registration gate validates against the rule's source.
--
-- The constraints only widen: `type` gains 'SQL', and `model`/`preset` become nullable but are tied to
-- type = 'LLM' so an LLM evaluator can never lose them and a SQL evaluator can never carry them. Every
-- existing row is type = 'LLM' with both columns populated, so the new CHECKs hold without a backfill.
ALTER TABLE evaluator_version
    DROP CONSTRAINT evaluator_version_type_check,
    ADD CONSTRAINT evaluator_version_type_check CHECK (type IN ('LLM', 'SQL'));

ALTER TABLE evaluator_version
    ALTER COLUMN model DROP NOT NULL,
    ALTER COLUMN preset DROP NOT NULL;

-- model and preset are required for an LLM evaluator and forbidden for a SQL one. The service layer
-- rejects the same shapes with a caller-safe 422; these are the structural invariants behind it.
ALTER TABLE evaluator_version
    ADD CONSTRAINT evaluator_model_iff_llm CHECK ((type = 'LLM') = (model IS NOT NULL)),
    ADD CONSTRAINT evaluator_preset_iff_llm CHECK ((type = 'LLM') = (preset IS NOT NULL));
