package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.JwtTestTokens;
import com.epam.aidial.analytics.web.security.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link AbstractRoleQueryParamIntegrationTest} in {@code oidc} mode. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "clickhouse.migrations.enabled=false",
        "sync.validate-schema=false",
        "poll.enabled=false",
        "security.mode=oidc",
        "providers.full.issuer=" + RoleQueryParamOidcIntegrationTest.ISSUER,
        "providers.full.jwk-set-uri=https://issuer.test/full/jwks",
        "providers.full.audiences=" + RoleQueryParamOidcIntegrationTest.AUDIENCE,
        "providers.full.role-claims=roles",
        "providers.full.roles-mapping={\"admin\":[\"FULL_ADMIN\"]}",
})
@Import(TestSecurityConfig.class)
class RoleQueryParamOidcIntegrationTest extends AbstractRoleQueryParamIntegrationTest {

    static final String ISSUER = "https://issuer.test/full";
    static final String AUDIENCE = "adas-test";

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
    }

    /** A caller without TABLE_GRANT gets 400 for a missing or blank role, and 403 only once the role is usable. */
    @Test
    void missingOrBlankRoleIsBadRequestBeforeThePrivilegeCheck() throws Exception {
        final String viewer = "Bearer " + JwtTestTokens.token(ISSUER, AUDIENCE,
                Map.of("sub", "viewer-user", "roles", List.of("viewer"), "email", "viewer@test"));
        assertThat(send("PUT", accessPath(), entry(0), viewer).statusCode()).isEqualTo(400);
        assertThat(send("DELETE", accessPath(), null, viewer).statusCode()).isEqualTo(400);
        assertThat(send("PUT", accessPath() + "?role=%20", entry(0), viewer).statusCode()).isEqualTo(400);
        assertThat(send("DELETE", accessPath() + "?role=%20", null, viewer).statusCode()).isEqualTo(400);
        assertThat(send("GET", accessPath() + "?role=%20", null, viewer).statusCode()).isEqualTo(400);
        assertThat(send("PUT", accessPath() + "?role=x", entry(0), viewer).statusCode()).isEqualTo(403);
    }

    @Override
    String adminAuthorization() {
        return "Bearer " + JwtTestTokens.token(ISSUER, AUDIENCE,
                Map.of("sub", "full-user", "roles", List.of("admin"), "email", "full@test"));
    }
}
