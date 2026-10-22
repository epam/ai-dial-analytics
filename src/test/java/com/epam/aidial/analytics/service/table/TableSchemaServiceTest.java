package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.clickhouse.ClickHouseDdlExecutor;
import com.epam.aidial.analytics.configuration.TableLimitsProperties;
import com.epam.aidial.analytics.exception.ClickHouseQueryException;
import com.epam.aidial.analytics.exception.SystemTableException;
import com.epam.aidial.analytics.exception.TableConflictException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.table.PatchInput.Rename;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit coverage for {@link TableSchemaService}, the live-schema (PATCH) surface: it applies only to a
 * materialized ({@code ACTIVE}) table (409 otherwise), issues all physical commands as one
 * multi-command {@code ALTER}, and — the safety-critical invariant — never transitions a table to
 * {@code FAILED}: a failed {@code ALTER} leaves it {@code ACTIVE} with the catalog untouched.
 */
@ExtendWith(MockitoExtension.class)
class TableSchemaServiceTest {

    @Mock
    private TableDefinitionRepository definitions;
    @Mock
    private ColumnMappingRepository columns;
    @Mock
    private CatalogSchemaWriter writer;
    @Mock
    private TableCatalogService catalogService;
    @Mock
    private ClickHouseDdlExecutor ddlExecutor;
    @Mock
    private TableDdlRenderer ddlRenderer;
    @Mock
    private ClickHouseTypeMapper typeMapper;

    @Mock
    private PipelineInputDependents pipelineReads;
    @Mock
    private PipelineRepository pipelines;
    @Mock
    private PipelineInputDependents pipelineDeclarationReads;
    @Mock
    private com.epam.aidial.analytics.service.security.SensitiveColumnAccess sensitiveAccess;
    @Mock
    private com.epam.aidial.analytics.clickhouse.ClickHouseTableInspector inspector;
    @Mock
    private com.epam.aidial.analytics.service.table.relayout.RelayoutStateStore relayouts;


    private TableSchemaService service;

    @BeforeEach
    void setUp() {
        // Cleared by default, as every caller is in none mode, so these schema tests stay about the schema;
        // the tests of the clearance rule itself withdraw it.
        org.mockito.Mockito.lenient().when(sensitiveAccess.maySeeSensitive(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);
        service = new TableSchemaService(definitions, columns, sensitiveAccess,
                new com.epam.aidial.analytics.service.table.grant.SensitiveGrantInvariant(
                        definitions, columns, pipelines, pipelineDeclarationReads, org.mockito.Mockito.mock(com.epam.aidial.analytics.web.security.ProviderRolePrivileges.class)),
                writer, catalogService,
                ddlExecutor, ddlRenderer, typeMapper, new TableLimitsProperties(256, 1000, 10000), pipelineReads,
                org.mockito.Mockito.mock(com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyService.class), inspector, relayouts);
    }

    private static TableDefinitionEntity source(String name, TableStatus status) {
        final TableDefinitionEntity entity = new TableDefinitionEntity();
        entity.setName(name);
        entity.setType(TableType.SOURCE);
        entity.setStatus(status);
        entity.setOrderingKey(new java.util.ArrayList<>(List.of("widget_id")));
        return entity;
    }

    private static ColumnMappingEntity column(String table, String sourceName, QueryFieldType type, int ordinal) {
        final ColumnMappingEntity col = new ColumnMappingEntity();
        col.setTableName(table);
        col.setSourceName(sourceName);
        col.setName(sourceName);
        col.setType(type);
        col.setOrdinal(ordinal);
        return col;
    }

    private static PatchInput addColumn(String name, QueryFieldType type) {
        return new PatchInput(
                List.of(new ColumnInput(name, name, type, null, null, null, null, null, null, false, false)),
                List.of(), List.of(), List.of(), Change.leave());
    }

    private static PatchInput ttlPatch(Change<TtlInput> ttl) {
        return new PatchInput(List.of(), List.of(), List.of(), List.of(), ttl);
    }

    private void givenActiveWidgetMetrics(ColumnMappingEntity... cols) {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(source("widget_metrics", TableStatus.ACTIVE)));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics")).willReturn(List.of(cols));
    }

    @Test
    void patchOnNotMaterializedTableIsConflict() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(source("widget_metrics", TableStatus.PENDING)));

        assertThatThrownBy(() -> service.patchSchema("widget_metrics", addColumn("score", QueryFieldType.LONG)))
                .isInstanceOf(TableConflictException.class);
        verify(ddlExecutor, never()).execute(any());
    }

    @Test
    void patchDuringRelayoutIsConflict() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(source("widget_metrics", TableStatus.ACTIVE)));
        given(relayouts.find("widget_metrics")).willReturn(Optional.of(new com.epam.aidial.analytics.service.table.relayout.RelayoutStateStore.State(
                "widget_metrics", com.epam.aidial.analytics.service.table.relayout.RelayoutStep.COPIED, "_relayout_widget_metrics",
                null, null, null, false, null)));

        assertThatThrownBy(() -> service.patchSchema("widget_metrics", addColumn("score", QueryFieldType.LONG)))
                .isInstanceOf(TableConflictException.class)
                .hasMessageContaining("being relaid out");
        verify(ddlExecutor, never()).execute(any());
    }

    @Test
    void patchOnSystemTableIsRejected() {
        final TableDefinitionEntity def = source("dial_usage_log", TableStatus.ACTIVE);
        def.setSystem(true);
        given(definitions.findById("dial_usage_log")).willReturn(Optional.of(def));

        assertThatThrownBy(() -> service.patchSchema("dial_usage_log", addColumn("score", QueryFieldType.LONG)))
                .isInstanceOf(SystemTableException.class);
        verify(ddlExecutor, never()).execute(any());
    }

    @Test
    void addColumnOnActiveIssuesSingleAlter() {
        givenActiveWidgetMetrics(column("widget_metrics", "widget_id", QueryFieldType.STRING, 0));
        given(typeMapper.toDdlType(QueryFieldType.LONG, null, null, false)).willReturn("Int64");
        given(ddlRenderer.addColumnCommand(any())).willReturn("ADD COLUMN `score` Int64");
        given(ddlRenderer.renderAlter(eq("widget_metrics"), anyList()))
                .willReturn("ALTER TABLE `widget_metrics` ADD COLUMN `score` Int64");
        given(catalogService.get("widget_metrics")).willReturn(
                new TableView(source("widget_metrics", TableStatus.ACTIVE), List.of()));

        service.patchSchema("widget_metrics", addColumn("score", QueryFieldType.LONG));

        verify(ddlExecutor).execute("ALTER TABLE `widget_metrics` ADD COLUMN `score` Int64");
        verify(ddlRenderer).renderAlter("widget_metrics", List.of("ADD COLUMN `score` Int64"));
        verify(writer).applyPatch(eq("widget_metrics"), anyList(), anyList(), anyList(), anyList(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void heavyUpdateIssuesNoAlterAndReachesTheCatalogWriter() {
        // Marking a column heavy is catalog-only metadata: no ClickHouse ALTER is issued at all, and the
        // flag travels to the writer as a "set" change.
        givenActiveWidgetMetrics(
                column("widget_metrics", "widget_id", QueryFieldType.STRING, 0),
                column("widget_metrics", "payload", QueryFieldType.STRING, 1));
        given(catalogService.get("widget_metrics")).willReturn(
                new TableView(source("widget_metrics", TableStatus.ACTIVE), List.of()));

        service.patchSchema("widget_metrics", new PatchInput(
                List.of(), List.of(), List.of(),
                List.of(new PatchInput.ColumnMetadataUpdate("payload", Change.leave(), Change.leave(),
                        Change.leave(), Change.leave(), Change.set(true))),
                Change.leave()));

        verifyNoInteractions(ddlExecutor);
        final ArgumentCaptor<List<CatalogSchemaWriter.MetadataWrite>> updates = ArgumentCaptor.forClass(List.class);
        verify(writer).applyPatch(eq("widget_metrics"), anyList(), anyList(), anyList(), updates.capture(), any());
        assertThat(updates.getValue()).singleElement().satisfies(update -> {
            assertThat(update.exposedName()).isEqualTo("payload");
            assertThat(update.heavy().present()).isTrue();
            assertThat(update.heavy().value()).isTrue();
            assertThat(update.sensitive().present()).isFalse();
        });
    }

    /**
     * The DDL and the catalog row of one added enum column must render from the same list. The catalog row
     * is written from the trimmed domain ({@code TableColumnRules.normalizeEnumValues}), so rendering the
     * ALTER from the raw declaration would materialize {@code Enum8(' high ' = 1)} under a column the read
     * surfaces advertise as {@code high} — permanently unwritable for the value it publishes, and
     * unrepairable, since the domain is immutable.
     */
    @Test
    @SuppressWarnings("unchecked")
    void addedEnumColumnIsTrimmedForTheDdlAndTheCatalogAlike() {
        givenActiveWidgetMetrics(column("widget_metrics", "widget_id", QueryFieldType.STRING, 0));
        given(typeMapper.toDdlType(QueryFieldType.ENUM, null, List.of("high", "low"), false))
                .willReturn("Enum8('high' = 1, 'low' = 2)");
        given(ddlRenderer.addColumnCommand(any())).willReturn("ADD COLUMN `severity` Enum8('high' = 1, 'low' = 2)");
        given(ddlRenderer.renderAlter(eq("widget_metrics"), anyList())).willReturn("ALTER ...");
        given(catalogService.get("widget_metrics")).willReturn(
                new TableView(source("widget_metrics", TableStatus.ACTIVE), List.of()));

        service.patchSchema("widget_metrics", new PatchInput(
                List.of(new ColumnInput("severity", "severity", QueryFieldType.ENUM, null,
                        List.of("  high ", "low  "), null, null, null, null, false, false)),
                List.of(), List.of(), List.of(), Change.leave()));

        // The mapper is stubbed for the trimmed list only: an un-normalized call would have returned null.
        verify(typeMapper).toDdlType(QueryFieldType.ENUM, null, List.of("high", "low"), false);
        final ArgumentCaptor<List<CatalogSchemaWriter.AppendWrite>> adds = ArgumentCaptor.forClass(List.class);
        verify(writer).applyPatch(eq("widget_metrics"), anyList(), anyList(), adds.capture(), anyList(), any());
        assertThat(adds.getValue()).singleElement().satisfies(add ->
                assertThat(add.column().enumValues()).containsExactly("high", "low"));
    }

    @Test
    void ttlSetIssuesModifyTtlCommand() {
        givenActiveWidgetMetrics(
                column("widget_metrics", "widget_id", QueryFieldType.STRING, 0),
                column("widget_metrics", "created_at", QueryFieldType.TIMESTAMP, 1));
        given(ddlRenderer.modifyTtlCommand(any()))
                .willReturn("MODIFY TTL toDateTime(`created_at`) + INTERVAL 90 DAY");
        given(ddlRenderer.renderAlter(eq("widget_metrics"), anyList())).willReturn("ALTER ...");
        given(catalogService.get("widget_metrics")).willReturn(
                new TableView(source("widget_metrics", TableStatus.ACTIVE), List.of()));

        final Change<TtlInput> ttl = Change.set(new TtlInput("created_at", 90, TtlUnit.DAY));
        service.patchSchema("widget_metrics", ttlPatch(ttl));

        verify(ddlRenderer).modifyTtlCommand(
                new TableDdlRenderer.TtlDdl("created_at", 90, TtlUnit.DAY, true));
        verify(ddlExecutor).execute("ALTER ...");
        verify(writer).applyPatch(eq("widget_metrics"), anyList(), anyList(), anyList(), anyList(), eq(ttl));
    }

    @Test
    void ttlRemovalOnTableWithTtlIssuesRemoveTtl() {
        final TableDefinitionEntity def = source("widget_metrics", TableStatus.ACTIVE);
        def.setTtl(new TtlInput("created_at", 90, TtlUnit.DAY));
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(def));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics")).willReturn(List.of(
                column("widget_metrics", "widget_id", QueryFieldType.STRING, 0),
                column("widget_metrics", "created_at", QueryFieldType.TIMESTAMP, 1)));
        given(ddlRenderer.removeTtlCommand()).willReturn("REMOVE TTL");
        given(ddlRenderer.renderAlter("widget_metrics", List.of("REMOVE TTL")))
                .willReturn("ALTER TABLE `widget_metrics` REMOVE TTL");
        given(catalogService.get("widget_metrics")).willReturn(new TableView(def, List.of()));

        service.patchSchema("widget_metrics", ttlPatch(Change.set(null)));

        verify(ddlExecutor).execute("ALTER TABLE `widget_metrics` REMOVE TTL");
    }

    @Test
    void ttlRemovalWithoutStoredTtlIsIdempotentNoOp() {
        givenActiveWidgetMetrics(column("widget_metrics", "widget_id", QueryFieldType.STRING, 0));
        given(catalogService.get("widget_metrics")).willReturn(
                new TableView(source("widget_metrics", TableStatus.ACTIVE), List.of()));

        service.patchSchema("widget_metrics", ttlPatch(Change.set(null)));

        verify(ddlExecutor, never()).execute(any());
    }

    @Test
    void ttlOnUnknownColumnIsRejected() {
        givenActiveWidgetMetrics(column("widget_metrics", "widget_id", QueryFieldType.STRING, 0));

        assertThatThrownBy(() -> service.patchSchema("widget_metrics",
                ttlPatch(Change.set(new TtlInput("nope", 1, TtlUnit.DAY)))))
                .isInstanceOf(TableValidationException.class);
        verify(ddlExecutor, never()).execute(any());
    }

    @Test
    void ttlOnNonTimeColumnIsRejected() {
        givenActiveWidgetMetrics(column("widget_metrics", "widget_id", QueryFieldType.STRING, 0));

        assertThatThrownBy(() -> service.patchSchema("widget_metrics",
                ttlPatch(Change.set(new TtlInput("widget_id", 1, TtlUnit.DAY)))))
                .isInstanceOf(TableValidationException.class);
        verify(ddlExecutor, never()).execute(any());
    }

    @Test
    void ttlOnNullableColumnIsRejected() {
        final ColumnMappingEntity nullableTs = column("widget_metrics", "seen_at", QueryFieldType.TIMESTAMP, 1);
        nullableTs.setNullable(true);
        givenActiveWidgetMetrics(column("widget_metrics", "widget_id", QueryFieldType.STRING, 0), nullableTs);

        assertThatThrownBy(() -> service.patchSchema("widget_metrics",
                ttlPatch(Change.set(new TtlInput("seen_at", 1, TtlUnit.DAY)))))
                .isInstanceOf(TableValidationException.class);
        verify(ddlExecutor, never()).execute(any());
    }

    @Test
    void ttlMayReferenceJustAddedColumn() {
        givenActiveWidgetMetrics(column("widget_metrics", "widget_id", QueryFieldType.STRING, 0));
        given(typeMapper.toDdlType(QueryFieldType.TIMESTAMP, null, null, false)).willReturn("DateTime64(3)");
        given(ddlRenderer.addColumnCommand(any())).willReturn("ADD COLUMN `event_time` DateTime64(3)");
        given(ddlRenderer.modifyTtlCommand(any()))
                .willReturn("MODIFY TTL toDateTime(`event_time`) + INTERVAL 30 DAY");
        given(ddlRenderer.renderAlter(eq("widget_metrics"), anyList())).willReturn("ALTER ...");
        given(catalogService.get("widget_metrics")).willReturn(
                new TableView(source("widget_metrics", TableStatus.ACTIVE), List.of()));

        final PatchInput patch = new PatchInput(
                List.of(new ColumnInput("event_time", "event_time", QueryFieldType.TIMESTAMP,
                        null, null, false, null, null, null, false, false)),
                List.of(), List.of(), List.of(),
                Change.set(new TtlInput("event_time", 30, TtlUnit.DAY)));
        service.patchSchema("widget_metrics", patch);

        verify(ddlRenderer).renderAlter("widget_metrics", List.of(
                "ADD COLUMN `event_time` DateTime64(3)",
                "MODIFY TTL toDateTime(`event_time`) + INTERVAL 30 DAY"));
    }

    @Test
    void dropOfTtlReferencedColumnIsRejected() {
        final TableDefinitionEntity def = source("widget_metrics", TableStatus.ACTIVE);
        def.setTtl(new TtlInput("created_at", 90, TtlUnit.DAY));
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(def));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics")).willReturn(List.of(
                column("widget_metrics", "widget_id", QueryFieldType.STRING, 0),
                column("widget_metrics", "created_at", QueryFieldType.TIMESTAMP, 1)));

        final PatchInput patch = new PatchInput(List.of(), List.of("created_at"), List.of(), List.of(),
                Change.set(null));
        assertThatThrownBy(() -> service.patchSchema("widget_metrics", patch))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("referenced by the table TTL");
        verify(ddlExecutor, never()).execute(any());
    }

    // --- scan metadata: renameable, not droppable ---------------------------------------------------

    private static TableDefinitionEntity scannableWidgetMetrics() {
        final TableDefinitionEntity def = source("widget_metrics", TableStatus.ACTIVE);
        def.setIdentityColumn("widget_key");
        def.setVersionColumn("seen_at");
        return def;
    }

    private void givenScannableWidgetMetrics() {
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(scannableWidgetMetrics()));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics")).willReturn(List.of(
                column("widget_metrics", "widget_id", QueryFieldType.STRING, 0),
                column("widget_metrics", "widget_key", QueryFieldType.STRING, 1),
                column("widget_metrics", "seen_at", QueryFieldType.TIMESTAMP, 2)));
    }

    @Test
    void dropOfIdentityColumnIsRejected() {
        givenScannableWidgetMetrics();

        assertThatThrownBy(() -> service.patchSchema("widget_metrics",
                new PatchInput(List.of(), List.of("widget_key"), List.of(), List.of(), Change.leave())))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("identity column 'widget_key' cannot be dropped");
        verify(ddlExecutor, never()).execute(any());
    }

    @Test
    void dropOfVersionColumnIsRejected() {
        givenScannableWidgetMetrics();

        assertThatThrownBy(() -> service.patchSchema("widget_metrics",
                new PatchInput(List.of(), List.of("seen_at"), List.of(), List.of(), Change.leave())))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("version column 'seen_at' cannot be dropped");
        verify(ddlExecutor, never()).execute(any());
    }

    /** The asymmetry: a rename has a repoint, so it is allowed and reaches the writer. */
    @Test
    void renameOfVersionColumnIsAllowedAndReachesTheWriter() {
        givenScannableWidgetMetrics();
        given(ddlRenderer.renameColumnCommand("seen_at", "ingested_at"))
                .willReturn("RENAME COLUMN `seen_at` TO `ingested_at`");
        given(ddlRenderer.renderAlter(eq("widget_metrics"), anyList())).willReturn("ALTER ...");
        given(catalogService.get("widget_metrics")).willReturn(
                new TableView(scannableWidgetMetrics(), List.of()));

        service.patchSchema("widget_metrics", new PatchInput(List.of(), List.of(),
                List.of(new Rename("seen_at", "ingested_at")), List.of(), Change.leave()));

        verify(ddlExecutor).execute("ALTER ...");
        final ArgumentCaptor<List<CatalogSchemaWriter.RenameWrite>> renames = renameCaptor();
        verify(writer).applyPatch(eq("widget_metrics"), anyList(), renames.capture(), anyList(), anyList(), any());
        assertThat(renames.getValue()).singleElement().satisfies(rename -> {
            assertThat(rename.fromSourceName()).isEqualTo("seen_at");
            assertThat(rename.to()).isEqualTo("ingested_at");
        });
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<CatalogSchemaWriter.RenameWrite>> renameCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    // --- a source column an enrichment is keyed on is immutable -------------------------------------

    private void givenEnrichmentKeyedOnWidgetKey() {
        final TableDefinitionEntity enrichment = new TableDefinitionEntity();
        enrichment.setName("widget_labels");
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setStatus(TableStatus.ACTIVE);
        enrichment.setSourceTable("widget_metrics");
        enrichment.setGrainKey("widget_key");
        given(definitions.findBySourceTable("widget_metrics")).willReturn(List.of(enrichment));
    }

    @Test
    void renameOfSourceColumnReadByPipelineIsRejected() {
        // A source column read by a stored declaration. Before this guard the check returned early for a
        // SOURCE, so a TABLE_MODIFY holder could free the name an expression reads.
        givenScannableWidgetMetrics();
        given(pipelineReads.pipelinesReadingSourceColumn("widget_metrics", "seen_at"))
                .willReturn(List.of("p_notes"));

        assertThatThrownBy(() -> service.patchSchema("widget_metrics", new PatchInput(List.of(), List.of(),
                List.of(new Rename("seen_at", "ingested_at")), List.of(), Change.leave())))
                .isInstanceOf(TableConflictException.class)
                .hasMessageContaining("p_notes")
                .hasMessageContaining("source 'widget_metrics'");
        verify(ddlExecutor, never()).execute(any());
    }

    @Test
    void renameOntoSourceColumnReadByPipelineIsRejected() {
        // The other half of a repoint, and the one that carries the values: freeing the name is refused
        // above, so the attack needs the sensitive column to move INTO a name already being read.
        givenScannableWidgetMetrics();
        given(pipelineReads.pipelinesReadingSourceColumn("widget_metrics", "seen_at"))
                .willReturn(List.of());
        given(pipelineReads.pipelinesReadingSourceColumn("widget_metrics", "widget_ref"))
                .willReturn(List.of("p_notes"));

        assertThatThrownBy(() -> service.patchSchema("widget_metrics", new PatchInput(List.of(), List.of(),
                List.of(new Rename("seen_at", "widget_ref")), List.of(), Change.leave())))
                .isInstanceOf(TableConflictException.class)
                .hasMessageContaining("p_notes")
                .hasMessageContaining("renamed onto");
        verify(ddlExecutor, never()).execute(any());
    }

    @Test
    void renameOfColumnKeyedOnByEnrichmentIsRejected() {
        givenScannableWidgetMetrics();
        givenEnrichmentKeyedOnWidgetKey();

        assertThatThrownBy(() -> service.patchSchema("widget_metrics", new PatchInput(List.of(), List.of(),
                List.of(new Rename("widget_key", "widget_ref")), List.of(), Change.leave())))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("grain key of enrichment 'widget_labels'");
        verify(ddlExecutor, never()).execute(any());
    }

    @Test
    void dropOfColumnKeyedOnByEnrichmentIsRejected() {
        givenScannableWidgetMetrics();
        givenEnrichmentKeyedOnWidgetKey();

        assertThatThrownBy(() -> service.patchSchema("widget_metrics",
                new PatchInput(List.of(), List.of("widget_key"), List.of(), List.of(), Change.leave())))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("grain key of enrichment 'widget_labels'");
        verify(ddlExecutor, never()).execute(any());
    }

    /** A not-yet-materialized enrichment contributes no join, and re-resolves its grain when it does. */
    @Test
    void renameIsAllowedWhenTheKeyedEnrichmentIsNotMaterialized() {
        givenScannableWidgetMetrics();
        final TableDefinitionEntity pending = new TableDefinitionEntity();
        pending.setName("widget_labels");
        pending.setType(TableType.ENRICHMENT);
        pending.setStatus(TableStatus.PENDING);
        pending.setSourceTable("widget_metrics");
        given(definitions.findBySourceTable("widget_metrics")).willReturn(List.of(pending));
        given(ddlRenderer.renameColumnCommand("widget_key", "widget_ref"))
                .willReturn("RENAME COLUMN `widget_key` TO `widget_ref`");
        given(ddlRenderer.renderAlter(eq("widget_metrics"), anyList())).willReturn("ALTER ...");
        given(catalogService.get("widget_metrics")).willReturn(
                new TableView(scannableWidgetMetrics(), List.of()));

        service.patchSchema("widget_metrics", new PatchInput(List.of(), List.of(),
                List.of(new Rename("widget_key", "widget_ref")), List.of(), Change.leave()));

        verify(ddlExecutor).execute("ALTER ...");
    }

    // --- an enrichment's provenance columns may be dropped, not renamed ------------------------------

    @Test
    void renameOfEnrichmentProvenanceColumnIsRejected() {
        final TableDefinitionEntity enrichment = new TableDefinitionEntity();
        enrichment.setName("widget_labels");
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setStatus(TableStatus.ACTIVE);
        enrichment.setSourceTable("widget_metrics");
        enrichment.setGrainKey("widget_key");
        given(definitions.findById("widget_labels")).willReturn(Optional.of(enrichment));
        given(columns.findByTableNameOrderByOrdinal("widget_labels")).willReturn(List.of(
                column("widget_labels", "widget_key", QueryFieldType.STRING, 0),
                column("widget_labels", "enriched_at", QueryFieldType.TIMESTAMP, 1)));

        assertThatThrownBy(() -> service.patchSchema("widget_labels", new PatchInput(List.of(), List.of(),
                List.of(new Rename("enriched_at", "labelled_at")), List.of(), Change.leave())))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("provenance column 'enriched_at' cannot be renamed");
        verify(ddlExecutor, never()).execute(any());
    }

    @Test
    void renameOntoFreedProvenanceNameIsRejected() {
        final TableDefinitionEntity enrichment = new TableDefinitionEntity();
        enrichment.setName("widget_labels");
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setStatus(TableStatus.ACTIVE);
        enrichment.setSourceTable("widget_metrics");
        enrichment.setGrainKey("widget_key");
        given(definitions.findById("widget_labels")).willReturn(Optional.of(enrichment));
        given(columns.findByTableNameOrderByOrdinal("widget_labels")).willReturn(List.of(
                column("widget_labels", "widget_key", QueryFieldType.STRING, 0),
                column("widget_labels", "label", QueryFieldType.STRING, 1),
                column("widget_labels", "enriched_at", QueryFieldType.TIMESTAMP, 2)));

        assertThatThrownBy(() -> service.patchSchema("widget_labels", new PatchInput(List.of(), List.of("enriched_at"),
                List.of(new Rename("label", "enriched_at")), List.of(), Change.leave())))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("cannot be renamed to 'enriched_at'");
        verify(ddlExecutor, never()).execute(any());
    }

    /** Provenance is an enrichment concept: a source column that happens to share a name renames freely. */
    @Test
    void renameOfSourceColumnNamedLikeProvenanceIsAllowed() {
        givenActiveWidgetMetrics(column("widget_metrics", "widget_id", QueryFieldType.STRING, 0),
                column("widget_metrics", "model", QueryFieldType.STRING, 1));
        given(ddlRenderer.renameColumnCommand("model", "model_name"))
                .willReturn("RENAME COLUMN `model` TO `model_name`");
        given(ddlRenderer.renderAlter(eq("widget_metrics"), anyList())).willReturn("ALTER ...");
        given(catalogService.get("widget_metrics")).willReturn(
                new TableView(source("widget_metrics", TableStatus.ACTIVE), List.of()));

        service.patchSchema("widget_metrics", new PatchInput(List.of(), List.of(),
                List.of(new Rename("model", "model_name")), List.of(), Change.leave()));

        verify(ddlExecutor).execute("ALTER ...");
    }

    // --- the sensitivity clearance --------------------------------------------------------------------

    private void withdrawClearance() {
        given(sensitiveAccess.maySeeSensitive("widget_metrics")).willReturn(false);
    }

    private void givenWidgetMetricsWithSensitiveName() {
        final ColumnMappingEntity name = column("widget_metrics", "employee_name", QueryFieldType.STRING, 1);
        name.setSensitive(true);
        givenActiveWidgetMetrics(column("widget_metrics", "widget_id", QueryFieldType.STRING, 0), name);
    }

    @Test
    void withoutTheClearanceHiddenColumnIsUnknownToEveryOperation() {
        givenWidgetMetricsWithSensitiveName();
        withdrawClearance();

        final PatchInput drop = new PatchInput(List.of(), List.of("employee_name"), List.of(), List.of(), Change.leave());
        final PatchInput rename = new PatchInput(List.of(), List.of(),
                List.of(new PatchInput.Rename("employee_name", "full_name")), List.of(), Change.leave());
        for (final PatchInput patch : List.of(drop, rename, sensitiveUpdate("employee_name", false))) {
            assertThatThrownBy(() -> service.patchSchema("widget_metrics", patch))
                    .as("a hidden column must read exactly as a column that does not exist")
                    .isInstanceOf(TableValidationException.class)
                    .hasMessage("unknown column 'employee_name'");
        }
        verifyNoInteractions(ddlExecutor);
        verify(writer, never()).applyPatch(any(), anyList(), anyList(), anyList(), anyList(), any());
    }

    @Test
    void withoutTheClearanceTheFlagMayNotBeSetOnVisibleColumn() {
        givenWidgetMetricsWithSensitiveName();
        withdrawClearance();

        assertThatThrownBy(() -> service.patchSchema("widget_metrics", sensitiveUpdate("widget_id", true)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("requires TABLE_SENSITIVE");
        verify(writer, never()).applyPatch(any(), anyList(), anyList(), anyList(), anyList(), any());
    }

    @Test
    void withoutTheClearanceSensitiveColumnMayNotBeAdded() {
        givenWidgetMetricsWithSensitiveName();
        withdrawClearance();
        final PatchInput add = new PatchInput(
                List.of(new ColumnInput("salary", "salary", QueryFieldType.LONG, null, null, null, null, null, null,
                        true, false)),
                List.of(), List.of(), List.of(), Change.leave());

        assertThatThrownBy(() -> service.patchSchema("widget_metrics", add))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("declaring column 'salary' sensitive");
        verifyNoInteractions(ddlExecutor);
    }

    @Test
    void withoutTheClearanceNameCollisionWithHiddenColumnIsStillRefused() {
        givenWidgetMetricsWithSensitiveName();
        withdrawClearance();

        assertThatThrownBy(() -> service.patchSchema("widget_metrics",
                addColumn("employee_name", QueryFieldType.STRING)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("already exists");
        verifyNoInteractions(ddlExecutor);
    }

    /** A hidden column whose exposed and physical names differ, so each collision check is tested alone. */
    private void givenWidgetMetricsWithDivergentSensitiveName() {
        final ColumnMappingEntity email = column("widget_metrics", "user_email", QueryFieldType.STRING, 1);
        email.setName("email");
        email.setSensitive(true);
        givenActiveWidgetMetrics(column("widget_metrics", "widget_id", QueryFieldType.STRING, 0), email,
                column("widget_metrics", "label", QueryFieldType.STRING, 2));
    }

    @Test
    void withoutTheClearanceAddCollidingOnlyOnTheHiddenExposedNameIsRefused() {
        givenWidgetMetricsWithDivergentSensitiveName();
        withdrawClearance();
        final PatchInput add = new PatchInput(
                List.of(new ColumnInput("e2", "email", QueryFieldType.STRING, null, null, null, null, null, null,
                        false, false)),
                List.of(), List.of(), List.of(), Change.leave());

        assertThatThrownBy(() -> service.patchSchema("widget_metrics", add))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("already exists");
        verifyNoInteractions(ddlExecutor);
    }

    @Test
    void withoutTheClearanceRenameOntoTheHiddenExposedNameIsRefused() {
        givenWidgetMetricsWithDivergentSensitiveName();
        withdrawClearance();
        final PatchInput rename = new PatchInput(List.of(), List.of(),
                List.of(new PatchInput.Rename("label", "email")), List.of(), Change.leave());

        assertThatThrownBy(() -> service.patchSchema("widget_metrics", rename))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("collides");
        verifyNoInteractions(ddlExecutor);
    }

    @Test
    void withoutTheClearanceTheColumnLimitStillCountsHiddenColumns() {
        givenWidgetMetricsWithDivergentSensitiveName();
        withdrawClearance();
        service = new TableSchemaService(definitions, columns, sensitiveAccess,
                new com.epam.aidial.analytics.service.table.grant.SensitiveGrantInvariant(definitions, columns,
                        pipelines, pipelineDeclarationReads,
                        org.mockito.Mockito.mock(com.epam.aidial.analytics.web.security.ProviderRolePrivileges.class)),
                writer, catalogService, ddlExecutor, ddlRenderer, typeMapper,
                new TableLimitsProperties(3, 1000, 10000), pipelineReads,
                org.mockito.Mockito.mock(com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyService.class), inspector, relayouts);

        assertThatThrownBy(() -> service.patchSchema("widget_metrics", addColumn("score", QueryFieldType.LONG)))
                .as("three columns exist, one of them hidden; a fourth breaks a limit of three for every caller")
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("at most 3 columns");
        verifyNoInteractions(ddlExecutor);
    }

    @Test
    void withoutTheClearanceTtlOnHiddenColumnIsUnknown() {
        final ColumnMappingEntity birth = column("widget_metrics", "birth_date", QueryFieldType.TIMESTAMP, 1);
        birth.setSensitive(true);
        givenActiveWidgetMetrics(column("widget_metrics", "widget_id", QueryFieldType.STRING, 0), birth);
        withdrawClearance();

        assertThatThrownBy(() -> service.patchSchema("widget_metrics",
                ttlPatch(Change.set(new TtlInput("birth_date", 1, TtlUnit.DAY)))))
                .as("no type or nullability oracle, and no expiry by a sensitive value")
                .isInstanceOf(TableValidationException.class)
                .hasMessageNotContaining("type")
                .hasMessageNotContaining("nullable");
        verifyNoInteractions(ddlExecutor);
    }

    @Test
    void withoutTheClearanceStoredTtlOnHiddenColumnIsUntouchable() {
        final TableDefinitionEntity def = source("widget_metrics", TableStatus.ACTIVE);
        def.setTtlColumn("birth_date");
        def.setTtlInterval(1);
        def.setTtlUnit(TtlUnit.DAY);
        final ColumnMappingEntity birth = column("widget_metrics", "birth_date", QueryFieldType.TIMESTAMP, 1);
        birth.setSensitive(true);
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(def));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics"))
                .willReturn(List.of(column("widget_metrics", "widget_id", QueryFieldType.STRING, 0), birth));
        withdrawClearance();

        assertThatThrownBy(() -> service.patchSchema("widget_metrics", ttlPatch(Change.set(null))))
                .isInstanceOf(com.epam.aidial.analytics.exception.SensitiveClearanceRequiredException.class)
                .hasMessageContaining("TTL");
        verifyNoInteractions(ddlExecutor);
    }

    @Test
    void withoutTheClearanceNonSensitiveMetadataStaysEditable() {
        givenWidgetMetricsWithSensitiveName();
        withdrawClearance();
        given(catalogService.get("widget_metrics")).willReturn(
                new TableView(source("widget_metrics", TableStatus.ACTIVE), List.of()));
        final PatchInput describe = new PatchInput(List.of(), List.of(), List.of(),
                List.of(new PatchInput.ColumnMetadataUpdate("widget_id", Change.leave(), Change.leave(),
                        Change.set("the widget"), Change.leave(), Change.leave())),
                Change.leave());

        service.patchSchema("widget_metrics", describe);

        verify(writer).applyPatch(eq("widget_metrics"), anyList(), anyList(), anyList(), anyList(), any());
    }

    @Test
    void withTheClearanceTheFlagIsClearedOnSensitiveColumn() {
        givenWidgetMetricsWithSensitiveName();
        given(catalogService.get("widget_metrics")).willReturn(
                new TableView(source("widget_metrics", TableStatus.ACTIVE), List.of()));

        service.patchSchema("widget_metrics", sensitiveUpdate("employee_name", false));

        verify(writer).applyPatch(eq("widget_metrics"), anyList(), anyList(), anyList(), anyList(), any());
    }

    // --- update: sensitive on a cursor column ------------------------------------------------------

    private static PatchInput sensitiveUpdate(String column, boolean sensitive) {
        return new PatchInput(List.of(), List.of(), List.of(),
                List.of(new PatchInput.ColumnMetadataUpdate(column, Change.leave(), Change.leave(),
                        Change.leave(), Change.set(sensitive), Change.leave())),
                Change.leave());
    }

    @Test
    void markingTheVersionColumnSensitiveIsRejected() {
        givenScannableWidgetMetrics();

        assertThatThrownBy(() -> service.patchSchema("widget_metrics", sensitiveUpdate("seen_at", true)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("version column 'seen_at' cannot be marked sensitive");
        verifyNoInteractions(ddlExecutor);
        verify(writer, never()).applyPatch(any(), anyList(), anyList(), anyList(), anyList(), any());
    }

    @Test
    void markingTheIdentityColumnSensitiveIsRejected() {
        givenScannableWidgetMetrics();

        assertThatThrownBy(() -> service.patchSchema("widget_metrics", sensitiveUpdate("widget_key", true)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("identity column 'widget_key' cannot be marked sensitive");
    }

    /** Only setting the flag is refused: clearing it, and every other attribute, stays permitted. */
    @Test
    void clearingSensitiveOnCursorColumnIsAllowed() {
        givenScannableWidgetMetrics();
        given(catalogService.get("widget_metrics")).willReturn(
                new TableView(scannableWidgetMetrics(), List.of()));

        service.patchSchema("widget_metrics", sensitiveUpdate("seen_at", false));

        verifyNoInteractions(ddlExecutor);
        verify(writer).applyPatch(eq("widget_metrics"), anyList(), anyList(), anyList(), anyList(), any());
    }

    @Test
    void markingNonCursorColumnSensitiveIsAllowed() {
        givenScannableWidgetMetrics();
        given(catalogService.get("widget_metrics")).willReturn(
                new TableView(scannableWidgetMetrics(), List.of()));

        service.patchSchema("widget_metrics", sensitiveUpdate("widget_id", true));

        verify(writer).applyPatch(eq("widget_metrics"), anyList(), anyList(), anyList(), anyList(), any());
    }

    /**
     * The check is against the pair as the request leaves it: renaming the version column and marking the
     * renamed column sensitive in one request is the same violation, spelled differently.
     */
    @Test
    void markingJustRenamedCursorColumnSensitiveIsRejected() {
        givenScannableWidgetMetrics();

        assertThatThrownBy(() -> service.patchSchema("widget_metrics", new PatchInput(List.of(), List.of(),
                List.of(new Rename("seen_at", "ingested_at")),
                List.of(new PatchInput.ColumnMetadataUpdate("ingested_at", Change.leave(), Change.leave(),
                        Change.leave(), Change.set(true), Change.leave())),
                Change.leave())))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("version column 'ingested_at' cannot be marked sensitive");
        verify(ddlExecutor, never()).execute(any());
    }

    @Test
    void failedAlterLeavesTableActiveAndCatalogUntouched() {
        givenActiveWidgetMetrics(column("widget_metrics", "widget_id", QueryFieldType.STRING, 0));
        given(typeMapper.toDdlType(QueryFieldType.LONG, null, null, false)).willReturn("Int64");
        given(ddlRenderer.addColumnCommand(any())).willReturn("ADD COLUMN `score` Int64");
        given(ddlRenderer.renderAlter(eq("widget_metrics"), anyList()))
                .willReturn("ALTER TABLE `widget_metrics` ADD COLUMN `score` Int64");
        willThrow(new ClickHouseQueryException("rejected", null)).given(ddlExecutor).execute(any());

        assertThatThrownBy(() -> service.patchSchema("widget_metrics", addColumn("score", QueryFieldType.LONG)))
                .isInstanceOf(ClickHouseQueryException.class);
        // The FAILED invariant: a live ALTER failure never flips the table to FAILED (or any status),
        // and — because the catalog is written only after the single ALTER succeeds — nothing is persisted.
        verify(writer, never()).markStatus(any(), any());
        verify(writer, never()).applyPatch(any(), anyList(), anyList(), anyList(), anyList(), any());
    }

    // --- an eligible enrichment not laid out aligned yet -----------------------------------------

    /** {@code dial_usage_log}'s shape, and an identity-keyed enrichment of it whose relayout has not run. */
    private void givenUnalignedEligibleEnrichment() {
        final TableDefinitionEntity usage = new TableDefinitionEntity();
        usage.setName("usage");
        usage.setType(TableType.SOURCE);
        usage.setStatus(TableStatus.ACTIVE);
        usage.setPartitionColumn("request_time");
        usage.setPartitionGranularity(PartitionGranularity.DAY);
        usage.setIdentityColumn("event_id");
        final ColumnMappingEntity requestTime = column("usage", "request_time", QueryFieldType.TIMESTAMP, 1);
        final TableDefinitionEntity enrichment = new TableDefinitionEntity();
        enrichment.setName("usage_labels");
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setStatus(TableStatus.ACTIVE);
        enrichment.setSourceTable("usage");
        enrichment.setGrainKey("event_id");
        given(definitions.findById("usage_labels")).willReturn(Optional.of(enrichment));
        given(definitions.findById("usage")).willReturn(Optional.of(usage));
        given(columns.findByTableNameOrderByOrdinal("usage_labels")).willReturn(List.of(
                column("usage_labels", "label", QueryFieldType.STRING, 0)));
        given(columns.findByTableNameOrderByOrdinal("usage")).willReturn(List.of(
                column("usage", "event_id", QueryFieldType.STRING, 0), requestTime));
    }

    /** Added now, a nullable {@code request_time} would leave the coming relayout a column it cannot adopt. */
    @Test
    void addOfUnadoptableAlignmentNameBeforeTheRelayoutIsRefused() {
        givenUnalignedEligibleEnrichment();

        assertThatThrownBy(() -> service.patchSchema("usage_labels", addColumn("request_time", QueryFieldType.TIMESTAMP)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("alignment column");
        assertThatThrownBy(() -> service.patchSchema("usage_labels", new PatchInput(List.of(), List.of(),
                List.of(new Rename("label", "request_time")), List.of(), Change.leave())))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("alignment column");
        verify(ddlExecutor, never()).execute(any());
    }

    @Test
    void addOfAdoptableAlignmentNameBeforeTheRelayoutIsAccepted() {
        givenUnalignedEligibleEnrichment();
        given(ddlRenderer.renderAlter(eq("usage_labels"), anyList())).willReturn("ALTER ...");
        given(catalogService.get("usage_labels")).willReturn(new TableView(new TableDefinitionEntity(), List.of()));

        service.patchSchema("usage_labels", new PatchInput(
                List.of(new ColumnInput("request_time", "request_time", QueryFieldType.TIMESTAMP, null, null, false,
                        null, null, null, false, false)),
                List.of(), List.of(), List.of(), Change.leave()));

        verify(ddlExecutor).execute("ALTER ...");
    }

    @Test
    void dropOfIndexedColumnDropsItsIndexFirstInTheSameAlter() {
        givenActiveWidgetMetrics(column("widget_metrics", "widget_id", QueryFieldType.STRING, 0),
                column("widget_metrics", "session", QueryFieldType.STRING, 1));
        // The index a rename left behind: named for the old column, over the one being dropped.
        given(inspector.skipIndexes("widget_metrics")).willReturn(List.of(
                new com.epam.aidial.analytics.clickhouse.ClickHouseTableInspector.SkipIndex("idx_sid", "session", "bloom_filter"),
                new com.epam.aidial.analytics.clickhouse.ClickHouseTableInspector.SkipIndex("idx_other", "other", "bloom_filter")));
        given(ddlRenderer.dropIndexCommand("idx_sid")).willReturn("DROP INDEX IF EXISTS `idx_sid`");
        given(ddlRenderer.dropColumnCommand("session")).willReturn("DROP COLUMN `session`");
        given(ddlRenderer.renderAlter(eq("widget_metrics"), anyList())).willReturn("ALTER ...");
        given(catalogService.get("widget_metrics")).willReturn(
                new TableView(source("widget_metrics", TableStatus.ACTIVE), List.of()));

        service.patchSchema("widget_metrics", new PatchInput(List.of(), List.of("session"), List.of(), List.of(), Change.leave()));

        verify(ddlRenderer).renderAlter("widget_metrics", List.of("DROP INDEX IF EXISTS `idx_sid`", "DROP COLUMN `session`"));
    }
}
