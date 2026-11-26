package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.table.TableAccessService;
import com.epam.aidial.analytics.service.table.TableAccessService.AccessEntries;
import com.epam.aidial.analytics.service.table.TableAccessService.EntryRequest;
import com.epam.aidial.analytics.service.table.TableAccessService.RowsRequest;
import com.epam.aidial.analytics.web.flight.FlightSqlServerLifecycle;
import com.epam.aidial.analytics.web.security.JwtTestTokens;
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
import tools.jackson.databind.node.LongNode;

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
 * {@code oidc} mode with two of the shared context's locally-minted JWT providers — one granting
 * {@code FULL_ADMIN}, one {@code READ_ONLY_ADMIN} — and asserts that the Arrow schema {@code GetTables} reports for
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
class FlightMetadataAuthIntegrationTest extends AbstractOidcAuthIntegrationTest {

    /**
     * The entity whose wildcard schema can still show a sensitive column being filtered.
     *
     * <p>{@code GetTables} reports an entity's STAR schema, and a star never fans out into a grain join —
     * so every enrichment column is absent from it for admin and read-only callers alike. On
     * {@code dial_usage_log} the difference would rest on the two budget paths V59 seeds sensitive, while most
     * of its sensitive columns live on {@code dial_usage_log_payload}. {@code rate_analytics.comment} (V3) is
     * the only sensitive column of its table and has no enrichment beside it, so it shows the rule alone.
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
     * payload enrichment by V38, the cache markers V50 seeds there, and the two budget paths V59 seeds on
     * {@code dial_usage_log} itself. This is the set that matters for {@code INFORMATION_SCHEMA.COLUMNS},
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
            "dial_usage_log_payload.request_cache_control",
            // The computed rebuild fields carry request_body's flag and are listed like any column of the entity.
            "dial_usage_log_payload.request_body_full",
            "dial_usage_log_payload.request_body_rebuild",
            "budget_policy",
            "budget_assignment");

    @Autowired
    private FlightSqlServerLifecycle lifecycle;

    @Autowired
    private TableAccessService tableAccess;

    private BufferAllocator clientAllocator;
    private FlightSqlClient sqlClient;

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
        final List<String> adminFields = tableSchemaFields(SENSITIVE_ENTITY, bearerOption(fullAdminToken()));
        final List<String> readOnlyFields = tableSchemaFields(SENSITIVE_ENTITY, bearerOption(readOnlyToken()));

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
        final List<String> admin = catalogSqlColumnNames(bearerOption(fullAdminToken()));
        final List<String> readOnly = catalogSqlColumnNames(bearerOption(readOnlyToken()));

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

    /**
     * A column policy hides a column on every Flight channel exactly as the clearance does: the {@code GetTables}
     * schema, {@code INFORMATION_SCHEMA.COLUMNS} and a star query's result schema. {@code viewer} reads globally, so an
     * entry on it with no privileges and a column list confines it.
     */
    @Test
    void columnPolicyHidesColumnsOnEveryFlightChannel() throws Exception {
        final AccessEntries before = tableAccess.list("dial_usage_log");
        assertThat(before.roles()).doesNotContainKey("viewer");
        tableAccess.put("dial_usage_log", "viewer", new EntryRequest(LongNode.valueOf(before.generation()), List.of(), null,
                List.of("event_id", "project_id", "request_time")));
        try {
            final CallOption readOnly = bearerOption(readOnlyToken());
            assertThat(tableSchemaFields("dial_usage_log", readOnly)).contains("project_id").doesNotContain("deployment");
            assertThat(catalogSqlColumnNames(readOnly)).containsExactlyInAnyOrder("event_id", "project_id", "request_time");
            final FlightInfo info = sqlClient.execute("SELECT * FROM dial_usage_log LIMIT 1", readOnly);
            assertThat(info.getSchemaOptional().orElseThrow().getFields().stream().map(Field::getName).toList())
                    .containsExactlyInAnyOrder("event_id", "project_id", "request_time");
            assertThatThrownBy(() -> drain("SELECT deployment FROM dial_usage_log LIMIT 1", readOnly)).hasMessageContaining("deployment");

            assertThat(tableSchemaFields("dial_usage_log", bearerOption(fullAdminToken()))).contains("deployment");
        } finally {
            tableAccess.delete("dial_usage_log", "viewer", null);
        }
    }

    /**
     * A masked column is listed on every Flight channel as a visible one is. {@code viewer}, confined to the rows of
     * {@code P1} with three columns, beside {@code fm-u}, which reads the rows of {@code P2} with every column, masks the
     * rest instead of hiding them, so each channel lists what {@code viewer} alone lists without an entry.
     */
    @Test
    void columnPolicyMasksColumnsListedOnEveryFlightChannel() throws Exception {
        final CallOption unconfined = bearerOption(readOnlyToken());
        final List<String> schema = tableSchemaFields("dial_usage_log", unconfined);
        final List<String> catalog = catalogSqlColumnNames(unconfined);
        final AccessEntries before = tableAccess.list("dial_usage_log");
        assertThat(before.roles()).doesNotContainKeys("viewer", "fm-u");
        try {
            tableAccess.put("dial_usage_log", "viewer", new EntryRequest(LongNode.valueOf(before.generation()), List.of(),
                    new RowsRequest(null, "project_id = 'P1'"), List.of("event_id", "project_id", "request_time")));
            tableAccess.put("dial_usage_log", "fm-u", new EntryRequest(LongNode.valueOf(tableAccess.list("dial_usage_log").generation()),
                    List.of("read"), new RowsRequest(null, "project_id = 'P2'"), null));
            // The entries take: viewer alone is confined to its three columns.
            assertThat(tableSchemaFields("dial_usage_log", unconfined)).containsExactlyInAnyOrder("event_id", "project_id", "request_time");

            final CallOption masked = bearerOption(JwtTestTokens.token(READONLY_ISSUER, AUDIENCE,
                    Map.of("sub", "fm-user", "roles", List.of("viewer", "fm-u"), "email", "fm@test")));
            assertThat(tableSchemaFields("dial_usage_log", masked)).contains("deployment").containsExactlyElementsOf(schema);
            assertThat(catalogSqlColumnNames(masked)).containsExactlyElementsOf(catalog);
            final FlightInfo info = sqlClient.execute("SELECT * FROM dial_usage_log LIMIT 1", masked);
            assertThat(info.getSchemaOptional().orElseThrow().getFields().stream().map(Field::getName).toList()).contains("deployment");
        } finally {
            for (final String role : List.of("viewer", "fm-u")) {
                if (tableAccess.list("dial_usage_log").roles().containsKey(role)) {
                    tableAccess.delete("dial_usage_log", role, null);
                }
            }
        }
    }

    @Test
    void readOnlyCallerCannotSelectTheMergedResponse() {
        // Seeing the column and referencing it are two doors on the same room, and V29 has to close both.
        // A sensitive column is removed from the non-entitled caller's model outright, so naming it is not
        // a permission error the caller can appeal — the field simply does not exist for them. The admin
        // half is what makes the read-only half meaningful: the statement is valid, the caller is not.
        final String sql = "SELECT \"dial_usage_log_payload.assembled_response\" FROM dial_usage_log LIMIT 1";

        assertThatCode(() -> drain(sql, bearerOption(fullAdminToken()))).doesNotThrowAnyException();
        assertThatThrownBy(() -> drain(sql, bearerOption(readOnlyToken())))
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
        final CallOption readOnly = bearerOption(readOnlyToken());
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

    private static CallOption bearerOption(String token) {
        final FlightCallHeaders headers = new FlightCallHeaders();
        headers.insert("authorization", bearer(token));
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
