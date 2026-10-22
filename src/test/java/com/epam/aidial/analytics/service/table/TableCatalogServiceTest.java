package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.clickhouse.ClickHouseDdlExecutor;
import com.epam.aidial.analytics.configuration.TableLimitsProperties;
import com.epam.aidial.analytics.exception.SystemTableException;
import com.epam.aidial.analytics.exception.TableConflictException;
import com.epam.aidial.analytics.exception.TableNotFoundException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit coverage for {@link TableCatalogService}: create records identity only (no ClickHouse DDL,
 * status {@code PENDING}); update mutates catalog metadata (description + tag ordering); delete drops
 * the ClickHouse table and blocks a source with dependents; system tables reject mutation.
 */
@ExtendWith(MockitoExtension.class)
class TableCatalogServiceTest {

    @Mock
    private TableDefinitionRepository definitions;
    @Mock
    private ColumnMappingRepository columns;
    @Mock
    private CatalogSchemaWriter writer;
    @Mock
    private ClickHouseDdlExecutor ddlExecutor;
    @Mock
    private TableDdlRenderer ddlRenderer;
    @Mock
    private PipelineRepository pipelines;
    @Mock
    private PipelineInputDependents pipelineReads;
    @Mock
    private PipelineTargetCascade pipelineTargetCascade;

    @Mock
    private com.epam.aidial.analytics.service.table.grant.TableCallerPermissions callerPermissions;
    @Mock
    private com.epam.aidial.analytics.service.security.SensitiveColumnAccess sensitiveAccess;

    private TableCatalogService service;

    @BeforeEach
    void setUp() {
        service = new TableCatalogService(definitions, columns, callerPermissions, sensitiveAccess, writer, ddlExecutor, ddlRenderer,
                new TableLimitsProperties(256, 1000, 10000), pipelines, pipelineReads, pipelineTargetCascade);
    }

    private static CreateTableInput source(String name) {
        return new CreateTableInput(name, "desc", TableType.SOURCE, null);
    }

    private static CreateTableInput enrichment(String name, String sourceTable) {
        return new CreateTableInput(name, null, TableType.ENRICHMENT, sourceTable);
    }

    private static TableDefinitionEntity definition(String name, TableType type, String sourceTable, TableStatus status) {
        final TableDefinitionEntity entity = new TableDefinitionEntity();
        entity.setName(name);
        entity.setType(type);
        entity.setSourceTable(sourceTable);
        entity.setStatus(status);
        return entity;
    }

    @Test
    void createRecordsPendingIdentityWithoutDdl() {
        given(definitions.existsById("widget_metrics")).willReturn(false);
        given(definitions.count()).willReturn(0L);
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(definition("widget_metrics", TableType.SOURCE, null, TableStatus.PENDING)));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics")).willReturn(List.of());

        final TableView created = service.create(source("widget_metrics"));

        assertThat(created.definition().getStatus()).isEqualTo(TableStatus.PENDING);
        verify(writer).persistCreate(any());
        // Create issues no ClickHouse DDL and never flips the status itself.
        verify(ddlExecutor, never()).execute(any());
        verify(writer, never()).markStatus(any(), any());
    }

    @Test
    void invalidNameIsValidationError() {
        assertThatThrownBy(() -> service.create(source("Widget_Metrics")))
                .isInstanceOf(TableValidationException.class);
        verify(writer, never()).persistCreate(any());
    }

    /**
     * The SQL surface exposes every entity a second time under the schema name {@code adas}, and its
     * catalog relations under {@code INFORMATION_SCHEMA}. SQL resolves a one-part name against schemas
     * before tables, so a table named after either would be unreachable as {@code FROM <name>} while
     * {@code /v1/queries/entities} and {@code INFORMATION_SCHEMA.TABLES} both went on listing it. Refused
     * at the point of naming, because that is the only place the problem fits in one sentence.
     */
    @Test
    void nameUsedAsSchemaBySqlSurfaceIsRejected() {
        for (final String reserved : List.of("adas", "information_schema")) {
            assertThatThrownBy(() -> service.create(source(reserved)))
                    .as(reserved)
                    .isInstanceOf(TableValidationException.class)
                    .hasMessageContaining("reserved");
        }
        verify(writer, never()).persistCreate(any());
    }

    @Test
    void duplicateNameIsConflict() {
        given(definitions.existsById("widget_metrics")).willReturn(true);

        assertThatThrownBy(() -> service.create(source("widget_metrics")))
                .isInstanceOf(TableConflictException.class);
        verify(writer, never()).persistCreate(any());
    }

    @Test
    void exceedingTableCapIsValidationError() {
        service = new TableCatalogService(definitions, columns, callerPermissions, sensitiveAccess, writer, ddlExecutor, ddlRenderer,
                new TableLimitsProperties(256, 1, 10000), pipelines, pipelineReads, pipelineTargetCascade);
        given(definitions.existsById("widget_metrics")).willReturn(false);
        given(definitions.count()).willReturn(1L);

        assertThatThrownBy(() -> service.create(source("widget_metrics")))
                .isInstanceOf(TableValidationException.class);
        verify(writer, never()).persistCreate(any());
    }

    @Test
    void enrichmentWithoutSourceTableIsValidationError() {
        given(definitions.existsById("enr")).willReturn(false);
        given(definitions.count()).willReturn(0L);

        assertThatThrownBy(() -> service.create(enrichment("enr", null)))
                .isInstanceOf(TableValidationException.class);
        verify(writer, never()).persistCreate(any());
    }

    @Test
    void enrichmentWithUnknownSourceIsValidationError() {
        given(definitions.existsById("enr")).willReturn(false);
        given(definitions.count()).willReturn(0L);
        given(definitions.findById("ghost")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(enrichment("enr", "ghost")))
                .isInstanceOf(TableValidationException.class);
    }

    @Test
    void enrichmentReferencingNonSourceIsValidationError() {
        given(definitions.existsById("enr")).willReturn(false);
        given(definitions.count()).willReturn(0L);
        given(definitions.findById("other"))
                .willReturn(Optional.of(definition("other", TableType.ENRICHMENT, "events", TableStatus.ACTIVE)));

        assertThatThrownBy(() -> service.create(enrichment("enr", "other")))
                .isInstanceOf(TableValidationException.class);
    }

    /**
     * The ingest ledger lives under this prefix; a create of its name must never reach the catalog or
     * ClickHouse, where a failed CREATE's compensating drop would remove it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"_dial_internal_ingested_blob", "_dial_internal_anything"})
    void createUnderTheInternalPrefixIsRejectedBeforeAnyWrite(String name) {
        assertThatThrownBy(() -> service.create(source(name)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("reserved for service-internal tables");

        verifyNoInteractions(definitions, writer, ddlExecutor);
    }

    @Test
    void createNonEnrichmentWithSourceTableIsValidationError() {
        given(definitions.existsById("rollup")).willReturn(false);
        given(definitions.count()).willReturn(0L);

        assertThatThrownBy(() -> service.create(
                new CreateTableInput("rollup", null, TableType.SOURCE, "events")))
                .isInstanceOf(TableValidationException.class);
        verify(writer, never()).persistCreate(any());
    }

    @Test
    void createPassesTheDeclaredWriteDisciplineThrough() {
        final ArgumentCaptor<CreateTableInput> persisted = ArgumentCaptor.forClass(CreateTableInput.class);
        given(definitions.existsById("usage_daily")).willReturn(false);
        given(definitions.count()).willReturn(0L);
        given(definitions.findById("usage_daily"))
                .willReturn(Optional.of(definition("usage_daily", TableType.SOURCE, null, TableStatus.PENDING)));
        given(columns.findByTableNameOrderByOrdinal("usage_daily")).willReturn(List.of());

        service.create(new CreateTableInput("usage_daily", null, TableType.SOURCE, null,
                TableWriteMode.UPSERT_BY_KEY, null));

        verify(writer).persistCreate(persisted.capture());
        assertThat(persisted.getValue().write()).isEqualTo(TableWriteMode.UPSERT_BY_KEY);
    }

    @Test
    void writeDisciplineOnAnEnrichmentIsValidationError() {
        given(definitions.existsById("enr")).willReturn(false);
        given(definitions.count()).willReturn(0L);
        given(definitions.findById("events"))
                .willReturn(Optional.of(definition("events", TableType.SOURCE, null, TableStatus.ACTIVE)));

        // An enrichment always collapses on its grain, so declaring a discipline for it would be a second
        // source of truth for the same fact.
        assertThatThrownBy(() -> service.create(new CreateTableInput("enr", null, TableType.ENRICHMENT,
                "events", TableWriteMode.UPSERT_BY_KEY, null)))
                .isInstanceOf(TableValidationException.class);
        verify(writer, never()).persistCreate(any());
    }

    @Test
    void deleteOfPipelineInputIsBlocked() {
        given(definitions.findById("events"))
                .willReturn(Optional.of(definition("events", TableType.SOURCE, null, TableStatus.ACTIVE)));
        given(definitions.findBySourceTable("events")).willReturn(List.of());
        final PipelineEntity reader = new PipelineEntity();
        reader.setName("usage_daily_rollup");
        given(pipelines.findByInputTable("events")).willReturn(List.of(reader));

        // Breaking the dependency here beats letting the pipeline survive pointing at nothing and fail on
        // its next refresh. (Unreachable end-to-end today — every scan source is system-owned, so the
        // system-table guard fires first — which is why it is pinned at this level.)
        assertThatThrownBy(() -> service.delete("events"))
                .isInstanceOf(TableConflictException.class)
                .hasMessageContaining("usage_daily_rollup");
        verify(ddlExecutor, never()).execute(any());
    }

    @Test
    void deleteSourceWithEnrichmentsIsBlocked() {
        given(definitions.findById("events"))
                .willReturn(Optional.of(definition("events", TableType.SOURCE, null, TableStatus.ACTIVE)));
        given(definitions.findBySourceTable("events"))
                .willReturn(List.of(definition("enr", TableType.ENRICHMENT, "events", TableStatus.ACTIVE)));

        assertThatThrownBy(() -> service.delete("events")).isInstanceOf(TableConflictException.class);
        verify(ddlExecutor, never()).execute(any());
    }

    @Test
    void deleteLeafDropsClickHouseTable() {
        given(definitions.findById("events"))
                .willReturn(Optional.of(definition("events", TableType.SOURCE, null, TableStatus.ACTIVE)));
        given(definitions.findBySourceTable("events")).willReturn(List.of());
        given(ddlRenderer.renderDrop("events")).willReturn("DROP TABLE IF EXISTS `events`");

        service.delete("events");

        verify(ddlExecutor).execute("DROP TABLE IF EXISTS `events`");
        verify(definitions).delete(any());
    }

    /**
     * One cascade for both kinds, where the two registries needed one each: {@code pipeline.target} is
     * UNIQUE across kinds, so the enrich declaration targeting an enrichment and the rollup targeting a
     * source are the same lookup.
     */
    @Test
    void deleteEnrichmentCascadesItsTargetingPipelineFirst() {
        given(definitions.findById("widget_labels")).willReturn(
                Optional.of(definition("widget_labels", TableType.ENRICHMENT, "widget_metrics", TableStatus.ACTIVE)));
        given(ddlRenderer.renderDrop("widget_labels")).willReturn("DROP TABLE IF EXISTS `widget_labels`");

        service.delete("widget_labels");

        final InOrder order = inOrder(pipelineTargetCascade, ddlExecutor);
        order.verify(pipelineTargetCascade).deletePipelineTargetingTable("widget_labels");
        order.verify(ddlExecutor).execute("DROP TABLE IF EXISTS `widget_labels`");
    }

    @Test
    void deleteSourceCascadesItsTargetingPipelineFirst() {
        given(definitions.findById("events"))
                .willReturn(Optional.of(definition("events", TableType.SOURCE, null, TableStatus.ACTIVE)));
        given(definitions.findBySourceTable("events")).willReturn(List.of());
        given(ddlRenderer.renderDrop("events")).willReturn("DROP TABLE IF EXISTS `events`");

        service.delete("events");

        final InOrder order = inOrder(pipelineTargetCascade, ddlExecutor);
        order.verify(pipelineTargetCascade).deletePipelineTargetingTable("events");
        order.verify(ddlExecutor).execute("DROP TABLE IF EXISTS `events`");
    }

    /** The cascade must not run when a guard has already refused the delete. */
    @Test
    void deleteRefusedByAnInputReaderNeverReachesTheCascade() {
        given(definitions.findById("events"))
                .willReturn(Optional.of(definition("events", TableType.SOURCE, null, TableStatus.ACTIVE)));
        given(definitions.findBySourceTable("events")).willReturn(List.of());
        final PipelineEntity reader = new PipelineEntity();
        reader.setName("events_hourly");
        given(pipelines.findByInputTable("events")).willReturn(List.of(reader));

        assertThatThrownBy(() -> service.delete("events")).isInstanceOf(TableConflictException.class);

        verify(pipelineTargetCascade, never()).deletePipelineTargetingTable(any());
    }

    @Test
    void deleteMissingTableIsNotFound() {
        given(definitions.findById("ghost")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("ghost")).isInstanceOf(TableNotFoundException.class);
    }

    @Test
    void getReturnsColumnsAndGrainForEnrichment() {
        final TableDefinitionEntity enrichment =
                definition("widget_labels", TableType.ENRICHMENT, "widget_metrics", TableStatus.ACTIVE);
        enrichment.setGrainKey("widget_id");
        given(definitions.findById("widget_labels")).willReturn(Optional.of(enrichment));
        final ColumnMappingEntity label = new ColumnMappingEntity();
        label.setTableName("widget_labels");
        label.setSourceName("label");
        label.setName("label");
        label.setType(QueryFieldType.STRING);
        given(columns.findByTableNameOrderByOrdinal("widget_labels")).willReturn(List.of(label));

        final TableView view = service.get("widget_labels");

        assertThat(view.columns()).extracting(ColumnMappingEntity::getName).containsExactly("label");
        assertThat(view.definition().getGrainKey()).isEqualTo("widget_id");
    }

    @Test
    void updateMetadataSetsDescriptionAndTagOrder() {
        final TableDefinitionEntity def = definition("widget_metrics", TableType.SOURCE, null, TableStatus.PENDING);
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(def));
        given(definitions.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics")).willReturn(List.of());

        service.updateMetadata("widget_metrics", "updated", Change.set(List.of("identifier", "cost")));

        assertThat(def.getDescription()).isEqualTo("updated");
        assertThat(def.getTagOrder()).containsExactly("identifier", "cost");
    }

    @Test
    void updateMetadataLeavesDescriptionWhenNull() {
        final TableDefinitionEntity def = definition("widget_metrics", TableType.SOURCE, null, TableStatus.PENDING);
        def.setDescription("original");
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(def));
        given(definitions.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics")).willReturn(List.of());

        service.updateMetadata("widget_metrics", null, Change.leave());

        assertThat(def.getDescription()).isEqualTo("original");
    }

    @Test
    void updateMissingTableIsNotFound() {
        given(definitions.findById("ghost")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateMetadata("ghost", "x", Change.leave()))
                .isInstanceOf(TableNotFoundException.class);
    }

    @Test
    void updateOfSystemTableIsRejectedAndNotPersisted() {
        given(definitions.findById("dial_usage_log")).willReturn(Optional.of(systemDefinition("dial_usage_log")));

        assertThatThrownBy(() -> service.updateMetadata("dial_usage_log", "tampered", Change.leave()))
                .isInstanceOf(SystemTableException.class);
        verify(definitions, never()).save(any());
    }

    @Test
    void deleteOfSystemTableIsRejectedAndDropsNothing() {
        given(definitions.findById("dial_usage_log")).willReturn(Optional.of(systemDefinition("dial_usage_log")));

        assertThatThrownBy(() -> service.delete("dial_usage_log")).isInstanceOf(SystemTableException.class);
        verify(ddlExecutor, never()).execute(any());
        verify(definitions, never()).delete(any());
    }

    private static TableDefinitionEntity systemDefinition(String name) {
        final TableDefinitionEntity entity = definition(name, TableType.SOURCE, null, TableStatus.ACTIVE);
        entity.setSystem(true);
        return entity;
    }
}
