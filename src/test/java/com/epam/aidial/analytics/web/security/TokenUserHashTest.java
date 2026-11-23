package com.epam.aidial.analytics.web.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Both token converters put the provider's user hash on the caller identity: the obfuscated form for a
 * string claim, and none when the claim is missing.
 */
class TokenUserHashTest {

    private static final String SHA256_OF_S1_USER1 = "34d2ba3434e49df221c0150d3d11ef737bac223e81cce74ff59f5873429375d9";

    private final UserHasher hasher = UserHasher.of("providers.p", "email", "s1", true);
    private final UserRolesResolver roles = mock(UserRolesResolver.class);

    @Test
    void jwtCarriesTheObfuscatedHashOfItsClaim() {
        final JwtAuthenticationConverter converter =
                new JwtAuthenticationConverter(jwt -> List.of(), roles, "sub", Set.of("email"), false, hasher);

        assertThat(userHash(converter.convert(jwt(Map.of("sub", "u1", "email", "user1@example.com")))))
                .isEqualTo(SHA256_OF_S1_USER1);
        assertThat(userHash(converter.convert(jwt(Map.of("sub", "u1"))))).isNull();
    }

    @Test
    void opaqueTokenCarriesTheObfuscatedHashOfItsClaim() {
        final OpaqueAuthenticationConverter converter = new OpaqueAuthenticationConverter(roles, Set.of("email"), false, hasher);

        assertThat(userHash(converter.convert("t", principal(Map.of("sub", "u1", "email", "user1@example.com")))))
                .isEqualTo(SHA256_OF_S1_USER1);
        assertThat(userHash(converter.convert("t", principal(Map.of("sub", "u1"))))).isNull();
    }

    private static String userHash(Authentication authentication) {
        return ((UserSecurityDetails) authentication.getDetails()).userHash();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("t").header("alg", "none").issuer("https://issuer.test/p")
                .claims(all -> all.putAll(claims)).build();
    }

    private static DefaultOAuth2AuthenticatedPrincipal principal(Map<String, Object> attributes) {
        return new DefaultOAuth2AuthenticatedPrincipal(attributes, List.of());
    }
}
