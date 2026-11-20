package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.ClientConfigProperties;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Asserts that a statement's settings cap ClickHouse's Keeper retries as {@link KeeperRetries} does. */
final class KeeperRetriesAssert {

    private KeeperRetriesAssert() {
    }

    static void assertCapped(Map<String, Object> settings) {
        assertThat(settings)
                .containsEntry(ClientConfigProperties.serverSetting(KeeperRetries.MAX_RETRIES_SETTING), "8")
                .containsEntry(ClientConfigProperties.serverSetting(KeeperRetries.MAX_BACKOFF_MS_SETTING), "2000");
    }
}
