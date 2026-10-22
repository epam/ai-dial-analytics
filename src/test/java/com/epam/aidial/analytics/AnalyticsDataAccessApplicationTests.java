package com.epam.aidial.analytics;

import com.epam.aidial.analytics.integration.PostgresContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// ClickHouse migrations and startup schema validation are disabled here so the context starts without
// a live ClickHouse (the migration runner and the schema validator otherwise hit ClickHouse at
// startup). The poller is disabled so its scheduled cycle does not fire against a real backend during
// the test. Strict validation is exercised in SchemaValidatorTest. PostgreSQL is required, however:
// the table catalog's DataSource + Boot Flyway run at startup, so a singleton PostgreSQL container is
// wired in via @DynamicPropertySource.
@SpringBootTest(properties = {
        "clickhouse.migrations.enabled=false",
        "sync.validate-schema=false",
        "poll.enabled=false"
})
class AnalyticsDataAccessApplicationTests {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
    }

    @Test
    void contextLoads() {
        // Verifies the Spring application context starts with the configured beans.
    }
}
