package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.pipeline.AggregateRefreshService;
import com.epam.aidial.analytics.service.security.SystemIdentity;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.web.security.JwtTestTokens;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static com.epam.aidial.analytics.integration.RunnerCalls.runnerPost;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Pipeline and export paths refuse a hidden column" (export is in {@code CatalogTransferAuthIntegrationTest}) and
 * "Column policies do not propagate to derived tables", in {@code oidc} mode over the seeded {@code dial_usage_log}.
 *
 * <p>The runner carries only the unmapped role {@code cpp-runner}: {@code write} on the target and a {@code read} entry
 * on {@code dial_usage_log} whose {@code columns} each test sets, so its list alone decides which columns the scan,
 * group assembly and materialize may return. The target enrichment is keyed on {@code event_id}, so it is aligned with
 * {@code dial_usage_log} and every scan also returns {@code request_time} as {@code __partition_v}. The entries this
 * class writes on the shared seeded tables are removed after each test.
 */
class ColumnPolicyPipelineIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final String USAGE = "dial_usage_log";
    private static final String TARGET = "cpp_tox";
    private static final String GROUP_TARGET = "cpp_conv";
    private static final String ROLLUP = "cpp_users";
    private static final String EVENTS = "cpp_events";
    private static final String EVENTS_TARGET = "cpp_events_tox";
    private static final String LABELS = "cpp_labels";
    private static final String RUNNER = "cpp-runner";
    private static final String CURSOR = "\"_ingested_at\",\"event_id\",\"request_time\"";
    private static final String SEEDED_EVENT = "00000000-0000-4000-8000-0000000000c1";

    @Autowired
    private ColumnMappingRepository columnMappings;
    @Autowired
    private AggregateRefreshService refreshService;
    @Autowired
    private SystemIdentity systemIdentity;

    @BeforeEach
    void createTarget() throws Exception {
        tearDown();
        enrichment(TARGET, USAGE, "event_id", "tox_score", "long");
        entry(TARGET, RUNNER, "\"privileges\":[\"write\"]");
    }

    @AfterEach
    void tearDown() throws Exception {
        for (final String role : List.of(RUNNER, "registry", "viewer", "cpp-x")) {
            revoke(USAGE, role);
        }
        for (final String pipeline : List.of("cpp-rollup", "cpp-unplannable", "cpp-labels", "cpp-up")) {
            asAdmin(delete("/v1/pipelines/" + pipeline));
        }
        for (final String table : List.of(TARGET, GROUP_TARGET, ROLLUP, EVENTS_TARGET, EVENTS, LABELS)) {
            asAdmin(delete("/v1/tables/" + table));
        }
        ClickHouseProbe.execute("ALTER TABLE " + USAGE + " DELETE WHERE trace_id = 'cpp-seed' SETTINGS mutations_sync = 1");
    }

    // --- authoring --------------------------------------------------------------------------------------

    @Test
    void authorCannotRegisterPipelineOverColumnHiddenFromIt() throws Exception {
        // `registry` reads globally, so an entry with no privileges and a list confines it.
        entry(USAGE, "registry", "\"columns\":[" + CURSOR + ",\"project_id\"]");
        send(post("/v1/pipelines"), registry(), enrichBody("cpp-hidden-input", TARGET, boundTo("deployment"), ""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("column_not_entitled"))
                .andExpect(jsonPath("$.message").value(containsString("'deployment'")));
        send(post("/v1/pipelines"), registry(), "{\"name\":\"cpp-hidden-sql\"," + Transforms.sql().output("tox_score", "length(deployment)").member()
                + ",\"kind\":\"enrich\",\"target\":\"" + TARGET + "\",\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("column_not_entitled"));
        asAdmin(get("/v1/pipelines/cpp-hidden-input")).andExpect(status().isNotFound());
        asAdmin(get("/v1/pipelines/cpp-hidden-sql")).andExpect(status().isNotFound());
    }

    @Test
    void hiddenColumnInFilterSignalOrPreferSqlIsRefusedNotReportedMalformed() throws Exception {
        enrichment(LABELS, USAGE, "event_id", "label", "string");
        enrichment(GROUP_TARGET, USAGE, "chat_id", "tox_score", "long");
        entry(USAGE, "registry", "\"columns\":[" + CURSOR + ",\"chat_id\",\"project_id\"]");
        // An enrichment column the list does not name, and a source column it does not name.
        for (final String filter : List.of("\\\"" + LABELS + ".label\\\" = 'x'", "deployment <> ''")) {
            send(post("/v1/pipelines"), registry(), enrichBody("cpp-hidden-filter", TARGET, boundTo("project_id"), ",\"filter\":\"" + filter + "\""))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("column_not_entitled"));
        }
        final String transform = Transforms.llm()
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"Members ({{members_included}}). {{members}}\"}]}")
                .input("pid", "project_id").proseOutputs("tox_score").member();
        for (final String trigger : List.of("\"ready_when\":{\"signal\":\"deployment <> ''\"}",
                "\"ready_when\":{\"idle\":\"10m\"},\"member_select\":{\"prefer_sql\":\"deployment <> ''\",\"limit\":5}")) {
            send(post("/v1/pipelines"), registry(), "{\"name\":\"cpp-hidden-group\"," + transform + ",\"kind\":\"enrich\",\"target\":\"" + GROUP_TARGET
                    + "\",\"trigger\":{\"kind\":\"group\",\"group_by\":\"chat_id\"," + trigger + "},\"enabled\":true}")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("column_not_entitled"));
        }
        asAdmin(get("/v1/pipelines/cpp-hidden-filter")).andExpect(status().isNotFound());
        asAdmin(get("/v1/pipelines/cpp-hidden-group")).andExpect(status().isNotFound());
    }

    @Test
    void hiddenBindingWinsOverUnentitledSensitiveBindingInEitherOrder() throws Exception {
        // `registry` reads without the clearance; its list names the sensitive body but not `deployment`.
        entry(USAGE, "registry", "\"columns\":[" + CURSOR + ",\"dial_usage_log_payload.request_body\"]");
        final String body = "dial_usage_log_payload.request_body";
        for (final List<String> columns : List.of(List.of(body, "deployment"), List.of("deployment", body))) {
            final String transform = Transforms.llm().requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"{{a}} {{b}}\"}]}")
                    .input("a", columns.get(0)).input("b", columns.get(1)).proseOutputs("tox_score").member();
            send(post("/v1/pipelines"), registry(), enrichBody("cpp-mixed", TARGET, transform, ""))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("column_not_entitled"))
                    .andExpect(jsonPath("$.message").value(containsString("'deployment'")));
        }
    }

    @Test
    void anAuthorHoldingOnlyRegistryAdminIsNotNarrowed() throws Exception {
        entry(USAGE, "cpp-x", "\"privileges\":[\"read\"],\"columns\":[\"project_id\"]");
        final String author = JwtTestTokens.token(FULL_ISSUER, AUDIENCE, Map.of("sub", "cpp-author", "roles", List.of("author"), "email", "author@test"));
        send(post("/v1/pipelines"), author, enrichBody("cpp-unnarrowed", TARGET, boundTo("deployment"), ""))
                .andExpect(status().isCreated());
    }

    // --- the runner's scan ------------------------------------------------------------------------------

    @Test
    void scanReadingHiddenColumnIsRefusedNamingIt() throws Exception {
        final String pipeline = register("cpp-scan-bound", TARGET, boundTo("deployment"), "");
        entry(USAGE, RUNNER, "\"privileges\":[\"read\"],\"columns\":[" + CURSOR + "]");
        scan(pipeline).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("column_not_entitled"))
                .andExpect(jsonPath("$.message").value(containsString("'deployment'")));

        entry(USAGE, RUNNER, "\"privileges\":[\"read\"],\"columns\":[" + CURSOR + ",\"deployment\"]");
        scan(pipeline).andExpect(status().isOk());
    }

    @Test
    void restrictedRunnersListMustNameTheCursorAndPartitionColumns() throws Exception {
        final String pipeline = register("cpp-scan-cursor", TARGET, boundTo("project_id"), "");
        entry(USAGE, RUNNER, "\"privileges\":[\"read\"],\"columns\":[\"event_id\",\"project_id\",\"request_time\"]");
        scan(pipeline).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("column_not_entitled"))
                .andExpect(jsonPath("$.message").value(containsString("'_ingested_at'")));

        entry(USAGE, RUNNER, "\"privileges\":[\"read\"],\"columns\":[\"_ingested_at\",\"event_id\",\"project_id\"]");
        scan(pipeline).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("column_not_entitled"))
                .andExpect(jsonPath("$.message").value(containsString("'request_time'")));

        entry(USAGE, RUNNER, "\"privileges\":[\"read\"],\"columns\":[" + CURSOR + ",\"project_id\"]");
        scan(pipeline).andExpect(status().isOk());
    }

    @Test
    void columnHiddenAndUnentitledSensitiveIsColumnNotEntitled() throws Exception {
        final String pipeline = register("cpp-scan-body", TARGET, boundTo("dial_usage_log_payload.request_body"), "");
        entry(USAGE, RUNNER, "\"privileges\":[\"read\"],\"columns\":[" + CURSOR + "]");
        scan(pipeline).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("column_not_entitled"));

        // Listed but still uncleared: the clearance refusal is back.
        entry(USAGE, RUNNER, "\"privileges\":[\"read\"],\"columns\":[" + CURSOR + ",\"dial_usage_log_payload.request_body\"]");
        scan(pipeline).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("sensitive_column_not_entitled"));
    }

    @Test
    void storedFilterOverHiddenColumnIsRefusedNotReportedMalformed() throws Exception {
        final String pipeline = register("cpp-scan-filter", TARGET, Transforms.llm().proseOutputs("tox_score").member(),
                ",\"filter\":\"deployment <> ''\"");
        entry(USAGE, RUNNER, "\"privileges\":[\"read\",\"sensitive\"],\"columns\":[" + CURSOR + "]");
        scan(pipeline).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("column_not_entitled"));
    }

    @Test
    void sensitivePartitionColumnNeedsTheClearanceAndClearedScanCarriesIt() throws Exception {
        ClickHouseProbe.execute("INSERT INTO " + USAGE + " (event_id, project_id, deployment, request_time, trace_id, core_span_id, _ingested_at)"
                + " VALUES (toUUID('" + SEEDED_EVENT + "'), 'cpp-p', 'cpp-d', now64(3), 'cpp-seed', 's', '2020-01-01 00:00:01.000')");
        final String pipeline = register("cpp-scan-partition", TARGET, boundTo("project_id"), "");
        final ColumnMappingEntity requestTime = columnMappings.findByTableNameOrderByOrdinal(USAGE).stream()
                .filter(column -> "request_time".equals(column.getName())).findFirst().orElseThrow();
        requestTime.setSensitive(true);
        columnMappings.save(requestTime);
        try {
            entry(USAGE, RUNNER, "\"privileges\":[\"read\"]");
            scan(pipeline).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("sensitive_column_not_entitled"));

            entry(USAGE, RUNNER, "\"privileges\":[\"read\",\"sensitive\"]");
            scan(pipeline).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].__partition_v").exists());
        } finally {
            requestTime.setSensitive(false);
            columnMappings.save(requestTime);
        }
    }

    @Test
    void columnIsListedByItsExposedNameAndJudgedByItOnTheScan() throws Exception {
        send(post("/v1/tables"), admin(), "{\"name\":\"" + EVENTS + "\",\"type\":\"source\"}").andExpect(status().isCreated());
        send(post("/v1/tables/" + EVENTS + "/schema"), admin(), "{\"columns\":[{\"source_name\":\"event_id\",\"name\":\"event_id\",\"type\":\"string\"},"
                + "{\"source_name\":\"ingested\",\"name\":\"ingest_time\",\"type\":\"timestamp\"},"
                + "{\"source_name\":\"project_id\",\"name\":\"project_id\",\"type\":\"string\"}],\"ordering_key\":[\"event_id\"],"
                // The scan metadata holds physical names.
                + "\"identity_column\":\"event_id\",\"version_column\":\"ingested\"}").andExpect(status().isOk());
        enrichment(EVENTS_TARGET, EVENTS, "event_id", "tox_score", "long");
        entry(EVENTS_TARGET, RUNNER, "\"privileges\":[\"write\"]");
        final String pipeline = register("cpp-events", EVENTS_TARGET, boundTo("project_id"), "");

        entry(EVENTS, RUNNER, "\"privileges\":[\"read\"],\"columns\":[\"event_id\",\"ingest_time\",\"project_id\"]");
        scan(pipeline).andExpect(status().isOk());
        putEntry(EVENTS, RUNNER, "\"privileges\":[\"read\"],\"columns\":[\"event_id\",\"ingested\",\"project_id\"]")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("ingested")));

        entry(EVENTS, RUNNER, "\"privileges\":[\"read\"],\"columns\":[\"event_id\",\"project_id\"]");
        scan(pipeline).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("column_not_entitled"))
                .andExpect(jsonPath("$.message").value(containsString("'ingest_time'")))
                .andExpect(jsonPath("$.message").value(not(containsString("'ingested'"))));

        // A rename follows the exposed name into the list.
        entry(EVENTS, RUNNER, "\"privileges\":[\"read\"],\"columns\":[\"event_id\",\"ingest_time\",\"project_id\"]");
        send(patch("/v1/tables/" + EVENTS + "/schema"), admin(), "{\"rename\":[{\"from\":\"ingest_time\",\"to\":\"loaded_at\"}]}")
                .andExpect(status().isOk());
        asAdmin(get("/v1/tables/" + EVENTS + "/access?role=" + RUNNER)).andExpect(jsonPath("$.columns", hasItem("loaded_at")))
                .andExpect(jsonPath("$.columns", not(hasItem("ingest_time"))));
    }

    // --- group assembly and materialize -----------------------------------------------------------------

    @Test
    void groupAssemblyNeedsTheVersionColumnListed() throws Exception {
        enrichment(GROUP_TARGET, USAGE, "chat_id", "tox_score", "long");
        entry(GROUP_TARGET, RUNNER, "\"privileges\":[\"write\"]");
        final String transform = Transforms.llm()
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"Members ({{members_included}}). {{members}}\"}]}")
                .input("dep", "deployment").proseOutputs("tox_score").member();
        final String pipeline = JsonPath.read(send(post("/v1/pipelines"), admin(), "{\"name\":\"cpp-group\"," + transform
                        + ",\"kind\":\"enrich\",\"target\":\"" + GROUP_TARGET + "\",\"trigger\":{\"kind\":\"group\",\"group_by\":\"chat_id\","
                        + "\"ready_when\":{\"idle\":\"10m\"}},\"enabled\":true}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.name");

        entry(USAGE, RUNNER, "\"privileges\":[\"read\"],\"columns\":[\"chat_id\",\"deployment\",\"event_id\"]");
        runner(runnerPost("/v1/pipelines/" + pipeline + "/group-rows"), "{\"group_key\":\"chat-a\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("column_not_entitled"))
                .andExpect(jsonPath("$.message").value(containsString("'_ingested_at'")));

        entry(USAGE, RUNNER, "\"privileges\":[\"read\"],\"columns\":[\"_ingested_at\",\"chat_id\",\"deployment\",\"event_id\"]");
        runner(runnerPost("/v1/pipelines/" + pipeline + "/group-rows"), "{\"group_key\":\"chat-a\"}")
                .andExpect(status().isOk());
    }

    @Test
    void materializeNeedsItsCursorColumnsAndItsInputsListed() throws Exception {
        final String pipeline = JsonPath.read(send(post("/v1/pipelines"), admin(), "{\"name\":\"cpp-materialize\","
                        + Transforms.sql().output("tox_score", "length(project_id)").member()
                        + ",\"kind\":\"enrich\",\"target\":\"" + TARGET + "\",\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.name");

        entry(USAGE, RUNNER, "\"privileges\":[\"read\"],\"columns\":[\"event_id\",\"project_id\",\"request_time\"]");
        materialize(pipeline).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("column_not_entitled"))
                .andExpect(jsonPath("$.message").value(containsString("'_ingested_at'")));

        entry(USAGE, RUNNER, "\"privileges\":[\"read\"],\"columns\":[" + CURSOR + "]");
        materialize(pipeline).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("column_not_entitled"));

        entry(USAGE, RUNNER, "\"privileges\":[\"read\"],\"columns\":[" + CURSOR + ",\"project_id\"]");
        materialize(pipeline).andExpect(status().isOk()).andExpect(jsonPath("$.rows_written").exists());
    }

    @Test
    void sensitiveVersionColumnNeedsTheClearanceToMaterialize() throws Exception {
        final String pipeline = JsonPath.read(send(post("/v1/pipelines"), admin(), "{\"name\":\"cpp-materialize-version\","
                        + Transforms.sql().output("tox_score", "length(project_id)").member()
                        + ",\"kind\":\"enrich\",\"target\":\"" + TARGET + "\",\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.name");
        final ColumnMappingEntity version = columnMappings.findByTableNameOrderByOrdinal(USAGE).stream()
                .filter(column -> "_ingested_at".equals(column.getName())).findFirst().orElseThrow();
        version.setSensitive(true);
        columnMappings.save(version);
        try {
            entry(USAGE, RUNNER, "\"privileges\":[\"read\"]");
            materialize(pipeline).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("sensitive_column_not_entitled"))
                    .andExpect(jsonPath("$.message").value(containsString("_ingested_at")));

            entry(USAGE, RUNNER, "\"privileges\":[\"read\",\"sensitive\"]");
            materialize(pipeline).andExpect(status().isOk());
        } finally {
            version.setSensitive(false);
            columnMappings.save(version);
        }
    }

    /**
     * A {@code sql} preview reading a lagging enrichment keeps working for a caller from whom the version column is
     * hidden, and its note gives the gap without the newest version or the materialized position.
     */
    @Test
    void sqlPreviewNoteOmitsTheInstantsWhenTheVersionColumnIsHidden() throws Exception {
        for (final String at : List.of("2020-01-01 00:00:01.000", "2020-01-01 00:00:02.000")) {
            ClickHouseProbe.execute("INSERT INTO " + USAGE + " (event_id, project_id, deployment, request_time, trace_id, core_span_id, _ingested_at)"
                    + " VALUES (generateUUIDv4(), 'cpp-p', 'cpp-d', now64(3), 'cpp-seed', 's', '" + at + "')");
        }
        // The upstream materializes one row, so its position is behind the newest input.
        register("cpp-up", TARGET, Transforms.sql().output("tox_score", "length(project_id)").member(), "");
        entry(USAGE, RUNNER, "\"privileges\":[\"read\"]");
        runner(runnerPost("/v1/pipelines/cpp-up/materialize"), "{\"limit\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.rows_written").value(1));
        enrichment(LABELS, USAGE, "event_id", "label", "long");
        entry(LABELS, "registry", "\"privileges\":[\"write\"]");
        // Disabled, so the preview runs the whole gate as the caller.
        send(post("/v1/pipelines"), admin(), "{\"name\":\"cpp-labels\"," + Transforms.sql().output("label", "length(project_id)").member()
                + ",\"kind\":\"enrich\",\"target\":\"" + LABELS + "\",\"trigger\":{\"kind\":\"schedule\"},\"enabled\":false,"
                // Matches the rows the upstream has not reached too, whose tox_score reads as NULL.
                + "\"filter\":\"\\\"" + TARGET + ".tox_score\\\" IS NULL OR \\\"" + TARGET + ".tox_score\\\" >= 0\"}").andExpect(status().isCreated());
        final String isoInstant = ".*\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d.*";
        final String behind = "$.notes[?(@.code == 'enrichment_behind')]";

        entry(USAGE, "registry", "\"columns\":[\"event_id\",\"project_id\",\"request_time\",\"" + TARGET + ".tox_score\"]");
        send(post("/v1/pipelines/cpp-labels/preview"), registry(), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath(behind + ".subject", contains(TARGET)))
                .andExpect(jsonPath(behind + ".message", contains(allOf(containsString("materialized position of enrichment '" + TARGET + "'"),
                        not(matchesPattern(isoInstant))))));

        entry(USAGE, "registry", "\"columns\":[" + CURSOR + ",\"project_id\",\"" + TARGET + ".tox_score\"]");
        send(post("/v1/pipelines/cpp-labels/preview"), registry(), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath(behind + ".message", contains(matchesPattern(isoInstant))));
    }

    // --- aggregates -------------------------------------------------------------------------------------

    @Test
    void aggregateOfHiddenColumnIsNotHiddenAndTheScheduledRefreshReadsIt() throws Exception {
        ClickHouseProbe.execute("INSERT INTO " + USAGE + " (event_id, project_id, deployment, user_hash, request_time, trace_id, core_span_id,"
                + " _ingested_at) VALUES (toUUID('" + SEEDED_EVENT + "'), 'cpp-p', 'cpp-dep', 'cpp-user', '2026-01-01 00:00:00.000',"
                + " 'cpp-seed', 's', '2020-01-01 00:00:01.000')");
        rollup("SELECT deployment, count(DISTINCT user_hash) FROM dial_usage_log GROUP BY 1", true);
        // `viewer` reads globally; its entry hides user_hash on the source.
        entry(USAGE, "viewer", "\"columns\":[\"deployment\",\"project_id\"]");

        // The scheduler's identity is not narrowed by any column policy.
        systemIdentity.runAsSystem(() -> refreshService.refresh("cpp-rollup"));
        sql(admin(), "SELECT users FROM " + ROLLUP + " WHERE deployment = 'cpp-dep'").andExpect(jsonPath("$.rows[0].users").value(1));

        final String viewer = JwtTestTokens.token(READONLY_ISSUER, AUDIENCE, Map.of("sub", "cpp-viewer", "roles", List.of("viewer"), "email", "v@test"));
        sql(viewer, "SELECT user_hash FROM dial_usage_log LIMIT 1").andExpect(status().isBadRequest());
        sql(viewer, "SELECT * FROM " + ROLLUP + " WHERE deployment = 'cpp-dep'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].users").value(1));
    }

    @Test
    void unplannableAggregateStatementIsRefusedOnlyForColumnItMentions() throws Exception {
        entry(USAGE, "registry", "\"columns\":[" + CURSOR + ",\"deployment\",\"project_id\"]");
        rollup("SELECT deployment, sum(user_hash) FROM dial_usage_log GROUP BY 1", false);
        send(post("/v1/pipelines/cpp-rollup/preview"), registry(), "")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("column_not_entitled"))
                .andExpect(jsonPath("$.message").value(containsString("user_hash")));

        // Unplannable over columns the author sees: the planning failure, for a restricted author too.
        asAdmin(delete("/v1/pipelines/cpp-rollup")).andExpect(status().isNoContent());
        rollup("SELECT deployment, sum(project_id) FROM dial_usage_log GROUP BY 1", false);
        send(post("/v1/pipelines/cpp-rollup/preview"), registry(), "").andExpect(status().isUnprocessableContent());
    }

    @Test
    void lagSecondsIsReturnedWhenTheVersionColumnIsHidden() throws Exception {
        // A row to refresh over, so the run advances the cursor that GET derives lag_seconds from.
        ClickHouseProbe.execute("INSERT INTO " + USAGE + " (event_id, project_id, deployment, user_hash, request_time, trace_id, core_span_id,"
                + " _ingested_at) VALUES (toUUID('" + SEEDED_EVENT + "'), 'cpp-p', 'cpp-dep', 'cpp-user', '2026-01-01 00:00:00.000',"
                + " 'cpp-seed', 's', '2020-01-01 00:00:01.000')");
        rollup("SELECT deployment, count(DISTINCT user_hash) FROM dial_usage_log GROUP BY 1", true);
        entry(USAGE, "registry", "\"columns\":[\"deployment\",\"event_id\",\"request_time\",\"user_hash\"]");
        entry(ROLLUP, "registry", "\"privileges\":[\"write\"]");

        send(post("/v1/pipelines/cpp-rollup/refresh"), registry(), "").andExpect(status().isOk()).andExpect(jsonPath("$.lag_seconds").exists());
        // The registry read is REGISTRY_READ's; `exporter` adds it and governs no table, so the list still confines.
        final String reader = JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "cpp-registry", "roles", List.of("registry", "exporter"), "email", "registry@test"));
        mockMvc.perform(get("/v1/pipelines/cpp-rollup").header(HttpHeaders.AUTHORIZATION, bearer(reader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..lag_seconds", hasItem(org.hamcrest.Matchers.notNullValue())));
    }

    // --- helpers --------------------------------------------------------------------------------------

    /** An aggregate {@code cpp-rollup} writing {@code (deployment, users)} into {@link #ROLLUP} from {@code select}. */
    private void rollup(String select, boolean armed) throws Exception {
        if (asAdmin(get("/v1/tables/" + ROLLUP)).andReturn().getResponse().getStatus() == 404) {
            send(post("/v1/tables"), admin(), "{\"name\":\"" + ROLLUP + "\",\"type\":\"source\",\"write\":\"upsert_by_key\"}").andExpect(status().isCreated());
            send(post("/v1/tables/" + ROLLUP + "/schema"), admin(), "{\"columns\":[{\"source_name\":\"deployment\",\"name\":\"deployment\",\"type\":\"string\"},"
                    + "{\"source_name\":\"users\",\"name\":\"users\",\"type\":\"long\"}],\"ordering_key\":[\"deployment\"]}").andExpect(status().isOk());
        }
        send(post("/v1/pipelines"), admin(), "{\"name\":\"cpp-rollup\",\"kind\":\"aggregate\",\"target\":\"" + ROLLUP + "\","
                + "\"sql\":\"INSERT INTO " + ROLLUP + " (deployment, users) " + select + "\","
                + "\"trigger\":{\"kind\":\"schedule\",\"cron\":\"0 0 * * * *\"}}").andExpect(status().isCreated());
        if (armed) {
            send(patch("/v1/pipelines/cpp-rollup"), admin(), "{\"enabled\":true}").andExpect(status().isOk());
        }
    }

    private String register(String name, String target, String transform, String extra) throws Exception {
        return JsonPath.read(send(post("/v1/pipelines"), admin(), enrichBody(name, target, transform, extra))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.name");
    }

    private static String enrichBody(String name, String target, String transform, String extra) {
        return "{\"name\":\"" + name + "\"," + transform + ",\"kind\":\"enrich\",\"target\":\"" + target + "\","
                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true" + extra + "}";
    }

    /** A row-grain {@code llm} transform binding {@code column} and writing {@code tox_score}. */
    private static String boundTo(String column) {
        return Transforms.llm().requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"{{value}}\"}]}")
                .input("value", column).proseOutputs("tox_score").member();
    }

    private void enrichment(String name, String source, String grainKey, String column, String type) throws Exception {
        send(post("/v1/tables"), admin(), "{\"name\":\"" + name + "\",\"type\":\"enrichment\",\"source_table\":\"" + source + "\"}")
                .andExpect(status().isCreated());
        send(post("/v1/tables/" + name + "/schema"), admin(), "{\"grain_key\":\"" + grainKey + "\",\"columns\":[{\"source_name\":\""
                + column + "\",\"name\":\"" + column + "\",\"type\":\"" + type + "\"}]}").andExpect(status().isOk());
    }

    private ResultActions scan(String pipeline) throws Exception {
        return runner(runnerPost("/v1/pipelines/" + pipeline + "/scan"), "{\"limit\":5}");
    }

    private ResultActions materialize(String pipeline) throws Exception {
        return runner(runnerPost("/v1/pipelines/" + pipeline + "/materialize"), "{\"limit\":5}");
    }

    private ResultActions runner(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(runnerToken())).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions sql(String token, String statement) throws Exception {
        return send(post("/v1/queries/execute-sql"), token, "{\"sql\":\"" + statement + "\"}");
    }

    /** Replaces {@code role}'s whole entry on {@code table}, asserted. */
    private void entry(String table, String role, String members) throws Exception {
        putEntry(table, role, members).andExpect(status().isOk());
    }

    private ResultActions putEntry(String table, String role, String members) throws Exception {
        final String access = asAdmin(get("/v1/tables/" + table + "/access")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return send(put("/v1/tables/" + table + "/access?role=" + role), admin(),
                "{\"generation\":" + JsonPath.read(access, "$.generation") + "," + members + "}");
    }

    /** Removes {@code role}'s entry on {@code table} when it has one, asserted, so no entry leaks into the next test. */
    private void revoke(String table, String role) throws Exception {
        final Map<String, Object> roles = JsonPath.read(asAdmin(get("/v1/tables/" + table + "/access")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.roles");
        if (roles.containsKey(role)) {
            asAdmin(delete("/v1/tables/" + table + "/access?role=" + role)).andExpect(status().isNoContent());
        }
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions asAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(admin())));
    }

    private static String admin() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE, Map.of("sub", "cpp-admin", "roles", List.of("admin"), "email", "admin@test"));
    }

    private static String registry() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE, Map.of("sub", "cpp-registry", "roles", List.of("registry"), "email", "registry@test"));
    }

    private static String runnerToken() {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE, Map.of("sub", "cpp-runner-user", "roles", List.of(RUNNER), "email", "runner@test"));
    }
}
