package com.epam.aidial.analytics.service.query.repository;

import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.clickhouse.ClickHouseClientWrapper;
import com.epam.aidial.analytics.clickhouse.FinalSettings;
import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.translate.ExprTranslator;
import com.epam.aidial.analytics.service.query.translate.FilterTranslator;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.service.query.translate.ValueExprToObjectMapper;
import com.epam.aidial.analytics.service.security.RowPolicyFilter;
import com.epam.aidial.analytics.service.security.RowPolicyFilterStubs;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Slice coverage of the deduplication {@link StructuredQueryExecutor} attaches. A user read — the row query
 * and its {@code count(*)} total, a composite statement — carries no {@code final} setting: {@code FINAL} is
 * rendered into its SQL where the query reads final, and
 * {@code do_not_merge_across_partitions_select_final = 1} is added exactly when it reads a partition-local
 * entity final. A pipeline read (every scan) carries {@code final = 1}, plus the partition switch when its
 * model is partition-local. Uses the real translation stack (builder + connection-less CLICKHOUSE render context)
 * and mocks only the client wrapper, capturing the {@link QuerySettings} the executor passes.
 */
@ExtendWith(MockitoExtension.class)
class StructuredQueryExecutorTest {

    /** The key {@code QuerySettings.serverSetting("final", ...)} stores under in {@code getAllSettings()}. */
    private static final String FINAL_KEY = FinalSettings.FINAL_KEY;

    /** The key the per-partition merge switch is stored under. */
    private static final String NO_MERGE_KEY = FinalSettings.NO_MERGE_ACROSS_PARTITIONS_KEY;

    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
    private final QueryEntityModel model = DialUsageLogTestModel.model();
    /** The shipped entity as the catalog describes it: its source is upsert_by_key, so it may be read FINAL. */
    private final QueryEntityModel collapsing = collapsing(model);
    private final StructuredQueryBuilder builder = newBuilder();

    @Mock
    private ClickHouseClientWrapper clickHouse;

    private static QueryEntityModel collapsing(QueryEntityModel m) {
        return new QueryEntityModel(m.entity(), m.table(), m.bindings(), m.enrichments(), m.baseSchema(), m.tagOrder(),
                m.partitionLocal(), true);
    }

    private List<String> sqlOfEveryStatement(int statements) {
        final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(clickHouse, times(statements)).query(sql.capture(), any(QuerySettings.class));
        return sql.getAllValues();
    }

    private static StructuredQueryBuilder newBuilder() {
        ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
        return new StructuredQueryBuilder(expr, new FilterTranslator(expr));
    }

    /** Row mode with include_total, which forces both executions: the row query and the count(*) total query. */
    private static StructuredQuery rowQueryWithTotal() {
        return new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.ROW,
                false,
                List.of(new OutputColumn(new FieldExpr("project_id"), null)),
                null,
                null,
                null,
                new OffsetPage(0L, 10, true));
    }

    private SelectQuery<Record> scanStatement() {
        final SelectQuery<Record> scan = ctx.selectQuery();
        scan.addFrom(DSL.table(DSL.name("dial_usage_log")));
        scan.addSelect(DSL.field(DSL.name("dial_usage_log", "project_id")));
        return scan;
    }

    private List<Map<String, Object>> settingsOfEveryStatement(int statements) {
        final ArgumentCaptor<QuerySettings> settings = ArgumentCaptor.forClass(QuerySettings.class);
        verify(clickHouse, never()).query(anyString());
        verify(clickHouse, times(statements)).query(anyString(), settings.capture());
        return settings.getAllValues().stream().map(QuerySettings::getAllSettings).toList();
    }

    @Test
    void rowQueryAndCountQueryBothDeduplicateInsideEachPartitionForPartitionLocalEntity() {
        // The shipped dial_usage_log entity read final (an absent `final` over a collapsing source): FINAL is in
        // both statements' SQL, not in a setting, and every table the entity can reach keeps a key's versions in
        // one partition, so both dedupe per partition — which is what lets ClickHouse keep pruning partitions
        // under FINAL instead of merging every part of the payload table.
        given(clickHouse.query(anyString(), any(QuerySettings.class))).willReturn(List.of());

        new StructuredQueryExecutor(builder, ctx, clickHouse, RowPolicyFilterStubs.inert()).execute(collapsing, rowQueryWithTotal());

        assertThat(settingsOfEveryStatement(2)).allSatisfy(s -> assertThat(s)
                .doesNotContainKey(FINAL_KEY)
                .containsEntry(NO_MERGE_KEY, "1"));
        assertThat(sqlOfEveryStatement(2)).allSatisfy(sql -> assertThat(sql).contains("\"dial_usage_log\" FINAL"));
    }

    @Test
    void crossPartitionEntityWithholdsThePartitionLocalSetting() {
        // The catalog could not vouch for one of the entity's tables: FINAL stays, the merge scope stays
        // global — slower, never wrong — on every statement of the read.
        given(clickHouse.query(anyString(), any(QuerySettings.class))).willReturn(List.of());

        new StructuredQueryExecutor(builder, ctx, clickHouse, RowPolicyFilterStubs.inert())
                .execute(collapsing(DialUsageLogTestModel.crossPartitionModel()), rowQueryWithTotal());

        assertThat(settingsOfEveryStatement(2)).allSatisfy(s -> assertThat(s)
                .doesNotContainKey(FINAL_KEY)
                .doesNotContainKey(NO_MERGE_KEY));
        assertThat(sqlOfEveryStatement(2)).allSatisfy(sql -> assertThat(sql).contains(" FINAL"));
    }

    @Test
    void rawReadCarriesNeitherFinalNorThePartitionSwitch() {
        // `"final": false` over a partition-local entity: nothing is read under FINAL, so the partition switch,
        // which only affects FINAL reads, is not attached either.
        given(clickHouse.query(anyString(), any(QuerySettings.class))).willReturn(List.of());

        new StructuredQueryExecutor(builder, ctx, clickHouse, RowPolicyFilterStubs.inert()).execute(collapsing, rowQueryWithTotal().withFinalRead(false));

        assertThat(settingsOfEveryStatement(2)).allSatisfy(s -> assertThat(s)
                .doesNotContainKey(FINAL_KEY)
                .doesNotContainKey(NO_MERGE_KEY));
        assertThat(sqlOfEveryStatement(2)).allSatisfy(sql -> assertThat(sql).doesNotContain("FINAL"));
    }

    @Test
    void forcedBuilderReadsFinalWhateverTheQuerySays() {
        given(clickHouse.query(anyString(), any(QuerySettings.class))).willReturn(List.of());
        final ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
        final StructuredQueryBuilder forced = new StructuredQueryBuilder(expr, new FilterTranslator(expr), true);

        new StructuredQueryExecutor(forced, ctx, clickHouse, RowPolicyFilterStubs.inert()).execute(collapsing, rowQueryWithTotal().withFinalRead(false));

        assertThat(settingsOfEveryStatement(2)).allSatisfy(s -> assertThat(s).containsEntry(NO_MERGE_KEY, "1"));
        assertThat(sqlOfEveryStatement(2)).allSatisfy(sql -> assertThat(sql).contains("\"dial_usage_log\" FINAL"));
    }

    @Test
    void callerSettingsMergeOverTheStandingFinalSetting() {
        // A caller adds to the deduplication rather than replacing it. A budget that silently dropped
        // final=1 would change what the preview *reports*, not just what it costs — and the numbers would
        // be wrong in the direction of showing more rows than any read of the same data returns.
        new StructuredQueryExecutor(builder, ctx, clickHouse, RowPolicyFilterStubs.inert())
                .executeScan(scanStatement(), new QuerySettings().serverSetting("max_execution_time", "30"));

        final ArgumentCaptor<QuerySettings> settings = ArgumentCaptor.forClass(QuerySettings.class);
        verify(clickHouse).query(anyString(), settings.capture());
        assertThat(settings.getValue().getAllSettings())
                .containsEntry(FINAL_KEY, "1")
                .containsEntry("clickhouse_setting_max_execution_time", "30");
    }

    @Test
    void scanWithoutCallerSettingsStillDeduplicates() {
        new StructuredQueryExecutor(builder, ctx, clickHouse, RowPolicyFilterStubs.inert()).executeScan(scanStatement());

        final ArgumentCaptor<QuerySettings> settings = ArgumentCaptor.forClass(QuerySettings.class);
        verify(clickHouse).query(anyString(), settings.capture());
        assertThat(settings.getValue().getAllSettings()).containsEntry(FINAL_KEY, "1");
    }

    /**
     * A pipeline read reaches ClickHouse through the settings-carrying call with its query id intact: the
     * no-settings {@code query(String)} would drop the budget and the id together.
     */
    @Test
    void pipelineReadCarriesItsQueryIdThroughTheSettingsCall() {
        new StructuredQueryExecutor(builder, ctx, clickHouse, RowPolicyFilterStubs.inert())
                .executeScan(scanStatement(), new QuerySettings().setQueryId("adas-p1-scan-1"), model);

        final ArgumentCaptor<QuerySettings> settings = ArgumentCaptor.forClass(QuerySettings.class);
        verify(clickHouse, never()).query(anyString());
        verify(clickHouse).query(anyString(), settings.capture());
        assertThat(settings.getValue().getQueryId()).isEqualTo("adas-p1-scan-1");
    }

    @Test
    void modelAwareScanFollowsTheModelsPartitionLocality() {
        final StructuredQueryExecutor executor = new StructuredQueryExecutor(builder, ctx, clickHouse, RowPolicyFilterStubs.inert());

        executor.executeScan(scanStatement(), new QuerySettings(), model);
        executor.executeScan(scanStatement(), new QuerySettings(), DialUsageLogTestModel.crossPartitionModel());

        final List<Map<String, Object>> sent = settingsOfEveryStatement(2);
        assertThat(sent.get(0)).containsEntry(FINAL_KEY, "1").containsEntry(NO_MERGE_KEY, "1");
        assertThat(sent.get(1)).containsEntry(FINAL_KEY, "1").doesNotContainKey(NO_MERGE_KEY);
    }

    @Test
    void entryPointsWithoutModelStayCrossPartition() {
        // No model, no claim: the model-less scans keep final=1 alone, and the two-argument composite — a user
        // read, whose FINAL lives in its SQL — carries nothing. A composite plan asserts partition locality
        // explicitly, having ANDed it over every entity it reads final.
        final StructuredQueryExecutor executor = new StructuredQueryExecutor(builder, ctx, clickHouse, RowPolicyFilterStubs.inert());

        executor.executeScan(scanStatement());
        executor.executeScan(scanStatement(), new QuerySettings());
        executor.executeComposite(scanStatement(), new QuerySettings());
        executor.executeComposite(scanStatement(), new QuerySettings(), true);

        final List<Map<String, Object>> sent = settingsOfEveryStatement(4);
        assertThat(sent.subList(0, 2)).allSatisfy(s -> assertThat(s)
                .containsEntry(FINAL_KEY, "1")
                .doesNotContainKey(NO_MERGE_KEY));
        assertThat(sent.get(2)).doesNotContainKey(FINAL_KEY).doesNotContainKey(NO_MERGE_KEY);
        assertThat(sent.get(3)).doesNotContainKey(FINAL_KEY).containsEntry(NO_MERGE_KEY, "1");
    }

    @Test
    void pageNamesItsProjectedColumnsEvenWithNoRows() {
        // The CSV endpoints emit a header from the page's columns; with no rows there is no row to read
        // the key set from, so the page itself must carry the projection — for the DSL page and the
        // composite (execute-sql) page alike.
        given(clickHouse.query(anyString(), any(QuerySettings.class))).willReturn(List.of());
        final StructuredQueryExecutor executor = new StructuredQueryExecutor(builder, ctx, clickHouse, RowPolicyFilterStubs.inert());
        final StructuredQuery aliased = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.ROW,
                false,
                List.of(new OutputColumn(new FieldExpr("project_id"), "project"),
                        new OutputColumn(new FieldExpr("deployment"), null)),
                null,
                null,
                null,
                null);

        final QueryResultPage page = executor.execute(model, aliased);
        final QueryResultPage composite = executor.executeComposite(scanStatement(), new QuerySettings());

        assertThat(page.rows()).isEmpty();
        assertThat(page.columns()).containsExactly("project", "deployment");
        assertThat(composite.rows()).isEmpty();
        assertThat(composite.columns()).containsExactly("project_id");
    }

    @Test
    void userReadsCarryTheRowPolicyFilterAndPipelineScansNever() {
        // The one chokepoint every user surface (DSL, SQL, CSV, Flight) executes through: the row query and its
        // total carry the caller's filter, a composite statement does too, and a pipeline scan never asks.
        given(clickHouse.query(anyString(), any(QuerySettings.class))).willReturn(List.of());
        final RowPolicyFilter confining = org.mockito.Mockito.mock(RowPolicyFilter.class);
        given(confining.apply(any(QuerySettings.class))).willAnswer(inv ->
                ((QuerySettings) inv.getArgument(0)).serverSetting(RowPolicyFilter.SETTING, "{'default.t':'1 = 0'}"));
        final StructuredQueryExecutor executor = new StructuredQueryExecutor(builder, ctx, clickHouse, confining);
        final String filterKey = com.clickhouse.client.api.ClientConfigProperties.serverSetting(RowPolicyFilter.SETTING);

        executor.execute(collapsing, rowQueryWithTotal());
        executor.executeComposite(scanStatement(), new QuerySettings());
        executor.executeScan(scanStatement(), new QuerySettings(), collapsing);

        final List<Map<String, Object>> sent = settingsOfEveryStatement(4);
        assertThat(sent.subList(0, 3)).allSatisfy(s -> assertThat(s).containsEntry(filterKey, "{'default.t':'1 = 0'}"));
        assertThat(sent.get(3)).doesNotContainKey(filterKey);
        verify(confining, times(2)).apply(any(QuerySettings.class));
    }
}
