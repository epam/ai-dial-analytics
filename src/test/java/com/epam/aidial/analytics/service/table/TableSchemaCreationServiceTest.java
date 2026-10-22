package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.clickhouse.ClickHouseDdlExecutor;
import com.epam.aidial.analytics.exception.ClickHouseQueryException;
import com.epam.aidial.analytics.exception.SensitiveGrantWidensException;
import com.epam.aidial.analytics.exception.SystemTableException;
import com.epam.aidial.analytics.exception.TableConflictException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.security.Privilege;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit coverage for {@link TableSchemaCreationService#defineSchema}: it gates on the un-materialized
 * state (409 otherwise) and a system table (409), enforces schema completeness on the input (422 before
 * anything is persisted), persists the schema and issues the single {@code CREATE TABLE} flipping to
 * {@code ACTIVE}, and on a ClickHouse failure drops + marks {@code FAILED} (never leaving an
 * {@code ACTIVE} table).
 */
@ExtendWith(MockitoExtension.class)
class TableSchemaCreationServiceTest {

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
    private PipelineRepository pipelines;
    @Mock
    private PipelineInputDependents pipelineReads;
    @Mock
    private com.epam.aidial.analytics.service.security.SensitiveColumnAccess sensitiveAccess;
    @Mock
    private com.epam.aidial.analytics.clickhouse.ClickHouseTableInspector inspector;

    private TableSchemaCreationService service;

    @BeforeEach
    void setUp() {
        service = new TableSchemaCreationService(definitions, columns, writer, catalogService,
                ddlExecutor, ddlRenderer, typeMapper,
                new com.epam.aidial.analytics.service.table.grant.SensitiveGrantInvariant(
                        definitions, columns, pipelines, pipelineReads, org.mockito.Mockito.mock(com.epam.aidial.analytics.web.security.ProviderRolePrivileges.class)),
                sensitiveAccess, inspector);
        org.mockito.Mockito.lenient().when(sensitiveAccess.maySeeSensitive(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);
    }

    private static TableDefinitionEntity source(String name, TableStatus status, List<String> orderingKey) {
        final TableDefinitionEntity entity = new TableDefinitionEntity();
        entity.setName(name);
        entity.setType(TableType.SOURCE);
        entity.setStatus(status);
        entity.setOrderingKey(new ArrayList<>(orderingKey));
        return entity;
    }

    private static TableDefinitionEntity enrichment(String name, TableStatus status, String sourceTable) {
        final TableDefinitionEntity entity = new TableDefinitionEntity();
        entity.setName(name);
        entity.setType(TableType.ENRICHMENT);
        entity.setStatus(status);
        entity.setSourceTable(sourceTable);
        return entity;
    }

    private static ColumnMappingEntity column(String table, String sourceName, QueryFieldType type) {
        final ColumnMappingEntity col = new ColumnMappingEntity();
        col.setTableName(table);
        col.setSourceName(sourceName);
        col.setName(sourceName);
        col.setType(type);
        return col;
    }

    private static ColumnInput col(String name, QueryFieldType type) {
        return new ColumnInput(name, name, type, null, null, null, null, null, null, false, false);
    }

    private static ColumnInput sensitiveCol(String name, QueryFieldType type) {
        return new ColumnInput(name, name, type, null, null, null, null, null, null, true, false);
    }

    private static DefineSchemaInput sourceInput(List<ColumnInput> cols, List<String> orderingKey) {
        return new DefineSchemaInput(cols, orderingKey, null, null, null, null, null);
    }

    private static DefineSchemaInput enrichmentInput(List<ColumnInput> cols, String grainKey) {
        return new DefineSchemaInput(cols, null, null, null, grainKey, null, null);
    }

    // --- state / system gates -------------------------------------------------------------------

    @Test
    void defineSchemaOnMaterializedTableIsConflict() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(source("widget_metrics", TableStatus.ACTIVE, List.of("widget_id"))));

        assertThatThrownBy(() -> service.defineSchema("widget_metrics",
                sourceInput(List.of(col("widget_id", QueryFieldType.STRING)), List.of("widget_id"))))
                .isInstanceOf(TableConflictException.class);
        verify(writer, never()).persistSchema(any(), any());
        verify(ddlExecutor, never()).execute(any());
    }

    @Test
    void defineSchemaOnSystemTableIsRejected() {
        final TableDefinitionEntity def = source("dial_usage_log", TableStatus.PENDING, List.of());
        def.setSystem(true);
        given(definitions.findById("dial_usage_log")).willReturn(Optional.of(def));

        assertThatThrownBy(() -> service.defineSchema("dial_usage_log",
                sourceInput(List.of(col("widget_id", QueryFieldType.STRING)), List.of("widget_id"))))
                .isInstanceOf(SystemTableException.class);
        verify(writer, never()).persistSchema(any(), any());
    }

    @Test
    @DisplayName("defining a sensitive column is refused while the grants already launder it")
    void defineSchemaWithSensitiveColumnHonoursTheSubsetRule() {
        // The third way a table first comes to carry a sensitive column, and the one that left the whole
        // invariant bypassable through the front door: grant sensitive on a PENDING enrichment while
        // it has no columns at all (accepted, because the pair has nothing to protect), then define a
        // schema that declares one. Nothing downstream would have refused it, and the next pipeline
        // registration would fail over a grant edit made days earlier.
        final TableDefinitionEntity enrichment = enrichment("usage_pii", TableStatus.PENDING, "dial_usage_log");
        enrichment.getAccess().setRolesFor(Privilege.TABLE_SENSITIVE, List.of("analysts"));
        given(definitions.findById("usage_pii")).willReturn(Optional.of(enrichment));
        given(definitions.findById("dial_usage_log"))
                .willReturn(Optional.of(source("dial_usage_log", TableStatus.ACTIVE, List.of("id"))));

        assertThatThrownBy(() -> service.defineSchema("usage_pii", enrichmentInput(
                List.of(col("user_id", QueryFieldType.STRING),
                        sensitiveCol("claims", QueryFieldType.STRING)),
                "user_id")))
                .isInstanceOf(SensitiveGrantWidensException.class)
                .hasMessageContaining("dial_usage_log");
        verify(writer, never()).persistSchema(any(), any());
        verify(ddlExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("declaring a sensitive column needs the clearance on the table")
    void defineSchemaWithSensitiveColumnNeedsTheClearance() {
        final TableDefinitionEntity enrichment = enrichment("usage_pii", TableStatus.PENDING, "dial_usage_log");
        given(definitions.findById("usage_pii")).willReturn(Optional.of(enrichment));
        given(sensitiveAccess.maySeeSensitive("usage_pii")).willReturn(false);

        assertThatThrownBy(() -> service.defineSchema("usage_pii", enrichmentInput(
                List.of(col("user_id", QueryFieldType.STRING),
                        sensitiveCol("claims", QueryFieldType.STRING)),
                "user_id")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("declaring column 'claims' sensitive");
        verify(writer, never()).persistSchema(any(), any());
        verify(ddlExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("defining a schema with no sensitive column is unconstrained by the grants")
    void defineSchemaWithoutSensitiveColumnIsUnconstrained() {
        final TableDefinitionEntity enrichment = enrichment("usage_plain", TableStatus.PENDING, "dial_usage_log");
        enrichment.getAccess().setRolesFor(Privilege.TABLE_SENSITIVE, List.of("analysts"));
        enrichment.setGrainKey("user_id");
        given(definitions.findById("usage_plain")).willReturn(Optional.of(enrichment));
        given(columns.findByTableNameOrderByOrdinal("usage_plain"))
                .willReturn(List.of(column("usage_plain", "user_id", QueryFieldType.STRING)));
        given(columns.findByTableNameOrderByOrdinal("dial_usage_log"))
                .willReturn(List.of(column("dial_usage_log", "user_id", QueryFieldType.STRING)));

        service.defineSchema("usage_plain",
                enrichmentInput(List.of(col("user_id", QueryFieldType.STRING)), "user_id"));

        verify(writer).persistSchema(eq("usage_plain"), any());
        verify(writer).markStatus("usage_plain", TableStatus.ACTIVE);
    }

    // --- completeness gate (422 before persisting) ----------------------------------------------

    @Test
    void defineSourceWithoutColumnsIsValidationErrorAndPersistsNothing() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(source("widget_metrics", TableStatus.PENDING, List.of())));

        assertThatThrownBy(() -> service.defineSchema("widget_metrics",
                sourceInput(List.of(), List.of("widget_id"))))
                .isInstanceOf(TableValidationException.class);
        verify(writer, never()).persistSchema(any(), any());
        verify(ddlExecutor, never()).execute(any());
        verify(writer, never()).markStatus(any(), any());
    }

    @Test
    void defineSourceWithoutOrderingKeyIsValidationError() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(source("widget_metrics", TableStatus.PENDING, List.of())));

        assertThatThrownBy(() -> service.defineSchema("widget_metrics",
                sourceInput(List.of(col("widget_id", QueryFieldType.STRING)), List.of())))
                .isInstanceOf(TableValidationException.class);
        verify(writer, never()).persistSchema(any(), any());
    }

    @Test
    void defineEnrichmentWithoutGrainIsValidationError() {
        given(definitions.findById("widget_labels"))
                .willReturn(Optional.of(enrichment("widget_labels", TableStatus.PENDING, "widget_metrics")));

        assertThatThrownBy(() -> service.defineSchema("widget_labels",
                enrichmentInput(List.of(col("label", QueryFieldType.STRING)), null)))
                .isInstanceOf(TableValidationException.class);
        verify(writer, never()).persistSchema(any(), any());
        verify(ddlExecutor, never()).execute(any());
    }

    /**
     * An enrichment declares neither scan column: it is keyed on its grain key, and its row version is the
     * {@code ReplacingMergeTree} column the platform stamps. Either member is a 422, not a silent drop.
     */
    @Test
    void defineEnrichmentDeclaringEitherScanColumnIsValidationError() {
        given(definitions.findById("widget_labels"))
                .willReturn(Optional.of(enrichment("widget_labels", TableStatus.PENDING, "widget_metrics")));

        assertThatThrownBy(() -> service.defineSchema("widget_labels", new DefineSchemaInput(
                List.of(col("label", QueryFieldType.STRING)), null, null, null, "widget_id", null, null,
                "widget_id", null)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("identity_column is not accepted for an enrichment");

        assertThatThrownBy(() -> service.defineSchema("widget_labels", new DefineSchemaInput(
                List.of(col("label", QueryFieldType.STRING)), null, null, null, "widget_id", null, null,
                null, "_updated_at")))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("version_column is not accepted for an enrichment");

        verify(writer, never()).persistSchema(any(), any());
        verify(ddlExecutor, never()).execute(any());
    }

    // --- happy path + failure --------------------------------------------------------------------

    @Test
    void defineSourcePersistsIssuesCreateAndFlipsActive() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(source("widget_metrics", TableStatus.PENDING, List.of("widget_id"))));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics")).willReturn(List.of(
                column("widget_metrics", "widget_id", QueryFieldType.STRING),
                column("widget_metrics", "score", QueryFieldType.LONG)));
        given(typeMapper.toDdlType(QueryFieldType.STRING, null, null, false)).willReturn("String");
        given(typeMapper.toDdlType(QueryFieldType.LONG, null, null, false)).willReturn("Int64");
        given(ddlRenderer.renderCreate(any(), any())).willReturn("CREATE TABLE widget_metrics ...");
        given(catalogService.get("widget_metrics")).willReturn(
                new TableView(source("widget_metrics", TableStatus.ACTIVE, List.of("widget_id")), List.of()));

        service.defineSchema("widget_metrics", sourceInput(
                List.of(col("widget_id", QueryFieldType.STRING), col("score", QueryFieldType.LONG)),
                List.of("widget_id")));

        verify(writer).persistSchema(any(), any());
        verify(ddlExecutor).execute("CREATE TABLE widget_metrics ...");
        verify(writer).markStatus("widget_metrics", TableStatus.ACTIVE);
    }

    @Test
    void defineClickHouseFailureDropsAndMarksFailed() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(source("widget_metrics", TableStatus.PENDING, List.of("widget_id"))));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics"))
                .willReturn(List.of(column("widget_metrics", "widget_id", QueryFieldType.STRING)));
        given(typeMapper.toDdlType(QueryFieldType.STRING, null, null, false)).willReturn("String");
        given(ddlRenderer.renderCreate(any(), any())).willReturn("CREATE TABLE widget_metrics ...");
        given(ddlRenderer.renderDrop("widget_metrics")).willReturn("DROP TABLE IF EXISTS `widget_metrics`");
        lenient().doThrow(new ClickHouseQueryException("rejected", null))
                .when(ddlExecutor).execute("CREATE TABLE widget_metrics ...");

        assertThatThrownBy(() -> service.defineSchema("widget_metrics",
                sourceInput(List.of(col("widget_id", QueryFieldType.STRING)), List.of("widget_id"))))
                .isInstanceOf(ClickHouseQueryException.class);
        verify(ddlExecutor).execute("DROP TABLE IF EXISTS `widget_metrics`");
        verify(writer).markStatus("widget_metrics", TableStatus.FAILED);
        verify(writer, never()).markStatus("widget_metrics", TableStatus.ACTIVE);
    }

    @Test
    void defineEnrichmentIssuesReplacingMergeTreeCreate() {
        // The writer is mocked, so the grain key persistSchema would set is seeded on the definition the
        // spec builder reads back.
        final TableDefinitionEntity target = enrichment("widget_labels", TableStatus.PENDING, "widget_metrics");
        target.setGrainKey("widget_id");
        given(definitions.findById("widget_labels")).willReturn(Optional.of(target));
        given(columns.findByTableNameOrderByOrdinal("widget_labels"))
                .willReturn(List.of(column("widget_labels", "label", QueryFieldType.STRING)));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics"))
                .willReturn(List.of(column("widget_metrics", "widget_id", QueryFieldType.STRING)));
        given(typeMapper.toDdlType(QueryFieldType.STRING, null, null, false)).willReturn("String");
        given(ddlRenderer.renderCreate(any(), any())).willReturn("CREATE TABLE widget_labels ...");
        final TableDefinitionEntity active = enrichment("widget_labels", TableStatus.ACTIVE, "widget_metrics");
        active.setGrainKey("widget_id");
        given(catalogService.get("widget_labels")).willReturn(new TableView(active, List.of()));

        service.defineSchema("widget_labels",
                enrichmentInput(List.of(col("label", QueryFieldType.STRING)), "widget_id"));

        verify(writer).persistSchema(any(), any());
        verify(ddlExecutor).execute("CREATE TABLE widget_labels ...");
        verify(writer).markStatus("widget_labels", TableStatus.ACTIVE);
    }

    /** The inherited-TTL read happens after the schema committed, so a ClickHouse failure there is a FAILED create too. */
    @Test
    void inheritedTtlReadFailureDropsAndMarksFailed() {
        final TableDefinitionEntity target = enrichment("widget_labels", TableStatus.PENDING, "widget_metrics");
        target.setGrainKey("widget_id");
        target.setPartitionColumn("seen_at");
        target.setPartitionGranularity(PartitionGranularity.DAY);
        given(definitions.findById("widget_labels")).willReturn(Optional.of(target));
        given(columns.findByTableNameOrderByOrdinal("widget_labels"))
                .willReturn(List.of(column("widget_labels", "label", QueryFieldType.STRING)));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics")).willReturn(List.of(
                column("widget_metrics", "widget_id", QueryFieldType.STRING),
                column("widget_metrics", "seen_at", QueryFieldType.TIMESTAMP)));
        given(inspector.engineFull("widget_metrics")).willThrow(new ClickHouseQueryException("rejected", null));
        given(ddlRenderer.renderDrop("widget_labels")).willReturn("DROP TABLE IF EXISTS `widget_labels`");

        assertThatThrownBy(() -> service.defineSchema("widget_labels",
                enrichmentInput(List.of(col("label", QueryFieldType.STRING)), "widget_id")))
                .isInstanceOf(ClickHouseQueryException.class);
        verify(ddlExecutor).execute("DROP TABLE IF EXISTS `widget_labels`");
        verify(writer).markStatus("widget_labels", TableStatus.FAILED);
        verify(writer, never()).markStatus("widget_labels", TableStatus.ACTIVE);
    }
}
