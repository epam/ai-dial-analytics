package com.epam.aidial.analytics.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the ClickHouse connection ({@code clickhouse.*}). Values are env-driven via
 * {@code application.yml}; nothing is hard-coded. The configured user only needs read access to
 * {@code system.tables} and the target databases.
 *
 * @param host     ClickHouse host
 * @param port     ClickHouse HTTP port (default {@code 8123})
 * @param database optional single target database; when blank, all non-system databases are listed
 * @param user     ClickHouse user
 * @param password ClickHouse password
 */
@ConfigurationProperties(prefix = "clickhouse")
public record ClickHouseProperties(String host, int port, String database, String user, String password) {

    /**
     * The database every client session resolves unqualified table names against: {@code database} trimmed,
     * or ClickHouse's {@code default} when it is blank. The one spelling of that rule, shared by the client
     * builder and by anything that must name a table the way a rendered statement's unqualified reference
     * resolves — the row-policy filter keys its entries by it.
     */
    public String sessionDatabase() {
        return database == null || database.isBlank() ? "default" : database.trim();
    }

    /** Redacts {@code password} so an accidental log or actuator dump never leaks the credential. */
    @Override
    public String toString() {
        return "ClickHouseProperties[host=" + host + ", port=" + port + ", database=" + database
                + ", user=" + user + ", password=***]";
    }
}
