package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.flight.FlightSqlServerLifecycle;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStatusCode;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ClickHouse-down behavior (spec "ClickHouse down" scenario): the context points the
 * ClickHouse client at a port nothing listens on (migrations disabled so startup succeeds; the
 * Postgres catalog still provides {@code dial_usage_log}). {@code getFlightInfo} still succeeds —
 * the two-call model derives the schema without touching the store — while {@code doGet} fails
 * with UNAVAILABLE and no raw driver message.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class FlightClickHouseDownIntegrationTest {

    @Autowired
    private FlightSqlServerLifecycle lifecycle;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("clickhouse.host", () -> "localhost");
        registry.add("clickhouse.port", () -> "59999");
        registry.add("clickhouse.migrations.enabled", () -> "false");
        // Data-sync validates its mapping against a live ClickHouse at startup; this context's whole
        // point is an unreachable ClickHouse, so skip that probe.
        registry.add("sync.validate-schema", () -> "false");
        registry.add("flight.enabled", () -> "true");
        registry.add("flight.port", () -> "0");
    }

    @Test
    void doGetFailsUnavailableWhileGetFlightInfoStillWorks() throws Exception {
        try (BufferAllocator allocator = new RootAllocator();
                FlightSqlClient client = new FlightSqlClient(FlightClient.builder(
                        allocator, Location.forGrpcInsecure("localhost", lifecycle.boundPort())).build())) {
            // Schema derivation is catalog-only; no ClickHouse round trip.
            final FlightInfo info = client.execute("SELECT event_kind FROM dial_usage_log LIMIT 10");
            assertThat(info.getEndpoints()).hasSize(1);

            // Execution must surface UNAVAILABLE without leaking driver internals.
            assertThatThrownBy(() -> {
                try (FlightStream stream = client.getStream(info.getEndpoints().getFirst().getTicket())) {
                    while (stream.next()) {
                        // drain
                    }
                }
            }).isInstanceOfSatisfying(FlightRuntimeException.class, e -> {
                assertThat(e.status().code()).isEqualTo(FlightStatusCode.UNAVAILABLE);
                assertThat(String.valueOf(e.status().description())).doesNotContain("Connection refused");
            });
        }
    }
}
