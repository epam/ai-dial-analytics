package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.dashboard.DashboardAccess;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base for integration tests that exercise the PostgreSQL table catalog over the full Spring Boot
 * application but do not need ClickHouse. Boots {@code @SpringBootTest} + {@code @AutoConfigureMockMvc}
 * against the singleton PostgreSQL Testcontainer ({@link PostgresContainerSupport}); the real Boot
 * Flyway provisioning of the catalog tables is part of the path under test:
 * {@code MockMvc -> TableController -> table catalog services -> repositories -> PostgreSQL}.
 *
 * <p>ClickHouse-touching startup work is switched off (schema migrations, sync schema validation, the
 * poller) so no ClickHouse backend is required — the ClickHouse client is created lazily and nothing
 * connects to it during these tests.
 *
 * <p>Requires a running Docker daemon.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "clickhouse.migrations.enabled=false",
        // No ClickHouse here: the startup engine check finds nothing to reach, so the storage gate stays closed.
        "clickhouse.port=1",
        "sync.validate-schema=false",
        "poll.enabled=false"
})
@AutoConfigureMockMvc
@Import(OpenStorageGate.class)
abstract class AbstractPostgresIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    /**
     * A spy so {@link DashboardWriteRaceIntegrationTest} can hold a write between its check and its commit. Declared
     * here rather than there because a bean override is part of the test-context cache key: on one subclass it would
     * fork a second context. Unstubbed it calls the real bean, and Mockito resets it after every test.
     */
    @MockitoSpyBean
    protected DashboardAccess dashboardAccess;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
    }
}
