package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.JwtTestTokens;
import com.epam.aidial.analytics.web.security.TestSecurityConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scope-based authorization of {@code /v1/saved-queries} in {@code oidc} mode.
 *
 * <p>Two JWT providers are configured — one granting {@code FULL_ADMIN}, one {@code READ_ONLY_ADMIN} —
 * whose tokens are minted locally and verified against an HMAC secret via {@link TestSecurityConfig},
 * so no live JWKS or IdP is needed. Two distinct {@code READ_ONLY_ADMIN} principals exercise the owner
 * filter, which needs two people holding the same role.
 *
 * <p>ClickHouse is never contacted: saved queries are validated by translation, which renders SQL and
 * discards it. Only PostgreSQL is required.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "clickhouse.migrations.enabled=false",
        "sync.validate-schema=false",
        "poll.enabled=false",
        "security.mode=oidc",
        "security.defaults.email-claim=email",
        "security.defaults.principal-claim=sub",
        "providers.full.issuer=" + SavedQueryAuthIntegrationTest.FULL_ISSUER,
        "providers.full.jwk-set-uri=https://issuer.test/full/jwks",
        "providers.full.audiences=" + SavedQueryAuthIntegrationTest.AUDIENCE,
        "providers.full.role-claims=roles",
        "providers.full.roles-mapping={\"admin\":[\"FULL_ADMIN\"]}",
        "providers.readonly.issuer=" + SavedQueryAuthIntegrationTest.READONLY_ISSUER,
        "providers.readonly.jwk-set-uri=https://issuer.test/readonly/jwks",
        "providers.readonly.audiences=" + SavedQueryAuthIntegrationTest.AUDIENCE,
        "providers.readonly.role-claims=roles",
        "providers.readonly.roles-mapping={\"viewer\":[\"READ_ONLY_ADMIN\"]}",
})
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class SavedQueryAuthIntegrationTest {

    static final String AUDIENCE = "adas-test";
    static final String FULL_ISSUER = "https://issuer.test/full";
    static final String READONLY_ISSUER = "https://issuer.test/readonly";

    private static final String BASE = "/v1/saved-queries";

    /** A minimal, translatable personal saved query. */
    private static final String PERSONAL = """
            {"name":"Mine","scope":"personal",
             "query":{"entity":"dial_usage_log","mode":"row",
               "select":[{"expr":{"type":"field","name":"project_id"}}]}}
            """;

    private static final String COMMON = PERSONAL
            .replace("\"scope\":\"personal\"", "\"scope\":\"common\"")
            .replace("\"Mine\"", "\"Shared\"");

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
    }

    // ---------------------------------------------------------------- personal scope

    @Test
    void recordsTheAuthenticatedPrincipalAsOwner() throws Exception {
        mockMvc.perform(authored(post(BASE), alice()).content(PERSONAL))
                .andExpect(status().isCreated())
                // The principal decides ownership; the email rides along only so a UI can render it.
                .andExpect(jsonPath("$.owner_id").value("alice"))
                .andExpect(jsonPath("$.owner_email").value("alice@test"));
    }

    @Test
    void refusesPersonalSaveWhenThePrincipalDoesNotResolve() throws Exception {
        // The token authenticates and carries a role, but no `sub` — so getAuthor() is null. A personal
        // row stored with a null owner would match no caller on `owner_id = :author`: unreadable,
        // unreplaceable and undeletable by everyone, forever. Refuse rather than write it.
        mockMvc.perform(authored(post(BASE), principalless()).content(PERSONAL))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("principal_unavailable"));

        // And nothing was persisted — the list such a caller sees is empty either way, so check the
        // admin's view of every scope instead.
        mockMvc.perform(authored(get(BASE).param("scope", "common"), admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved_queries[?(@.name=='Mine')]", hasSize(0)));
    }

    @Test
    void allowsCommonSaveWhenThePrincipalDoesNotResolve() throws Exception {
        // Deliberately not guarded: a common row stays readable by everyone whatever its owner, so an
        // unresolved principal costs only the "Saved by" attribution, not reachability. Blocking it
        // would break a deployment that curates shared definitions under principal-less tokens.
        mockMvc.perform(authored(post(BASE), principallessAdmin())
                        .content(COMMON.replace("\"Shared\"", "\"Shared anonymously\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.owner_id").doesNotExist())
                .andExpect(jsonPath("$.scope").value("common"));
    }

    @Test
    void ownerEmailIsTakenFromTheTokenNotTheBody() throws Exception {
        // Otherwise a caller could author a saved query that displays someone else's name as "Saved by".
        final String spoofed = PERSONAL.replace(
                "\"name\":\"Mine\"", "\"name\":\"Spoofed\",\"owner_email\":\"admin@test\"");
        mockMvc.perform(authored(post(BASE), alice()).content(spoofed))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("owner_email")));
    }

    @Test
    void ownerEmailSurvivesReplaceByAnotherAdmin() throws Exception {
        // owner_id is preserved across a replace, so the email beside it must be too — an admin editing
        // a common saved query must not become its displayed author.
        final String id = create(COMMON, admin());

        mockMvc.perform(authored(put(BASE + "/" + id), otherAdmin())
                        .content(COMMON.replace("\"Shared\"", "\"Shared, edited\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner_id").value("admin-user"))
                .andExpect(jsonPath("$.owner_email").value("admin@test"))
                .andExpect(jsonPath("$.generation").value(2));
    }

    @Test
    void personalRowsOfOthersAreAbsentFromTheList() throws Exception {
        create(PERSONAL.replace("\"Mine\"", "\"Alice only\""), alice());

        mockMvc.perform(authored(get(BASE), bob()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved_queries[?(@.name=='Alice only')]", hasSize(0)));

        mockMvc.perform(authored(get(BASE), alice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved_queries[?(@.name=='Alice only')]", hasSize(1)));
    }

    @Test
    void anotherCallersPersonalRowIsNotFoundNotForbidden() throws Exception {
        // 404, never 403: a 403 would confirm that someone else has a saved query under this id.
        final String id = create(PERSONAL, alice());

        mockMvc.perform(authored(get(BASE + "/" + id), bob()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));

        mockMvc.perform(authored(put(BASE + "/" + id), bob()).content(PERSONAL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));

        mockMvc.perform(authored(delete(BASE + "/" + id), bob()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));

        // And it really was not deleted.
        mockMvc.perform(authored(get(BASE + "/" + id), alice())).andExpect(status().isOk());
    }

    @Test
    void nonAdminCanFullyManageItsOwnPersonalRows() throws Exception {
        final String id = create(PERSONAL, alice());
        mockMvc.perform(authored(put(BASE + "/" + id), alice())
                        .content(PERSONAL.replace("\"Mine\"", "\"Mine, renamed\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mine, renamed"))
                .andExpect(jsonPath("$.generation").value(2));
        mockMvc.perform(authored(delete(BASE + "/" + id), alice())).andExpect(status().isNoContent());
    }

    // ---------------------------------------------------------------- common scope

    @Test
    void anyApplicationRoleCanReadCommon() throws Exception {
        // A name unique to this test: the suite shares one database, so asserting on a name other tests
        // also create would couple this assertion to execution order.
        final String id = create(COMMON.replace("\"Shared\"", "\"Readable by all\""), admin());

        mockMvc.perform(authored(get(BASE + "/" + id), bob()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("common"))
                // owner_id is the author in both scopes — what the UI shows as "Saved by".
                .andExpect(jsonPath("$.owner_id").value("admin-user"));

        mockMvc.perform(authored(get(BASE).param("scope", "common"), bob()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved_queries[?(@.name=='Readable by all')]", hasSize(1)));
    }

    @Test
    void writingCommonRequiresFullAdmin() throws Exception {
        mockMvc.perform(authored(post(BASE), bob()).content(COMMON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        final String id = create(COMMON, admin());

        mockMvc.perform(authored(put(BASE + "/" + id), bob()).content(COMMON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        mockMvc.perform(authored(delete(BASE + "/" + id), bob()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void nonAdminCannotPromoteItsOwnRowToCommon() throws Exception {
        final String id = create(PERSONAL, alice());

        mockMvc.perform(authored(put(BASE + "/" + id), alice()).content(COMMON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        mockMvc.perform(authored(get(BASE + "/" + id), alice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("personal"));
    }

    @Test
    void nonAdminCannotDemoteCommonRow() throws Exception {
        // Checking only the requested scope would let a non-admin move a shared definition into their own
        // personal scope, removing it from everyone else.
        final String id = create(COMMON, admin());

        mockMvc.perform(authored(put(BASE + "/" + id), bob()).content(PERSONAL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        mockMvc.perform(authored(get(BASE + "/" + id), bob()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("common"));
    }

    @Test
    void anAdminCanManageCommon() throws Exception {
        final String id = create(COMMON, admin());
        mockMvc.perform(authored(put(BASE + "/" + id), admin())
                        .content(COMMON.replace("\"Shared\"", "\"Shared, renamed\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Shared, renamed"));
        mockMvc.perform(authored(delete(BASE + "/" + id), admin())).andExpect(status().isNoContent());
    }

    // ---------------------------------------------------------------- sensitive literals

    @Test
    void anAdminCanSaveLiteralBoundToSensitiveColumn() throws Exception {
        final String body = """
                {"name":"Sensitive filter","scope":"personal",
                 "query":{"entity":"dial_usage_log","mode":"row",
                   "filter":{"op":"eq","args":[
                     {"type":"field","name":"dial_usage_log_payload.request_body"},
                     {"type":"value","value_type":"string","value":"a secret"}]}}}
                """;
        final String id = create(body, admin());

        // Stored verbatim: the literal survives the round trip, unredacted.
        mockMvc.perform(authored(get(BASE + "/" + id), admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query.filter.args[0].name").value("dial_usage_log_payload.request_body"))
                .andExpect(jsonPath("$.query.filter.args[1].value").value("a secret"));
    }

    @Test
    void theSameAcceptanceAppliesToSqlBody() throws Exception {
        final String body = """
                {"name":"Sensitive sql","scope":"personal",
                 "sql":"SELECT project_id FROM dial_usage_log WHERE \\"dial_usage_log_payload.request_body\\" = 'a secret'"}
                """;
        final String id = create(body, admin());

        mockMvc.perform(authored(get(BASE + "/" + id), admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sql").value(org.hamcrest.Matchers.containsString("'a secret'")));
    }

    @Test
    void acceptsSensitiveLiteralReachedThroughSelectAlias() throws Exception {
        // `having` is translated against the select outputs, so this names the sensitive column only
        // through its alias — once the write path applies no sensitivity rule, that is immaterial.
        final String body = """
                {"name":"Sensitive via alias","scope":"personal",
                 "query":{"entity":"dial_usage_log","mode":"aggregate",
                   "group_by":["project_id"],
                   "select":[
                     {"expr":{"type":"field","name":"project_id"}},
                     {"expr":{"type":"fn","name":"max","args":[
                        {"type":"field","name":"dial_usage_log_payload.request_body"}]},"as":"m"}],
                   "having":{"op":"eq","args":[
                     {"type":"field","name":"m"},
                     {"type":"value","value_type":"string","value":"a secret"}]}}}
                """;
        mockMvc.perform(authored(post(BASE), admin()).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(authored(get(BASE), admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved_queries[?(@.name=='Sensitive via alias')]", hasSize(1)));
    }

    @Test
    void replaceMayIntroduceSensitiveLiteral() throws Exception {
        // The refusal used to apply on replace as well as create; both paths are now unrestricted.
        final String id = create(PERSONAL, admin());
        final String replacement = """
                {"name":"Now sensitive","scope":"personal",
                 "query":{"entity":"dial_usage_log","mode":"row",
                   "filter":{"op":"eq","args":[
                     {"type":"field","name":"dial_usage_log_payload.request_body"},
                     {"type":"value","value_type":"string","value":"a secret"}]}}}
                """;
        mockMvc.perform(authored(put(BASE + "/" + id), admin()).content(replacement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query.filter.args[1].value").value("a secret"));
    }

    @Test
    void commonSensitiveLiteralIsReadableByAnyApplicationRole() throws Exception {
        // The read-side consequence of the relaxation, asserted rather than left implicit: `common` rows
        // are readable by every application role and bodies are returned unredacted, so the literal an
        // entitled admin published reaches a READ_ONLY_ADMIN who is refused `request_body` itself on the
        // query surfaces. Deliberate — only FULL_ADMIN can write `common`, and that is the role entitled to
        // sensitive columns — and load-bearing: if a sensitivity check is ever added to the read path, this
        // test must be revisited together with the spec, not quietly relaxed.
        final String body = """
                {"name":"Shared sensitive filter","scope":"common",
                 "query":{"entity":"dial_usage_log","mode":"row",
                   "filter":{"op":"eq","args":[
                     {"type":"field","name":"dial_usage_log_payload.request_body"},
                     {"type":"value","value_type":"string","value":"a secret"}]}}}
                """;
        final String id = create(body, admin());

        // The non-entitled caller cannot reference the column in a body of its own...
        mockMvc.perform(authored(post(BASE), bob())
                        .content(body.replace("\"scope\":\"common\"", "\"scope\":\"personal\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        // ...yet reads the admin's stored one, literal included.
        mockMvc.perform(authored(get(BASE + "/" + id), bob()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query.filter.args[0].name").value("dial_usage_log_payload.request_body"))
                .andExpect(jsonPath("$.query.filter.args[1].value").value("a secret"));

        mockMvc.perform(authored(get(BASE).param("scope", "common"), bob()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved_queries[?(@.name=='Shared sensitive filter')]", hasSize(1)));

        mockMvc.perform(authored(delete(BASE + "/" + id), admin())).andExpect(status().isNoContent());
    }

    @Test
    void nonAdminGetsOrdinaryUnknownFieldErrorInstead() throws Exception {
        // Read entitlement is untouched by the write-path relaxation: the column is absent from a
        // non-entitled caller's model, so the reference fails at resolution — and nothing in the
        // response confirms that the column exists.
        final String body = """
                {"name":"Probe","scope":"personal",
                 "query":{"entity":"dial_usage_log","mode":"row",
                   "filter":{"op":"eq","args":[
                     {"type":"field","name":"dial_usage_log_payload.request_body"},
                     {"type":"value","value_type":"string","value":"a secret"}]}}}
                """;
        mockMvc.perform(authored(post(BASE), bob()).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void anAdminCanSaveSensitiveColumnComparedToAnotherColumn() throws Exception {
        final String body = """
                {"name":"Column compare","scope":"personal",
                 "query":{"entity":"dial_usage_log","mode":"row",
                   "filter":{"op":"eq","args":[
                     {"type":"field","name":"dial_usage_log_payload.request_body"},
                     {"type":"field","name":"dial_usage_log_payload.response_body"}]}}}
                """;
        mockMvc.perform(authored(post(BASE), admin()).content(body))
                .andExpect(status().isCreated());
    }

    // ---------------------------------------------------------------- helpers

    private String create(String body, String token) throws Exception {
        final String response = mockMvc.perform(authored(post(BASE), token).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private static MockHttpServletRequestBuilder authored(MockHttpServletRequestBuilder builder, String token) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType("application/json");
    }

    private static String admin() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "admin-user", "roles", List.of("admin"), "email", "admin@test"));
    }

    /** A second {@code FULL_ADMIN}, so "edited by a different admin" is a real scenario. */
    private static String otherAdmin() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "admin-user-2", "roles", List.of("admin"), "email", "admin2@test"));
    }

    /**
     * A valid token carrying no {@code sub} — the configured principal claim yields nothing, so
     * {@code getAuthor()} is null even though the caller is authenticated and holds a role. This is what
     * a principal-claim misconfiguration looks like from the inside.
     */
    private static String principalless() {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE,
                Map.of("roles", List.of("viewer"), "email", "nobody@test"));
    }

    /** The same, holding {@code FULL_ADMIN} so it can attempt a {@code common} write. */
    private static String principallessAdmin() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("roles", List.of("admin"), "email", "nobody@test"));
    }

    /** A {@code READ_ONLY_ADMIN}. */
    private static String alice() {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE,
                Map.of("sub", "alice", "roles", List.of("viewer"), "email", "alice@test"));
    }

    /** A second {@code READ_ONLY_ADMIN}, so the owner filter has two people holding the same role. */
    private static String bob() {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE,
                Map.of("sub", "bob", "roles", List.of("viewer"), "email", "bob@test"));
    }
}
