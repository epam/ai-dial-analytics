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
 * Visibility and privilege authorization of {@code /v1/dashboards} in {@code oidc} mode, and the audit
 * feed's narrowing of {@code Dashboard} activities.
 *
 * <p>Every caller's privileges come from a {@code roles-mapping}, never from injected authorities, so the
 * {@code SAVED_QUERY_WRITE -> SAVED_QUERY_READ} and {@code SAVED_QUERY_PUBLISH -> SAVED_QUERY_READ}
 * implications under test are the parser's. One provider maps
 * the bundles; another maps single privileges, each alongside {@code TABLE_READ} only so its inline bodies
 * translate — that privilege implies nothing about dashboards.
 *
 * <p>Only PostgreSQL is required; inline bodies are validated by translation, never executed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "clickhouse.migrations.enabled=false",
        // No ClickHouse here: the startup engine check finds nothing to reach, so the storage gate stays closed.
        "clickhouse.port=1",
        "sync.validate-schema=false",
        "poll.enabled=false",
        "security.mode=oidc",
        "security.defaults.email-claim=email",
        "security.defaults.principal-claim=sub",
        "providers.bundles.issuer=" + DashboardAuthIntegrationTest.BUNDLES_ISSUER,
        "providers.bundles.jwk-set-uri=https://issuer.test/bundles/jwks",
        "providers.bundles.audiences=" + DashboardAuthIntegrationTest.AUDIENCE,
        "providers.bundles.role-claims=roles",
        "providers.bundles.roles-mapping={\"admin\":[\"FULL_ADMIN\"],\"viewer\":[\"READ_ONLY_ADMIN\"],"
                + "\"analyst\":[\"READ_ONLY_ANALYTIC\"]}",
        "providers.single.issuer=" + DashboardAuthIntegrationTest.SINGLE_ISSUER,
        "providers.single.jwk-set-uri=https://issuer.test/single/jwks",
        "providers.single.audiences=" + DashboardAuthIntegrationTest.AUDIENCE,
        "providers.single.role-claims=roles",
        "providers.single.roles-mapping={\"reader\":[\"SAVED_QUERY_READ\",\"TABLE_READ\"],"
                + "\"writer\":[\"SAVED_QUERY_WRITE\",\"TABLE_READ\"],"
                + "\"publisher\":[\"SAVED_QUERY_PUBLISH\",\"TABLE_READ\"],"
                + "\"readpublisher\":[\"SAVED_QUERY_READ\",\"SAVED_QUERY_PUBLISH\",\"TABLE_READ\"],"
                + "\"tableonly\":[\"TABLE_READ\"]}",
})
@AutoConfigureMockMvc
@Import({TestSecurityConfig.class, OpenStorageGate.class})
class DashboardAuthIntegrationTest {

    static final String AUDIENCE = "adas-test";
    static final String BUNDLES_ISSUER = "https://issuer.test/bundles";
    static final String SINGLE_ISSUER = "https://issuer.test/single";

    private static final String BASE = "/v1/dashboards";
    private static final String FEED = "/v1/activities";

    private static final String WIDGET = """
            {"id":"w1","row_limit":10,"layout":{"x":0,"y":0,"w":6,"h":4},
             "inline_query":{"name":"Raw","sql":"SELECT project_id FROM dial_usage_log"}}""";

    /** An inline body naming a sensitive column, which only a cleared caller can resolve. */
    private static final String SENSITIVE_WIDGET = """
            {"id":"w1","row_limit":10,"layout":{"x":0,"y":0,"w":6,"h":4},
             "inline_query":{"name":"Sensitive","sql":"SELECT project_id FROM dial_usage_log WHERE \\"dial_usage_log_payload.request_body\\" = 'a secret'"}}""";

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
    }

    // ---------------------------------------------------------------- visibility

    @Test
    void privateDashboardIsInvisibleToAnotherCaller() throws Exception {
        final String id = create(body("Alice only", "private", WIDGET), alice());

        // A full admin, holding every privilege: 404 on every path, never 403.
        mockMvc.perform(authored(get(BASE + "/" + id), admin())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mockMvc.perform(authored(put(BASE + "/" + id), admin()).content(withGeneration(body("Taken", "private", WIDGET), 0)))
                .andExpect(status().isNotFound());
        mockMvc.perform(authored(delete(BASE + "/" + id), admin())).andExpect(status().isNotFound());
        mockMvc.perform(authored(get(BASE), admin()))
                .andExpect(jsonPath("$.dashboards[?(@.id=='" + id + "')]", hasSize(0)));

        mockMvc.perform(authored(get(BASE + "/" + id), alice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner_id").value("alice"))
                .andExpect(jsonPath("$.owner_email").value("alice@test"));
    }

    @Test
    void publicDashboardIsReadableWithSavedQueryRead() throws Exception {
        final String id = create(body("Readable", "public", WIDGET), admin());

        mockMvc.perform(authored(get(BASE + "/" + id), reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.widgets[0].inline_query.sql").exists())
                .andExpect(jsonPath("$.permissions.write").value(false))
                .andExpect(jsonPath("$.permissions.publish").value(false));
        mockMvc.perform(authored(get(BASE), reader()))
                .andExpect(jsonPath("$.dashboards[?(@.id=='" + id + "')]", hasSize(1)));
    }

    @Test
    void readingWithoutSavedQueryReadIsForbidden() throws Exception {
        mockMvc.perform(authored(get(BASE), tableOnly()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    // ---------------------------------------------------------------- the write implies the read

    @Test
    void writeGrantReadsPublicDashboardsButCannotWriteThem() throws Exception {
        final String id = create(body("For the writer", "public", WIDGET), admin());

        mockMvc.perform(authored(get(BASE), writer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dashboards[?(@.id=='" + id + "')]", hasSize(1)))
                .andExpect(jsonPath("$.dashboards[?(@.id=='" + id + "')].permissions.write").value(false));
        mockMvc.perform(authored(get(BASE + "/" + id), writer())).andExpect(status().isOk());

        mockMvc.perform(authored(delete(BASE + "/" + id), writer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(authored(get(BASE + "/" + id), admin())).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- publishing

    @Test
    void publishOnlyCallerCuratesAndReadsPublicDashboards() throws Exception {
        final String created = mockMvc.perform(authored(post(BASE), publisher()).content(body("Curated", "public", WIDGET)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        final String own = JsonPath.read(created, "$.id");
        final Number generation = JsonPath.read(created, "$.generation");

        mockMvc.perform(authored(put(BASE + "/" + own), publisher())
                        .content(withGeneration(body("Curated, edited", "public", WIDGET), generation.longValue())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Curated, edited"));

        final String others = create(body("Someone else's", "public", WIDGET), admin());
        mockMvc.perform(authored(delete(BASE + "/" + others), publisher())).andExpect(status().isNoContent());

        // SAVED_QUERY_PUBLISH implies SAVED_QUERY_READ, so the publisher reads what it curates.
        mockMvc.perform(authored(get(BASE + "/" + own), publisher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Curated, edited"))
                .andExpect(jsonPath("$.permissions.write").value(true))
                .andExpect(jsonPath("$.permissions.publish").value(false));
        mockMvc.perform(authored(get(BASE), publisher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dashboards[?(@.id=='" + own + "')]", hasSize(1)))
                .andExpect(jsonPath("$.permissions.create_private").value(false))
                .andExpect(jsonPath("$.permissions.create_public").value(true));
    }

    @Test
    void publishOnlyCallerDoesNotSeeAnotherCallersPrivateDashboard() throws Exception {
        final String id = create(body("Alice's own", "private", WIDGET), alice());
        mockMvc.perform(authored(get(BASE + "/" + id), publisher())).andExpect(status().isNotFound());
        mockMvc.perform(authored(get(BASE), publisher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dashboards[?(@.id=='" + id + "')]", hasSize(0)));
    }

    @Test
    void publishOnlyCallerCannotWithdrawDashboard() throws Exception {
        final String created = mockMvc.perform(authored(post(BASE), publisher()).content(body("Stays public", "public", WIDGET)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        final Number generation = JsonPath.read(created, "$.generation");

        mockMvc.perform(authored(put(BASE + "/" + JsonPath.read(created, "$.id")), publisher())
                        .content(withGeneration(body("Stays public", "private", WIDGET), generation.longValue())))
                .andExpect(status().isForbidden());
    }

    @Test
    void writingPublicDashboardRequiresSavedQueryPublish() throws Exception {
        mockMvc.perform(authored(post(BASE), alice()).content(body("Not allowed", "public", WIDGET)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        final String id = create(body("Admin's", "public", WIDGET), admin());
        final long generation = generationOf(id, admin());
        mockMvc.perform(authored(put(BASE + "/" + id), alice()).content(withGeneration(body("Edited", "public", WIDGET), generation)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authored(delete(BASE + "/" + id), alice())).andExpect(status().isForbidden());
        mockMvc.perform(authored(get(BASE + "/" + id), alice())).andExpect(jsonPath("$.name").value("Admin's"));
    }

    @Test
    void forbiddenStaleReplaceAnswersForbiddenNotConflict() throws Exception {
        final String id = create(body("Stale for alice", "public", WIDGET), admin());
        mockMvc.perform(authored(put(BASE + "/" + id), alice()).content(withGeneration(body("x", "public", WIDGET), 99)))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonPublisherCanNeitherPublishNorWithdraw() throws Exception {
        final String own = create(body("Mine to keep", "private", WIDGET), alice());
        mockMvc.perform(authored(put(BASE + "/" + own), alice())
                        .content(withGeneration(body("Mine to keep", "public", WIDGET), generationOf(own, alice()))))
                .andExpect(status().isForbidden());
        mockMvc.perform(authored(get(BASE + "/" + own), alice())).andExpect(jsonPath("$.visibility").value("private"));

        final String shared = create(body("Shared, stays", "public", WIDGET), admin());
        mockMvc.perform(authored(put(BASE + "/" + shared), alice())
                        .content(withGeneration(body("Shared, stays", "private", WIDGET), generationOf(shared, admin()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void publisherMayPublishItsOwnDashboard() throws Exception {
        final String id = create(body("To publish", "private", WIDGET), admin());
        mockMvc.perform(authored(put(BASE + "/" + id), admin())
                        .content(withGeneration(body("To publish", "public", WIDGET), generationOf(id, admin()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("public"))
                .andExpect(jsonPath("$.owner_id").value("admin-user"));
    }

    @Test
    void withdrawingAnotherCallersPublicDashboardReturnsItToItsOwner() throws Exception {
        final String id = create(body("Withdrawn", "public", WIDGET), admin());

        mockMvc.perform(authored(put(BASE + "/" + id), otherAdmin())
                        .content(withGeneration(body("Withdrawn", "private", WIDGET), generationOf(id, admin()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner_id").value("admin-user"));

        mockMvc.perform(authored(get(BASE + "/" + id), admin())).andExpect(status().isOk());
        mockMvc.perform(authored(get(BASE + "/" + id), otherAdmin())).andExpect(status().isNotFound());
    }

    @Test
    void publishOnlyOwnerCannotChangeItsWithdrawnDashboard() throws Exception {
        final String id = create(body("Curated, then withdrawn", "public", WIDGET), publisher());
        mockMvc.perform(authored(put(BASE + "/" + id), admin())
                        .content(withGeneration(body("Curated, then withdrawn", "private", WIDGET), generationOf(id, publisher()))))
                .andExpect(status().isOk());

        // Every write to a private dashboard needs SAVED_QUERY_WRITE, which this owner does not hold.
        final long generation = generationOf(id, publisher());
        mockMvc.perform(authored(get(BASE + "/" + id), publisher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.write").value(false));
        mockMvc.perform(authored(put(BASE + "/" + id), publisher())
                        .content(withGeneration(body("Edited", "private", WIDGET), generation)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authored(put(BASE + "/" + id), publisher())
                        .content(withGeneration(body("Re-published", "public", WIDGET), generation)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authored(delete(BASE + "/" + id), publisher())).andExpect(status().isForbidden());
    }

    @Test
    void callerWithNoWritePrivilegeLearnsNothingFromWrite() throws Exception {
        final String id = create(body("Probed", "public", WIDGET), admin());
        mockMvc.perform(authored(delete(BASE + "/dash_00000000000000000000000000000000"), reader()))
                .andExpect(status().isForbidden());
        mockMvc.perform(authored(delete(BASE + "/" + id), reader())).andExpect(status().isForbidden());
    }

    @Test
    void theAnalystBundleKeepsPrivateDashboardsButCannotPublishOne() throws Exception {
        mockMvc.perform(authored(post(BASE), analyst()).content(body("Analyst's", "private", WIDGET)))
                .andExpect(status().isCreated());
        mockMvc.perform(authored(post(BASE), analyst()).content(body("Analyst's", "public", WIDGET)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anEditorWhoseCatalogViewIsNarrowerCannotSaveTheDashboard() throws Exception {
        // The admin may resolve the sensitive column; a publisher without the clearance may not, so every
        // replace it attempts revalidates the stored body and fails, even one that changes only the name.
        final String id = create(body("Sensitive", "public", SENSITIVE_WIDGET), admin());
        mockMvc.perform(authored(put(BASE + "/" + id), readPublisher())
                        .content(withGeneration(body("Sensitive, renamed", "public", SENSITIVE_WIDGET), generationOf(id, admin()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("widget w1: ")));
        mockMvc.perform(authored(get(BASE + "/" + id), admin())).andExpect(jsonPath("$.name").value("Sensitive"));
    }

    // ---------------------------------------------------------------- ownership

    @Test
    void privateDashboardIsRefusedWhenThePrincipalDoesNotResolve() throws Exception {
        mockMvc.perform(authored(post(BASE), principalless()).content(body("Orphan", "private", WIDGET)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("principal_unavailable"));
    }

    @Test
    void callerWithNoResolvablePrincipalListsPublicDashboardsOnly() throws Exception {
        final String hidden = create(body("Alice's secret", "private", WIDGET), alice());
        final String shown = create(body("Everyone's", "public", WIDGET), admin());

        mockMvc.perform(authored(get(BASE), principalless()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dashboards[?(@.id=='" + hidden + "')]", hasSize(0)))
                .andExpect(jsonPath("$.dashboards[?(@.id=='" + shown + "')]", hasSize(1)))
                .andExpect(jsonPath("$.permissions.create_private").value(false));
    }

    @Test
    void anOwnerlessPublicDashboardCannotBeWithdrawn() throws Exception {
        final String created = mockMvc.perform(authored(post(BASE), principallessAdmin())
                        .content(body("Ownerless", "public", WIDGET)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.owner_id").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        final String id = JsonPath.read(created, "$.id");

        mockMvc.perform(authored(get(BASE + "/" + id), admin()))
                .andExpect(jsonPath("$.permissions.write").value(true))
                .andExpect(jsonPath("$.permissions.publish").value(false));
        mockMvc.perform(authored(put(BASE + "/" + id), admin())
                        .content(withGeneration(body("Ownerless", "private", WIDGET), generationOf(id, admin()))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
        mockMvc.perform(authored(get(BASE + "/" + id), admin()))
                .andExpect(jsonPath("$.visibility").value("public"))
                .andExpect(jsonPath("$.name").value("Ownerless"));
    }

    // ---------------------------------------------------------------- permissions

    @Test
    void reportsEveryPermissionsCombination() throws Exception {
        final String own = create(body("Own private", "private", WIDGET), alice());
        final String shared = create(body("Shared", "public", WIDGET), admin());

        expectPermissions(own, alice(), false, true, false);
        expectPermissions(shared, alice(), true, false, false);
        expectPermissions(shared, readPublisher(), true, true, false);
        expectPermissions(shared, admin(), true, true, true);

        mockMvc.perform(authored(get(BASE), alice()))
                .andExpect(jsonPath("$.permissions.create_private").value(true))
                .andExpect(jsonPath("$.permissions.create_public").value(false));
        mockMvc.perform(authored(get(BASE), admin()))
                .andExpect(jsonPath("$.permissions.create_private").value(true))
                .andExpect(jsonPath("$.permissions.create_public").value(true));
    }

    @Test
    void rejectsSuppliedPermissions() throws Exception {
        mockMvc.perform(authored(post(BASE), alice())
                        .content(body("Asks for more", "private", WIDGET).replace("\"widgets\"", "\"permissions\":{\"publish\":true},\"widgets\"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    // ---------------------------------------------------------------- the activity feed

    @Test
    void anOwnerSeesItsPrivateDashboardsActivityAndOthersDoNot() throws Exception {
        final String id = create(body("Audited private", "private", WIDGET), alice());

        mockMvc.perform(authored(post(FEED), alice()).content(resourceFilter(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].resourceType").value("Dashboard"));
        mockMvc.perform(authored(post(FEED), bob()).content(resourceFilter(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        // A private dashboard is its owner's alone: a full admin holding every privilege does not see it.
        mockMvc.perform(authored(post(FEED), admin()).content(resourceFilter(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        // Single-activity retrieval is narrowed the same way: absent, not forbidden.
        final String feed = mockMvc.perform(authored(post(FEED), alice()).content(resourceFilter(id)))
                .andReturn().getResponse().getContentAsString();
        final String activityId = JsonPath.read(feed, "$.data[0].activityId");
        for (final String other : List.of(bob(), admin())) {
            mockMvc.perform(authored(get(FEED + "/" + activityId), other))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("activity_not_found"));
        }
        mockMvc.perform(authored(get(FEED + "/" + activityId), alice())).andExpect(status().isOk());
    }

    @Test
    void publicDashboardsActivityIsVisibleToAnyAuditReader() throws Exception {
        final String id = create(body("Audited public", "public", WIDGET), admin());
        mockMvc.perform(authored(post(FEED), bob()).content(resourceFilter(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].resourceType").value("Dashboard"));
    }

    @Test
    void deletedDashboardsActivityIsVisibleToNoOne() throws Exception {
        final String shared = create(body("Audited, then deleted", "public", WIDGET), admin());
        final String own = create(body("Alice's, then deleted", "private", WIDGET), alice());
        mockMvc.perform(authored(post(FEED), alice()).content(resourceFilter(shared)))
                .andExpect(jsonPath("$.data[0].resourceType").value("Dashboard"));
        mockMvc.perform(authored(post(FEED), alice()).content(resourceFilter(own)))
                .andExpect(jsonPath("$.data[0].resourceType").value("Dashboard"));
        mockMvc.perform(authored(delete(BASE + "/" + shared), admin())).andExpect(status().isNoContent());
        mockMvc.perform(authored(delete(BASE + "/" + own), alice())).andExpect(status().isNoContent());

        // No current row to resolve visibility or owner from, so every caller loses both: the public one's
        // author (the full admin) and reader (alice), and the private one's former owner (alice).
        for (final String id : List.of(shared, own)) {
            for (final String caller : List.of(alice(), admin())) {
                mockMvc.perform(authored(post(FEED), caller).content(resourceFilter(id)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data").isEmpty());
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private void expectPermissions(String id, String token, boolean listed, boolean write, boolean publish) throws Exception {
        mockMvc.perform(authored(get(BASE + "/" + id), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.write").value(write))
                .andExpect(jsonPath("$.permissions.publish").value(publish));
        if (listed) {
            mockMvc.perform(authored(get(BASE), token))
                    .andExpect(jsonPath("$.dashboards[?(@.id=='" + id + "')].permissions.write").value(write))
                    .andExpect(jsonPath("$.dashboards[?(@.id=='" + id + "')].permissions.publish").value(publish));
        }
    }

    private String create(String body, String token) throws Exception {
        return JsonPath.read(mockMvc.perform(authored(post(BASE), token).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private long generationOf(String id, String token) throws Exception {
        final Number generation = JsonPath.read(mockMvc.perform(authored(get(BASE + "/" + id), token))
                .andReturn().getResponse().getContentAsString(), "$.generation");
        return generation.longValue();
    }

    private static String body(String name, String visibility, String widgets) {
        return "{\"name\":\"" + name + "\",\"visibility\":\"" + visibility + "\",\"widgets\":[" + widgets + "]}";
    }

    private static String withGeneration(String body, long generation) {
        return body.substring(0, body.length() - 1) + ",\"generation\":" + generation + "}";
    }

    private static String resourceFilter(String resourceId) {
        return "{\"filters\":[{\"column\":\"resourceId\",\"operator\":\"eq\",\"value\":\"" + resourceId + "\"}]}";
    }

    private static MockHttpServletRequestBuilder authored(MockHttpServletRequestBuilder builder, String token) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType("application/json");
    }

    private static String token(String issuer, String sub, String role) {
        return JwtTestTokens.token(issuer, AUDIENCE, sub == null
                ? Map.of("roles", List.of(role), "email", "nobody@test")
                : Map.of("sub", sub, "roles", List.of(role), "email", sub + "@test"));
    }

    private static String admin() {
        return token(BUNDLES_ISSUER, "admin-user", "admin");
    }

    private static String otherAdmin() {
        return token(BUNDLES_ISSUER, "admin-user-2", "admin");
    }

    /** {@code READ_ONLY_ADMIN}: keeps private dashboards and reads the audit feed. */
    private static String alice() {
        return token(BUNDLES_ISSUER, "alice", "viewer");
    }

    /** A second {@code READ_ONLY_ADMIN}, so the owner filter has two people holding the same role. */
    private static String bob() {
        return token(BUNDLES_ISSUER, "bob", "viewer");
    }

    private static String analyst() {
        return token(BUNDLES_ISSUER, "analyst", "analyst");
    }

    /** A {@code READ_ONLY_ADMIN} whose token carries no {@code sub}, so its principal does not resolve. */
    private static String principalless() {
        return token(BUNDLES_ISSUER, null, "viewer");
    }

    /** The same, holding {@code FULL_ADMIN}, so it can create a public dashboard with no owner. */
    private static String principallessAdmin() {
        return token(BUNDLES_ISSUER, null, "admin");
    }

    private static String reader() {
        return token(SINGLE_ISSUER, "reader", "reader");
    }

    private static String writer() {
        return token(SINGLE_ISSUER, "writer", "writer");
    }

    private static String publisher() {
        return token(SINGLE_ISSUER, "publisher", "publisher");
    }

    private static String readPublisher() {
        return token(SINGLE_ISSUER, "readpublisher", "readpublisher");
    }

    private static String tableOnly() {
        return token(SINGLE_ISSUER, "tableonly", "tableonly");
    }
}
