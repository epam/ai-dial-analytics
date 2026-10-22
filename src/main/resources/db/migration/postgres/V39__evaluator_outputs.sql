-- Adds the evaluator `outputs` column: the single declaration of what an evaluator produces, keyed by
-- the target column that receives each output, replacing the input_vars + response_schema + output_vars
-- trio for versions registered from here on.
--
-- This is the whole migration. NOT ONE ROW IS REWRITTEN, and that is a decision rather than an
-- omission. evaluator_version is immutable by contract (see V11's own DDL) and a stored response_schema
-- is what a live prompt is made of, so:
--
--   * an `llm` row cannot be folded faithfully at all. Its authored property order was already
--     destroyed by jsonb key normalisation (length-then-bytes), so a rewrite would have to CHOOSE an
--     order — and under response_format {type: json_schema, strict: true} property order is the model's
--     generation order, which makes that a silent prompt change on the pipeline that spends the most;
--   * a `sql` row is mechanically trivial to fold (outputs = {var.name: var.sql}) and still wrong. This
--     change drops the per-output logical type, and GET /v1/evaluators/{name}/versions/{v} names neither
--     a target nor a source to re-derive one from. Re-deriving it from the target column would be wrong
--     in both directions, because the type check is compatibility rather than equality (numeric widening
--     and string -> enum both pass), so the rewritten row would answer GET with strictly less than the
--     row it replaced.
--
-- Existing rows therefore keep output_vars (and response_schema) forever; only newly registered versions
-- populate `outputs`; and every reader resolves the two shapes through
-- EvaluatorVersionEntity.effectiveOutputVars(). Consequences worth stating: generation,
-- materialized_through_version, materialized_through_identity, materialized_generation, drained_at,
-- history_rederived_at and enabled are untouched by construction rather than by discipline, and the
-- rendered prompt is bit-identical -- not "identical except enum order".
--
-- Nullable and with no default: null is the load-bearing signal that this version predates the shape and
-- that its output_vars/response_schema still win. Reversed by dropping the column; no data moves.
ALTER TABLE evaluator_version
    ADD COLUMN outputs JSONB;

COMMENT ON COLUMN evaluator_version.outputs IS
    'Ordered array of {name, prose?, values?, jsonata?, sql?} - what this evaluator produces, keyed by the '
    'target column receiving each output. NULL on a version registered before this shape, whose output_vars '
    'and response_schema stay authoritative forever. An ARRAY rather than an object because the order is the '
    'composed response schema property order, and a jsonb object would re-sort it by key length.';
