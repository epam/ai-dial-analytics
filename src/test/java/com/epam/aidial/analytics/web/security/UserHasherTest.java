package com.epam.aidial.analytics.web.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The user hash has to equal what DIAL Core writes to its usage log, so the formula is pinned against a
 * digest computed outside this code: lowercase hex SHA-256 of the salt followed by the claim.
 */
class UserHasherTest {

    private static final String SHA256_OF_S1_USER1 = "34d2ba3434e49df221c0150d3d11ef737bac223e81cce74ff59f5873429375d9";

    @Test
    void hashIsSha256OfSaltThenClaimInLowercaseHex() {
        assertThat(UserHasher.of("providers.p", "email", "s1", null).hash("user1@example.com"))
                .isEqualTo(SHA256_OF_S1_USER1);
    }

    @Test
    void obfuscationOffYieldsTheClaimItself() {
        assertThat(UserHasher.of("providers.p", "email", null, false).hash("user1@example.com"))
                .isEqualTo("user1@example.com");
    }

    @Test
    void missingNonStringAndBlankClaimsYieldNoHash() {
        final UserHasher hasher = UserHasher.of("providers.p", "email", "s1", true);

        assertThat(hasher.hash(null)).isNull();
        assertThat(hasher.hash(List.of("user1@example.com"))).isNull();
        // Not hashed: every user with a blank claim would share one hash.
        assertThat(hasher.hash("")).isNull();
        assertThat(hasher.hash("  ")).isNull();
    }

    @Test
    void listShapedClaimMustHoldExactlyOneString() {
        final UserHasher hasher = UserHasher.of("security.api-key", "email", "s1", true);

        assertThat(hasher.hashSingle(List.of("user1@example.com"))).isEqualTo(SHA256_OF_S1_USER1);
        assertThat(hasher.hashSingle(List.of("a@example.com", "b@example.com"))).isNull();
        assertThat(hasher.hashSingle(List.of())).isNull();
        assertThat(hasher.hashSingle(null)).isNull();
    }

    @Test
    void noClaimConfiguredDerivesNothing() {
        final UserHasher hasher = UserHasher.of("providers.p", " ", "s1", true);

        assertThat(hasher.loggingKey()).isNull();
        assertThat(hasher.hash("user1@example.com")).isNull();
    }

    @Test
    void claimWithoutSaltFailsNamingTheSettings() {
        assertThatThrownBy(() -> UserHasher.of("providers.p", "email", "", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("providers.p.logging-key")
                .hasMessageContaining("providers.p.logging-salt");
    }

    @Test
    void saltIsNotPrinted() {
        assertThat(UserHasher.of("providers.p", "email", "s1-secret", true).toString()).doesNotContain("s1-secret");
    }
}
