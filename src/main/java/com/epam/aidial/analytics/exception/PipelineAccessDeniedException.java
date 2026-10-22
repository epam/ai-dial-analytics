package com.epam.aidial.analytics.exception;

/**
 * A caller lacks the target-enrichment access required to scan or write through a pipeline-scoped
 * data-plane endpoint. Mapped to HTTP 403 with the stable code {@code pipeline_access_denied}.
 */
public class PipelineAccessDeniedException extends RuntimeException {
    public PipelineAccessDeniedException(String message) {
        super(message);
    }
}
