package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.query.QuerySettings;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settings a pipeline read runs under, and their rendering into the statement a preview prints — both from
 * one merge, so the printed statement cannot carry different settings from the one that ran.
 */
class FinalSettingsTest {

    @Test
    void pipelineReadMergesTheCallersSettingsOverTheDeduplication() {
        final QuerySettings extra = new QuerySettings().serverSetting("max_memory_usage", "100");

        assertThat(FinalSettings.pipelineRead(extra, true).getAllSettings())
                .containsEntry(FinalSettings.FINAL_KEY, "1")
                .containsEntry(FinalSettings.TRANSFORM_NULL_IN_KEY, "0")
                .containsEntry(FinalSettings.NO_MERGE_ACROSS_PARTITIONS_KEY, "1")
                .containsEntry("clickhouse_setting_max_memory_usage", "100");
    }

    @Test
    void renderAppendsTheServerSettingsSortedAndNeverTheQueryId() {
        final QuerySettings extra = new QuerySettings()
                .serverSetting("max_memory_usage", "100")
                .serverSetting("max_execution_time", "30");
        extra.setQueryId("adas-p-preview-1");

        assertThat(FinalSettings.render("SELECT 1", extra, false)).isEqualTo("SELECT 1 SETTINGS final = 1, "
                + "max_execution_time = 30, max_memory_usage = 100, transform_null_in = 0");
    }

    @Test
    void renderNamesThePerPartitionMergeOnlyForPartitionLocalRead() {
        assertThat(FinalSettings.render("SELECT 1", new QuerySettings(), true))
                .isEqualTo("SELECT 1 SETTINGS do_not_merge_across_partitions_select_final = 1, final = 1, "
                        + "transform_null_in = 0");
    }
}
