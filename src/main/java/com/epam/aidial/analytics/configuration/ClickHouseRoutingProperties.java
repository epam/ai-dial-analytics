package com.epam.aidial.analytics.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.Locale;

/**
 * Where user queries and pipeline runs execute ({@code clickhouse.routing.*}): over the discovered replicas
 * ({@code replicas}, the default) or always on the primary ({@code primary}). Both are escape hatches that send the
 * workload to one server without reconfiguring infrastructure; see
 * {@code openspec/specs/clickhouse-replication/spec.md}.
 *
 * <p>Bound as strings and parsed here, so an unknown value fails startup with a message naming the property and
 * its accepted values rather than a generic conversion error.
 */
@ConfigurationProperties(prefix = "clickhouse.routing")
public record ClickHouseRoutingProperties(Route queries, Route pipelines) {

    /** The two routes a workload can take. */
    public enum Route {
        /** Spread over the discovered replicas. */
        REPLICAS,
        /** Always the primary, {@code clickhouse.host}. */
        PRIMARY;

        /** Parses {@code value} for {@code property}; blank means {@link #REPLICAS}. */
        static Route parse(String property, String value) {
            if (value == null || value.isBlank()) {
                return REPLICAS;
            }
            for (final Route route : values()) {
                if (route.name().equals(value.trim().toUpperCase(Locale.ROOT))) {
                    return route;
                }
            }
            throw new IllegalArgumentException(property + " must be 'replicas' or 'primary', but was '" + value + "'");
        }
    }

    /** Binds the raw values, so the parse failure names the property. */
    @ConstructorBinding
    public ClickHouseRoutingProperties(String queries, String pipelines) {
        this(Route.parse("clickhouse.routing.queries", queries), Route.parse("clickhouse.routing.pipelines", pipelines));
    }

    /** Both routes on the discovered replicas. */
    public static ClickHouseRoutingProperties defaults() {
        return new ClickHouseRoutingProperties(Route.REPLICAS, Route.REPLICAS);
    }
}
