package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.data.ClickHouseFormat;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The row writer behind {@code POST /v1/tables/{name}/rows}, the pipeline write-back and OTel ingest caps ClickHouse's
 * Keeper retries on each insert, so a waiting caller hears {@code 503} within seconds with Keeper gone
 * (openspec/specs/clickhouse-replication/spec.md).
 */
class CatalogRowWriterTest {

    private static final String TABLE = "widgets";

    @Test
    void insertCapsKeeperRetries() {
        final Client client = mock(Client.class);
        when(client.insert(eq(TABLE), any(InputStream.class), eq(ClickHouseFormat.JSONEachRow), any(InsertSettings.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(InsertResponse.class)));
        final CatalogRowWriter writer = new CatalogRowWriter(null, null, JsonMapper.builder().build(), TestClickHouseSpans.noop()) {
            @Override
            Client client() {
                return client;
            }
        };

        writer.insert(TABLE, List.of(Map.of("widget_id", "w1")));

        final ArgumentCaptor<InsertSettings> settings = ArgumentCaptor.forClass(InsertSettings.class);
        verify(client).insert(eq(TABLE), any(InputStream.class), eq(ClickHouseFormat.JSONEachRow), settings.capture());
        KeeperRetriesAssert.assertCapped(settings.getValue().getAllSettings());
    }
}
