package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.JwtTestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end behavior of role-based per-table access control in {@code oidc} mode. Two JWT providers
 * are configured: {@code full} (role {@code admin} → FULL_ADMIN) and {@code readonly} (role
 * {@code viewer} → READ_ONLY_ADMIN). A "writer" caller is minted from the readonly issuer carrying the
 * <em>unmapped</em> raw role {@code analytics-writer}, so it maps to no application role and can be
 * authorized only by a matching table access entry.
 *
 * <p>Requires Docker (PostgreSQL + ClickHouse Testcontainers): row writes and CREATE TABLE run for real.
 */
class TableAccessIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final String BASE = "/v1/tables";
    private static final String PROBE = "access_probe";
    private static final String WRITER_ROLE = "analytics-writer";

    @BeforeEach
    void createProbe() throws Exception {
        // Identity → define + materialize schema, all as FULL_ADMIN, so the probe is ACTIVE and writable.
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + PROBE + "\",\"description\":\"access probe\",\"type\":\"source\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post(BASE + "/" + PROBE + "/schema")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columns\":[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"}],"
                                + "\"ordering_key\":[\"k\"]}"))
                .andExpect(status().isOk());
    }

    @AfterEach
    void dropProbe() throws Exception {
        mockMvc.perform(delete(BASE + "/" + PROBE).header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())));
    }

    // --- credentials -----------------------------------------------------------------------------

    private static String fullAdminToken() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "full-user", "roles", List.of("admin"), "email", "full@test"));
    }

    /** READ_ONLY_ADMIN caller with no writer role. */
    private static String readOnlyToken() {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE,
                Map.of("sub", "readonly-user", "roles", List.of("viewer"), "email", "readonly@test"));
    }

    /** Carries the unmapped raw role {@code analytics-writer} → no application role. */
    private static String writerToken() {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE,
                Map.of("sub", "writer-user", "roles", List.of(WRITER_ROLE), "email", "writer@test"));
    }

    /** READ_ONLY_ADMIN (can read the catalog) that also carries the raw {@code analytics-writer} role. */
    private static String readingWriterToken() {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE,
                Map.of("sub", "reading-writer", "roles", List.of("viewer", WRITER_ROLE), "email", "rw@test"));
    }

    private void putAccess(String body) throws Exception {
        mockMvc.perform(put(BASE + "/" + PROBE + "/access")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    // --- grant management (TABLE_GRANT on both the read and the write) ---------------------------

    @Test
    void grantAdminGetsAndMergesGrants() throws Exception {
        mockMvc.perform(get(BASE + "/" + PROBE + "/access").header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list").doesNotExist())
                .andExpect(jsonPath("$.get").isArray())
                .andExpect(jsonPath("$.read").isArray())
                .andExpect(jsonPath("$.sensitive").isArray())
                .andExpect(jsonPath("$.write").isArray())
                .andExpect(jsonPath("$.modify").isArray());

        // The PUT answers with the stored lists AND the effective flags, because the stored lists alone do
        // not say what a grant achieved — two rules live in one document.
        mockMvc.perform(put(BASE + "/" + PROBE + "/access")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"write\":[\"" + WRITER_ROLE + "\",\"" + WRITER_ROLE + "\"],\"modify\":[]}"))
                .andExpect(status().isOk())
                // The five lists stay at the top level, exactly where GET puts them: a client reading
                // response.write must keep working, which is the whole point of the merge semantics.
                .andExpect(jsonPath("$.write.length()").value(1))
                .andExpect(jsonPath("$.write[0]").value(WRITER_ROLE))
                .andExpect(jsonPath("$.permissions.write").value(true));
    }

    @Test
    void absentKeyKeepsTheStoredListAndEmptyArrayRevokes() throws Exception {
        putAccess("{\"read\":[\"" + WRITER_ROLE + "\"]}");

        // A client written against the old two-key body must not revoke the read grants it never mentioned.
        mockMvc.perform(put(BASE + "/" + PROBE + "/access")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"write\":[],\"modify\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read[0]").value(WRITER_ROLE));

        mockMvc.perform(put(BASE + "/" + PROBE + "/access")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"read\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").isEmpty());
    }

    @Test
    void readOnlyAdminCanNeitherReadNorReplaceGrants() throws Exception {
        putAccess("{\"write\":[\"" + WRITER_ROLE + "\"],\"modify\":[]}");

        // The read moved from "any application role" to TABLE_GRANT: the lists disclose which provider
        // roles exist and what each may do, which is organisational structure.
        mockMvc.perform(get(BASE + "/" + PROBE + "/access").header(HttpHeaders.AUTHORIZATION, bearer(readOnlyToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(put(BASE + "/" + PROBE + "/access")
                        .header(HttpHeaders.AUTHORIZATION, bearer(readOnlyToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"write\":[\"" + WRITER_ROLE + "\"],\"modify\":[]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void rolelessCallerCannotReadAccess() throws Exception {
        // Authorized per table for writes, but carries no application role — catalog reads stay closed.
        putAccess("{\"write\":[\"" + WRITER_ROLE + "\"],\"modify\":[]}");

        mockMvc.perform(get(BASE + "/" + PROBE + "/access").header(HttpHeaders.AUTHORIZATION, bearer(writerToken())))
                .andExpect(status().isForbidden());
    }

    @Test
    void systemTableRefusesWriteGrantsAndAcceptsReadGrants() throws Exception {
        // The refusal moved from the object to the keys. A system table is fed out of band, so every
        // modifying operation on it is refused whatever a grant says — but that is a reason to keep
        // `write`/`modify` empty, not a reason to refuse a read grant, and refusing every PUT made
        // per-table read grants inapplicable to dial_usage_log, the primary analytics table.
        mockMvc.perform(put(BASE + "/dial_usage_log/access")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"write\":[\"" + WRITER_ROLE + "\"],\"modify\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("table_is_system"));

        try {
            // A read-side key on the same table is stored, and a five-key body with empty write/modify
            // succeeds — which is what lets an existing two-key client keep working.
            mockMvc.perform(put(BASE + "/dial_usage_log/access")
                            .header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"read\":[\"" + WRITER_ROLE + "\"],\"write\":[],\"modify\":[]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.read[0]").value(WRITER_ROLE))
                    .andExpect(jsonPath("$.write").isEmpty());

            // And the reported permissions tell the truth about it: the read-side flags follow the grant
            // while write/modify stay hard false for every caller.
            mockMvc.perform(get(BASE + "/dial_usage_log")
                            .header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissions.read").value(true))
                    .andExpect(jsonPath("$.permissions.write").value(false))
                    .andExpect(jsonPath("$.permissions.modify").value(false));
        } finally {
            mockMvc.perform(put(BASE + "/dial_usage_log/access")
                            .header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"get\":[],\"read\":[],\"sensitive\":[]}"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void accessOnUnknownTableIs404() throws Exception {
        mockMvc.perform(get(BASE + "/does_not_exist/access").header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    // --- per-table authorization by role ---------------------------------------------------------

    @Test
    void writeRoleAllowsRowsButNotModify() throws Exception {
        putAccess("{\"write\":[\"" + WRITER_ROLE + "\"],\"modify\":[]}");

        mockMvc.perform(post(BASE + "/" + PROBE + "/rows")
                        .header(HttpHeaders.AUTHORIZATION, bearer(writerToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"k\":\"v1\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(1));

        mockMvc.perform(put(BASE + "/" + PROBE)
                        .header(HttpHeaders.AUTHORIZATION, bearer(writerToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"changed\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void modifyRoleAllowsDescriptionUpdate() throws Exception {
        putAccess("{\"write\":[],\"modify\":[\"" + WRITER_ROLE + "\"]}");

        mockMvc.perform(put(BASE + "/" + PROBE)
                        .header(HttpHeaders.AUTHORIZATION, bearer(writerToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"changed by grantee\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("changed by grantee"));
    }

    @Test
    void withoutAccessMutationIsForbidden() throws Exception {
        mockMvc.perform(post(BASE + "/" + PROBE + "/rows")
                        .header(HttpHeaders.AUTHORIZATION, bearer(writerToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"k\":\"v1\"}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    /**
     * The translation and validation endpoints resolve at {@code TABLE_GET}, not {@code TABLE_READ}: they
     * validate against the catalog model and never contact ClickHouse, so they disclose schema rather than
     * data. This is the pair of assertions that pins the difference — the same statement is translatable and
     * validatable but not executable for a {@code get}-only grantee.
     */
    @Test
    void getGrantAllowsTranslationAndValidationButNotExecution() throws Exception {
        putAccess("{\"get\":[\"" + WRITER_ROLE + "\"]}");
        final String statement = "{\"sql\":\"SELECT k FROM " + PROBE + " LIMIT 1\"}";

        mockMvc.perform(post("/v1/queries/translate-sql")
                        .header(HttpHeaders.AUTHORIZATION, bearer(writerToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(statement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query.entity").value(PROBE));

        mockMvc.perform(post("/v1/queries/validate-sql")
                        .header(HttpHeaders.AUTHORIZATION, bearer(writerToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(statement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        // The same statement on the execute path resolves at TABLE_READ, which this caller does not hold,
        // so the table is absent from its model and the relation is unknown — never a 403.
        mockMvc.perform(post("/v1/queries/execute-sql")
                        .header(HttpHeaders.AUTHORIZATION, bearer(writerToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(statement))
                .andExpect(status().isBadRequest());
    }

    /** And a table below even the `get` rung is unresolvable on the translation path too. */
    @Test
    void noReadSideGrantMakesTranslationReportAnUnknownRelation() throws Exception {
        putAccess("{\"write\":[\"" + WRITER_ROLE + "\"]}");

        mockMvc.perform(post("/v1/queries/translate-sql")
                        .header(HttpHeaders.AUTHORIZATION, bearer(writerToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT k FROM " + PROBE + " LIMIT 1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void writeOnlyCallerCannotReadOrList() throws Exception {
        putAccess("{\"write\":[\"" + WRITER_ROLE + "\"],\"modify\":[]}");

        // Authorized to write the specific table...
        mockMvc.perform(post(BASE + "/" + PROBE + "/rows")
                        .header(HttpHeaders.AUTHORIZATION, bearer(writerToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"k\":\"v2\"}]}"))
                .andExpect(status().isOk());

        // ...and invisible in the catalog, because TABLE_WRITE implies nothing. The listing is filtered
        // rather than refused — an empty listing is a 200 with no entries, not a 403, so the response does
        // not disclose that the caller is entitled to nothing.
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(writerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables").isEmpty());
        // And the single get answers 404, not 403: a missing read privilege must not reveal the table.
        mockMvc.perform(get(BASE + "/" + PROBE).header(HttpHeaders.AUTHORIZATION, bearer(writerToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void readGrantMakesTheTableVisibleAndReadable() throws Exception {
        putAccess("{\"read\":[\"" + WRITER_ROLE + "\"]}");

        // A grant alone authorizes: this caller holds no global privilege at all.
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(writerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name == '" + PROBE + "')]").exists());
        // Implication is reported after derivation, though the stored list names the role under `read` only.
        mockMvc.perform(get(BASE + "/" + PROBE).header(HttpHeaders.AUTHORIZATION, bearer(writerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.read").value(true))
                .andExpect(jsonPath("$.permissions.get").value(true))
                .andExpect(jsonPath("$.permissions.sensitive").value(false))
                .andExpect(jsonPath("$.permissions.write").value(false))
                // No list flag: a caller that sees the table at all would only ever see it true.
                .andExpect(jsonPath("$.permissions.list").doesNotExist());
    }

    /** A {@code get} grant is what shows one table in the listing; there is no narrower per-table key. */
    @Test
    void getGrantListsTheTable() throws Exception {
        putAccess("{\"get\":[\"" + WRITER_ROLE + "\"]}");

        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(writerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name == '" + PROBE + "')]").exists());
    }

    /**
     * {@code TABLE_LIST} has no per-table key: held globally it enumerates every table, and on one table it
     * is conferred by any grant that already shows it. A body still carrying {@code list} is refused like any
     * unknown member rather than silently dropped, so a client cannot believe it granted something.
     */
    @Test
    void listKeyIsRefused() throws Exception {
        mockMvc.perform(put(BASE + "/" + PROBE + "/access")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"list\":[\"" + WRITER_ROLE + "\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void modifyRoleAllowsTtlPatch() throws Exception {
        putAccess("{\"write\":[],\"modify\":[\"" + WRITER_ROLE + "\"]}");

        // One PATCH by the grantee: add a timestamp column and point the table TTL at it — both ride
        // the same single ALTER, authorized by the modify access role.
        mockMvc.perform(patch(BASE + "/" + PROBE + "/schema")
                        .header(HttpHeaders.AUTHORIZATION, bearer(writerToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"add\":[{\"source_name\":\"seen_at\",\"name\":\"seen_at\",\"type\":\"timestamp\"}],"
                                + "\"ttl\":{\"column\":\"seen_at\",\"interval\":30,\"unit\":\"day\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttl.column").value("seen_at"))
                .andExpect(jsonPath("$.ttl.interval").value(30))
                .andExpect(jsonPath("$.ttl.unit").value("day"));
    }

    @Test
    void deleteStaysAdminOnlyEvenWithModifyAccess() throws Exception {
        putAccess("{\"write\":[],\"modify\":[\"" + WRITER_ROLE + "\"]}");

        mockMvc.perform(delete(BASE + "/" + PROBE).header(HttpHeaders.AUTHORIZATION, bearer(writerToken())))
                .andExpect(status().isForbidden());
    }

    // --- caller-effective permissions reporting ---------------------------------------------------

    @Test
    void matchingWriteRoleIsReportedOnGetAndList() throws Exception {
        putAccess("{\"write\":[\"" + WRITER_ROLE + "\"],\"modify\":[]}");

        mockMvc.perform(get(BASE + "/" + PROBE).header(HttpHeaders.AUTHORIZATION, bearer(readingWriterToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.write").value(true))
                .andExpect(jsonPath("$.permissions.modify").value(false));

        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(readingWriterToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name=='" + PROBE + "')].permissions.write").value(true))
                .andExpect(jsonPath("$.tables[?(@.name=='" + PROBE + "')].permissions.modify").value(false));
    }

    @Test
    void unlistedCallerIsReportedNoPermissions() throws Exception {
        putAccess("{\"write\":[],\"modify\":[]}");

        mockMvc.perform(get(BASE + "/" + PROBE).header(HttpHeaders.AUTHORIZATION, bearer(readOnlyToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.write").value(false))
                .andExpect(jsonPath("$.permissions.modify").value(false));
    }

    @Test
    void adminIsReportedFullPermissionsExceptOnSystemTables() throws Exception {
        mockMvc.perform(get(BASE + "/" + PROBE).header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.write").value(true))
                .andExpect(jsonPath("$.permissions.modify").value(true));

        // A system table refuses every modifying operation, so even FULL_ADMIN sees no permissions.
        mockMvc.perform(get(BASE + "/dial_usage_log").header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.write").value(false))
                .andExpect(jsonPath("$.permissions.modify").value(false));
    }
}
