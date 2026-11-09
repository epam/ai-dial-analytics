package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.clickhouse.replication.StorageReadyGate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;

/**
 * Opens the storage gate once the context is ready, for a test that boots without ClickHouse and exercises only the
 * PostgreSQL-backed endpoints. Without it the gate stays closed, as it does for a real instance whose ClickHouse was
 * unreachable at startup, and every {@code /v1} request answers {@code 503}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class OpenStorageGate {

    @Bean
    ApplicationListener<ApplicationReadyEvent> openStorageGateWhenReady(StorageReadyGate gate) {
        return event -> gate.open();
    }
}
