package com.epam.aidial.analytics.web.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

import java.util.List;

/**
 * Supports several opaque-token providers: an opaque token carries no issuer, so each configured
 * provider's introspector is tried in turn until one accepts the token. If none does (or none is
 * configured), the token is rejected. Delegates are tried in configuration order.
 */
@Slf4j
@RequiredArgsConstructor
public class MultiIssuerOpaqueTokenIntrospector implements OpaqueTokenIntrospector {

    private final List<OpaqueTokenIntrospector> opaqueTokenIntrospectors;

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        for (OpaqueTokenIntrospector introspector : opaqueTokenIntrospectors) {
            try {
                return introspector.introspect(token);
            } catch (Exception ex) {
                log.debug("Opaque token introspection failed for one provider; trying the next", ex);
            }
        }
        throw new BadOpaqueTokenException("No configured provider could introspect the opaque token");
    }
}
