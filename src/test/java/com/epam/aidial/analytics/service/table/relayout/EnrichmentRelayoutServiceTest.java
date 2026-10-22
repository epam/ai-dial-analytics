package com.epam.aidial.analytics.service.table.relayout;

import com.epam.aidial.analytics.clickhouse.ClickHouseDdlExecutor;
import com.epam.aidial.analytics.clickhouse.ClickHouseTableInspector;
import com.epam.aidial.analytics.configuration.PipelineBudgetProperties;
import com.epam.aidial.analytics.service.pipeline.LookupIndexService;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.pipeline.PipelineSpecCodec;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.PartitionGranularity;
import com.epam.aidial.analytics.service.table.TableDdlRenderer;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableSchemaCreationService;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.TableWriteMode;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The refusals of {@link EnrichmentRelayoutService} — a database that cannot exchange tables, a writer that cannot be
 * judged — the copy table's name, and how a slice's bounds are rendered.
 */
class EnrichmentRelayoutServiceTest {

    private final PipelineSpecCodec specCodec = mock(PipelineSpecCodec.class);

    private final TableDefinitionRepository definitions = mock(TableDefinitionRepository.class);
    private final ColumnMappingRepository columns = mock(ColumnMappingRepository.class);
    private final PipelineRepository pipelines = mock(PipelineRepository.class);
    private final ClickHouseDdlExecutor ddl = mock(ClickHouseDdlExecutor.class);
    private final ClickHouseTableInspector inspector = mock(ClickHouseTableInspector.class);
    private final RelayoutStateStore states = mock(RelayoutStateStore.class);

    private final EnrichmentRelayoutService service = new EnrichmentRelayoutService(definitions, columns, pipelines,
            specCodec, mock(TableSchemaCreationService.class), new TableDdlRenderer(), ddl, inspector,
            mock(LookupIndexService.class), states, mock(DataSource.class), mock(TransactionTemplate.class),
            new PipelineBudgetProperties(Duration.ofSeconds(120), DataSize.ofGigabytes(2)));

    @Test
    void databaseEngineThatCannotExchangeTablesIsSkippedAndReportedUnsupported() {
        givenTable();
        given(pipelines.findByTarget("uci")).willReturn(Optional.empty());
        given(inspector.databaseEngine()).willReturn("Ordinary");

        service.relayout("uci");

        verify(states).start(eq("uci"), eq(RelayoutStep.UNSUPPORTED), anyString(), contains("Ordinary"));
        verify(ddl, never()).execute(any());
    }

    @Test
    void writerWhoseSpecCannotBeReadRefusesTheRelayout() {
        givenTable();
        final PipelineEntity writer = new PipelineEntity();
        writer.setName("uci-writer");
        writer.setKind(PipelineKind.ENRICH);
        given(pipelines.findByTarget("uci")).willReturn(Optional.of(writer));
        given(specCodec.readEnrich(writer)).willThrow(new IllegalStateException("unreadable"));
        given(inspector.databaseEngine()).willReturn("Atomic");

        service.relayout("uci");

        verify(states).start(eq("uci"), eq(RelayoutStep.UNSUPPORTED), anyString(), contains("cannot be read"));
        verify(ddl, never()).execute(any());
    }

    @Test
    void copyTableNameIsReservedAndFitsAnIdentifier() {
        assertThat(EnrichmentRelayoutService.copyName("uci")).isEqualTo("_relayout_uci");
        final String longName = "a".repeat(64);
        final String copy = EnrichmentRelayoutService.copyName(longName);
        assertThat(copy).hasSize(64).startsWith("_relayout_aaa").isNotEqualTo(EnrichmentRelayoutService.copyName("a".repeat(63) + "b"));
    }

    @Test
    void sliceBoundsAreCastToTheKeyTypeAndEscaped() {
        assertThat(new EnrichmentRelayoutService.Slice(null, null).where("`k`", "UUID")).isEqualTo("1");
        assertThat(new EnrichmentRelayoutService.Slice("a", null).where("`k`", "String")).isEqualTo("`k` > CAST('a' AS String)");
        assertThat(new EnrichmentRelayoutService.Slice(null, "5").where("`k`", "Int64")).isEqualTo("`k` <= CAST('5' AS Int64)");
        assertThat(new EnrichmentRelayoutService.Slice("2026-10-01 00:00:00.000", "o'k\\").where("`k`", "String"))
                .isEqualTo("`k` > CAST('2026-10-01 00:00:00.000' AS String) AND `k` <= CAST('o\\'k\\\\' AS String)");
    }

    private void givenTable() {
        final TableDefinitionEntity source = new TableDefinitionEntity();
        source.setName("dial_usage_log");
        source.setType(TableType.SOURCE);
        source.setWriteMode(TableWriteMode.APPEND);
        source.setIdentityColumn("event_id");
        source.setPartitionColumn("request_time");
        source.setPartitionGranularity(PartitionGranularity.DAY);
        final TableDefinitionEntity table = new TableDefinitionEntity();
        table.setName("uci");
        table.setType(TableType.ENRICHMENT);
        table.setSourceTable("dial_usage_log");
        table.setGrainKey("event_id");
        final ColumnMappingEntity requestTime = new ColumnMappingEntity();
        requestTime.setSourceName("request_time");
        requestTime.setType(QueryFieldType.TIMESTAMP);
        given(definitions.findById("uci")).willReturn(Optional.of(table));
        given(definitions.findById("dial_usage_log")).willReturn(Optional.of(source));
        given(columns.findByTableNameOrderByOrdinal("dial_usage_log")).willReturn(List.of(requestTime));
        given(columns.findByTableNameOrderByOrdinal("uci")).willReturn(List.of());
        given(states.find("uci")).willReturn(Optional.empty());
    }
}
