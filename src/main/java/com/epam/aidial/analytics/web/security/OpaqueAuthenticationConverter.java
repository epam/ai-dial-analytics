package com.epam.aidial.analytics.web.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenAuthenticationConverter;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the authentication token from an introspected opaque-token principal: resolves the caller's
 * email, maps the introspected provider roles onto {@link Privilege}s, and wraps everything in a
 * {@link BearerTokenAuthentication}. As with the JWT path, a valid token is always authenticated and its
 * authorities come from the one shared {@link CallerAuthorities} function; endpoint access is then decided
 * per endpoint by {@link RequiresPrivilege} and per-table grant evaluation.
 */
@Slf4j
@RequiredArgsConstructor
public class OpaqueAuthenticationConverter implements OpaqueTokenAuthenticationConverter {

    private final UserRolesResolver userRolesResolver;
    private final Set<String> emailClaims;
    private final boolean requireEmail;

    @Override
    public Authentication convert(String introspectedToken, OAuth2AuthenticatedPrincipal authenticatedPrincipal) {
        String providerName = authenticatedPrincipal.getAttribute(DefaultOpaqueTokenIntrospector.IDP_CLAIM);
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, introspectedToken, null, null);
        String email = extractEmail(authenticatedPrincipal.getAttributes());

        if (requireEmail && email == null) {
            throw new AuthenticationServiceException("Email claim is required");
        }

        UserSecurityDetails details = new UserSecurityDetails(email);

        Collection<? extends GrantedAuthority> rawAuthorities = authenticatedPrincipal.getAuthorities();
        Set<Privilege> privileges = userRolesResolver.resolve(rawAuthorities);

        // Expanded privileges plus the raw provider roles (prefixed) for per-table grant matching.
        List<GrantedAuthority> authorities = CallerAuthorities.buildFromAuthorities(privileges, rawAuthorities);

        log.trace("Authorization state - idp: {}, authorities: {}", providerName, authorities);

        BearerTokenAuthentication authentication = new BearerTokenAuthentication(authenticatedPrincipal, accessToken, authorities);
        authentication.setDetails(details);

        if (privileges.isEmpty()) {
            log.debug("Opaque token for idp {} maps to no privilege; authenticated with raw provider roles only",
                    providerName);
        }

        return authentication;
    }

    private String extractEmail(Map<String, Object> attributes) {
        for (String claim : emailClaims) {
            Object value = attributes.get(claim);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }
}
