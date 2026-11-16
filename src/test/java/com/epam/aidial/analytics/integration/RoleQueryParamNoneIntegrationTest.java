package com.epam.aidial.analytics.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** {@link AbstractRoleQueryParamIntegrationTest} in {@code none} mode, where every request is permitted. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "clickhouse.migrations.enabled=false",
        // No ClickHouse here: the startup engine check finds nothing to reach, so the storage gate stays closed.
        "clickhouse.port=1",
        "sync.validate-schema=false",
        "poll.enabled=false",
        "security.mode=none",
})
@Import(OpenStorageGate.class)
class RoleQueryParamNoneIntegrationTest extends AbstractRoleQueryParamIntegrationTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
    }

    @Override
    String adminAuthorization() {
        return null;
    }
}
