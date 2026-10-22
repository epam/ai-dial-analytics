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
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.util.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Flight transport's row cap ({@code flight.max-rows}) in both directions, exercised at a
 * deliberately tiny configured value so the over-cap cases need six rows instead of ten thousand and one.
 *
 * <p>Configuring the cap is the point of the class, not a convenience: the spec requires a non-default
 * {@code flight.max-rows} to be honoured, which cannot be asserted from the default-valued context. That
 * costs one extra Spring context and JDBC pool (see {@link AbstractClickHouseIntegrationTest}'s note on
 * property sets as cache keys) — accepted here because the alternative is a 10 001-row fixture that still
 * would not prove the property is read.
 *
 * <p>The under-cap and above-1000 explicit-limit paths are covered against the shipped default in
 * {@code FlightSqlIntegrationTest.unboundedQueryUnderTheCapReturnsEveryRow}.
 */
class FlightRowCapIntegrationTest extends AbstractClickHouseIntegrationTest {

    /** Small enough that a handful of fixture rows straddles it. */
    private static final int CAP = 5;

    @Autowired
    private FlightSqlServerLifecycle lifecycle;

    private BufferAllocator clientAllocator;
    private FlightSqlClient sqlClient;

    @DynamicPropertySource
    static void flightProperties(DynamicPropertyRegistry registry) {
        registry.add("flight.enabled", () -> "true");
        registry.add("flight.port", () -> "0");
        registry.add("flight.max-rows", () -> String.valueOf(CAP));
    }

    @BeforeEach
    void openClientAndCleanSlate() {
        truncate();
        clientAllocator = new RootAllocator();
        sqlClient = new FlightSqlClient(FlightClient.builder(
                clientAllocator, Location.forGrpcInsecure("localhost", lifecycle.boundPort())).build());
    }

    @AfterEach
    void closeClient() throws Exception {
        sqlClient.close();
        clientAllocator.close();
    }

    @Test
    void unboundedResultExactlyAtTheCapStreamsEveryRowAndNoProbeRow() {
        seed(CAP);

        // The query ran asking for CAP + 1 rows to detect truncation; exactly CAP came back, so the
        // result is complete and the extra requested row must not appear anywhere in the stream.
        assertThat(executeQuery("SELECT project_id FROM dial_usage_log ORDER BY project_id")).hasSize(CAP);
    }

    @Test
    void unboundedResultOverTheCapFailsWithoutDeliveringPartialPage() {
        seed(CAP + 1);

        // getFlightInfo validates without executing, so it cannot know the row count and must succeed.
        final FlightInfo info = sqlClient.execute("SELECT project_id FROM dial_usage_log");
        assertThat(info.getEndpoints()).hasSize(1);

        // The stream then fails, and — the point of the requirement — delivers nothing at all rather
        // than the first CAP rows.
        final List<Map<String, Object>> delivered = new ArrayList<>();
        assertThatThrownBy(() -> {
            try (FlightStream stream = sqlClient.getStream(info.getEndpoints().getFirst().getTicket())) {
                while (stream.next()) {
                    delivered.addAll(rows(stream.getRoot()));
                }
            }
        }).isInstanceOfSatisfying(FlightRuntimeException.class, e -> {
            assertThat(e.status().code()).isEqualTo(FlightStatusCode.INVALID_ARGUMENT);
            assertThat(String.valueOf(e.status().description()))
                    .contains(String.valueOf(CAP)).contains("LIMIT").contains("aggregate");
        });
        assertThat(delivered).isEmpty();
    }

    @Test
    void unboundedAggregateOverTheCapSucceeds() {
        seed(CAP + 1);

        // One row cannot exceed the cap, so the query that the old explicit-LIMIT rule refused — and that
        // Power BI's DirectQuery generator emits — must succeed.
        assertThat(executeQuery("SELECT count(*) AS n FROM dial_usage_log"))
                .containsExactly(Map.of("n", (long) (CAP + 1)));
    }

    @Test
    void explicitLimitAboveTheCapIsClampedNotRejected() {
        seed(CAP - 2);

        // The bound is far above the cap but the result is small — the literal alone must not fail the
        // call. This is the shape a BI tool's guard rail takes (Power BI writes LIMIT 1000001).
        assertThat(executeQuery("SELECT project_id FROM dial_usage_log ORDER BY project_id LIMIT 1000001"))
                .hasSize(CAP - 2);
    }

    @Test
    void clampedLimitStillFailsOnAnOversizedResult() {
        seed(CAP + 1);

        // Clamping is not permission to truncate: the same probe that guards a no-LIMIT query guards
        // this one, and getFlightInfo cannot know the row count so it must still succeed.
        final FlightInfo info = sqlClient.execute("SELECT project_id FROM dial_usage_log LIMIT 1000001");
        assertThat(info.getEndpoints()).hasSize(1);

        final List<Map<String, Object>> delivered = new ArrayList<>();
        assertThatThrownBy(() -> {
            try (FlightStream stream = sqlClient.getStream(info.getEndpoints().getFirst().getTicket())) {
                while (stream.next()) {
                    delivered.addAll(rows(stream.getRoot()));
                }
            }
        }).isInstanceOfSatisfying(FlightRuntimeException.class, e -> {
            assertThat(e.status().code()).isEqualTo(FlightStatusCode.INVALID_ARGUMENT);
            assertThat(String.valueOf(e.status().description()))
                    .contains(String.valueOf(CAP)).contains("LIMIT").contains("aggregate");
        });
        assertThat(delivered).isEmpty();
    }

    @Test
    void clampedLimitPreservesOffset() {
        seed(CAP + 1);

        // CAP + 1 rows, OFFSET 2, bound clamped to CAP: CAP - 1 rows remain, inside the cap.
        assertThat(executeQuery(
                "SELECT project_id FROM dial_usage_log ORDER BY project_id LIMIT 1000001 OFFSET 2"))
                .hasSize(CAP - 1);
    }

    @Test
    void clampedLimitNeverLeaksTheProbeRow() {
        seed(CAP);

        // Exactly at the cap: the query asked for CAP + 1 to detect truncation, CAP came back, so the
        // result is complete and the extra requested row must not appear.
        assertThat(executeQuery("SELECT project_id FROM dial_usage_log ORDER BY project_id LIMIT 1000001"))
                .hasSize(CAP);
    }

    /**
     * A catalog statement is outside the cap entirely — the spec's "Metadata statement is exempt from the
     * row cap", and now a claim `docs/configuration.md` makes too. This is the only suite with a lowered
     * cap, so it is the only place the exemption can be shown: the catalog has far more than {@link #CAP}
     * columns, so were the clamp applied the call would fail with the truncation error every clamped
     * ClickHouse query gets. Nothing is seeded, which also proves the rows do not come from ClickHouse.
     */
    @Test
    void catalogStatementIsExemptFromTheCap() {
        final List<Map<String, Object>> rows =
                executeQuery("SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS");

        assertThat(rows).hasSizeGreaterThan(CAP + 1);
        // An oversized explicit LIMIT is not clamped here either: it simply bounds nothing.
        assertThat(executeQuery("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS LIMIT 1000001"))
                .hasSameSizeAs(rows);
    }

    @Test
    void configuredCapGovernsTheClamp() {
        seed(CAP + 1);

        // LIMIT CAP + 1 is one row over this deliberately tiny cap. It is no longer refused for its
        // literal — it is clamped, runs, and fails only because the result genuinely exceeds the cap.
        assertThatThrownBy(() -> executeQuery("SELECT project_id FROM dial_usage_log LIMIT " + (CAP + 1)))
                .isInstanceOfSatisfying(FlightRuntimeException.class, e -> {
                    assertThat(e.status().code()).isEqualTo(FlightStatusCode.INVALID_ARGUMENT);
                    assertThat(String.valueOf(e.status().description())).contains(String.valueOf(CAP));
                });
        // …and exactly at the cap it is honoured as written, proving the boundary is the configured
        // value rather than the default.
        assertThat(executeQuery("SELECT project_id FROM dial_usage_log ORDER BY project_id LIMIT " + CAP))
                .hasSize(CAP);
    }

    @Test
    void explicitLimitUnderTheCapStopsAtTheBoundWithoutError() {
        seed(CAP + 1);

        // More rows match than the bound allows, but the caller asked for the bound — no probe, no error.
        assertThat(executeQuery("SELECT project_id FROM dial_usage_log ORDER BY project_id LIMIT 3"))
                .hasSize(3);
    }

    @Test
    void offsetWithoutLimitIsBoundedByTheCapRatherThanRejected() {
        seed(CAP + 1);

        // CAP + 1 rows with OFFSET 2 leaves CAP - 1 — inside the cap, so it streams instead of failing.
        assertThat(executeQuery(
                "SELECT project_id FROM dial_usage_log ORDER BY project_id OFFSET 2")).hasSize(CAP - 1);
    }

    @Test
    void restKeepsTheSharedGovernanceRegardlessOfTheFlightCap() throws Exception {
        seed(CAP + 1);

        // The Flight cap is a Flight-transport setting; REST still applies the shared default of 100,
        // which for this fixture means every row comes back and nothing is rejected.
        mockMvc.perform(post("/v1/queries/execute-sql").contentType(APPLICATION_JSON).content("""
                        {"sql": "SELECT project_id FROM dial_usage_log"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(CAP + 1)));
    }

    // -------------------------------------------------------------- helpers

    private void seed(int rowCount) {
        final var fixture = fixture();
        for (int i = 0; i < rowCount; i++) {
            fixture.row("chat", String.format("p%03d", i), i);
        }
        fixture.insert();
    }

    private List<Map<String, Object>> executeQuery(String sql) {
        final FlightInfo info = sqlClient.execute(sql);
        try (FlightStream stream = sqlClient.getStream(info.getEndpoints().getFirst().getTicket())) {
            final List<Map<String, Object>> collected = new ArrayList<>();
            while (stream.next()) {
                collected.addAll(rows(stream.getRoot()));
            }
            return collected;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static List<Map<String, Object>> rows(VectorSchemaRoot root) {
        final List<Map<String, Object>> rows = new ArrayList<>(root.getRowCount());
        for (int i = 0; i < root.getRowCount(); i++) {
            final Map<String, Object> row = new LinkedHashMap<>();
            for (final FieldVector vector : root.getFieldVectors()) {
                final Object value = vector.isNull(i) ? null : vector.getObject(i);
                row.put(vector.getName(), value instanceof Text text ? text.toString() : value);
            }
            rows.add(row);
        }
        return rows;
    }
}
