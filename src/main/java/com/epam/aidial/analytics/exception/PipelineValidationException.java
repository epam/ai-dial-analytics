package com.epam.aidial.analytics.exception;

/**
 * A pipeline failed validation at register, patch, or execution: a malformed aggregate declaration
 * (empty or duplicated group keys, a measure carrying an expression, an unsupported bucket unit, an
 * unimplemented freshness mode or trigger kind), a target whose shape cannot hold the rollup (not an
 * {@code upsert_by_key} source, an {@code ordering_key} that differs from the group-by tuple, a missing
 * or type-incompatible column), or an unusable input (not a scannable source, or the pipeline's own
 * target). Mapped to HTTP 422 with the stable code {@code pipeline_validation_failed}.
 */
public class PipelineValidationException extends RuntimeException {
    public PipelineValidationException(String message) {
        super(message);
    }
}
