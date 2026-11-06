package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import com.epam.aidial.analytics.exception.ClickHouseQueryException;
import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The only component that knows about ClickHouse. Opens a read-only HTTP connection from
 * configuration and runs read queries against it on demand (no caching). Failures ClickHouse could not
 * serve — transport, and server-side resource exhaustion — are translated into
 * {@link ClickHouseUnavailableException}; statements it rejected surface as
 * {@link ClickHouseQueryException}. {@link ClickHouseFailures} draws the line. Raw ClickHouse messages and
 * stack traces are never propagated to callers.
 *
 * <p>The underlying {@link Client} is created lazily on first use so the service still boots (and
 * stays healthy) when ClickHouse is unreachable; the failure then surfaces per-request as a 503.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ClickHouseClientWrapper {

    private final ClickHouseProperties properties;
    private final ClickHouseSpans clickHouseSpans;
    private volatile Client client;

    /**
     * Runs an arbitrary read query and returns the raw records. Transport failures and server-side
     * resource exhaustion surface as {@link ClickHouseUnavailableException} (503); any other failure is
     * treated as a query error ({@link ClickHouseQueryException}, mapped to 400), since the rendered SQL is
     * the only client-controlled input on this path. Raw ClickHouse messages are never propagated to
     * callers.
     *
     * <p><b>Not for pipeline statements.</b> It takes no settings, so a statement run through it carries no
     * {@code pipeline.budget.*} ceiling and no query id; every pipeline read goes through
     * {@link #query(String, QuerySettings)}.
     *
     * @param sql a complete, self-contained SQL statement (parameters already inlined)
     */
    public List<GenericRecord> query(String sql) {
        return execute(null, () -> client().queryAll(sql));
    }

    /**
     * Runs a read query with per-call ClickHouse query settings (e.g. {@code final = 1} to force
     * latest-version-wins deduplication of {@code ReplacingMergeTree} reads). Error translation is
     * identical to {@link #query(String)}. The plain {@link #query(String)} keeps today's behavior for
     * callers that must not carry these settings (schema validation, data-sync reads).
     *
     * @param sql      a complete, self-contained SQL statement (parameters already inlined)
     * @param settings the per-query ClickHouse settings to run the statement under
     */
    public List<GenericRecord> query(String sql, QuerySettings settings) {
        return execute(settings.getQueryId(), () -> client().queryAll(sql, settings));
    }

    /**
     * As {@link #query(String, QuerySettings)}, binding {@code params} server-side ({@code {name:Type}} placeholders in
     * {@code sql}). For statements ADAS writes itself whose values come from data it read, never from a caller.
     */
    public List<GenericRecord> query(String sql, Map<String, Object> params, QuerySettings settings) {
        return execute(settings.getQueryId(), () -> client().queryAll(sql, params, settings));
    }

    private List<GenericRecord> execute(String queryId, Supplier<List<GenericRecord>> call) {
        return clickHouseSpans.query(queryId, () -> {
            try {
                return call.get();
            } catch (Exception ex) {
                if (ClickHouseFailures.isUnavailable(ex)) {
                    throw new ClickHouseUnavailableException("ClickHouse is unreachable", ex);
                }
                throw new ClickHouseQueryException("ClickHouse rejected the query", ex);
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
        return ClickHouseClients.builder(properties, properties.user(), properties.password()).build();
    }

    @PreDestroy
    void shutdown() {
        Client local = client;
        if (local != null) {
            try {
                local.close();
            } catch (Exception ex) {
                log.debug("Error closing ClickHouse client", ex);
            }
        }
    }
}
