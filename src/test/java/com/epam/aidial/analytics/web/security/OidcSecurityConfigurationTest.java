package com.epam.aidial.analytics.web.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OidcSecurityConfigurationTest {

    @Test
    void apiDocsArePublicOnlyWhenSwaggerAuthorizationIsDisabled() {
        assertThat(OidcSecurityConfiguration.publicPaths(false))
                .containsExactly(OidcSecurityConfiguration.OPERATIONAL_PATHS)
                .doesNotContain(OidcSecurityConfiguration.API_DOCS_PATHS);
        assertThat(OidcSecurityConfiguration.publicPaths(true))
                .contains(OidcSecurityConfiguration.OPERATIONAL_PATHS)
                .contains(OidcSecurityConfiguration.API_DOCS_PATHS);
    }

    @Test
    void publicPathsNeverExposeFunctionalEndpoints() {
        assertThat(OidcSecurityConfiguration.publicPaths(true)).noneMatch(path -> path.startsWith("/v1"));
    }
}
