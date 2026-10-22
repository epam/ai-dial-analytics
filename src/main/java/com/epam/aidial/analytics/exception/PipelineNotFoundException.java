package com.epam.aidial.analytics.exception;

/**
 * A pipeline named in a request path is not in the registry. Mapped to HTTP 404 with the stable code
 * {@code unknown_pipeline}.
 */
public class PipelineNotFoundException extends RuntimeException {
    public PipelineNotFoundException(String message) {
        super(message);
    }
}
