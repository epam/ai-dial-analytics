package com.epam.aidial.analytics.web.flight;

import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.apikey.ApiKeyAuthorityResolver;
import com.epam.aidial.analytics.web.security.apikey.ApiKeyCache;
import com.epam.aidial.analytics.web.security.apikey.CoreApiKeyIntrospector;
import com.epam.aidial.analytics.web.security.apikey.IntrospectionResult;
import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStatusCode;
import org.apache.arrow.flight.auth2.CallHeaderAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.OpaqueTokenAuthenticationProvider;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Flight auth adapter coverage (spec: Flight authentication mirrors service security mode):
 * missing/invalid credentials → UNAUTHENTICATED, valid api-key with a mapped role → accepted,
 * introspection outage → UNAVAILABLE, bearer precedence over api-key, and a caller with nothing to match
 * → UNAUTHORIZED.
 *
 * <p>Also the {@code Basic} credential form: the {@code api-key} sentinel username (case-insensitive),
 * a wrong username rejected before any introspection, the marked bearer value the handshake answers
 * with, and the per-call revalidation that keeps it from behaving like a session token.
 */
class FlightHeaderAuthenticatorTest {

    private static final String MARKED_BEARER_PREFIX = "Bearer adas-api-key:";

    private final JwtAuthenticationProvider jwtProvider = mock(JwtAuthenticationProvider.class);
    private final OpaqueTokenAuthenticationProvider opaqueProvider = mock(OpaqueTokenAuthenticationProvider.class);
    private final CoreApiKeyIntrospector introspector = mock(CoreApiKeyIntrospector.class);
    private final ApiKeyAuthorityResolver authorityResolver = mock(ApiKeyAuthorityResolver.class);
    private final ApiKeyCache cache = passThroughCache();
    private final FlightHeaderAuthenticator authenticator = new FlightHeaderAuthenticator(
            jwtProvider,
            opaqueProvider,
            providerOf(introspector),
            providerOf(cache),
            providerOf(authorityResolver));

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T instance) {
        final ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    /** A cache that always calls through to the loader — cache behavior itself is tested elsewhere. */
    private static ApiKeyCache passThroughCache() {
        final ApiKeyCache cache = mock(ApiKeyCache.class);
        when(cache.getOrAuthenticate(any(), any())).thenAnswer(invocation -> {
            final Supplier<Authentication> loader = invocation.getArgument(1);
            return loader.get();
        });
        return cache;
    }

    private static CallHeaders headers(String... pairs) {
        final CallHeaders headers = new FlightCallHeaders();
        for (int i = 0; i < pairs.length; i += 2) {
            headers.insert(pairs[i], pairs[i + 1]);
        }
        return headers;
    }

    @Test
    void missingCredentialsRejectedUnauthenticated() {
        assertThatThrownBy(() -> authenticator.authenticate(headers()))
                .isInstanceOfSatisfying(FlightRuntimeException.class,
                        e -> assertThat(e.status().code()).isEqualTo(FlightStatusCode.UNAUTHENTICATED));
    }

    @Test
    void validApiKeyWithMappedPrivilegeAccepted() {
        when(introspector.introspect("k")).thenReturn(
                new IntrospectionResult("proj", null, List.of("admin"), true));
        when(authorityResolver.resolve(List.of("admin"), true))
                .thenReturn(java.util.EnumSet.of(Privilege.TABLE_READ));

        final CallHeaderAuthenticator.AuthResult result =
                authenticator.authenticate(headers("api-key", "k"));
        assertThat(result.getPeerIdentity()).isEqualTo("proj");
    }

    @Test
    void rejectedApiKeyUnauthenticated() {
        when(introspector.introspect("bad")).thenThrow(new BadCredentialsException("Invalid API key"));
        assertThatThrownBy(() -> authenticator.authenticate(headers("api-key", "bad")))
                .isInstanceOfSatisfying(FlightRuntimeException.class,
                        e -> assertThat(e.status().code()).isEqualTo(FlightStatusCode.UNAUTHENTICATED));
    }

    @Test
    void introspectionOutageUnavailable() {
        when(introspector.introspect("k")).thenThrow(new AuthenticationServiceException("Core down"));
        assertThatThrownBy(() -> authenticator.authenticate(headers("api-key", "k")))
                .isInstanceOfSatisfying(FlightRuntimeException.class,
                        e -> assertThat(e.status().code()).isEqualTo(FlightStatusCode.UNAVAILABLE));
    }

    @Test
    void apiKeyMappingToNoPrivilegeIsStillAdmittedForItsProviderRoles() {
        // The gate no longer tests for an application role. A key that maps to no privilege still carries
        // its raw Core roles, and a per-table grant naming one of them is exactly how a BI tool is meant to
        // be authorized — turning it away here would make per-table read grants unreachable over Flight.
        when(introspector.introspect("k")).thenReturn(
                new IntrospectionResult("proj", null, List.of("analytics-viewers"), true));
        when(authorityResolver.resolve(List.of("analytics-viewers"), true))
                .thenReturn(java.util.EnumSet.noneOf(Privilege.class));

        assertThat(authenticator.authenticate(headers("api-key", "k")).getPeerIdentity()).isEqualTo("proj");
    }

    @Test
    void apiKeyWithNoPrivilegeAndNoProviderRoleIsUnauthorized() {
        // Nothing to match, globally or by grant, so this caller can never reach a table. Rejected rather
        // than served an empty catalog on every call.
        when(introspector.introspect("k")).thenReturn(
                new IntrospectionResult("proj", null, List.of(), true));
        when(authorityResolver.resolve(List.of(), true)).thenReturn(java.util.EnumSet.noneOf(Privilege.class));

        assertThatThrownBy(() -> authenticator.authenticate(headers("api-key", "k")))
                .isInstanceOfSatisfying(FlightRuntimeException.class,
                        e -> assertThat(e.status().code()).isEqualTo(FlightStatusCode.UNAUTHORIZED));
    }

    @Test
    void bearerWinsOverApiKey() {
        // An opaque (non-JWS) bearer token routes to the opaque manager; the api-key path is not consulted.
        when(opaqueProvider.supports(any())).thenReturn(true);
        when(opaqueProvider.authenticate(any())).thenThrow(new BadCredentialsException("bad token"));

        assertThatThrownBy(() -> authenticator.authenticate(
                headers("authorization", "Bearer opaque-token", "api-key", "k")))
                .isInstanceOfSatisfying(FlightRuntimeException.class,
                        e -> assertThat(e.status().code()).isEqualTo(FlightStatusCode.UNAUTHENTICATED));
        verify(introspector, never()).introspect(any());
    }

    // ------------------------------------------------------------------ Basic credential form

    @Test
    void basicWithSentinelUsernameAuthenticatesAsTheKeyPrincipal() {
        coreResolvesToReadOnly("k");

        final CallHeaderAuthenticator.AuthResult result =
                authenticator.authenticate(headers("authorization", basic("api-key", "k")));

        assertThat(result.getPeerIdentity()).isEqualTo("proj");
        // The key, not the marked envelope, is what reaches the shared cache and Core.
        verify(cache).getOrAuthenticate(eq("k"), any());
        verify(introspector).introspect("k");
    }

    @Test
    void sentinelUsernameIsCaseInsensitive() {
        coreResolvesToReadOnly("k");

        for (final String username : List.of("API-KEY", "Api-Key", "aPi-KeY")) {
            assertThat(authenticator.authenticate(headers("authorization", basic(username, "k"))).getPeerIdentity())
                    .isEqualTo("proj");
        }
    }

    @Test
    void wrongUsernameRejectedWithMessageNamingTheAcceptedOne() {
        coreResolvesToReadOnly("k");

        assertThatThrownBy(() -> authenticator.authenticate(
                headers("authorization", basic("someone@example.com", "k"))))
                .isInstanceOfSatisfying(FlightRuntimeException.class, e -> {
                    assertThat(e.status().code()).isEqualTo(FlightStatusCode.UNAUTHENTICATED);
                    assertThat(e.status().description()).contains("api-key");
                });
        // Never silently authenticated as the key's principal, and the key is never introspected.
        verify(introspector, never()).introspect(any());
    }

    @Test
    void basicWithRejectedKeyFailsLikeTheHeaderPath() {
        when(introspector.introspect("bad")).thenThrow(new BadCredentialsException("Invalid API key"));

        assertThatThrownBy(() -> authenticator.authenticate(headers("authorization", basic("api-key", "bad"))))
                .isInstanceOfSatisfying(FlightRuntimeException.class, e -> {
                    assertThat(e.status().code()).isEqualTo(FlightStatusCode.UNAUTHENTICATED);
                    assertThat(e.status().description()).isEqualTo("invalid API key");
                });
    }

    @Test
    void basicWithNoPrivilegeAndNoProviderRoleUnauthorized() {
        when(introspector.introspect("k")).thenReturn(
                new IntrospectionResult("proj", null, List.of(), true));
        when(authorityResolver.resolve(List.of(), true)).thenReturn(java.util.EnumSet.noneOf(Privilege.class));

        assertThatThrownBy(() -> authenticator.authenticate(headers("authorization", basic("api-key", "k"))))
                .isInstanceOfSatisfying(FlightRuntimeException.class,
                        e -> assertThat(e.status().code()).isEqualTo(FlightStatusCode.UNAUTHORIZED));
    }

    @Test
    void malformedBasicCredentialsRejected() {
        final String notBase64 = "Basic not-base64!!!";
        final String noColon = "Basic " + Base64.getEncoder()
                .encodeToString("nocolonhere".getBytes(StandardCharsets.UTF_8));

        for (final String value : List.of(notBase64, noColon)) {
            assertThatThrownBy(() -> authenticator.authenticate(headers("authorization", value)))
                    .isInstanceOfSatisfying(FlightRuntimeException.class,
                            e -> assertThat(e.status().code()).isEqualTo(FlightStatusCode.UNAUTHENTICATED));
        }
        verify(introspector, never()).introspect(any());
    }

    @Test
    void emptyPasswordRejectedWithoutIntrospection() {
        assertThatThrownBy(() -> authenticator.authenticate(headers("authorization", basic("api-key", ""))))
                .isInstanceOfSatisfying(FlightRuntimeException.class,
                        e -> assertThat(e.status().code()).isEqualTo(FlightStatusCode.UNAUTHENTICATED));
        verify(introspector, never()).introspect(any());
    }

    @Test
    void passwordMayContainColons() {
        coreResolvesToReadOnly("a:b:c");

        assertThat(authenticator.authenticate(headers("authorization", basic("api-key", "a:b:c"))).getPeerIdentity())
                .isEqualTo("proj");
        verify(introspector).introspect("a:b:c");
    }

    @Test
    void bearerWinsOverBasicWhateverTheWireOrder() {
        when(opaqueProvider.supports(any())).thenReturn(true);
        when(opaqueProvider.authenticate(any())).thenThrow(new BadCredentialsException("bad token"));
        coreResolvesToReadOnly("k");

        // gRPC lets authorization repeat, so both schemes can genuinely arrive on one call. Precedence is
        // by scheme, not by arrival order — the bearer is used and the key is never introspected.
        for (final CallHeaders both : List.of(
                headers("authorization", "Bearer opaque-token", "authorization", basic("api-key", "k")),
                headers("authorization", basic("api-key", "k"), "authorization", "Bearer opaque-token"))) {
            assertThatThrownBy(() -> authenticator.authenticate(both))
                    .isInstanceOfSatisfying(FlightRuntimeException.class, e -> {
                        assertThat(e.status().code()).isEqualTo(FlightStatusCode.UNAUTHENTICATED);
                        assertThat(e.status().description()).isEqualTo("invalid bearer token");
                    });
        }
        verify(introspector, never()).introspect(any());
    }

    @Test
    void apiKeyHeaderWinsOverBasic() {
        coreResolvesToReadOnly("header-key");

        final CallHeaderAuthenticator.AuthResult result = authenticator.authenticate(
                headers("api-key", "header-key", "authorization", basic("api-key", "basic-key")));

        assertThat(result.getPeerIdentity()).isEqualTo("proj");
        verify(introspector).introspect("header-key");
        verify(introspector, never()).introspect("basic-key");
        // The Api-Key header needs no handshake answer, so none is appended.
        assertThat(outgoingAuthorization(result)).isNull();
    }

    // ------------------------------------------------------- Handshake answer and its revalidation

    @Test
    void basicAuthenticatedCallAnswersWithTheMarkedBearerValue() {
        coreResolvesToReadOnly("k");

        final CallHeaderAuthenticator.AuthResult result =
                authenticator.authenticate(headers("authorization", basic("api-key", "k")));

        assertThat(outgoingAuthorization(result)).isEqualTo(MARKED_BEARER_PREFIX + "k");
    }

    @Test
    void otherCredentialFormsAnswerWithNoHeader() {
        coreResolvesToReadOnly("k");
        assertThat(outgoingAuthorization(authenticator.authenticate(headers("api-key", "k")))).isNull();

        assertThat(outgoingAuthorization(authenticator.authenticate(
                headers("authorization", MARKED_BEARER_PREFIX + "k")))).isNull();
    }

    @Test
    void markedBearerValueIntrospectsTheStrippedKey() {
        coreResolvesToReadOnly("k");

        final CallHeaderAuthenticator.AuthResult result =
                authenticator.authenticate(headers("authorization", MARKED_BEARER_PREFIX + "k"));

        assertThat(result.getPeerIdentity()).isEqualTo("proj");
        verify(introspector).introspect("k");
        verify(cache).getOrAuthenticate(eq("k"), any());
        // Routed on the marker, so no OIDC round trip is attempted for it.
        verify(opaqueProvider, never()).authenticate(any());
        verify(jwtProvider, never()).authenticate(any());
    }

    @Test
    void markedBearerValueIsRevalidatedOnEveryCall() {
        // Handshake succeeds; the key is then revoked in Core. Nothing the handshake handed out keeps the
        // connection working once the cached introspection is gone (the cache here always calls through).
        when(introspector.introspect("k"))
                .thenReturn(new IntrospectionResult("proj", null, List.of("admin"), true))
                .thenThrow(new BadCredentialsException("Invalid API key"));
        when(authorityResolver.resolve(List.of("admin"), true))
                .thenReturn(java.util.EnumSet.of(Privilege.TABLE_READ));

        final String handed = outgoingAuthorization(
                authenticator.authenticate(headers("authorization", basic("api-key", "k"))));
        assertThat(handed).isEqualTo(MARKED_BEARER_PREFIX + "k");

        assertThatThrownBy(() -> authenticator.authenticate(headers("authorization", handed)))
                .isInstanceOfSatisfying(FlightRuntimeException.class,
                        e -> assertThat(e.status().code()).isEqualTo(FlightStatusCode.UNAUTHENTICATED));
    }

    @Test
    void blankBearerValueIsUnauthenticatedNotInternal() {
        // "Unauthenticated or rejected credentials SHALL yield gRPC UNAUTHENTICATED": an authorization value
        // of "Bearer " with nothing after it must not escape as an IllegalArgumentException from
        // BearerTokenAuthenticationToken's Assert.hasText, which Arrow would map to a bare INTERNAL.
        for (final String value : List.of("Bearer ", "Bearer    ")) {
            assertThatThrownBy(() -> authenticator.authenticate(headers("authorization", value)))
                    .isInstanceOfSatisfying(FlightRuntimeException.class,
                            e -> assertThat(e.status().code()).isEqualTo(FlightStatusCode.UNAUTHENTICATED));
        }
    }

    @Test
    void basicIntrospectionOutageUnavailable() {
        when(introspector.introspect("k")).thenThrow(new AuthenticationServiceException("Core down"));

        assertThatThrownBy(() -> authenticator.authenticate(headers("authorization", basic("api-key", "k"))))
                .isInstanceOfSatisfying(FlightRuntimeException.class,
                        e -> assertThat(e.status().code()).isEqualTo(FlightStatusCode.UNAVAILABLE));
    }

    @Test
    void resolveAuthenticationSeesTheBasicFormToo() {
        // FlightPrincipalMiddleware carries roles into the producer through this method, so it has to
        // recognise every credential form the gate does.
        coreResolvesToReadOnly("k");

        final Authentication authentication =
                authenticator.resolveAuthentication(headers("authorization", basic("api-key", "k")));

        assertThat(authentication.getName()).isEqualTo("proj");
        assertThat(authentication.getAuthorities()).extracting("authority")
                .as("the shared token builder carries the raw provider roles on this path too, so a "
                        + "per-table grant naming one of them matches over Flight exactly as over HTTP")
                .containsExactlyInAnyOrder("PRIVILEGE_TABLE_READ", "PROVIDER_ROLE_admin");
    }

    /** Stubs Core to resolve {@code key} to the project principal {@code proj} holding TABLE_READ. */
    private void coreResolvesToReadOnly(String key) {
        when(introspector.introspect(key)).thenReturn(
                new IntrospectionResult("proj", null, List.of("admin"), true));
        when(authorityResolver.resolve(List.of("admin"), true))
                .thenReturn(java.util.EnumSet.of(Privilege.TABLE_READ));
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** The {@code authorization} value the result appends to a response, or {@code null} when it adds none. */
    private static String outgoingAuthorization(CallHeaderAuthenticator.AuthResult result) {
        final CallHeaders outgoing = new FlightCallHeaders();
        result.appendToOutgoingHeaders(outgoing);
        return outgoing.get("authorization");
    }
}
