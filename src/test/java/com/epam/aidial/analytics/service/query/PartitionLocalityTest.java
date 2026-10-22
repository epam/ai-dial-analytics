package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.TableWriteMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The five rules of {@link PartitionLocality}, each on a hand-built definition, plus the shipped pair the
 * rule exists for: {@code dial_usage_log} (partitioned on a sort-key column) enriched by
 * {@code dial_usage_log_payload} (keyed on {@code event_id}, partitioned on the aligned {@code request_time}).
 */
class PartitionLocalityTest {

    private static TableDefinitionEntity source(TableWriteMode write, String partition, String... orderingKey) {
        final TableDefinitionEntity entity = new TableDefinitionEntity();
        entity.setName("src");
        entity.setType(TableType.SOURCE);
        entity.setWriteMode(write);
        entity.setPartitionColumn(partition);
        entity.setOrderingKey(List.of(orderingKey));
        return entity;
    }

    private static TableDefinitionEntity enrichment(String partition, String grainKey) {
        final TableDefinitionEntity entity = new TableDefinitionEntity();
        entity.setName("enr");
        entity.setType(TableType.ENRICHMENT);
        entity.setSourceTable("src");
        entity.setGrainKey(grainKey);
        entity.setPartitionColumn(partition);
        entity.setOrderingKey(List.of(grainKey));
        return entity;
    }

    private static TableDefinitionEntity dialUsageLog() {
        return source(TableWriteMode.UPSERT_BY_KEY, "request_time",
                "project_id", "deployment", "request_time", "trace_id", "core_span_id");
    }

    @Test
    void appendSourceIsAlwaysPartitionLocal() {
        // A plain MergeTree collapses nothing, so FINAL's merge scope cannot get anything wrong on it —
        // whatever it partitions by.
        assertThat(PartitionLocality.isPartitionLocal(source(TableWriteMode.APPEND, "day", "id"), null)).isTrue();
    }

    @Test
    void unpartitionedUpsertSourceIsPartitionLocal() {
        assertThat(PartitionLocality.isPartitionLocal(source(TableWriteMode.UPSERT_BY_KEY, null, "id"), null)).isTrue();
    }

    @Test
    void upsertSourcePartitionedOnAnOrderingKeyColumnIsPartitionLocal() {
        assertThat(PartitionLocality.isPartitionLocal(dialUsageLog(), null)).isTrue();
    }

    @Test
    void upsertSourcePartitionedOutsideItsOrderingKeyIsNot() {
        // Two versions of one key may carry different partition values, so nothing pins them to one partition.
        assertThat(PartitionLocality.isPartitionLocal(source(TableWriteMode.UPSERT_BY_KEY, "day", "id"), null)).isFalse();
    }

    @Test
    void enrichmentAlignedWithPartitionLocalSourceIsPartitionLocal() {
        // dial_usage_log_payload: ORDER BY event_id, partitioned on the request_time copied from its parent.
        assertThat(PartitionLocality.isPartitionLocal(enrichment("request_time", "event_id"), dialUsageLog())).isTrue();
    }

    @Test
    void unpartitionedEnrichmentIsPartitionLocal() {
        assertThat(PartitionLocality.isPartitionLocal(enrichment(null, "event_id"), dialUsageLog())).isTrue();
    }

    @Test
    void enrichmentPartitionedOnAnUnalignedColumnIsNot() {
        // Same shape as the payload table but partitioned by its own timestamp: the alignment claim is absent,
        // so nothing says a grain key's versions share a partition.
        assertThat(PartitionLocality.isPartitionLocal(enrichment("created_on", "event_id"), dialUsageLog())).isFalse();
    }

    @Test
    void alignmentDoesNotRescueEnrichmentOfCrossPartitionSource() {
        // The claim is "same value as the source for a grain key"; if the source's own versions can span
        // partitions, so can the enrichment's.
        final TableDefinitionEntity crossPartitionSource = source(TableWriteMode.UPSERT_BY_KEY, "day", "id");
        assertThat(PartitionLocality.isPartitionLocal(enrichment("day", "id"), crossPartitionSource)).isFalse();
    }

    @Test
    void enrichmentWithoutSourceToCompareAgainstIsNot() {
        assertThat(PartitionLocality.isPartitionLocal(enrichment("request_time", "event_id"), null)).isFalse();
    }
}
