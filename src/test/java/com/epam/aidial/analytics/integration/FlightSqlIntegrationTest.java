package com.epam.aidial.analytics.integration;

import com.clickhouse.client.api.Client;
import com.epam.aidial.analytics.service.query.arrow.ArrowAllocators;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.flight.FlightSqlServerLifecycle;
import com.epam.aidial.analytics.web.flight.FlightStreamGate;
import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStatusCode;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.flight.sql.impl.FlightSql;
import org.apache.arrow.flight.sql.util.TableRef;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.UInt4Vector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.DenseUnionVector;
import org.apache.arrow.vector.ipc.ReadChannel;
import org.apache.arrow.vector.ipc.message.MessageSerializer;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flight SQL end-to-end against real ClickHouse + Postgres: the full production
 * path {@code FlightSqlClient → gRPC → AdasFlightSqlProducer → SqlQueryFrontEnd (Calcite) →
 * StructuredQueryService → jOOQ render → ClickHouse client-v2 → ClickHouse}, plus REST-vs-Flight
 * equivalence for the same logical query. Runs in {@code security.mode=none} (the default), so the
 * endpoint being open without credentials here is itself the spec's "open in none mode" scenario.
 */
class FlightSqlIntegrationTest extends AbstractClickHouseIntegrationTest {

    @Autowired
    private FlightSqlServerLifecycle lifecycle;
    @Autowired
    private FlightStreamGate gate;
    @Autowired
    private ArrowAllocators allocators;
    @Autowired
    private TableDefinitionRepository definitions;
    @Autowired
    private ColumnMappingRepository columnMappings;

    private BufferAllocator clientAllocator;
    private FlightClient flightClient;
    private FlightSqlClient sqlClient;

    @DynamicPropertySource
    static void flightProperties(DynamicPropertyRegistry registry) {
        registry.add("flight.enabled", () -> "true");
        registry.add("flight.port", () -> "0");
    }

    @BeforeEach
    void openClientAndCleanSlate() {
        truncate();
        clientAllocator = new RootAllocator();
        flightClient = FlightClient.builder(
                clientAllocator, Location.forGrpcInsecure("localhost", lifecycle.boundPort())).build();
        sqlClient = new FlightSqlClient(flightClient);
    }

    @AfterEach
    void closeClient() throws Exception {
        sqlClient.close();
        clientAllocator.close();
    }

    // ---------------------------------------------------------------- 7.1

    @Test
    void getFlightInfoAndDoGetAgreeOnSchemaAndStreamRows() {
        fixture().row("chat", "p1", 10).row("chat", "p1", 20).row("embed", "p2", 30).insert();

        final FlightInfo info = sqlClient.execute(
                "SELECT event_kind, count(*) AS n FROM dial_usage_log GROUP BY event_kind ORDER BY n DESC LIMIT 10");
        assertThat(info.getSchemaOptional()).isPresent();
        final Schema schema = info.getSchemaOptional().orElseThrow();
        assertThat(schema.findField("event_kind").getType()).isEqualTo(ArrowType.Utf8.INSTANCE);
        assertThat(schema.findField("n").getType()).isEqualTo(new ArrowType.Int(64, true));

        try (FlightStream stream = sqlClient.getStream(info.getEndpoints().getFirst().getTicket())) {
            assertThat(stream.getSchema()).isEqualTo(schema);
            final List<Map<String, Object>> rows = collect(stream);
            assertThat(rows).containsExactly(
                    Map.of("event_kind", "chat", "n", 2L),
                    Map.of("event_kind", "embed", "n", 1L));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void catalogMetadataStatementStreamsOverTheStatementPath() {
        // Verbatim from Power BI's Dremio connector 3.14.0 — the statement that used to fail with
        // "Object 'INFORMATION_SCHEMA' not found".
        final FlightInfo info = sqlClient.execute("""
                /* PBI ADBC metadata: GetSchemas */
                                SELECT DISTINCT TABLE_SCHEMA
                                FROM INFORMATION_SCHEMA."TABLES"
                                WHERE TABLE_TYPE <> 'SYSTEM_TABLE'
                                ORDER BY TABLE_SCHEMA""");

        final Schema schema = info.getSchemaOptional().orElseThrow();
        assertThat(schema.findField("TABLE_SCHEMA").getType()).isEqualTo(ArrowType.Utf8.INSTANCE);
        try (FlightStream stream = sqlClient.getStream(info.getEndpoints().getFirst().getTicket())) {
            assertThat(stream.getSchema()).isEqualTo(schema);
            assertThat(collect(stream)).containsExactly(Map.of("TABLE_SCHEMA", "adas"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Two results the evaluator can produce but the Arrow layer has to be able to encode, asserted over the
     * real listener because that is the only place the failure appeared: the evaluator's error containment
     * ends when the rows are handed to the writer, so a value the writer cannot coerce fails <em>after</em>
     * {@code listener.start} — a mid-stream gRPC {@code INTERNAL} rather than the client error it is.
     */
    @Test
    void catalogStatementResultsSurviveArrowEncoding() {
        // The date arrives as the epoch day Arrow's DATEDAY vector carries — the same encoding a DATE
        // column of a data query streams. Before the fix this failed mid-stream instead: the writer has no
        // coercion from the evaluator's raw Integer into a date column.
        assertThat(executeQuery("SELECT DATE '2020-01-01' AS d, TIMESTAMP '2020-01-01 10:00:00' AS t "
                + "FROM INFORMATION_SCHEMA.\"TABLES\" LIMIT 1"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("d")).isEqualTo((int) LocalDate.of(2020, 1, 1).toEpochDay());
                    assertThat(row.get("t")).isEqualTo(
                            LocalDateTime.of(2020, 1, 1, 10, 0).toInstant(ZoneOffset.UTC).toEpochMilli());
                });

        // A global aggregate matching nothing still streams its one row, over the wire, as 0.
        assertThat(executeQuery("SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.\"TABLES\" "
                + "WHERE TABLE_NAME = 'no_such_table'"))
                .containsExactly(Map.of("n", 0L));
    }

    @Test
    void catalogColumnStatementStreamsWithItsTrailingSeparator() {
        final List<Map<String, Object>> rows = executeQuery("""
                /* PBI ADBC metadata: GetTableType */
                                SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE
                                FROM INFORMATION_SCHEMA.COLUMNS
                                WHERE TABLE_SCHEMA = 'adas'
                                AND TABLE_NAME = 'dial_usage_log';""");

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row -> assertThat(row.get("IS_NULLABLE")).isEqualTo("YES"));
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("COLUMN_NAME")).isEqualTo("event_kind");
            assertThat(row.get("DATA_TYPE")).isEqualTo("CHARACTER VARYING");
        });
    }

    @Test
    void schemaQualifiedEntityNameStreamsLikeTheBareOne() {
        fixture().row("chat", "p1", 10).insert();

        assertThat(executeQuery("SELECT event_kind FROM \"adas\".\"dial_usage_log\" LIMIT 10"))
                .isEqualTo(executeQuery("SELECT event_kind FROM dial_usage_log LIMIT 10"));
    }

    @Test
    void catalogRpcsIgnoreTheSqlSchemaNamespace() {
        // The SQL surface resolves `adas`, but the RPCs deliberately still report no schema — that is
        // what keeps RPC-driven clients generating unqualified names (design D4).
        assertThat(metadataRows(sqlClient.getSchemas(null, null))).isEmpty();
        assertThat(metadataRows(sqlClient.getTables(null, null, null, null, false)))
                .allSatisfy(row -> assertThat(row.get("db_schema_name")).isNull())
                .extracting(row -> row.get("table_name"))
                .doesNotContain("TABLES", "COLUMNS", "SCHEMATA");
    }

    @Test
    void flightValuesMatchRestValuesPerType() throws Exception {
        typedRow("00000000-0000-4000-8000-000000000001", "chat", 200, true, "12.5", "u1", 42,
                "2026-01-01 00:00:00.000");

        // REST: the same logical query through POST /v1/queries/execute.
        mockMvc.perform(post("/v1/queries/execute").contentType(APPLICATION_JSON).content("""
                        {
                          "entity": "dial_usage_log",
                          "mode": "row",
                          "select": [
                            {"expr": {"type": "field", "name": "event_id"}},
                            {"expr": {"type": "field", "name": "event_kind"}},
                            {"expr": {"type": "field", "name": "response_status"}},
                            {"expr": {"type": "field", "name": "success"}},
                            {"expr": {"type": "field", "name": "total_price"}},
                            {"expr": {"type": "field", "name": "total_tokens"}}
                          ]
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].event_id").value("00000000-0000-4000-8000-000000000001"))
                .andExpect(jsonPath("$.rows[0].event_kind").value("chat"))
                .andExpect(jsonPath("$.rows[0].response_status").value(200))
                .andExpect(jsonPath("$.rows[0].success").value(true))
                .andExpect(jsonPath("$.rows[0].total_price").value(12.5))
                .andExpect(jsonPath("$.rows[0].total_tokens").value(42));

        // Flight: same values, columnar encoding.
        final List<Map<String, Object>> rows = executeQuery(
                "SELECT event_id, event_kind, response_status, success, total_price, total_tokens, request_time "
                        + "FROM dial_usage_log LIMIT 10");
        assertThat(rows).hasSize(1);
        final Map<String, Object> row = rows.getFirst();
        assertThat(row.get("event_id")).isEqualTo("00000000-0000-4000-8000-000000000001");
        assertThat(row.get("event_kind")).isEqualTo("chat");
        assertThat(row.get("response_status")).isEqualTo(200);
        assertThat(row.get("success")).isEqualTo(true);
        assertThat((BigDecimal) row.get("total_price")).isEqualByComparingTo("12.5");
        assertThat(row.get("total_tokens")).isEqualTo(42L);
        assertThat(row.get("request_time")).isEqualTo(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());
    }

    // ---------------------------------------------------------------- 7.2

    @Test
    void supportedSqlConstructsExecuteOnRealClickHouse() {
        fixture()
                .add(r -> r.eventKind("chat").projectId("alpha").deployment("gpt-4o").totalTokens(10))
                .add(r -> r.eventKind("chat").projectId("beta").deployment("gpt-4o-mini").totalTokens(20))
                .add(r -> r.eventKind("embed").projectId("beta").deployment("text-embed").totalTokens(30))
                .add(r -> r.eventKind("chat").projectId("gamma").deployment("claude").totalTokens(40))
                .insert();

        // Comparison operators, IN, contains (LIKE '%…%'), BETWEEN expansion. Every statement carries an
        // explicit LIMIT: the Flight surface rejects an unbounded query (see limitGovernanceIsFlightOnly).
        assertThat(executeQuery("SELECT project_id FROM dial_usage_log WHERE total_tokens > 10 AND total_tokens <= 30 "
                + "ORDER BY total_tokens LIMIT 10")).extracting(r -> r.get("project_id")).containsExactly("beta", "beta");
        assertThat(executeQuery("SELECT project_id FROM dial_usage_log WHERE project_id IN ('alpha', 'gamma') "
                + "ORDER BY project_id LIMIT 10")).extracting(r -> r.get("project_id")).containsExactly("alpha", "gamma");
        assertThat(executeQuery("SELECT deployment FROM dial_usage_log WHERE deployment LIKE '%embed%' LIMIT 10"))
                .extracting(r -> r.get("deployment")).containsExactly("text-embed");
        // LIKE is case-sensitive (standard SQL); ILIKE is the case-insensitive form.
        assertThat(executeQuery("SELECT deployment FROM dial_usage_log WHERE deployment LIKE '%EMBED%' LIMIT 10"))
                .isEmpty();
        assertThat(executeQuery("SELECT deployment FROM dial_usage_log WHERE deployment ILIKE '%EMBED%' LIMIT 10"))
                .extracting(r -> r.get("deployment")).containsExactly("text-embed");
        assertThat(executeQuery("SELECT project_id FROM dial_usage_log WHERE total_tokens BETWEEN 20 AND 30 "
                + "ORDER BY total_tokens LIMIT 10")).hasSize(2);

        // IS NULL / IS NOT NULL on a Nullable column (total_price is never set by the fixture).
        assertThat(executeQuery("SELECT count(*) AS n FROM dial_usage_log WHERE total_price IS NULL LIMIT 10"))
                .containsExactly(Map.of("n", 4L));
        assertThat(executeQuery("SELECT count(*) AS n FROM dial_usage_log WHERE total_price IS NOT NULL LIMIT 10"))
                .containsExactly(Map.of("n", 0L));

        // DISTINCT, GROUP BY/HAVING, ORDER BY with NULLS FIRST/LAST, LIMIT/OFFSET.
        assertThat(executeQuery("SELECT DISTINCT event_kind FROM dial_usage_log ORDER BY event_kind LIMIT 10"))
                .extracting(r -> r.get("event_kind")).containsExactly("chat", "embed");
        assertThat(executeQuery("SELECT event_kind, sum(total_tokens) AS t FROM dial_usage_log "
                + "GROUP BY event_kind HAVING sum(total_tokens) > 40 ORDER BY t DESC LIMIT 10"))
                .containsExactly(Map.of("event_kind", "chat", "t", 70L));
        assertThat(executeQuery("SELECT project_id, total_price FROM dial_usage_log "
                + "ORDER BY total_price ASC NULLS FIRST, project_id LIMIT 1"))
                .extracting(r -> r.get("total_price")).containsExactly((Object) null);
        assertThat(executeQuery("SELECT project_id FROM dial_usage_log ORDER BY total_tokens LIMIT 2 OFFSET 1"))
                .extracting(r -> r.get("project_id")).containsExactly("beta", "beta");

        // Scalar functions from the catalog.
        assertThat(executeQuery("SELECT upper(deployment) AS d FROM dial_usage_log WHERE total_tokens = 40 LIMIT 10"))
                .containsExactly(Map.of("d", "CLAUDE"));
    }

    @Test
    void enrichmentColumnLeftJoinPreservesNullsAsArrowNulls() {
        fixture()
                .add(r -> r.eventKind("chat").userHash("u1").totalTokens(1))
                .add(r -> r.eventKind("chat").userHash("u2").totalTokens(2))
                .insert();
        try {
            // display_name is Nullable so an unmatched LEFT JOIN yields NULL (a non-Nullable String
            // would yield the column default '' under ClickHouse's join_use_nulls=0 default). The engine is the
            // one every enrichment has — version-collapsing — because a final read renders FINAL on the join,
            // which ClickHouse refuses on a plain MergeTree.
            enrichmentClient().queryAll("CREATE TABLE user_dim (user_hash String, display_name Nullable(String)) "
                    + "ENGINE = ReplacingMergeTree ORDER BY user_hash");
            enrichmentClient().queryAll("INSERT INTO user_dim VALUES ('u1', 'User One')");
            registerEnrichmentCatalog();

            final List<Map<String, Object>> rows = executeQuery(
                    "SELECT user_hash, \"user_dim.display_name\" FROM dial_usage_log ORDER BY user_hash LIMIT 10");
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).get("user_hash")).isEqualTo("u1");
            assertThat(rows.get(0).get("user_dim.display_name")).isEqualTo("User One");
            assertThat(rows.get(1).get("user_hash")).isEqualTo("u2");
            assertThat(rows.get(1)).containsEntry("user_dim.display_name", null);
        } finally {
            columnMappings.findByTableNameOrderByOrdinal("user_dim").forEach(columnMappings::delete);
            definitions.findById("user_dim").ifPresent(definitions::delete);
            enrichmentClient().queryAll("DROP TABLE IF EXISTS user_dim");
        }
    }

    /** The rebuilt request body and its status stream as nullable Utf8, with the values the JSON SQL endpoint returns. */
    @Test
    void computedFieldsStreamAsUtf8WithTheValuesExecuteSqlReturns() throws Exception {
        final String root = "00000000-0000-4000-8000-000000000101";
        final String child = "00000000-0000-4000-8000-000000000102";
        final String time = fixtureTime("10:00:00.000");
        enrichmentClient().queryAll("INSERT INTO dial_usage_log_payload (event_id, request_time, trace_id, core_span_id, request_hash,"
                + " request_parent_hash, request_ancestor_hashes, request_body) VALUES"
                + " (toUUID('" + eventIdFor("trace-0000") + "'), '" + time + "', 'trace-0000', '', toUUID('" + root + "'),"
                + " toUUID('00000000-0000-0000-0000-000000000000'), [], '{\"messages\":[{\"content\":\"u1\",\"role\":\"user\"}],\"model\":\"m\"}'),"
                + " (toUUID('" + eventIdFor("trace-0001") + "'), '" + time + "', 'trace-0001', '', toUUID('" + child + "'),"
                + " toUUID('" + root + "'), [toUUID('" + root + "')],"
                + " '{\"messages\":[{\"content\":\"a1\",\"role\":\"assistant\"},{\"content\":\"u2\",\"role\":\"user\"}],\"model\":\"m\"}')");
        enrichmentClient().queryAll("INSERT INTO dial_usage_log (event_id, request_time, trace_id) VALUES (toUUID('"
                + eventIdFor("trace-0001") + "'), '" + time + "', 'trace-0001')");
        final String sql = "SELECT trace_id, \"dial_usage_log_payload.request_body_full\", \"dial_usage_log_payload.request_body_rebuild\""
                + " FROM dial_usage_log WHERE trace_id = 'trace-0001' LIMIT 1";
        final String rest = mockMvc.perform(post("/v1/queries/execute-sql").contentType(APPLICATION_JSON)
                        .content("{\"sql\":\"" + sql.replace("\"", "\\\"") + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        final FlightInfo info = sqlClient.execute(sql);
        final Schema schema = info.getSchemaOptional().orElseThrow();
        assertThat(schema.findField("dial_usage_log_payload.request_body_full").getType()).isEqualTo(ArrowType.Utf8.INSTANCE);
        assertThat(schema.findField("dial_usage_log_payload.request_body_full").isNullable()).isTrue();
        assertThat(schema.findField("dial_usage_log_payload.request_body_rebuild").getType()).isEqualTo(ArrowType.Utf8.INSTANCE);
        final List<Map<String, Object>> rows = executeQuery(sql);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("dial_usage_log_payload.request_body_rebuild")).isEqualTo("rebuilt");
        final String full = (String) rows.getFirst().get("dial_usage_log_payload.request_body_full");
        assertThat(full).isEqualTo("{\"messages\":[{\"content\":\"u1\",\"role\":\"user\"},{\"content\":\"a1\",\"role\":\"assistant\"},"
                + "{\"content\":\"u2\",\"role\":\"user\"}],\"model\":\"m\"}");
        assertThat(new com.epam.aidial.analytics.configuration.JsonMapperConfiguration().jsonMapper().readTree(rest)
                .get("rows").get(0).get("dial_usage_log_payload.request_body_full").asString()).isEqualTo(full);
    }

    @Test
    void starSchemaOmitsHeavyAndEnrichmentFields() {
        // The canonical external-client statement (pandas/ADBC sends nothing but this text): the Arrow
        // schema must carry the source's non-heavy columns only — no raw payload column, and no
        // enrichment field, so the stream costs no LEFT JOIN either.
        fixture().add(r -> r.eventKind("chat").userHash("u1").totalTokens(1)).insert();
        try {
            enrichmentClient().queryAll("CREATE TABLE user_dim (user_hash String, display_name Nullable(String)) "
                    + "ENGINE = ReplacingMergeTree ORDER BY user_hash");
            registerEnrichmentCatalog();

            final FlightInfo info = sqlClient.execute("SELECT * FROM dial_usage_log LIMIT 1");
            final Schema schema = info.getSchemaOptional().orElseThrow();
            final List<String> fields = schema.getFields().stream().map(Field::getName).toList();
            assertThat(fields)
                    // Every verbatim-content column is an enrichment column now, so the star leaves
                    // them out for a second reason as well: a wildcard never fans out into a grain
                    // join. The claim/header pair shows that is about OWNERSHIP, not heaviness —
                    // neither is flagged heavy, and both are still absent.
                    .doesNotContain("dial_usage_log_payload.request_body", "dial_usage_log_payload.response_body",
                            "dial_usage_log_payload.jwt_claims", "dial_usage_log_payload.request_tags",
                            "user_dim.display_name")
                    .contains("event_id", "request_body_bytes", "response_body_bytes");

            try (FlightStream stream = sqlClient.getStream(info.getEndpoints().getFirst().getTicket())) {
                assertThat(stream.getSchema()).isEqualTo(schema);
                assertThat(collect(stream)).hasSize(1);
            } catch (Exception e) {
                throw new AssertionError(e);
            }

            // Named explicitly, the same columns stream normally.
            final List<Map<String, Object>> named = executeQuery(
                    "SELECT event_kind, \"dial_usage_log_payload.response_body\", \"user_dim.display_name\" FROM dial_usage_log LIMIT 1");
            assertThat(named).hasSize(1);
            assertThat(named.getFirst()).containsKeys("event_kind", "dial_usage_log_payload.response_body", "user_dim.display_name");
        } finally {
            columnMappings.findByTableNameOrderByOrdinal("user_dim").forEach(columnMappings::delete);
            definitions.findById("user_dim").ifPresent(definitions::delete);
            enrichmentClient().queryAll("DROP TABLE IF EXISTS user_dim");
        }
    }

    // ---------------------------------------------------------------- 7.3

    @Test
    void provisionalRenderingsExecuteOnRealClickHouse() {
        fixture()
                .add(r -> r.eventKind("chat").requestTime("2026-01-01 10:07:00.000").totalTokens(10))
                .add(r -> r.eventKind("chat").requestTime("2026-01-01 10:22:00.000").totalTokens(20))
                .add(r -> r.eventKind("chat").requestTime("2026-01-01 11:03:00.000").totalTokens(30))
                .insert();

        // percentile_cont / percentile_disc (jOOQ experimental ClickHouse rendering, spec caveat).
        assertThat(executeQuery("SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY total_tokens) AS p "
                + "FROM dial_usage_log LIMIT 10")).containsExactly(Map.of("p", 20.0d));
        assertThat(executeQuery("SELECT percentile_disc(0.5) WITHIN GROUP (ORDER BY total_tokens) AS p "
                + "FROM dial_usage_log LIMIT 10")).containsExactly(Map.of("p", 20.0d));

        // width_bucket over [0, 40) in 4 buckets: 10 -> 2, 20 -> 3, 30 -> 4.
        assertThat(executeQuery(
                "SELECT width_bucket(total_tokens, 0, 40, 4) AS b FROM dial_usage_log ORDER BY b LIMIT 10"))
                .extracting(r -> ((Number) r.get("b")).intValue()).containsExactly(2, 3, 4);

        // date_bin hourly bucketing (toStartOfInterval).
        final List<Map<String, Object>> buckets = executeQuery(
                "SELECT date_bin(1, 'hour', request_time) AS bucket, count(*) AS n FROM dial_usage_log "
                        + "GROUP BY date_bin(1, 'hour', request_time) ORDER BY bucket LIMIT 10");
        assertThat(buckets).containsExactly(
                Map.of("bucket", Instant.parse("2026-01-01T10:00:00Z").toEpochMilli(), "n", 2L),
                Map.of("bucket", Instant.parse("2026-01-01T11:00:00Z").toEpochMilli(), "n", 1L));

        // date_trunc calendar bucketing. Worth running live for two reasons the unit tests cannot cover:
        // the operator reaches validation only through the parse-time rebind (the Babel grammar binds
        // DATE_TRUNC to Calcite's own BigQuery-shaped operator), and `quarter` is a ClickHouse INTERVAL
        // keyword nothing else in the codebase exercises.
        assertThat(executeQuery("SELECT date_trunc('month', request_time) AS m, count(*) AS n "
                + "FROM dial_usage_log GROUP BY date_trunc('month', request_time) ORDER BY m LIMIT 10"))
                .containsExactly(
                        Map.of("m", Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), "n", 3L));
        assertThat(executeQuery("SELECT date_trunc('quarter', request_time) AS q FROM dial_usage_log "
                + "ORDER BY q LIMIT 1"))
                .containsExactly(Map.of("q", Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()));
    }

    @Test
    void derivationExpressionFunctionsExecuteOnRealClickHouse() {
        // The seven derivation functions, run for the reason the percentiles above are: rendering SQL to a
        // string proves nothing about whether ClickHouse accepts it. Two of them need this more than
        // anything else in the catalog — over an `object` column, JSONExtractKeysAndValues returns an EMPTY
        // key set and raises nothing, so the wrong rendering of json_extract_string_ci passes every offline
        // test and silently answers '' for every row. This is the only place that would catch it.
        fixture()
                .add(r -> r.eventKind("chat").chatId("chat-1").deployment("gpt-4o")
                        .jwtClaims("{\"roles\":[\"admin\",\"Reader\"],\"user_id\":\"u-1\"}")
                        .requestTags("{\"X-Session-Id\":\"AbC-123\",\"User-Agent\":\"Claude-CLI/2.1\"}"))
                .add(r -> r.eventKind("chat").chatId("").deployment("claude-3")
                        .jwtClaims("{}")
                        .requestTags("{\"x-session-id\":\"xyz-789\"}"))
                .insert();

        // if + not_empty: the first-non-absent merge, over a column whose absent value is the empty string.
        assertThat(executeQuery("SELECT if(not_empty(chat_id), chat_id, 'none') AS k FROM dial_usage_log "
                + "ORDER BY k LIMIT 10"))
                .containsExactly(Map.of("k", "chat-1"), Map.of("k", "none"));

        // The predicate producers, and a nested if for a multi-branch classification.
        assertThat(executeQuery("SELECT if(starts_with(deployment, 'gpt'), 'gpt', "
                + "if(contains(deployment, 'claude'), 'claude', 'other')) AS vendor FROM dial_usage_log "
                + "ORDER BY vendor LIMIT 10"))
                .containsExactly(Map.of("vendor", "claude"), Map.of("vendor", "gpt"));
        assertThat(executeQuery("SELECT equals(deployment, 'gpt-4o') AS hit FROM dial_usage_log "
                + "ORDER BY hit LIMIT 10"))
                .containsExactly(Map.of("hit", false), Map.of("hit", true));

        // starts_with is case-sensitive, and lower() is how a caller opts out of that.
        assertThat(executeQuery("SELECT starts_with(deployment, 'GPT') AS a, "
                + "starts_with(lower(deployment), 'gpt') AS b FROM dial_usage_log "
                + "WHERE deployment = 'gpt-4o' LIMIT 1"))
                .containsExactly(Map.of("a", false, "b", true));

        // json_extract_string_ci over an OBJECT column, matching both spellings of the same header. The
        // assertion that the object rendering works at all: the broken form returns '' here, not an error.
        assertThat(executeQuery("SELECT json_extract_string_ci(\"dial_usage_log_payload.request_tags\", 'x-session-id') AS sid "
                + "FROM dial_usage_log ORDER BY sid LIMIT 10"))
                .containsExactly(Map.of("sid", "AbC-123"), Map.of("sid", "xyz-789"));

        // Only the key is folded — the value comes back in the case the document carries.
        assertThat(executeQuery("SELECT json_extract_string_ci(\"dial_usage_log_payload.request_tags\", 'USER-AGENT') AS ua "
                + "FROM dial_usage_log ORDER BY ua DESC LIMIT 1"))
                .containsExactly(Map.of("ua", "Claude-CLI/2.1"));

        // json_extract_array over an OBJECT column, and its empty-array answer for an absent member. The
        // element case is asserted too: the extraction reads values, it does not fold them.
        final List<Map<String, Object>> roles = executeQuery(
                "SELECT json_extract_array(\"dial_usage_log_payload.jwt_claims\", 'roles') AS roles "
                        + "FROM dial_usage_log ORDER BY chat_id DESC LIMIT 10");
        assertThat(roles).hasSize(2);
        assertThat(listOfStrings(roles.get(0).get("roles"))).containsExactly("admin", "Reader");
        assertThat(listOfStrings(roles.get(1).get("roles"))).isEmpty();
    }

    /**
     * The predicate producers over a genuinely <b>NULL</b> operand, which is the case their renderings exist
     * for and the one the rest of this coverage misses: every other fixture column is non-nullable, so
     * absence arrives as the empty string and the {@code ifNull} wraps are never exercised.
     *
     * <p>ClickHouse gives none of these a false for free — {@code startsWith(NULL, 'x')},
     * {@code position(NULL, 'x') > 0} and {@code NULL = 'x'} all evaluate to NULL — so without the wraps
     * these four columns would come back null and an {@code if} built on one would take its else branch by
     * accident rather than by contract. Asserting the rendered text proves the wrap was written; only this
     * proves it does what it was written for.
     *
     * <p>{@code parent_deployment} is the operand because it is a nullable column the fixture never sets, so
     * it is NULL rather than empty for every row.
     */
    @Test
    void predicateProducersAreFalseNotNullOverNullOperandOnRealClickHouse() {
        fixture().add(r -> r.eventKind("chat").chatId("chat-1").deployment("gpt-4o")).insert();

        assertThat(queryScalar("SELECT count(*) AS n FROM dial_usage_log WHERE parent_deployment IS NULL", "n"))
                .as("the fixture must actually leave parent_deployment NULL for this test to mean anything")
                .isEqualTo("1");

        assertThat(executeQuery("SELECT not_empty(parent_deployment) AS a,"
                + " starts_with(parent_deployment, 'x') AS b,"
                + " contains(parent_deployment, 'x') AS c,"
                + " equals(parent_deployment, 'x') AS d"
                + " FROM dial_usage_log LIMIT 1"))
                .containsExactly(Map.of("a", false, "b", false, "c", false, "d", false));

        // And the merge those falses exist to drive: a NULL first candidate falls through to the second.
        assertThat(executeQuery("SELECT if(not_empty(parent_deployment), parent_deployment, deployment) AS d"
                + " FROM dial_usage_log LIMIT 1"))
                .containsExactly(Map.of("d", "gpt-4o"));
    }

    /**
     * ClickHouse's own {@code if} takes the <b>else</b> branch for a NULL condition — the semantics the
     * {@code structured-query} spec states as {@code if}'s contract.
     *
     * <p>Asserted against the server directly rather than through the query surface because no DSL
     * expression can currently produce a null condition: the four predicate producers and
     * {@code json_extract_bool} are all non-null by construction, and no boolean column in the catalog is
     * nullable. So the contract rests entirely on the server behaving this way, which is a claim about
     * ClickHouse — and one worth holding here rather than in a design note, since a version that returned
     * the {@code then} branch (or NULL) instead would invert every merge built on the spec's wording without
     * failing anything else.
     */
    @Test
    void clickHouseConditionalTakesTheElseBranchForNullCondition() {
        assertThat(queryScalar("SELECT if(NULL, 'then', 'else') AS branch", "branch")).isEqualTo("else");
        assertThat(queryScalar(
                "SELECT if(CAST(NULL AS Nullable(UInt8)), 'then', 'else') AS branch", "branch"))
                .isEqualTo("else");
    }

    @Test
    void argMaxExecutesOnRealClickHouseAndReturnsTheLatestRowsValue() {
        // arg_max is here for a DIFFERENT reason than the percentiles above. Its rendering is not
        // provisional — the native function name is emitted directly by a hand-built template, the way
        // date_bin's toStartOfInterval is. What needs a live run is that the SQL surface reaches it at
        // all: three separate registrations feed that path (the Calcite operator in SqlQueryOperators,
        // the aggExpr case, and the fnKind case), each failing on its own and none of them exercised by
        // rendering SQL to a string.
        fixture()
                .add(r -> r.eventKind("chat").deployment("gpt-4o").requestTime("2026-01-01 10:00:00.000").totalTokens(30))
                .add(r -> r.eventKind("chat").deployment("gpt-4o-mini").requestTime("2026-01-01 10:05:00.000").totalTokens(10))
                .add(r -> r.eventKind("chat").deployment("claude").requestTime("2026-01-01 10:09:00.000").totalTokens(20))
                .insert();

        // The fixture is built so "latest" and "greatest" disagree: max(total_tokens) is the FIRST row's
        // 30, while the last row by request_time carries 20. A test where they coincide would pass
        // against max() and prove nothing.
        assertThat(executeQuery("SELECT arg_max(total_tokens, request_time) AS latest, max(total_tokens) AS biggest "
                + "FROM dial_usage_log LIMIT 10"))
                .containsExactly(Map.of("latest", 20L, "biggest", 30L));

        // Grouped, with a string value operand — the motivating shape (a per-key latest attribute) and
        // the one that proves the result column is typed from the value, not from the order.
        assertThat(executeQuery("SELECT event_kind, arg_max(deployment, request_time) AS latest_deployment "
                + "FROM dial_usage_log GROUP BY event_kind LIMIT 10"))
                .containsExactly(Map.of("event_kind", "chat", "latest_deployment", "claude"));
    }

    @Test
    void argMaxOnRealClickHouseSkipsNullOperandsOnEitherSide() {
        // Verified against ClickHouse 25.3 and re-verified on 26.3 rather than assumed, and the
        // assumption was wrong: an earlier draft of the spec said a winning row with a null VALUE yields
        // null. It does not — argMax skips such a row and falls through to the next-latest row that has
        // one, exactly as it skips a row with a null ORDER. So the result is null only when NO row of the
        // group has both operands non-null. Neither fact is derivable from the rendered SQL, which is why
        // this runs live.
        // Seeded by hand rather than through fixture(), which has no setter for a nullable column.
        execute("INSERT INTO dial_usage_log "
                + "(request_time, event_kind, project_id, user_hash, chat_id, trace_id, deployment, "
                + "parent_deployment, total_tokens) VALUES "
                + "('2026-02-01 10:00:00.000', 'nulls', '', '', '', 'nulls-1', '', 'first', 1),"
                + "('2026-02-01 10:05:00.000', 'nulls', '', '', '', 'nulls-2', '', NULL, 2)");

        // The later row's VALUE is null, so it does not compete and the earlier row wins. A caller
        // reading this as "the latest rating" gets the latest NON-NULL one, which is a different
        // question — hence the spec says so rather than leaving it to be discovered.
        assertThat(executeQuery("SELECT arg_max(parent_deployment, request_time) AS latest "
                + "FROM dial_usage_log WHERE event_kind = 'nulls' LIMIT 10"))
                .containsExactly(Map.of("latest", "first"));

        // Null ORDER rows do not compete either: ranking by the nullable column leaves only the row that
        // has one, so its total_tokens wins even though it is not the latest.
        assertThat(executeQuery("SELECT arg_max(total_tokens, parent_deployment) AS picked "
                + "FROM dial_usage_log WHERE event_kind = 'nulls' LIMIT 10"))
                .containsExactly(Map.of("picked", 1L));

        // Null only when nothing in the group qualifies. Both rows here have a null ORDER, so the
        // aggregate has no candidate at all — the case a nullable target column has to accommodate.
        execute("INSERT INTO dial_usage_log "
                + "(request_time, event_kind, project_id, user_hash, chat_id, trace_id, deployment, "
                + "parent_deployment, total_tokens) VALUES "
                + "('2026-02-02 10:00:00.000', 'allnull', '', '', '', 'allnull-1', '', NULL, 1),"
                + "('2026-02-02 10:05:00.000', 'allnull', '', '', '', 'allnull-2', '', NULL, 2)");

        assertThat(executeQuery("SELECT arg_max(total_tokens, parent_deployment) AS picked "
                + "FROM dial_usage_log WHERE event_kind = 'allnull' LIMIT 10"))
                .containsExactly(Collections.singletonMap("picked", null));
    }

    // ---------------------------------------------------------------- 7.4

    @Test
    void unboundedQueryUnderTheCapReturnsEveryRow() {
        // No LIMIT is no longer rejected: it runs under the transport's own cap (10000 by default), so a
        // client that cannot generate a LIMIT — Power BI's DirectQuery generator, any aggregate probe —
        // works, while a truncated page is still never returned (see FlightRowCapIntegrationTest).
        final var fixture = fixture();
        for (int i = 0; i < 1200; i++) {
            fixture.row("chat", "p" + i, i);
        }
        fixture.insert();

        // 1200 rows: above the old shared maximum of 1000, below the Flight cap.
        assertThat(executeQuery("SELECT project_id FROM dial_usage_log")).hasSize(1200);
        // OFFSET without LIMIT is bounded by the cap rather than rejected.
        assertThat(executeQuery("SELECT project_id FROM dial_usage_log ORDER BY total_tokens OFFSET 5"))
                .hasSize(1195);
        // An unbounded aggregate returns its single row — it cannot truncate, so it must not be refused.
        assertThat(executeQuery("SELECT count(*) AS n FROM dial_usage_log"))
                .containsExactly(Map.of("n", 1200L));
        // LIMIT above the cap is clamped, not rejected: the literal is a client guard rail, and the
        // result here is well inside the cap (see FlightRowCapIntegrationTest for the boundary cases).
        assertThat(executeQuery("SELECT project_id FROM dial_usage_log LIMIT 1000001")).hasSize(1200);
        // An explicit LIMIT is honored exactly, including above the shared 1000 the endpoint used to inherit.
        assertThat(executeQuery("SELECT project_id FROM dial_usage_log LIMIT 1100")).hasSize(1100);
        assertThat(executeQuery("SELECT project_id FROM dial_usage_log LIMIT 7")).hasSize(7);
    }

    @Test
    void limitGovernanceIsFlightOnly() throws Exception {
        final var fixture = fixture();
        for (int i = 0; i < 150; i++) {
            fixture.row("chat", "p" + i, i);
        }
        fixture.insert();

        // Flight's row cap is a Flight-surface rule. REST keeps the shared governance: the same unbounded
        // statement still truncates silently to the default of 100 there.
        mockMvc.perform(post("/v1/queries/execute-sql").contentType(APPLICATION_JSON).content("""
                        {"sql": "SELECT project_id FROM dial_usage_log"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(100)));

        // …and REST still rejects an oversized explicit limit. The Flight clamp is deliberately not
        // extended here: a REST caller hand-writes a page size, it is not a streaming guard rail.
        mockMvc.perform(post("/v1/queries/execute-sql").contentType(APPLICATION_JSON).content("""
                        {"sql": "SELECT project_id FROM dial_usage_log LIMIT 50000"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("exceeds the maximum")));
    }

    @Test
    void powerBiDirectQueryStatementWithItsGuardRailLimitReturnsRows() {
        fixture()
                .add(r -> r.eventKind("llm_call").deployment("gpt-4o").totalTokens(10))
                .add(r -> r.eventKind("llm_call").deployment("gpt-4o").totalTokens(20))
                .add(r -> r.eventKind("llm_call").deployment("claude").totalTokens(30))
                .insert();

        // The statement Power BI's DirectQuery generator emits for a table visual combining a dimension
        // with a measure, captured from a TCP tee in front of the Flight listener. The LIMIT is Power
        // BI's one-million-plus-one overflow guard, not a request for a million rows — this result is
        // two deployments' worth.
        //
        // One substitution from the captured text: the measure is SUM("total_tokens") rather than
        // SUM("total_price"), because the fixture cannot populate total_price and a SUM over an
        // all-null column returns null, which the statement's own `not "C1" is null` would then filter
        // away — testing nothing. Every other character, including the nesting, the alias-on-aggregate
        // filter and the LIMIT/OFFSET, is as captured.
        final List<Map<String, Object>> rows = executeQuery("""
                select "deployment", "C1"
                from (
                    select "deployment", SUM("total_tokens") as "C1"
                    from "adas"."dial_usage_log"
                    group by "deployment"
                ) as "ITBL"
                where not "C1" is null
                LIMIT 1000001 OFFSET 0""");

        assertThat(rows).extracting(row -> row.get("deployment"))
                .containsExactlyInAnyOrder("gpt-4o", "claude");
        assertThat(rows).extracting(row -> row.get("C1"))
                .containsExactlyInAnyOrder(30L, 30L);
    }

    @Test
    void saturatedGateFailsFastWithResourceExhausted() {
        fixture().row("chat", "p1", 1).insert();
        final int permits = gate.availablePermits();
        for (int i = 0; i < permits; i++) {
            gate.acquire();
        }
        try {
            assertThatThrownBy(() -> executeQuery("SELECT project_id FROM dial_usage_log LIMIT 10"))
                    .isInstanceOfSatisfying(FlightRuntimeException.class,
                            e -> assertThat(e.status().code()).isEqualTo(FlightStatusCode.RESOURCE_EXHAUSTED));
        } finally {
            for (int i = 0; i < permits; i++) {
                gate.release();
            }
        }
        assertThat(gate.availablePermits()).isEqualTo(permits);
    }

    @Test
    void compositeStatementStreamsUnderTheSameGovernance() {
        // A self-join and a window: the composite plan takes the same path as a single-entity statement —
        // the advertised schema at getFlightInfo, the rows at doGet, and the row cap's probe — so the schema
        // must name the joined columns' kinds and the two calls must agree.
        fixture()
                .add(r -> r.eventKind("chat").projectId("p1").totalTokens(100).traceId("t1").chatId("c1"))
                .add(r -> r.eventKind("chat").projectId("p1").totalTokens(300).traceId("t2").chatId("c1"))
                .insert();

        final FlightInfo info = sqlClient.execute("SELECT a.trace_id, b.total_tokens AS other,"
                + " row_number() OVER (ORDER BY a.trace_id) AS rn FROM dial_usage_log a"
                + " JOIN dial_usage_log b ON a.chat_id = b.chat_id AND a.trace_id <> b.trace_id ORDER BY a.trace_id");
        final Schema schema = info.getSchemaOptional().orElseThrow();
        assertThat(schema.findField("trace_id").getType()).isEqualTo(ArrowType.Utf8.INSTANCE);
        assertThat(schema.findField("other").getType()).isEqualTo(new ArrowType.Int(64, true));
        assertThat(schema.findField("rn").getType()).isEqualTo(new ArrowType.Int(64, true));

        try (FlightStream stream = sqlClient.getStream(info.getEndpoints().getFirst().getTicket())) {
            assertThat(stream.getSchema()).isEqualTo(schema);
            final List<Map<String, Object>> rows = collect(stream);
            assertThat(rows).containsExactly(
                    Map.of("trace_id", "t1", "other", 300L, "rn", 1L),
                    Map.of("trace_id", "t2", "other", 100L, "rn", 2L));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void setOperationStreamsUnderTheCap() {
        // The combined result is what the cap and the truncation probe count, and the trailing ORDER BY
        // binds to the last branch as it does in ClickHouse — so the two 'chat' rows of the first branch
        // arrive before the second branch's sorted rows rather than the whole result being sorted.
        fixture()
                .add(r -> r.eventKind("chat").projectId("p1").totalTokens(100).traceId("t2"))
                .add(r -> r.eventKind("chat").projectId("p1").totalTokens(300).traceId("t1"))
                .insert();

        final FlightInfo info = sqlClient.execute("SELECT trace_id FROM dial_usage_log"
                + " UNION ALL SELECT trace_id FROM dial_usage_log ORDER BY trace_id");
        final Schema schema = info.getSchemaOptional().orElseThrow();
        assertThat(schema.findField("trace_id").getType()).isEqualTo(ArrowType.Utf8.INSTANCE);

        try (FlightStream stream = sqlClient.getStream(info.getEndpoints().getFirst().getTicket())) {
            assertThat(stream.getSchema()).isEqualTo(schema);
            final List<Map<String, Object>> rows = collect(stream);
            assertThat(rows).hasSize(4);
            // The last two are the sorted branch; the first two are the unsorted one.
            assertThat(rows.subList(2, 4)).containsExactly(
                    Map.of("trace_id", "t1"), Map.of("trace_id", "t2"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void invalidSqlFailsClosedAndLeaksNothing() throws Exception {
        fixture().row("chat", "p1", 1).insert();

        // These carry no LIMIT deliberately: translation fails first, so the message must still name the
        // real problem rather than the missing limit.
        // A bare UNION, which ClickHouse refuses too — the ALL and DISTINCT spellings stream (see
        // setOperationStreamsUnderTheCap).
        assertThatThrownBy(() -> sqlClient.execute(
                "SELECT project_id FROM dial_usage_log UNION SELECT project_id FROM dial_usage_log"))
                .isInstanceOfSatisfying(FlightRuntimeException.class,
                        e -> assertThat(e.status().code()).isEqualTo(FlightStatusCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> sqlClient.execute(
                "SELECT a.project_id FROM dial_usage_log a JOIN dial_usage_log b ON a.total_tokens > b.total_tokens"))
                .isInstanceOfSatisfying(FlightRuntimeException.class,
                        e -> assertThat(e.status().code()).isEqualTo(FlightStatusCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> sqlClient.execute("SELECT no_such FROM dial_usage_log"))
                .isInstanceOfSatisfying(FlightRuntimeException.class,
                        e -> assertThat(e.status().code()).isEqualTo(FlightStatusCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> executeQuery("SELECT nonexistent_fn(project_id) FROM dial_usage_log LIMIT 10"))
                .isInstanceOf(FlightRuntimeException.class);

        // A successful stream after the failures, then the no-leak gate: close the client and wait
        // for every per-request child allocation to drain back to zero.
        assertThat(executeQuery("SELECT project_id FROM dial_usage_log LIMIT 10")).hasSize(1);
        sqlClient.close();
        clientAllocator.close();
        clientAllocator = new RootAllocator();
        flightClient = FlightClient.builder(
                clientAllocator, Location.forGrpcInsecure("localhost", lifecycle.boundPort())).build();
        sqlClient = new FlightSqlClient(flightClient);
        final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (allocators.allocatedBytes() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(allocators.allocatedBytes()).isZero();
        assertThat(gate.availablePermits()).isPositive();
    }

    @Test
    void finalModifierStreamsAndIllegalFinalIsInvalidArgument() {
        fixture().row("chat", "p1", 1).insert();

        // The shared SQL front end carries FINAL onto Flight as-is; under the shipped flag every read is final anyway.
        assertThat(executeQuery("SELECT project_id FROM dial_usage_log FINAL LIMIT 10")).hasSize(1);
        assertThat(executeQuery("SELECT l.project_id FROM dial_usage_log AS l FINAL LIMIT 10")).hasSize(1);
        // FINAL after a derived table names no entity: a validation failure, like any other.
        assertThatThrownBy(() -> sqlClient.execute(
                "SELECT * FROM (SELECT project_id FROM dial_usage_log) FINAL"))
                .isInstanceOfSatisfying(FlightRuntimeException.class,
                        e -> assertThat(e.status().code()).isEqualTo(FlightStatusCode.INVALID_ARGUMENT));
    }

    @Test
    void preparedStatementsAreUnimplemented() {
        assertThatThrownBy(() -> sqlClient.prepare("SELECT project_id FROM dial_usage_log"))
                .isInstanceOfSatisfying(FlightRuntimeException.class,
                        e -> assertThat(e.status().code()).isEqualTo(FlightStatusCode.UNIMPLEMENTED));
    }

    @Test
    void arrayColumnStreamsAsListVector() {
        enrichmentClient().queryAll("INSERT INTO dial_usage_log "
                + "(request_time, event_kind, project_id, trace_id, execution_path) VALUES "
                + "('2026-01-01 00:00:00.000', 'chat', 'p1', 'arr-1', ['core','assistant']), "
                + "('2026-01-01 00:00:00.000', 'chat', 'p2', 'arr-2', [])");

        final FlightInfo info = sqlClient.execute(
                "SELECT project_id, execution_path FROM dial_usage_log ORDER BY project_id LIMIT 10");
        final Schema schema = info.getSchemaOptional().orElseThrow();
        final Field arrayField = schema.findField("execution_path");
        assertThat(arrayField.getType()).isEqualTo(ArrowType.List.INSTANCE);
        assertThat(arrayField.getFieldType().isNullable()).isTrue();
        assertThat(arrayField.getChildren()).hasSize(1);
        assertThat(arrayField.getChildren().getFirst().getName()).isEqualTo("item");
        assertThat(arrayField.getChildren().getFirst().getType()).isEqualTo(ArrowType.Utf8.INSTANCE);

        try (FlightStream stream = sqlClient.getStream(info.getEndpoints().getFirst().getTicket())) {
            assertThat(stream.getSchema()).isEqualTo(schema);
            final List<Map<String, Object>> rows = collect(stream);
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).get("project_id")).isEqualTo("p1");
            assertThat(listOfStrings(rows.get(0).get("execution_path"))).containsExactly("core", "assistant");
            assertThat(rows.get(1).get("project_id")).isEqualTo("p2");
            // Empty ClickHouse array -> empty (non-null) Arrow list.
            assertThat(listOfStrings(rows.get(1).get("execution_path"))).isEmpty();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void objectColumnStreamsAsJsonText() {
        // Arrow has no schemaless-JSON type, so an object column is advertised as Utf8 and each cell
        // carries the value's JSON serialization — parseable back to what the JSON endpoint returns.
        // The claim document moved to dial_usage_log_payload, so each row is seeded on both tables
        // under a shared event_id — the key the grain join matches on — and at the same request_time,
        // because the two are partition-aligned in the catalog and the keyed join bounds the payload side
        // by the parent's value. Today's date (fixtureTime) keeps it inside the payload table's 90-day TTL.
        enrichmentClient().queryAll("INSERT INTO dial_usage_log "
                + "(event_id, request_time, event_kind, project_id, trace_id) VALUES "
                + "(toUUID('" + eventIdFor("obj-1") + "'), '" + fixtureTime("00:00:00.000") + "', 'chat', 'p1', 'obj-1'), "
                + "(toUUID('" + eventIdFor("obj-2") + "'), '" + fixtureTime("00:00:00.000") + "', 'chat', 'p2', 'obj-2')");
        enrichmentClient().queryAll("INSERT INTO dial_usage_log_payload "
                + "(event_id, request_time, trace_id, core_span_id, jwt_claims) VALUES "
                + "(toUUID('" + eventIdFor("obj-1") + "'), '" + fixtureTime("00:00:00.000") + "', 'obj-1', '', "
                + "'{\"sub\":\"user-1\",\"roles\":[\"admin\"]}'), "
                + "(toUUID('" + eventIdFor("obj-2") + "'), '" + fixtureTime("00:00:00.000") + "', 'obj-2', '', '{}')");

        final FlightInfo info = sqlClient.execute(
                "SELECT project_id, \"dial_usage_log_payload.jwt_claims\" FROM dial_usage_log ORDER BY project_id LIMIT 10");
        final Schema schema = info.getSchemaOptional().orElseThrow();
        final Field objectField = schema.findField("dial_usage_log_payload.jwt_claims");
        assertThat(objectField.getType()).isEqualTo(ArrowType.Utf8.INSTANCE);
        assertThat(objectField.getFieldType().isNullable()).isTrue();
        assertThat(objectField.getChildren()).isEmpty();

        try (FlightStream stream = sqlClient.getStream(info.getEndpoints().getFirst().getTicket())) {
            assertThat(stream.getSchema()).isEqualTo(schema);
            final List<Map<String, Object>> rows = collect(stream);
            assertThat(rows).hasSize(2);
            assertThat(rows.getFirst().get("project_id")).isEqualTo("p1");
            final JsonNode claims = JsonMapper.builder().build().readTree(rows.getFirst().get("dial_usage_log_payload.jwt_claims").toString());
            assertThat(claims.path("sub").asString()).isEqualTo("user-1");
            assertThat(claims.path("roles").path(0).asString()).isEqualTo("admin");
            // Empty ClickHouse JSON -> the non-null text "{}", never a Java-style toString rendering.
            assertThat(rows.get(1).get("dial_usage_log_payload.jwt_claims")).isEqualTo("{}");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void objectColumnMisuseFailsWithInvalidArgument() {
        assertThatThrownBy(() -> sqlClient.execute(
                "SELECT project_id FROM dial_usage_log WHERE \"dial_usage_log_payload.jwt_claims\" IS NOT NULL"))
                .isInstanceOfSatisfying(FlightRuntimeException.class, e -> {
                    assertThat(e.status().code()).isEqualTo(FlightStatusCode.INVALID_ARGUMENT);
                    assertThat(e.status().description()).contains("dial_usage_log_payload.jwt_claims");
                });
    }

    @Test
    void arrayColumnMisuseFailsWithInvalidArgument() {
        assertThatThrownBy(() -> sqlClient.execute(
                "SELECT project_id FROM dial_usage_log WHERE execution_path IS NOT NULL"))
                .isInstanceOfSatisfying(FlightRuntimeException.class, e -> {
                    assertThat(e.status().code()).isEqualTo(FlightStatusCode.INVALID_ARGUMENT);
                    assertThat(e.status().description()).contains("execution_path");
                });
    }

    private static List<String> listOfStrings(Object listValue) {
        assertThat(listValue).isInstanceOf(List.class);
        return ((List<?>) listValue).stream().map(Object::toString).toList();
    }

    @Test
    void timestampSchemaIsMillisUtc() {
        final FlightInfo info = sqlClient.execute("SELECT request_time FROM dial_usage_log LIMIT 10");
        final Schema schema = info.getSchemaOptional().orElseThrow();
        assertThat(schema.findField("request_time").getType())
                .isEqualTo(new ArrowType.Timestamp(TimeUnit.MILLISECOND, "UTC"));
    }

    // ------------------------------------------------------ catalog metadata

    @Test
    void catalogAndSchemaListingsAreEmpty() {
        // ADAS has no catalog or schema namespace: the SQL front end resolves one-part names only, so
        // advertising a namespace would make clients generate qualified statements it cannot answer.
        assertThat(metadataRows(sqlClient.getCatalogs())).isEmpty();
        assertThat(metadataRows(sqlClient.getSchemas(null, null))).isEmpty();
    }

    @Test
    void tableTypesCarriesOnlyTable() {
        assertThat(metadataRows(sqlClient.getTableTypes()))
                .containsExactly(Map.of("table_type", "TABLE"));
    }

    @Test
    void tableListMatchesRestDiscovery() throws Exception {
        final String restEntities = mockMvc.perform(get("/v1/queries/entities"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        final List<String> restNames = new ArrayList<>();
        JsonMapper.builder().build().readTree(restEntities).forEach(node -> restNames.add(node.path("name").asString()));
        assertThat(restNames).contains("dial_usage_log");

        final List<Map<String, Object>> rows = metadataRows(
                sqlClient.getTables(null, null, null, null, false));
        assertThat(rows).extracting(r -> r.get("table_name")).containsExactlyElementsOf(restNames);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row).containsEntry("table_type", "TABLE");
            assertThat(row).containsEntry("catalog_name", null);
            assertThat(row).containsEntry("db_schema_name", null);
        });
    }

    @Test
    void includedTableSchemaEqualsTheStarSchema() throws Exception {
        // The whole point of sourcing catalog rows from CatalogQueryService: what a metadata-driven
        // client reads here is exactly what the star statement it then generates will deliver.
        final Schema starSchema = sqlClient.execute("SELECT * FROM dial_usage_log LIMIT 1")
                .getSchemaOptional().orElseThrow();

        final Map<String, Object> row = metadataRows(sqlClient.getTables(null, null, "dial_usage_log", null, true))
                .getFirst();
        final byte[] serialized = (byte[]) row.get("table_schema");
        final Schema advertised = MessageSerializer.deserializeSchema(
                new ReadChannel(Channels.newChannel(new ByteArrayInputStream(serialized))));
        assertThat(advertised.getFields()).isEqualTo(starSchema.getFields());
    }

    @Test
    void tableFiltersNarrowTheListing() {
        assertThat(metadataRows(sqlClient.getTables(null, null, "dial\\_%", null, false)))
                .extracting(r -> r.get("table_name")).contains("dial_usage_log");
        assertThat(metadataRows(sqlClient.getTables(null, null, "no_such_%", null, false))).isEmpty();
        // Patterns match case-insensitively, like the identifier a client would then write in a
        // statement (GetSqlInfo advertises SQL_CASE_SENSITIVITY_CASE_INSENSITIVE).
        assertThat(metadataRows(sqlClient.getTables(null, null, "DIAL_USAGE_LOG", null, false)))
                .extracting(r -> r.get("table_name")).contains("dial_usage_log");
        // ADAS publishes no catalog, so any named catalog matches nothing.
        assertThat(metadataRows(sqlClient.getTables("some_catalog", null, null, null, false))).isEmpty();
        // A table-types filter that excludes TABLE matches nothing; one that includes it is a no-op.
        assertThat(metadataRows(sqlClient.getTables(null, null, null, List.of("VIEW"), false))).isEmpty();
        assertThat(metadataRows(sqlClient.getTables(null, null, null, List.of("TABLE"), false))).isNotEmpty();
        // The schema pattern is matched against the empty schema name, so only a pattern accepting it
        // leaves rows.
        assertThat(metadataRows(sqlClient.getTables(null, "%", null, null, false))).isNotEmpty();
        assertThat(metadataRows(sqlClient.getTables(null, "public", null, null, false))).isEmpty();
    }

    @Test
    void catalogRpcsStayAvailableWhileTheGateIsSaturated() {
        // The gate caps ClickHouse execution; no catalog RPC touches ClickHouse. Gating them would let
        // query saturation break a driver's discovery, on calls it treats as infallible.
        final int permits = gate.availablePermits();
        for (int i = 0; i < permits; i++) {
            gate.acquire();
        }
        try {
            assertThat(metadataRows(sqlClient.getTables(null, null, null, null, true))).isNotEmpty();
            assertThat(metadataRows(sqlClient.getTableTypes())).hasSize(1);
            assertThat(metadataRows(sqlClient.getXdbcTypeInfo())).isNotEmpty();
            assertThat(metadataRows(sqlClient.getCatalogs())).isEmpty();
            // A catalog *statement* is the same fact discovered a different way, and the connectors that
            // send one never send the RPCs. Its rows are held in process, so it must not queue behind
            // ClickHouse execution either: move gate.acquire() above the plan switch and this hangs
            // until the test times out, while every RPC assertion above stays green.
            assertThat(executeQuery("SELECT DISTINCT TABLE_SCHEMA FROM INFORMATION_SCHEMA.\"TABLES\""))
                    .isNotEmpty();
        } finally {
            for (int i = 0; i < permits; i++) {
                gate.release();
            }
        }
        assertThat(gate.availablePermits()).isEqualTo(permits);
    }

    @Test
    void xdbcTypeInfoCoversTheArrowTypeMapping() {
        final List<Map<String, Object>> rows = metadataRows(sqlClient.getXdbcTypeInfo());
        assertThat(rows).extracting(r -> r.get("type_name")).containsExactly(
                "VARCHAR", "INTEGER", "BIGINT", "DOUBLE", "DECIMAL", "BOOLEAN", "DATE", "TIMESTAMP");
        assertThat(rows).allSatisfy(row -> assertThat(row)
                .containsEntry("nullable", FlightSql.Nullable.NULLABILITY_NULLABLE_VALUE));
        assertThat(rows.get(2)).containsEntry("data_type", FlightSql.XdbcDataType.XDBC_BIGINT_VALUE);

        final List<Map<String, Object>> filtered = metadataRows(
                sqlClient.getXdbcTypeInfo(FlightSql.XdbcDataType.XDBC_BIGINT_VALUE));
        assertThat(filtered).extracting(r -> r.get("type_name")).containsExactly("BIGINT");
    }

    @Test
    void unimplementedMetadataCommandsFailAtGetFlightInfo() {
        // Before this change these returned an endpoint and failed only at doGet, which drivers treat as
        // a protocol violation — the Dremio ODBC driver crashes its host process on it.
        assertThatThrownBy(() -> sqlClient.getPrimaryKeys(TableRef.of(null, null, "dial_usage_log")))
                .isInstanceOfSatisfying(FlightRuntimeException.class,
                        e -> assertThat(e.status().code()).isEqualTo(FlightStatusCode.UNIMPLEMENTED));
        assertThatThrownBy(() -> sqlClient.getExportedKeys(TableRef.of(null, null, "dial_usage_log")))
                .isInstanceOfSatisfying(FlightRuntimeException.class,
                        e -> assertThat(e.status().code()).isEqualTo(FlightStatusCode.UNIMPLEMENTED));
    }

    @Test
    void sqlInfoDeclaresNoCatalogAndNoSchemaNamespace() {
        // The measured defect this pins: without these four values a driver assumes a schema namespace
        // exists (SQL_SCHEMA_TERM='schema', SQL_SCHEMA_USAGE=1), so Power BI qualifies its generated
        // statement with the schema name — which is "" because GetDbSchemas streams no rows — and ADAS
        // gets `from ""."rate_analytics"`, a parse error on an empty quoted identifier. withSqlDdl*
        // does not cover this: it declares DDL support, not whether the namespace level is there.
        final Map<Integer, Object> info = sqlInfo(
                FlightSql.SqlInfo.SQL_SCHEMA_TERM_VALUE,
                FlightSql.SqlInfo.SQL_SCHEMAS_SUPPORTED_ACTIONS_VALUE,
                FlightSql.SqlInfo.SQL_CATALOG_TERM_VALUE,
                FlightSql.SqlInfo.SQL_CATALOGS_SUPPORTED_ACTIONS_VALUE);

        assertThat(info).containsOnlyKeys(
                FlightSql.SqlInfo.SQL_SCHEMA_TERM_VALUE,
                FlightSql.SqlInfo.SQL_SCHEMAS_SUPPORTED_ACTIONS_VALUE,
                FlightSql.SqlInfo.SQL_CATALOG_TERM_VALUE,
                FlightSql.SqlInfo.SQL_CATALOGS_SUPPORTED_ACTIONS_VALUE);
        assertThat(info.get(FlightSql.SqlInfo.SQL_SCHEMA_TERM_VALUE)).hasToString("");
        assertThat(info.get(FlightSql.SqlInfo.SQL_CATALOG_TERM_VALUE)).hasToString("");
        // The protocol carries supported actions as an int32 bitmask, so "no actions" is literally 0 —
        // which is what an ODBC driver surfaces as SQL_SCHEMA_USAGE=0, the value that stops Power BI
        // qualifying. Present-and-zero matters: an omitted key leaves the driver on its "supported" default.
        assertThat(info.get(FlightSql.SqlInfo.SQL_SCHEMAS_SUPPORTED_ACTIONS_VALUE)).isEqualTo(0);
        assertThat(info.get(FlightSql.SqlInfo.SQL_CATALOGS_SUPPORTED_ACTIONS_VALUE)).isEqualTo(0);
    }

    @Test
    void sqlInfoAnswersIdentifierMetadata() {
        // SQL_EXTRA_NAME_CHARACTERS is what an ODBC driver surfaces as SQL_SPECIAL_CHARACTERS, one of the
        // two values measured as <Error> before this change. Presence is the assertion that matters: an
        // unadvertised code is not a missing row, it aborts the whole call (see the negative test below),
        // so a test checking only values would still pass against a regression that dropped the provider.
        final Map<Integer, Object> info = sqlInfo(
                FlightSql.SqlInfo.SQL_EXTRA_NAME_CHARACTERS_VALUE,
                FlightSql.SqlInfo.SQL_QUOTED_IDENTIFIER_CASE_VALUE,
                FlightSql.SqlInfo.SQL_SEARCH_STRING_ESCAPE_VALUE,
                FlightSql.SqlInfo.SQL_KEYWORDS_VALUE);

        assertThat(info).containsKeys(
                FlightSql.SqlInfo.SQL_EXTRA_NAME_CHARACTERS_VALUE,
                FlightSql.SqlInfo.SQL_QUOTED_IDENTIFIER_CASE_VALUE,
                FlightSql.SqlInfo.SQL_SEARCH_STRING_ESCAPE_VALUE,
                FlightSql.SqlInfo.SQL_KEYWORDS_VALUE);
        // Empty: nothing beyond [A-Za-z0-9_] is legal in an *unquoted* identifier. The one dotted form,
        // an enrichment column, is addressable only double-quoted, which this value does not cover.
        assertThat(info.get(FlightSql.SqlInfo.SQL_EXTRA_NAME_CHARACTERS_VALUE)).hasToString("");
        assertThat(info.get(FlightSql.SqlInfo.SQL_SEARCH_STRING_ESCAPE_VALUE)).hasToString("\\");
        assertThat(info.get(FlightSql.SqlInfo.SQL_QUOTED_IDENTIFIER_CASE_VALUE)).isEqualTo(
                (long) FlightSql.SqlSupportedCaseSensitivity.SQL_CASE_SENSITIVITY_CASE_INSENSITIVE_VALUE);
        assertThat((Iterable<?>) info.get(FlightSql.SqlInfo.SQL_KEYWORDS_VALUE)).isEmpty();
    }

    @Test
    void sqlInfoDeclaresUnionSupportRejectedConstructsAndUnboundedLimits() {
        final Map<Integer, Object> info = sqlInfo(
                FlightSql.SqlInfo.SQL_SUPPORTED_UNIONS_VALUE,
                FlightSql.SqlInfo.SQL_SUPPORTED_SUBQUERIES_VALUE,
                FlightSql.SqlInfo.SQL_OUTER_JOINS_SUPPORT_LEVEL_VALUE,
                FlightSql.SqlInfo.SQL_SUPPORTS_LIKE_ESCAPE_CLAUSE_VALUE,
                FlightSql.SqlInfo.SQL_MAX_TABLES_IN_SELECT_VALUE,
                FlightSql.SqlInfo.SQL_MAX_TABLE_NAME_LENGTH_VALUE,
                FlightSql.SqlInfo.SQL_MAX_COLUMN_NAME_LENGTH_VALUE,
                FlightSql.SqlInfo.SQL_MAX_CATALOG_NAME_LENGTH_VALUE,
                FlightSql.SqlInfo.SQL_DB_SCHEMA_NAME_LENGTH_VALUE,
                FlightSql.SqlInfo.SQL_MAX_CURSOR_NAME_LENGTH_VALUE);

        // Unions execute, so this is a positive declaration: both bits, and a statement generated from
        // either is one the front end accepts.
        final int unions = (1 << FlightSql.SqlSupportedUnions.SQL_UNION_VALUE)
                | (1 << FlightSql.SqlSupportedUnions.SQL_UNION_ALL_VALUE);
        assertThat(info.get(FlightSql.SqlInfo.SQL_SUPPORTED_UNIONS_VALUE)).isEqualTo(unions);

        // LIKE ESCAPE is genuinely rejected before ClickHouse is contacted, so declaring it absent tells a
        // client the truth rather than letting it discover an INVALID_ARGUMENT after generating the
        // statement. The next two are INHERITED UNDER-CLAIMS: subqueries and outer joins both execute, and
        // these declarations were never updated when they shipped. They are left as they are deliberately
        // — see the flight-sql-endpoint spec, which records all three — so this assertion pins the
        // under-claim rather than endorsing it.
        assertThat(info.get(FlightSql.SqlInfo.SQL_SUPPORTS_LIKE_ESCAPE_CLAUSE_VALUE)).isEqualTo(false);
        assertThat(info.get(FlightSql.SqlInfo.SQL_SUPPORTED_SUBQUERIES_VALUE)).isEqualTo(0);
        assertThat(info.get(FlightSql.SqlInfo.SQL_OUTER_JOINS_SUPPORT_LEVEL_VALUE)).isEqualTo(0);

        // The third inherited under-claim: `1` was a real bound only while the translator accepted a
        // single-entity FROM. Everything else is 0, the ODBC/JDBC encoding of "no limit imposed" — ADAS
        // validates neither identifier length nor statement size.
        assertThat(info.get(FlightSql.SqlInfo.SQL_MAX_TABLES_IN_SELECT_VALUE)).isEqualTo(1L);
        assertThat(info.get(FlightSql.SqlInfo.SQL_MAX_TABLE_NAME_LENGTH_VALUE)).isEqualTo(0L);
        assertThat(info.get(FlightSql.SqlInfo.SQL_MAX_COLUMN_NAME_LENGTH_VALUE)).isEqualTo(0L);
        assertThat(info.get(FlightSql.SqlInfo.SQL_MAX_CATALOG_NAME_LENGTH_VALUE)).isEqualTo(0L);
        assertThat(info.get(FlightSql.SqlInfo.SQL_DB_SCHEMA_NAME_LENGTH_VALUE)).isEqualTo(0L);
        assertThat(info.get(FlightSql.SqlInfo.SQL_MAX_CURSOR_NAME_LENGTH_VALUE)).isEqualTo(0L);
    }

    @Test
    void sqlInfoAdvertisesTheCapabilitiesAdasActuallyHas() {
        final Map<Integer, Object> info = sqlInfo(
                FlightSql.SqlInfo.SQL_SUPPORTS_COLUMN_ALIASING_VALUE,
                FlightSql.SqlInfo.SQL_SUPPORTS_TABLE_CORRELATION_NAMES_VALUE,
                FlightSql.SqlInfo.SQL_ALL_TABLES_ARE_SELECTABLE_VALUE,
                FlightSql.SqlInfo.SQL_SUPPORTED_RESULT_SET_TYPES_VALUE,
                FlightSql.SqlInfo.SQL_SUPPORTS_EXPRESSIONS_IN_ORDER_BY_VALUE,
                FlightSql.SqlInfo.SQL_SUPPORTS_ORDER_BY_UNRELATED_VALUE,
                FlightSql.SqlInfo.SQL_NULL_PLUS_NULL_IS_NULL_VALUE);

        assertThat(info.get(FlightSql.SqlInfo.SQL_SUPPORTS_COLUMN_ALIASING_VALUE)).isEqualTo(true);
        assertThat(info.get(FlightSql.SqlInfo.SQL_SUPPORTS_TABLE_CORRELATION_NAMES_VALUE)).isEqualTo(true);
        assertThat(info.get(FlightSql.SqlInfo.SQL_ALL_TABLES_ARE_SELECTABLE_VALUE)).isEqualTo(true);
        // A Flight stream is consumed once, forward. The protocol carries the set as a bitmask over the
        // enum's numbers, so a lone FORWARD_ONLY (number 1) is 1 << 1.
        assertThat(info.get(FlightSql.SqlInfo.SQL_SUPPORTED_RESULT_SET_TYPES_VALUE)).isEqualTo(
                1 << FlightSql.SqlSupportedResultSetType.SQL_RESULT_SET_TYPE_FORWARD_ONLY_VALUE);
        // Deliberately narrower than reality — see getStreamSqlInfo. Both are mixed in ADAS
        // (`ORDER BY count(*)` resolves through a select output; `ORDER BY price * 2` is rejected as
        // arithmetic), and the conservative answer steers a client to the form that always works.
        assertThat(info.get(FlightSql.SqlInfo.SQL_SUPPORTS_EXPRESSIONS_IN_ORDER_BY_VALUE)).isEqualTo(false);
        assertThat(info.get(FlightSql.SqlInfo.SQL_SUPPORTS_ORDER_BY_UNRELATED_VALUE)).isEqualTo(false);
        // Standard SQL semantics, and the one advertised positive that cannot be exercised through a
        // statement: the front end rejects arithmetic outright, so no query can observe null + null. A
        // wire-level assertion is the whole of what "covered by a test" can mean for it.
        assertThat(info.get(FlightSql.SqlInfo.SQL_NULL_PLUS_NULL_IS_NULL_VALUE)).isEqualTo(true);
    }

    @Test
    void declaredCapabilitiesAreExecutable() {
        fixture().add(r -> r.eventKind("chat").projectId("alpha").deployment("gpt-4o").totalTokens(10)).insert();

        // SQL_SUPPORTS_TABLE_CORRELATION_NAMES is advertised true, so a correlation name must actually
        // work. `SELECT t.*` was already specified; a qualified *column* reference was not, which is why
        // this is measured rather than assumed.
        assertThat(executeQuery("SELECT t.deployment FROM dial_usage_log t LIMIT 1"))
                .extracting(r -> r.get("deployment")).containsExactly("gpt-4o");

        // SQL_QUOTED_IDENTIFIER_CASE is advertised case-insensitive, so a quoted identifier whose case
        // differs from the catalog's must still resolve.
        assertThat(executeQuery("SELECT \"DEPLOYMENT\" FROM dial_usage_log LIMIT 1"))
                .hasSize(1);
    }

    @Test
    void sqlInfoNullOrderingMatchesTheBackendsActualPlacement() {
        // SQL_NULL_ORDERING describes ClickHouse's behaviour, not ADAS's — the DSL exposes explicit
        // NULLS FIRST/LAST per key without fixing a default. So the declaration is pinned against a
        // real sort rather than derived: this test fails if the backend's default placement changes.
        final String nullPriced = "11111111-1111-1111-1111-111111111111";
        final String pricedRow = "22222222-2222-2222-2222-222222222222";
        typedRow(nullPriced, "chat", 200, true, "NULL", "u1", 1, "2026-01-01 00:00:00");
        typedRow(pricedRow, "chat", 200, true, "12.5", "u2", 2, "2026-01-01 00:00:01");

        final List<Map<String, Object>> ascending = executeQuery(
                "SELECT event_id, total_price FROM dial_usage_log ORDER BY total_price ASC LIMIT 10");
        assertThat(ascending).extracting(r -> r.get("event_id"))
                .containsExactly(pricedRow, nullPriced);

        // Nulls last ascending == nulls sort as the high values == SQL_NULLS_SORTED_HIGH.
        final Map<Integer, Object> info = sqlInfo(FlightSql.SqlInfo.SQL_NULL_ORDERING_VALUE);
        assertThat(info.get(FlightSql.SqlInfo.SQL_NULL_ORDERING_VALUE))
                .isEqualTo((long) FlightSql.SqlNullOrdering.SQL_NULLS_SORTED_HIGH_VALUE);
    }

    /**
     * The protocol reads an empty code list as "send everything", and that is what a driver issues at
     * connect time — so it is the call with the largest blast radius and the one no individual assertion
     * covers. Because {@code SqlInfoBuilder.send} aborts the whole response on a single code it cannot
     * write, presence is the load-bearing property of every declaration in this change: a value that is
     * declared but not writable would take the entire connect-time call down with it. Asserting per-code
     * values one batch at a time cannot see that, since a batch only proves the codes it names.
     */
    @Test
    void theSendEverythingCallReturnsEveryDeclaredValue() {
        final Map<Integer, Object> all = sqlInfo();

        // The count is a floor, not a fixture: adding a declaration should not have to edit this test.
        assertThat(all).hasSizeGreaterThan(45);
        // The declarations no other test reads off the wire. SQL_SUPPORTS_CONVERT is the interesting one:
        // it is the only value written into the dense union's int-to-int-list Map member, so it exercises
        // a union branch nothing else in the suite touches.
        assertThat(all).containsKeys(
                FlightSql.SqlInfo.SQL_SUPPORTS_CONVERT_VALUE,
                FlightSql.SqlInfo.SQL_PROCEDURE_TERM_VALUE,
                FlightSql.SqlInfo.SQL_DEFAULT_TRANSACTION_ISOLATION_VALUE,
                FlightSql.SqlInfo.SQL_SUPPORTED_POSITIONED_COMMANDS_VALUE,
                FlightSql.SqlInfo.SQL_SUPPORTED_TRANSACTIONS_ISOLATION_LEVELS_VALUE,
                FlightSql.SqlInfo.SQL_CATALOG_AT_START_VALUE,
                FlightSql.SqlInfo.SQL_MAX_ROW_SIZE_VALUE,
                FlightSql.SqlInfo.SQL_MAX_STATEMENT_LENGTH_VALUE,
                FlightSql.SqlInfo.SQL_MAX_CONNECTIONS_VALUE,
                FlightSql.SqlInfo.SQL_MAX_COLUMNS_IN_SELECT_VALUE,
                FlightSql.SqlInfo.FLIGHT_SQL_SERVER_CANCEL_VALUE,
                FlightSql.SqlInfo.FLIGHT_SQL_SERVER_BULK_INGESTION_VALUE,
                FlightSql.SqlInfo.FLIGHT_SQL_SERVER_STATEMENT_TIMEOUT_VALUE);

        // Every code it just declared, requested together: no declaration aborts a batched call, which is
        // the failure mode this whole change exists to remove.
        final int[] declared = all.keySet().stream().mapToInt(Integer::intValue).toArray();
        assertThat(sqlInfo(declared)).containsOnlyKeys(all.keySet());
    }

    @Test
    void anUnadvertisedSqlInfoCodeFailsTheWholeCall() {
        // The cost of holding the function-support sets back, made explicit. SqlInfoBuilder.send does
        // `providers.get(code).accept(...)` with no null check, so an unadvertised code NPEs and
        // listener.error aborts the response — taking the supported values requested alongside it down
        // too. This is why SQL_TIMEDATE_FUNCTIONS reads as <Error> in an ODBC trace rather than as a
        // default, and why the excluded set is kept as small as the admissibility rule allows.
        //
        // Advertising a function set is not a free win: it is a claim about what ADAS can *execute*, and
        // a client that believes it folds the function into a generated statement the translator then
        // rejects. Turning a working local computation into a hard error is worse than failing
        // discovery, so the exclusion stands until each bit is mapped against the closed catalog.
        assertThatThrownBy(() -> sqlInfo(
                FlightSql.SqlInfo.SQL_DATETIME_FUNCTIONS_VALUE,
                FlightSql.SqlInfo.SQL_SCHEMA_TERM_VALUE))
                .isInstanceOf(FlightRuntimeException.class);
    }

    @Test
    void credentialHeadersAreIgnoredInNoneMode() {
        // "Open in none mode" covers more than "no credentials needed": no authenticator is registered, so
        // credentials that would be *rejected* in oidc mode must not be inspected at all here. A Basic
        // header with a username the sentinel rule forbids is the sharpest form of that.
        final CallHeaders credentials = new FlightCallHeaders();
        credentials.insert("authorization", "Basic " + Base64.getEncoder()
                .encodeToString("not-the-sentinel:whatever".getBytes(StandardCharsets.UTF_8)));
        final HeaderCallOption option = new HeaderCallOption(credentials);

        final FlightInfo info = sqlClient.execute("SELECT count(*) AS n FROM dial_usage_log", option);
        try (FlightStream stream = sqlClient.getStream(info.getEndpoints().getFirst().getTicket(), option)) {
            assertThat(collect(stream)).hasSize(1);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** The requested `GetSqlInfo` values, keyed by info code, read out of the dense-union value column. */
    private Map<Integer, Object> sqlInfo(int... codes) {
        final FlightInfo info = sqlClient.getSqlInfo(codes);
        try (FlightStream stream = sqlClient.getStream(info.getEndpoints().getFirst().getTicket())) {
            final Map<Integer, Object> values = new LinkedHashMap<>();
            while (stream.next()) {
                final VectorSchemaRoot root = stream.getRoot();
                final UInt4Vector names = (UInt4Vector) root.getVector("info_name");
                final DenseUnionVector union = (DenseUnionVector) root.getVector("value");
                for (int i = 0; i < root.getRowCount(); i++) {
                    values.put(names.get(i), union.getObject(i));
                }
            }
            return values;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Runs the two-call flow for a metadata FlightInfo and materializes the single batch as row maps. */
    private List<Map<String, Object>> metadataRows(FlightInfo info) {
        assertThat(info.getEndpoints()).hasSize(1);
        try (FlightStream stream = sqlClient.getStream(info.getEndpoints().getFirst().getTicket())) {
            assertThat(stream.getSchema()).isEqualTo(info.getSchemaOptional().orElseThrow());
            return collect(stream);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // -------------------------------------------------------------- helpers

    /** Runs the two-call flow (getFlightInfo + doGet) and materializes the stream as row maps. */
    private List<Map<String, Object>> executeQuery(String sql) {
        final FlightInfo info = sqlClient.execute(sql);
        try (FlightStream stream = sqlClient.getStream(info.getEndpoints().getFirst().getTicket())) {
            return collect(stream);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static List<Map<String, Object>> collect(FlightStream stream) {
        final List<Map<String, Object>> rows = new ArrayList<>();
        while (stream.next()) {
            final VectorSchemaRoot root = stream.getRoot();
            for (int i = 0; i < root.getRowCount(); i++) {
                final Map<String, Object> row = new LinkedHashMap<>();
                for (final FieldVector vector : root.getFieldVectors()) {
                    final Object value = vector.isNull(i) ? null : vector.getObject(i);
                    row.put(vector.getName(), value instanceof Text text ? text.toString() : value);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** Inserts one row exercising every supported scalar type (columns outside the shared fixture). */
    private void typedRow(String eventId, String eventKind, int responseStatus, boolean success,
            String totalPrice, String userHash, long totalTokens, String requestTime) {
        enrichmentClient().queryAll("INSERT INTO dial_usage_log "
                + "(event_id, event_kind, response_status, success, total_price, user_hash, total_tokens, "
                + "request_time, trace_id) VALUES ('" + eventId + "', '" + eventKind + "', " + responseStatus
                + ", " + success + ", " + totalPrice + ", '" + userHash + "', " + totalTokens
                + ", '" + requestTime + "', 'typed-row')");
    }

    /** Registers {@code user_dim} as an ACTIVE enrichment of {@code dial_usage_log} in the catalog. */
    private void registerEnrichmentCatalog() {
        final TableDefinitionEntity definition = new TableDefinitionEntity();
        definition.setName("user_dim");
        definition.setType(TableType.ENRICHMENT);
        definition.setSourceTable("dial_usage_log");
        definition.setStatus(TableStatus.ACTIVE);
        definition.setGrainKey("user_hash");
        definitions.save(definition);

        final ColumnMappingEntity column = new ColumnMappingEntity();
        column.setTableName("user_dim");
        column.setSourceName("display_name");
        column.setName("display_name");
        column.setType(QueryFieldType.STRING);
        column.setNullable(true);
        column.setOrdinal(0);
        columnMappings.save(column);
    }

    /** Raw write client against the shared container, for DDL and typed inserts. */
    private static Client enrichmentTools;

    private static Client enrichmentClient() {
        if (enrichmentTools == null) {
            final var container = ClickHouseContainerSupport.CLICKHOUSE;
            enrichmentTools = new Client.Builder()
                    .addEndpoint("http://" + container.getHost() + ":"
                            + container.getMappedPort(ClickHouseContainerSupport.HTTP_PORT) + "/")
                    .setUsername(container.getUsername())
                    .setPassword(container.getPassword())
                    .setDefaultDatabase(container.getDatabaseName())
                    .build();
        }
        return enrichmentTools;
    }
}
