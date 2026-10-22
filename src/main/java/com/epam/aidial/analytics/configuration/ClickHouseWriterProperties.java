package com.epam.aidial.analytics.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the ClickHouse <em>writer</em> credential ({@code clickhouse.writer.*}) used only by
 * the {@code data-sync} ingest path. Host, port, and database are reused from {@link ClickHouseProperties};
 * only the credential differs so the write grant stays isolated from the read-only query/discovery path.
 * The writer user needs INSERT on the target table; it is supplied from the deployment's secret store.
 *
 * @param user     ClickHouse writer user
 * @param password ClickHouse writer password
 */
@ConfigurationProperties(prefix = "clickhouse.writer")
public record ClickHouseWriterProperties(String user, String password) {

    /** Redacts {@code password} so an accidental log or actuator dump never leaks the credential. */
    @Override
    public String toString() {
        return "ClickHouseWriterProperties[user=" + user + ", password=***]";
    }
}
