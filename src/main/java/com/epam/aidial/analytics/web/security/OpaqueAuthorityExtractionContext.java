package com.epam.aidial.analytics.web.security;

import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Set;

/**
 * Everything a custom ({@code fn:}) authority converter needs to derive a caller's roles from a source
 * other than the userinfo claims — see {@link OpaqueTokenCustomGrantedAuthoritiesConverters}. The
 * caller's own bearer token is carried through so the converter can call a further API on the caller's
 * behalf; the userinfo {@code attributes} and the provider's {@code emailClaims} identify who that
 * caller is.
 *
 * @param restClient  the client shared with the opaque introspection path
 * @param token       the caller's opaque bearer token, as presented
 * @param attributes  the claims returned by the provider's userinfo endpoint
 * @param emailClaims the provider's email claim names, tried in order
 */
public record OpaqueAuthorityExtractionContext(
        RestClient restClient,
        String token,
        Map<String, Object> attributes,
        Set<String> emailClaims) {
}
