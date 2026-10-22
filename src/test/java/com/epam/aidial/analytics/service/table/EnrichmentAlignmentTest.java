package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit coverage for {@link EnrichmentAlignment}: which enrichments are eligible, and which columns are adoptable. */
class EnrichmentAlignmentTest {

    private static TableDefinitionEntity source(TableWriteMode write, String partition, String identity, List<String> orderingKey) {
        final TableDefinitionEntity source = new TableDefinitionEntity();
        source.setName("src");
        source.setType(TableType.SOURCE);
        source.setWriteMode(write);
        source.setPartitionColumn(partition);
        source.setPartitionGranularity(partition == null ? null : PartitionGranularity.DAY);
        source.setIdentityColumn(identity);
        source.setOrderingKey(new ArrayList<>(orderingKey));
        return source;
    }

    /** {@code dial_usage_log}'s shape: an append source partitioned by request_time, identified by event_id. */
    private static TableDefinitionEntity usageLog() {
        return source(TableWriteMode.APPEND, "request_time", "event_id", List.of("request_time", "event_id"));
    }

    @Test
    void identityKeyedEnrichmentOfPartitionedAppendSourceIsAlignedOnThePartitionColumn() {
        assertThat(EnrichmentAlignment.eligibleColumn("event_id", usageLog())).isEqualTo("request_time");
    }

    /** An API-written source qualifies like a system one: an append source is partition-local by construction. */
    @Test
    void partitionedAppendApiSourceIsAligned() {
        final TableDefinitionEntity api = source(TableWriteMode.APPEND, "day", "id", List.of("id"));

        assertThat(EnrichmentAlignment.eligibleColumn("id", api)).isEqualTo("day");
    }

    @Test
    void upsertSourcePartitionedOutsideItsKeyIsNotAligned() {
        final TableDefinitionEntity upsert = source(TableWriteMode.UPSERT_BY_KEY, "day", "id", List.of("id"));

        assertThat(EnrichmentAlignment.eligibleColumn("id", upsert)).isNull();
    }

    @Test
    void upsertSourcePartitionedInsideItsKeyIsAligned() {
        final TableDefinitionEntity upsert = source(TableWriteMode.UPSERT_BY_KEY, "day", "id", List.of("id", "day"));

        assertThat(EnrichmentAlignment.eligibleColumn("id", upsert)).isEqualTo("day");
    }

    @Test
    void groupGrainEnrichmentIsNotAligned() {
        assertThat(EnrichmentAlignment.eligibleColumn("client_session_id", usageLog())).isNull();
    }

    @Test
    void unpartitionedSourceIsNotAligned() {
        assertThat(EnrichmentAlignment.eligibleColumn("id", source(TableWriteMode.APPEND, null, "id", List.of("id"))))
                .isNull();
    }

    /** The grain key already is the partition value: a second column of that name would fail the CREATE. */
    @Test
    void identityThatIsAlsoThePartitionColumnIsNotAligned() {
        assertThat(EnrichmentAlignment.eligibleColumn("day", source(TableWriteMode.APPEND, "day", "day", List.of("day"))))
                .isNull();
    }

    @Test
    void enrichmentAsSourceOrMissingGrainIsNotAligned() {
        final TableDefinitionEntity enrichment = usageLog();
        enrichment.setType(TableType.ENRICHMENT);

        assertThat(EnrichmentAlignment.eligibleColumn("event_id", enrichment)).isNull();
        assertThat(EnrichmentAlignment.eligibleColumn(null, usageLog())).isNull();
        assertThat(EnrichmentAlignment.eligibleColumn("event_id", null)).isNull();
    }

    private static ColumnMappingEntity column(QueryFieldType type, boolean nullable) {
        final ColumnMappingEntity column = new ColumnMappingEntity();
        column.setSourceName("request_time");
        column.setName("request_time");
        column.setType(type);
        column.setNullable(nullable);
        return column;
    }

    @Test
    void onlyNonNullablePlainColumnOfTheSourceTypeIsAdoptable() {
        final ColumnMappingEntity sourceColumn = column(QueryFieldType.TIMESTAMP, false);

        assertThat(EnrichmentAlignment.adoptable(column(QueryFieldType.TIMESTAMP, false), sourceColumn)).isTrue();
        assertThat(EnrichmentAlignment.adoptable(column(QueryFieldType.TIMESTAMP, true), sourceColumn)).isFalse();
        assertThat(EnrichmentAlignment.adoptable(column(QueryFieldType.STRING, false), sourceColumn)).isFalse();
        final ColumnMappingEntity sensitive = column(QueryFieldType.TIMESTAMP, false);
        sensitive.setSensitive(true);
        assertThat(EnrichmentAlignment.adoptable(sensitive, sourceColumn)).isFalse();
        final ColumnMappingEntity heavy = column(QueryFieldType.TIMESTAMP, false);
        heavy.setHeavy(true);
        assertThat(EnrichmentAlignment.adoptable(heavy, sourceColumn)).isFalse();
    }
}
