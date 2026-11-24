package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.table.TableDefinitionLockingImpl;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
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
 * on both (read-side on the source, write on the enrichment), an aggregate target, two enrich pipelines and an aggregate pipeline whose only reference to the
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
    @Autowired
    private TableDefinitionRepository definitions;
    @Autowired
    private TableDefinitionLockingImpl locking;
    @Autowired
    private PlatformTransactionManager transactionManager;

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
        putAccess(BASE, READERS, "read");
        putAccess(SYSTEM, READERS, "read", "sensitive");
        // An enrichment's read side is its source's; only its write and modify privileges are its own.
        putAccess(DUL, READERS, "write");
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
        restoreAccess(SYSTEM, systemAccess);
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
                "{\"" + READERS + "\":{\"privileges\":[\"read\",\"sensitive\"],\"rows\":null,\"columns\":null}}"));
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

        assertThat(bundle.get("format_version").asInt()).isEqualTo(4);
        final ObjectNode newer = bundle.deepCopy();
        newer.put("format_version", 5);
        newer.put("added_in_v5", true);
        importCall("/v1/catalog/import", newer.toString(), null, "SKIP_IF_EXISTS")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("catalog_bundle_version_unsupported"))
                .andExpect(jsonPath("$.message").value(containsString("format_version 5")));

        // Version-1 to version-3 bundles — this shape carries neither `sql`, a route, an input token budget nor
        // access — still read, and plan as before.
        for (int version : new int[] {1, 2, 3}) {
            final ObjectNode older = bundle.deepCopy();
            older.put("format_version", version);
            importCall("/v1/catalog/import/preview", older.toString(), null, "SKIP_IF_EXISTS")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.validation_errors").isEmpty());
        }

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
        assertThat(json.readTree(bundle).has("access")).isFalse();
        assertThat(json.readTree(bundle).get("tables").get(0).has("access")).isFalse();
        assertThat(json.readTree(bundle).has("access_omitted")).isFalse();
        forget();
        final String systemBefore = access(SYSTEM);

        importCall("/v1/catalog/import", bundle, null, "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name == '" + DUL + "')].status").value(hasItem("created")));

        assertThat(json.readTree(access(DUL))).isEqualTo(json.readTree("{}"));
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
            assertThat(bundle.get("format_version").asInt()).isEqualTo(4);
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
        putAccess(SYSTEM, "cx-extra", "read");
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

    // --- access ---------------------------------------------------------------------------------------

    @Test
    void accessRoundTripsWithTheEnrichmentItsColumnPolicyNames() throws Exception {
        putEntryJson(BASE, "cx-analysts", "{\"privileges\":[\"read\"],\"rows\":{\"where\":\"label = 'a'\"},"
                + "\"columns\":[\"event_id\",\"label\",\"" + SENT + ".sentiment\"]}");
        final String body = "{\"components\":[{\"type\":\"table\",\"name\":\"" + BASE + "\"}],\"include_access\":true}";
        mockMvc.perform(post("/v1/catalog/export/preview").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects[?(@.name == '" + SENT + "')].reason").value(hasItem("column policy of " + BASE)))
                .andExpect(jsonPath("$.objects[?(@.name == '" + SENT_LIVE + "')].reason").value(hasItem("targets " + SENT)))
                .andExpect(jsonPath("$.access[?(@.table == '" + BASE + "')].roles[*]").value(contains("cx-analysts", READERS)));
        final String bundle = export(body);
        final JsonNode exported = json.readTree(bundle);
        assertThat(exported.get("format_version").asInt()).isEqualTo(4);
        assertThat(names(exported.get("tables"), "table")).containsExactlyInAnyOrder(BASE, SENT);
        final JsonNode analysts = exported.get("access").get(BASE).get("cx-analysts");
        assertThat(analysts.get("rows").get("filter").isObject()).isTrue();
        assertThat(analysts.has("columns")).isTrue();
        assertThat(exported.get("access").get(BASE).get(READERS).get("rows").isNull()).isTrue();
        final String before = access(BASE);

        forget();
        final ResultActions imported = importAccess("/v1/catalog/import", bundle, "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("completed"))
                .andExpect(jsonPath("$.access[*].status", everyItem(is("created"))))
                .andExpect(jsonPath("$.access[*].role").value(contains("cx-analysts", READERS)));
        assertThat(access(BASE)).isEqualTo(before);

        final long generation = generation(BASE);
        importAccess("/v1/catalog/import", bundle, "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access[*].import_action", everyItem(is("SKIP"))))
                .andExpect(jsonPath("$.access[*].status", everyItem(is("skipped"))));
        assertThat(generation(BASE)).isEqualTo(generation);
    }

    /** The pass applies only entries that change, so a preview whose access equals the target's waits on no row lock. */
    @Test
    void previewOfUnchangedAccessTakesNoRowLock() throws Exception {
        putAccess(SYSTEM, "cx-kept", "get");
        final String bundle = accessOnly("{\"cx-kept\":{\"privileges\":[\"get\"]}}");
        final Object timeout = ReflectionTestUtils.getField(locking, "lockTimeout");
        ReflectionTestUtils.setField(locking, "lockTimeout", Duration.ofMillis(300));
        try {
            final String preview = new TransactionTemplate(transactionManager).execute(status -> {
                definitions.findForUpdate(SYSTEM).orElseThrow();
                try (ExecutorService other = Executors.newSingleThreadExecutor()) {
                    return other.submit(() -> importAccess("/v1/catalog/import/preview", bundle, "SKIP_IF_EXISTS")
                            .andReturn().getResponse().getContentAsString()).get(30, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            });
            final JsonNode entry = json.readTree(preview).get("access").get(0);
            assertThat(entry.get("import_action").asString()).isEqualTo("SKIP");
            assertThat(entry.get("problems")).isEmpty();
        } finally {
            ReflectionTestUtils.setField(locking, "lockTimeout", timeout);
        }
    }

    @Test
    void accessLandsOnTheSystemTablePerRoleAndMovesTheGenerationAsPutsWould() throws Exception {
        final String bundle = accessOnly("{\"" + READERS + "\":{\"privileges\":[\"read\"],\"rows\":null,\"columns\":null},"
                + "\"cx-new\":{\"privileges\":[\"get\"]},\"cx-kept\":{\"privileges\":[\"get\"]}}");
        putAccess(SYSTEM, "cx-kept", "get");
        putAccess(SYSTEM, "cx-other", "read");
        final String otherBefore = json.readTree(access(SYSTEM)).get("cx-other").toString();
        final long generation = generation(SYSTEM);

        importAccess("/v1/catalog/import/preview", bundle, "FAIL_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access[?(@.role == 'cx-new')].import_action").value(hasItem("CREATE")))
                .andExpect(jsonPath("$.access[?(@.role == 'cx-new')].prev").value(hasItem(nullValue())))
                .andExpect(jsonPath("$.access[?(@.role == '" + READERS + "')].import_action").value(hasItem("REPLACE")))
                .andExpect(jsonPath("$.access[?(@.role == '" + READERS + "')].prev.privileges[*]").value(contains("read", "sensitive")))
                .andExpect(jsonPath("$.access[?(@.role == 'cx-new')].ineffective_grants[*]").isEmpty());
        importCall("/v1/catalog/import", bundle, null, "FAIL_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access").isEmpty());
        assertThat(generation(SYSTEM)).isEqualTo(generation);

        final String importId = importId(importAccess("/v1/catalog/import", bundle, "FAIL_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("completed"))
                .andExpect(jsonPath("$.access[?(@.role == 'cx-new')].status").value(hasItem("created")))
                .andExpect(jsonPath("$.access[?(@.role == '" + READERS + "')].status").value(hasItem("replaced")))
                .andExpect(jsonPath("$.access[?(@.role == 'cx-kept')].status").value(hasItem("skipped"))));
        assertThat(generation(SYSTEM)).isEqualTo(generation + 2);
        final JsonNode roles = json.readTree(access(SYSTEM));
        assertThat(roles.get(READERS).get("privileges").toString()).isEqualTo("[\"read\"]");
        assertThat(roles.get("cx-new").get("privileges").toString()).isEqualTo("[\"get\"]");
        assertThat(roles.get("cx-kept").get("privileges").toString()).isEqualTo("[\"get\"]");
        // A role the bundle does not name keeps its entry.
        assertThat(roles.get("cx-other").toString()).isEqualTo(otherBefore);
        assertThat(types(filtered(importActivities(importId), "Table", SYSTEM))).containsExactly("Update");
        // The import moved only the marks of the roles it changed, as PUTs would have.
        putEntryAt(SYSTEM, READERS, "{\"privileges\":[\"get\"]}", generation).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("access_conflict"));
        putEntryAt(SYSTEM, "cx-new", "{\"privileges\":[\"read\"]}", generation).andExpect(status().isConflict());
        putEntryAt(SYSTEM, "cx-kept", "{\"privileges\":[\"get\",\"read\"]}", generation).andExpect(status().isOk());
        putAccess(SYSTEM, "cx-kept", "get");

        final int lastRevision = lastRevision();
        final long afterImport = generation(SYSTEM);
        importAccess("/v1/catalog/import", bundle, "FAIL_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access[*].status", everyItem(is("skipped"))));
        assertThat(generation(SYSTEM)).isEqualTo(afterImport);
        assertThat(lastRevision()).isEqualTo(lastRevision);
    }

    @Test
    void exportPreviewReportsWhatStaysBehind() throws Exception {
        storedAccess(BASE, access -> {
            ((ArrayNode) access.get("read")).add("*").add("READ_ONLY_ANALYTIC");
            ((ArrayNode) access.get("write")).add("*");
        });
        jdbc.update("UPDATE table_definition SET column_policies = CAST(? AS jsonb) WHERE name = ?",
                "{\"READ_ONLY_ANALYTIC\":[\"label\",\"" + SENT + ".sentiment\"]}", BASE);
        storedAccess(SENT, access -> {
            ((ArrayNode) access.get("read")).add("cx-left");
            ((ArrayNode) access.get("write")).add("cx-left");
        });
        // A leftover row policy on the enrichment, in the stored form a PUT on the source writes.
        putEntryJson(BASE, "cx-left", "{\"privileges\":[\"read\"],\"rows\":{\"where\":\"label = 'a'\"}}");
        jdbc.update("UPDATE table_definition SET row_policies = (SELECT row_policies FROM table_definition WHERE name = ?) "
                + "WHERE name = ?", BASE, SENT);
        mockMvc.perform(delete("/v1/tables/" + BASE + "/access?role=cx-left")).andExpect(status().isNoContent());
        storedAccess(SYSTEM, access -> ((ArrayNode) access.get("write")).add(READERS));
        final String body = "{\"components\":[{\"type\":\"table\",\"name\":\"" + BASE + "\"},{\"type\":\"table\",\"name\":\""
                + SENT + "\"},{\"type\":\"table\",\"name\":\"" + SYSTEM + "\"}],\"include_access\":true}";

        mockMvc.perform(post("/v1/catalog/export/preview").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access[?(@.table == '" + BASE + "')].roles[*]").value(contains("*", READERS)))
                .andExpect(jsonPath("$.access[?(@.table == '" + BASE + "')].left_behind[?(@.role == '*')].error")
                        .value(hasItem("validation_error")))
                .andExpect(jsonPath("$.access[?(@.table == '" + BASE + "')].left_behind[?(@.role == '*')].entry.privileges[*]")
                        .value(contains("write")))
                .andExpect(jsonPath("$.access[?(@.table == '" + BASE + "')].left_behind[?(@.role == 'READ_ONLY_ANALYTIC')].error")
                        .value(hasItem("validation_error")))
                .andExpect(jsonPath("$.access[?(@.table == '" + BASE + "')].left_behind[?(@.role == 'READ_ONLY_ANALYTIC')].entry.privileges[*]")
                        .value(contains("read")))
                .andExpect(jsonPath("$.access[?(@.table == '" + BASE + "')].left_behind[?(@.role == 'READ_ONLY_ANALYTIC')].entry.columns[*]")
                        .value(contains(SENT + ".sentiment", "label")))
                .andExpect(jsonPath("$.access[?(@.table == '" + SENT + "')].left_behind[?(@.role == 'cx-left')].error")
                        .value(hasItem("enrichment_follows_source")))
                .andExpect(jsonPath("$.access[?(@.table == '" + SENT + "')].left_behind[?(@.role == 'cx-left')].entry.privileges[*]")
                        .value(contains("read")))
                .andExpect(jsonPath("$.access[?(@.table == '" + SENT + "')].left_behind[?(@.role == 'cx-left')].entry.rows.filter")
                        .value(hasItem(notNullValue())))
                .andExpect(jsonPath("$.access[?(@.table == '" + SYSTEM + "')].left_behind[?(@.role == '" + READERS + "')].error")
                        .value(hasItem("table_is_system")))
                .andExpect(jsonPath("$.required_system_tables[*].name").value(hasItem(SYSTEM)));
        final JsonNode bundle = json.readTree(export(body));
        assertThat(bundle.get("access").get(BASE).get("*").get("privileges").toString()).isEqualTo("[\"read\"]");
        assertThat(bundle.get("access").get(BASE).has("READ_ONLY_ANALYTIC")).isFalse();
        assertThat(bundle.get("access").get(SENT).get("cx-left").toString())
                .isEqualTo("{\"privileges\":[\"write\"],\"rows\":null,\"columns\":null}");
        assertThat(bundle.get("access").get(SYSTEM).get(READERS).get("privileges").toString()).isEqualTo("[\"read\",\"sensitive\"]");

        // Without the flag the preview carries no access member.
        mockMvc.perform(post("/v1/catalog/export/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[{\"type\":\"table\",\"name\":\"" + BASE + "\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access").doesNotExist());
        // A left-behind entry naming an enrichment pulls nothing in.
        final JsonNode alone = json.readTree(export("{\"components\":[{\"type\":\"table\",\"name\":\"" + BASE
                + "\"}],\"include_access\":true}"));
        assertThat(names(alone.get("tables"), "table")).containsExactly(BASE);
    }

    @Test
    void skippedEnrichmentLeavesOnlyTheEntryNamingItBehind() throws Exception {
        putEntryJson(BASE, "cx-analysts", "{\"privileges\":[\"read\"],\"columns\":[\"event_id\",\"" + SENT + ".sentiment\"]}");
        jdbc.update("UPDATE table_definition SET status = 'FAILED' WHERE name = ?", SENT);
        try {
            mockMvc.perform(post("/v1/catalog/export/preview").contentType(MediaType.APPLICATION_JSON).content("{\"include_access\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.objects[*].name").value(hasItem(BASE)))
                    .andExpect(jsonPath("$.skipped[?(@.name == '" + SENT + "')].reason").value(hasItem("failed")))
                    .andExpect(jsonPath("$.access[?(@.table == '" + BASE + "')].roles[*]").value(contains(READERS)))
                    .andExpect(jsonPath("$.access[?(@.table == '" + BASE + "')].left_behind[?(@.role == 'cx-analysts')].error")
                            .value(hasItem("dependency_skipped")));
            mockMvc.perform(post("/v1/catalog/export").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"components\":[{\"type\":\"table\",\"name\":\"" + BASE + "\"}],\"include_access\":true}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("catalog_export_invalid"))
                    .andExpect(jsonPath("$.message").value(containsString(SENT)));
        } finally {
            jdbc.update("UPDATE table_definition SET status = 'ACTIVE' WHERE name = ?", SENT);
        }
    }

    @Test
    void exportingEverythingCoversSystemTablesNothingReaches() throws Exception {
        forget();
        mockMvc.perform(post("/v1/catalog/export/preview").contentType(MediaType.APPLICATION_JSON).content("{\"include_access\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required_system_tables[?(@.name == '" + SYSTEM + "')].reason").value(hasItem("access")))
                .andExpect(jsonPath("$.access[?(@.table == '" + SYSTEM + "')].roles[*]").value(hasItem(READERS)));
        mockMvc.perform(post("/v1/catalog/export").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[{\"type\":\"table\",\"name\":\"" + SYSTEM + "\"}],\"include_access\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables").isEmpty())
                .andExpect(jsonPath("$.pipelines").isEmpty())
                .andExpect(jsonPath("$.required_system_tables[*]").value(contains(SYSTEM)))
                .andExpect(jsonPath("$.access." + SYSTEM + "['" + READERS + "'].privileges[*]")
                        .value(contains("read", "sensitive")));
        mockMvc.perform(post("/v1/catalog/export").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[{\"type\":\"table\",\"name\":\"dial_usage_log_payload\"}],\"include_access\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required_system_tables[*]").value(contains("dial_usage_log_payload")))
                .andExpect(jsonPath("$.access").isEmpty());
    }

    @Test
    void importValidatesAccessWithThePutRules() throws Exception {
        putEntryJson(BASE, "cx-ordered", "{\"privileges\":[\"get\",\"read\"],\"columns\":[\"event_id\",\"label\"]}");
        final ObjectNode bundle = (ObjectNode) json.readTree(export("{\"components\":[{\"type\":\"table\",\"name\":\"" + BASE
                + "\"}],\"include_access\":true}"));
        final ObjectNode reordered = bundle.deepCopy();
        ((ObjectNode) reordered.get("access").get(BASE)).set("cx-ordered",
                json.readTree("{\"privileges\":[\"read\",\"get\"],\"columns\":[\"label\",\"event_id\"]}"));
        importAccess("/v1/catalog/import/preview", reordered.toString(), "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access[?(@.role == 'cx-ordered')].import_action").value(hasItem("SKIP")));
        forget();

        // Without the flag the access member is ignored: the created table has no entry.
        importCall("/v1/catalog/import", bundle.toString(), null, "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access").isEmpty());
        assertThat(json.readTree(access(BASE))).isEqualTo(json.readTree("{}"));
        forget();

        assertAccessFails(withEntry(bundle, "cx-x", "{\"privileges\":[]}"), "validation_error: ");
        assertAccessFails(withEntry(bundle, "*", "{\"privileges\":[\"write\"]}"), "validation_error: ");
        assertAccessFails(withEntry(bundle, "cx-x", "{\"privileges\":[\"read\"],\"columns\":[\"zzz\"]}"), "zzz");
        assertBundleProblem(withEntry(withEntry(bundle, "cx-a", "{\"privileges\":[\"read\"]}"), " cx-a", "{\"privileges\":[\"get\"]}"),
                "trim to role 'cx-a'");
        assertBundleProblem(withEntry(bundle, " ", "{\"privileges\":[\"read\"]}"), "blank role key");
        final ObjectNode nowhere = bundle.deepCopy();
        ((ObjectNode) nowhere.get("access")).set("cx_nowhere", json.readTree("{\"r\":{\"privileges\":[\"read\"]}}"));
        assertBundleProblem(nowhere, "'cx_nowhere'");
        final ObjectNode withoutAccess = bundle.deepCopy();
        withoutAccess.remove("access");
        assertBundleProblem(withoutAccess, "no access member");
        final ObjectNode nullAccess = bundle.deepCopy();
        nullAccess.putNull("access");
        assertBundleProblem(nullAccess, "no access member");
        importCall("/v1/catalog/import/preview", nullAccess.toString(), null, "SKIP_IF_EXISTS").andExpect(status().isOk());

        for (final String nulled : List.of("{\"" + BASE + "\":null}", "{\"" + BASE + "\":{\"r\":null}}",
                "{\"" + BASE + "\":{\"r\":{\"privileges\":[\"read\"],\"extra\":1}}}")) {
            final ObjectNode invalid = bundle.deepCopy();
            invalid.set("access", json.readTree(nulled));
            importAccess("/v1/catalog/import/preview", invalid.toString(), "SKIP_IF_EXISTS").andExpect(status().isBadRequest());
            importCall("/v1/catalog/import/preview", invalid.toString(), null, "SKIP_IF_EXISTS").andExpect(status().isBadRequest());
        }

        importAccess("/v1/catalog/import/preview", withEntry(bundle, "cx-x", "{\"privileges\":[\"read\"],\"rows\":{\"where\":\"label = 'a'\"}}").toString(),
                        "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access[?(@.role == 'cx-x')].next.rows.filter").value(hasItem(notNullValue())))
                .andExpect(jsonPath("$.access[?(@.role == 'cx-x')].next.rows.where").isEmpty());
        importAccess("/v1/catalog/import/preview", withEntry(bundle, "*", "{\"privileges\":[\"read\"]}").toString(), "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access[?(@.role == '*')].ineffective_grants[*].reason").value(hasItem("everyone_reads_all_rows")));
    }

    @Test
    void previewWithAccessArmsAgainstTheAccessItWouldWrite() throws Exception {
        final ObjectNode bundle = (ObjectNode) json.readTree(export("{\"include_access\":true}"));
        importAccess("/v1/catalog/import/preview", bundle.toString(), "FAIL_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name == '" + BASE + "')].import_action").value(hasItem("FAIL")))
                .andExpect(jsonPath("$.access[?(@.table == '" + BASE + "')].import_action").value(hasItem("SKIP")));
        forget();

        importAccess("/v1/catalog/import/preview", withEntry(bundle, "*", "{\"privileges\":[\"write\"]}").toString(), "FAIL_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[*].armable", everyItem(is(true))))
                .andExpect(jsonPath("$.access[?(@.role == '*')].import_action").value(hasItem("FAIL")))
                .andExpect(jsonPath("$.access[*].ineffective_grants[*]").isEmpty());

        final ObjectNode refused = bundle.deepCopy();
        for (final JsonNode table : refused.get("tables")) {
            if (table.get("table").get("name").asString().equals(BASE)) {
                ((ObjectNode) table.get("schema").get("columns").get(1)).put("name", "bad name");
            }
        }
        importAccess("/v1/catalog/import/preview", refused.toString(), "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name == '" + BASE + "')].import_action").value(hasItem("FAIL")))
                .andExpect(jsonPath("$.access[?(@.table == '" + BASE + "')].import_action", everyItem(is("FAIL"))))
                .andExpect(jsonPath("$.access[?(@.table == '" + BASE + "')].problems[*]").value(hasItem(containsString(BASE))));

        final ObjectNode missingSystem = bundle.deepCopy();
        ((ArrayNode) missingSystem.get("required_system_tables")).add("cx_no_such_system_table");
        ((ObjectNode) missingSystem.get("access")).set("cx_no_such_system_table", json.readTree("{\"r\":{\"privileges\":[\"read\"]}}"));
        importAccess("/v1/catalog/import/preview", missingSystem.toString(), "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required_system_tables[?(@.name == 'cx_no_such_system_table')].import_action").value(hasItem("FAIL")))
                .andExpect(jsonPath("$.access[?(@.table == 'cx_no_such_system_table')].import_action").value(hasItem("FAIL")));
        importAccess("/v1/catalog/import", missingSystem.toString(), "SKIP_IF_EXISTS")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("catalog_import_invalid"));
    }

    @Test
    void failedAccessStepRollsEverythingBack() throws Exception {
        final ObjectNode bundle = (ObjectNode) json.readTree(exportTables(BASE));
        bundle.set("access", json.readTree("{\"" + BASE + "\":{\"" + READERS + "\":{\"privileges\":[\"read\"]}},\"" + SYSTEM
                + "\":{\"cx-new\":{\"privileges\":[\"get\"]}}}"));
        ((ArrayNode) bundle.get("required_system_tables")).add(SYSTEM);
        final ObjectNode two = (ObjectNode) json.readTree(exportTables(BASE, SENT));
        two.set("access", bundle.get("access"));
        two.set("required_system_tables", bundle.get("required_system_tables"));
        forget();
        final String systemBefore = fullAccess(SYSTEM);
        // The access write on the created table fails only once the table is committed, as it is in the import's
        // access step, never inside the validation pass, which creates the table in its own transaction.
        jdbc.execute("CREATE OR REPLACE FUNCTION cx_fail_access() RETURNS trigger AS $$ BEGIN "
                + "IF NEW.name = '" + BASE + "' AND NEW.access::text IS DISTINCT FROM OLD.access::text "
                + "AND NOT (OLD.xmin = pg_current_xact_id()::xid) THEN RAISE EXCEPTION 'cx access refused'; END IF; "
                + "RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER cx_fail_access BEFORE UPDATE ON table_definition FOR EACH ROW EXECUTE FUNCTION cx_fail_access()");
        try {
            importAccess("/v1/catalog/import", bundle.toString(), "SKIP_IF_EXISTS")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("rolled_back"))
                    .andExpect(jsonPath("$.tables[?(@.name == '" + BASE + "')].status").value(hasItem("rolled_back")))
                    .andExpect(jsonPath("$.access[*].status", everyItem(is("rolled_back"))))
                    .andExpect(jsonPath("$.access[*].problems[*]", everyItem(containsString("writing the access failed"))));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS cx_fail_access ON table_definition");
            jdbc.execute("DROP FUNCTION IF EXISTS cx_fail_access()");
        }
        mockMvc.perform(get("/v1/tables/" + BASE)).andExpect(status().isNotFound());
        assertThat(fullAccess(SYSTEM)).isEqualTo(systemBefore);

        // A failure before the access step leaves every access entry unattempted.
        execute("CREATE TABLE " + SENT + " (x UInt8) ENGINE = Memory");
        importAccess("/v1/catalog/import", two.toString(), "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("rolled_back"))
                .andExpect(jsonPath("$.access[*].status", everyItem(is("skipped"))))
                .andExpect(jsonPath("$.access[*].problems[*]", everyItem(containsString("not attempted"))))
                .andExpect(jsonPath("$.access[*].problems[*]", everyItem(containsString("table '" + SENT + "'"))));
        assertThat(fullAccess(SYSTEM)).isEqualTo(systemBefore);
    }

    @Test
    void systemTableAccessFollowsTheClosureAndPullsInWhatItsColumnPoliciesName() throws Exception {
        mockMvc.perform(post("/v1/catalog/export").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[{\"type\":\"pipeline\",\"name\":\"" + DUL_LIVE + "\"}],\"include_access\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required_system_tables[*]").value(hasItem(SYSTEM)))
                .andExpect(jsonPath("$.access." + SYSTEM + "['" + READERS + "'].privileges[*]").value(contains("read", "sensitive")));

        final String payload = "dial_usage_log_payload";
        final String ownColumn = firstColumn(SYSTEM);
        final String payloadColumn = payload + "." + firstColumn(payload);
        putEntryJson(SYSTEM, "cx-analysts", "{\"privileges\":[\"read\"],\"columns\":[\"" + ownColumn + "\",\"" + DUL + ".note\",\""
                + payloadColumn + "\"]}");
        final String body = "{\"components\":[{\"type\":\"table\",\"name\":\"" + SYSTEM + "\"}],\"include_access\":true}";
        mockMvc.perform(post("/v1/catalog/export/preview").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects[?(@.name == '" + DUL + "')].reason").value(hasItem("column policy of " + SYSTEM)))
                .andExpect(jsonPath("$.objects[?(@.name == '" + DUL_LIVE + "')].reason").value(hasItem("targets " + DUL)))
                .andExpect(jsonPath("$.required_system_tables[?(@.name == '" + payload + "')].reason")
                        .value(hasItem("column policy of " + SYSTEM)));
        final String bundle = export(body);

        forget();
        // Deleting the enrichment took its column out of the policy here, so the import replaces the entry, and it
        // can only do so because the bundle brings the enrichment along.
        importAccess("/v1/catalog/import", bundle, "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("completed"))
                .andExpect(jsonPath("$.tables[?(@.name == '" + DUL + "')].status").value(hasItem("created")))
                .andExpect(jsonPath("$.access[?(@.role == 'cx-analysts')].status").value(hasItem("replaced")));
        assertThat(json.readTree(access(SYSTEM)).get("cx-analysts"))
                .isEqualTo(json.readTree(bundle).get("access").get(SYSTEM).get("cx-analysts"));
    }

    @Test
    void exportingEverythingLeavesSystemTableUnportableStateBehind() throws Exception {
        putEntryJson(SYSTEM, "cx-analysts", "{\"privileges\":[\"read\"],\"columns\":[\"" + firstColumn(SYSTEM) + "\",\"" + DUL
                + ".note\"]}");
        storedAccess(SYSTEM, access -> ((ArrayNode) access.get("write")).add(READERS));
        jdbc.update("UPDATE table_definition SET status = 'FAILED' WHERE name = ?", DUL);
        try {
            mockMvc.perform(post("/v1/catalog/export/preview").contentType(MediaType.APPLICATION_JSON).content("{\"include_access\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.skipped[?(@.name == '" + DUL + "')].reason").value(hasItem("failed")))
                    .andExpect(jsonPath("$.access[?(@.table == '" + SYSTEM + "')].roles[*]").value(not(hasItem("cx-analysts"))))
                    .andExpect(jsonPath("$.access[?(@.table == '" + SYSTEM + "')].left_behind[?(@.role == 'cx-analysts')].error")
                            .value(hasItem("dependency_skipped")))
                    .andExpect(jsonPath("$.access[?(@.table == '" + SYSTEM + "')].left_behind[?(@.role == '" + READERS + "')].error")
                            .value(hasItem("table_is_system")));
        } finally {
            jdbc.update("UPDATE table_definition SET status = 'ACTIVE' WHERE name = ?", DUL);
        }
    }

    @Test
    void accessLandsOnSkippedExistingTableAndNeverRemovesAnEntry() throws Exception {
        final ObjectNode bundle = withEntry((ObjectNode) json.readTree(export("{\"components\":[{\"type\":\"table\",\"name\":\""
                + BASE + "\"}],\"include_access\":true}")), "cx-x", "{\"privileges\":[\"get\"]}");
        final JsonNode tableBefore = json.readTree(mockMvc.perform(get("/v1/tables/" + BASE)).andReturn().getResponse().getContentAsString());

        importAccess("/v1/catalog/import", bundle.toString(), "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name == '" + BASE + "')].import_action").value(hasItem("SKIP")))
                .andExpect(jsonPath("$.access[?(@.role == 'cx-x')].status").value(hasItem("created")))
                .andExpect(jsonPath("$.access[?(@.role == '" + READERS + "')].status").value(hasItem("skipped")));
        final JsonNode tableAfter = json.readTree(mockMvc.perform(get("/v1/tables/" + BASE)).andReturn().getResponse().getContentAsString());
        assertThat(tableAfter.get("columns")).isEqualTo(tableBefore.get("columns"));
        assertThat(tableAfter.get("description")).isEqualTo(tableBefore.get("description"));
        assertThat(json.readTree(access(BASE)).get("cx-x").get("privileges").toString()).isEqualTo("[\"get\"]");

        final String readersBefore = json.readTree(access(BASE)).get(READERS).toString();
        final ObjectNode emptied = withEntry(bundle, READERS, "{\"privileges\":[]}");
        importAccess("/v1/catalog/import/preview", emptied.toString(), "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access[?(@.role == '" + READERS + "')].import_action").value(hasItem("FAIL")))
                .andExpect(jsonPath("$.access[?(@.role == '" + READERS + "')].problems[*]")
                        .value(hasItem(containsString("DELETE /v1/tables/{name}/access?role="))));
        importAccess("/v1/catalog/import", emptied.toString(), "SKIP_IF_EXISTS")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("catalog_import_invalid"));
        assertThat(json.readTree(access(BASE)).get(READERS).toString()).isEqualTo(readersBefore);

        // A bundle edited to drop the enrichment its access names fails that entry, naming the column.
        final ObjectNode lacking = withEntry(bundle, "cx-y", "{\"privileges\":[\"read\"],\"columns\":[\"event_id\",\"" + SENT + ".sentiment\"]}");
        forget();
        importAccess("/v1/catalog/import/preview", lacking.toString(), "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access[?(@.role == 'cx-y')].import_action").value(hasItem("FAIL")))
                .andExpect(jsonPath("$.access[?(@.role == 'cx-y')].problems[*]").value(hasItem(containsString(SENT + ".sentiment"))));
        importAccess("/v1/catalog/import", lacking.toString(), "SKIP_IF_EXISTS")
                .andExpect(status().isUnprocessableContent());
    }

    // --- selective export -----------------------------------------------------------------------------

    @Test
    void pipelineExportedAloneCarriesNothingElseAndImportsWhereItsTablesExist() throws Exception {
        final String alone = "{\"components\":[{\"type\":\"pipeline\",\"name\":\"" + DUL_LIVE + "\",\"dependencies\":false}]}";
        final String bundle = export(alone);
        final JsonNode exported = json.readTree(bundle);
        assertThat(names(exported.get("pipelines"), "pipeline")).containsExactly(DUL_LIVE);
        assertThat(exported.get("tables")).isEmpty();
        assertThat(exported.get("required_system_tables")).isEmpty();
        exportCall("/v1/catalog/export/preview", alone)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects[*].name").value(contains(DUL_LIVE)))
                .andExpect(jsonPath("$.objects[0].reason").value("selected"))
                .andExpect(jsonPath("$.required_system_tables").isEmpty());
        final JsonNode withAccess = json.readTree(export(alone.replace("]}", "],\"include_access\":true}")));
        assertThat(withAccess.get("required_system_tables")).isEmpty();
        assertThat(withAccess.get("access")).isEqualTo(json.readTree("{}"));

        // DUL_LIVE never ran, so deleting it leaves its name free for the import.
        deletePipeline(DUL_LIVE);
        importCall("/v1/catalog/import", bundle, null, "FAIL_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("completed"))
                .andExpect(jsonPath("$.pipelines[0].import_action").value("CREATE"))
                .andExpect(jsonPath("$.pipelines[0].status").value("created"));
        mockMvc.perform(get("/v1/pipelines/" + DUL_LIVE)).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));

        deletePipeline(DUL_LIVE);
        mockMvc.perform(delete("/v1/tables/" + DUL)).andExpect(status().isNoContent());
        importCall("/v1/catalog/import/preview", bundle, null, "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[0].import_action").value("FAIL"))
                .andExpect(jsonPath("$.pipelines[0].problems[*]").value(hasItem(containsString("'" + DUL + "'"))));
        importCall("/v1/catalog/import", bundle, null, "SKIP_IF_EXISTS")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("catalog_import_invalid"));
    }

    @Test
    void pipelineAloneSkipsTheClosureChecksUnlessAnotherComponentReachesIt() throws Exception {
        jdbc.update("UPDATE table_definition SET status = 'PENDING' WHERE name = ?", SENT);
        try {
            exportCall("/v1/catalog/export", "{\"components\":[{\"type\":\"pipeline\",\"name\":\"" + SENT_LIVE + "\",\"dependencies\":false}]}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pipelines[*].pipeline.name").value(contains(SENT_LIVE)));
        } finally {
            jdbc.update("UPDATE table_definition SET status = 'ACTIVE' WHERE name = ?", SENT);
        }

        // A declaration that no longer resolves: DUL_LIVE's target stops being an enrichment, so it has no read source.
        final Map<String, Object> dul = jdbc.queryForMap("SELECT type, source_table, grain_key FROM table_definition WHERE name = ?", DUL);
        jdbc.update("UPDATE table_definition SET type = 'SOURCE', source_table = NULL, grain_key = NULL WHERE name = ?", DUL);
        try {
            exportCall("/v1/catalog/export", "{\"components\":[{\"type\":\"pipeline\",\"name\":\"" + DUL_LIVE + "\"}]}")
                    .andExpect(status().isUnprocessableContent());
            exportCall("/v1/catalog/export", "{\"components\":[{\"type\":\"pipeline\",\"name\":\"" + DUL_LIVE + "\",\"dependencies\":false}]}")
                    .andExpect(status().isOk());
        } finally {
            jdbc.update("UPDATE table_definition SET type = ?, source_table = ?, grain_key = ? WHERE name = ?",
                    dul.get("type"), dul.get("source_table"), dul.get("grain_key"), DUL);
        }

        // Reached by another component's closure, the pipeline is expanded and meets that closure's rules.
        final String reached = "{\"components\":[{\"type\":\"table\",\"name\":\"" + ROLL + "\"},"
                + "{\"type\":\"pipeline\",\"name\":\"" + ROLLUP + "\",\"dependencies\":false}]}";
        exportCall("/v1/catalog/export/preview", reached)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects[?(@.name == '" + BASE + "')].reason").value(hasItem("input of " + ROLLUP)))
                .andExpect(jsonPath("$.objects[?(@.name == '" + ROLLUP + "')].reason").value(hasItem("selected")));
        jdbc.update("UPDATE table_definition SET status = 'PENDING' WHERE name = ?", BASE);
        try {
            exportCall("/v1/catalog/export", reached)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("catalog_export_invalid"));
        } finally {
            jdbc.update("UPDATE table_definition SET status = 'ACTIVE' WHERE name = ?", BASE);
        }
    }

    @Test
    void oneRolesPolicyExportsAloneAndImportsOntoTheExistingTable() throws Exception {
        putAccess(BASE, "cx-finance", "read");
        final String body = "{\"components\":[{\"type\":\"table\",\"name\":\"" + BASE + "\",\"access_only\":true,\"roles\":[\""
                + READERS + "\"]}],\"include_access\":true}";
        exportCall("/v1/catalog/export/preview", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects").isEmpty())
                .andExpect(jsonPath("$.required_system_tables").isEmpty())
                .andExpect(jsonPath("$.access[0].table").value(BASE))
                .andExpect(jsonPath("$.access[0].roles[*]").value(contains(READERS)))
                .andExpect(jsonPath("$.access[0].left_behind").isEmpty());
        final ObjectNode bundle = (ObjectNode) json.readTree(export(body));
        assertThat(bundle.get("tables")).isEmpty();
        assertThat(bundle.get("pipelines")).isEmpty();
        assertThat(bundle.get("required_system_tables")).isEmpty();
        assertThat(bundle.get("access").get(BASE).size()).isEqualTo(1);
        assertThat(bundle.get("access").get(BASE).has(READERS)).isTrue();

        // The target's entry for the role differs; the role the bundle does not name keeps its entry.
        putAccess(BASE, READERS, "get");
        final String financeBefore = json.readTree(access(BASE)).get("cx-finance").toString();
        final JsonNode tableBefore = json.readTree(mockMvc.perform(get("/v1/tables/" + BASE)).andReturn().getResponse().getContentAsString());
        final long generation = generation(BASE);
        final String importId = importId(importAccess("/v1/catalog/import", bundle.toString(), "FAIL_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("completed"))
                .andExpect(jsonPath("$.access[0].import_action").value("REPLACE"))
                .andExpect(jsonPath("$.access[0].status").value("replaced")));
        final JsonNode roles = json.readTree(access(BASE));
        assertThat(roles.get(READERS).get("privileges").toString()).isEqualTo("[\"read\"]");
        assertThat(roles.get("cx-finance").toString()).isEqualTo(financeBefore);
        final JsonNode tableAfter = json.readTree(mockMvc.perform(get("/v1/tables/" + BASE)).andReturn().getResponse().getContentAsString());
        assertThat(tableAfter.get("columns")).isEqualTo(tableBefore.get("columns"));
        assertThat(generation(BASE)).isEqualTo(generation + 1);
        assertThat(types(filtered(importActivities(importId), "Table", BASE))).containsExactly("Update");

        final ObjectNode lacking = bundle.deepCopy();
        ((ObjectNode) lacking.get("access").get(BASE)).set(READERS, json.readTree("{\"privileges\":[\"read\"],\"columns\":[\"cx_nope\"]}"));
        importAccess("/v1/catalog/import/preview", lacking.toString(), "FAIL_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access[0].import_action").value("FAIL"))
                .andExpect(jsonPath("$.access[0].problems[*]").value(hasItem(containsString("cx_nope"))));
        importAccess("/v1/catalog/import", lacking.toString(), "FAIL_IF_EXISTS")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("catalog_import_invalid"));

        // Beside a pipeline refused at planning, the entry is still checked and reported.
        final ObjectNode rollup = (ObjectNode) json.readTree(export("{\"components\":[{\"type\":\"pipeline\",\"name\":\"" + ROLLUP
                + "\",\"dependencies\":false}]}")).get("pipelines").get(0);
        ((ObjectNode) rollup.get("pipeline")).putArray("inputs").add("cx_missing");
        deletePipeline(ROLLUP);
        final ObjectNode mixed = bundle.deepCopy();
        ((ArrayNode) mixed.get("pipelines")).add(rollup);
        putAccess(BASE, READERS, "get");
        importAccess("/v1/catalog/import/preview", mixed.toString(), "FAIL_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[0].import_action").value("FAIL"))
                .andExpect(jsonPath("$.pipelines[0].problems[*]").value(hasItem(containsString("cx_missing"))))
                .andExpect(jsonPath("$.access[0].import_action").value("REPLACE"));
        importAccess("/v1/catalog/import", mixed.toString(), "FAIL_IF_EXISTS")
                .andExpect(status().isUnprocessableContent());

        forget();
        assertBundleProblem(bundle, "'" + BASE + "'");
    }

    @Test
    void accessOnlyTablesCarryNoDefinitionAndPullNothingIn() throws Exception {
        putEntryJson(BASE, "cx-analysts", "{\"privileges\":[\"read\"],\"columns\":[\"event_id\",\"" + SENT + ".sentiment\"]}");
        final JsonNode base = json.readTree(export(accessOnlyOf(BASE)));
        assertThat(base.get("tables")).isEmpty();
        assertThat(base.get("required_system_tables")).isEmpty();
        assertThat(base.get("access").get(BASE).get("cx-analysts").get("columns").toString()).contains(SENT + ".sentiment");

        final String payload = "dial_usage_log_payload";
        putEntryJson(SYSTEM, "cx-analysts", "{\"privileges\":[\"read\"],\"columns\":[\"" + firstColumn(SYSTEM) + "\",\"" + DUL
                + ".note\",\"" + payload + "." + firstColumn(payload) + "\"]}");
        exportCall("/v1/catalog/export/preview", accessOnlyOf(SYSTEM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects").isEmpty())
                .andExpect(jsonPath("$.required_system_tables[*].name").value(contains(SYSTEM)))
                .andExpect(jsonPath("$.required_system_tables[0].reason").value("selected"));
        final String systemOnly = export(accessOnlyOf(SYSTEM));
        assertThat(json.readTree(systemOnly).get("access").get(SYSTEM).has("cx-analysts")).isTrue();

        // Reached by another component's closure, the system table follows its column policies after all.
        exportCall("/v1/catalog/export/preview", "{\"components\":[{\"type\":\"pipeline\",\"name\":\"" + DUL_LIVE + "\"},"
                        + "{\"type\":\"table\",\"name\":\"" + SYSTEM + "\",\"access_only\":true}],\"include_access\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required_system_tables[*].name").value(hasItem(SYSTEM)))
                .andExpect(jsonPath("$.required_system_tables[?(@.name == '" + payload + "')].reason")
                        .value(hasItem("column policy of " + SYSTEM)));

        // On a target without the enrichment the policy names, that entry is refused, naming the column.
        forget();
        importAccess("/v1/catalog/import/preview", systemOnly, "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access[?(@.role == 'cx-analysts')].import_action").value(hasItem("FAIL")))
                .andExpect(jsonPath("$.access[?(@.role == 'cx-analysts')].problems[*]").value(hasItem(containsString(DUL + ".note"))));
    }

    @Test
    void accessOnlyTableIsExportedWhateverItsState() throws Exception {
        jdbc.update("UPDATE table_definition SET status = 'PENDING' WHERE name = ?", BASE);
        try {
            exportCall("/v1/catalog/export", accessOnlyOf(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.access." + BASE + "['" + READERS + "'].privileges[*]").value(contains("read")));
        } finally {
            jdbc.update("UPDATE table_definition SET status = 'ACTIVE' WHERE name = ?", BASE);
        }
        mockMvc.perform(post("/v1/otel/sinks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cxapp\",\"signals\":[\"logs\"]}"))
                .andExpect(status().isCreated());
        try {
            putAccess("otel_cxapp_logs", READERS, "read");
            exportCall("/v1/catalog/export", accessOnlyOf("otel_cxapp_logs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tables").isEmpty())
                    .andExpect(jsonPath("$.access.otel_cxapp_logs['" + READERS + "'].privileges[*]").value(contains("read")));
        } finally {
            mockMvc.perform(delete("/v1/otel/sinks/cxapp"));
        }
    }

    @Test
    void rolesNarrowWhatTheTableCarriesAndPullsIn() throws Exception {
        putAccess(BASE, "cx-finance", "read");
        final JsonNode whole = json.readTree(export("{\"components\":[{\"type\":\"table\",\"name\":\"" + BASE
                + "\",\"roles\":[\"cx-finance\"]}],\"include_access\":true}"));
        assertThat(names(whole.get("tables"), "table")).contains(BASE);
        assertThat(whole.get("access").get(BASE).size()).isEqualTo(1);
        assertThat(whole.get("access").get(BASE).has("cx-finance")).isTrue();

        // An unlisted role's column policy pulls nothing in, and nothing of it is reported as left behind.
        putEntryJson(SYSTEM, "cx-analysts", "{\"privileges\":[\"read\"],\"columns\":[\"" + firstColumn(SYSTEM) + "\",\"" + DUL
                + ".note\"]}");
        exportCall("/v1/catalog/export/preview", "{\"components\":[{\"type\":\"table\",\"name\":\"" + SYSTEM + "\",\"roles\":[\""
                        + READERS + "\"]}],\"include_access\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects[*].name").value(not(hasItem(DUL))))
                .andExpect(jsonPath("$.access[0].roles[*]").value(contains(READERS)))
                .andExpect(jsonPath("$.access[0].left_behind[*].role").value(not(hasItem("cx-analysts"))));

        // An access-only table another component reaches travels whole, its roles still limited.
        putAccess(ROLL, READERS, "read");
        putAccess(ROLL, "cx-finance", "read");
        exportCall("/v1/catalog/export/preview", "{\"components\":[{\"type\":\"pipeline\",\"name\":\"" + ROLLUP + "\"},"
                        + "{\"type\":\"table\",\"name\":\"" + ROLL + "\",\"access_only\":true,\"roles\":[\"" + READERS + "\"]}],"
                        + "\"include_access\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects[?(@.name == '" + ROLL + "')].reason").value(hasItem("target of " + ROLLUP)))
                .andExpect(jsonPath("$.access[?(@.table == '" + ROLL + "')].roles[*]").value(contains(READERS)));
    }

    @Test
    void selectorsAreRefusedInTheirOrder() throws Exception {
        for (final String component : List.of(
                "{\"type\":\"pipeline\",\"name\":\"" + ROLLUP + "\",\"roles\":[\"" + READERS + "\"]}",
                "{\"type\":\"table\",\"name\":\"" + BASE + "\",\"dependencies\":true}",
                "{\"type\":\"table\",\"name\":\"" + BASE + "\",\"roles\":[]}",
                "{\"type\":\"table\",\"name\":\"" + BASE + "\",\"roles\":[\" \"]}",
                "{\"type\":\"table\",\"name\":\"" + BASE + "\",\"roles\":[1]}",
                "{\"type\":\"table\",\"name\":\"" + BASE + "\",\"roles\":[[\"a\"]]}")) {
            exportCall("/v1/catalog/export", "{\"components\":[" + component + "],\"include_access\":true}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("bad_request"));
        }
        exportCall("/v1/catalog/export", "{\"components\":[{\"type\":\"table\",\"name\":\"" + BASE + "\",\"dependencies\":false}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("access_only and roles")));

        for (final String component : List.of(
                "{\"type\":\"table\",\"name\":\"" + BASE + "\",\"access_only\":true}",
                "{\"type\":\"table\",\"name\":\"" + BASE + "\",\"roles\":[\"" + READERS + "\"]}",
                "{\"type\":\"table\",\"name\":\"does_not_exist\",\"access_only\":true}")) {
            exportCall("/v1/catalog/export", "{\"components\":[" + component + "]}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("catalog_export_invalid"));
        }

        exportCall("/v1/catalog/export", "{\"components\":[{\"type\":\"table\",\"name\":\"" + BASE + "\",\"access_only\":true,"
                        + "\"roles\":[\"" + READERS + "\",\"does-not-exist\"]}],\"include_access\":true}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(jsonPath("$.message").value(containsString(BASE)))
                .andExpect(jsonPath("$.message").value(containsString("does-not-exist")));
        // The role is checked before the closure is walked: SENT's source being PENDING would otherwise be a 422.
        jdbc.update("UPDATE table_definition SET status = 'PENDING' WHERE name = ?", BASE);
        try {
            exportCall("/v1/catalog/export", "{\"components\":[{\"type\":\"table\",\"name\":\"" + SENT
                            + "\",\"roles\":[\"does-not-exist\"]}],\"include_access\":true}")
                    .andExpect(status().isNotFound());
        } finally {
            jdbc.update("UPDATE table_definition SET status = 'ACTIVE' WHERE name = ?", BASE);
        }

        final String twoWays = "{\"components\":[{\"type\":\"table\",\"name\":\"%s\"},"
                + "{\"type\":\"table\",\"name\":\"%s\",\"access_only\":true}],\"include_access\":true}";
        for (final String table : List.of(BASE, "does_not_exist")) {
            exportCall("/v1/catalog/export", twoWays.formatted(table, table))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("catalog_export_invalid"));
        }

        putAccess(BASE, "cx-a", "get");
        putAccess(BASE, "cx-b", "get");
        exportCall("/v1/catalog/export", "{\"components\":[{\"type\":\"table\",\"name\":\"" + BASE + "\"},"
                        + "{\"type\":\"table\",\"name\":\"" + BASE + "\",\"access_only\":false}],\"include_access\":true}")
                .andExpect(status().isOk());
        exportCall("/v1/catalog/export", "{\"components\":[{\"type\":\"table\",\"name\":\"" + BASE + "\",\"roles\":[\"cx-a\",\"cx-b\"]},"
                        + "{\"type\":\"table\",\"name\":\"" + BASE + "\",\"roles\":[\" cx-b\",\"cx-a\"]}],\"include_access\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access." + BASE + ".*", org.hamcrest.Matchers.hasSize(2)));
    }

    /** An export body selecting only {@code table}'s access. */
    private static String accessOnlyOf(String table) {
        return "{\"components\":[{\"type\":\"table\",\"name\":\"" + table + "\",\"access_only\":true}],\"include_access\":true}";
    }

    private ResultActions exportCall(String path, String body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String firstColumn(String table) throws Exception {
        return json.readTree(mockMvc.perform(get("/v1/tables/" + table)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).get("columns").get(0).get("name").asString();
    }

    private ResultActions putEntryAt(String table, String role, String members, long generation) throws Exception {
        return mockMvc.perform(put("/v1/tables/" + table + "/access?role=" + role).contentType(MediaType.APPLICATION_JSON)
                .content(((ObjectNode) json.readTree(members)).put("generation", generation).toString()));
    }

    /** A bundle requiring {@code dial_usage_log} and carrying only {@code roles} as its access, with no table or pipeline. */
    private String accessOnly(String roles) {
        return "{\"format\":\"adas-catalog\",\"format_version\":4,\"required_system_tables\":[\"" + SYSTEM + "\"],"
                + "\"tables\":[],\"pipelines\":[],\"access\":{\"" + SYSTEM + "\":" + roles + "}}";
    }

    /** {@code bundle} with {@code role}'s entry on {@code BASE} set to {@code entry}. */
    private ObjectNode withEntry(ObjectNode bundle, String role, String entry) {
        final ObjectNode copy = bundle.deepCopy();
        ((ObjectNode) copy.get("access").get(BASE)).set(role, json.readTree(entry));
        return copy;
    }

    private void assertAccessFails(ObjectNode bundle, String problem) throws Exception {
        importAccess("/v1/catalog/import/preview", bundle.toString(), "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access[*].import_action").value(hasItem("FAIL")))
                .andExpect(jsonPath("$.access[*].problems[*]").value(hasItem(containsString(problem))));
        importAccess("/v1/catalog/import", bundle.toString(), "SKIP_IF_EXISTS")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("catalog_import_invalid"));
        mockMvc.perform(get("/v1/tables/" + BASE)).andExpect(status().isNotFound());
    }

    private void assertBundleProblem(ObjectNode bundle, String problem) throws Exception {
        importAccess("/v1/catalog/import/preview", bundle.toString(), "SKIP_IF_EXISTS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validation_errors[*]").value(hasItem(containsString(problem))));
        importAccess("/v1/catalog/import", bundle.toString(), "SKIP_IF_EXISTS")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("catalog_import_invalid"));
    }

    private ResultActions importAccess(String path, String bundle, String policy) throws Exception {
        return importCall(path, bundle, Map.of("include_access", "true"), policy);
    }

    /** Puts {@code role}'s whole entry on {@code table} as {@code members}, at the table's current generation. */
    private void putEntryJson(String table, String role, String members) throws Exception {
        putEntry(table, role, ((ObjectNode) json.readTree(members)).put("generation", generation(table)));
    }

    /** Rewrites {@code table}'s stored grant lists in place, as an earlier build could have left them. */
    private void storedAccess(String table, Consumer<ObjectNode> change) {
        final ObjectNode access = (ObjectNode) json.readTree(jdbc.queryForObject("SELECT access::text FROM table_definition "
                + "WHERE name = ?", String.class, table));
        for (final String key : List.of("get", "read", "sensitive", "write", "modify")) {
            if (!access.has(key)) {
                access.putArray(key);
            }
        }
        change.accept(access);
        jdbc.update("UPDATE table_definition SET access = CAST(? AS jsonb) WHERE name = ?", access.toString(), table);
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

    /** Replaces {@code role}'s entry on {@code table} with {@code privileges}, at the table's current generation. */
    private void putAccess(String table, String role, String... privileges) throws Exception {
        final ObjectNode body = json.createObjectNode().put("generation", generation(table));
        final ArrayNode keys = body.putArray("privileges");
        List.of(privileges).forEach(keys::add);
        putEntry(table, role, body);
    }

    private void putEntry(String table, String role, ObjectNode body) throws Exception {
        mockMvc.perform(put("/v1/tables/" + table + "/access?role=" + role).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk());
    }

    private String fullAccess(String table) throws Exception {
        return mockMvc.perform(get("/v1/tables/" + table + "/access")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private long generation(String table) throws Exception {
        return json.readTree(fullAccess(table)).get("generation").asLong();
    }

    /** {@code table}'s role entries, without the generation (which a column change on an enrichment may move). */
    private String access(String table) throws Exception {
        return json.readTree(fullAccess(table)).get("roles").toString();
    }

    /** Puts {@code table}'s role entries back to those of {@code savedAccess}, a whole GET access document. */
    private void restoreAccess(String table, String savedAccess) throws Exception {
        final JsonNode saved = json.readTree(savedAccess).get("roles");
        for (final Map.Entry<String, JsonNode> entry : json.readTree(access(table)).properties()) {
            if (!saved.has(entry.getKey())) {
                mockMvc.perform(delete("/v1/tables/" + table + "/access?role=" + entry.getKey())).andExpect(status().isNoContent());
            } else if (!saved.get(entry.getKey()).equals(entry.getValue())) {
                putEntry(table, entry.getKey(), ((ObjectNode) saved.get(entry.getKey()).deepCopy()).put("generation", generation(table)));
            }
        }
        for (final Map.Entry<String, JsonNode> entry : saved.properties()) {
            if (!json.readTree(access(table)).has(entry.getKey())) {
                putEntry(table, entry.getKey(), ((ObjectNode) entry.getValue().deepCopy()).put("generation", generation(table)));
            }
        }
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
