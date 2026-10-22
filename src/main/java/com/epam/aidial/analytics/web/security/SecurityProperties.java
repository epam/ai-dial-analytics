package com.epam.aidial.analytics.web.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the scalar authentication configuration ({@code security.*}). Values are
 * env-driven via {@code application.yml}; property descriptions live in {@code docs/configuration.md}.
 * Identity providers live in a separate top-level map — see {@link IdentityProvidersProperties}.
 *
 * <p>This holder is bound in every mode (it is harmless when {@code mode} is {@code none}).
 *
 * @param mode         security mode: {@code oidc} (enforce Bearer-JWT auth) or {@code none} (open);
 *                     interpreted once by {@link SecurityMode}, which rejects any other value at startup
 * @param requireEmail when {@code true}, a token without a resolvable email claim is rejected
 * @param disableSwaggerAuthorization when {@code true}, the OpenAPI document and Swagger UI are served without
 *                     a credential in {@code oidc} mode; otherwise they need an authenticated caller
 * @param defaults     fallback claim names and roles mapping applied to providers that omit their own
 */
@ConfigurationProperties(prefix = "security")
public record SecurityProperties(
        String mode,
        boolean requireEmail,
        boolean disableSwaggerAuthorization,
        Defaults defaults) {

    /**
     * Service-wide fallbacks used when a provider does not specify its own values.
     *
     * @param rolesMapping  JSON object mapping provider role names to lists of {@link Privilege} or
     *                      {@link RoleBundle} names, e.g. {@code {"admin":["FULL_ADMIN"]}} or
     *                      {@code {"analyst":["TABLE_READ","SAVED_QUERY_READ"]}}; merged under each
     *                      provider's own mapping. An unknown value fails startup
     * @param emailClaim    default claim name to read the caller's email from
     * @param principalClaim default claim name to use as the authenticated principal name
     */
    public record Defaults(
            String rolesMapping,
            String emailClaim,
            String principalClaim) {
    }
}
