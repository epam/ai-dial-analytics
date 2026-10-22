package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.query.saved.SavedQueryEntity;
import com.epam.aidial.analytics.service.query.saved.SavedQueryRepository;
import com.epam.aidial.analytics.service.query.saved.SavedQueryResultView;
import com.epam.aidial.analytics.service.query.saved.SavedQueryScope;
import com.epam.aidial.analytics.service.table.TableAccess;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.TableWriteMode;
import com.epam.aidial.analytics.web.security.JwtTestTokens;
import com.epam.aidial.analytics.web.security.TestSecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization of the audit surface in {@code oidc} mode.
 *
 * <p>The proposition under test is that the trail is readable by exactly the callers who can read the
 * thing it describes — no wider, no narrower. Wider would mean a table's history leaking to someone who
 * cannot read the table; narrower would mean gating the description above the description's subject.
 * So: 401 unauthenticated, 200 for a plain {@code READ_ONLY_ADMIN} (proving it is not
 * {@code FULL_ADMIN}-only), and 403 for an authenticated caller who maps to no application role
 * (proving it did not end up at bare authentication).
 *
 * <p>Only PostgreSQL is required; nothing here issues ClickHouse DDL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "clickhouse.migrations.enabled=false",
        "sync.validate-schema=false",
        "poll.enabled=false",
        "security.mode=oidc",
        "security.defaults.email-claim=email",
        "security.defaults.principal-claim=sub",
        "providers.full.issuer=" + AuditTrailAuthIntegrationTest.FULL_ISSUER,
        "providers.full.jwk-set-uri=https://issuer.test/full/jwks",
        "providers.full.audiences=" + AuditTrailAuthIntegrationTest.AUDIENCE,
        "providers.full.role-claims=roles",
        "providers.full.roles-mapping={\"admin\":[\"FULL_ADMIN\"]}",
        "providers.readonly.issuer=" + AuditTrailAuthIntegrationTest.READONLY_ISSUER,
        "providers.readonly.jwk-set-uri=https://issuer.test/readonly/jwks",
        "providers.readonly.audiences=" + AuditTrailAuthIntegrationTest.AUDIENCE,
        "providers.readonly.role-claims=roles",
        "providers.readonly.roles-mapping={\"viewer\":[\"READ_ONLY_ADMIN\"]}",
})
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class AuditTrailAuthIntegrationTest {

    static final String AUDIENCE = "adas-test";
    static final String FULL_ISSUER = "https://issuer.test/full";
    static final String READONLY_ISSUER = "https://issuer.test/readonly";

    private static final String FEED = "/v1/activities";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TableDefinitionRepository definitions;
    @Autowired
    private PipelineRepository pipelines;
    @Autowired
    private SavedQueryRepository savedQueries;
    @Autowired
    private TransactionTemplate transactions;

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
    }

    @Test
    void refusesAnUnauthenticatedCaller() throws Exception {
        mockMvc.perform(post(FEED).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void allowsReadOnlyAdmin() throws Exception {
        // The load-bearing half: the feed must not be FULL_ADMIN-only, because it exposes no field a
        // read-only admin cannot already read on the table itself.
        mockMvc.perform(authored(post(FEED), readOnlyAdmin()).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").exists());
    }

    @Test
    void allowsFullAdmin() throws Exception {
        mockMvc.perform(authored(post(FEED), fullAdmin()).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void refusesAnAuthenticatedCallerWithNoApplicationRole() throws Exception {
        // The other half: a valid token that maps to no role reads no table, so it must read no table
        // history either. If this ever answers 200, the feed has become more permissive than its subject.
        mockMvc.perform(authored(post(FEED), roleless()).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theSnapshotRouteMatchesTheLiveReadsAuthorization() throws Exception {
        final String path = "/v1/tables/dial_usage_log/revision/1";

        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        // A caller with no read privilege on the table is told the table is not there, not that it may not
        // see it — the non-disclosure rule, which makes the refusal indistinguishable from an absent table.
        mockMvc.perform(authored(get(path), roleless()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        // A caller holding TABLE_GET gets past authorization; whether that revision exists is a separate
        // question, and 404 revision_not_found is the honest answer for a table whose history predates the
        // trail.
        mockMvc.perform(authored(get(path), readOnlyAdmin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("revision_not_found"));
    }

    @Test
    void thePipelineSnapshotRouteMatchesTheLiveReadsAuthorization() throws Exception {
        final String path = "/v1/pipelines/does-not-exist/revision/1";

        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(authored(get(path), roleless())).andExpect(status().isForbidden());
        // A pipeline's own live reads need only a mapped application role, so this route behaves like the
        // table snapshot route above: a read-only admin falls through to the 404 rather than being refused.
        // Both kinds sit on this one route since the fold, so the enrich half is authorized the same way.
        mockMvc.perform(authored(get(path), readOnlyAdmin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("revision_not_found"));
        mockMvc.perform(authored(get(path), fullAdmin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("revision_not_found"));
    }

    @Test
    void readOnlyAdminSeesPipelineActivitiesInTheFeedJustAsFullAdminDoes() throws Exception {
        final String name = "auth_test_pipeline";
        createPipeline(name);
        try {
            // No Pipeline-specific narrowing: the bar for a resource's audit surface is whatever reading
            // that resource's current state requires, and a pipeline read needs only an application role.
            mockMvc.perform(authored(post(FEED), readOnlyAdmin()).content(resourceFilter(name)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].resourceType").value("Pipeline"));
            mockMvc.perform(authored(post(FEED), fullAdmin()).content(resourceFilter(name)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].resourceType").value("Pipeline"));
        } finally {
            deletePipeline(name);
        }
    }

    @Test
    void readOnlyAdminMayFilterTheFeedToPipelineActivities() throws Exception {
        final String name = "auth_test_pipeline_typed";
        createPipeline(name);
        try {
            // A resource-type filter is the shape that used to come back empty for this role.
            mockMvc.perform(authored(post(FEED), readOnlyAdmin()).content(resourceTypeFilter()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].resourceType").value("Pipeline"));
        } finally {
            deletePipeline(name);
        }
    }

    @Test
    void theFeedFiltersByTheAuthorsEmail() throws Exception {
        // Written through the API, not the repository: only an authenticated request records an email.
        final String name = "auth_test_email_table";
        mockMvc.perform(authored(post("/v1/tables"), fullAdmin())
                        .content("{\"name\":\"" + name + "\",\"type\":\"source\"}"))
                .andExpect(status().isCreated());
        try {
            mockMvc.perform(authored(post(FEED), readOnlyAdmin()).content(emailFilter(name, "eq", "admin@test")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.data[0].initiatedEmail").value("admin@test"));
            // The resourceId alone already narrows to one row, so each operator also needs a miss to prove
            // the email predicate is applied at all.
            mockMvc.perform(authored(post(FEED), readOnlyAdmin()).content(emailFilter(name, "co", "ADMIN@")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1));
            mockMvc.perform(authored(post(FEED), readOnlyAdmin()).content(emailFilter(name, "co", "alice")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            mockMvc.perform(authored(post(FEED), readOnlyAdmin()).content(emailFilter(name, "eq", "alice@test")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
        } finally {
            transactions.executeWithoutResult(status -> definitions.findById(name).ifPresent(definitions::delete));
        }
    }

    @Test
    void savedQueryOwnerSeesItsPersonalActivityAndFullAdminDoesToo() throws Exception {
        // `alice` is the read-only admin token's `sub` — see readOnlyAdmin().
        final String id = createSavedQuery(SavedQueryScope.PERSONAL, "alice");
        try {
            mockMvc.perform(authored(post(FEED), readOnlyAdmin()).content(resourceFilter(id)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].resourceType").value("SavedQuery"));
            mockMvc.perform(authored(post(FEED), fullAdmin()).content(resourceFilter(id)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].resourceType").value("SavedQuery"));
        } finally {
            deleteSavedQuery(id);
        }
    }

    @Test
    void nonOwnerReadOnlyAdminDoesNotSeeAnothersPersonalSavedQueryActivity() throws Exception {
        final String id = createSavedQuery(SavedQueryScope.PERSONAL, "someone-else");
        try {
            // A content filter within an otherwise-successful response, not an authorization denial — the
            // request still succeeds, with the row simply absent.
            mockMvc.perform(authored(post(FEED), readOnlyAdmin()).content(resourceFilter(id)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
            mockMvc.perform(authored(post(FEED), fullAdmin()).content(resourceFilter(id)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].resourceType").value("SavedQuery"));
        } finally {
            deleteSavedQuery(id);
        }
    }

    @Test
    void anyRoleHoldingCallerSeesCommonSavedQueryActivity() throws Exception {
        final String id = createSavedQuery(SavedQueryScope.COMMON, null);
        try {
            mockMvc.perform(authored(post(FEED), readOnlyAdmin()).content(resourceFilter(id)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].resourceType").value("SavedQuery"));
            mockMvc.perform(authored(post(FEED), fullAdmin()).content(resourceFilter(id)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].resourceType").value("SavedQuery"));
        } finally {
            deleteSavedQuery(id);
        }
    }

    @Test
    void theSavedQuerySnapshotRouteMatchesTheOwnerCommonFullAdminRule() throws Exception {
        // `alice` is the read-only admin token's `sub` (see readOnlyAdmin()); owning it by someone else
        // makes her a non-owner here, so the narrowing — not merely "no such revision" — is what refuses.
        final String id = createSavedQuery(SavedQueryScope.PERSONAL, "someone-else");
        try {
            final String path = "/v1/saved-queries/" + id + "/revision/1";

            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            // FULL_ADMIN gets past the visibility gate; whether revision 1 happens to be this saved
            // query's own creation is a separate question, and 404 is the honest answer either way.
            mockMvc.perform(authored(get(path), fullAdmin()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("revision_not_found"));
            // Invisible and nonexistent share one answer, by the same disclosure rule the live read uses.
            mockMvc.perform(authored(get(path), readOnlyAdmin()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("not_found"));
        } finally {
            deleteSavedQuery(id);
        }
    }

    @Test
    void deletedSavedQueryActivityIsVisibleOnlyToFullAdmin() throws Exception {
        final String id = createSavedQuery(SavedQueryScope.PERSONAL, "alice");
        transactions.executeWithoutResult(status -> savedQueries.deleteById(id));

        // No current row to resolve scope/owner from, so even the caller this row would have belonged
        // to loses feed visibility the moment it is gone — the stated trade-off, not an oversight.
        mockMvc.perform(authored(post(FEED), readOnlyAdmin()).content(resourceFilter(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        mockMvc.perform(authored(post(FEED), fullAdmin()).content(resourceFilter(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].resourceType").value("SavedQuery"));
    }

    @Test
    void singleActivityReadIsGatedTheSameWay() throws Exception {
        final String path = FEED + "/00000000-0000-0000-0000-000000000000";

        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(authored(get(path), roleless())).andExpect(status().isForbidden());
        mockMvc.perform(authored(get(path), readOnlyAdmin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("activity_not_found"));
    }

    @Test
    void singlePipelineActivityIsDirectlyReadableByReadOnlyAdmin() throws Exception {
        final String name = "auth_test_pipeline_direct";
        createPipeline(name);
        try {
            final String activityId = extractFirstActivityId(
                    mockMvc.perform(authored(post(FEED), fullAdmin()).content(resourceFilter(name))).andReturn());
            // get() applies whatever narrowings the feed applies, and Pipeline now has none — so the id
            // resolves for a READ_ONLY_ADMIN rather than answering with the no-disclosure 404.
            mockMvc.perform(authored(get(FEED + "/" + activityId), readOnlyAdmin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resourceType").value("Pipeline"));
            mockMvc.perform(authored(get(FEED + "/" + activityId), fullAdmin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resourceType").value("Pipeline"));
        } finally {
            deletePipeline(name);
        }
    }

    @Test
    void singlePersonalSavedQueryActivityIsNotDirectlyReadableByNonOwner() throws Exception {
        // `alice` is the read-only admin's sub; owning by someone-else makes her a non-owner.
        final String id = createSavedQuery(SavedQueryScope.PERSONAL, "someone-else");
        try {
            final String activityId = extractFirstActivityId(
                    mockMvc.perform(authored(post(FEED), fullAdmin()).content(resourceFilter(id))).andReturn());
            // Non-owner gets 404 — same no-disclosure shape the live saved-query read uses.
            mockMvc.perform(authored(get(FEED + "/" + activityId), readOnlyAdmin()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("activity_not_found"));
            mockMvc.perform(authored(get(FEED + "/" + activityId), fullAdmin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resourceType").value("SavedQuery"));
        } finally {
            deleteSavedQuery(id);
        }
    }

    private static MockHttpServletRequestBuilder authored(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private static String fullAdmin() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "admin-user", "roles", List.of("admin"), "email", "admin@test"));
    }

    private static String readOnlyAdmin() {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE,
                Map.of("sub", "alice", "roles", List.of("viewer"), "email", "alice@test"));
    }

    /** A valid token whose roles map to no application role — authenticated, but entitled to nothing. */
    private static String roleless() {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE,
                Map.of("sub", "nobody", "roles", List.of("some-unmapped-role"), "email", "nobody@test"));
    }

    private String extractFirstActivityId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/0/activityId").asText();
    }

    private static String resourceFilter(String resourceId) {
        return "{\"filters\":[{\"column\":\"resourceId\",\"operator\":\"eq\",\"value\":\"" + resourceId + "\"}]}";
    }

    /** One resource's activities, further narrowed by the author's email. */
    private static String emailFilter(String resourceId, String operator, String email) {
        return "{\"filters\":[{\"column\":\"resourceId\",\"operator\":\"eq\",\"value\":\"" + resourceId + "\"},"
                + "{\"column\":\"initiatedEmail\",\"operator\":\"" + operator + "\",\"value\":\"" + email + "\"}]}";
    }

    /** Narrows the feed to `Pipeline`-typed activities — the request shape a read-only admin may now make. */
    private static String resourceTypeFilter() {
        return "{\"filters\":[{\"column\":\"resourceType\",\"operator\":\"eq\",\"value\":\"Pipeline\"}]}";
    }

    private void createPipeline(String name) {
        transactions.executeWithoutResult(status -> {
            final TableDefinitionEntity table = new TableDefinitionEntity();
            table.setName(name + "_table");
            table.setType(TableType.SOURCE);
            table.setStatus(TableStatus.PENDING);
            table.setWriteMode(TableWriteMode.UPSERT_BY_KEY);
            table.setAccess(new TableAccess());
            definitions.save(table);

            final PipelineEntity pipeline = new PipelineEntity();
            pipeline.setName(name);
            pipeline.setKind(PipelineKind.AGGREGATE);
            pipeline.setTarget(table.getName());
            pipeline.setInputTable(table.getName());
            // trigger_kind became a NOT NULL column of the merged registry, guarded by
            // pipeline_cron_iff_schedule: a cron without SCHEDULE is now unstorable.
            pipeline.setTriggerKind(TriggerKind.SCHEDULE);
            pipeline.setTriggerCron("0 0 * * * *");
            pipeline.setEnabled(false);
            pipeline.setHasMore(false);
            pipeline.setRebuiltAt(Instant.now());
            pipeline.setGeneration(0);
            pipeline.setCreatedAt(Instant.now());
            pipeline.setUpdatedAt(Instant.now());
            pipelines.save(pipeline);
        });
    }

    private void deletePipeline(String name) {
        transactions.executeWithoutResult(status -> {
            pipelines.findById(name).ifPresent(pipelines::delete);
            definitions.findById(name + "_table").ifPresent(definitions::delete);
        });
    }

    private String createSavedQuery(SavedQueryScope scope, String ownerId) {
        final SavedQueryEntity entity = new SavedQueryEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setName("auth test query");
        entity.setScope(scope);
        entity.setOwnerId(ownerId);
        entity.setSource(List.of("some_table"));
        entity.setSqlText("SELECT 1");
        entity.setResultView(SavedQueryResultView.TABLE);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        transactions.executeWithoutResult(status -> savedQueries.save(entity));
        return entity.getId();
    }

    private void deleteSavedQuery(String id) {
        transactions.executeWithoutResult(status -> savedQueries.findById(id).ifPresent(savedQueries::delete));
    }
}
