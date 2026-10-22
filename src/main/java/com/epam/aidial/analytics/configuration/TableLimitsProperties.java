package com.epam.aidial.analytics.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the catalog's lightweight guardrails ({@code tables.limits.*}), env-overridable
 * via {@code application.yml}. {@code maxColumnsPerTable} is enforced when creating a source and when a
 * {@code PATCH} adds columns; {@code maxTables} is enforced at create; {@code maxWriteBatchRows} caps a
 * single row-write batch. Violations are rejected with HTTP 422.
 *
 * @param maxColumnsPerTable maximum exposed columns per table (default 256)
 * @param maxTables          maximum table definitions in the catalog (default 1000)
 * @param maxWriteBatchRows  maximum rows accepted in one {@code POST .../rows} batch (default 10000)
 */
@ConfigurationProperties(prefix = "tables.limits")
public record TableLimitsProperties(int maxColumnsPerTable, int maxTables, int maxWriteBatchRows) {
}
