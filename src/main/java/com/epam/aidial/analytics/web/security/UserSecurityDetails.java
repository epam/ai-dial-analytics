package com.epam.aidial.analytics.web.security;

/**
 * Caller details carried on the authentication token's {@code details} slot. Kept separate from the
 * authorities so non-authorization attributes can be read from the security context without re-parsing
 * the token.
 *
 * @param email    the caller's email if present in the token, otherwise {@code null}
 * @param userHash the caller's user hash (see {@link UserHasher}), otherwise {@code null}
 * @param project  the project of a project API key, otherwise {@code null}
 */
public record UserSecurityDetails(String email, String userHash, String project) {

    /** Details for a caller with neither a user hash nor a project. */
    public UserSecurityDetails(String email) {
        this(email, null, null);
    }
}
