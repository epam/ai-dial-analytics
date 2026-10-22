package com.epam.aidial.analytics.web.flight;

import com.epam.aidial.analytics.web.security.CallerAuthorities;
import com.epam.aidial.analytics.web.security.ConditionalOnSecurityMode;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.PrivilegeAuthority;
import com.epam.aidial.analytics.web.security.ProviderRoleAuthority;
import com.epam.aidial.analytics.web.security.SecurityMode;
import com.epam.aidial.analytics.web.security.UserSecurityDetails;
import com.epam.aidial.analytics.web.security.apikey.ApiKeyAuthenticationToken;
import com.epam.aidial.analytics.web.security.apikey.ApiKeyAuthorityResolver;
import com.epam.aidial.analytics.web.security.apikey.ApiKeyCache;
import com.epam.aidial.analytics.web.security.apikey.CoreApiKeyIntrospector;
import com.epam.aidial.analytics.web.security.apikey.IntrospectionResult;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.auth2.CallHeaderAuthenticator;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.OpaqueTokenAuthenticationProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Flight-side authentication for {@code security.mode=oidc}: a gRPC header adapter
 * over the exact same components that guard the HTTP API. Three credential forms are accepted, in
 * this precedence: a {@code Bearer} token validated by the multi-issuer JWT/opaque machinery
 * (JWS-parseable → JWKS verification, otherwise userinfo introspection); an {@code Api-Key} header
 * introspected against DIAL Core through the shared cache; and {@code Basic} credentials whose
 * username is the fixed sentinel {@code api-key} and whose password is a DIAL API key, validated
 * by that same introspection path. Bearer wins when several are present, matching the HTTP filter
 * order.
 *
 * <p>Authorization mirrors the HTTP surface: this gate requires only that the principal be
 * authenticated and hold <em>some</em> table read privilege — globally or by a grant — and the per-RPC
 * checks in {@code AdasFlightSqlProducer} then decide each call, with the metadata RPCs filtered by
 * {@link Privilege#TABLE_READ} exactly as HTTP discovery is. The gate used to require
 * {@code FULL_ADMIN} or {@code READ_ONLY_ADMIN}, which made a caller authorized entirely by per-table
 * grants — the BI-tool persona those grants exist for — unable to reach Flight at all.
 *
 * <p>This bean only exists when both the Flight endpoint and oidc mode are on; in
 * {@code security.mode=none} no authenticator is registered and the endpoint is open, matching HTTP
 * behavior.
 *
 * <p>The {@code Basic} form exists for clients that can express only a username and a password and
 * can set neither a custom header nor a bearer token. A wrong username is rejected rather than
 * ignored: the principal comes from introspecting the key, so a caller who typed some other
 * identity must not be authenticated as the key's principal while believing otherwise.
 *
 * <p>A {@code Basic}-authenticated call is answered with
 * {@code authorization: Bearer adas-api-key:<the same key>}, because no Arrow-derived client
 * re-sends {@code Basic} per call — each sends it on {@code Handshake} only and then carries
 * forward whatever {@code Bearer }-prefixed value the server returned (the Flight SQL ODBC drivers
 * abort the client process when that value is absent). This is deliberately <em>not</em> a minted
 * session token: the value carries the presented API key unchanged, holds no independent lifetime,
 * is never stored server-side, and is re-introspected through {@link ApiKeyCache} on every call
 * that presents it — so a revoked key stops working within the same cache TTL that bounds the
 * {@code Api-Key} header path, and nothing issued here can outlive it.
 */
@Slf4j
@Component
@ConditionalOnSecurityMode(SecurityMode.OIDC)
@ConditionalOnProperty(name = "flight.enabled", havingValue = "true")
public class FlightHeaderAuthenticator implements CallHeaderAuthenticator {

    private static final String AUTHORIZATION_HEADER = "authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String BASIC_PREFIX = "Basic ";

    /** The only accepted {@code Basic} username: the credential is a DIAL API key, not a user account. */
    private static final String API_KEY_USERNAME = "api-key";

    /** Marks a bearer value as carrying a DIAL API key rather than an OIDC token. */
    private static final String API_KEY_BEARER_MARKER = "adas-api-key:";

    private final AuthenticationManager jwtManager;
    private final AuthenticationManager opaqueManager;
    private final ObjectProvider<CoreApiKeyIntrospector> introspector;
    private final ObjectProvider<ApiKeyCache> cache;
    private final ObjectProvider<ApiKeyAuthorityResolver> authorityResolver;

    public FlightHeaderAuthenticator(
            JwtAuthenticationProvider jwtAuthenticationProvider,
            OpaqueTokenAuthenticationProvider opaqueTokenAuthenticationProvider,
            ObjectProvider<CoreApiKeyIntrospector> introspector,
            ObjectProvider<ApiKeyCache> cache,
            ObjectProvider<ApiKeyAuthorityResolver> authorityResolver) {
        this.jwtManager = new ProviderManager(jwtAuthenticationProvider);
        this.opaqueManager = new ProviderManager(opaqueTokenAuthenticationProvider);
        this.introspector = introspector;
        this.cache = cache;
        this.authorityResolver = authorityResolver;
    }

    @Override
    public AuthResult authenticate(CallHeaders incomingHeaders) {
        return authorized(resolve(incomingHeaders));
    }

    /**
     * Authenticates the call's credentials and returns the full {@link Authentication} (with its granted
     * authorities), without applying the query-role authorization gate. This is the same authentication
     * the {@link #authenticate} gate uses; it is exposed so the Flight producer can learn the caller's
     * privileges and provider roles, and filter per table accordingly. Throws the same
     * {@code UNAUTHENTICATED}/{@code UNAVAILABLE} Flight exceptions as the gate on failure.
     */
    public Authentication resolveAuthentication(CallHeaders incomingHeaders) {
        return resolve(incomingHeaders).authentication();
    }

    /**
     * An authenticated call: the caller, plus the {@code authorization} value to send back when the
     * credential form needs answering ({@code null} for every form that does not).
     */
    private record ResolvedCall(Authentication authentication, String outgoingAuthorization) {

        static ResolvedCall of(Authentication authentication) {
            return new ResolvedCall(authentication, null);
        }
    }

    /** A decoded {@code Basic} credential. The password may contain colons; the username may not. */
    private record BasicCredential(String username, String password) {
    }

    private ResolvedCall resolve(CallHeaders incomingHeaders) {
        final String bearer = bearerToken(incomingHeaders);
        if (bearer != null) {
            return ResolvedCall.of(authenticateBearer(bearer));
        }
        final String apiKey = header(incomingHeaders, CoreApiKeyIntrospector.API_KEY_HEADER);
        if (StringUtils.isNotBlank(apiKey)) {
            return ResolvedCall.of(authenticateApiKey(apiKey));
        }
        final BasicCredential basic = basicCredential(incomingHeaders);
        if (basic != null) {
            final String key = apiKeyFromBasic(basic);
            // Answer with the same credential in a marked envelope, so a client that authenticates once
            // per connection carries it forward — and every call it makes is introspected afresh.
            return new ResolvedCall(authenticateApiKey(key), BEARER_PREFIX + API_KEY_BEARER_MARKER + key);
        }
        throw CallStatus.UNAUTHENTICATED
                .withDescription("credentials required: provide a Bearer token, an Api-Key header, or Basic credentials "
                        + "with the username \"api-key\" and a DIAL API key as the password")
                .toRuntimeException();
    }

    private Authentication authenticateBearer(String token) {
        if (Strings.CI.startsWith(token, API_KEY_BEARER_MARKER)) {
            // The marked envelope this endpoint hands out for a Basic handshake: a DIAL API key, not an
            // OIDC token. Routing on the marker keeps a genuinely invalid bearer token from costing a
            // Core introspection, and keeps "invalid bearer token" distinguishable from "invalid API key".
            return authenticateApiKey(requireKey(token.substring(API_KEY_BEARER_MARKER.length())));
        }
        if (StringUtils.isBlank(token)) {
            // BearerTokenAuthenticationToken asserts a non-blank token, and an IllegalArgumentException from
            // it is not an AuthenticationException — it would escape as a bare INTERNAL instead of the
            // UNAUTHENTICATED this endpoint promises for a rejected credential.
            throw CallStatus.UNAUTHENTICATED.withDescription("invalid bearer token").toRuntimeException();
        }
        final AuthenticationManager manager = parsesAsJws(token) ? jwtManager : opaqueManager;
        try {
            return manager.authenticate(new BearerTokenAuthenticationToken(token));
        } catch (AuthenticationServiceException e) {
            throw unavailable(e);
        } catch (AuthenticationException e) {
            log.debug("Flight bearer authentication failed: {}", e.getMessage());
            throw CallStatus.UNAUTHENTICATED.withDescription("invalid bearer token").toRuntimeException();
        }
    }

    private Authentication authenticateApiKey(String apiKey) {
        final CoreApiKeyIntrospector coreIntrospector = introspector.getIfAvailable();
        final ApiKeyCache keyCache = cache.getIfAvailable();
        final ApiKeyAuthorityResolver resolver = authorityResolver.getIfAvailable();
        if (coreIntrospector == null || keyCache == null || resolver == null) {
            throw CallStatus.UNAUTHENTICATED
                    .withDescription("api-key authentication is not enabled; provide a Bearer token")
                    .toRuntimeException();
        }
        try {
            return keyCache.getOrAuthenticate(apiKey, () -> {
                final IntrospectionResult result = coreIntrospector.introspect(apiKey);
                final Set<Privilege> privileges = resolver.resolve(result.rawRoles(), result.fromProjectKey());
                // The same shared builder the three HTTP entry points use, so this path carries the caller's
                // raw PROVIDER_ROLE_* authorities too. It did not, while sharing one ApiKeyCache entry with
                // HTTP keyed by the credential — so whichever surface introspected a key first decided
                // whether its per-table grants could match at all.
                final Collection<GrantedAuthority> authorities =
                        CallerAuthorities.build(privileges, result.rawRoles());
                final ApiKeyAuthenticationToken token = new ApiKeyAuthenticationToken(result.principal(), authorities);
                token.setDetails(new UserSecurityDetails(result.email()));
                return token;
            });
        } catch (AuthenticationServiceException e) {
            throw unavailable(e);
        } catch (AuthenticationException e) {
            log.debug("Flight api-key authentication failed: {}", e.getMessage());
            throw CallStatus.UNAUTHENTICATED.withDescription("invalid API key").toRuntimeException();
        }
    }

    /**
     * The HTTP {@code /v1/**} rule, mirrored: authentication, plus the one thing that can be decided
     * without a catalog lookup — that the caller could be authorized for <em>something</em>.
     *
     * <p>Everything else is decided per RPC, downstream, by the same filtered query model the HTTP surface
     * resolves through: the metadata RPCs list only tables the caller may read, and a statement over one it
     * may not resolves as an unknown entity. What this gate must not do is test for an application role, as
     * it used to: a caller authorized entirely by per-table grants — the BI-tool persona those grants exist
     * for — holds no global privilege and would be turned away at the door.
     *
     * <p>A caller holding no privilege and carrying no provider role can never match anything, globally or
     * by grant, so it is rejected here rather than served an empty catalog on every call.
     */
    private static AuthResult authorized(ResolvedCall call) {
        final Authentication authentication = call.authentication();
        if (!authentication.isAuthenticated()) {
            throw CallStatus.UNAUTHENTICATED
                    .withDescription("invalid credentials")
                    .toRuntimeException();
        }
        final boolean authorizable = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> PrivilegeAuthority.isPrivilege(a) || ProviderRoleAuthority.isProviderRole(a));
        if (!authorizable) {
            throw CallStatus.UNAUTHORIZED
                    .withDescription("principal holds no privilege and carries no provider role, so no table "
                            + "is reachable")
                    .toRuntimeException();
        }
        final String identity = authentication.getName();
        final String outgoing = call.outgoingAuthorization();
        if (outgoing == null) {
            return () -> identity;
        }
        // Arrow appends these to the response headers, falling back to the trailers when the call sent no
        // headers — which is what a payload-less Handshake does, and where the C++ client looks first.
        return new AuthResult() {

            @Override
            public String getPeerIdentity() {
                return identity;
            }

            @Override
            public void appendToOutgoingHeaders(CallHeaders outgoingHeaders) {
                outgoingHeaders.insert(AUTHORIZATION_HEADER, outgoing);
            }
        };
    }

    private static RuntimeException unavailable(AuthenticationServiceException e) {
        log.warn("Flight authentication backend unavailable: {}", e.getMessage());
        return CallStatus.UNAVAILABLE
                .withDescription("authentication is temporarily unavailable")
                .toRuntimeException();
    }

    private static String bearerToken(CallHeaders headers) {
        return schemeValue(headers, BEARER_PREFIX);
    }

    /**
     * The credential carried by the first {@code authorization} value using {@code scheme}, stripped of
     * the scheme prefix, or {@code null} when no value uses it. gRPC permits a header to repeat, so
     * precedence between schemes is decided by the scheme rather than by the order they arrived in.
     */
    private static String schemeValue(CallHeaders headers, String scheme) {
        for (final String value : authorizationValues(headers)) {
            if (value != null && Strings.CI.startsWith(value, scheme)) {
                return value.substring(scheme.length());
            }
        }
        return null;
    }

    /** gRPC header keys are lowercase on the wire, but tolerate the canonical casing too. */
    private static Iterable<String> authorizationValues(CallHeaders headers) {
        final Iterable<String> lower = headers.getAll(AUTHORIZATION_HEADER);
        if (lower != null && lower.iterator().hasNext()) {
            return lower;
        }
        final Iterable<String> canonical = headers.getAll("Authorization");
        return canonical != null ? canonical : List.of();
    }

    /**
     * Decodes {@code authorization: Basic base64(<username>:<password>)}, or returns {@code null} when
     * no {@code authorization} value uses that scheme. Malformed values are rejected without disclosing
     * which part failed, matching Arrow's own {@code BasicCallHeaderAuthenticator}.
     */
    private static BasicCredential basicCredential(CallHeaders headers) {
        final String encoded = schemeValue(headers, BASIC_PREFIX);
        if (encoded == null) {
            return null;
        }
        final String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(encoded.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw malformedBasic();
        }
        // Split on the first colon: a password may contain colons, a username may not.
        final int colon = decoded.indexOf(':');
        if (colon < 0) {
            throw malformedBasic();
        }
        return new BasicCredential(decoded.substring(0, colon), decoded.substring(colon + 1));
    }

    /**
     * The sentinel-username rule. A wrong username is never ignored in favour of the password alone: the
     * principal comes from introspecting the key, so a caller who named another identity must not be
     * silently authenticated as the key's principal. The accepted value is named in the rejection because
     * it is a documented constant, identical for every deployment.
     */
    private static String apiKeyFromBasic(BasicCredential credential) {
        if (!API_KEY_USERNAME.equalsIgnoreCase(credential.username())) {
            throw CallStatus.UNAUTHENTICATED
                    .withDescription("Basic credentials must use the username \"api-key\"; the password is the DIAL API key")
                    .toRuntimeException();
        }
        return requireKey(credential.password());
    }

    private static String requireKey(String apiKey) {
        if (StringUtils.isBlank(apiKey)) {
            throw CallStatus.UNAUTHENTICATED.withDescription("invalid API key").toRuntimeException();
        }
        return apiKey;
    }

    private static RuntimeException malformedBasic() {
        return CallStatus.UNAUTHENTICATED
                .withDescription("malformed Basic credentials")
                .toRuntimeException();
    }

    /** gRPC header keys are lowercase on the wire, but tolerate the canonical casing too. */
    private static String header(CallHeaders headers, String name) {
        final String lower = headers.get(name.toLowerCase(java.util.Locale.ROOT));
        return lower != null ? lower : headers.get(name);
    }

    private static boolean parsesAsJws(String token) {
        try {
            SignedJWT.parse(token);
            return true;
        } catch (ParseException e) {
            return false;
        }
    }
}
