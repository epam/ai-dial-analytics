package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.flight.FlightSqlServerLifecycle;
import com.epam.aidial.analytics.web.security.JwtTestTokens;
import com.epam.aidial.analytics.web.security.TestSecurityConfig;
import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ReadChannel;
import org.apache.arrow.vector.ipc.message.MessageSerializer;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Catalog metadata is subject to the same role-based sensitive-column filtering as query results
 * (spec: "Catalog metadata honors the caller's column visibility"). Runs the Flight endpoint in
 * {@code oidc} mode with two locally-minted JWT providers — one granting {@code FULL_ADMIN}, one
 * {@code READ_ONLY_ADMIN} — and asserts that the Arrow schema {@code GetTables} reports for
 * {@code dial_usage_log} omits the {@code sensitive} columns for the read-only caller while carrying
 * them for the admin.
 *
 * <p>The leak this guards against is subtler than returning values: a catalog that merely <em>names</em>
 * {@code dial_usage_log_payload.jwt_claims} tells a caller the column exists, which the query path deliberately does not. The
 * assertion is therefore about the advertised schema, not about any row content.
 *
 * <p>The same read-only caller also pins the other direction — that nothing the catalog lists is out of
 * reach for the caller it was listed to, which is what {@code SQL_ALL_TABLES_ARE_SELECTABLE=true}
 * declares (spec: "Every value this requirement declares as supported SHALL be covered by a test that
 * exercises the behaviour"). Column visibility narrows; entity visibility does not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "poll.enabled=false",
        "security.mode=oidc",
        "security.defaults.email-claim=email",
        "security.defaults.principal-claim=sub",
        "providers.full.issuer=" + FlightMetadataAuthIntegrationTest.FULL_ISSUER,
        "providers.full.jwk-set-uri=https://issuer.test/full/jwks",
        "providers.full.audiences=" + FlightMetadataAuthIntegrationTest.AUDIENCE,
        "providers.full.role-claims=roles",
        "providers.full.roles-mapping={\"admin\":[\"FULL_ADMIN\"]}",
        "providers.readonly.issuer=" + FlightMetadataAuthIntegrationTest.READONLY_ISSUER,
        "providers.readonly.jwk-set-uri=https://issuer.test/readonly/jwks",
        "providers.readonly.audiences=" + FlightMetadataAuthIntegrationTest.AUDIENCE,
        "providers.readonly.role-claims=roles",
        "providers.readonly.roles-mapping={\"viewer\":[\"READ_ONLY_ADMIN\"]}",
})
@Import(TestSecurityConfig.class)
class FlightMetadataAuthIntegrationTest {

    static final String AUDIENCE = "adas-test";
    static final String FULL_ISSUER = "https://issuer.test/full";
    static final String READONLY_ISSUER = "https://issuer.test/readonly";

    /**
     * The entity whose wildcard schema can still show a sensitive column being filtered.
     *
     * <p>Asserted on {@code rate_analytics}, not on {@code dial_usage_log}, and the reason is the split.
     * {@code GetTables} reports an entity's STAR schema, and a star never fans out into a grain join —
     * so every enrichment column is absent from it for admin and read-only callers alike. Since all
     * five of {@code dial_usage_log}'s sensitive columns moved to {@code dial_usage_log_payload}, that
     * table can no longer show a filtering difference here at all: both callers would see the same
     * list, and the assertion would pass while testing nothing. {@code rate_analytics.comment} (V3) is
     * the remaining sensitive SOURCE column, so it is what can still demonstrate the rule.
     *
     * <p>The enrichment columns' filtering is not left unguarded — it is
     * {@link #catalogSqlHidesSensitiveColumnsFromReadOnlyCaller} that covers them, because
     * {@code INFORMATION_SCHEMA.COLUMNS} enumerates the full binding list rather than the wildcard
     * expansion, and therefore does carry them.
     */
    private static final String SENSITIVE_ENTITY = "rate_analytics";

    private static final List<String> SENSITIVE_COLUMNS = List.of("comment");

    /**
     * Every column reachable from {@code dial_usage_log} that is marked {@code sensitive} — seeded by
     * migrations V3 ({@code jwt_claims}, {@code request_body}, {@code response_body}), V12
     * ({@code request_tags}) and V29 ({@code assembled_response}), all five re-keyed onto the
     * payload enrichment by V38, and the cache markers V50 seeds there. This is the set that matters for {@code INFORMATION_SCHEMA.COLUMNS},
     * which enumerates the entity's full binding list rather than its wildcard expansion, so the
     * payload columns ARE visible to an admin there — which is what makes this the channel that can
     * still prove the filtering the wildcard schema no longer can.
     */
    private static final List<String> ALL_SENSITIVE_COLUMNS = List.of(
            "dial_usage_log_payload.jwt_claims",
            "dial_usage_log_payload.request_tags",
            "dial_usage_log_payload.request_body",
            "dial_usage_log_payload.response_body",
            "dial_usage_log_payload.assembled_response",
            "dial_usage_log_payload.request_cache_markers");

    @Autowired
    private FlightSqlServerLifecycle lifecycle;

    private BufferAllocator clientAllocator;
    private FlightSqlClient sqlClient;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        ClickHouseContainerSupport.registerProperties(registry);
        registry.add("flight.enabled", () -> "true");
        registry.add("flight.port", () -> "0");
    }

    @BeforeEach
    void openClient() {
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
    void tableSchemaHidesSensitiveColumnsFromReadOnlyCaller() {
        final List<String> adminFields = tableSchemaFields(SENSITIVE_ENTITY, bearer(fullAdminToken()));
        final List<String> readOnlyFields = tableSchemaFields(SENSITIVE_ENTITY, bearer(readOnlyToken()));

        assertThat(adminFields).containsAll(SENSITIVE_COLUMNS);
        assertThat(readOnlyFields).doesNotContainAnyElementsOf(SENSITIVE_COLUMNS);
        // Everything else is identical: sensitivity hides those columns and nothing more.
        assertThat(readOnlyFields)
                .containsExactlyElementsOf(adminFields.stream().filter(f -> !SENSITIVE_COLUMNS.contains(f)).toList());
    }

    /**
     * {@code INFORMATION_SCHEMA.COLUMNS} is a second channel that enumerates columns, so the filtering
     * {@code GetTables} applies has to hold here too or the surface leaks through the door it just
     * opened. Correct by construction — the rows are built from the caller's resolved model, planned
     * inside {@code asCaller} — but "by construction" is exactly what a refactor breaks: hoisting the
     * plan out of {@code asCaller}, or memoizing the catalog snapshot across callers, would expose
     * sensitive column names here while leaving every RPC-level assertion green.
     */
    @Test
    void catalogSqlHidesSensitiveColumnsFromReadOnlyCaller() {
        final List<String> admin = catalogSqlColumnNames(bearer(fullAdminToken()));
        final List<String> readOnly = catalogSqlColumnNames(bearer(readOnlyToken()));

        assertThat(admin).containsAll(ALL_SENSITIVE_COLUMNS);
        assertThat(readOnly).doesNotContainAnyElementsOf(ALL_SENSITIVE_COLUMNS);
        assertThat(readOnly).containsExactlyElementsOf(
                admin.stream().filter(c -> !ALL_SENSITIVE_COLUMNS.contains(c)).toList());
    }

    /** The {@code COLUMN_NAME} values a catalog SQL statement streams to {@code credentials}. */
    private List<String> catalogSqlColumnNames(CallOption credentials) {
        final String sql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'dial_usage_log' ORDER BY ORDINAL_POSITION";
        final List<String> names = new ArrayList<>();
        try {
            final FlightInfo info = sqlClient.execute(sql, credentials);
            try (FlightStream stream = sqlClient.getStream(info.getEndpoints().getFirst().getTicket(), credentials)) {
                while (stream.next()) {
                    final VarCharVector column = (VarCharVector) stream.getRoot().getVector("COLUMN_NAME");
                    for (int row = 0; row < stream.getRoot().getRowCount(); row++) {
                        names.add(column.getObject(row).toString());
                    }
                }
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return names;
    }

    @Test
    void readOnlyCallerCannotSelectTheMergedResponse() {
        // Seeing the column and referencing it are two doors on the same room, and V29 has to close both.
        // A sensitive column is removed from the non-entitled caller's model outright, so naming it is not
        // a permission error the caller can appeal — the field simply does not exist for them. The admin
        // half is what makes the read-only half meaningful: the statement is valid, the caller is not.
        final String sql = "SELECT \"dial_usage_log_payload.assembled_response\" FROM dial_usage_log LIMIT 1";

        assertThatCode(() -> drain(sql, bearer(fullAdminToken()))).doesNotThrowAnyException();
        assertThatThrownBy(() -> drain(sql, bearer(readOnlyToken())))
                .hasMessageContaining("dial_usage_log_payload.assembled_response");
    }

    /** Runs {@code sql} as {@code credentials} and drains the stream, so an execution failure surfaces. */
    private void drain(String sql, CallOption credentials) throws Exception {
        final FlightInfo info = sqlClient.execute(sql, credentials);
        try (FlightStream stream = sqlClient.getStream(info.getEndpoints().getFirst().getTicket(), credentials)) {
            while (stream.next()) {
                assertThat(stream.getRoot().getRowCount()).isGreaterThanOrEqualTo(0);
            }
        }
    }

    @Test
    void everyTableTheCatalogListsIsSelectableByTheSameCaller() {
        // GetSqlInfo advertises SQL_ALL_TABLES_ARE_SELECTABLE=true, and the admissibility rule says a
        // positive claim is backed by a test that exercises the behaviour. This is that test: it takes
        // the entity names GetTables streams to a non-admin caller and issues a statement against each
        // with the same credentials.
        //
        // What the claim rests on matters, because the obvious rationale is wrong: the catalog is NOT
        // access-filtered per table. CatalogQueryService.queryableEntityNames(any()) returns every ACTIVE
        // source with no caller in scope, and the caller-scoped filtering in the catalog path
        // (SensitiveColumnAccess) hides *columns*, not entities. The declaration is true because ADAS
        // gates no entity's SELECT at all — per-table authorization (TableAccessEvaluator) covers WRITE
        // and MODIFY only. That is exactly the kind of fact that a spec reading cannot pin and only an
        // executed statement can: introduce a per-table read grant and this test fails before a client
        // discovers the lie as a PERMISSION_DENIED on a table its navigator offered.
        final CallOption readOnly = bearer(readOnlyToken());
        final List<String> listed = listedTableNames(readOnly);

        assertThat(listed).contains("dial_usage_log");
        for (final String table : listed) {
            selectOneRow(table, readOnly);
        }
    }

    /** The {@code table_name} values {@code GetTables} streams to {@code credentials}. */
    private List<String> listedTableNames(CallOption credentials) {
        final FlightInfo info = sqlClient.getTables(null, null, null, null, false, credentials);
        final List<String> names = new ArrayList<>();
        try (FlightStream stream = sqlClient.getStream(info.getEndpoints().getFirst().getTicket(), credentials)) {
            while (stream.next()) {
                final VarCharVector column = (VarCharVector) stream.getRoot().getVector("table_name");
                for (int row = 0; row < stream.getRoot().getRowCount(); row++) {
                    names.add(column.getObject(row).toString());
                }
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return names;
    }

    /**
     * Runs {@code SELECT * FROM "table" LIMIT 1} as {@code credentials} and drains the stream. Draining
     * is the assertion: translation failures surface at {@code getFlightInfo}, but an authorization or
     * execution failure only surfaces once the stream is pulled.
     */
    private void selectOneRow(String table, CallOption credentials) {
        try {
            final FlightInfo info = sqlClient.execute(
                    "SELECT * FROM \"" + table + "\" LIMIT 1", credentials);
            try (FlightStream stream = sqlClient.getStream(info.getEndpoints().getFirst().getTicket(), credentials)) {
                while (stream.next()) {
                    assertThat(stream.getRoot().getRowCount()).isLessThanOrEqualTo(1);
                }
            }
        } catch (Exception e) {
            throw new AssertionError(
                    "GetTables listed " + table + " but the same caller cannot select from it", e);
        }
    }

    /** The field names of the Arrow schema {@code GetTables} reports for {@code dial_usage_log}. */
    private List<String> tableSchemaFields(String table, CallOption credentials) {
        final FlightInfo info = sqlClient.getTables(null, null, table, null, true, credentials);
        try (FlightStream stream = sqlClient.getStream(info.getEndpoints().getFirst().getTicket(), credentials)) {
            assertThat(stream.next()).isTrue();
            final VectorSchemaRoot root = stream.getRoot();
            assertThat(root.getRowCount()).isEqualTo(1);
            final byte[] serialized = ((VarBinaryVector) root.getVector("table_schema")).get(0);
            final Schema schema = MessageSerializer.deserializeSchema(
                    new ReadChannel(Channels.newChannel(new ByteArrayInputStream(serialized))));
            return schema.getFields().stream().map(Field::getName).toList();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static CallOption bearer(String token) {
        final FlightCallHeaders headers = new FlightCallHeaders();
        headers.insert("authorization", "Bearer " + token);
        return new HeaderCallOption(headers);
    }

    private static String fullAdminToken() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "full-user", "roles", List.of("admin"), "email", "full@test"));
    }

    private static String readOnlyToken() {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE,
                Map.of("sub", "readonly-user", "roles", List.of("viewer"), "email", "readonly@test"));
    }
}
