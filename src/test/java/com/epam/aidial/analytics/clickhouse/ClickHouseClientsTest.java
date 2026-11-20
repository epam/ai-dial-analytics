package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.ClientConfigProperties;
import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Which clients cap ClickHouse's Keeper insert retries (openspec/specs/clickhouse-replication/spec.md). */
class ClickHouseClientsTest {

    private static final ClickHouseProperties CONNECTION = new ClickHouseProperties("localhost", 8123, null, "default", "");
    private static final String MAX_RETRIES = ClientConfigProperties.serverSetting(ClickHouseClients.INSERT_KEEPER_MAX_RETRIES_SETTING);
    private static final String MAX_BACKOFF = ClientConfigProperties.serverSetting(ClickHouseClients.INSERT_KEEPER_RETRY_MAX_BACKOFF_MS_SETTING);

    @Test
    void failFastWritersCapTheKeeperRetries() {
        assertCapped(configuration(ClickHouseClients.failFastWriterBuilder(CONNECTION, "writer", "")));
        assertCapped(configuration(ClickHouseClients.failFastWriterBuilder(CONNECTION, "replica-1", 8123, "writer", "")));
    }

    @Test
    void thePlainBuilderKeepsClickHouseDefaults() {
        assertThat(configuration(ClickHouseClients.builder(CONNECTION, "writer", ""))).doesNotContainKeys(MAX_RETRIES, MAX_BACKOFF);
        assertThat(configuration(ClickHouseClients.builder(CONNECTION, "replica-1", 8123, "reader", ""))).doesNotContainKeys(MAX_RETRIES, MAX_BACKOFF);
    }

    private static void assertCapped(Map<String, String> configuration) {
        assertThat(configuration)
                .containsEntry(MAX_RETRIES, ClickHouseClients.INSERT_KEEPER_MAX_RETRIES)
                .containsEntry(MAX_BACKOFF, ClickHouseClients.INSERT_KEEPER_RETRY_MAX_BACKOFF_MS);
    }

    private static Map<String, String> configuration(Client.Builder builder) {
        try (Client client = builder.build()) {
            return new HashMap<>(client.getConfiguration());
        }
    }
}
