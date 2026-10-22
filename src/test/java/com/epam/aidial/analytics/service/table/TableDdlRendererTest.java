package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.table.TableDdlRenderer.CreateSpec;
import com.epam.aidial.analytics.service.table.TableDdlRenderer.DdlColumn;
import com.epam.aidial.analytics.service.table.TableDdlRenderer.TtlDdl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit coverage for {@link TableDdlRenderer} — CREATE/ALTER/DROP rendering and identifier guarding. */
class TableDdlRendererTest {

    private final TableDdlRenderer renderer = new TableDdlRenderer();

    @Test
    void rendersSourceAsMergeTreeWithOrderBy() {
        final String sql = renderer.renderCreate("user_region", new CreateSpec(
                TableType.SOURCE, TableWriteMode.APPEND,
                List.of(new DdlColumn("user_id", "String"), new DdlColumn("region", "String")),
                List.of("user_id"), null, null, null));

        assertThat(sql)
                .contains("CREATE TABLE `user_region`")
                .contains("`user_id` String")
                .contains("`region` String")
                .contains("ENGINE = MergeTree")
                .contains("ORDER BY (`user_id`)")
                .doesNotContain("PARTITION BY")
                .doesNotContain("TTL")
                // An append source carries no version column: it accumulates rows rather than collapsing
                // them, so the DDL is exactly what it was before the write facet existed.
                .doesNotContain("_updated_at");
    }

    @Test
    void rendersUpsertByKeySourceAsReplacingMergeTreeOverOrderingKey() {
        final String sql = renderer.renderCreate("usage_daily", new CreateSpec(
                TableType.SOURCE, TableWriteMode.UPSERT_BY_KEY,
                List.of(new DdlColumn("deployment", "String"), new DdlColumn("day", "Date"),
                        new DdlColumn("call_count", "Int64")),
                List.of("deployment", "day"), null, null, null));

        assertThat(sql)
                .contains("`call_count` Int64")
                .contains("`_updated_at` DateTime64(3) DEFAULT now64(3)")
                .contains("ENGINE = ReplacingMergeTree(`_updated_at`)")
                // The ordering key is the unique key: a re-written (deployment, day) collapses to the
                // latest version rather than adding a second row.
                .contains("ORDER BY (`deployment`, `day`)");
    }

    @Test
    void rendersUpsertByKeySourceWithPartitionAndTtl() {
        final String sql = renderer.renderCreate("usage_daily", new CreateSpec(
                TableType.SOURCE, TableWriteMode.UPSERT_BY_KEY,
                List.of(new DdlColumn("day", "Date"), new DdlColumn("cost", "Decimal(38, 12)")),
                List.of("day"), "day", PartitionGranularity.MONTH,
                new TtlDdl("day", 10, TtlUnit.YEAR, false)));

        assertThat(sql)
                .contains("ENGINE = ReplacingMergeTree(`_updated_at`)")
                .contains("PARTITION BY toYYYYMM(`day`)")
                .endsWith("TTL `day` + INTERVAL 10 YEAR");
    }

    /** ClickHouse has no {@code toYYYY}: a yearly partition is {@code toYear}. */
    @Test
    void rendersYearlyPartitionWithToYear() {
        final String sql = renderer.renderCreate("events", new CreateSpec(
                TableType.SOURCE, TableWriteMode.APPEND,
                List.of(new DdlColumn("request_time", "DateTime64(3)")),
                List.of("request_time"), "request_time", PartitionGranularity.YEAR, null));

        assertThat(sql).contains("PARTITION BY toYear(`request_time`)");
    }

    @Test
    void rendersIndexDropAndNamedLookupIndex() {
        assertThat(renderer.dropIndexCommand("idx_session")).isEqualTo("DROP INDEX IF EXISTS `idx_session`");
        assertThat(renderer.addLookupIndexCommand("session", "idx_session_2"))
                .isEqualTo("ADD INDEX IF NOT EXISTS `idx_session_2` `session` TYPE bloom_filter(0.001) GRANULARITY 1");
    }

    @Test
    void rendersSourcePartitionByExpression() {
        final String sql = renderer.renderCreate("events", new CreateSpec(
                TableType.SOURCE, TableWriteMode.APPEND,
                List.of(new DdlColumn("request_time", "DateTime64(3)"), new DdlColumn("v", "Int64")),
                List.of("request_time"), "request_time", PartitionGranularity.MONTH, null));

        assertThat(sql).contains("PARTITION BY toYYYYMM(`request_time`)");
    }

    @Test
    void rendersTtlClauseForDateColumnBare() {
        final String sql = renderer.renderCreate("events", new CreateSpec(
                TableType.SOURCE, TableWriteMode.APPEND,
                List.of(new DdlColumn("event_date", "Date"), new DdlColumn("v", "Int64")),
                List.of("event_date"), null, null,
                new TtlDdl("event_date", 6, TtlUnit.MONTH, false)));

        assertThat(sql).endsWith("TTL `event_date` + INTERVAL 6 MONTH");
    }

    @Test
    void rendersTtlClauseForTimestampColumnWrapped() {
        final String sql = renderer.renderCreate("events", new CreateSpec(
                TableType.SOURCE, TableWriteMode.APPEND,
                List.of(new DdlColumn("request_time", "DateTime64(3)"), new DdlColumn("v", "Int64")),
                List.of("request_time"), null, null,
                new TtlDdl("request_time", 1, TtlUnit.YEAR, true)));

        assertThat(sql).endsWith("TTL toDateTime(`request_time`) + INTERVAL 1 YEAR");
    }

    @Test
    void rendersTtlClauseAfterOrderByOnEnrichment() {
        final String sql = renderer.renderCreate("widget_labels", new CreateSpec(
                TableType.ENRICHMENT, TableWriteMode.APPEND,
                List.of(new DdlColumn("widget_id", "String"), new DdlColumn("seen_at", "DateTime64(3)")),
                List.of("widget_id"), null, null,
                new TtlDdl("seen_at", 30, TtlUnit.DAY, true)));

        assertThat(sql).contains("ORDER BY (`widget_id`)\nTTL toDateTime(`seen_at`) + INTERVAL 30 DAY");
    }

    @Test
    void rendersEnrichmentAsReplacingMergeTreeWithUpdatedAt() {
        final String sql = renderer.renderCreate("widget_labels", new CreateSpec(
                TableType.ENRICHMENT, TableWriteMode.APPEND,
                List.of(new DdlColumn("widget_id", "String")),
                List.of("widget_id"), null, null, null));

        assertThat(sql)
                .contains("`widget_id` String")
                .contains("`_updated_at` DateTime64(3)")
                .contains("ENGINE = ReplacingMergeTree(`_updated_at`)")
                .contains("ORDER BY (`widget_id`)");
    }

    @Test
    void rendersSingleMultiCommandAlterInCanonicalOrder() {
        final String sql = renderer.renderAlter("t", List.of(
                renderer.dropColumnCommand("old"),
                renderer.renameColumnCommand("label", "category"),
                renderer.addColumnCommand(new DdlColumn("v", "Nullable(String)")),
                renderer.modifyTtlCommand(new TtlDdl("created_at", 90, TtlUnit.DAY, true))));

        assertThat(sql).isEqualTo("ALTER TABLE `t` DROP COLUMN `old`, RENAME COLUMN `label` TO `category`, "
                + "ADD COLUMN `v` Nullable(String), MODIFY TTL toDateTime(`created_at`) + INTERVAL 90 DAY");
    }

    @Test
    void rendersEachTtlUnitKeyword() {
        assertThat(renderer.modifyTtlCommand(new TtlDdl("d", 1, TtlUnit.HOUR, false)))
                .isEqualTo("MODIFY TTL `d` + INTERVAL 1 HOUR");
        assertThat(renderer.modifyTtlCommand(new TtlDdl("d", 2, TtlUnit.DAY, false)))
                .isEqualTo("MODIFY TTL `d` + INTERVAL 2 DAY");
        assertThat(renderer.modifyTtlCommand(new TtlDdl("d", 3, TtlUnit.WEEK, false)))
                .isEqualTo("MODIFY TTL `d` + INTERVAL 3 WEEK");
        assertThat(renderer.modifyTtlCommand(new TtlDdl("d", 4, TtlUnit.MONTH, false)))
                .isEqualTo("MODIFY TTL `d` + INTERVAL 4 MONTH");
        assertThat(renderer.modifyTtlCommand(new TtlDdl("d", 5, TtlUnit.YEAR, false)))
                .isEqualTo("MODIFY TTL `d` + INTERVAL 5 YEAR");
    }

    @Test
    void rendersRemoveTtlCommand() {
        assertThat(renderer.renderAlter("t", List.of(renderer.removeTtlCommand())))
                .isEqualTo("ALTER TABLE `t` REMOVE TTL");
    }

    @Test
    void rejectsAlterWithoutCommands() {
        assertThatThrownBy(() -> renderer.renderAlter("t", List.of()))
                .isInstanceOf(TableValidationException.class);
    }

    @Test
    void rendersDropTableIfExists() {
        assertThat(renderer.renderDrop("user_region")).isEqualTo("DROP TABLE IF EXISTS `user_region`");
    }

    @Test
    void rejectsInvalidTableIdentifier() {
        final CreateSpec spec = new CreateSpec(
                TableType.SOURCE, TableWriteMode.APPEND,
                List.of(new DdlColumn("c", "String")), List.of("c"), null, null, null);
        assertThatThrownBy(() -> renderer.renderCreate("bad name", spec))
                .isInstanceOf(TableValidationException.class);
    }

    @Test
    void rejectsEmptyColumnsForSource() {
        final CreateSpec spec = new CreateSpec(
                TableType.SOURCE, TableWriteMode.APPEND, List.of(), List.of("c"), null, null, null);
        assertThatThrownBy(() -> renderer.renderCreate("t", spec)).isInstanceOf(TableValidationException.class);
    }

    @Test
    void rejectsEmptyOrderingKey() {
        final CreateSpec spec = new CreateSpec(
                TableType.SOURCE, TableWriteMode.APPEND,
                List.of(new DdlColumn("c", "String")), List.of(), null, null, null);
        assertThatThrownBy(() -> renderer.renderCreate("t", spec)).isInstanceOf(TableValidationException.class);
    }
}
