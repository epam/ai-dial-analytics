package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.JwtTestTokens;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.nullValue;
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

    @Autowired
    private JdbcTemplate jdbc;

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

    /** The table's current access generation, read as FULL_ADMIN. */
    private long generation(String table) throws Exception {
        final String body = mockMvc.perform(get(BASE + "/" + table + "/access").header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.generation")).longValue();
    }

    /** PUTs {@code role}'s entry on {@code table} as {@code token}: {@code members} plus the table's current generation. */
    private ResultActions putEntry(String token, String table, String role, String members) throws Exception {
        return putAs(token, BASE + "/" + table + "/access?role=" + role,
                "{\"generation\":" + generation(table) + (members.isEmpty() ? "" : "," + members) + "}");
    }

    /** Replaces the writer role's entry on the probe with {@code privileges}. */
    private void grant(String... privileges) throws Exception {
        putEntry(fullAdminToken(), PROBE, WRITER_ROLE, privileges(privileges)).andExpect(status().isOk());
    }

    private static String privileges(String... privileges) {
        return privileges.length == 0 ? "\"privileges\":[]" : "\"privileges\":[\"" + String.join("\",\"", privileges) + "\"]";
    }

    // --- grant management (TABLE_GRANT on both the read and the write) ---------------------------

    @Test
    void grantAdminReadsAndReplacesAnEntry() throws Exception {
        final long before = generation(PROBE);
        mockMvc.perform(get(BASE + "/" + PROBE + "/access").header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").isMap())
                .andExpect(jsonPath("$.roles").isEmpty())
                .andExpect(jsonPath("$.read").doesNotExist());

        // The PUT answers with the entry as stored (a privilege named twice is stored once) and the new
        // generation; the caller's own permissions are on the table read, not here.
        putEntry(fullAdminToken(), PROBE, WRITER_ROLE, privileges("write", "write"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(before + 1))
                .andExpect(jsonPath("$.role").value(WRITER_ROLE))
                .andExpect(jsonPath("$.privileges.length()").value(1))
                .andExpect(jsonPath("$.privileges[0]").value("write"))
                .andExpect(jsonPath("$.rows").value(nullValue()))
                .andExpect(jsonPath("$.columns").value(nullValue()))
                .andExpect(jsonPath("$.permissions").doesNotExist());

        mockMvc.perform(get(BASE + "/" + PROBE + "/access?role=" + WRITER_ROLE).header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(before + 1))
                .andExpect(jsonPath("$.role").value(WRITER_ROLE))
                .andExpect(jsonPath("$.privileges[0]").value("write"));
    }

    @Test
    void putForOneRoleLeavesOtherEntriesAndEmptyEntryRemovesTheRole() throws Exception {
        grant("read");

        // Another role's PUT does not touch the writer role's entry.
        putEntry(fullAdminToken(), PROBE, "other-role", privileges("write")).andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + PROBE + "/access").header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles['" + WRITER_ROLE + "'].privileges[0]").value("read"))
                .andExpect(jsonPath("$.roles['other-role'].privileges[0]").value("write"));

        // An entry left with nothing is removed, and DELETE removes a role's entry outright.
        grant();
        mockMvc.perform(get(BASE + "/" + PROBE + "/access?role=" + WRITER_ROLE).header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mockMvc.perform(delete(BASE + "/" + PROBE + "/access?role=other-role").header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete(BASE + "/" + PROBE + "/access?role=other-role").header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/" + PROBE + "/access").header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                .andExpect(jsonPath("$.roles").isEmpty());
    }

    @Test
    void readOnlyAdminCanNeitherReadNorReplaceGrants() throws Exception {
        grant("write");

        // The read moved from "any application role" to TABLE_GRANT: the entries disclose which provider
        // roles exist and what each may do, which is organisational structure.
        mockMvc.perform(get(BASE + "/" + PROBE + "/access").header(HttpHeaders.AUTHORIZATION, bearer(readOnlyToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(get(BASE + "/" + PROBE + "/access?role=" + WRITER_ROLE).header(HttpHeaders.AUTHORIZATION, bearer(readOnlyToken())))
                .andExpect(status().isForbidden());
        putEntry(readOnlyToken(), PROBE, WRITER_ROLE, privileges("write"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(delete(BASE + "/" + PROBE + "/access?role=" + WRITER_ROLE).header(HttpHeaders.AUTHORIZATION, bearer(readOnlyToken())))
                .andExpect(status().isForbidden());
        // A body that does not bind is refused 400 before the TABLE_GRANT check answers 403.
        putEntry(readOnlyToken(), PROBE, WRITER_ROLE, "\"privileges\":[1]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
        putEntry(readOnlyToken(), PROBE, WRITER_ROLE, "\"list\":[\"x\"]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void rolelessCallerCannotReadAccess() throws Exception {
        // Authorized per table for writes, but carries no application role — catalog reads stay closed.
        grant("write");

        mockMvc.perform(get(BASE + "/" + PROBE + "/access").header(HttpHeaders.AUTHORIZATION, bearer(writerToken())))
                .andExpect(status().isForbidden());
    }

    @Test
    void systemTableRefusesWriteGrantsAndAcceptsReadGrants() throws Exception {
        // The refusal moved from the object to the privileges. A system table is fed out of band, so every
        // modifying operation on it is refused whatever a grant says — but that is a reason to keep
        // `write`/`modify` off its entries, not a reason to refuse a read grant, and refusing every PUT made
        // per-table read grants inapplicable to dial_usage_log, the primary analytics table.
        putEntry(fullAdminToken(), "dial_usage_log", WRITER_ROLE, privileges("read", "write"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("table_is_system"));

        try {
            // A read-side privilege on the same table is stored.
            putEntry(fullAdminToken(), "dial_usage_log", WRITER_ROLE, privileges("read"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.privileges.length()").value(1))
                    .andExpect(jsonPath("$.privileges[0]").value("read"));

            // And the reported permissions tell the truth about it: the read-side flags follow the grant
            // while write/modify stay hard false for every caller.
            mockMvc.perform(get(BASE + "/dial_usage_log")
                            .header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissions.read").value(true))
                    .andExpect(jsonPath("$.permissions.write").value(false))
                    .andExpect(jsonPath("$.permissions.modify").value(false));
        } finally {
            mockMvc.perform(delete(BASE + "/dial_usage_log/access?role=" + WRITER_ROLE)
                            .header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                    .andExpect(status().isNoContent());
        }
    }

    @Test
    void accessOnUnknownTableIs404() throws Exception {
        mockMvc.perform(get(BASE + "/does_not_exist/access").header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mockMvc.perform(get(BASE + "/does_not_exist/access?role=" + WRITER_ROLE).header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    // --- per-table authorization by role ---------------------------------------------------------

    @Test
    void writeRoleAllowsRowsButNotModify() throws Exception {
        grant("write");

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
        grant("modify");

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
        grant("get");
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
        grant("write");

        mockMvc.perform(post("/v1/queries/translate-sql")
                        .header(HttpHeaders.AUTHORIZATION, bearer(writerToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT k FROM " + PROBE + " LIMIT 1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void writeOnlyCallerCannotReadOrList() throws Exception {
        grant("write");

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
        grant("read");

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
        grant("get");

        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(writerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name == '" + PROBE + "')]").exists());
    }

    /**
     * {@code TABLE_LIST} has no per-table key: held globally it enumerates every table, and on one table it
     * is conferred by any grant that already shows it. An entry naming {@code list} is refused like any
     * unknown privilege rather than silently dropped, so a client cannot believe it granted something.
     */
    @Test
    void listKeyIsRefused() throws Exception {
        putEntry(fullAdminToken(), PROBE, WRITER_ROLE, privileges("list"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void modifyRoleAllowsTtlPatch() throws Exception {
        grant("modify");

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
        grant("modify");

        mockMvc.perform(delete(BASE + "/" + PROBE).header(HttpHeaders.AUTHORIZATION, bearer(writerToken())))
                .andExpect(status().isForbidden());
    }

    // --- an enrichment's read side is its source's -------------------------------------------------

    private static final String ENRICHMENT = "access_probe_labels";

    /** An ACTIVE enrichment of the probe, with one plain and one sensitive column. */
    private void createEnrichment() throws Exception {
        mockMvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + ENRICHMENT + "\",\"type\":\"enrichment\",\"source_table\":\"" + PROBE + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post(BASE + "/" + ENRICHMENT + "/schema").header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grain_key\":\"k\",\"columns\":[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"},"
                                + "{\"source_name\":\"secret\",\"name\":\"secret\",\"type\":\"string\",\"sensitive\":true}]}"))
                .andExpect(status().isOk());
    }

    private void dropEnrichment() throws Exception {
        mockMvc.perform(delete(BASE + "/" + ENRICHMENT).header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())));
    }

    private ResultActions putAs(String token, String path, String body) throws Exception {
        return mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void readSideGrantsAndRowPoliciesOnAnEnrichmentAreRefused() throws Exception {
        createEnrichment();
        try {
            // Judged on the body as sent and before the generation, so none of these bodies carries one.
            final String access = BASE + "/" + ENRICHMENT + "/access?role=";
            for (final String privileges : List.of("[\"get\"]", "[\"read\"]", "[\"sensitive\"]", "[\"read\",\"modify\"]")) {
                putAs(fullAdminToken(), access + "x", "{\"privileges\":" + privileges + "}")
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.error").value("enrichment_follows_source"))
                        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(PROBE)));
            }
            putAs(fullAdminToken(), BASE + "/dial_usage_log_payload/access?role=x", "{\"privileges\":[\"read\",\"write\"]}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("enrichment_follows_source"));
            putEntry(fullAdminToken(), ENRICHMENT, "labeler", privileges("write"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.privileges.length()").value(1))
                    .andExpect(jsonPath("$.privileges[0]").value("write"));

            final String filter = "{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"label\"},"
                    + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"x\"}]}";
            for (final String[] put : List.of(
                    new String[] {"team-x", "{\"rows\":{\"filter\":" + filter + "}}"},
                    new String[] {"team-x", "{\"rows\":{\"where\":\"no_such_column = 'x'\"}}"},
                    new String[] {"READ_ONLY_ANALYTIC", "{\"rows\":{\"filter\":" + filter + "}}"},
                    new String[] {"team-x", "{\"rows\":{\"filter\":" + filter + ",\"where\":\"label = 'x'\"}}"},
                    new String[] {"team-x", "{\"privileges\":[\"write\"],\"columns\":[\"label\"]}"})) {
                putAs(fullAdminToken(), access + put[0], put[1])
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.error").value("enrichment_follows_source"));
            }
            mockMvc.perform(delete(access + "team-x").header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                    .andExpect(status().isNotFound());
            putAs(fullAdminToken(), BASE + "/no_such_table/access?role=team-x", "{\"rows\":{\"filter\":" + filter + ",\"where\":\"a = 1\"}}")
                    .andExpect(status().isNotFound());
        } finally {
            dropEnrichment();
        }
    }

    @Test
    void sourceReaderQueriesAnEnrichmentItHoldsNothingOn() throws Exception {
        createEnrichment();
        try {
            grant("read");

            mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(writerToken())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tables[?(@.name == '" + ENRICHMENT + "')].permissions.read").value(true))
                    .andExpect(jsonPath("$.tables[?(@.name == '" + ENRICHMENT + "')].permissions.sensitive").value(false));
            mockMvc.perform(post("/v1/queries/execute-sql").header(HttpHeaders.AUTHORIZATION, bearer(writerToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sql\":\"SELECT k, \\\"" + ENRICHMENT + ".label\\\" FROM " + PROBE + " LIMIT 1\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/v1/queries/execute").header(HttpHeaders.AUTHORIZATION, bearer(writerToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"entity\":\"" + PROBE + "\",\"mode\":\"row\",\"select\":[{\"expr\":{\"type\":\"field\","
                                    + "\"name\":\"" + ENRICHMENT + ".label\"}}],\"page\":{\"type\":\"offset\",\"limit\":1}}"))
                    .andExpect(status().isOk());
            // The sensitive column follows the source's clearance, which this caller lacks.
            mockMvc.perform(post("/v1/queries/execute-sql").header(HttpHeaders.AUTHORIZATION, bearer(writerToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sql\":\"SELECT \\\"" + ENRICHMENT + ".secret\\\" FROM " + PROBE + " LIMIT 1\"}"))
                    .andExpect(status().isBadRequest());

            grant("read", "sensitive");
            mockMvc.perform(post("/v1/queries/execute-sql").header(HttpHeaders.AUTHORIZATION, bearer(writerToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sql\":\"SELECT \\\"" + ENRICHMENT + ".secret\\\" FROM " + PROBE + " LIMIT 1\"}"))
                    .andExpect(status().isOk());
        } finally {
            dropEnrichment();
        }
    }

    @Test
    void enrichmentMaintainerGetsTheDefinitionButNotTheSource() throws Exception {
        createEnrichment();
        try {
            putEntry(fullAdminToken(), ENRICHMENT, WRITER_ROLE, privileges("modify"))
                    .andExpect(status().isOk());
            // A source clearance for the maintainer extends its modify to the sensitive columns: not ineffective.
            putEntry(fullAdminToken(), PROBE, WRITER_ROLE, privileges("sensitive"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ineffective_grants").isEmpty());

            mockMvc.perform(get(BASE + "/" + ENRICHMENT).header(HttpHeaders.AUTHORIZATION, bearer(writerToken())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissions.get").value(true))
                    .andExpect(jsonPath("$.permissions.read").value(false));
            mockMvc.perform(get(BASE + "/" + PROBE).header(HttpHeaders.AUTHORIZATION, bearer(writerToken())))
                    .andExpect(status().isNotFound());
        } finally {
            dropEnrichment();
        }
    }

    // --- caller-effective permissions reporting ---------------------------------------------------

    @Test
    void matchingWriteRoleIsReportedOnGetAndList() throws Exception {
        grant("write");

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
        grant("read");

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

    @Test
    void grantFlagFollowsTableGrantOnGetListAndCreate() throws Exception {
        for (final String table : List.of(PROBE, "dial_usage_log")) {
            mockMvc.perform(get(BASE + "/" + table).header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissions.grant").value(true));
            mockMvc.perform(get(BASE + "/" + table).header(HttpHeaders.AUTHORIZATION, bearer(readOnlyToken())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissions.read").value(true))
                    .andExpect(jsonPath("$.permissions.grant").value(false));
        }
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name=='" + PROBE + "')].permissions.grant").value(true));
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(readOnlyToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name=='" + PROBE + "')].permissions.grant").value(false));

        final String created = "access_probe_created";
        try {
            mockMvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + created + "\",\"type\":\"source\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.permissions.grant").value(true));
            mockMvc.perform(delete(BASE + "/" + created).header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                    .andExpect(status().isNoContent());
            // `importer` holds TABLE_CREATE alone.
            mockMvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, bearer(JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                                    Map.of("sub", "importer-user", "roles", List.of("importer"), "email", "importer@test"))))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + created + "\",\"type\":\"source\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.permissions.grant").value(false));
        } finally {
            mockMvc.perform(delete(BASE + "/" + created).header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())));
        }
    }

    // --- ineffective_grants on the reads ------------------------------------------------------------

    private ResultActions readAccess(String table, String role) throws Exception {
        return mockMvc.perform(get(BASE + "/" + table + "/access" + (role == null ? "" : "?role=" + role))
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                .andExpect(status().isOk());
    }

    @Test
    void readForEveryoneOverRowPolicyIsReportedOnBothReadsAndFollowsOtherRoles() throws Exception {
        putEntry(fullAdminToken(), PROBE, "*", privileges("read")).andExpect(status().isOk());
        readAccess(PROBE, null)
                .andExpect(jsonPath("$.ineffective_grants.length()").value(1))
                .andExpect(jsonPath("$.ineffective_grants[0].reason").value("everyone_reads_all_rows"));

        // Another role's policy makes lifts_row_policies true on the `*` entry saved before it.
        putEntry(fullAdminToken(), PROBE, "X", "\"rows\":{\"where\":\"k = 'v'\"}").andExpect(status().isOk());
        readAccess(PROBE, null)
                .andExpect(jsonPath("$.ineffective_grants.length()").value(2))
                .andExpect(jsonPath("$.ineffective_grants[0].role").value("*"))
                .andExpect(jsonPath("$.ineffective_grants[0].key").value("read"))
                .andExpect(jsonPath("$.ineffective_grants[0].reason").value("lifts_row_policies"))
                .andExpect(jsonPath("$.ineffective_grants[0].message").isString())
                .andExpect(jsonPath("$.ineffective_grants[1].role").value("*"))
                .andExpect(jsonPath("$.ineffective_grants[1].reason").value("everyone_reads_all_rows"));
        readAccess(PROBE, "*")
                .andExpect(jsonPath("$.ineffective_grants.length()").value(2))
                .andExpect(jsonPath("$.ineffective_grants[0].reason").value("lifts_row_policies"))
                .andExpect(jsonPath("$.ineffective_grants[1].reason").value("everyone_reads_all_rows"));
        readAccess(PROBE, "X")
                .andExpect(jsonPath("$.ineffective_grants").isArray())
                .andExpect(jsonPath("$.ineffective_grants").isEmpty());

        mockMvc.perform(delete(BASE + "/" + PROBE + "/access?role=X").header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                .andExpect(status().isNoContent());
        readAccess(PROBE, null)
                .andExpect(jsonPath("$.ineffective_grants.length()").value(1))
                .andExpect(jsonPath("$.ineffective_grants[0].reason").value("everyone_reads_all_rows"));
    }

    @Test
    void everyoneReadsAllRowsIsReportedOnSaveAndReadUntilRowsAreGiven() throws Exception {
        putEntry(fullAdminToken(), PROBE, "*", privileges("read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ineffective_grants.length()").value(1))
                .andExpect(jsonPath("$.ineffective_grants[0].role").value("*"))
                .andExpect(jsonPath("$.ineffective_grants[0].key").value("read"))
                .andExpect(jsonPath("$.ineffective_grants[0].reason").value("everyone_reads_all_rows"));
        readAccess(PROBE, null).andExpect(jsonPath("$.ineffective_grants[0].reason").value("everyone_reads_all_rows"));

        putEntry(fullAdminToken(), PROBE, "*", privileges("read") + ",\"rows\":{\"where\":\"k = 'v'\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ineffective_grants").isEmpty());
        readAccess(PROBE, null).andExpect(jsonPath("$.ineffective_grants").isEmpty());

        // A column list does not narrow rows.
        putEntry(fullAdminToken(), PROBE, "*", privileges("read") + ",\"columns\":[\"k\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ineffective_grants[0].reason").value("everyone_reads_all_rows"));
        readAccess(PROBE, "*").andExpect(jsonPath("$.ineffective_grants[0].reason").value("everyone_reads_all_rows"));

        putEntry(fullAdminToken(), PROBE, "*", privileges("get")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ineffective_grants").isEmpty());
        putEntry(fullAdminToken(), PROBE, "*", privileges() + ",\"rows\":{\"where\":\"k = 'v'\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.ineffective_grants").isEmpty());
        readAccess(PROBE, null).andExpect(jsonPath("$.ineffective_grants").isEmpty());
    }

    @Test
    void clearanceWarningOnReadDisappearsWhenTheRoleGainsVerb() throws Exception {
        createEnrichment();
        try {
            putEntry(fullAdminToken(), PROBE, "pii-analysts", privileges("sensitive")).andExpect(status().isOk());
            readAccess(PROBE, "pii-analysts")
                    .andExpect(jsonPath("$.ineffective_grants.length()").value(1))
                    .andExpect(jsonPath("$.ineffective_grants[0].reason").value("sensitive_without_verb"));

            putEntry(fullAdminToken(), PROBE, "*", privileges("get")).andExpect(status().isOk());
            readAccess(PROBE, "pii-analysts").andExpect(jsonPath("$.ineffective_grants").isEmpty());

            mockMvc.perform(delete(BASE + "/" + PROBE + "/access?role=*").header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                    .andExpect(status().isNoContent());
            readAccess(PROBE, "pii-analysts").andExpect(jsonPath("$.ineffective_grants[0].reason").value("sensitive_without_verb"));
            putEntry(fullAdminToken(), ENRICHMENT, "pii-analysts", privileges("modify")).andExpect(status().isOk());
            readAccess(PROBE, "pii-analysts").andExpect(jsonPath("$.ineffective_grants").isEmpty());
        } finally {
            dropEnrichment();
        }
    }

    @Test
    void readForEveryoneLeftOnAnEnrichmentIsNotReported() throws Exception {
        createEnrichment();
        try {
            // Raw SQL: the API refuses a read-side entry on an enrichment, and the clean-up migration has already run.
            jdbc.update("UPDATE table_definition SET access = ?::jsonb WHERE name = ?", "{\"read\":[\"*\"]}", ENRICHMENT);
            readAccess(ENRICHMENT, null)
                    .andExpect(jsonPath("$.roles['*'].privileges[0]").value("read"))
                    .andExpect(jsonPath("$.ineffective_grants").isEmpty());
            readAccess(ENRICHMENT, "*").andExpect(jsonPath("$.ineffective_grants").isEmpty());
        } finally {
            dropEnrichment();
        }
    }

    @Test
    void readsWithNothingToReportCarryAnEmptyReport() throws Exception {
        readAccess(PROBE, null)
                .andExpect(jsonPath("$.ineffective_grants").isArray())
                .andExpect(jsonPath("$.ineffective_grants").isEmpty());
        putEntry(fullAdminToken(), PROBE, WRITER_ROLE, privileges("read") + ",\"rows\":{\"where\":\"k = 'v'\"}").andExpect(status().isOk());
        readAccess(PROBE, null).andExpect(jsonPath("$.ineffective_grants").isEmpty());
        readAccess(PROBE, WRITER_ROLE).andExpect(jsonPath("$.ineffective_grants").isEmpty());
    }
}
