package com.epam.aidial.analytics.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Singleton ClickHouse Testcontainer shared by every full-context integration test that exercises the
 * query/sync path, mirroring {@link PostgresContainerSupport}. Started once in a static initializer and
 * reused for the JVM lifetime (Testcontainers' Ryuk reaper removes it on exit); container startup
 * dominates wall-clock, so reuse keeps the suite fast.
 *
 * <p>{@link #registerProperties(DynamicPropertyRegistry)} wires the container into {@code clickhouse.*}
 * — including the writer credential, which reuses the container user since it holds the DDL grant
 * needed to migrate — and leaves migrations enabled so the real {@code ClickHouseMigrationRunner}/Flyway
 * provisioning of {@code dial_usage_log} runs (that provisioning is part of the plumbing under test).
 * The single HTTP/8123 port serves both the client-v2 query path and the Flyway (JDBC) migration path.
 *
 * <p>{@link #CLICKHOUSE} and {@link #HTTP_PORT} are exposed for the in-JVM fixture writer that
 * {@link AbstractClickHouseIntegrationTest} builds against the same container.
 *
 * <p>Requires a running Docker daemon.
 */
final class ClickHouseContainerSupport {

    /** ClickHouse HTTP port — exposed by the container and used by both the client-v2 and JDBC paths. */
    static final int HTTP_PORT = 8123;

    static final ClickHouseContainer CLICKHOUSE =
            new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:26.8"))
                    // Pin the server timezone so DateTime64 literals/inserts compare deterministically.
                    // Production correctness no longer rests on this: ADAS declares its timestamp columns
                    // DateTime64(3, 'UTC') and compiles time buckets with an explicit 'UTC', so neither
                    // depends on the server's zone. The consequence for this suite is that it CANNOT catch a
                    // regression there — a bucket that fell back to the server's zone would still align at UTC
                    // here. The render-level assertions in AggregateCompilerTest and DialUsageLogQueryRenderTest
                    // are the guard for that.
                    .withEnv("TZ", "UTC")
                    // Encryption key for the AES_256_GCM_SIV codec on the body columns; without it the
                    // hardened migration's CREATE TABLE fails. Throwaway test key (see the resource).
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("clickhouse/encryption.xml"),
                            "/etc/clickhouse-server/config.d/encryption.xml");

    static {
        CLICKHOUSE.start();
    }

    private ClickHouseContainerSupport() {
    }

    /** Wires the singleton container's host/port/credentials into {@code clickhouse.*}. */
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("clickhouse.host", CLICKHOUSE::getHost);
        registry.add("clickhouse.port", () -> CLICKHOUSE.getMappedPort(HTTP_PORT));
        registry.add("clickhouse.database", CLICKHOUSE::getDatabaseName);
        registry.add("clickhouse.user", CLICKHOUSE::getUsername);
        registry.add("clickhouse.password", CLICKHOUSE::getPassword);
        registry.add("clickhouse.writer.user", CLICKHOUSE::getUsername);
        registry.add("clickhouse.writer.password", CLICKHOUSE::getPassword);
        registry.add("clickhouse.migrations.enabled", () -> "true");
    }
}
