package com.epam.aidial.analytics.clickhouse;

/**
 * Runs one catalog-driven DDL statement. {@link ClickHouseDdlExecutor} is the only real implementation;
 * the interface exists so a single call can run with its DDL suppressed, without swapping the shared bean
 * (which would make a concurrent real request skip its DDL too).
 */
@FunctionalInterface
public interface DdlExecutor {

    /**
     * Executes nothing. For a validation pass that runs the real catalog services inside a transaction it
     * then rolls back: the Postgres half is rolled back with it, and there is no ClickHouse half to undo.
     */
    DdlExecutor NO_OP = ddl -> { };

    void execute(String ddl);
}
