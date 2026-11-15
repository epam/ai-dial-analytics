package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.JwtTestTokens;
import com.epam.aidial.analytics.web.security.TestSecurityConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

/** {@link AbstractEncodedPathIntegrationTest} in {@code oidc} mode, with the API docs public. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "clickhouse.migrations.enabled=false",
        "sync.validate-schema=false",
        "poll.enabled=false",
        "security.mode=oidc",
        "security.disable-swagger-authorization=true",
        "providers.full.issuer=" + EncodedPathOidcIntegrationTest.ISSUER,
        "providers.full.jwk-set-uri=https://issuer.test/full/jwks",
        "providers.full.audiences=" + EncodedPathOidcIntegrationTest.AUDIENCE,
        "providers.full.role-claims=roles",
        "providers.full.roles-mapping={\"admin\":[\"FULL_ADMIN\"]}",
})
@Import(TestSecurityConfig.class)
class EncodedPathOidcIntegrationTest extends AbstractEncodedPathIntegrationTest {

    static final String ISSUER = "https://issuer.test/full";
    static final String AUDIENCE = "adas-test";

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
    }

    @Override
    String adminAuthorization() {
        return "Bearer " + JwtTestTokens.token(ISSUER, AUDIENCE,
                Map.of("sub", "full-user", "roles", List.of("admin"), "email", "full@test"));
    }
}
