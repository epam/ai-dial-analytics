package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.PipelineInputDependents;
import com.epam.aidial.analytics.web.security.JwtTestTokens;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static com.epam.aidial.analytics.integration.RunnerCalls.runnerPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Least-privilege authorization for the pipeline data-plane in {@code oidc} mode. Proves the runner needs
 * no global privilege at all: a caller carrying only an unmapped provider role reaches
 * {@code POST /v1/pipelines/{name}/scan|rows} and is authorized by <b>two</b> grants — {@code read} on the
 * pipeline's read source and {@code write} on its target enrichment.
 *
 * <p>The read half is what closed the confused deputy. Authorizing both directions from the target's write
 * grant alone meant a {@code write} grant on one small derived enrichment was a read capability over the
 * table it derives from — and, because a scan's projection may include the columns of that source's other
 * active enrichments, over its siblings too. So {@code target write alone} is now denied
 * ({@code 403 pipeline_access_denied}), and the tests below grant the source read explicitly.
 *
 * <p>Also covers the sensitive boundary: a caller granted <em>both</em> directions still cannot operate a
 * pipeline that reads a {@code sensitive} column ({@code 403 sensitive_column_not_entitled}) — that needs
 * {@code sensitive} on the table the column belongs to, which is now grantable per table rather than
 * requiring global administrative authority. Requires Docker.
 */
class PipelineDataAuthorizationIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final String ENRICHMENT = "authz_tox";
    private static final String GROUP_ENRICHMENT = "authz_conv";
    private static final String WRITER_ROLE = "runner-role";

    /** A row-grain {@code llm} transform writing the fixture's one output column. */
    private static String rowTransform() {
        return Transforms.llm().proseOutputs("tox_score").member();
    }

    /**
     * A group-grain transform for the one group pipeline here. The template rule is grain-specific — a
     * group template must reference {{members}}, a row-grain one may reference no built-in — so one
     * template cannot serve both grains.
     */
    private static String groupTransform() {
        return Transforms.llm()
                .requestTemplate("{\"messages\":[{\"role\":\"user\","
                        + "\"content\":\"Members ({{members_included}}). {{members}}\"}]}")
                .input("body", "dial_usage_log_payload.request_body")
                .proseOutputs("tox_score")
                .member();
    }

    /** The read source every pipeline here follows when it declares none: the target's parent. */
    private static final String DEFAULT_READ_SOURCE = "dial_usage_log";

    private String pipelineName;

    @Autowired
    private ColumnMappingRepository columnMappings;
    @Autowired
    private PipelineInputDependents pipelineReads;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc.perform(post("/v1/tables").header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + ENRICHMENT + "\",\"type\":\"enrichment\","
                                + "\"source_table\":\"dial_usage_log\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/tables/" + ENRICHMENT + "/schema")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grain_key\":\"event_id\","
                                + "\"columns\":[{\"source_name\":\"tox_score\",\"name\":\"tox_score\",\"type\":\"long\"}]}"))
                .andExpect(status().isOk());
        String content = mockMvc.perform(post("/v1/pipelines").header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"authz-pipeline\"," + rowTransform() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        pipelineName = JsonPath.read(content, "$.name");
    }

    /**
     * Merges one key of {@code table}'s grant list. Absent keys keep their stored value, so granting the
     * source's {@code read} does not disturb the target's {@code write} and vice versa.
     *
     * <p>{@code dial_usage_log} and {@code rate_analytics} are {@code system} tables, and the read-side keys
     * are storable on one — which is the whole point of moving the system-table refusal from the object to
     * the keys. A blanket refusal would make this test unwritable.
     */
    private void grant(String table, String key, String role) throws Exception {
        mockMvc.perform(put("/v1/tables/" + table + "/access")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"" + key + "\":[\"" + role + "\"]}"))
                .andExpect(status().isOk());
    }

    /**
     * Revokes every read-side grant on a shared system table, so one test cannot leak into the next.
     *
     * <p>Asserted, not fire-and-forget. {@code dial_usage_log} and {@code rate_analytics} are seeded rows
     * shared with the rest of the suite rather than data this class owns, so a revoke that silently failed
     * would hand the next test a grant it never made — and that test would pass for the wrong reason.
     */
    private void revokeReads(String table) throws Exception {
        mockMvc.perform(put("/v1/tables/" + table + "/access")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"get\":[],\"read\":[],\"sensitive\":[]}"))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() throws Exception {
        revokeReads(DEFAULT_READ_SOURCE);
        revokeReads("rate_analytics");
        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())));
    }

    @Test
    void runnerNeedsSourceReadAndTargetWrite() throws Exception {
        // No global privilege — only an unmapped provider role. Denied.
        mockMvc.perform(runnerPost("/v1/pipelines/" + pipelineName + "/scan").header(HttpHeaders.AUTHORIZATION, bearer(runner()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("pipeline_access_denied"));

        // Target write alone is no longer enough: the scan returns rows of the READ SOURCE, so a write
        // grant on a derived enrichment must not buy a read of the table it derives from.
        grant(ENRICHMENT, "write", WRITER_ROLE);
        mockMvc.perform(runnerPost("/v1/pipelines/" + pipelineName + "/scan").header(HttpHeaders.AUTHORIZATION, bearer(runner()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("pipeline_access_denied"));

        // Source read alone is not enough either: the pipeline writes its target.
        grant(ENRICHMENT, "write", "someone-else");
        grant(DEFAULT_READ_SOURCE, "read", WRITER_ROLE);
        mockMvc.perform(runnerPost("/v1/pipelines/" + pipelineName + "/scan").header(HttpHeaders.AUTHORIZATION, bearer(runner()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("pipeline_access_denied"));

        // Both grants: authorized, with no global privilege anywhere.
        grant(ENRICHMENT, "write", WRITER_ROLE);
        mockMvc.perform(runnerPost("/v1/pipelines/" + pipelineName + "/scan").header(HttpHeaders.AUTHORIZATION, bearer(runner()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows").isArray());
    }

    @Test
    void runnerWithoutGrantCannotWrite() throws Exception {
        mockMvc.perform(runnerPost("/v1/pipelines/" + pipelineName + "/rows").header(HttpHeaders.AUTHORIZATION, bearer(runner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"11111111-1111-1111-1111-111111111111\",\"tox_score\":1}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("pipeline_access_denied"));
    }

    @Test
    void fullAdminMayScan() throws Exception {
        mockMvc.perform(runnerPost("/v1/pipelines/" + pipelineName + "/scan").header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows").isArray());
    }

    /**
     * A pipeline whose declared read source is a different table has that table authorized <b>in its own
     * right</b>. The target's grant says nothing about what may be read, so a declared source is exactly
     * where the old model leaked: a write grant on a small enrichment bought a read of whatever table the
     * declaration happened to name.
     */
    @Test
    void declaredReadSourceIsAuthorizedInItsOwnRight() throws Exception {
        mockMvc.perform(delete("/v1/pipelines/" + pipelineName).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(status().isNoContent());
        // Keyed on chat_id, which dial_usage_log (the attachment) and rate_analytics (the read source)
        // both carry — the conversation-grain enrichment fed from ratings.
        mockMvc.perform(post("/v1/tables").header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"authz_rated\",\"type\":\"enrichment\","
                                + "\"source_table\":\"dial_usage_log\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/tables/authz_rated/schema")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grain_key\":\"chat_id\",\"columns\":[{\"source_name\":\"tox_score\","
                                + "\"name\":\"tox_score\",\"type\":\"long\"}]}"))
                .andExpect(status().isOk());
        try {
            String declared = JsonPath.read(mockMvc.perform(post("/v1/pipelines")
                            .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"authz-declared-pipeline\"," + rowTransform() + ","
                                    + "\"kind\":\"enrich\",\"target\":\"authz_rated\",\"inputs\":[\"rate_analytics\"],"
                                    + "\"trigger\":{\"kind\":\"schedule\"},\"filter\":\"rate > 0\","
                                    + "\"enabled\":true}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString(), "$.name");

            // No grant on the target: denied, even though nothing was said about rate_analytics either way.
            mockMvc.perform(runnerPost("/v1/pipelines/" + declared + "/scan")
                            .header(HttpHeaders.AUTHORIZATION, bearer(runner()))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":5}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("pipeline_access_denied"));

            // The target grant alone does NOT authorize reading the declared source. This is the case the
            // old model got wrong, and the one assertion in this file that changed meaning rather than
            // gaining a step.
            grant("authz_rated", "write", WRITER_ROLE);
            mockMvc.perform(runnerPost("/v1/pipelines/" + declared + "/scan")
                            .header(HttpHeaders.AUTHORIZATION, bearer(runner()))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":5}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("pipeline_access_denied"));

            // A read grant on the table actually read is what authorizes the read direction.
            grant("rate_analytics", "read", WRITER_ROLE);
            mockMvc.perform(runnerPost("/v1/pipelines/" + declared + "/scan")
                            .header(HttpHeaders.AUTHORIZATION, bearer(runner()))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":5}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows").isArray());
        } finally {
            mockMvc.perform(delete("/v1/tables/authz_rated")
                    .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())));
        }
    }

    /**
     * The per-column half of the same boundary, now on a DECLARED read source: {@code comment} is a
     * sensitive column of {@code rate_analytics}, and a granted-but-non-entitled caller is refused for
     * reaching it — the sensitive re-check follows the table the pipeline reads, not the target's own source.
     */
    @Test
    void grantedButNonEntitledRunnerCannotScanSensitiveColumnOfDeclaredReadSource() throws Exception {
        mockMvc.perform(delete("/v1/pipelines/" + pipelineName).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/v1/tables").header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"authz_rated_s\",\"type\":\"enrichment\","
                                + "\"source_table\":\"dial_usage_log\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/tables/authz_rated_s/schema")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grain_key\":\"chat_id\",\"columns\":[{\"source_name\":\"tox_score\","
                                + "\"name\":\"tox_score\",\"type\":\"long\"}]}"))
                .andExpect(status().isOk());
        try {
            String sensitive = JsonPath.read(mockMvc.perform(post("/v1/pipelines")
                            .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"authz-declared-sensitive\"," + rowTransform()
                                    + ",\"kind\":\"enrich\",\"target\":\"authz_rated_s\",\"inputs\":[\"rate_analytics\"],"
                                    + "\"trigger\":{\"kind\":\"schedule\"},\"filter\":\"length(comment) > 0\","
                                    + "\"enabled\":true}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString(), "$.name");

            // Both directions granted, so the refusal below is the sensitive check rather than the
            // read/write one — `read` on rate_analytics does not carry `sensitive`.
            grant("authz_rated_s", "write", WRITER_ROLE);
            grant("rate_analytics", "read", WRITER_ROLE);

            mockMvc.perform(runnerPost("/v1/pipelines/" + sensitive + "/scan")
                            .header(HttpHeaders.AUTHORIZATION, bearer(runner()))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":5}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("sensitive_column_not_entitled"));

            mockMvc.perform(runnerPost("/v1/pipelines/" + sensitive + "/scan")
                            .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":5}"))
                    .andExpect(status().isOk());
        } finally {
            mockMvc.perform(delete("/v1/tables/authz_rated_s")
                    .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())));
        }
    }

    /**
     * The same Track C boundary where the sensitive column is reached through the pipeline's {@code filter_sql}
     * rather than a bound column. A non-entitled caller cannot even parse the stored filter — the sensitive
     * column is hidden from its schema view — so the refusal has to be re-attributed to the 403 it is,
     * instead of reporting a governed pipeline as a malformed one.
     */
    @Test
    void grantedButNonEntitledRunnerCannotScanFilterOnSensitiveColumn() throws Exception {
        mockMvc.perform(delete("/v1/pipelines/" + pipelineName).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(status().isNoContent());
        String content = mockMvc.perform(post("/v1/pipelines").header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"authz-filter-pipeline\"," + rowTransform() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"filter\":\"length(\\\"dial_usage_log_payload.request_body\\\") > 0\","
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String filterPipelineName = JsonPath.read(content, "$.name");

        grant(ENRICHMENT, "write", WRITER_ROLE);
        grant(DEFAULT_READ_SOURCE, "read", WRITER_ROLE);

        mockMvc.perform(runnerPost("/v1/pipelines/" + filterPipelineName + "/scan")
                        .header(HttpHeaders.AUTHORIZATION, bearer(runner()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("sensitive_column_not_entitled"));

        mockMvc.perform(runnerPost("/v1/pipelines/" + filterPipelineName + "/scan")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows").isArray());
    }

    /**
     * The Track C consequence, at the egress point: a target-enrichment grant is not enough to operate a
     * pipeline that reads a {@code sensitive} column. A granted-but-non-entitled caller is refused, and only
     * a {@code FULL_ADMIN} caller may cause the derivation to run — which is why the Enrichment Runner
     * must be {@code FULL_ADMIN} for any sensitive-touching pipeline. Exercised through the Class-1
     * materialize path, whose motivating workload is exactly reading stored bodies.
     */
    @Test
    void grantedButNonEntitledRunnerCannotMaterializeSensitiveReadingPipeline() throws Exception {
        // length over the payload body is a real derivation over a sensitive column, and yields a long — the type
        // the existing target column already declares.
        mockMvc.perform(delete("/v1/pipelines/" + pipelineName).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(status().isNoContent());
        String content = mockMvc.perform(post("/v1/pipelines").header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        // Keyed by the target column, so the output is named `tox_score` rather than
                        // `size`: an output resolves to the column of the same name.
                        .content("{\"name\":\"authz-sql-pipeline\","
                                + Transforms.sql()
                                        .output("tox_score",
                                                "length(\\\"dial_usage_log_payload.request_body\\\")")
                                        .member() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sqlPipelineName = JsonPath.read(content, "$.name");

        // Grant both directions so the read/write checks pass and the sensitive check is what refuses.
        grant(ENRICHMENT, "write", WRITER_ROLE);
        grant(DEFAULT_READ_SOURCE, "read", WRITER_ROLE);

        mockMvc.perform(runnerPost("/v1/pipelines/" + sqlPipelineName + "/materialize")
                        .header(HttpHeaders.AUTHORIZATION, bearer(runner()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("sensitive_column_not_entitled"));

        // The entitled caller runs the same derivation. This class shares dial_usage_log with the rest of
        // the suite, so assert only that it is authorized, not on a particular row count.
        mockMvc.perform(runnerPost("/v1/pipelines/" + sqlPipelineName + "/materialize")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows_written").exists());
    }

    /**
     * The same Track C boundary on the group path, where the sensitive column is projected directly (via a
     * bound input binding) rather than through a parsed expression: an entitled caller receives the body
     * column, a granted-but-non-entitled caller is refused rather than served a group with the column
     * quietly stripped — a stripped group would yield a quietly wrong label.
     *
     * <p>The pipeline's <em>scan</em> is asserted alongside it because a group pipeline's page deliberately does
     * not project its bound columns. The entitlement check must keep covering them anyway: the pipeline still
     * reads the body, at the assembly endpoint, so narrowing the projection must not hand a non-entitled
     * caller a dirty-mark on a pipeline whose groups it would then be refused.
     */
    @Test
    void groupPathServesBodyColumnsOnlyToAnEntitledCaller() throws Exception {
        mockMvc.perform(post("/v1/tables").header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + GROUP_ENRICHMENT + "\",\"type\":\"enrichment\","
                                + "\"source_table\":\"dial_usage_log\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/tables/" + GROUP_ENRICHMENT + "/schema")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grain_key\":\"chat_id\",\"columns\":[{\"source_name\":\"tox_score\","
                                + "\"name\":\"tox_score\",\"type\":\"long\"}]}"))
                .andExpect(status().isOk());
        String content = mockMvc.perform(post("/v1/pipelines").header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"authz-group-pipeline\"," + groupTransform() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + GROUP_ENRICHMENT + "\","
                                + "\"trigger\":{\"kind\":\"group\",\"group_by\":\"chat_id\","
                                + "\"ready_when\":{\"idle\":\"10m\"}},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String groupPipelineName = JsonPath.read(content, "$.name");

        grant(GROUP_ENRICHMENT, "write", WRITER_ROLE);
        grant(DEFAULT_READ_SOURCE, "read", WRITER_ROLE);

        try {
            mockMvc.perform(runnerPost("/v1/pipelines/" + groupPipelineName + "/group-rows")
                            .header(HttpHeaders.AUTHORIZATION, bearer(runner()))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"group_key\":\"chat-a\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("sensitive_column_not_entitled"));

            mockMvc.perform(runnerPost("/v1/pipelines/" + groupPipelineName + "/group-rows")
                            .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"group_key\":\"chat-a\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows").isArray());

            // The scan of the same pipeline: refused for the non-entitled caller even though a group pipeline's
            // page no longer carries the bound body — the check follows what the pipeline reads, not what the
            // response returns.
            mockMvc.perform(runnerPost("/v1/pipelines/" + groupPipelineName + "/scan")
                            .header(HttpHeaders.AUTHORIZATION, bearer(runner()))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":5}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("sensitive_column_not_entitled"));

            mockMvc.perform(runnerPost("/v1/pipelines/" + groupPipelineName + "/scan")
                            .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":5}"))
                    .andExpect(status().isOk());

            // A selection policy is a third way a pipeline can name a sensitive column, and the only one whose
            // columns never reach the response at all — they are read to rank members and then dropped. The
            // check binds to what the pipeline DECLARES, so it refuses this exactly as it refuses a binding: an
            // entitlement that depended on which phase projected a column would be no entitlement at all.
            // Repointed onto the existing pipeline rather than added beside it, since a target enrichment
            // admits only one pipeline.
            mockMvc.perform(patch("/v1/pipelines/" + groupPipelineName).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"authz-group-pipeline\"," + groupTransform() + ","
                                    + "\"kind\":\"enrich\",\"target\":\"" + GROUP_ENRICHMENT + "\","
                                    + "\"trigger\":{\"kind\":\"group\",\"group_by\":\"chat_id\","
                                    + "\"ready_when\":{\"idle\":\"10m\"},"
                                    + "\"member_select\":{\"prefer_sql\":\"\\\"dial_usage_log_payload.request_body\\\" IS NOT NULL\","
                                    + "\"limit\":1}},"
                                    + "\"enabled\":true}"))
                    .andExpect(status().isOk());

            mockMvc.perform(runnerPost("/v1/pipelines/" + groupPipelineName + "/group-rows")
                            .header(HttpHeaders.AUTHORIZATION, bearer(runner()))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"group_key\":\"chat-a\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("sensitive_column_not_entitled"));

            mockMvc.perform(runnerPost("/v1/pipelines/" + groupPipelineName + "/group-rows")
                            .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"group_key\":\"chat-a\"}"))
                    .andExpect(status().isOk());
        } finally {
            mockMvc.perform(delete("/v1/tables/" + GROUP_ENRICHMENT)
                    .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())));
        }
    }

    /**
     * The schema guard's question — "which pipelines read this source column?" — answered by the real
     * resolver over a real stored declaration. The guard's own unit test mocks this lookup, which is how it
     * shipped seeing only qualified {@code table.column} tokens: an expression reading
     * {@code length(deployment)} bare was invisible to it, which is the exact shape a rename-repoint uses.
     */
    @Test
    void sourceColumnReadBareByExpressionIsFoundBySchemaGuard() throws Exception {
        mockMvc.perform(delete("/v1/pipelines/" + pipelineName).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(status().isNoContent());
        final String name = registerSqlPipeline("authz-bare-read", "length(deployment)");
        try {
            assertThat(pipelineReads.pipelinesReadingSourceColumn(DEFAULT_READ_SOURCE, "deployment")).contains(name);
            assertThat(pipelineReads.pipelinesReadingSourceColumn(DEFAULT_READ_SOURCE, "project_id"))
                    .doesNotContain(name);
        } finally {
            mockMvc.perform(delete("/v1/pipelines/" + name).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())));
        }
    }

    /**
     * {@code trigger.group_by} is entitlement-checked like every other reference. It used to join the
     * gate's reference set unexamined, so a sensitive group key was copied into the target as its grain
     * without its author being checked. The author here may write pipelines and read every table, but not read sensitive
     * columns; its transform binds only a non-sensitive column, so the group key is the only thing that can
     * refuse it.
     */
    @Test
    void sensitiveGroupKeyRefusesAuthorWhoMayNotReadIt() throws Exception {
        mockMvc.perform(post("/v1/tables").header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + GROUP_ENRICHMENT + "\",\"type\":\"enrichment\","
                                + "\"source_table\":\"dial_usage_log\"}"))
                .andExpect(status().isCreated());
        final ColumnMappingEntity chatId = columnMappings.findByTableNameOrderByOrdinal(DEFAULT_READ_SOURCE)
                .stream().filter(column -> "chat_id".equals(column.getName())).findFirst().orElseThrow();
        try {
            mockMvc.perform(post("/v1/tables/" + GROUP_ENRICHMENT + "/schema")
                            .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"grain_key\":\"chat_id\",\"columns\":[{\"source_name\":\"tox_score\","
                                    + "\"name\":\"tox_score\",\"type\":\"long\"}]}"))
                    .andExpect(status().isOk());
            chatId.setSensitive(true);
            columnMappings.save(chatId);

            final String transform = Transforms.llm()
                    .requestTemplate("{\"messages\":[{\"role\":\"user\","
                            + "\"content\":\"Members ({{members_included}}). {{members}}\"}]}")
                    .input("model", "deployment")
                    .proseOutputs("tox_score")
                    .member();
            // Registered armed: a disabled registration is a draft and runs no gate, so the entitlement check
            // under test is asked when the pipeline is enabled.
            mockMvc.perform(post("/v1/pipelines").header(HttpHeaders.AUTHORIZATION, bearer(registryAuthor()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"authz-sensitive-group-key\"," + transform + ","
                                    + "\"kind\":\"enrich\",\"target\":\"" + GROUP_ENRICHMENT + "\","
                                    + "\"trigger\":{\"kind\":\"group\",\"group_by\":\"chat_id\","
                                    + "\"ready_when\":{\"idle\":\"10m\"}},\"enabled\":true}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("sensitive_column_not_entitled"));
        } finally {
            chatId.setSensitive(false);
            columnMappings.save(chatId);
            mockMvc.perform(delete("/v1/tables/" + GROUP_ENRICHMENT)
                    .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())));
        }
    }

    private static String registryAuthor() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "registry-user", "roles", List.of("registry"), "email", "registry@test"));
    }

    private String registerSqlPipeline(String name, String expression) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/v1/pipelines")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\","
                                + Transforms.sql().output("tox_score", expression).member() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.name");
    }

    private static String fullAdmin() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "full-user", "roles", List.of("admin"), "email", "full@test"));
    }

    /** Carries only the unmapped raw role {@code runner-role} → no application role. */
    private static String runner() {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE,
                Map.of("sub", "runner", "roles", List.of(WRITER_ROLE), "email", "runner@test"));
    }

}
