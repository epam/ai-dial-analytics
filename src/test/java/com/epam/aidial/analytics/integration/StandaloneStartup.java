package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.AnalyticsDataAccessApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boots the whole application outside the test-context cache, for a test about startup itself: what a start does to
 * ClickHouse and to the catalog, or whether it fails. Each start gets a fresh PostgreSQL catalog database, so "no
 * catalog row was written" can be read off it, and runs with the poller off and on random ports.
 *
 * <p>Test-only types are excluded from component scanning as {@code @SpringBootTest} excludes them, so a
 * {@code @TestConfiguration} of another test class never leaks into the started context.
 */
final class StandaloneStartup {

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<Class<?>> sources = new ArrayList<>(List.of(AnalyticsDataAccessApplication.class));
    private final String catalog;

    /** A start against a fresh catalog database named {@code catalog}. */
    StandaloneStartup(String catalog) {
        this.catalog = catalog;
        recreateCatalog(catalog);
        properties.put("spring.datasource.url", PostgresContainerSupport.jdbcUrlFor(catalog));
        properties.put("spring.datasource.username", PostgresContainerSupport.username());
        properties.put("spring.datasource.password", PostgresContainerSupport.password());
        properties.put("spring.datasource.hikari.maximum-pool-size", "2");
        properties.put("spring.flyway.enabled", "true");
        properties.put("poll.enabled", "false");
        properties.put("server.port", "0");
        properties.put("spring.jmx.enabled", "false");
    }

    StandaloneStartup property(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    /** Adds a configuration class to the started context. */
    StandaloneStartup source(Class<?> source) {
        sources.add(source);
        return this;
    }

    /** Starts the application; the caller closes it. */
    ConfigurableApplicationContext start() {
        // As arguments, not default properties: application.yml would override a default property with its own value.
        final String[] arguments = properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(sources.toArray(Class<?>[]::new))
                .initializers(context -> context.getBeanFactory()
                        .registerSingleton("standaloneTestTypeExcludeFilter", new TestTypes()))
                .run(arguments);
    }

    /** The tables the catalog database holds in its {@code public} schema. */
    List<String> catalogTables() {
        final List<String> tables = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(PostgresContainerSupport.jdbcUrlFor(catalog),
                        PostgresContainerSupport.username(), PostgresContainerSupport.password());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        return tables;
    }

    /** Excludes what {@code @SpringBootTest} excludes: test configurations and components, and any test's nested types. */
    private static final class TestTypes extends TypeExcludeFilter {

        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            final var metadata = reader.getAnnotationMetadata();
            if (metadata.hasAnnotation("org.springframework.boot.test.context.TestConfiguration")
                    || metadata.hasAnnotation("org.springframework.boot.test.context.TestComponent")) {
                return true;
            }
            final String enclosing = reader.getClassMetadata().getEnclosingClassName();
            return enclosing != null && (enclosing.endsWith("Test") || enclosing.endsWith("Tests"));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TestTypes;
        }

        @Override
        public int hashCode() {
            return TestTypes.class.hashCode();
        }
    }

    private static void recreateCatalog(String catalog) {
        try (Connection connection = DriverManager.getConnection(PostgresContainerSupport.jdbcUrl(),
                        PostgresContainerSupport.username(), PostgresContainerSupport.password());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + catalog + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + catalog);
        } catch (SQLException ex) {
            throw new IllegalStateException("could not create catalog database " + catalog, ex);
        }
    }
}
