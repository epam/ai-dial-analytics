package com.epam.aidial.analytics.web.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Converts a verified {@link Jwt} into an authentication token: it resolves the caller's email and
 * principal name from the configured claims, extracts provider roles, and maps them onto
 * {@link Privilege}s. A valid token is always authenticated; its authorities are built by the one shared
 * {@link CallerAuthorities} function, so this path and the other three entry points cannot diverge.
 * Whether it may reach a given endpoint is decided per endpoint by {@link RequiresPrivilege} — a token
 * that maps to no privilege can still be authorized for a specific table by a matching provider role.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final Converter<Jwt, Collection<GrantedAuthority>> jwtGrantedAuthoritiesConverter;
    private final UserRolesResolver userRolesResolver;
    private final String principalClaim;
    private final Set<String> emailClaims;
    private final boolean requireEmail;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String email = extractEmail(jwt);
        if (requireEmail && email == null) {
            throw new AuthenticationServiceException("Email claim is required");
        }

        UserSecurityDetails details = new UserSecurityDetails(email);
        String issuer = jwt.getIssuer().toString();
        Collection<GrantedAuthority> rawAuthorities = jwtGrantedAuthoritiesConverter.convert(jwt);
        Set<Privilege> privileges = userRolesResolver.resolve(rawAuthorities);
        String principalClaimValue = jwt.getClaimAsString(principalClaim);

        // The expanded privileges plus the raw provider roles (prefixed), so per-table grants can match on
        // the raw roles. A valid token is authenticated even with no privilege; the per-endpoint checks
        // then decide what it may reach.
        List<GrantedAuthority> authorities = CallerAuthorities.buildFromAuthorities(privileges, rawAuthorities);

        JwtAuthenticationToken authToken = new JwtAuthenticationToken(jwt, authorities, principalClaimValue);
        authToken.setDetails(details);

        if (privileges.isEmpty()) {
            log.debug("JWT for issuer {} maps to no privilege; authenticated with raw provider roles {}",
                    issuer, rawAuthorities);
        }
        log.trace("Authorization state - issuer: {}, authorities: {}", issuer, authorities);

        return authToken;
    }

    private String extractEmail(Jwt jwt) {
        for (String claim : emailClaims) {
            Object value = jwt.getClaims().get(claim);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }
}
