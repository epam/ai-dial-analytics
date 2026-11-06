package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.translate.ExprTranslator;
import com.epam.aidial.analytics.service.query.translate.FilterTranslator;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.service.query.translate.ValueExprToObjectMapper;
import org.jooq.DSLContext;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The {@code rest-sql-endpoint} spec's "SQL execution inherits the query budget": a SQL statement is translated into
 * the structured query and rendered through the same read shaping as its DSL equivalent, so a projection-only column
 * of the partition-aligned payload is fetched for the returned page only.
 */
class SqlReadShapingRenderTest {

    private final QueryEntityModel model = DialUsageLogTestModel.modelWithAlignedPayload();
    private final SqlQueryFrontEnd frontEnd = newFrontEnd(model);
    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
    private final StructuredQueryBuilder builder = newBuilder();

    private static SqlQueryFrontEnd newFrontEnd(QueryEntityModel model) {
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any())).thenReturn(List.of(DialUsageLogTestModel.ENTITY));
        when(catalog.findModel(eq(DialUsageLogTestModel.ENTITY), any())).thenReturn(Optional.of(model));
        return new SqlQueryFrontEnd(new CalciteQueryCatalog(catalog));
    }

    private static StructuredQueryBuilder newBuilder() {
        final ExprTranslator expressions = new ExprTranslator(new ValueExprToObjectMapper());
        return new StructuredQueryBuilder(expressions, new FilterTranslator(expressions));
    }

    @Test
    void projectionOnlyHeaderColumnIsFetchedForThePage() {
        final SqlTranslation translation = frontEnd.translate("SELECT request_time,"
                + " \"dial_usage_log_payload.request_tags\", trace_id FROM dial_usage_log"
                + " WHERE request_time >= TIMESTAMP '2026-01-01 00:00:00' ORDER BY request_time DESC LIMIT 25");

        final String sql = builder.build(ctx, model, translation.query(), StructuredQueryBuilder.MAX_LIMIT)
                .getSQL(ParamType.INLINED);

        assertThat(sql)
                .containsIgnoringCase("left outer join \"dial_usage_log_payload\" on")
                .containsIgnoringCase("(\"dial_usage_log_payload\".\"event_id\", \"dial_usage_log_payload\".\"request_time\")"
                        + " in (select \"__adas_lm_key\", \"__adas_lm_part\" from (")
                .containsIgnoringCase("\"dial_usage_log\".\"event_id\" is null")
                .endsWithIgnoringCase("limit 25 offset 0");
        final String pageBlock = sql.substring(sql.toLowerCase().indexOf("from (select"));
        assertThat(pageBlock.substring(0, pageBlock.indexOf("\"__adas_lm\")")))
                .doesNotContainIgnoringCase("dial_usage_log_payload")
                .containsIgnoringCase("limit 25");
    }
}
