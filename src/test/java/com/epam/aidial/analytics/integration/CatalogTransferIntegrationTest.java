package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Catalog export and import end to end, in security mode {@code none}, on one environment playing both roles:
 * a fixture is built, exported, deleted again together with its audit history (so the target has never seen
 * it), and imported.
 *
 * <p>The fixture is a source, an enrichment of it, a sensitive enrichment of {@code dial_usage_log} with grants
 * on both, an aggregate target, two enrich pipelines and an aggregate pipeline whose only reference to the
 * enrichment is inside its filter and group key.
 */
class CatalogTransferIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String BASE = "cx_base";
    private static final String SENT = "cx_sent";
    private static final String DUL = "cx_dul";
    private static final String ROLL = "cx_roll";
    private static final String SENT_LIVE = "cx-sent-live";
    private static final String DUL_LIVE = "cx-dul-live";
    private static final String ROLLUP = "cx-rollup";
    private static final String SYSTEM = "dial_usage_log";
    private static final String READERS = "cx-readers";
    private static final List<String> TABLES = List.of(DUL, SENT, ROLL, BASE);
    private static final List<String> PIPELINES = List.of(ROLLUP, SENT_LIVE, DUL_LIVE);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JsonMapper json;

    private String systemAccess;

    @BeforeEach
    void buildFixture() throws Exception {
        forget();
        systemAccess = mockMvc.perform(get("/v1/tables/" + SYSTEM + "/access")).andReturn().getResponse()
                .getContentAsString();
        IntegrationTables.source(mockMvc, BASE, """
                [{"source_name":"event_id","name":"event_id","type":"string","description":"row id","tag":"ids"},
                 {"source_name":"label","name":"label","type":"string","display_name":"Label"},
                 {"source_name":"score","name":"score","type":"long"},
                 {"source_name":"at","name":"at","type":"timestamp"}]""", "[\"event_id\"]", "event_id", "at");
        IntegrationTables.enrichment(mockMvc, SENT, BASE, "event_id",
                "[{\"source_name\":\"sentiment\",\"name\":\"sentiment\",\"type\":\"string\"}]");
        IntegrationTables.enrichment(mockMvc, DUL, SYSTEM, "event_id",
                "[{\"source_name\":\"note\",\"name\":\"note\",\"type\":\"string\",\"sensitive\":true}]");
        IntegrationTables.upsertSource(mockMvc, ROLL, """
                [{"source_name":"sentiment","name":"sentiment","type":"string"},
                 {"source_name":"total","name":"total","type":"long","nullable":true},
                 {"source_name":"events","name":"events","type":"long"}]""", "[\"sentiment\"]");
        putAccess(BASE, "{\"read\":[\"" + READERS + "\"]}");
        putAccess(SYSTEM, "{\"read\":[\"" + READERS + "\"],\"sensitive\":[\"" + READERS + "\"]}");
        putAccess(DUL, "{\"read\":[\"" + READERS + "\"],\"sensitive\":[\"" + READERS + "\"]}");
        createPipeline("{\"name\":\"" + SENT_LIVE + "\",\"kind\":\"enrich\",\"target\":\"" + SENT + "\","
                + Transforms.sql().output("sentiment", "lower('POS')").member()
                + ",\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}");
        createPipeline("{\"name\":\"" + DUL_LIVE + "\",\"kind\":\"enrich\",\"target\":\"" + DUL + "\","
                + Transforms.sql().output("note", "lower('X')").member() + ",\"trigger\":{\"kind\":\"schedule\"}}");
        createPipeline("""
                {"name":"%s","kind":"aggregate","target":"%s","inputs":["%s"],
                 "filter":"length(\\"%s.sentiment\\") > 0",
                 "group_by":[{"column":"%s.sentiment","as":"sentiment"}],
                 "measures":[{"name":"total","fn":"sum","column":"score"},{"name":"events","fn":"count"}],
                 "trigger":{"kind":"schedule","cron":"0 0 * * * *"}}""".formatted(ROLLUP, ROLL, BASE, SENT, SENT));
    }

    @AfterEach
    void cleanUp() throws Exception {
        forget();
        mockMvc.perform(put("/v1/tables/" + SYSTEM + "/access").contentType(MediaType.APPLICATION_JSON)
                .content(accessBody(systemAccess)));
    }

    // --- 8.1 fresh promotion ---------------------------------------------------------------------------

    @Test
    void freshPromotionRecreatesEverythingDisabledAndArmable() throws Exception {
        final String bundle = exportFixture();
        final JsonNode exported = json.readTree(bundle);
        assertThat(names(exported.get("tables"), "table")).containsExactlyInAnyOrder(BASE, ROLL, SENT, DUL);
        assertThat(names(exported.get("pipelines"), "pipeline")).containsExactlyInAnyOrder(SENT_LIVE, DUL_LIVE, ROLLUP);
        assertThat(exported.get("required_system_tables").get(0).asString()).isEqualTo(SYSTEM);
        assertThat(exported.get("format").asString()).isEqualTo("adas-catalog");
        final Map<String, JsonNode> tablesBefore = reads("/v1/tables/", TABLES);
        final Map<String, JsonNode> pipelinesBefore = reads("/v1/pipelines/", PIPELINES);

        forget();
        final long revisionsBefore = revisions();

        importCall("/v1/catalog/import/preview", bundle, null, "FAIL_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[*].import_action", everyItem(is("CREATE"))))
                .andExpect(jsonPath("$.pipelines[*].import_action", everyItem(is("CREATE"))))
                .andExpect(jsonPath("$.pipelines[*].armable", everyItem(is(true))))
                .andExpect(jsonPath("$.required_system_tables[0].import_action").value("SKIP"))
                .andExpect(jsonPath("$.validation_errors").isEmpty());
        for (final String table : TABLES) {
            mockMvc.perform(get("/v1/tables/" + table)).andExpect(status().isNotFound());
        }
        assertThat(queryScalar("SELECT count() AS n FROM system.tables WHERE name IN ('cx_base','cx_sent','cx_dul','cx_roll')",
                "n")).isEqualTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pipeline WHERE name IN (?, ?, ?)", Long.class,
                ROLLUP, SENT_LIVE, DUL_LIVE)).isZero();
        assertThat(revisions()).isEqualTo(revisionsBefore);

        final int lastRevisionBefore = lastRevision();
        final String importId = importId(importCall("/v1/catalog/import", bundle, null, "FAIL_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("completed"))
                .andExpect(jsonPath("$.tables[*].status", everyItem(is("created"))))
                .andExpect(jsonPath("$.pipelines[*].status", everyItem(is("created")))));
        assertImportTaggedEverythingItWrote(importId, lastRevisionBefore);

        assertThat(reads("/v1/tables/", TABLES)).isEqualTo(tablesBefore);
        final Map<String, JsonNode> pipelinesAfter = reads("/v1/pipelines/", PIPELINES);
        for (final String pipeline : PIPELINES) {
            assertThat(pipelinesAfter.get(pipeline).get("enabled").asBoolean()).isFalse();
            assertThat(declaration(pipelinesAfter.get(pipeline))).isEqualTo(declaration(pipelinesBefore.get(pipeline)));
        }
        assertThat(json.readTree(access(SYSTEM))).isEqualTo(json.readTree(
                "{\"get\":[],\"read\":[\"" + READERS + "\"],\"sensitive\":[\"" + READERS + "\"],\"write\":[],\"modify\":[]}"));
        for (final String pipeline : List.of(SENT_LIVE, DUL_LIVE, ROLLUP)) {
            mockMvc.perform(patch("/v1/pipelines/" + pipeline).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true));
        }
    }

    // --- 8.2 second promotion ----------------------------------------------------------------------------

    @Test
    void secondPromotionConflictsSkipsAndCreatesOnlyWhatIsNew() throws Exception {
        final String bundle = exportFixture();

        importCall("/v1/catalog/import", bundle, null, "FAIL_IF_EXISTS")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("catalog_import_conflict"));

        final long revisions = revisions();
        importCall("/v1/catalog/import", bundle, null, "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[*].import_action", everyItem(is("SKIP"))))
                .andExpect(jsonPath("$.tables[*].differs", everyItem(is(false))))
                .andExpect(jsonPath("$.pipelines[*].import_action", everyItem(is("SKIP"))))
                .andExpect(jsonPath("$.pipelines[*].differs", everyItem(is(false))));
        assertThat(revisions()).isEqualTo(revisions);

        deletePipeline(DUL_LIVE);
        purgeAudit(DUL_LIVE);
        importCall("/v1/catalog/import", bundle, null, "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[?(@.name == '" + DUL_LIVE + "')].import_action").value(hasItem("CREATE")))
                .andExpect(jsonPath("$.pipelines[?(@.import_action == 'CREATE')].name").value(hasItem(DUL_LIVE)))
                .andExpect(jsonPath("$.pipelines[?(@.name != '" + DUL_LIVE + "')].import_action",
                        everyItem(is("SKIP"))));
        mockMvc.perform(get("/v1/pipelines/" + DUL_LIVE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        // A column the pipeline needs is gone from the target it is validated against.
        deletePipeline(ROLLUP);
        purgeAudit(ROLLUP);
        mockMvc.perform(patch("/v1/tables/" + ROLL + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"drop\":[\"events\"]}"))
                .andExpect(status().isOk());
        importCall("/v1/catalog/import/preview", bundle, null, "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name == '" + ROLL + "')].differs").value(hasItem(true)))
                .andExpect(jsonPath("$.tables[?(@.name == '" + ROLL + "')].prev").exists())
                .andExpect(jsonPath("$.pipelines[?(@.name == '" + ROLLUP + "')].import_action").value(hasItem("CREATE")))
                .andExpect(jsonPath("$.pipelines[?(@.name == '" + ROLLUP + "')].armable").value(hasItem(false)))
                .andExpect(jsonPath("$.pipelines[?(@.name == '" + ROLLUP + "')].arm_problems[*]")
                        .value(hasItem(containsString("events"))));
        importCall("/v1/catalog/import/preview", bundle, null, "FAIL_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name == '" + ROLL + "')].differs").value(hasItem(true)))
                .andExpect(jsonPath("$.tables[?(@.name == '" + ROLL + "')].next").exists());
    }

    // --- 8.3 policy and OTLP ------------------------------------------------------------------------------

    @Test
    void overwriteAndMissingPolicyAreRefused() throws Exception {
        final String bundle = exportFixture();
        for (final String path : List.of("/v1/catalog/import", "/v1/catalog/import/preview")) {
            importCall(path, bundle, null, "OVERWRITE")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("resolution_policy_unsupported"));
            importCall(path, bundle, null, null).andExpect(status().isBadRequest());
        }
    }

    @Test
    void otlpLandingTablesAreLeftOutAndRefusedWhenSelected() throws Exception {
        mockMvc.perform(post("/v1/otel/sinks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cxapp\",\"signals\":[\"logs\"]}"))
                .andExpect(status().isCreated());
        try {
            mockMvc.perform(post("/v1/catalog/export").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("adas-catalog-")))
                    .andExpect(jsonPath("$.tables[*].table.name", not(hasItem("otel_cxapp_logs"))))
                    .andExpect(jsonPath("$.tables[*].table.name", hasItem(BASE)))
                    .andExpect(jsonPath("$.skipped[?(@.name == 'otel_cxapp_logs')].reason").value(hasItem("otlp")));
            mockMvc.perform(post("/v1/catalog/export").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"components\":[{\"type\":\"table\",\"name\":\"otel_cxapp_logs\"}]}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("catalog_export_invalid"));
        } finally {
            mockMvc.perform(delete("/v1/otel/sinks/cxapp"));
        }
    }

    @Test
    void exportSelectionErrorsAndPreview() throws Exception {
        mockMvc.perform(post("/v1/catalog/export/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[{\"type\":\"table\",\"name\":\"" + SENT + "\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects[?(@.name == '" + SENT + "')].reason").value(hasItem("selected")))
                .andExpect(jsonPath("$.objects[?(@.name == '" + BASE + "')].reason")
                        .value(hasItem("source_table of " + SENT)))
                .andExpect(jsonPath("$.objects[?(@.name == '" + SENT_LIVE + "')].reason").value(hasItem("targets " + SENT)));
        mockMvc.perform(post("/v1/catalog/export").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[{\"type\":\"pipeline\",\"name\":\"does_not_exist\"}]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("unknown_pipeline"));
        mockMvc.perform(post("/v1/catalog/export").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[{\"type\":\"saved_query\",\"name\":\"q\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    // --- 8.4 failures ----------------------------------------------------------------------------------

    @Test
    void ddlFailureOnTheSecondTableRollsBackTheFirstAndRerunConverges() throws Exception {
        final String bundle = exportTables(BASE, SENT);
        forget();
        // A ClickHouse table the catalog does not know makes the second CREATE TABLE fail.
        execute("CREATE TABLE " + SENT + " (x UInt8) ENGINE = Memory");

        final int lastRevisionBefore = lastRevision();
        final String rolledBack = importId(importCall("/v1/catalog/import", bundle, null, "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("rolled_back"))
                .andExpect(jsonPath("$.tables[?(@.name == '" + BASE + "')].status").value(hasItem("rolled_back")))
                .andExpect(jsonPath("$.tables[?(@.name == '" + SENT + "')].status").value(hasItem("failed"))));
        mockMvc.perform(get("/v1/tables/" + BASE)).andExpect(status().isNotFound());
        assertThat(queryScalar("SELECT count() AS n FROM system.tables WHERE name = '" + BASE + "'", "n")).isEqualTo("0");
        // The compensation delete is part of the import: it carries the same id as the create it undoes.
        final JsonNode baseActivities = filtered(importActivities(rolledBack), "Table", BASE);
        assertThat(types(baseActivities)).contains("Create", "Delete");
        assertImportTaggedEverythingItWrote(rolledBack, lastRevisionBefore);

        execute("DROP TABLE IF EXISTS " + SENT);
        final int lastRevisionBeforeRerun = lastRevision();
        final String rerun = importId(importCall("/v1/catalog/import", bundle, null, "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("completed")));
        assertThat(rerun).isNotEqualTo(rolledBack);
        assertImportTaggedEverythingItWrote(rerun, lastRevisionBeforeRerun);
        mockMvc.perform(get("/v1/tables/" + BASE)).andExpect(status().isOk());
        mockMvc.perform(get("/v1/tables/" + SENT)).andExpect(status().isOk());
    }

    @Test
    void reusedPipelineNameNeedsAcknowledgement() throws Exception {
        final String bundle = exportFixture();
        // Only a name that was once enabled counts: ROLLUP never ran, so its deletion leaves nothing behind.
        mockMvc.perform(patch("/v1/pipelines/" + DUL_LIVE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isOk());
        deletePipeline(DUL_LIVE);
        deletePipeline(ROLLUP);

        importCall("/v1/catalog/import/preview", bundle, null, "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[?(@.name == '" + DUL_LIVE + "')].reused_name").value(hasItem(true)))
                .andExpect(jsonPath("$.pipelines[?(@.name == '" + ROLLUP + "')].reused_name").value(hasItem(false)));
        importCall("/v1/catalog/import", bundle, null, "SKIP_IF_EXISTS")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("catalog_import_invalid"))
                .andExpect(jsonPath("$.message").value(containsString("/import/preview")));
        mockMvc.perform(get("/v1/pipelines/" + DUL_LIVE)).andExpect(status().isNotFound());

        importCall("/v1/catalog/import", bundle, Map.of("acknowledge_reused_names", "true"), "SKIP_IF_EXISTS")
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/pipelines/" + DUL_LIVE)).andExpect(status().isOk());
    }

    // --- 8.5 parsing ------------------------------------------------------------------------------------

    @Test
    void malformedBundlesAndPartsAreRefused() throws Exception {
        final ObjectNode bundle = (ObjectNode) json.readTree(exportFixture());

        final ObjectNode unknownNested = bundle.deepCopy();
        ((ObjectNode) pipeline(unknownNested, SENT_LIVE).get("transform")).put("temprature", 1);
        importCall("/v1/catalog/import/preview", unknownNested.toString(), null, "SKIP_IF_EXISTS")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("temprature")));

        final ObjectNode blankName = bundle.deepCopy();
        ((ObjectNode) blankName.get("tables").get(0).get("table")).put("name", " ");
        importCall("/v1/catalog/import/preview", blankName.toString(), null, "SKIP_IF_EXISTS")
                .andExpect(status().isBadRequest());

        assertThat(bundle.get("format_version").asInt()).isEqualTo(2);
        final ObjectNode newer = bundle.deepCopy();
        newer.put("format_version", 3);
        newer.put("added_in_v3", true);
        importCall("/v1/catalog/import", newer.toString(), null, "SKIP_IF_EXISTS")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("catalog_bundle_version_unsupported"))
                .andExpect(jsonPath("$.message").value(containsString("format_version 3")));

        // A version-1 bundle — the same shape without `sql` — still reads, and plans as before.
        final ObjectNode older = bundle.deepCopy();
        older.put("format_version", 1);
        importCall("/v1/catalog/import/preview", older.toString(), null, "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validation_errors").isEmpty());

        final ObjectNode wrongFormat = bundle.deepCopy();
        wrongFormat.put("format", "dm-config");
        importCall("/v1/catalog/import", wrongFormat.toString(), null, "SKIP_IF_EXISTS")
                .andExpect(status().isBadRequest());

        // Spring's own conversion failure, with no authored message: the generic sentence, never the detail.
        importCall("/v1/catalog/import", bundle.toString(), Map.of("acknowledge_reused_names", "maybe"), "SKIP_IF_EXISTS")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("The request could not be processed; check the request body, parameters, and content type"));

        mockMvc.perform(multipart("/v1/catalog/import").param("resolution_policy", "SKIP_IF_EXISTS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("file")));

        final byte[] oversized = new byte[21 * 1024 * 1024];
        mockMvc.perform(multipart("/v1/catalog/import")
                        .file(new MockMultipartFile("file", "big.json", MediaType.APPLICATION_JSON_VALUE, oversized))
                        .param("resolution_policy", "SKIP_IF_EXISTS"))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.error").value("payload_too_large"));
    }

    @Test
    void grantsNeverTravel() throws Exception {
        final String bundle = exportTables(DUL);
        assertThat(json.readTree(bundle).get("tables").get(0).has("access")).isFalse();
        assertThat(json.readTree(bundle).has("access_omitted")).isFalse();
        forget();
        final String systemBefore = access(SYSTEM);

        importCall("/v1/catalog/import", bundle, null, "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name == '" + DUL + "')].status").value(hasItem("created")));

        assertThat(json.readTree(access(DUL))).isEqualTo(json.readTree(
                "{\"get\":[],\"read\":[],\"sensitive\":[],\"write\":[],\"modify\":[]}"));
        assertThat(access(SYSTEM)).isEqualTo(systemBefore);
    }

    @Test
    void pendingTableIsSkippedWithEverythingThatNeedsItAndRefusedWhenSelected() throws Exception {
        // A table back in PENDING, with an enrichment and three pipelines already built on it.
        jdbc.update("UPDATE table_definition SET status = 'PENDING' WHERE name = ?", BASE);
        try {
            mockMvc.perform(post("/v1/catalog/export").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tables[*].table.name", not(hasItem(BASE))))
                    .andExpect(jsonPath("$.tables[*].table.name", not(hasItem(SENT))))
                    .andExpect(jsonPath("$.pipelines[*].pipeline.name", not(hasItem(SENT_LIVE))))
                    .andExpect(jsonPath("$.tables[*].table.name", hasItem(DUL)))
                    .andExpect(jsonPath("$.skipped[?(@.name == '" + BASE + "')].reason").value(hasItem("pending")))
                    .andExpect(jsonPath("$.skipped[?(@.name == '" + SENT + "')].reason")
                            .value(hasItem("dependency_skipped")))
                    .andExpect(jsonPath("$.skipped[?(@.name == '" + SENT_LIVE + "')].reason")
                            .value(hasItem("dependency_skipped")))
                    .andExpect(jsonPath("$.skipped[?(@.name == '" + ROLLUP + "')].reason")
                            .value(hasItem("dependency_skipped")));
            mockMvc.perform(post("/v1/catalog/export").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"components\":[{\"type\":\"table\",\"name\":\"" + BASE + "\"}]}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("catalog_export_invalid"))
                    .andExpect(jsonPath("$.message").value(containsString("PENDING")));
        } finally {
            jdbc.update("UPDATE table_definition SET status = 'ACTIVE' WHERE name = ?", BASE);
        }
    }

    @Test
    void systemTableSelectionAndNullComponentAreRefused() throws Exception {
        mockMvc.perform(post("/v1/catalog/export").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[{\"type\":\"table\",\"name\":\"" + SYSTEM + "\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("catalog_export_invalid"))
                .andExpect(jsonPath("$.message").value(containsString("system table")));
        mockMvc.perform(post("/v1/catalog/export/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[null]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void enrichmentReadOnlyInTheFilterIsPulledIntoTheClosure() throws Exception {
        final String target = "cx_filter_roll";
        final String pipeline = "cx-filter-rollup";
        IntegrationTables.upsertSource(mockMvc, target, """
                [{"source_name":"label","name":"label","type":"string"},
                 {"source_name":"events","name":"events","type":"long"}]""", "[\"label\"]");
        try {
            createPipeline("""
                    {"name":"%s","kind":"aggregate","target":"%s","inputs":["%s"],
                     "filter":"length(\\"%s.sentiment\\") > 0",
                     "group_by":[{"column":"label","as":"label"}],
                     "measures":[{"name":"events","fn":"count"}],
                     "trigger":{"kind":"schedule","cron":"0 0 * * * *"}}""".formatted(pipeline, target, BASE, SENT));
            mockMvc.perform(post("/v1/catalog/export/preview").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"components\":[{\"type\":\"pipeline\",\"name\":\"" + pipeline + "\"}]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.objects[?(@.name == '" + SENT + "')].reason")
                            .value(hasItem("read by " + pipeline)));
        } finally {
            mockMvc.perform(delete("/v1/pipelines/" + pipeline));
            mockMvc.perform(delete("/v1/tables/" + target));
            purgeAudit(pipeline);
            jdbc.update("DELETE FROM column_mapping_aud WHERE table_name = ?", target);
            jdbc.update("DELETE FROM table_definition_aud WHERE name = ?", target);
        }
    }

    /** An SQL declaration with comments, a WITH clause and its author's line breaks, reading the enrichment only in-text. */
    private static final String SQL_DECLARATION = """
            -- sentiment rollup, declared as SQL
            INSERT INTO cx_sql_roll (sentiment, events)
            WITH labelled AS (
                SELECT coalesce("cx_sent.sentiment", '') AS sentiment
                FROM cx_base
            )
            SELECT sentiment, count(*)
            FROM labelled
            GROUP BY 1""";

    @Test
    void sqlDeclarationRoundTripsVerbatimAndItsStatementDrivesTheDependencies() throws Exception {
        final String target = "cx_sql_roll";
        final String pipeline = "cx-sql-rollup";
        IntegrationTables.upsertSource(mockMvc, target, """
                [{"source_name":"sentiment","name":"sentiment","type":"string"},
                 {"source_name":"events","name":"events","type":"long"}]""", "[\"sentiment\"]");
        try {
            final ObjectNode create = json.createObjectNode();
            create.put("name", pipeline);
            create.put("kind", "aggregate");
            create.put("target", target);
            create.put("sql", SQL_DECLARATION);
            create.set("trigger", json.readTree("{\"kind\":\"schedule\",\"cron\":\"0 0 * * * *\"}"));
            createPipeline(create.toString());

            final ObjectNode bundle = (ObjectNode) json.readTree(
                    export("{\"components\":[{\"type\":\"pipeline\",\"name\":\"" + pipeline + "\"}]}"));
            assertThat(bundle.get("format_version").asInt()).isEqualTo(2);
            assertThat(names(bundle.get("tables"), "table")).contains(BASE, SENT, target);
            final JsonNode bundled = pipeline(bundle, pipeline);
            assertThat(bundled.get("sql").asString()).isEqualTo(SQL_DECLARATION);
            assertThat(bundled.has("group_by") || bundled.has("measures") || bundled.has("filter")).isFalse();

            deletePipeline(pipeline);
            mockMvc.perform(delete("/v1/tables/" + target)).andExpect(status().isNoContent());
            purgeAudit(pipeline);
            forget();

            // The enrichment referenced only inside the statement is a dependency: left out, the pipeline fails.
            final ObjectNode withoutEnrichment = bundle.deepCopy();
            withoutEnrichment.set("tables", without(bundle.get("tables"), "table", SENT));
            withoutEnrichment.set("pipelines", without(bundle.get("pipelines"), "pipeline", SENT_LIVE));
            ((ObjectNode) pipeline(withoutEnrichment, pipeline)).remove("inputs");
            importCall("/v1/catalog/import/preview", withoutEnrichment.toString(), null, "FAIL_IF_EXISTS")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pipelines[?(@.name == '" + pipeline + "')].import_action").value(hasItem("FAIL")))
                    .andExpect(jsonPath("$.pipelines[?(@.name == '" + pipeline + "')].problems[*]",
                            hasItem(containsString("'" + SENT + "'"))));

            importCall("/v1/catalog/import", bundle.toString(), null, "FAIL_IF_EXISTS")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("completed"));
            mockMvc.perform(get("/v1/pipelines/" + pipeline))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sql").value(SQL_DECLARATION))
                    .andExpect(jsonPath("$.enabled").value(false))
                    .andExpect(jsonPath("$.group_by").doesNotExist())
                    .andExpect(jsonPath("$.measures").doesNotExist())
                    .andExpect(jsonPath("$.filter").doesNotExist());
        } finally {
            mockMvc.perform(delete("/v1/pipelines/" + pipeline));
            mockMvc.perform(delete("/v1/tables/" + target));
            purgeAudit(pipeline);
            jdbc.update("DELETE FROM column_mapping_aud WHERE table_name = ?", target);
            jdbc.update("DELETE FROM table_definition_aud WHERE name = ?", target);
        }
    }

    /** {@code entries} without the one whose {@code member.name} is {@code name}. */
    private ArrayNode without(JsonNode entries, String member, String name) {
        final ArrayNode kept = json.createArrayNode();
        entries.forEach(entry -> {
            if (!entry.get(member).get("name").asString().equals(name)) {
                kept.add(entry);
            }
        });
        return kept;
    }

    @Test
    void skipImportLeavesDifferingSystemGrantsByteIdentical() throws Exception {
        final String bundle = exportFixture();
        putAccess(SYSTEM, "{\"read\":[\"" + READERS + "\",\"cx-extra\"],\"sensitive\":[\"" + READERS + "\"]}");
        final String before = access(SYSTEM);

        importCall("/v1/catalog/import", bundle, null, "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("completed"))
                .andExpect(jsonPath("$.required_system_tables[0].import_action").value("SKIP"))
                .andExpect(jsonPath("$.env_specific[?(@.type == 'system_table')]").isEmpty());

        assertThat(access(SYSTEM)).isEqualTo(before);
    }

    @Test
    void missingRequiredSystemTableRefusesTheImport() throws Exception {
        final ObjectNode bundle = (ObjectNode) json.readTree(exportFixture());
        ((ArrayNode) bundle.get("required_system_tables")).add("cx_no_such_system_table");

        importCall("/v1/catalog/import/preview", bundle.toString(), null, "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required_system_tables[?(@.name == 'cx_no_such_system_table')].import_action")
                        .value(hasItem("FAIL")))
                .andExpect(jsonPath("$.required_system_tables[?(@.name == 'cx_no_such_system_table')].problems[*]")
                        .value(hasItem(containsString("does not exist"))));
        importCall("/v1/catalog/import", bundle.toString(), null, "SKIP_IF_EXISTS")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("catalog_import_invalid"));
    }

    @Test
    void newPipelineIsValidatedAgainstConflictingTables() throws Exception {
        final String bundle = exportFixture();
        deletePipeline(ROLLUP);
        purgeAudit(ROLLUP);

        importCall("/v1/catalog/import/preview", bundle, null, "FAIL_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[*].import_action", everyItem(is("FAIL"))))
                .andExpect(jsonPath("$.pipelines[?(@.name == '" + ROLLUP + "')].import_action").value(hasItem("CREATE")))
                .andExpect(jsonPath("$.pipelines[?(@.name == '" + ROLLUP + "')].armable").value(hasItem(true)));
        mockMvc.perform(get("/v1/pipelines/" + ROLLUP)).andExpect(status().isNotFound());
    }

    @Test
    void nullElementsRepeatedFieldsAndDoubleKindsAreRefused() throws Exception {
        final ObjectNode bundle = (ObjectNode) json.readTree(exportFixture());

        final ObjectNode nullTable = bundle.deepCopy();
        ((ArrayNode) nullTable.get("tables")).addNull();
        final ObjectNode nullPipeline = bundle.deepCopy();
        ((ArrayNode) nullPipeline.get("pipelines")).addNull();
        final ObjectNode nullSystemTable = bundle.deepCopy();
        ((ArrayNode) nullSystemTable.get("required_system_tables")).addNull();
        final ObjectNode nullColumn = bundle.deepCopy();
        ((ArrayNode) nullColumn.get("tables").get(0).get("schema").get("columns")).addNull();
        for (final ObjectNode malformed : List.of(nullTable, nullPipeline, nullSystemTable, nullColumn)) {
            importCall("/v1/catalog/import/preview", malformed.toString(), null, "SKIP_IF_EXISTS")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("must not")));
        }

        final ObjectNode granted = bundle.deepCopy();
        ((ObjectNode) granted.get("tables").get(0)).set("access", json.readTree("{\"read\":[\"" + READERS + "\"]}"));
        importCall("/v1/catalog/import/preview", granted.toString(), null, "SKIP_IF_EXISTS")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("tables[0].access")));

        final String repeated = "{\"format\":\"adas-catalog\"," + bundle.toString().substring(1);
        importCall("/v1/catalog/import/preview", repeated, null, "SKIP_IF_EXISTS")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("repeats")));

        final ObjectNode doubled = bundle.deepCopy();
        final ObjectNode asTable = (ObjectNode) doubled.get("tables").get(0).deepCopy();
        ((ObjectNode) asTable.get("table")).put("name", SYSTEM);
        ((ArrayNode) doubled.get("tables")).add(asTable);
        importCall("/v1/catalog/import/preview", doubled.toString(), null, "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validation_errors[*]").value(hasItem(containsString("both as a table"))));
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private String exportFixture() throws Exception {
        return export("{\"components\":[{\"type\":\"table\",\"name\":\"" + ROLL + "\"},"
                + "{\"type\":\"table\",\"name\":\"" + DUL + "\"}]}");
    }

    private String exportTables(String... tables) throws Exception {
        final List<String> components = new ArrayList<>();
        for (final String table : tables) {
            components.add("{\"type\":\"table\",\"name\":\"" + table + "\"}");
        }
        // Only the named tables: the rest of their closure is removed from the bundle, so the import under test
        // creates those tables alone.
        final ObjectNode bundle = (ObjectNode) json.readTree(export("{\"components\":[" + String.join(",", components)
                + "]}"));
        bundle.set("pipelines", json.createArrayNode());
        final ArrayNode tablesOnly = json.createArrayNode();
        for (final JsonNode table : bundle.get("tables")) {
            if (List.of(tables).contains(table.get("table").get("name").asString())) {
                tablesOnly.add(table);
            }
        }
        bundle.set("tables", tablesOnly);
        return bundle.toString();
    }

    private String export(String body) throws Exception {
        return mockMvc.perform(post("/v1/catalog/export").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andReturn().getResponse().getContentAsString();
    }

    private ResultActions importCall(String path, String bundle, Map<String, String> params, String policy)
            throws Exception {
        final MockMultipartHttpServletRequestBuilder request = multipart(path)
                .file(new MockMultipartFile("file", "bundle.json", MediaType.APPLICATION_JSON_VALUE,
                        bundle.getBytes(StandardCharsets.UTF_8)));
        if (params != null) {
            params.forEach(request::param);
        }
        if (policy != null) {
            request.param("resolution_policy", policy);
        }
        return mockMvc.perform(request);
    }

    private Map<String, JsonNode> reads(String base, List<String> names) throws Exception {
        final Map<String, JsonNode> reads = new java.util.HashMap<>();
        for (final String name : names) {
            final MvcResult result = mockMvc.perform(get(base + name)).andExpect(status().isOk()).andReturn();
            reads.put(name, json.readTree(result.getResponse().getContentAsString()));
        }
        return reads;
    }

    /** A pipeline read without what an import never carries over. */
    private static JsonNode declaration(JsonNode pipeline) {
        final ObjectNode copy = (ObjectNode) pipeline.deepCopy();
        copy.remove(List.of("generation", "created_at", "updated_at", "state", "enabled", "notes"));
        return copy;
    }

    private static JsonNode pipeline(JsonNode bundle, String name) {
        for (final JsonNode pipeline : bundle.get("pipelines")) {
            if (pipeline.get("pipeline").get("name").asString().equals(name)) {
                return pipeline.get("pipeline");
            }
        }
        throw new AssertionError("no pipeline " + name);
    }

    private static List<String> names(JsonNode entries, String member) {
        final List<String> names = new ArrayList<>();
        entries.forEach(entry -> names.add(entry.get(member).get("name").asString()));
        return names;
    }

    private void createPipeline(String body) throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void deletePipeline(String name) throws Exception {
        mockMvc.perform(delete("/v1/pipelines/" + name)).andExpect(status().isNoContent());
    }

    private void putAccess(String table, String body) throws Exception {
        mockMvc.perform(put("/v1/tables/" + table + "/access").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private String access(String table) throws Exception {
        return mockMvc.perform(get("/v1/tables/" + table + "/access")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** A GET access document as a PUT body: the same five lists. */
    private String accessBody(String accessJson) {
        final JsonNode read = json.readTree(accessJson);
        final ObjectNode body = json.createObjectNode();
        for (final String key : List.of("get", "read", "sensitive", "write", "modify")) {
            body.set(key, read.has(key) ? read.get(key) : json.createArrayNode());
        }
        return body.toString();
    }

    private int lastRevision() {
        return jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM revinfo", Integer.class);
    }

    private String importId(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("import_id").asString();
    }

    /** Every activity the feed returns for {@code importId eq}, newest first. */
    private JsonNode importActivities(String importId) throws Exception {
        final String body = mockMvc.perform(post("/v1/activities").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageSize\":1000,\"filters\":[{\"column\":\"importId\",\"operator\":\"eq\","
                                + "\"value\":\"" + importId + "\"}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private JsonNode filtered(JsonNode activities, String resourceType, String resourceId) {
        final ArrayNode matches = json.createArrayNode();
        activities.forEach(activity -> {
            if (activity.get("resourceType").asString().equals(resourceType)
                    && activity.get("resourceId").asString().equals(resourceId)) {
                matches.add(activity);
            }
        });
        return matches;
    }

    private static List<String> types(JsonNode activities) {
        final List<String> types = new ArrayList<>();
        activities.forEach(activity -> types.add(activity.get("activityType").asString()));
        return types;
    }

    /**
     * Every activity written since {@code lastRevisionBefore} — this test runs alone, so all of them are the
     * import's — carries {@code importId}, the feed filtered by it returns exactly those, and an imported column
     * still points at a {@code Table} activity of the same import.
     */
    private void assertImportTaggedEverythingItWrote(String importId, int lastRevisionBefore) throws Exception {
        final long written = jdbc.queryForObject("SELECT count(*) FROM audit_activity WHERE revision > ?", Long.class,
                lastRevisionBefore);
        final long untagged = jdbc.queryForObject("SELECT count(*) FROM audit_activity WHERE revision > ? "
                + "AND (import_id IS NULL OR import_id <> ?)", Long.class, lastRevisionBefore, importId);
        assertThat(written).isPositive();
        assertThat(untagged).isZero();

        final JsonNode activities = importActivities(importId);
        assertThat(activities.size()).isEqualTo(written);
        final List<String> tableActivityIds = new ArrayList<>();
        activities.forEach(activity -> {
            assertThat(activity.get("importId").asString()).isEqualTo(importId);
            if (activity.get("resourceType").asString().equals("Table")) {
                tableActivityIds.add(activity.get("activityId").asString());
            }
        });
        activities.forEach(activity -> {
            if (activity.get("resourceType").asString().equals("TableColumn")) {
                assertThat(tableActivityIds).contains(activity.get("parentActivityId").asString());
            }
        });
        final String one = activities.get(0).get("activityId").asString();
        mockMvc.perform(get("/v1/activities/" + one)).andExpect(status().isOk())
                .andExpect(jsonPath("$.importId").value(importId));
    }

    private long revisions() {
        return jdbc.queryForObject("SELECT count(*) FROM revinfo", Long.class);
    }

    /** Deletes the fixture and every audit row it left, so this environment has never seen it. */
    private void forget() throws Exception {
        for (final String pipeline : PIPELINES) {
            mockMvc.perform(delete("/v1/pipelines/" + pipeline));
        }
        for (final String table : TABLES) {
            mockMvc.perform(delete("/v1/tables/" + table));
        }
        execute("DROP TABLE IF EXISTS " + SENT);
        for (final String pipeline : PIPELINES) {
            purgeAudit(pipeline);
        }
        for (final String table : TABLES) {
            jdbc.update("DELETE FROM column_mapping_aud WHERE table_name = ?", table);
            jdbc.update("DELETE FROM table_definition_aud WHERE name = ?", table);
        }
    }

    private void purgeAudit(String pipeline) {
        jdbc.update("DELETE FROM pipeline_aud WHERE name = ?", pipeline);
    }
}
