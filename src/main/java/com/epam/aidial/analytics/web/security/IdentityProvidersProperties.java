package com.epam.aidial.analytics.web.security;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Typed binding for the identity providers map, configured under the top-level {@code providers.*}
 * key (matching the ai-dial-admin-backend reference); property descriptions live in
 * {@code docs/configuration.md}. Scalar authentication settings live under {@code security.*} — see
 * {@link SecurityProperties}.
 *
 * <p>This holder is bound in every mode (it is harmless when {@code security.mode} is {@code none});
 * the {@code oidc} wiring in {@link OidcSecurityConfiguration} validates that at least one usable
 * provider exists.
 *
 * @param providers identity providers keyed by an arbitrary local name; see {@link ProviderConfig}
 */
@ConfigurationProperties
public record IdentityProvidersProperties(
        Map<String, ProviderConfig> providers) {

    /**
     * A single identity provider. A provider is a <b>JWT</b> provider when it has both an
     * {@code issuer} and a {@code jwkSetUri} (tokens are verified locally against the JWKS), and an
     * <b>opaque</b> provider when it has a {@code userInfoEndpoint} (non-JWT tokens are introspected by
     * calling that endpoint). A provider with neither is ignored. The two modes coexist: a deployment
     * may configure JWT providers and opaque providers side by side.
     *
     * @param issuer          expected {@code iss} claim value (a URL), or a tenant id when {@code aliases} is set (JWT)
     * @param jwkSetUri       JWKS endpoint used to fetch signing keys (marks a JWT provider)
     * @param userInfoEndpoint OIDC userinfo endpoint introspected for opaque tokens (marks an opaque provider)
     * @param audiences       accepted {@code aud} claim values (an {@code api://} prefixed variant is also accepted) (JWT)
     * @param aliases         optional host aliases used to expand Azure v1/v2 issuer URLs from a tenant id (JWT)
     * @param roleClaims      dotted claim paths to read provider role names from (e.g. {@code resource_access.roles})
     * @param emailClaims     claim names to read the caller's email from, tried in order
     * @param principalClaim  claim used as the authenticated principal name; falls back to the default when blank
     * @param rolesMapping    JSON object mapping this provider's role names to lists of {@link Privilege} or
     *                        {@link RoleBundle} names
     */
    public record ProviderConfig(
            String issuer,
            String jwkSetUri,
            String userInfoEndpoint,
            List<String> audiences,
            List<String> aliases,
            List<String> roleClaims,
            List<String> emailClaims,
            String principalClaim,
            String rolesMapping) {

        public boolean hasJwkSetUri() {
            return StringUtils.isNotBlank(jwkSetUri);
        }

        public boolean hasUserInfoEndpoint() {
            return StringUtils.isNotBlank(userInfoEndpoint);
        }
    }
}
