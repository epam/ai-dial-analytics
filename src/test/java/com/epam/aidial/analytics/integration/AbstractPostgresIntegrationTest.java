package com.epam.aidial.analytics.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
        "sync.validate-schema=false",
        "poll.enabled=false"
})
@AutoConfigureMockMvc
abstract class AbstractPostgresIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
    }
}
