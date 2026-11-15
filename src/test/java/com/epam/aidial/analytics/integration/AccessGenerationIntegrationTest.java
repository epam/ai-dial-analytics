package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.table.TableAccessService;
import com.epam.aidial.analytics.service.table.TableAccessService.EntryRequest;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.web.security.JwtTestTokens;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.LongNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The access generation and the definition row lock, over real PostgreSQL, mirroring the dashboard conflict tests.
 *
 * <p>The races are driven deterministically: the test holds the definition's row lock in its own transaction
 * ({@code findForUpdate}), starts the competing request on another thread, waits until PostgreSQL reports it waiting
 * on a lock, performs the other operation inside the held transaction (the access service joins it) and commits. A
 * writer that loaded the row before taking its lock would then hold a stale managed entity and write the access back as
 * it was, so every writer here is also checked for keeping the change committed while it waited.
 */
class AccessGenerationIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final String BASE = "/v1/tables";
    private static final String TABLE = "ag_probe";
    private static final String LABELS = "ag_probe_labels";
    private static final String PENDING = "ag_pending";
    private static final ExecutorService OTHER = Executors.newSingleThreadExecutor();

    @Autowired
    private TableDefinitionRepository definitions;
    @Autowired
    private TableAccessService access;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void createProbe() throws Exception {
        dropProbe();
        send(post(BASE), "{\"name\":\"" + TABLE + "\",\"type\":\"source\"}").andExpect(status().isCreated());
        send(post(BASE + "/" + TABLE + "/schema"), "{\"columns\":[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"},"
                + "{\"source_name\":\"a\",\"name\":\"a\",\"type\":\"string\"},{\"source_name\":\"b\",\"name\":\"b\",\"type\":\"string\"}],"
                + "\"ordering_key\":[\"k\"]}").andExpect(status().isOk());
    }

    @AfterEach
    void dropProbe() throws Exception {
        for (final String table : List.of(LABELS, TABLE, PENDING)) {
            mockMvc.perform(delete(BASE + "/" + table).header(HttpHeaders.AUTHORIZATION, bearer(admin())));
        }
    }

    @AfterAll
    static void stopThread() {
        OTHER.shutdownNow();
    }

    // --- the generation ---------------------------------------------------------------------------------

    @Test
    void stalePutIsRefusedAndChangesNothing() throws Exception {
        putEntry(TABLE, "x", generation(TABLE), "\"privileges\":[\"read\"]").andExpect(status().isOk());
        putEntry(TABLE, "x", generation(TABLE), "\"privileges\":[\"read\"],\"columns\":[\"a\"]").andExpect(status().isOk());
        final long current = generation(TABLE);
        assertThat(current).isEqualTo(2);

        putEntry(TABLE, "x", current - 1, "\"privileges\":[\"get\"]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("access_conflict"))
                .andExpect(jsonPath("$.message").value(not(containsString(String.valueOf(current)))))
                .andExpect(jsonPath("$.message").value(not(containsString(String.valueOf(current - 1)))));
        // Staleness is reported before the rest of the body, an empty column list included.
        putEntry(TABLE, "x", current - 1, "\"columns\":[]").andExpect(status().isConflict());
        asAdmin(get(BASE + "/" + TABLE + "/access"))
                .andExpect(jsonPath("$.generation").value(current))
                .andExpect(jsonPath("$.roles.x.privileges[0]").value("read"))
                .andExpect(jsonPath("$.roles.x.columns[0]").value("a"));
    }

    @Test
    void putWithoutUsableGenerationIsRefused() throws Exception {
        for (final String generation : List.of("", "\"generation\":null,", "\"generation\":\"0\",", "\"generation\":0.5,",
                "\"generation\":0.0,", "\"generation\":9223372036854775808,")) {
            send(put(BASE + "/" + TABLE + "/access/x"), "{" + generation + "\"privileges\":[\"read\"]}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("validation_error"));
        }
        // A missing generation is named before an invalid body.
        send(put(BASE + "/" + TABLE + "/access/x"), "{\"columns\":[]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("generation")));
        asAdmin(get(BASE + "/" + TABLE + "/access")).andExpect(jsonPath("$.generation").value(0)).andExpect(jsonPath("$.roles").isEmpty());
    }

    @Test
    void deleteMovesTheGenerationAndAnOlderPutConflicts() throws Exception {
        putEntry(TABLE, "x", 0, "\"privileges\":[\"read\"]").andExpect(status().isOk()).andExpect(jsonPath("$.generation").value(1));
        asAdmin(delete(BASE + "/" + TABLE + "/access/x")).andExpect(status().isNoContent());
        assertThat(generation(TABLE)).isEqualTo(2);
        putEntry(TABLE, "y", 1, "\"privileges\":[\"read\"]").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("access_conflict"));
    }

    @Test
    void descriptionUpdateAndAddedColumnDoNotMoveTheGeneration() throws Exception {
        putEntry(TABLE, "x", 0, "\"privileges\":[\"read\"],\"columns\":[\"a\"]").andExpect(status().isOk());
        send(put(BASE + "/" + TABLE), "{\"description\":\"described\"}").andExpect(status().isOk());
        send(patch(BASE + "/" + TABLE + "/schema"), "{\"add\":[{\"source_name\":\"z\",\"name\":\"z\",\"type\":\"string\"}]}")
                .andExpect(status().isOk());
        // Dropping a column no list names leaves it too.
        send(patch(BASE + "/" + TABLE + "/schema"), "{\"drop\":[\"z\"]}").andExpect(status().isOk());
        assertThat(generation(TABLE)).isEqualTo(1);
        putEntry(TABLE, "y", 1, "\"privileges\":[\"read\"]").andExpect(status().isOk());
    }

    @Test
    void firstAuditedWriteOfColumnPolicyCommitsAndIsMirrored() throws Exception {
        putEntry(TABLE, "x", 0, "\"privileges\":[\"read\"],\"columns\":[\"b\",\"a\"]").andExpect(status().isOk());
        final String mirrored = jdbc.queryForObject("SELECT column_policies::text FROM table_definition_aud WHERE name = ?"
                + " AND rev = (SELECT max(rev) FROM table_definition_aud WHERE name = ?)", String.class, TABLE, TABLE);
        assertThat(mirrored).contains("\"x\"").contains("\"a\"").contains("\"b\"");
        assertThat(jdbc.queryForObject("SELECT access_generation FROM table_definition WHERE name = ?", Long.class, TABLE)).isEqualTo(1);
    }

    // --- races ------------------------------------------------------------------------------------------

    @Test
    void twoPutsForDifferentRolesFromOneReadOneConflicts() throws Exception {
        final Future<MockHttpServletResponse> other = whileLocked(TABLE,
                entry(TABLE, "x", 0, "\"privileges\":[\"read\"]"),
                () -> access.put(TABLE, "y", request(0, List.of("read"), null)));
        final MockHttpServletResponse response = other.get(30, TimeUnit.SECONDS);
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains("access_conflict");
        asAdmin(get(BASE + "/" + TABLE + "/access"))
                .andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.roles.y").exists())
                .andExpect(jsonPath("$.roles.x").doesNotExist());
    }

    @Test
    void twoConcurrentDeletesOfOneEntryOneIsNotFound() throws Exception {
        putEntry(TABLE, "x", 0, "\"privileges\":[\"read\"]").andExpect(status().isOk());
        final Future<MockHttpServletResponse> other = whileLocked(TABLE, withAdmin(delete(BASE + "/" + TABLE + "/access/x")),
                () -> access.delete(TABLE, "x"));
        assertThat(other.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(404);
        assertThat(generation(TABLE)).isEqualTo(2);
    }

    @Test
    void deleteWaitingForPutToAnotherRoleSucceeds() throws Exception {
        putEntry(TABLE, "y", 0, "\"privileges\":[\"read\"]").andExpect(status().isOk());
        final Future<MockHttpServletResponse> other = whileLocked(TABLE, withAdmin(delete(BASE + "/" + TABLE + "/access/y")),
                () -> access.put(TABLE, "x", request(1, List.of("read"), null)));
        assertThat(other.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(204);
        asAdmin(get(BASE + "/" + TABLE + "/access"))
                .andExpect(jsonPath("$.generation").value(3))
                .andExpect(jsonPath("$.roles.x").exists())
                .andExpect(jsonPath("$.roles.y").doesNotExist());
    }

    @Test
    void putWaitingForDeleteOfAnotherRoleConflicts() throws Exception {
        putEntry(TABLE, "y", 0, "\"privileges\":[\"read\"]").andExpect(status().isOk());
        final Future<MockHttpServletResponse> other = whileLocked(TABLE, entry(TABLE, "x", 1, "\"privileges\":[\"read\"]"),
                () -> access.delete(TABLE, "y"));
        assertThat(other.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(409);
        asAdmin(get(BASE + "/" + TABLE + "/access"))
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.roles").isEmpty());
    }

    @Test
    void descriptionUpdateWaitingForPutKeepsItAndRecordsIt() throws Exception {
        final Future<MockHttpServletResponse> other = whileLocked(TABLE, withAdmin(put(BASE + "/" + TABLE)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"described\"}"), () -> access.put(TABLE, "x", request(0, List.of("read"), List.of("a", "k"))));
        assertThat(other.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
        expectEntryKept(TABLE, 1);
        latestSnapshot(TABLE).andExpect(jsonPath("$.description").value("described")).andExpect(jsonPath("$.roles.x.columns[1]").value("k"));
    }

    @Test
    void schemaPatchWaitingForPutKeepsItAndRecordsIt() throws Exception {
        final Future<MockHttpServletResponse> other = whileLocked(TABLE, withAdmin(patch(BASE + "/" + TABLE + "/schema"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"add\":[{\"source_name\":\"z\",\"name\":\"z\",\"type\":\"string\"}]}"),
                () -> access.put(TABLE, "x", request(0, List.of("read"), List.of("a", "k"))));
        assertThat(other.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
        expectEntryKept(TABLE, 1);
        latestSnapshot(TABLE).andExpect(jsonPath("$.columns[*].name", org.hamcrest.Matchers.hasItem("z")))
                .andExpect(jsonPath("$.roles.x.columns[0]").value("a"));
    }

    @Test
    void redefineWaitingForPutKeepsIt() throws Exception {
        send(post(BASE), "{\"name\":\"" + PENDING + "\",\"type\":\"source\"}").andExpect(status().isCreated());
        final Future<MockHttpServletResponse> other = whileLocked(PENDING, withAdmin(post(BASE + "/" + PENDING + "/schema"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"columns\":[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"}],"
                                + "\"ordering_key\":[\"k\"]}"),
                () -> access.put(PENDING, "x", request(0, List.of("read"), null)));
        assertThat(other.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
        asAdmin(get(BASE + "/" + PENDING + "/access"))
                .andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.roles.x.privileges[0]").value("read"));
    }

    @Test
    void enrichmentDeleteWaitingForPutOnItsSourcePrunesThePutsList() throws Exception {
        send(post(BASE), "{\"name\":\"" + LABELS + "\",\"type\":\"enrichment\",\"source_table\":\"" + TABLE + "\"}").andExpect(status().isCreated());
        send(post(BASE + "/" + LABELS + "/schema"), "{\"grain_key\":\"k\",\"columns\":[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"}]}")
                .andExpect(status().isOk());
        final Future<MockHttpServletResponse> other = whileLocked(TABLE, withAdmin(delete(BASE + "/" + LABELS)),
                () -> access.put(TABLE, "x", request(0, List.of("read"), List.of("k", LABELS + ".label"))));
        assertThat(other.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(204);
        asAdmin(get(BASE + "/" + TABLE + "/access"))
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.roles.x.columns.length()").value(1))
                .andExpect(jsonPath("$.roles.x.columns[0]").value("k"));
        latestSnapshot(TABLE).andExpect(jsonPath("$.roles.x.columns.length()").value(1));
    }

    /** The enrichment's PATCH locks the enrichment, then waits on its source, and rewrites the source's list as the PUT left it. */
    @Test
    void enrichmentColumnRenameWaitingForPutOnItsSourceRewritesThePutsList() throws Exception {
        send(post(BASE), "{\"name\":\"" + LABELS + "\",\"type\":\"enrichment\",\"source_table\":\"" + TABLE + "\"}").andExpect(status().isCreated());
        send(post(BASE + "/" + LABELS + "/schema"), "{\"grain_key\":\"k\",\"columns\":[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"}]}")
                .andExpect(status().isOk());
        final Future<MockHttpServletResponse> other = whileLocked(TABLE, withAdmin(patch(BASE + "/" + LABELS + "/schema"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rename\":[{\"from\":\"label\",\"to\":\"tag\"}]}"),
                () -> access.put(TABLE, "x", request(0, List.of("read"), List.of("k", LABELS + ".label"))));
        assertThat(other.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
        asAdmin(get(BASE + "/" + TABLE + "/access"))
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.roles.x.columns", org.hamcrest.Matchers.containsInAnyOrder("k", LABELS + ".tag")));
    }

    @Test
    void dropWaitingForPutListingTheColumnIsRefusedBeforeAnyAlter() throws Exception {
        final Future<MockHttpServletResponse> other = whileLocked(TABLE, withAdmin(patch(BASE + "/" + TABLE + "/schema"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"drop\":[\"b\"]}"),
                () -> access.put(TABLE, "y", request(0, List.of(), List.of("b"))));
        final MockHttpServletResponse response = other.get(30, TimeUnit.SECONDS);
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains("column_in_column_policy");
        assertThat(ClickHouseProbe.showCreateTable(TABLE)).contains("`b`");
        asAdmin(get(BASE + "/" + TABLE + "/access")).andExpect(jsonPath("$.roles.y.columns[0]").value("b"));
    }

    @Test
    void putWaitingForDropOfItsColumnIsRefused() throws Exception {
        final Future<MockHttpServletResponse> other = whileLocked(TABLE, entry(TABLE, "y", 0, "\"columns\":[\"b\"]"),
                () -> {
                    try {
                        send(patch(BASE + "/" + TABLE + "/schema"), "{\"drop\":[\"b\"]}").andExpect(status().isOk());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
        final MockHttpServletResponse response = other.get(30, TimeUnit.SECONDS);
        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getContentAsString()).contains("validation_error").contains("b");
        assertThat(ClickHouseProbe.showCreateTable(TABLE)).doesNotContain("`b`");
        asAdmin(get(BASE + "/" + TABLE + "/access")).andExpect(jsonPath("$.roles").isEmpty()).andExpect(jsonPath("$.generation").value(0));
    }

    // --- helpers --------------------------------------------------------------------------------------

    /**
     * Holds {@code table}'s row lock, starts {@code competing} on the other thread, waits until it blocks on a lock, runs
     * {@code underLock} in the held transaction and commits; the competing request then proceeds.
     */
    private Future<MockHttpServletResponse> whileLocked(String table, MockHttpServletRequestBuilder competing, Runnable underLock) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            definitions.findForUpdate(table).orElseThrow();
            final Future<MockHttpServletResponse> started = OTHER.submit(() -> mockMvc.perform(competing).andReturn().getResponse());
            awaitLockWaiter(started);
            underLock.run();
            return started;
        });
    }

    private void awaitLockWaiter(Future<?> competing) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
                Integer.class) == 0) {
            assertThat(competing.isDone()).as("the competing request finished without waiting for the row lock").isFalse();
            assertThat(System.nanoTime()).as("the competing request never waited for the row lock").isLessThan(deadline);
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private void expectEntryKept(String table, long generation) throws Exception {
        asAdmin(get(BASE + "/" + table + "/access"))
                .andExpect(jsonPath("$.generation").value(generation))
                .andExpect(jsonPath("$.roles.x.privileges[0]").value("read"))
                .andExpect(jsonPath("$.roles.x.columns[0]").value("a"))
                .andExpect(jsonPath("$.roles.x.columns[1]").value("k"));
    }

    /** The snapshot at the table's latest audit revision (its row or its columns), read by the admin, so it carries the roles. */
    private ResultActions latestSnapshot(String table) throws Exception {
        final Integer revision = jdbc.queryForObject("SELECT greatest((SELECT max(rev) FROM table_definition_aud WHERE name = ?),"
                + " (SELECT max(rev) FROM column_mapping_aud WHERE table_name = ?))", Integer.class, table, table);
        return asAdmin(get(BASE + "/" + table + "/revision/" + revision)).andExpect(status().isOk());
    }

    private static EntryRequest request(long generation, List<String> privileges, List<String> columns) {
        return new EntryRequest(LongNode.valueOf(generation), privileges, null, columns);
    }

    private long generation(String table) throws Exception {
        return ((Number) JsonPath.read(asAdmin(get(BASE + "/" + table + "/access")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.generation")).longValue();
    }

    private ResultActions putEntry(String table, String role, long generation, String members) throws Exception {
        return mockMvc.perform(entry(table, role, generation, members));
    }

    private MockHttpServletRequestBuilder entry(String table, String role, long generation, String members) {
        return withAdmin(put(BASE + "/" + table + "/access/" + role)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"generation\":" + generation + (members.isEmpty() ? "" : "," + members) + "}");
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(withAdmin(request).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions asAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(withAdmin(request));
    }

    private static MockHttpServletRequestBuilder withAdmin(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, bearer(admin()));
    }

    private static String admin() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE, Map.of("sub", "ag-admin", "roles", List.of("admin"), "email", "admin@test"));
    }
}
