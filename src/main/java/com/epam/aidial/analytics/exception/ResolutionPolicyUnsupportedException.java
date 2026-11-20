package com.epam.aidial.analytics.exception;

/**
 * A catalog import {@code resolution_policy} this build does not implement ({@code OVERWRITE}: import never
 * mutates an existing object's definition, columns or pipeline). Mapped to HTTP 422 with the stable code
 * {@code resolution_policy_unsupported}.
 */
public class ResolutionPolicyUnsupportedException extends RuntimeException {
    public ResolutionPolicyUnsupportedException(String message) {
        super(message);
    }
}
