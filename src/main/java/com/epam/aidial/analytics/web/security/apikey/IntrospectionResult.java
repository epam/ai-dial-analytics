package com.epam.aidial.analytics.web.security.apikey;

import java.util.List;
import java.util.Map;

/**
 * Result of a DIAL Core {@code /v1/user/info} introspection.
 *
 * <p>Core returns one of two shapes depending on what minted the key:</p>
 * <ul>
 *     <li><b>Project-key auth</b> ({@code fromProjectKey == true}) — response has {@code project};
 *         {@code principal} is the project name, {@code email} is {@code null}, and {@code claims} is
 *         empty (a project key carries no user claims).</li>
 *     <li><b>JWT-rooted per-request key</b> ({@code fromProjectKey == false}) — response has
 *         {@code userClaims}; {@code principal} and {@code email} are extracted from the configured
 *         default claims, and {@code claims} carries the normalized user claims for grant evaluation.</li>
 * </ul>
 */
public record IntrospectionResult(String principal, String email, List<String> rawRoles, boolean fromProjectKey,
                                  Map<String, List<String>> claims) {

    /** Convenience for callers with no claims to carry (project keys, tests). */
    public IntrospectionResult(String principal, String email, List<String> rawRoles, boolean fromProjectKey) {
        this(principal, email, rawRoles, fromProjectKey, Map.of());
    }
}
