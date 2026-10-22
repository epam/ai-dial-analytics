package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.data.ClickHouseFormat;
import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import com.epam.aidial.analytics.configuration.ClickHouseWriterProperties;
import com.epam.aidial.analytics.exception.ClickHouseQueryException;
import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Writes arbitrary rows into a catalog table by name, using the dedicated <em>writer</em> credential
 * ({@code clickhouse.writer.*}) — the minimal data-write surface behind {@code POST
 * /v1/tables/{name}/rows}. Rows are serialized as {@code JSONEachRow} (one JSON object per row, keyed
 * by physical ClickHouse column name) and inserted in a single batch. The {@code table} name is a
 * catalog identifier already validated by {@link com.epam.aidial.analytics.service.table.Identifiers},
 * so it is safe to pass through. The underlying {@link Client} is created lazily on first use, mirroring
 * {@link ClickHouseDdlExecutor} / {@link UsageLogWriter}.
 *
 * <p>Rows arrive already validated and normalized by
 * {@link com.epam.aidial.analytics.service.table.TableDataService} — in particular a {@code timestamp}
 * value is rendered into the {@code DateTime64} storage spelling there, so this writer serializes what it
 * is handed and never inspects a value.
 *
 * <p>Connectivity failures <em>and server-side resource exhaustion</em> surface as
 * {@link ClickHouseUnavailableException} (503); a batch ClickHouse rejects (unknown column, type mismatch)
 * surfaces as {@link ClickHouseQueryException} (400). {@link ClickHouseFailures} draws the line. The
 * resource half matters most on this path: the caller is the enrichment runner writing computed rows, and a
 * 400 tells it the rows are bad and must be discarded — so an insert that failed because ClickHouse was out
 * of memory used to destroy work that was never wrong. Raw ClickHouse messages are never propagated to API
 * callers.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CatalogRowWriter {

    private final ClickHouseProperties properties;
    private final ClickHouseWriterProperties writerProperties;
    private final JsonMapper jsonMapper;
    private final ClickHouseSpans clickHouseSpans;
    private volatile Client client;

    /**
     * Inserts {@code rows} into {@code table} as a single {@code JSONEachRow} batch (a no-op if empty).
     *
     * @throws ClickHouseUnavailableException if ClickHouse cannot be reached
     * @throws ClickHouseQueryException       if ClickHouse rejects the batch
     */
    public void insert(String table, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        final StringBuilder payload = new StringBuilder();
        for (final Map<String, Object> row : rows) {
            payload.append(jsonMapper.writeValueAsString(row)).append('\n');
        }
        final byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        clickHouseSpans.insert(table, () -> {
            try (InsertResponse response = client()
                    .insert(table, new ByteArrayInputStream(bytes), ClickHouseFormat.JSONEachRow)
                    .get()) {
                log.debug("Inserted {} rows into {}", rows.size(), table);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ClickHouseUnavailableException("Interrupted while inserting into ClickHouse", ex);
            } catch (Exception ex) {
                if (ClickHouseFailures.isUnavailable(ex)) {
                    throw new ClickHouseUnavailableException("ClickHouse is unreachable", ex);
                }
                throw new ClickHouseQueryException("ClickHouse rejected the insert", ex);
            }
        });
    }

    private Client client() {
        Client local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    local = buildClient();
                    client = local;
                }
            }
        }
        return local;
    }

    private Client buildClient() {
        return ClickHouseClients.builder(properties, writerProperties.user(), writerProperties.password())
                .serverSetting(ClickHouseWriterClient.ASYNC_INSERT, "0")
                // A write-back batch into an aligned enrichment may span more day partitions than the default 100.
                .serverSetting(ClickHouseInsertSelectExecutor.MAX_PARTITIONS_PER_INSERT_BLOCK, "0")
                .build();
    }

    @PreDestroy
    void shutdown() {
        final Client local = client;
        if (local != null) {
            try {
                local.close();
            } catch (Exception ex) {
                log.debug("Error closing ClickHouse row-writer client", ex);
            }
        }
    }
}
