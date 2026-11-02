package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.TestSecurityConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base for the authorization integration tests that drive the REST surface in {@code oidc} mode over
 * both containers. Boots {@code @SpringBootTest} + {@code @AutoConfigureMockMvc} against the singleton
 * PostgreSQL and ClickHouse containers, with two locally-signed JWT issuers — one mapping to
 * {@code FULL_ADMIN}, one to {@code READ_ONLY_ADMIN} — plus an opaque provider introspected against
 * {@link OpaqueUserInfoServerSupport}. Tokens are minted with
 * {@link com.epam.aidial.analytics.web.security.JwtTestTokens} and verified against the HMAC secret
 * {@link TestSecurityConfig} installs, so no live JWKS endpoint is needed.
 *
 * <p><strong>Why this is a base class and not four copies of the same annotations.</strong> The Spring
 * test-context cache keys on the {@code @SpringBootTest} property array <em>and</em> on the set of
 * {@code @DynamicPropertySource} methods ({@code DynamicPropertiesContextCustomizer} compares those
 * methods), so two classes declaring byte-identical properties still cost <em>two</em> cached
 * contexts if each declares its own registration method. Every cached context is retained for the
 * JVM's lifetime with its own Hikari pool, and the suite runs one worker — so a near-duplicate context
 * is paid for in heap by every test that runs after it. Declaring the configuration here once means
 * the subclasses share a single context.
 *
 * <p>The corollary is that a subclass must not add its own {@code @SpringBootTest} properties or
 * {@code @DynamicPropertySource} method — either one forks the context and gives the duplication back.
 * A property only one subclass reads is cheaper added to this shared set (it is inert for the others)
 * than paid for as a second context; see the same reasoning in
 * {@link AbstractClickHouseIntegrationTest}.
 *
 * <p>Requires a running Docker daemon.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        // MockMvc never starts the management server, so the actuator stays on the main port here;
        // HealthProbesIntegrationTest covers the separate port.
        "management.server.port=",
        "poll.enabled=false",
        "security.mode=oidc",
        "security.defaults.email-claim=email",
        "security.defaults.principal-claim=sub",
        "providers.full.issuer=" + AbstractOidcAuthIntegrationTest.FULL_ISSUER,
        "providers.full.jwk-set-uri=https://issuer.test/full/jwks",
        "providers.full.audiences=" + AbstractOidcAuthIntegrationTest.AUDIENCE,
        "providers.full.role-claims=roles",
        // `registry` is an author who may write pipelines but not read sensitive columns — the persona the
        // governance gate's entitlement checks exist for, and one no bundle expresses.
        // `granter` manages grant lists and nothing else, which no bundle expresses either.
        // `exporter` and `importer` hold only the catalog transfer endpoints' own gates, so every per-table and
        // repeated check behind them is decided by grants or refused.
        // `drafter` may author pipelines and see a table's schema but not read its rows: the persona a copied
        // preview statement must not let through.
        "providers.full.roles-mapping={\"admin\":[\"FULL_ADMIN\"],"
                + "\"registry\":[\"REGISTRY_ADMIN\",\"TABLE_READ\"],\"granter\":[\"TABLE_GRANT\"],"
                + "\"exporter\":[\"REGISTRY_READ\"],\"importer\":[\"TABLE_CREATE\"],"
                + "\"drafter\":[\"REGISTRY_ADMIN\",\"TABLE_GET\"]}",
        "providers.readonly.issuer=" + AbstractOidcAuthIntegrationTest.READONLY_ISSUER,
        "providers.readonly.jwk-set-uri=https://issuer.test/readonly/jwks",
        "providers.readonly.audiences=" + AbstractOidcAuthIntegrationTest.AUDIENCE,
        "providers.readonly.role-claims=roles",
        "providers.readonly.roles-mapping={\"viewer\":[\"READ_ONLY_ADMIN\"]}",
})
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
abstract class AbstractOidcAuthIntegrationTest {

    static final String AUDIENCE = "adas-test";
    static final String FULL_ISSUER = "https://issuer.test/full";
    static final String READONLY_ISSUER = "https://issuer.test/readonly";

    @Autowired
    protected MockMvc mockMvc;

    @DynamicPropertySource
    static void oidcAuthProperties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        ClickHouseContainerSupport.registerProperties(registry);
        OpaqueUserInfoServerSupport.registerProperties(registry);
    }

    static String bearer(String token) {
        return "Bearer " + token;
    }
}
