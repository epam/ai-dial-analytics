package com.epam.aidial.analytics.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Singleton PostgreSQL Testcontainer shared by every full-context ({@code @SpringBootTest})
 * integration test. Introducing the metadata store gave ADAS its first JDBC {@code DataSource}, so the
 * Spring context now needs PostgreSQL reachable at startup — Boot's Flyway auto-configuration migrates
 * eagerly and JPA needs the {@code DataSource}. The container is started once in a static initializer
 * and reused for the JVM lifetime (Testcontainers' Ryuk reaper removes it on exit); container startup
 * dominates wall-clock, so reuse keeps the suite fast.
 *
 * <p>{@link #registerProperties(DynamicPropertyRegistry)} wires the container into
 * {@code spring.datasource.*} and leaves Flyway enabled so the real {@code metadata} provisioning runs
 * — that provisioning is part of the plumbing under test. Referencing the method also triggers class
 * initialization, starting the container on first use.
 *
 * <p>Requires a running Docker daemon.
 */
public final class PostgresContainerSupport {

    /**
     * Well above PostgreSQL's default of 100. Every distinct {@code @SpringBootTest} property set is a
     * separate cached Spring context holding its own Hikari pool, and the test-context cache keeps them
     * all alive for the JVM's lifetime — so the suite's connection ceiling scales with the number of
     * context variants, not with the number of tests running at once. Hitting it fails a whole context
     * with {@code FATAL: sorry, too many clients already}, which reads like an unrelated flake in
     * whichever class happens to load last.
     */
    private static final String MAX_CONNECTIONS = "300";

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    // Wait until the host-mapped port actually accepts a connection before start() returns.
                    // On Docker-in-VM setups (e.g. Rancher Desktop) the published-port forward to the host can
                    // lag the container's own readiness; without this, an eager Flyway connect right after
                    // start() can be refused. The generous timeout absorbs slow VM startup.
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofMinutes(2))
                    // withCommand REPLACES the container's default CMD, which PostgreSQLContainer sets to
                    // `postgres -c fsync=off`. Repeat fsync=off here or every write in the shared container
                    // starts fsyncing — including the Flyway migrations of every cached Spring context.
                    .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=" + MAX_CONNECTIONS);

    static {
        POSTGRES.start();
    }

    private PostgresContainerSupport() {
    }

    /** Wires the singleton container's connection settings into {@code spring.datasource.*}. */
    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    /** JDBC URL of the shared container's default database. */
    static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    /**
     * JDBC URL of a throwaway database on the same container, for a test that needs to drive Flyway
     * itself rather than inherit the context's already-migrated schema. Separate database, so it cannot
     * disturb the catalog every other integration test shares.
     */
    static String jdbcUrlFor(String database) {
        return POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
    }

    static String username() {
        return POSTGRES.getUsername();
    }

    static String password() {
        return POSTGRES.getPassword();
    }
}
