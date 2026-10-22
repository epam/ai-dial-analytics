package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.Client;
import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import com.epam.aidial.analytics.configuration.ClickHouseWriterProperties;
import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The single ClickHouse client on the dedicated <em>writer</em> credential ({@code clickhouse.writer.*}),
 * shared by every statement-issuing path that is not the read/query client: catalog DDL
 * ({@link ClickHouseDdlExecutor}) and the Class-1 {@code INSERT … SELECT}
 * ({@link ClickHouseInsertSelectExecutor}). The read path ({@link ClickHouseClientWrapper}) stays
 * strictly read-only on its own credential.
 *
 * <p>The underlying {@link Client} is created lazily on first use, so the service boots even when
 * ClickHouse is unreachable — and created once, so the writer credential opens one connection pool
 * rather than one per calling component.
 *
 * <p>The 503-versus-4xx distinction that every caller of this client has to draw lives in
 * {@link ClickHouseFailures}, not here: it must be drawn identically whatever statement failed, and the
 * callers differ only in the message they attach.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ClickHouseWriterClient {

    /**
     * The {@code async_insert} server setting, which every row-inserting client in this package pins to
     * {@code 0}. ClickHouse defaults it to {@code 1} from 26.2, and the async buffer evaluates a column
     * {@code DEFAULT} once per <em>flush</em> rather than once per {@code INSERT} — while both dedup version
     * columns ({@code dial_usage_log._ingested_at} and a catalog table's {@code _updated_at}) are exactly
     * such a default, {@code DEFAULT now64(3)}, that no client supplies. Measured on 26.3.21.7: eight
     * concurrent inserts of one key inside the 200 ms {@code async_insert_busy_timeout_ms} window shared
     * one stamp, so {@code ReplacingMergeTree} had no ordering and kept an arbitrary row of the eight; with
     * the pin the same eight were stamped a millisecond apart and the newest won.
     *
     * <p>The pin buys ordering at the version column's own resolution and no finer: two writes of one key
     * inside the same millisecond tie either way, and on a tie the surviving row is unspecified. So this
     * restores the semantics the deduplication design was verified against — it does not make
     * {@code table-management}'s version contract hold for arbitrarily concurrent writers.
     *
     * <p>Pinning {@code 0} is a no-op on any server older than 26.2. It is deliberately <em>not</em> pinned
     * on this class's own client: ClickHouse routes neither {@code INSERT … SELECT} (the Class-1 path) nor
     * DDL through the async buffer — verified on 26.3 — so there is nothing to pin there. The Flyway
     * migration path is insulated by the JDBC driver, which pins the same setting itself (see build.gradle).
     */
    static final String ASYNC_INSERT = "async_insert";

    private final ClickHouseProperties properties;
    private final ClickHouseWriterProperties writerProperties;
    private volatile Client client;

    /** The writer client, built on first use. */
    public Client client() {
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

    /**
     * Wraps an interrupt as {@link ClickHouseUnavailableException} (503) after restoring the thread's
     * interrupt flag — a shared helper so no caller drops the flag on the floor.
     */
    public static ClickHouseUnavailableException interrupted(String message, InterruptedException ex) {
        Thread.currentThread().interrupt();
        return new ClickHouseUnavailableException(message, ex);
    }

    private Client buildClient() {
        return ClickHouseClients.builder(properties, writerProperties.user(), writerProperties.password())
                .build();
    }

    @PreDestroy
    void shutdown() {
        final Client local = client;
        if (local != null) {
            try {
                local.close();
            } catch (Exception ex) {
                log.debug("Error closing the ClickHouse writer client", ex);
            }
        }
    }
}
