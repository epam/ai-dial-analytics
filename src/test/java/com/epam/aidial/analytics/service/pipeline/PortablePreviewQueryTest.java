package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.service.pipeline.model.AggregateSpec;
import com.epam.aidial.analytics.service.pipeline.model.GroupKey;
import com.epam.aidial.analytics.service.pipeline.model.Measure;
import com.epam.aidial.analytics.service.pipeline.model.TruncUnit;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.PortableQueryRenderer;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.sql.CalciteQueryCatalog;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import com.epam.aidial.analytics.service.query.sql.StructuredQueryToSqlRenderer;
import com.epam.aidial.analytics.service.query.translate.ExprTranslator;
import com.epam.aidial.analytics.service.query.translate.FilterTranslator;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.service.query.translate.ValueExprToObjectMapper;
import com.epam.aidial.analytics.web.security.Privilege;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The preview's portable query, end to end short of ClickHouse: built from a declaration by the compilers, rendered
 * by the real renderer and read back by the real SQL front end over the {@link DialUsageLogTestModel} fixture with its
 * keyed enrichment — the same checks {@code /v1/queries/execute-sql} applies before it runs a statement.
 */
class PortablePreviewQueryTest {

    private static final String SESSION = DialUsageLogTestModel.ENRICHMENT_COLUMN;

    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
    private final ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
    private final QueryEntityModel model = DialUsageLogTestModel.modelWithKeyedEnrichment();
    private final SqlQueryFrontEnd frontEnd = frontEnd(model);
    private final AggregateCompiler compiler = new AggregateCompiler(expr, ctx);
    private final AggregateSqlCompiler sqlCompiler = new AggregateSqlCompiler(expr, new FilterTranslator(expr), ctx);
    private final PortableQueryRenderer renderer = new PortableQueryRenderer(catalog(model),
            new StructuredQueryBuilder(expr, new FilterTranslator(expr)), ctx,
            new StructuredQueryToSqlRenderer(new ValueExprToObjectMapper()), frontEnd);

    private static CatalogQueryService catalog(QueryEntityModel planned) {
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any())).thenReturn(List.of(DialUsageLogTestModel.ENTITY));
        when(catalog.findModel(eq(DialUsageLogTestModel.ENTITY), any())).thenReturn(Optional.of(planned));
        return catalog;
    }

    private static SqlQueryFrontEnd frontEnd(QueryEntityModel planned) {
        return new SqlQueryFrontEnd(new CalciteQueryCatalog(catalog(planned)));
    }

    /** A predicate as the gate hands it over: parsed by the front end, translated, with its node kept. */
    private AggregateCompiler.CompiledFilter predicate(String sql) {
        final FilterNode node = frontEnd.translate("SELECT event_id FROM dial_usage_log WHERE " + sql, Privilege.TABLE_GET)
                .query().filter();
        return new AggregateCompiler.CompiledFilter(DSL.condition(sql), Set.of(), node);
    }

    private static GroupKey plain(String column) {
        final GroupKey key = new GroupKey();
        key.setColumn(column);
        return key;
    }

    private static GroupKey day(String column, String as) {
        final GroupKey.Trunc trunc = new GroupKey.Trunc();
        trunc.setColumn(column);
        trunc.setUnit(TruncUnit.DAY);
        final GroupKey key = new GroupKey();
        key.setTrunc(trunc);
        key.setAs(as);
        return key;
    }

    private static Measure measure(String name, String fn, String column, boolean distinct, String where) {
        final Measure measure = new Measure();
        measure.setName(name);
        measure.setFn(fn);
        measure.setColumn(column);
        measure.setDistinct(distinct);
        measure.setWhere(where);
        return measure;
    }

    private static AggregateSpec spec(List<GroupKey> keys, List<Measure> measures) {
        final AggregateSpec spec = new AggregateSpec();
        spec.setGroupBy(keys);
        spec.setMeasures(measures);
        return spec;
    }

    private PortableQueryRenderer.Portable form(AggregateSpec spec, AggregateCompiler.CompiledFilter filter,
            Map<String, AggregateCompiler.CompiledFilter> measureFilters) {
        return renderer.tryRender(compiler.portablePreview(spec, DialUsageLogTestModel.ENTITY, filter, measureFilters, 5));
    }

    private static void assertCarriesNoPhysicalDetail(String sql) {
        assertThat(sql).doesNotContainIgnoringCase("join").doesNotContainIgnoringCase("settings")
                .doesNotContain("__").doesNotContain("toStartOfInterval").doesNotContain("argMax");
    }

    @Test
    void formDeclarationRendersAsItsOwnAggregateOverTheEntity() {
        final AggregateSpec spec = spec(List.of(plain("deployment"), day("request_time", "day")), List.of(
                measure("tokens", "sum", "total_tokens", false, null),
                measure("errors", "count", null, false, "response_status >= 400"),
                measure("traces", "count", "trace_id", true, null)));

        final PortableQueryRenderer.Portable portable = form(spec, predicate("chat_id <> ''"),
                Map.of("errors", predicate("response_status >= 400")));

        assertThat(portable.refusal()).isNull();
        assertThat(portable.sql()).isEqualTo("SELECT \"deployment\", date_trunc('day', \"request_time\") AS \"day\","
                + " sum(\"total_tokens\") AS \"tokens\","
                + " count(CASE WHEN \"response_status\" >= 400 THEN 1 END) AS \"errors\","
                + " count(DISTINCT \"trace_id\") AS \"traces\""
                + " FROM \"dial_usage_log\" FINAL WHERE \"chat_id\" <> ''"
                + " GROUP BY \"deployment\", \"day\" ORDER BY \"deployment\" ASC, \"day\" ASC LIMIT 5");
        assertCarriesNoPhysicalDetail(portable.sql());
        assertThat(portable.query().entity()).isEqualTo(DialUsageLogTestModel.ENTITY);
    }

    @Test
    void anEnrichmentColumnIsNamedAsTheEntityExposesItWithNoJoin() {
        final AggregateSpec spec = spec(List.of(plain(SESSION)), List.of(measure("turns", "count", null, false, null)));

        final PortableQueryRenderer.Portable portable = form(spec, predicate("\"" + SESSION + "\" <> ''"), Map.of());

        assertThat(portable.refusal()).isNull();
        assertThat(portable.sql()).contains("\"" + SESSION + "\"").contains("FROM \"dial_usage_log\"");
        assertCarriesNoPhysicalDetail(portable.sql());
    }

    /** The gate resolves {@code fn} against the query catalog's aggregates, so each must also run as a query. */
    @ParameterizedTest
    @ValueSource(strings = {"count", "sum", "avg", "min", "max", "group_uniq_array"})
    void everyMeasureFunctionTheGateAdmitsIsPortable(String fn) {
        final String column = "group_uniq_array".equals(fn) ? "deployment" : "total_tokens";
        final AggregateSpec spec = spec(List.of(plain("project_id")), List.of(
                measure("plain", fn, column, false, null),
                measure("scoped", fn, column, false, "response_status >= 400")));

        final PortableQueryRenderer.Portable portable = form(spec, AggregateCompiler.CompiledFilter.NONE,
                Map.of("scoped", predicate("response_status >= 400")));

        assertThat(portable.refusal()).isNull();
        assertThat(portable.sql()).contains(fn + "(\"" + column + "\") AS \"plain\"")
                .contains(fn + "(CASE WHEN \"response_status\" >= 400 THEN \"" + column + "\" END) AS \"scoped\"");
    }

    @Test
    void bucketNamedLikeInputFieldIsRefusedAndAttributed() {
        final AggregateSpec spec = spec(List.of(day("request_time", "request_time")),
                List.of(measure("calls", "count", null, false, null)));

        final PortableQueryRenderer.Portable portable = form(spec, AggregateCompiler.CompiledFilter.NONE, Map.of());

        assertThat(portable.rendered()).isFalse();
        assertThat(portable.query()).isNull();
        assertThat(portable.subject()).isEqualTo("request_time");
        assertThat(portable.refusal()).contains("request_time");
    }

    @Test
    void predicateWithoutItsParsedFormIsAnInvariantFailure() {
        final AggregateSpec spec = spec(List.of(plain("deployment")), List.of(measure("calls", "count", null, false, null)));

        org.assertj.core.api.Assertions.assertThatIllegalStateException().isThrownBy(() -> compiler.portablePreview(spec,
                DialUsageLogTestModel.ENTITY, new AggregateCompiler.CompiledFilter(DSL.condition("1 = 1"), Set.of()),
                Map.of(), 5));
    }

    @Test
    void anSqlDeclarationRendersItsBlockUnderTheTargetColumns() {
        final AggregateSqlValidator.Analysis analysis = new AggregateSqlValidator.Analysis(
                frontEnd.planInsert("INSERT INTO usage_daily (day, deployment, tokens) SELECT date_trunc('day', request_time),"
                        + " deployment, sum(total_tokens) FROM dial_usage_log WHERE chat_id <> '' GROUP BY 1, 2",
                        Privilege.TABLE_READ),
                List.of(0, 1), new AggregateSqlValidator.Bucket(0, TruncUnit.DAY));

        final PortableQueryRenderer.Portable portable = renderer.tryRender(sqlCompiler.portablePreview(analysis, 5));

        assertThat(portable.refusal()).isNull();
        assertThat(portable.sql()).isEqualTo("SELECT date_trunc('day', \"request_time\") AS \"day\", \"deployment\","
                + " sum(\"total_tokens\") AS \"tokens\" FROM \"dial_usage_log\" FINAL WHERE \"chat_id\" <> ''"
                + " GROUP BY \"day\", \"deployment\" ORDER BY \"day\" ASC, \"deployment\" ASC LIMIT 5");
    }

    @Test
    void anSqlMeasureNamedLikeAnInputFieldKeepsItsTargetNameRatherThanThePositionalAlias() {
        final AggregateSqlValidator.Analysis analysis = new AggregateSqlValidator.Analysis(
                frontEnd.planInsert("INSERT INTO usage (deployment, prompt_tokens) SELECT deployment, sum(total_tokens)"
                        + " FROM dial_usage_log GROUP BY 1", Privilege.TABLE_READ),
                List.of(0), null);

        final PortableQueryRenderer.Portable portable = renderer.tryRender(sqlCompiler.portablePreview(analysis, 5));

        assertThat(portable.refusal()).isNull();
        assertThat(portable.sql()).contains("sum(\"total_tokens\") AS \"prompt_tokens\"").doesNotContain("__c");
    }
}
