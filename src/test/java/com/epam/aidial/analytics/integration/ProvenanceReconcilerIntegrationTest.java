package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.clickhouse.ClickHouseDdlExecutor;
import com.epam.aidial.analytics.configuration.ProvenanceReconcileProperties;
import com.epam.aidial.analytics.service.enrichment.support.ProvenanceColumns;
import com.epam.aidial.analytics.service.enrichment.support.ProvenanceReconciler;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.table.CatalogSchemaWriter;
import com.epam.aidial.analytics.service.table.ClickHouseTypeMapper;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.TableDdlRenderer;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code ProvenanceReconciler} against real PostgreSQL + ClickHouse.
 *
 * <p>Provenance columns are injected into an enrichment's schema at <b>creation only</b>, and the enrich
 * write drops any the target does not physically carry. So every enrichment that existed before
 * {@code pipeline_generation} was added lacks it, and without this reconciler the effect of replacing
 * {@code evaluator_version} with it would be that lineage silently <em>stops</em> rather than improves.
 *
 * <p>The reconciler is invoked <b>directly</b> rather than by restarting the context: the Spring context
 * is shared across the whole integration suite and already ran it once at boot, so a test that needed a
 * second boot would need a context of its own — another JDBC pool against a container whose connection
 * ceiling the suite already runs close to. Calling {@code run} is the same entry point the
 * {@code ApplicationRunner} contract uses, and re-running it on demand is exactly the idempotence this
 * class is built for.
 *
 * <p>The fixture drops the column the API injected, which is the only way to reach the state that exists
 * in the running service: an enrichment materialized before the column was canonical. Requires Docker.
 */
@TestPropertySource(properties = {AbstractClickHouseIntegrationTest.SCAN_SAFETY_LAG_OFF,
        AbstractClickHouseIntegrationTest.GROUP_FETCH_MAX_BYTES_4KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_WINDOW_LIMIT_3,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_MEMORY_1KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_SPILL_OFF})
class ProvenanceReconcilerIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String SOURCE = "dial_usage_log";
    private static final String TARGET = "pr_labels";
    private static final String UNTARGETED = "pr_orphan";
    private static final String GENERATION = ProvenanceColumns.PIPELINE_GENERATION;

    @Autowired
    private ProvenanceReconciler reconciler;

    @Autowired
    private PipelineRepository pipelines;

    @Autowired
    private ColumnMappingRepository columns;

    @Autowired
    private TableDefinitionRepository definitions;

    @Autowired
    private CatalogSchemaWriter catalog;

    @Autowired
    private TableDdlRenderer renderer;

    @Autowired
    private ClickHouseTypeMapper typeMapper;

    @Autowired
    private ClickHouseDdlExecutor ddl;

    @BeforeEach
    void setUp() throws Exception {
        IntegrationTables.enrichment(mockMvc, TARGET, SOURCE, "event_id",
                "[{\"source_name\":\"title\",\"name\":\"title\",\"type\":\"string\",\"nullable\":true}]");
        IntegrationTables.enrichment(mockMvc, UNTARGETED, SOURCE, "event_id",
                "[{\"source_name\":\"title\",\"name\":\"title\",\"type\":\"string\",\"nullable\":true}]");
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"pr-pipeline\","
                                + Transforms.llm().proseOutputs("title").member() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + TARGET + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}"))
                .andExpect(status().isCreated());
    }

    @AfterEach
    void tearDown() throws Exception {
        mockMvc.perform(delete("/v1/pipelines/pr-pipeline"));
        mockMvc.perform(delete("/v1/tables/" + TARGET));
        mockMvc.perform(delete("/v1/tables/" + UNTARGETED));
    }

    /**
     * Puts a target into the pre-change state: the column gone from both ClickHouse and the catalog.
     * Dropping through the API keeps the two in step, which is what makes the reconciler's job the real
     * one rather than a repair of a divergence the test invented.
     */
    private void dropGenerationColumn(String table) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/v1/tables/" + table + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"drop\":[\"" + GENERATION + "\"]}"))
                .andExpect(status().isOk());
    }

    private boolean catalogDeclares(String table) {
        return columns.findByTableNameOrderByOrdinal(table).stream()
                .anyMatch(column -> GENERATION.equals(column.getSourceName()));
    }

    private boolean clickHouseHas(String table) {
        return !queryScalar("SELECT count() AS c FROM system.columns WHERE table = '" + table
                + "' AND name = '" + GENERATION + "'", "c").equals("0");
    }

    private void reconcile() {
        reconciler.run(null);
    }

    @Test
    void targetMissingTheColumnGainsItInBothClickHouseAndTheCatalog() throws Exception {
        dropGenerationColumn(TARGET);
        assertThat(catalogDeclares(TARGET)).isFalse();
        assertThat(clickHouseHas(TARGET)).isFalse();

        reconcile();

        assertThat(clickHouseHas(TARGET)).isTrue();
        assertThat(catalogDeclares(TARGET)).isTrue();
        // The canonical declaration, taken from ProvenanceColumns rather than restated — so the
        // reconciled column is byte-for-byte the one creation-time injection writes.
        mockMvc.perform(get("/v1/tables/" + TARGET))
                .andExpect(jsonPath("$.columns[?(@.name=='" + GENERATION + "')].type",
                        org.hamcrest.Matchers.contains("long")))
                .andExpect(jsonPath("$.columns[?(@.name=='" + GENERATION + "')].tag",
                        org.hamcrest.Matchers.contains(ProvenanceColumns.SYSTEM_TAG)));
    }

    @Test
    void secondRunWritesNoSecondCatalogRow() throws Exception {
        dropGenerationColumn(TARGET);
        reconcile();
        final int columnsAfterFirst = columns.findByTableNameOrderByOrdinal(TARGET).size();

        // Idempotent by construction. The ALTER is re-issued — deliberately, since that is what heals a
        // missing physical column — and IF NOT EXISTS makes it a metadata no-op; the catalog half is the
        // conditional one, because a second row would be a duplicate-column entry nothing could resolve.
        assertThatCode(this::reconcile).doesNotThrowAnyException();

        assertThat(columns.findByTableNameOrderByOrdinal(TARGET)).hasSize(columnsAfterFirst);
    }

    /**
     * The reconciler bumps <b>nothing</b>, and that is what makes it cheaper than the operator patch it
     * replaces.
     *
     * <p>That patch would have fired {@code onTargetColumnsChanged}, which is {@code llm}-scoped and would
     * bump each {@code llm} pipeline's {@code generation} once — a runner re-sync for a column no output
     * binds to. A provenance column changes neither the composed {@code response_schema} nor the derived
     * output mapping, so there is nothing for a consumer to re-read.
     */
    @Test
    void noPipelineGenerationOrTimestampMovesAndNoClassOneMarkIsDiscarded() throws Exception {
        final long before = pipelines.findById("pr-pipeline").orElseThrow().getGeneration();
        final var updatedBefore = pipelines.findById("pr-pipeline").orElseThrow().getUpdatedAt();
        dropGenerationColumn(TARGET);
        final long afterDrop = pipelines.findById("pr-pipeline").orElseThrow().getGeneration();

        reconcile();

        final var reconciled = pipelines.findById("pr-pipeline").orElseThrow();
        assertThat(reconciled.getGeneration())
                .as("the DROP itself bumps (it changes the target's columns); the reconciler does not")
                .isEqualTo(afterDrop)
                .isGreaterThan(before - 1);
        assertThat(reconciled.getUpdatedAt()).isAfterOrEqualTo(updatedBefore);
        assertThat(reconciled.getMaterializedGeneration())
                .as("no Class-1 materialized mark is discarded, because no generation moved")
                .isNull();
    }

    /**
     * Scoped to pipeline <b>targets</b>, not to every enrichment. Two concrete reasons: a system-owned
     * enrichment cannot be patched through the API at all ({@code requireModifiable} throws), so
     * reconciling every enrichment would reach a table the API cannot; and a non-target enrichment can
     * never carry the stamp, so the column would be dead weight.
     */
    @Test
    void enrichmentNoPipelineTargetsIsLeftAlone() throws Exception {
        dropGenerationColumn(UNTARGETED);

        reconcile();

        assertThat(catalogDeclares(UNTARGETED)).isFalse();
        assertThat(clickHouseHas(UNTARGETED)).isFalse();
    }

    /** The system-owned enrichment the API cannot patch is likewise untouched, for the same reason. */
    @Test
    void theSystemOwnedEnrichmentIsLeftAlone() {
        reconcile();

        // dial_usage_log_payload is seeded by a migration and is not the target of any ENRICH pipeline,
        // so the reconciler never reaches it — which is the point of scoping to targets.
        assertThat(catalogDeclares("dial_usage_log_payload")).isFalse();
    }

    /**
     * The {@code IF NOT EXISTS} case, and the one a retried operator {@code PATCH} cannot recover from: the
     * physical column exists but the catalog row does not.
     *
     * <p>{@code TableSchemaService.patchSchema} issues the {@code ALTER} and then writes the catalog, and
     * its {@code ADD COLUMN} carries no {@code IF NOT EXISTS} — so if the catalog write fails after the
     * {@code ALTER} lands, retrying the patch passes the duplicate check (which consults the catalog),
     * re-issues the {@code ALTER}, and ClickHouse rejects it. The reconciler completes the pair instead.
     */
    @Test
    void physicalColumnWithNoCatalogRowIsCompletedRatherThanRefused() throws Exception {
        dropGenerationColumn(TARGET);
        // Re-add the physical column behind the catalog's back: the divergence a crash between the two
        // halves of a patch leaves.
        execute("ALTER TABLE " + TARGET + " ADD COLUMN " + GENERATION + " Int64");
        assertThat(clickHouseHas(TARGET)).isTrue();
        assertThat(catalogDeclares(TARGET)).isFalse();

        assertThatCode(this::reconcile).doesNotThrowAnyException();

        assertThat(catalogDeclares(TARGET)).isTrue();
        assertThat(clickHouseHas(TARGET)).isTrue();
    }

    /**
     * The other divergence, and the one that used to be unrecoverable: the <b>catalog row exists and the
     * physical column does not</b>.
     *
     * <p>Skipping a target on the strength of its catalog row — which is what a catalog-gated {@code ALTER}
     * does — makes this state permanent, and it is not a state that merely lacks the stamp. The enrich
     * write's best-effort drop tests columns derived from {@code column_mapping.source_name} rather than
     * from {@code DESCRIBE TABLE}, so it believes the column is there and keeps sending it: every
     * {@code /rows} write-back and every Class-1 materialize to this target then fails with a ClickHouse
     * unknown-column error, forever, while every later boot skips the target because the catalog row
     * causing the failure is the thing being consulted. Issuing the conditional {@code ALTER}
     * unconditionally is what closes it, and it costs a metadata no-op per target per boot.
     */
    @Test
    void catalogRowWithNoPhysicalColumnGainsTheColumnRatherThanBeingSkipped() {
        // Drop the physical column behind the catalog's back — the mirror image of the divergence above,
        // and what a Postgres-only seed of the mapping row would produce from the start.
        execute("ALTER TABLE " + TARGET + " DROP COLUMN " + GENERATION);
        assertThat(catalogDeclares(TARGET)).isTrue();
        assertThat(clickHouseHas(TARGET)).isFalse();

        reconcile();

        assertThat(clickHouseHas(TARGET)).isTrue();
        assertThat(columns.findByTableNameOrderByOrdinal(TARGET).stream()
                .filter(column -> GENERATION.equals(column.getSourceName())).count())
                .as("the catalog row it already had is kept, not duplicated")
                .isEqualTo(1);
    }

    /**
     * A failing {@code ALTER} logs, counts and <b>completes startup</b>, leaving the best-effort drop
     * working.
     *
     * <p>Aborting instead would be actively worse: ADAS's liveness probe reads the aggregate
     * {@code /actuator/health}, so a boot-failing contributor restart-loops the service over a lineage
     * column. Induced by pointing a catalog row at an enrichment whose ClickHouse table is gone, which is
     * the shape a ClickHouse hiccup produces from the reconciler's point of view.
     */
    @Test
    void failingAlterIsLoggedAndSkippedRatherThanFailingStartup() throws Exception {
        dropGenerationColumn(TARGET);
        execute("DROP TABLE " + TARGET);

        assertThatCode(this::reconcile)
                .as("warn and continue: the degraded mode is the write's own best-effort drop")
                .doesNotThrowAnyException();

        assertThat(catalogDeclares(TARGET))
                .as("no catalog row is written for a target whose ALTER failed, so the two cannot diverge")
                .isFalse();
    }

    /** Disabled, the reconciler issues nothing at all — the one setting an operator may need. */
    @Test
    void theDisabledReconcilerIssuesNothing() throws Exception {
        dropGenerationColumn(TARGET);

        new ProvenanceReconciler(pipelines, definitions, columns, catalog, renderer, typeMapper, ddl,
                new ProvenanceReconcileProperties(false), new SimpleMeterRegistry())
                .run(null);

        assertThat(catalogDeclares(TARGET)).isFalse();
        assertThat(clickHouseHas(TARGET)).isFalse();
    }
}
