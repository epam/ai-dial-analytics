package com.epam.aidial.analytics.exception;

/**
 * A grant would expose a derived sensitive column more widely than the table it derives from exposes the
 * column it came from.
 *
 * <p>Because sensitive-column entitlement is grantable per table, declaring a derived column
 * {@code sensitive} no longer implies a uniform barrier: an enrichment's {@code sensitive} grantees
 * can differ from its read source's. Without this rule, a role that may not read a source's sensitive
 * column could be granted {@code sensitive} on an enrichment derived from it and read the value
 * through the derivation. The rule is enforced both at pipeline registration and on
 * {@code PUT /v1/tables/{name}/access}, because a grant made after registration would otherwise widen
 * exposure silently.
 *
 * <p>Mapped to HTTP 422 with the {@code sensitive_grant_widens} code. Its own code rather than the generic
 * {@code validation_error}, because the refusal names a pipeline the operator was probably not thinking
 * about and a client needs to tell it apart from a malformed body.
 */
public class SensitiveGrantWidensException extends RuntimeException {

    public SensitiveGrantWidensException(String message) {
        super(message);
    }
}
