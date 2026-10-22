package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.configuration.TableLimitsProperties;
import com.epam.aidial.analytics.exception.ColumnInRowPolicyException;
import com.epam.aidial.analytics.exception.TableNotFoundException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyService;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit coverage for {@link CatalogSchemaWriter}: {@code persistCreate} records identity only (no columns
 * or grain, no DDL); {@code persistSchema} validates and persists the physical schema of a
 * not-yet-materialized table (a source's columns + ordering key, an enrichment's user columns + grain);
 * and the live-schema helpers (append/remove/rename/update) plus {@code setTagOrder}/{@code markStatus}.
 */
@ExtendWith(MockitoExtension.class)
class CatalogSchemaWriterTest {

    @Mock
    private TableDefinitionRepository definitions;
    @Mock
    private ColumnMappingRepository columns;
    @Mock
    private PipelineGenerationBump pipelineGenerations;
    @Mock
    private RowPolicyService rowPolicies;

    private CatalogSchemaWriter writer;

    @BeforeEach
    void setUp() {
        writer = new CatalogSchemaWriter(definitions, columns, pipelineGenerations,
                new TableLimitsProperties(256, 1000, 10000), rowPolicies);
    }

    private static TableDefinitionEntity definition(String name, TableType type, String sourceTable) {
        final TableDefinitionEntity entity = new TableDefinitionEntity();
        entity.setName(name);
        entity.setType(type);
        entity.setSourceTable(sourceTable);
        entity.setStatus(TableStatus.PENDING);
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

    /** Same as {@link #col}, but declaring {@code nullable: true} explicitly. */
    private static ColumnInput nullableCol(String name, QueryFieldType type) {
        return new ColumnInput(name, name, type, null, null, true, null, null, null, false, false);
    }

    private static ColumnMappingEntity nullableColumn(String table, String sourceName, QueryFieldType type) {
        final ColumnMappingEntity col = column(table, sourceName, type);
        col.setNullable(true);
        return col;
    }

    // --- persistCreate: identity only -------------------------------------------------------------

    @Test
    void persistCreateStoresIdentityOnlyAsPending() {
        final ArgumentCaptor<TableDefinitionEntity> saved = ArgumentCaptor.forClass(TableDefinitionEntity.class);
        given(definitions.save(saved.capture())).willAnswer(inv -> inv.getArgument(0));

        writer.persistCreate(new CreateTableInput("widget_metrics", "desc", TableType.SOURCE, null,
                null, List.of(" identifier ", "cost")));

        assertThat(saved.getValue().getStatus()).isEqualTo(TableStatus.PENDING);
        assertThat(saved.getValue().getSourceTable()).isNull();
        // An unspecified write discipline stores as APPEND, so a definition written before the facet
        // existed reads back with the behaviour it already had.
        assertThat(saved.getValue().getWriteMode()).isEqualTo(TableWriteMode.APPEND);
        // Entries are trimmed on the way in; the stored ordering is the normalized list.
        assertThat(saved.getValue().getTagOrder()).containsExactly("identifier", "cost");
        // No columns or grain are written at create.
        verify(columns, never()).saveAll(any());
        assertThat(saved.getValue().getGrainKey()).isNull();
    }

    @Test
    void persistCreateStoresEnrichmentSourceTable() {
        final ArgumentCaptor<TableDefinitionEntity> saved = ArgumentCaptor.forClass(TableDefinitionEntity.class);
        given(definitions.save(saved.capture())).willAnswer(inv -> inv.getArgument(0));

        writer.persistCreate(new CreateTableInput("widget_labels", null, TableType.ENRICHMENT, "widget_metrics"));

        assertThat(saved.getValue().getSourceTable()).isEqualTo("widget_metrics");
        assertThat(saved.getValue().getStatus()).isEqualTo(TableStatus.PENDING);
    }

    @Test
    void persistCreateStoresDeclaredWriteDiscipline() {
        final ArgumentCaptor<TableDefinitionEntity> saved = ArgumentCaptor.forClass(TableDefinitionEntity.class);
        given(definitions.save(saved.capture())).willAnswer(inv -> inv.getArgument(0));

        writer.persistCreate(new CreateTableInput("usage_daily", null, TableType.SOURCE, null,
                TableWriteMode.UPSERT_BY_KEY, null));

        assertThat(saved.getValue().getWriteMode()).isEqualTo(TableWriteMode.UPSERT_BY_KEY);
    }

    // --- persistSchema: source -------------------------------------------------------------

    @Test
    void replaceSourceDraftStoresColumnsAndOrderingKey() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(definition("widget_metrics", TableType.SOURCE, null)));
        final TableDefinitionEntity def = definition("widget_metrics", TableType.SOURCE, null);
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(def));

        writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(col("widget_id", QueryFieldType.STRING), col("score", QueryFieldType.LONG)),
                List.of("widget_id"), null, null, null, null, null));

        verify(columns).deleteByTableName("widget_metrics");
        verify(columns).saveAll(any());
        assertThat(def.getOrderingKey()).containsExactly("widget_id");
    }

    @Test
    void persistSchemaRefusesDroppingRowPolicyColumn() {
        final TableDefinitionEntity def = definition("widget_metrics", TableType.SOURCE, null);
        def.setStatus(TableStatus.FAILED);
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(def));
        given(rowPolicies.referencedColumns(def)).willReturn(Map.of("project_id", Set.of("team-x")));

        assertThatThrownBy(() -> writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(col("widget_id", QueryFieldType.STRING)), List.of("widget_id"), null, null, null, null, null)))
                .isInstanceOf(ColumnInRowPolicyException.class)
                .hasMessageContaining("project_id")
                .hasMessageContaining("team-x");
        verify(columns, never()).deleteByTableName(any());
    }

    @Test
    void persistSchemaAcceptsKeptRowPolicyColumn() {
        final TableDefinitionEntity def = definition("widget_metrics", TableType.SOURCE, null);
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(def));
        given(rowPolicies.referencedColumns(def)).willReturn(Map.of("project_id", Set.of("team-x")));

        writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(col("widget_id", QueryFieldType.STRING), col("project_id", QueryFieldType.STRING)),
                List.of("widget_id"), null, null, null, null, null));

        verify(columns).saveAll(any());
    }

    @Test
    void replaceSourceDraftAllowsMissingOrderingKey() {
        final TableDefinitionEntity def = definition("widget_metrics", TableType.SOURCE, null);
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(def));

        writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(col("widget_id", QueryFieldType.STRING)), null, null, null, null, null, null));

        // A draft may be incomplete — no ordering_key yet; completeness is enforced at materialize.
        assertThat(def.getOrderingKey()).isEmpty();
    }

    @Test
    void replaceSourceDraftRejectsOrderingKeyNotAmongColumns() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(definition("widget_metrics", TableType.SOURCE, null)));

        assertThatThrownBy(() -> writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(col("widget_id", QueryFieldType.STRING)), List.of("missing"), null, null, null, null, null)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("ordering_key");
    }

    @Test
    @SuppressWarnings("unchecked")
    void replaceSourceDraftAcceptsArrayColumn() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(definition("widget_metrics", TableType.SOURCE, null)));

        writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(new ColumnInput("tags", "tags", QueryFieldType.ARRAY, QueryFieldType.STRING,
                        null, null, null, null, null, false, false)),
                List.of("tags"), null, null, null, null, null));

        final ArgumentCaptor<List<ColumnMappingEntity>> saved = ArgumentCaptor.forClass(List.class);
        verify(columns).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(c -> {
            assertThat(c.getType()).isEqualTo(QueryFieldType.ARRAY);
            assertThat(c.getElementType()).isEqualTo(QueryFieldType.STRING);
        });
    }

    @Test
    void replaceSourceDraftRejectsMapOrderingKey() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(definition("widget_metrics", TableType.SOURCE, null)));

        assertThatThrownBy(() -> writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(col("attrs", QueryFieldType.MAP)), List.of("attrs"), null, null, null, null, null)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("ordering_key column 'attrs'")
                .hasMessageContaining("a map cannot key a table");
        verify(columns, never()).saveAll(any());
    }

    @Test
    void replaceSourceDraftRejectsMapPartitionColumn() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(definition("widget_metrics", TableType.SOURCE, null)));

        assertThatThrownBy(() -> writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(col("widget_id", QueryFieldType.STRING), col("attrs", QueryFieldType.MAP)),
                List.of("widget_id"), "attrs", PartitionGranularity.DAY, null, null, null)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("partition_by column 'attrs'")
                .hasMessageContaining("a map cannot key a table");
        verify(columns, never()).saveAll(any());
    }

    @Test
    void replaceEnrichmentDraftRejectsMapGrainKey() {
        given(definitions.findById("widget_labels"))
                .willReturn(Optional.of(definition("widget_labels", TableType.ENRICHMENT, "widget_metrics")));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics"))
                .willReturn(List.of(column("widget_metrics", "attrs", QueryFieldType.MAP)));

        assertThatThrownBy(() -> writer.persistSchema("widget_labels", new DefineSchemaInput(
                List.of(col("label", QueryFieldType.STRING)), null, null, null, "attrs", null, null)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("grain_key column 'attrs'")
                .hasMessageContaining("a map cannot key a table");
        verify(columns, never()).saveAll(any());
    }

    /**
     * An {@code object} column cannot key a table either: ClickHouse refuses a {@code JSON} column in a
     * sorting key, so without this the draft would pass validation, the catalog rows would be committed,
     * and the {@code CREATE TABLE … ORDER BY (doc)} would fail raw and leave a {@code FAILED} definition.
     */
    @Test
    void replaceEnrichmentDraftRejectsObjectGrainKey() {
        given(definitions.findById("widget_labels"))
                .willReturn(Optional.of(definition("widget_labels", TableType.ENRICHMENT, "widget_metrics")));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics"))
                .willReturn(List.of(column("widget_metrics", "doc", QueryFieldType.OBJECT)));

        assertThatThrownBy(() -> writer.persistSchema("widget_labels", new DefineSchemaInput(
                List.of(col("label", QueryFieldType.STRING)), null, null, null, "doc", null, null)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("grain_key column 'doc'")
                .hasMessageContaining("an object cannot key a table");
        verify(columns, never()).saveAll(any());
    }

    /**
     * A nullable column cannot key a table either: ClickHouse refuses a nullable sorting key while
     * {@code allow_nullable_key} is disabled, so the {@code CREATE} would fail after the catalog rows had
     * already been written. Same reason the {@code map} refusal above lives here rather than in ClickHouse.
     */
    @Test
    void replaceSourceDraftRejectsNullableOrderingKey() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(definition("widget_metrics", TableType.SOURCE, null)));

        assertThatThrownBy(() -> writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(nullableCol("widget_id", QueryFieldType.STRING)), List.of("widget_id"),
                null, null, null, null, null)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("ordering_key column 'widget_id'")
                .hasMessageContaining("must be non-nullable")
                .hasMessageContaining("nullable sorting key");
        verify(columns, never()).saveAll(any());
    }

    @Test
    void replaceSourceDraftRejectsNullablePartitionColumn() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(definition("widget_metrics", TableType.SOURCE, null)));

        assertThatThrownBy(() -> writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(col("widget_id", QueryFieldType.STRING),
                        nullableCol("event_time", QueryFieldType.TIMESTAMP)),
                List.of("widget_id"), "event_time", PartitionGranularity.DAY, null, null, null)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("partition_by column 'event_time'")
                .hasMessageContaining("must be non-nullable")
                .hasMessageContaining("nullable partition key");
        verify(columns, never()).saveAll(any());
    }

    /**
     * The deliberate exception to the rule above: an enrichment's grain column is materialized
     * non-nullable whatever the source column declares, so its {@code ORDER BY (grain_key)} is never over a
     * {@code Nullable(...)} column and ClickHouse has nothing to refuse. Rejecting this would break
     * enrichments that materialize fine today.
     */
    @Test
    void replaceEnrichmentDraftAcceptsNullableSourceGrainKey() {
        given(definitions.findById("widget_labels"))
                .willReturn(Optional.of(definition("widget_labels", TableType.ENRICHMENT, "widget_metrics")));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics"))
                .willReturn(List.of(nullableColumn("widget_metrics", "widget_id", QueryFieldType.STRING)));

        writer.persistSchema("widget_labels", new DefineSchemaInput(
                List.of(col("label", QueryFieldType.STRING)), null, null, null, "widget_id", null, null));

        final ArgumentCaptor<TableDefinitionEntity> saved = ArgumentCaptor.forClass(TableDefinitionEntity.class);
        verify(definitions).save(saved.capture());
        assertThat(saved.getValue().getGrainKey()).isEqualTo("widget_id");
        assertThat(saved.getValue().getOrderingKey()).containsExactly("widget_id");
    }

    @Test
    void persistSchemaRejectsUnknownTable() {
        given(definitions.findById("ghost")).willReturn(Optional.empty());
        assertThatThrownBy(() -> writer.persistSchema("ghost", new DefineSchemaInput(
                List.of(col("c", QueryFieldType.STRING)), List.of("c"), null, null, null, null, null)))
                .isInstanceOf(TableNotFoundException.class);
    }

    @Test
    void replaceSourceDraftStoresValidTtl() {
        final TableDefinitionEntity def = definition("widget_metrics", TableType.SOURCE, null);
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(def));

        writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(col("widget_id", QueryFieldType.STRING), col("created_at", QueryFieldType.TIMESTAMP)),
                List.of("widget_id"), null, null, null, null,
                new TtlInput("created_at", 90, TtlUnit.DAY)));

        assertThat(def.getTtlColumn()).isEqualTo("created_at");
        assertThat(def.getTtlInterval()).isEqualTo(90);
        assertThat(def.getTtlUnit()).isEqualTo(TtlUnit.DAY);
    }

    @Test
    void replaceSourceDraftRejectsTtlOnUnknownColumn() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(definition("widget_metrics", TableType.SOURCE, null)));

        assertThatThrownBy(() -> writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(col("widget_id", QueryFieldType.STRING)),
                List.of("widget_id"), null, null, null, null,
                new TtlInput("missing", 1, TtlUnit.DAY))))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("ttl column");
    }

    @Test
    void replaceSourceDraftRejectsTtlOnNonTimeColumn() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(definition("widget_metrics", TableType.SOURCE, null)));

        assertThatThrownBy(() -> writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(col("widget_id", QueryFieldType.STRING)),
                List.of("widget_id"), null, null, null, null,
                new TtlInput("widget_id", 1, TtlUnit.DAY))))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("date or timestamp");
    }

    @Test
    void replaceSourceDraftRejectsTtlOnNullableColumn() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(definition("widget_metrics", TableType.SOURCE, null)));

        assertThatThrownBy(() -> writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(col("widget_id", QueryFieldType.STRING),
                        new ColumnInput("seen_at", "seen_at", QueryFieldType.TIMESTAMP,
                                null, null, true, null, null, null, false, false)),
                List.of("widget_id"), null, null, null, null,
                new TtlInput("seen_at", 1, TtlUnit.DAY))))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("non-nullable");
    }

    @Test
    void replaceSourceDraftRejectsNonPositiveTtlInterval() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(definition("widget_metrics", TableType.SOURCE, null)));

        assertThatThrownBy(() -> writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(col("widget_id", QueryFieldType.STRING), col("event_date", QueryFieldType.DATE)),
                List.of("widget_id"), null, null, null, null,
                new TtlInput("event_date", 0, TtlUnit.DAY))))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("positive");
    }

    // --- persistSchema: scan metadata ---------------------------------------------------------

    @Test
    void replaceSourceDraftStoresDeclaredScanMetadata() {
        final TableDefinitionEntity def = definition("widget_metrics", TableType.SOURCE, null);
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(def));

        writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(col("widget_id", QueryFieldType.STRING), col("seen_at", QueryFieldType.TIMESTAMP)),
                List.of("widget_id"), null, null, null, null, null, "widget_id", "seen_at"));

        assertThat(def.getIdentityColumn()).isEqualTo("widget_id");
        assertThat(def.getVersionColumn()).isEqualTo("seen_at");
    }

    /**
     * An omission means "leave it": re-posting a FAILED table's schema without the pair must not silently
     * drop a value the caller already declared. (The completeness gate above this layer requires a source
     * to supply both, so this reaches the writer only through a direct call.)
     */
    @Test
    void replaceSourceDraftLeavesStoredScanMetadataWhenOmitted() {
        final TableDefinitionEntity def = definition("widget_metrics", TableType.SOURCE, null);
        def.setIdentityColumn("widget_id");
        def.setVersionColumn("seen_at");
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(def));

        writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(col("widget_id", QueryFieldType.STRING)), List.of("widget_id"),
                null, null, null, null, null));

        assertThat(def.getIdentityColumn()).isEqualTo("widget_id");
        assertThat(def.getVersionColumn()).isEqualTo("seen_at");
    }

    @Test
    void replaceSourceDraftRejectsIdentityColumnNotAmongColumns() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(definition("widget_metrics", TableType.SOURCE, null)));

        assertThatThrownBy(() -> writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(col("widget_id", QueryFieldType.STRING)), List.of("widget_id"),
                null, null, null, null, null, "missing", null)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("identity_column 'missing' is not a declared column");
    }

    @Test
    void replaceSourceDraftRejectsVersionColumnNotAmongColumns() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(definition("widget_metrics", TableType.SOURCE, null)));

        assertThatThrownBy(() -> writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(col("widget_id", QueryFieldType.STRING)), List.of("widget_id"),
                null, null, null, null, null, null, "missing")))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("version_column 'missing' is not a declared column");
    }

    /**
     * The scan renders the cursor's version half as {@code toUnixTimestamp64Milli(version)}, which only a
     * {@code DateTime64} column answers — so a non-temporal version is a 422 here rather than a ClickHouse
     * error on the first scan.
     */
    @Test
    void replaceSourceDraftRejectsNonTimestampVersionColumn() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(definition("widget_metrics", TableType.SOURCE, null)));

        assertThatThrownBy(() -> writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(col("widget_id", QueryFieldType.STRING), col("day", QueryFieldType.DATE)),
                List.of("widget_id"), null, null, null, null, null, "widget_id", "day")))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("version_column 'day' is declared 'date'")
                .hasMessageContaining("must be declared 'timestamp'");
    }

    /** A NULL fails every cursor comparison, so such a row would never appear on any page. */
    @Test
    void replaceSourceDraftRejectsNullableScanColumn() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(definition("widget_metrics", TableType.SOURCE, null)));

        assertThatThrownBy(() -> writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(col("widget_id", QueryFieldType.STRING),
                        new ColumnInput("seen_at", "seen_at", QueryFieldType.TIMESTAMP, null, null, true,
                                null, null, null, false, false)),
                List.of("widget_id"), null, null, null, null, null, "widget_id", "seen_at")))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("version_column 'seen_at' is nullable");
    }

    /** Both cursor columns are projected, and the identity is echoed in the page cursor. */
    @Test
    void replaceSourceDraftRejectsSensitiveScanColumn() {
        given(definitions.findById("widget_metrics"))
                .willReturn(Optional.of(definition("widget_metrics", TableType.SOURCE, null)));

        assertThatThrownBy(() -> writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(new ColumnInput("widget_id", "widget_id", QueryFieldType.STRING, null, null, null,
                                null, null, null, true, false),
                        col("seen_at", QueryFieldType.TIMESTAMP)),
                List.of("widget_id"), null, null, null, null, null, "widget_id", "seen_at")))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("identity_column 'widget_id' is sensitive");
    }

    /** An upsert_by_key source physically carries the engine's version column, absent from column_mapping. */
    @Test
    void replaceUpsertSourceDraftStoresEngineVersionColumn() {
        final TableDefinitionEntity def = definition("conv_rollup", TableType.SOURCE, null);
        def.setWriteMode(TableWriteMode.UPSERT_BY_KEY);
        given(definitions.findById("conv_rollup")).willReturn(Optional.of(def));

        writer.persistSchema("conv_rollup", new DefineSchemaInput(
                List.of(col("chat_id", QueryFieldType.STRING), col("turn_count", QueryFieldType.LONG)),
                List.of("chat_id"), null, null, null, null, null, "chat_id", "_updated_at"));

        assertThat(def.getIdentityColumn()).isEqualTo("chat_id");
        assertThat(def.getVersionColumn()).isEqualTo("_updated_at");
    }

    /** An append source is a plain MergeTree, so the engine version it would name does not exist. */
    @Test
    void replaceAppendSourceDraftRejectsEngineVersionColumn() {
        final TableDefinitionEntity def = definition("widget_metrics", TableType.SOURCE, null);
        def.setWriteMode(TableWriteMode.APPEND);
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(def));

        assertThatThrownBy(() -> writer.persistSchema("widget_metrics", new DefineSchemaInput(
                List.of(col("widget_id", QueryFieldType.STRING)), List.of("widget_id"),
                null, null, null, null, null, "widget_id", "_updated_at")))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("only an 'upsert_by_key' table carries");
    }

    // --- persistSchema: enrichment ---------------------------------------------------------

    @Test
    void replaceEnrichmentDraftStoresGrainAndUserColumns() {
        final TableDefinitionEntity def = definition("widget_labels", TableType.ENRICHMENT, "widget_metrics");
        given(definitions.findById("widget_labels")).willReturn(Optional.of(def));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics"))
                .willReturn(List.of(column("widget_metrics", "widget_id", QueryFieldType.STRING)));

        writer.persistSchema("widget_labels", new DefineSchemaInput(
                List.of(col("label", QueryFieldType.STRING)), null, null, null, "widget_id", null, null));

        assertThat(def.getGrainKey()).isEqualTo("widget_id");
        assertThat(def.getOrderingKey()).containsExactly("widget_id");
        verify(columns).saveAll(any());
    }

    @Test
    void replaceEnrichmentDraftRejectsGrainKeyNotAmongSourceColumns() {
        given(definitions.findById("widget_labels"))
                .willReturn(Optional.of(definition("widget_labels", TableType.ENRICHMENT, "widget_metrics")));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics"))
                .willReturn(List.of(column("widget_metrics", "widget_id", QueryFieldType.STRING)));

        assertThatThrownBy(() -> writer.persistSchema("widget_labels", new DefineSchemaInput(
                List.of(), null, null, null, "user_hash", null, null)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("not a column of source");
    }

    @Test
    void replaceEnrichmentDraftWithoutGrainKeyClearsGrain() {
        final TableDefinitionEntity def = definition("widget_labels", TableType.ENRICHMENT, "widget_metrics");
        def.setGrainKey("widget_id");
        given(definitions.findById("widget_labels")).willReturn(Optional.of(def));

        writer.persistSchema("widget_labels", new DefineSchemaInput(
                List.of(col("label", QueryFieldType.STRING)), null, null, null, null, null, null));

        assertThat(def.getGrainKey()).isNull();
        assertThat(def.getOrderingKey()).isEmpty();
    }

    // --- applyPatch: one-transaction catalog write of a live PATCH -----------------------------

    @Test
    void applyPatchRenameOfTtlColumnRenamesStoredTtl() {
        final TableDefinitionEntity def = definition("widget_metrics", TableType.SOURCE, null);
        def.setStatus(TableStatus.ACTIVE);
        def.setTtl(new TtlInput("created_at", 90, TtlUnit.DAY));
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(def));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics"))
                .willReturn(List.of(column("widget_metrics", "created_at", QueryFieldType.TIMESTAMP)));

        writer.applyPatch("widget_metrics", List.of(),
                List.of(new CatalogSchemaWriter.RenameWrite("created_at", "created_ts")),
                List.of(), List.of(), Change.leave());

        // Mirrors ClickHouse rewriting the physical TTL expression on RENAME COLUMN.
        assertThat(def.getTtlColumn()).isEqualTo("created_ts");
        assertThat(def.getTtlInterval()).isEqualTo(90);
        assertThat(def.getTtlUnit()).isEqualTo(TtlUnit.DAY);
    }

    /**
     * The pair follows a rename for the same reason the TTL column does: the physical column survives with
     * its data, so the catalog keeps pointing at it. Nothing else about the definition moves — and only a
     * repointed <em>version</em> column notifies the rule registry, since only that name is on the rule wire.
     */
    @Test
    void applyPatchRenameOfIdentityColumnRepointsStoredPair() {
        final TableDefinitionEntity def = definition("widget_metrics", TableType.SOURCE, null);
        def.setStatus(TableStatus.ACTIVE);
        def.setIdentityColumn("widget_id");
        def.setVersionColumn("seen_at");
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(def));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics"))
                .willReturn(List.of(column("widget_metrics", "widget_id", QueryFieldType.STRING)));

        writer.applyPatch("widget_metrics", List.of(),
                List.of(new CatalogSchemaWriter.RenameWrite("widget_id", "widget_key")),
                List.of(), List.of(), Change.leave());

        assertThat(def.getIdentityColumn()).isEqualTo("widget_key");
        assertThat(def.getVersionColumn()).isEqualTo("seen_at");
        verifyNoInteractions(pipelineGenerations);
    }

    @Test
    void applyPatchRenameOfVersionColumnRepointsAndBumpsPipelineGenerations() {
        final TableDefinitionEntity def = definition("widget_metrics", TableType.SOURCE, null);
        def.setStatus(TableStatus.ACTIVE);
        def.setIdentityColumn("widget_id");
        def.setVersionColumn("seen_at");
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(def));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics"))
                .willReturn(List.of(column("widget_metrics", "seen_at", QueryFieldType.TIMESTAMP)));

        writer.applyPatch("widget_metrics", List.of(),
                List.of(new CatalogSchemaWriter.RenameWrite("seen_at", "ingested_at")),
                List.of(), List.of(), Change.leave());

        assertThat(def.getVersionColumn()).isEqualTo("ingested_at");
        assertThat(def.getIdentityColumn()).isEqualTo("widget_id");
        // In this transaction: the new name and the signal to go and read it cannot be committed apart.
        verify(pipelineGenerations).onVersionColumnRepointed("widget_metrics");
    }

    @Test
    void applyPatchRenameOfAnUnrelatedColumnLeavesThePairAndPipelinesAlone() {
        final TableDefinitionEntity def = definition("widget_metrics", TableType.SOURCE, null);
        def.setStatus(TableStatus.ACTIVE);
        def.setIdentityColumn("widget_id");
        def.setVersionColumn("seen_at");
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(def));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics"))
                .willReturn(List.of(column("widget_metrics", "score", QueryFieldType.LONG)));

        writer.applyPatch("widget_metrics", List.of(),
                List.of(new CatalogSchemaWriter.RenameWrite("score", "rating")),
                List.of(), List.of(), Change.leave());

        assertThat(def.getIdentityColumn()).isEqualTo("widget_id");
        assertThat(def.getVersionColumn()).isEqualTo("seen_at");
        verifyNoInteractions(pipelineGenerations);
    }

    /** One request may move the version column and the TTL column at once; both stored names follow. */
    @Test
    void applyPatchRenamesVersionAndTtlColumnsInOneRequest() {
        final TableDefinitionEntity def = definition("widget_metrics", TableType.SOURCE, null);
        def.setStatus(TableStatus.ACTIVE);
        def.setVersionColumn("seen_at");
        def.setTtl(new TtlInput("created_at", 90, TtlUnit.DAY));
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(def));
        given(columns.findByTableNameOrderByOrdinal("widget_metrics")).willReturn(List.of(
                column("widget_metrics", "seen_at", QueryFieldType.TIMESTAMP),
                column("widget_metrics", "created_at", QueryFieldType.TIMESTAMP)));

        writer.applyPatch("widget_metrics", List.of(),
                List.of(new CatalogSchemaWriter.RenameWrite("seen_at", "ingested_at"),
                        new CatalogSchemaWriter.RenameWrite("created_at", "created_ts")),
                List.of(), List.of(), Change.leave());

        assertThat(def.getVersionColumn()).isEqualTo("ingested_at");
        assertThat(def.getTtlColumn()).isEqualTo("created_ts");
        verify(pipelineGenerations).onVersionColumnRepointed("widget_metrics");
    }

    @Test
    void applyPatchSetsAndClearsTtl() {
        final TableDefinitionEntity def = definition("widget_metrics", TableType.SOURCE, null);
        def.setStatus(TableStatus.ACTIVE);
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(def));

        writer.applyPatch("widget_metrics", List.of(), List.of(), List.of(), List.of(),
                Change.set(new TtlInput("created_at", 30, TtlUnit.MONTH)));
        assertThat(def.getTtlColumn()).isEqualTo("created_at");
        assertThat(def.getTtlInterval()).isEqualTo(30);
        assertThat(def.getTtlUnit()).isEqualTo(TtlUnit.MONTH);

        writer.applyPatch("widget_metrics", List.of(), List.of(), List.of(), List.of(), Change.set(null));
        assertThat(def.getTtlColumn()).isNull();
        assertThat(def.getTtlInterval()).isNull();
        assertThat(def.getTtlUnit()).isNull();
    }

    // --- live-schema helpers (unchanged behavior) ----------------------------------------------

    @Test
    void appendColumnPersistsSensitiveFlag() {
        given(columns.save(any())).willAnswer(inv -> inv.getArgument(0));
        final ColumnInput input =
                new ColumnInput("secret", "secret", QueryFieldType.STRING, null, null, null, null, null, null, true, false);

        writer.appendColumn("t", input, false, 5);

        final ArgumentCaptor<ColumnMappingEntity> saved = ArgumentCaptor.forClass(ColumnMappingEntity.class);
        verify(columns).save(saved.capture());
        assertThat(saved.getValue().isSensitive()).isTrue();
    }

    @Test
    void updateColumnMetadataSetsSeveralFieldsAtOnce() {
        final ColumnMappingEntity col = column("dial_usage_log", "jwt_claims", QueryFieldType.OBJECT);
        given(columns.findByTableNameOrderByOrdinal("dial_usage_log")).willReturn(List.of(col));
        given(columns.save(any())).willAnswer(inv -> inv.getArgument(0));

        writer.updateColumnMetadata("dial_usage_log", "jwt_claims",
                Change.set("user_email"), Change.set("Claims"), Change.set("JWT claims blob"), Change.set(true),
                Change.set(true));

        assertThat(col.getTag()).isEqualTo("user_email");
        assertThat(col.getDisplayName()).isEqualTo("Claims");
        assertThat(col.getDescription()).isEqualTo("JWT claims blob");
        assertThat(col.isSensitive()).isTrue();
        verify(columns).save(col);
    }

    @Test
    void updateColumnMetadataLeavesAbsentFieldsUntouched() {
        final ColumnMappingEntity col = column("dial_usage_log", "total_money", QueryFieldType.LONG);
        col.setTag("cost");
        col.setDisplayName("Total money spend");
        col.setSensitive(true);
        given(columns.findByTableNameOrderByOrdinal("dial_usage_log")).willReturn(List.of(col));
        given(columns.save(any())).willAnswer(inv -> inv.getArgument(0));

        writer.updateColumnMetadata("dial_usage_log", "total_money",
                Change.set("spend"), Change.leave(), Change.leave(), Change.leave(), Change.leave());

        assertThat(col.getTag()).isEqualTo("spend");
        assertThat(col.getDisplayName()).isEqualTo("Total money spend");
        assertThat(col.isSensitive()).isTrue();
        verify(columns).save(col);
    }

    @Test
    void renamePreservesSensitiveHeavyAndDisplayName() {
        final ColumnMappingEntity col = column("t", "secret", QueryFieldType.STRING);
        col.setSensitive(true);
        col.setHeavy(true);
        col.setDisplayName("Secret value");
        col.setOrdinal(3);
        given(columns.findByTableNameOrderByOrdinal("t")).willReturn(List.of(col));
        given(columns.save(any())).willAnswer(inv -> inv.getArgument(0));

        writer.renameColumn("t", "secret", "hidden");

        final ArgumentCaptor<ColumnMappingEntity> saved = ArgumentCaptor.forClass(ColumnMappingEntity.class);
        verify(columns).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("hidden");
        assertThat(saved.getValue().isSensitive()).isTrue();
        assertThat(saved.getValue().isHeavy()).isTrue();
        assertThat(saved.getValue().getDisplayName()).isEqualTo("Secret value");
    }

    @Test
    void appendColumnPersistsHeavyFlag() {
        given(columns.save(any())).willAnswer(inv -> inv.getArgument(0));
        final ColumnInput input = new ColumnInput(
                "payload", "payload", QueryFieldType.STRING, null, null, null, null, null, null, false, true);

        writer.appendColumn("t", input, false, 5);

        final ArgumentCaptor<ColumnMappingEntity> saved = ArgumentCaptor.forClass(ColumnMappingEntity.class);
        verify(columns).save(saved.capture());
        assertThat(saved.getValue().isHeavy()).isTrue();
        assertThat(saved.getValue().isSensitive()).isFalse();
    }

    @Test
    void updateColumnMetadataTogglesHeavyBothWays() {
        final ColumnMappingEntity col = column("dial_usage_log", "dial_usage_log_payload.response_body", QueryFieldType.STRING);
        given(columns.findByTableNameOrderByOrdinal("dial_usage_log")).willReturn(List.of(col));
        given(columns.save(any())).willAnswer(inv -> inv.getArgument(0));

        writer.updateColumnMetadata("dial_usage_log", "dial_usage_log_payload.response_body",
                Change.leave(), Change.leave(), Change.leave(), Change.leave(), Change.set(true));
        assertThat(col.isHeavy()).isTrue();

        writer.updateColumnMetadata("dial_usage_log", "dial_usage_log_payload.response_body",
                Change.leave(), Change.leave(), Change.leave(), Change.leave(), Change.set(false));
        assertThat(col.isHeavy()).isFalse();
    }

    @Test
    void setTagOrderReplacesThenClears() {
        final TableDefinitionEntity def = definition("widget_metrics", TableType.SOURCE, null);
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(def));
        given(definitions.save(any())).willAnswer(inv -> inv.getArgument(0));

        writer.setTagOrder("widget_metrics", List.of("identifier", "cost"));
        assertThat(def.getTagOrder()).containsExactly("identifier", "cost");

        writer.setTagOrder("widget_metrics", List.of());
        assertThat(def.getTagOrder()).isNull();
    }

    @Test
    void markStatusUpdatesStatus() {
        final TableDefinitionEntity def = definition("widget_metrics", TableType.SOURCE, null);
        given(definitions.findById("widget_metrics")).willReturn(Optional.of(def));
        given(definitions.save(any())).willAnswer(inv -> inv.getArgument(0));

        writer.markStatus("widget_metrics", TableStatus.ACTIVE);

        assertThat(def.getStatus()).isEqualTo(TableStatus.ACTIVE);
    }
}
