package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.Client;
import com.epam.aidial.analytics.exception.ClickHouseQueryException;
import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import com.epam.aidial.analytics.observability.SqlLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Runs catalog-driven DDL ({@code CREATE TABLE} / {@code DROP TABLE}) against ClickHouse using the
 * dedicated <em>writer</em> credential ({@code clickhouse.writer.*}), via the shared
 * {@link ClickHouseWriterClient}. This is the only runtime DDL path in ADAS (migrations aside); the
 * read/query client ({@link ClickHouseClientWrapper}) stays strictly read-only.
 *
 * <p>Connectivity failures surface as {@link ClickHouseUnavailableException} (503); a statement that
 * ClickHouse rejects surfaces as {@link ClickHouseQueryException} so the caller can mark the table
 * {@code FAILED}. Raw ClickHouse messages are never propagated to API callers.
 */
@Component
@RequiredArgsConstructor
public class ClickHouseDdlExecutor implements DdlExecutor {

    private final ClickHouseWriterClient writer;
    private final ClickHouseSpans clickHouseSpans;

    /**
     * Executes a single DDL statement (already a complete, self-contained SQL string with identifiers
     * quoted). Returns normally on success.
     *
     * @throws ClickHouseUnavailableException if ClickHouse cannot be reached
     * @throws ClickHouseQueryException       if ClickHouse rejects the statement
     */
    @Override
    public void execute(String ddl) {
        // On the SQL logger like every other statement text: a column default or TTL can inline a literal.
        SqlLog.LOG.debug("Executing ClickHouse DDL: {}", ddl);
        clickHouseSpans.ddl(() -> {
            final Client client = writer.client();
            try {
                client.queryAll(ddl);
            } catch (Exception ex) {
                if (ClickHouseFailures.isUnavailable(ex)) {
                    throw new ClickHouseUnavailableException("ClickHouse is unreachable", ex);
                }
                throw new ClickHouseQueryException("ClickHouse rejected the DDL statement", ex);
            }
        });
    }
}
