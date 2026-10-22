package com.epam.aidial.analytics.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.jdbc.datasource.JdbcTelemetry;
import io.opentelemetry.instrumentation.jdbc.datasource.OpenTelemetryDataSource;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Wraps the auto-configured JDBC {@link DataSource} (the Postgres metadata store, over HikariCP) with
 * OpenTelemetry's JDBC instrumentation so every statement emits a {@link
 * io.opentelemetry.api.trace.SpanKind#CLIENT CLIENT} span with {@code db.*} attributes and a
 * <em>sanitized</em> {@code db.query.text} (literals replaced with {@code ?}). This covers all JPA
 * repositories and the Postgres Flyway migrations without per-call code.
 *
 * <p>{@code opentelemetry-jdbc} is used instead of {@code datasource-micrometer} because the latter
 * has no Spring Boot 4 release; wrapping the DataSource bean is independent of Boot's autoconfigure
 * internals. The ClickHouse Flyway migration builds its own non-Spring {@code DataSource}, so it is
 * not covered here (startup-only). When tracing is disabled ({@code management.tracing.enabled} off,
 * the default) the SDK hands out a no-op tracer, so the wrapper starts no spans and is effectively a
 * pass-through.
 */
@Configuration
public class JdbcTracingConfiguration {

    /**
     * A {@link BeanPostProcessor} that replaces each {@link DataSource} bean with an
     * OpenTelemetry-instrumented wrapper. Declared {@code static} so it is instantiated early (before
     * regular beans) without forcing the surrounding configuration to initialize; the {@link
     * ObjectProvider} defers resolving the {@link OpenTelemetry} bean until a DataSource is actually
     * post-processed. If no {@link OpenTelemetry} bean is present the DataSource is left unwrapped
     * (rather than failing startup), mirroring the defensive wiring in the log-appender configuration.
     */
    @Bean
    static BeanPostProcessor openTelemetryDataSourcePostProcessor(ObjectProvider<OpenTelemetry> openTelemetry) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof OpenTelemetryDataSource)) {
                    OpenTelemetry otel = openTelemetry.getIfAvailable();
                    if (otel != null) {
                        return JdbcTelemetry.create(otel).wrap(dataSource);
                    }
                }
                return bean;
            }
        };
    }
}
