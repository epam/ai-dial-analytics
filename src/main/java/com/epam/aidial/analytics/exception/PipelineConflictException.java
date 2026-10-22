package com.epam.aidial.analytics.exception;

/**
 * A pipeline mutation collided with the registry's state: a duplicate pipeline name, a target that
 * already has a pipeline of either kind (two processes writing the same keyed table would clobber each
 * other's rows), or a declaration edit on an enabled pipeline whose kind forbids one. Mapped to HTTP 409
 * with the stable code {@code pipeline_conflict}.
 *
 * <p>Deliberately not a validation code. The declaration sent may be perfectly valid and only the timing
 * wrong, and reporting a state conflict as a validation failure tells a client to fix a declaration that
 * has nothing wrong with it — which is what the enrichment registry did before the two merged.
 */
public class PipelineConflictException extends RuntimeException {
    public PipelineConflictException(String message) {
        super(message);
    }
}
